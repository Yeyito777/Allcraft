package net.minecraft.allcraft;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.lang.instrument.ClassDefinition;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import org.slf4j.Logger;

/** Applies arbitrary class-file overlays to the running Allcraft JVM. */
public final class AllcraftRuntime {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Map<Class<?>, byte[]> BASE_DEFINITIONS = new IdentityHashMap<>();
    private static final Map<Class<?>, byte[]> CURRENT_DEFINITIONS = new IdentityHashMap<>();
    private static final List<JarFile> APPENDED_ARTIFACTS = new ArrayList<>();

    private AllcraftRuntime() {
    }

    public static synchronized ApplyResult apply(Path artifact, String expectedSha256) throws Exception {
        long startedAt = System.nanoTime();
        long gcCountBefore = gcCount();
        long gcMillisBefore = gcMillis();
        Path normalized = artifact.toAbsolutePath().normalize();
        if (!Files.isRegularFile(normalized)) {
            throw new IOException("Runtime artifact does not exist: " + normalized);
        }

        String actualSha256 = sha256(normalized);
        if (!actualSha256.equals(expectedSha256)) {
            throw new IOException("Runtime artifact SHA-256 mismatch for " + normalized.getFileName());
        }

        Map<String, byte[]> classes = readClasses(normalized);
        List<String> entrypoints = readEntrypoints(normalized);
        if (classes.isEmpty() && entrypoints.isEmpty()) {
            return result(0, 0, 0, entrypoints, startedAt, 0L, gcCountBefore, gcMillisBefore);
        }

        Instrumentation instrumentation = AllcraftAgent.instrumentation();
        if (!instrumentation.isRedefineClassesSupported()) {
            throw new IllegalStateException("This JVM does not support class redefinition");
        }

        ClassLoader gameLoader = AllcraftRuntime.class.getClassLoader();
        Map<String, Class<?>> loaded = loadedClasses(instrumentation, gameLoader);
        List<ClassDefinition> redefinitions = new ArrayList<>();
        Map<Class<?>, byte[]> changedDefinitions = new IdentityHashMap<>();
        List<String> added = new ArrayList<>();
        List<String> skipped = new ArrayList<>();

        for (Map.Entry<String, byte[]> entry : classes.entrySet()) {
            Class<?> current = loaded.get(entry.getKey());
            if (current == null) {
                added.add(entry.getKey());
                continue;
            }

            if (!instrumentation.isModifiableClass(current)) {
                throw new IllegalStateException("JVM refuses to redefine " + current.getName());
            }

            rememberBaseDefinition(current);
            if (Arrays.equals(CURRENT_DEFINITIONS.get(current), entry.getValue())) {
                skipped.add(current.getName());
                continue;
            }

            redefinitions.add(new ClassDefinition(current, entry.getValue()));
            changedDefinitions.put(current, entry.getValue());
        }

        JarFile appended = null;
        long redefineMillis = 0L;
        try {
            if (!added.isEmpty()) {
                appended = new JarFile(normalized.toFile(), false);
                instrumentation.appendToSystemClassLoaderSearch(appended);
                APPENDED_ARTIFACTS.add(appended);
            }
            for (String className : added) {
                Class<?> addedClass = Class.forName(className, false, gameLoader);
                CURRENT_DEFINITIONS.put(addedClass, classes.get(className));
            }
            if (!redefinitions.isEmpty()) {
                long redefineStartedAt = System.nanoTime();
                instrumentation.redefineClasses(redefinitions.toArray(ClassDefinition[]::new));
                redefineMillis = elapsedMillis(redefineStartedAt);
                CURRENT_DEFINITIONS.putAll(changedDefinitions);
            }

            invokeEntrypoints(entrypoints, gameLoader);
        } catch (Exception | Error e) {
            if (appended != null && !APPENDED_ARTIFACTS.contains(appended)) {
                appended.close();
            }
            throw e;
        }

        List<String> redefinedNames = redefinitions.stream().map(definition -> definition.getDefinitionClass().getName()).sorted().toList();
        added.sort(Comparator.naturalOrder());
        skipped.sort(Comparator.naturalOrder());
        ApplyResult result = result(
            redefinedNames.size(), added.size(), skipped.size(), entrypoints, startedAt, redefineMillis, gcCountBefore, gcMillisBefore
        );
        LOGGER.info(
            "Applied Allcraft artifact {}: {} redefined, {} added, {} unchanged; {} ms total, {} ms redefine, GC delta {}/{} ms",
            normalized.getFileName(),
            redefinedNames.size(),
            added.size(),
            skipped.size(),
            result.totalMillis(),
            result.redefineMillis(),
            result.gcCollections(),
            result.gcMillis()
        );
        return result;
    }

