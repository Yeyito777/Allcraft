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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
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
    private static final List<JarFile> APPENDED_ARTIFACTS = new ArrayList<>();

    private AllcraftRuntime() {
    }

    public static synchronized ApplyResult apply(Path artifact, String expectedSha256) throws Exception {
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
            return new ApplyResult(0, 0, List.of());
        }

        Instrumentation instrumentation = AllcraftAgent.instrumentation();
        if (!instrumentation.isRedefineClassesSupported()) {
            throw new IllegalStateException("This JVM does not support class redefinition");
        }

        ClassLoader gameLoader = AllcraftRuntime.class.getClassLoader();
        Map<String, Class<?>> loaded = loadedClasses(instrumentation, gameLoader);
        List<ClassDefinition> redefinitions = new ArrayList<>();
        List<String> added = new ArrayList<>();

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
            redefinitions.add(new ClassDefinition(current, entry.getValue()));
        }

        JarFile appended = new JarFile(normalized.toFile(), false);
        boolean retained = false;
        try {
            instrumentation.appendToSystemClassLoaderSearch(appended);
            APPENDED_ARTIFACTS.add(appended);
            retained = true;
            for (String className : added) {
                Class.forName(className, false, gameLoader);
            }
            if (!redefinitions.isEmpty()) {
                instrumentation.redefineClasses(redefinitions.toArray(ClassDefinition[]::new));
            }

            invokeEntrypoints(entrypoints, gameLoader);
        } finally {
            if (!retained) {
                appended.close();
            }
        }

        List<String> redefinedNames = redefinitions.stream().map(definition -> definition.getDefinitionClass().getName()).sorted().toList();
        added.sort(Comparator.naturalOrder());
        LOGGER.info(
            "Applied Allcraft artifact {}: {} redefined class(es), {} added class(es)",
            normalized.getFileName(),
            redefinedNames.size(),
            added.size()
        );
        return new ApplyResult(redefinedNames.size(), added.size(), entrypoints);
    }

    public static synchronized void resetToBase() throws Exception {
        if (BASE_DEFINITIONS.isEmpty()) {
            return;
        }

        Instrumentation instrumentation = AllcraftAgent.instrumentation();
        List<ClassDefinition> definitions = new ArrayList<>(BASE_DEFINITIONS.size());
        for (Map.Entry<Class<?>, byte[]> entry : BASE_DEFINITIONS.entrySet()) {
            if (instrumentation.isModifiableClass(entry.getKey())) {
                definitions.add(new ClassDefinition(entry.getKey(), entry.getValue()));
            }
        }

        if (!definitions.isEmpty()) {
            instrumentation.redefineClasses(definitions.toArray(ClassDefinition[]::new));
            LOGGER.info("Reset {} Allcraft-modified class(es) to the installed base", definitions.size());
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

            BASE_DEFINITIONS.put(type, input.readAllBytes());
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

    public record ApplyResult(int redefinedClasses, int addedClasses, List<String> invokedEntrypoints) {
        public String summary() {
            return this.redefinedClasses + " redefined, " + this.addedClasses + " added, " + this.invokedEntrypoints.size() + " activated";
        }
    }

    private static final class ArtifactReadException extends RuntimeException {
        private final IOException ioException;

        private ArtifactReadException(IOException ioException) {
            this.ioException = ioException;
        }
    }
}