    public static synchronized void resetToBase() throws Exception {
        if (BASE_DEFINITIONS.isEmpty()) {
            return;
        }

        Instrumentation instrumentation = AllcraftAgent.instrumentation();
        List<Map.Entry<Class<?>, byte[]>> definitions = BASE_DEFINITIONS.entrySet()
            .stream()
            .filter(entry -> instrumentation.isModifiableClass(entry.getKey()))
            .filter(entry -> !Arrays.equals(entry.getValue(), CURRENT_DEFINITIONS.get(entry.getKey())))
            .sorted(Comparator.comparing(entry -> entry.getKey().getName()))
            .toList();
        long startedAt = System.nanoTime();
        for (Map.Entry<Class<?>, byte[]> entry : definitions) {
            try {
                instrumentation.redefineClasses(new ClassDefinition(entry.getKey(), entry.getValue()));
                CURRENT_DEFINITIONS.put(entry.getKey(), entry.getValue());
            } catch (Exception e) {
                throw new IllegalStateException("Failed to reset runtime class " + entry.getKey().getName(), e);
            }
        }

        if (!definitions.isEmpty()) {
            LOGGER.info("Reset {} Allcraft-modified class(es) to the installed base in {} ms", definitions.size(), elapsedMillis(startedAt));
        }
    }

    private static Map<String, byte[]> readClasses(Path artifact) throws IOException {
        Map<String, byte[]> classes = new LinkedHashMap<>();
        try (JarFile jar = new JarFile(artifact.toFile(), false)) {
            jar.stream()
                .filter(entry -> !entry.isDirectory())
                .filter(entry -> entry.getName().endsWith(".class"))
                .filter(entry -> !entry.getName().startsWith("META-INF/versions/"))
                .filter(entry -> !entry.getName().equals("module-info.class"))
                .sorted(Comparator.comparing(JarEntry::getName))
                .forEach(entry -> {
                    String className = entry.getName().substring(0, entry.getName().length() - 6).replace('/', '.');
                    try (InputStream input = jar.getInputStream(entry)) {
                        classes.put(className, input.readAllBytes());
                    } catch (IOException e) {
                        throw new ArtifactReadException(e);
                    }
                });
        } catch (ArtifactReadException e) {
            throw e.ioException;
        }

        return classes;
    }

    private static List<String> readEntrypoints(Path artifact) throws IOException {
        try (JarFile jar = new JarFile(artifact.toFile(), false)) {
            JarEntry manifestEntry = jar.getJarEntry("META-INF/allcraft-patch.json");
            if (manifestEntry == null) {
                return List.of();
            }

            try (Reader reader = new InputStreamReader(jar.getInputStream(manifestEntry), StandardCharsets.UTF_8)) {
                JsonObject manifest = JsonParser.parseReader(reader).getAsJsonObject();
                if (!manifest.has("entrypoints")) {
                    return List.of();
                }

                JsonArray values = manifest.getAsJsonArray("entrypoints");
                List<String> result = new ArrayList<>(values.size());
                values.forEach(value -> result.add(value.getAsString()));
                return List.copyOf(result);
            } catch (RuntimeException e) {
                throw new IOException("Invalid runtime artifact manifest in " + artifact.getFileName(), e);
            }
        }
    }

    private static Map<String, Class<?>> loadedClasses(Instrumentation instrumentation, ClassLoader gameLoader) {
        Map<String, Class<?>> loaded = new HashMap<>();
        for (Class<?> candidate : instrumentation.getAllLoadedClasses()) {
            if (candidate.getClassLoader() == gameLoader) {
                loaded.putIfAbsent(candidate.getName(), candidate);
            }
        }

        return loaded;
    }

    private static void rememberBaseDefinition(Class<?> type) throws IOException {
        if (BASE_DEFINITIONS.containsKey(type)) {
            return;
        }

        String resource = type.getName().replace('.', '/') + ".class";
        ClassLoader loader = type.getClassLoader();
        try (InputStream input = loader == null ? ClassLoader.getSystemResourceAsStream(resource) : loader.getResourceAsStream(resource)) {
            if (input == null) {
                throw new IOException("Cannot capture installed definition for " + type.getName());
            }

            byte[] baseDefinition = input.readAllBytes();
            BASE_DEFINITIONS.put(type, baseDefinition);
            CURRENT_DEFINITIONS.putIfAbsent(type, baseDefinition);
        }
    }

    private static void invokeEntrypoints(List<String> entrypoints, ClassLoader gameLoader) throws Exception {
        for (String className : entrypoints) {
            Class<?> type = Class.forName(className, true, gameLoader);
            Method method = type.getDeclaredMethod("allcraftActivate");
            if (!Modifier.isStatic(method.getModifiers()) || method.getParameterCount() != 0) {
                throw new IllegalStateException(className + ".allcraftActivate must be a static no-argument method");
            }

            try {
                method.setAccessible(true);
                method.invoke(null);
            } catch (InvocationTargetException e) {
                Throwable cause = e.getCause();
                if (cause instanceof Exception exception) {
                    throw exception;
                }

                if (cause instanceof Error error) {
                    throw error;
                }

                throw e;
            }
        }
    }

    private static String sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(path)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    digest.update(buffer, 0, read);
                }
            }

            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static ApplyResult result(
        int redefinedClasses,
        int addedClasses,
        int unchangedClasses,
        List<String> entrypoints,
        long startedAt,
        long redefineMillis,
        long gcCountBefore,
        long gcMillisBefore
    ) {
        return new ApplyResult(
            redefinedClasses,
            addedClasses,
            unchangedClasses,
            List.copyOf(entrypoints),
            elapsedMillis(startedAt),
            redefineMillis,
            Math.max(0L, gcCount() - gcCountBefore),
            Math.max(0L, gcMillis() - gcMillisBefore)
        );
    }

    private static long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000L;
    }

    private static long gcCount() {
        long total = 0L;
        for (GarbageCollectorMXBean collector : ManagementFactory.getGarbageCollectorMXBeans()) {
            total += Math.max(0L, collector.getCollectionCount());
        }
        return total;
    }

    private static long gcMillis() {
        long total = 0L;
        for (GarbageCollectorMXBean collector : ManagementFactory.getGarbageCollectorMXBeans()) {
            total += Math.max(0L, collector.getCollectionTime());
        }
        return total;
    }

    public record ApplyResult(
        int redefinedClasses,
        int addedClasses,
        int unchangedClasses,
        List<String> invokedEntrypoints,
        long totalMillis,
        long redefineMillis,
        long gcCollections,
        long gcMillis
    ) {
        public String summary() {
            return this.redefinedClasses
                + " redefined, "
                + this.addedClasses
                + " added, "
                + this.unchangedClasses
                + " unchanged, "
                + this.invokedEntrypoints.size()
                + " activated in "
                + this.totalMillis
                + " ms";
        }
    }

    private static final class ArtifactReadException extends RuntimeException {
        private final IOException ioException;

        private ArtifactReadException(IOException ioException) {
            this.ioException = ioException;
        }
    }
}
