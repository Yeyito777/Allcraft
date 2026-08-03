package net.minecraft.allcraft;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.ClassTransform;
import java.lang.classfile.CodeTransform;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.CRC32;
import org.slf4j.Logger;

/** Production source-revision differ, compiler, and client/server artifact builder. */
public final class AllcraftRevisionBuilder {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final int SNAPSHOT_FORMAT = 1;
    private static final int ARTIFACT_FORMAT = 2;
    private static final String CACHE_FORMAT = "allcraft-general-compiler-v1";
    private static final String SNAPSHOT = "revisions/current-source.json";
    private static final Pattern PACKAGE = Pattern.compile("(?m)^\\s*package\\s+([A-Za-z_$][\\w$]*(?:\\.[A-Za-z_$][\\w$]*)*)\\s*;");
    private static final Pattern TOP_LEVEL = Pattern.compile(
        "(?m)^(?:\\s*(?:public|protected|private|abstract|final|sealed|non-sealed|static|strictfp)\\s+)*(?:class|interface|enum|record|@interface)\\s+([A-Za-z_$][\\w$]*)"
    );
    private static final ClassDesc NO_CLASS_DEF = ClassDesc.of("java.lang.NoClassDefFoundError");
    private static final MethodTypeDesc STRING_CONSTRUCTOR = MethodTypeDesc.ofDescriptor("(Ljava/lang/String;)V");
    private static volatile List<String> baseClassEntries;

    private AllcraftRevisionBuilder() {
    }

    /** Captures the committed source state. World initialization calls this before arbitrary edits. */
    public static synchronized void initializeBaseline(Path worldRoot) throws IOException {
        Path normalized = worldRoot.toAbsolutePath().normalize();
        Path snapshotPath = normalized.resolve("patches").resolve(SNAPSHOT);
        JsonObject manifest = readJson(normalized.resolve("patches/manifest.json"));
        long revision = manifest.has("currentRevision") ? manifest.get("currentRevision").getAsLong() : 0L;
        if (Files.isRegularFile(snapshotPath)) {
            SourceSnapshot current = readSnapshot(snapshotPath);
            if (current.revision != revision) {
                Path committed = committedSnapshot(normalized.resolve("patches"), revision);
                if (!Files.isRegularFile(committed)) {
                    throw new IOException(
                        "Source snapshot/manifest mismatch and committed snapshot " + committed.getFileName() + " is missing"
                    );
                }
                writeAtomically(snapshotPath, Files.readAllBytes(committed));
            }
            return;
        }
        SourceSnapshot snapshot = scan(normalized.resolve("source"), revision, Map.of());
        writeSnapshot(snapshotPath, snapshot);
        writeSnapshot(committedSnapshot(normalized.resolve("patches"), revision), snapshot);
        LOGGER.info("Captured Allcraft source baseline revision {} with {} file(s)", revision, snapshot.files.size());
    }

    public static PreparedRevision prepare(Path worldRoot, Request request) throws IOException {
        long startedAt = System.nanoTime();
        Path normalized = worldRoot.toAbsolutePath().normalize();
        Path sourceRoot = normalized.resolve("source");
        Path patchesRoot = normalized.resolve("patches");
        initializeBaseline(normalized);
        JsonObject worldManifest = readJson(patchesRoot.resolve("manifest.json"));
        String serverId = worldManifest.get("serverId").getAsString();
        String worldId = worldManifest.get("worldId").getAsString();
        long parentRevision = worldManifest.get("currentRevision").getAsLong();
        long revision = parentRevision + 1L;
        String patchId = UUID.randomUUID().toString();
        String runId = request.runId == null ? UUID.randomUUID().toString() : request.runId;

        SourceSnapshot previous = readSnapshot(patchesRoot.resolve(SNAPSHOT));
        if (previous.revision != parentRevision) {
            throw new IOException(
                "Committed source snapshot is revision " + previous.revision + " but world manifest is revision " + parentRevision
            );
        }
        SourceSnapshot scanned = scan(sourceRoot, revision, previous.files);
        Diff diff = diff(previous, scanned);
        if (diff.changed.isEmpty() && diff.deleted.isEmpty()) {
            throw new IOException("World source has no changes relative to revision " + parentRevision);
        }

        SideBuild client = buildSide(Side.CLIENT, sourceRoot, patchesRoot, worldManifest, previous, scanned, diff);
        SideBuild server = buildSide(Side.SERVER, sourceRoot, patchesRoot, worldManifest, previous, scanned, diff);
        SourceSnapshot next = withCompiledClasses(scanned, previous, client, server);
        Path stagedSnapshot = patchesRoot.resolve("transactions").resolve(patchId + "-source.json");
        writeSnapshot(stagedSnapshot, next);

        byte[] clientArtifact = createArtifact(
            Side.CLIENT, request, serverId, worldId, runId, patchId, parentRevision, revision, client, diff
        );
        byte[] serverArtifact = createArtifact(
            Side.SERVER, request, serverId, worldId, runId, patchId, parentRevision, revision, server, diff
        );
        Path clientPath = patchesRoot.resolve("artifacts/client").resolve(stem(revision, patchId) + ".jar");
        Path serverPath = patchesRoot.resolve("artifacts/server").resolve(stem(revision, patchId) + ".jar");
        writeAtomically(clientPath, clientArtifact);
        writeAtomically(serverPath, serverArtifact);

        JsonObject descriptor = new JsonObject();
        descriptor.addProperty("format", ARTIFACT_FORMAT);
        descriptor.addProperty("kind", "source-revision");
        descriptor.addProperty("label", request.label);
        descriptor.addProperty("serverId", serverId);
        descriptor.addProperty("worldId", worldId);
        descriptor.addProperty("runId", runId);
        descriptor.addProperty("patchId", patchId);
        descriptor.addProperty("parentRevision", parentRevision);
        descriptor.addProperty("revision", revision);
        descriptor.addProperty("clientSha256", sha256(clientArtifact));
        descriptor.addProperty("serverSha256", sha256(serverArtifact));
        descriptor.addProperty("clientClasses", client.classes.size());
        descriptor.addProperty("serverClasses", server.classes.size());
        descriptor.addProperty("addedClientClasses", client.addedClasses.size());
        descriptor.addProperty("addedServerClasses", server.addedClasses.size());
        descriptor.addProperty("deletedClientClasses", client.deletedClasses.size());
        descriptor.addProperty("deletedServerClasses", server.deletedClasses.size());
        descriptor.addProperty("clientResources", client.resources.size());
        descriptor.addProperty("serverResources", server.resources.size());
        descriptor.addProperty("createdAt", Instant.now().toString());
        descriptor.add("changedFiles", strings(diff.changed));
        descriptor.add("deletedFiles", strings(diff.deleted));
        descriptor.add("movedFiles", strings(diff.moved));
        writeJsonAtomically(patchesRoot.resolve("source").resolve(stem(revision, patchId) + ".json"), descriptor);

        long elapsed = elapsedMillis(startedAt);
        LOGGER.info(
            "Prepared general Allcraft revision {} from {} changed and {} deleted file(s): client {}/{} classes/resources, server {}/{} in {} ms",
            revision,
            diff.changed.size(),
            diff.deleted.size(),
            client.classes.size(),
            client.resources.size(),
            server.classes.size(),
            server.resources.size(),
            elapsed
        );
        return new PreparedRevision(
            request,
            serverId,
            worldId,
            runId,
            patchId,
            parentRevision,
            revision,
            clientArtifact,
            serverArtifact,
            sha256(clientArtifact),
            sha256(serverArtifact),
            clientPath,
            serverPath,
            stagedSnapshot,
            List.copyOf(diff.changed),
            List.copyOf(diff.deleted),
            List.copyOf(diff.moved),
            client,
            server,
            elapsed
        );
    }

    public static synchronized void commit(PreparedRevision prepared) throws IOException {
        Path patchesRoot = prepared.serverArtifactPath.getParent().getParent().getParent();
        Path destination = patchesRoot.resolve(SNAPSHOT);
        SourceSnapshot staged = readSnapshot(prepared.stagedSnapshot);
        if (staged.revision != prepared.revision) {
            throw new IOException("Staged source snapshot revision mismatch");
        }
        byte[] bytes = Files.readAllBytes(prepared.stagedSnapshot);
        writeAtomically(committedSnapshot(patchesRoot, prepared.revision), bytes);
        writeAtomically(destination, bytes);
        Files.deleteIfExists(prepared.stagedSnapshot);
    }

    public static void discard(PreparedRevision prepared) {
        try {
            Files.deleteIfExists(prepared.stagedSnapshot);
        } catch (IOException e) {
            LOGGER.warn("Failed to discard staged source snapshot {}", prepared.stagedSnapshot, e);
        }
    }

    private static SideBuild buildSide(
        Side side,
        Path sourceRoot,
        Path patchesRoot,
        JsonObject worldManifest,
        SourceSnapshot previous,
        SourceSnapshot current,
        Diff diff
    ) throws IOException {
        Set<String> changedJava = new LinkedHashSet<>();
        Set<String> deletedJava = new LinkedHashSet<>();
        Map<String, byte[]> resources = new LinkedHashMap<>();
        List<String> deletedResources = new ArrayList<>();
        for (String path : diff.changed) {
            FileState state = current.files.get(path);
            if (state == null || !state.appliesTo(side)) {
                continue;
            }
            if (state.kind.equals("java")) {
                changedJava.add(path);
            } else if (state.kind.equals("resource")) {
                String entry = resourceEntry(path, side);
                if (entry != null) {
                    resources.put(entry, Files.readAllBytes(sourceRoot.resolve(path)));
                }
            }
        }
        for (String path : diff.deleted) {
            FileState state = previous.files.get(path);
            if (state == null || !state.appliesTo(side)) {
                continue;
            }
            if (state.kind.equals("java")) {
                deletedJava.add(path);
            } else if (state.kind.equals("resource")) {
                String entry = resourceEntry(path, side);
                if (entry != null) {
                    deletedResources.add(entry);
                }
            }
        }

        Hooks hooks = readHooks(sourceRoot, side);
        Set<String> compilationSeeds = new LinkedHashSet<>(changedJava);
        Set<String> hookClasses = new LinkedHashSet<>();
        hookClasses.addAll(hooks.prepare);
        hookClasses.addAll(hooks.migrate);
        hookClasses.addAll(hooks.commit);
        hookClasses.addAll(hooks.rollback);
        for (Map.Entry<String, FileState> entry : current.files.entrySet()) {
            FileState state = entry.getValue();
            if (!state.kind.equals("java") || !state.appliesTo(side)) {
                continue;
            }
            String topLevel = sourceClassName(entry.getKey(), state);
            if ((topLevel != null && hookClasses.contains(topLevel)) || state.classNames.stream().anyMatch(hookClasses::contains)) {
                compilationSeeds.add(entry.getKey());
            }
        }
        Set<String> closure = dependencyClosure(side, sourceRoot, current, compilationSeeds, deletedJava, previous);
        Compilation compilation = closure.isEmpty()
            ? Compilation.empty()
            : compile(side, sourceRoot, patchesRoot, worldManifest, closure);
        Map<String, byte[]> classes = new LinkedHashMap<>(compilation.classes);

        Set<String> previousClasses = classSet(previous, side);
        Set<String> prospectiveClasses = new LinkedHashSet<>(classSet(current, side));
        // Changed files get their exact javac output below; remove their stale inner classes first.
        for (String source : closure) {
            FileState old = previous.files.get(source);
            if (old != null) {
                prospectiveClasses.removeAll(old.classNames);
            }
            prospectiveClasses.addAll(classesForSource(source, compilation.classes));
        }
        List<String> deletedClasses = previousClasses.stream().filter(name -> !prospectiveClasses.contains(name)).sorted().toList();
        List<String> addedClasses = prospectiveClasses.stream().filter(name -> !previousClasses.contains(name)).sorted().toList();
        Map<String, byte[]> tombstones = new LinkedHashMap<>();
        Map<String, byte[]> previousDefinitions = new LinkedHashMap<>();
        for (Map.Entry<String, byte[]> entry : classes.entrySet()) {
            String className = className(entry.getKey());
            if (!addedClasses.contains(className)) {
                byte[] oldBytes = previousClassBytes(side, className, patchesRoot, worldManifest);
                if (oldBytes == null) {
                    throw new IOException("Cannot stage " + className + ": parent-revision class bytes are unavailable");
                }
                previousDefinitions.put(entry.getKey(), oldBytes);
            }
        }
        for (String className : deletedClasses) {
            byte[] oldBytes = previousClassBytes(side, className, patchesRoot, worldManifest);
            if (oldBytes == null) {
                throw new IOException("Cannot retire " + className + ": previous class bytes are unavailable");
            }
            previousDefinitions.put(classEntry(className), oldBytes);
            byte[] tombstone = tombstone(className, oldBytes);
            classes.put(classEntry(className), tombstone);
            tombstones.put(classEntry(className), tombstone);
        }
        // Keep a retirement definition for every newly emitted class so a later world can remove it.
        for (Map.Entry<String, byte[]> entry : List.copyOf(classes.entrySet())) {
            tombstones.putIfAbsent(entry.getKey(), tombstone(className(entry.getKey()), entry.getValue()));
        }

        return new SideBuild(
            Map.copyOf(classes),
            Map.copyOf(tombstones),
            Map.copyOf(previousDefinitions),
            Map.copyOf(resources),
            List.copyOf(deletedResources.stream().sorted().toList()),
            List.copyOf(deletedClasses),
            List.copyOf(addedClasses),
            List.copyOf(closure.stream().sorted().toList()),
            hooks,
            compilation.cacheHit,
            compilation.elapsedMillis
        );
    }

    private static SourceSnapshot withCompiledClasses(
        SourceSnapshot scanned, SourceSnapshot previous, SideBuild client, SideBuild server
    ) {
        Map<String, FileState> files = new LinkedHashMap<>();
        Set<String> clientCompiled = new HashSet<>(client.compiledSources);
        Set<String> serverCompiled = new HashSet<>(server.compiledSources);
        for (Map.Entry<String, FileState> entry : scanned.files.entrySet()) {
            String path = entry.getKey();
            FileState state = entry.getValue();
            if (!state.kind.equals("java")) {
                files.put(path, state);
                continue;
            }
            Set<String> names = new LinkedHashSet<>();
            if (state.appliesTo(Side.CLIENT) && clientCompiled.contains(path)) {
                names.addAll(classesForSource(path, client.classes));
            }
            if (state.appliesTo(Side.SERVER) && serverCompiled.contains(path)) {
                names.addAll(classesForSource(path, server.classes));
            }
            if (names.isEmpty()) {
                FileState old = previous.files.get(path);
                if (old != null) {
                    names.addAll(old.classNames);
                } else {
                    names.add(sourceClassName(path, state));
                }
            }
            files.put(path, state.withClassNames(names.stream().filter(value -> value != null).sorted().toList()));
        }
        return new SourceSnapshot(scanned.revision, Map.copyOf(files));
    }

    private static Set<String> dependencyClosure(
        Side side,
        Path sourceRoot,
        SourceSnapshot current,
        Set<String> changed,
        Set<String> deleted,
        SourceSnapshot previous
    ) throws IOException {
        Set<String> selected = new LinkedHashSet<>(changed);
        Set<String> affectedNames = new LinkedHashSet<>();
        for (String path : changed) {
            FileState state = current.files.get(path);
            if (state != null) {
                affectedNames.addAll(typeNames(path, state, sourceRoot.resolve(path)));
            }
        }
        for (String path : deleted) {
            FileState state = previous.files.get(path);
            if (state != null) {
                affectedNames.addAll(state.classNames);
                String simple = simpleSourceName(path);
                if (simple != null) {
                    affectedNames.add(simple);
                }
            }
        }
        Map<String, String> sources = new LinkedHashMap<>();
        for (Map.Entry<String, FileState> entry : current.files.entrySet()) {
            if (entry.getValue().kind.equals("java") && entry.getValue().appliesTo(side)) {
                sources.put(entry.getKey(), Files.readString(sourceRoot.resolve(entry.getKey()), StandardCharsets.UTF_8));
            }
        }
        boolean grew;
        do {
            grew = false;
            Set<String> tokens = new LinkedHashSet<>();
            for (String value : affectedNames) {
                tokens.add(value);
                int separator = value.lastIndexOf('.');
                if (separator >= 0) {
                    tokens.add(value.substring(separator + 1));
                }
            }
            for (Map.Entry<String, String> candidate : sources.entrySet()) {
                if (selected.contains(candidate.getKey()) || !mentionsAny(candidate.getValue(), tokens)) {
                    continue;
                }
                selected.add(candidate.getKey());
                FileState state = current.files.get(candidate.getKey());
                int before = affectedNames.size();
                affectedNames.addAll(typeNames(candidate.getKey(), state, sourceRoot.resolve(candidate.getKey())));
                grew |= affectedNames.size() != before;
            }
        } while (grew);
        selected.removeIf(path -> !Files.isRegularFile(sourceRoot.resolve(path)));
        return selected;
    }

    private static boolean mentionsAny(String source, Set<String> tokens) {
        for (String token : tokens) {
            if (token == null || token.isBlank()) {
                continue;
            }
            if (source.contains(token) && Pattern.compile("(?<![A-Za-z0-9_$])" + Pattern.quote(token) + "(?![A-Za-z0-9_$])").matcher(source).find()) {
                return true;
            }
        }
        return false;
    }

    private static Compilation compile(
        Side side, Path sourceRoot, Path patchesRoot, JsonObject manifest, Set<String> sources
    ) throws IOException {
        long startedAt = System.nanoTime();
        List<Path> sourceFiles = sources.stream().map(sourceRoot::resolve).map(path -> path.toAbsolutePath().normalize()).sorted().toList();
        String classPath = compileClassPath(side, patchesRoot, manifest);
        String key = cacheKey(side, sourceFiles, classPath);
        Path cacheRoot = patchesRoot.resolve("build-cache/general").resolve(side.id);
        Path entry = cacheRoot.resolve(key);
        Path output = entry.resolve("classes");
        if (Files.isRegularFile(entry.resolve("complete"))) {
            Map<String, byte[]> cached = readClasses(output);
            if (!cached.isEmpty()) {
                return new Compilation(cached, true, elapsedMillis(startedAt));
            }
        }

        Files.createDirectories(cacheRoot);
        Path temporary = cacheRoot.resolve("." + key + "." + UUID.randomUUID() + ".tmp");
        Path temporaryOutput = temporary.resolve("classes");
        Path compilerLog = temporary.resolve("javac.log");
        Files.createDirectories(temporaryOutput);
        List<String> command = new ArrayList<>();
        command.add(configuredJavac().toString());
        command.add("-J-Xms32m");
        command.add("-J-Xmx2g");
        command.add("-J-XX:ActiveProcessorCount=4");
        command.add("-classpath");
        command.add(classPath);
        command.add("-sourcepath");
        command.add(sourcePath(side, sourceRoot));
        command.add("-d");
        command.add(temporaryOutput.toString());
        command.add("-encoding");
        command.add("UTF-8");
        command.add("-g");
        command.add("-parameters");
        command.add("-proc:none");
        command.add("-implicit:none");
        sourceFiles.forEach(path -> command.add(path.toString()));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(compilerLog.toFile()).start();
        boolean finished;
        try {
            finished = process.waitFor(10L, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            deleteTree(temporary);
            throw new IOException("Interrupted while compiling source revision", e);
        }
        if (!finished) {
            process.destroyForcibly();
            deleteTree(temporary);
            throw new IOException("Source revision compilation timed out after ten minutes");
        }
        if (process.exitValue() != 0) {
            String diagnostics = Files.isRegularFile(compilerLog) ? Files.readString(compilerLog, StandardCharsets.UTF_8) : "";
            deleteTree(temporary);
            throw new IOException("Source revision compilation failed:\n" + diagnostics.substring(0, Math.min(20000, diagnostics.length())));
        }
        Map<String, byte[]> classes = readClasses(temporaryOutput);
        if (classes.isEmpty()) {
            deleteTree(temporary);
            throw new IOException("Compiler produced no class files for " + sourceFiles);
        }
        Files.writeString(temporary.resolve("complete"), key + System.lineSeparator(), StandardCharsets.UTF_8);
        deleteTree(entry);
        try {
            Files.move(temporary, entry, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temporary, entry);
        }
        return new Compilation(classes, false, elapsedMillis(startedAt));
    }

    private static String compileClassPath(Side side, Path patchesRoot, JsonObject manifest) throws IOException {
        List<String> values = new ArrayList<>();
        JsonArray patches = manifest.has("patches") ? manifest.getAsJsonArray("patches") : new JsonArray();
        for (JsonElement value : patches) {
            JsonObject patch = value.getAsJsonObject();
            Path artifact = patchesRoot.resolve("artifacts/" + side.id).resolve(
                stem(patch.get("revision").getAsLong(), patch.get("patchId").getAsString()) + ".jar"
            );
            if (Files.isRegularFile(artifact)) {
                values.add(artifact.toString());
            }
        }
        values.add(System.getProperty("java.class.path"));
        return String.join(File.pathSeparator, values);
    }

    private static String sourcePath(Side side, Path sourceRoot) {
        List<String> roots = new ArrayList<>();
        Path sideRoot = sourceRoot.resolve(side.id);
        if (Files.isDirectory(sideRoot)) {
            roots.add(sideRoot.toString());
        }
        Path shared = sourceRoot.resolve("shared");
        if (Files.isDirectory(shared)) {
            roots.add(shared.toString());
        }
        return String.join(File.pathSeparator, roots);
    }

    private static String cacheKey(Side side, List<Path> sources, String classPath) throws IOException {
        MessageDigest digest = digest();
        update(digest, CACHE_FORMAT);
        update(digest, side.id);
        update(digest, configuredJavac().toString());
        for (Path source : sources) {
            update(digest, source.toString());
            digest.update(Files.readAllBytes(source));
        }
        for (String value : classPath.split(Pattern.quote(File.pathSeparator))) {
            Path path = Path.of(value);
            update(digest, path.toAbsolutePath().normalize().toString());
            if (Files.exists(path)) {
                update(digest, Long.toString(Files.size(path)));
                update(digest, Long.toString(Files.getLastModifiedTime(path).toMillis()));
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static byte[] createArtifact(
        Side side,
        Request request,
        String serverId,
        String worldId,
        String runId,
        String patchId,
        long parentRevision,
        long revision,
        SideBuild build,
        Diff diff
    ) throws IOException {
        JsonObject descriptor = new JsonObject();
        descriptor.addProperty("format", ARTIFACT_FORMAT);
        descriptor.addProperty("kind", "source-revision");
        descriptor.addProperty("side", side.id);
        descriptor.addProperty("label", request.label);
        descriptor.addProperty("testName", request.label);
        descriptor.addProperty("serverId", serverId);
        descriptor.addProperty("worldId", worldId);
        descriptor.addProperty("runId", runId);
        descriptor.addProperty("patchId", patchId);
        descriptor.addProperty("parentRevision", parentRevision);
        descriptor.addProperty("revision", revision);
        descriptor.addProperty("step", request.step);
        descriptor.addProperty("totalSteps", request.totalSteps);
        descriptor.addProperty("display", request.display);
        descriptor.addProperty("message", request.message);
        descriptor.addProperty("classCount", build.classes.size());
        descriptor.addProperty("resourceCount", build.resources.size());
        descriptor.add("changedFiles", strings(diff.changed));
        descriptor.add("deletedFiles", strings(diff.deleted));
        descriptor.add("movedFiles", strings(diff.moved));
        descriptor.add("addedClasses", strings(build.addedClasses));
        descriptor.add("deletedClasses", strings(build.deletedClasses));
        descriptor.add("deletedResources", strings(build.deletedResources));
        descriptor.add("hooks", build.hooks.toJson());
        descriptor.add("entrypoints", strings(side == Side.CLIENT ? request.clientEntrypoints : request.serverEntrypoints));

        byte[] json = (GSON.toJson(descriptor) + System.lineSeparator()).getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (JarOutputStream jar = new JarOutputStream(output)) {
            writeJarEntry(jar, "META-INF/allcraft-patch.json", json);
            if (request.testFixture) {
                writeJarEntry(jar, "allcraft/test-patch.json", json);
            }
            if (!build.resources.isEmpty() || !build.deletedResources.isEmpty()) {
                writeJarEntry(jar, "pack.mcmeta", packMetadata(request.label, build.deletedResources));
            }
            for (Map.Entry<String, byte[]> entry : build.classes.entrySet()) {
                writeJarEntry(jar, entry.getKey(), entry.getValue());
            }
            for (Map.Entry<String, byte[]> entry : build.tombstones.entrySet()) {
                writeJarEntry(jar, "META-INF/allcraft-tombstones/" + entry.getKey(), entry.getValue());
            }
            for (Map.Entry<String, byte[]> entry : build.previousDefinitions.entrySet()) {
                writeJarEntry(jar, "META-INF/allcraft-previous/" + entry.getKey(), entry.getValue());
            }
            for (Map.Entry<String, byte[]> entry : build.resources.entrySet()) {
                writeJarEntry(jar, entry.getKey(), entry.getValue());
            }
            if (request.fillerBytes > 0) {
                byte[] filler = new byte[request.fillerBytes];
                new java.util.Random(31L * revision + request.step).nextBytes(filler);
                writeStoredJarEntry(jar, "allcraft/test-filler.bin", filler);
            }
        }
        return output.toByteArray();
    }

    private static byte[] packMetadata(String label, List<String> deleted) {
        JsonObject root = new JsonObject();
        JsonObject pack = new JsonObject();
        pack.addProperty("description", "Allcraft source revision: " + label);
        pack.addProperty("pack_format", 1);
        root.add("pack", pack);
        JsonArray blocked = new JsonArray();
        for (String entry : deleted) {
            int typeSeparator = entry.indexOf('/');
            int namespaceSeparator = typeSeparator < 0 ? -1 : entry.indexOf('/', typeSeparator + 1);
            if (namespaceSeparator < 0) {
                continue;
            }
            JsonObject pattern = new JsonObject();
            pattern.addProperty("namespace", "^" + Pattern.quote(entry.substring(typeSeparator + 1, namespaceSeparator)) + "$");
            pattern.addProperty("path", "^" + Pattern.quote(entry.substring(namespaceSeparator + 1)) + "$");
            blocked.add(pattern);
        }
        JsonObject filter = new JsonObject();
        filter.add("block", blocked);
        root.add("filter", filter);
        return (GSON.toJson(root) + System.lineSeparator()).getBytes(StandardCharsets.UTF_8);
    }

    private static SourceSnapshot scan(Path sourceRoot, long revision, Map<String, FileState> previous) throws IOException {
        Map<String, FileState> files = new LinkedHashMap<>();
        if (!Files.isDirectory(sourceRoot)) {
            throw new IOException("World source is missing: " + sourceRoot);
        }
        try (Stream<Path> stream = Files.walk(sourceRoot)) {
            for (Path file : stream.filter(Files::isRegularFile).sorted().toList()) {
                String relative = unix(sourceRoot.relativize(file));
                if (relative.startsWith(".git/") || relative.contains("/build/") || relative.endsWith("~")) {
                    continue;
                }
                SideScope scope = classifyScope(relative);
                String kind = relative.endsWith(".java") ? "java" : isResource(relative) ? "resource" : "control";
                List<String> classNames = List.of();
                FileState old = previous.get(relative);
                if (old != null) {
                    classNames = old.classNames;
                } else if (kind.equals("java")) {
                    String name = sourceClassName(relative, new FileState("", 0L, scope.id, kind, List.of()));
                    if (name != null) {
                        classNames = baseClassNames(name);
                    }
                }
                files.put(relative, new FileState(sha256(file), Files.size(file), scope.id, kind, classNames));
            }
        }
        return new SourceSnapshot(revision, Map.copyOf(files));
    }

    private static Diff diff(SourceSnapshot previous, SourceSnapshot current) {
        List<String> changed = new ArrayList<>();
        List<String> deleted = new ArrayList<>();
        for (Map.Entry<String, FileState> entry : current.files.entrySet()) {
            FileState old = previous.files.get(entry.getKey());
            if (old == null || !old.sha256.equals(entry.getValue().sha256)) {
                changed.add(entry.getKey());
            }
        }
        previous.files.keySet().stream().filter(path -> !current.files.containsKey(path)).forEach(deleted::add);
        Map<String, ArrayDeque<String>> deletedByHash = new HashMap<>();
        for (String path : deleted) {
            deletedByHash.computeIfAbsent(previous.files.get(path).sha256, ignored -> new ArrayDeque<>()).add(path);
        }
        List<String> moved = new ArrayList<>();
        for (String path : changed) {
            FileState state = current.files.get(path);
            if (previous.files.containsKey(path)) {
                continue;
            }
            ArrayDeque<String> candidates = deletedByHash.get(state.sha256);
            if (candidates != null && !candidates.isEmpty()) {
                moved.add(candidates.removeFirst() + " -> " + path);
            }
        }
        changed.sort(String::compareTo);
        deleted.sort(String::compareTo);
        moved.sort(String::compareTo);
        return new Diff(changed, deleted, moved);
    }

    private static SourceSnapshot readSnapshot(Path path) throws IOException {
        JsonObject root = readJson(path);
        long revision = root.get("revision").getAsLong();
        Map<String, FileState> files = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject("files").entrySet()) {
            JsonObject value = entry.getValue().getAsJsonObject();
            List<String> classes = new ArrayList<>();
            if (value.has("classNames")) {
                value.getAsJsonArray("classNames").forEach(name -> classes.add(name.getAsString()));
            }
            files.put(
                entry.getKey(),
                new FileState(
                    value.get("sha256").getAsString(),
                    value.get("size").getAsLong(),
                    value.get("scope").getAsString(),
                    value.get("kind").getAsString(),
                    List.copyOf(classes)
                )
            );
        }
        return new SourceSnapshot(revision, Map.copyOf(files));
    }

    private static void writeSnapshot(Path path, SourceSnapshot snapshot) throws IOException {
        JsonObject root = new JsonObject();
        root.addProperty("format", SNAPSHOT_FORMAT);
        root.addProperty("revision", snapshot.revision);
        root.addProperty("capturedAt", Instant.now().toString());
        JsonObject files = new JsonObject();
        snapshot.files.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            FileState state = entry.getValue();
            JsonObject value = new JsonObject();
            value.addProperty("sha256", state.sha256);
            value.addProperty("size", state.size);
            value.addProperty("scope", state.scope);
            value.addProperty("kind", state.kind);
            value.add("classNames", strings(state.classNames));
            files.add(entry.getKey(), value);
        });
        root.add("files", files);
        writeJsonAtomically(path, root);
    }

    private static Set<String> classSet(SourceSnapshot snapshot, Side side) {
        Set<String> result = new LinkedHashSet<>();
        snapshot.files.values().stream().filter(state -> state.kind.equals("java") && state.appliesTo(side)).forEach(state -> result.addAll(state.classNames));
        return result;
    }

    private static List<String> classesForSource(String path, Map<String, byte[]> classes) {
        String simple = simpleSourceName(path);
        if (simple == null) {
            return List.of();
        }
        FileState dummy = new FileState("", 0L, classifyScope(path).id, "java", List.of());
        String top = sourceClassName(path, dummy);
        if (top == null) {
            return List.of();
        }
        String prefix = top.replace('.', '/');
        return classes.keySet()
            .stream()
            .filter(entry -> entry.equals(prefix + ".class") || entry.startsWith(prefix + "$"))
            .map(AllcraftRevisionBuilder::className)
            .sorted()
            .toList();
    }

    private static String sourceClassName(String path, FileState state) {
        if (!path.endsWith(".java")) {
            return null;
        }
        String relative = path;
        int slash = relative.indexOf('/');
        if (slash >= 0 && (relative.startsWith("client/") || relative.startsWith("server/") || relative.startsWith("shared/"))) {
            relative = relative.substring(slash + 1);
        }
        return relative.substring(0, relative.length() - 5).replace('/', '.');
    }

    private static List<String> typeNames(String path, FileState state, Path file) throws IOException {
        String source = Files.readString(file, StandardCharsets.UTF_8);
        Matcher packageMatcher = PACKAGE.matcher(source);
        String packageName = packageMatcher.find() ? packageMatcher.group(1) : "";
        Matcher matcher = TOP_LEVEL.matcher(source);
        List<String> result = new ArrayList<>();
        while (matcher.find()) {
            String simple = matcher.group(1);
            result.add(simple);
            if (!packageName.isEmpty()) {
                result.add(packageName + "." + simple);
            }
        }
        if (result.isEmpty()) {
            String fallback = sourceClassName(path, state);
            if (fallback != null) {
                result.add(fallback);
            }
        }
        return result;
    }

    private static String simpleSourceName(String path) {
        if (!path.endsWith(".java")) {
            return null;
        }
        int slash = path.lastIndexOf('/');
        return path.substring(slash + 1, path.length() - 5);
    }

    private static byte[] previousClassBytes(
        Side side, String className, Path patchesRoot, JsonObject manifest
    ) throws IOException {
        String entryName = classEntry(className);
        JsonArray patches = manifest.has("patches") ? manifest.getAsJsonArray("patches") : new JsonArray();
        for (int index = patches.size() - 1; index >= 0; index--) {
            JsonObject patch = patches.get(index).getAsJsonObject();
            Path artifact = patchesRoot.resolve("artifacts/" + side.id).resolve(
                stem(patch.get("revision").getAsLong(), patch.get("patchId").getAsString()) + ".jar"
            );
            byte[] found = jarEntry(artifact, entryName);
            if (found != null) {
                return found;
            }
        }
        Path base = configuredBaseJar();
        return jarEntry(base, entryName);
    }

    private static byte[] tombstone(String className, byte[] original) throws IOException {
        try {
            ClassFile classFile = ClassFile.of();
            ClassModel model = classFile.parse(original);
            String message = className + " was retired by an Allcraft source revision";
            CodeTransform discardOriginalBody = (builder, element) -> {
            };
            CodeTransform body = discardOriginalBody.andThen(
                    CodeTransform.endHandler(
                        code -> code.new_(NO_CLASS_DEF)
                            .dup()
                            .ldc(message)
                            .invokespecial(NO_CLASS_DEF, "<init>", STRING_CONSTRUCTOR)
                            .athrow()
                    )
                );
            byte[] transformed = classFile.transformClass(model, ClassTransform.transformingMethodBodies(body));
            List<java.lang.VerifyError> errors = classFile.verify(transformed);
            if (!errors.isEmpty()) {
                throw new IOException("Generated tombstone for " + className + " did not verify: " + errors.getFirst());
            }
            return transformed;
        } catch (IllegalArgumentException e) {
            throw new IOException("Cannot generate tombstone for " + className, e);
        }
    }

    private static Hooks readHooks(Path sourceRoot, Side side) throws IOException {
        Path config = sourceRoot.resolve("allcraft-revision.json");
        if (!Files.isRegularFile(config)) {
            return Hooks.EMPTY;
        }
        JsonObject root = readJson(config);
        JsonObject selected = root.has(side.id) ? root.getAsJsonObject(side.id) : new JsonObject();
        return new Hooks(hook(selected, "prepare"), hook(selected, "migrate"), hook(selected, "commit"), hook(selected, "rollback"));
    }

    private static List<String> hook(JsonObject object, String name) {
        if (!object.has(name)) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        object.getAsJsonArray(name).forEach(value -> values.add(value.getAsString()));
        return List.copyOf(values);
    }

    private static String resourceEntry(String path, Side side) {
        String relative = path;
        if (relative.startsWith("shared/")) {
            relative = relative.substring("shared/".length());
        } else if (relative.startsWith(side.id + "/")) {
            relative = relative.substring(side.id.length() + 1);
        } else {
            return null;
        }
        if (side == Side.CLIENT && relative.startsWith("assets/")) {
            return relative;
        }
        if (side == Side.SERVER && relative.startsWith("data/")) {
            return relative;
        }
        return null;
    }

    private static boolean isResource(String path) {
        return path.startsWith("client/assets/")
            || path.startsWith("client/data/")
            || path.startsWith("server/assets/")
            || path.startsWith("server/data/")
            || path.startsWith("shared/assets/")
            || path.startsWith("shared/data/");
    }

    private static SideScope classifyScope(String path) {
        if (path.startsWith("client/")) {
            return SideScope.CLIENT;
        }
        if (path.startsWith("server/")) {
            return SideScope.SERVER;
        }
        if (path.startsWith("shared/")) {
            return SideScope.SHARED;
        }
        return SideScope.CONTROL;
    }

    private static List<String> baseClassNames(String topLevel) throws IOException {
        String prefix = topLevel.replace('.', '/');
        List<String> result = baseClassEntries()
            .stream()
            .filter(name -> name.equals(prefix + ".class") || name.startsWith(prefix + "$"))
            .map(AllcraftRevisionBuilder::className)
            .sorted()
            .toList();
        if (result.isEmpty()) {
            return List.of(topLevel);
        }
        return result;
    }

    private static List<String> baseClassEntries() throws IOException {
        List<String> cached = baseClassEntries;
        if (cached != null) {
            return cached;
        }
        synchronized (AllcraftRevisionBuilder.class) {
            if (baseClassEntries == null) {
                try (JarFile jar = new JarFile(configuredBaseJar().toFile(), false)) {
                    baseClassEntries = jar.stream()
                        .filter(entry -> !entry.isDirectory() && entry.getName().endsWith(".class"))
                        .map(JarEntry::getName)
                        .sorted()
                        .toList();
                }
            }
            return baseClassEntries;
        }
    }

    private static Path configuredBaseJar() throws IOException {
        String configured = System.getProperty("allcraft.baseJar");
        if (configured != null && !configured.isBlank()) {
            Path result = Path.of(configured).toAbsolutePath().normalize();
            if (Files.isRegularFile(result)) {
                return result;
            }
        }
        String source = System.getProperty("allcraft.sourceRoot");
        if (source != null && !source.isBlank()) {
            Path candidate = Path.of(source).toAbsolutePath().normalize().getParent().resolve("build/allcraft-26.2.jar");
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        for (String value : System.getProperty("java.class.path").split(Pattern.quote(File.pathSeparator))) {
            Path candidate = Path.of(value);
            if (candidate.getFileName() != null && candidate.getFileName().toString().startsWith("allcraft-") && Files.isRegularFile(candidate)) {
                return candidate.toAbsolutePath().normalize();
            }
        }
        throw new IOException("Cannot locate the installed Allcraft base JAR");
    }

    private static Path configuredJavac() throws IOException {
        String value = System.getProperty("allcraft.javac");
        Path javac = value == null || value.isBlank()
            ? Path.of(System.getProperty("java.home"), "bin", isWindows() ? "javac.exe" : "javac")
            : Path.of(value);
        javac = javac.toAbsolutePath().normalize();
        if (!Files.isExecutable(javac)) {
            throw new IOException("Allcraft javac is missing or not executable: " + javac);
        }
        return javac;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static Map<String, byte[]> readClasses(Path root) throws IOException {
        Map<String, byte[]> result = new LinkedHashMap<>();
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path file : paths.filter(Files::isRegularFile).filter(path -> path.toString().endsWith(".class")).sorted().toList()) {
                result.put(unix(root.relativize(file)), Files.readAllBytes(file));
            }
        }
        return result;
    }

    private static byte[] jarEntry(Path jarPath, String name) throws IOException {
        if (!Files.isRegularFile(jarPath)) {
            return null;
        }
        try (JarFile jar = new JarFile(jarPath.toFile(), false)) {
            JarEntry entry = jar.getJarEntry(name);
            if (entry == null) {
                return null;
            }
            try (InputStream input = jar.getInputStream(entry)) {
                return input.readAllBytes();
            }
        }
    }

    private static String classEntry(String className) {
        return className.replace('.', '/') + ".class";
    }

    private static String className(String entry) {
        return entry.substring(0, entry.length() - ".class".length()).replace('/', '.');
    }

    private static String stem(long revision, String patchId) {
        return String.format("%08d-%s", revision, patchId);
    }

    private static Path committedSnapshot(Path patchesRoot, long revision) {
        return patchesRoot.resolve("revisions").resolve(String.format("source-%08d.json", revision));
    }

    private static JsonArray strings(Iterable<String> values) {
        JsonArray result = new JsonArray();
        values.forEach(result::add);
        return result;
    }

    private static JsonObject readJson(Path path) throws IOException {
        try {
            return JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (RuntimeException e) {
            throw new IOException("Invalid Allcraft JSON " + path, e);
        }
    }

    private static void writeJsonAtomically(Path path, JsonObject value) throws IOException {
        writeAtomically(path, (GSON.toJson(value) + System.lineSeparator()).getBytes(StandardCharsets.UTF_8));
    }

    private static void writeAtomically(Path path, byte[] bytes) throws IOException {
        Files.createDirectories(path.getParent());
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
        Files.write(temporary, bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        try {
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String sha256(Path path) throws IOException {
        MessageDigest digest = digest();
        try (InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String sha256(byte[] bytes) {
        return HexFormat.of().formatHex(digest().digest(bytes));
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void update(MessageDigest digest, String value) {
        digest.update(value.getBytes(StandardCharsets.UTF_8));
        digest.update((byte)0);
    }

    private static String unix(Path path) {
        return path.toString().replace(path.getFileSystem().getSeparator(), "/");
    }

    private static long elapsedMillis(long start) {
        return (System.nanoTime() - start) / 1_000_000L;
    }

    private static void writeJarEntry(JarOutputStream jar, String name, byte[] bytes) throws IOException {
        JarEntry entry = new JarEntry(name);
        jar.putNextEntry(entry);
        jar.write(bytes);
        jar.closeEntry();
    }

    private static void writeStoredJarEntry(JarOutputStream jar, String name, byte[] bytes) throws IOException {
        CRC32 crc = new CRC32();
        crc.update(bytes);
        JarEntry entry = new JarEntry(name);
        entry.setMethod(JarEntry.STORED);
        entry.setSize(bytes.length);
        entry.setCompressedSize(bytes.length);
        entry.setCrc(crc.getValue());
        jar.putNextEntry(entry);
        jar.write(bytes);
        jar.closeEntry();
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory, IOException error) throws IOException {
                if (error != null) {
                    throw error;
                }
                Files.delete(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    public record Request(
        String label,
        String display,
        String message,
        int fillerBytes,
        int step,
        int totalSteps,
        boolean testFixture,
        String runId,
        List<String> clientEntrypoints,
        List<String> serverEntrypoints
    ) {
        public Request {
            if (label == null || label.isBlank() || label.length() > 32) {
                throw new IllegalArgumentException("Revision label must contain 1-32 characters");
            }
            clientEntrypoints = List.copyOf(clientEntrypoints);
            serverEntrypoints = List.copyOf(serverEntrypoints);
        }

        public static Request production(String label) {
            return new Request(label, "chat", "Arbitrary world source revision", 0, 1, 1, false, null, List.of(), List.of());
        }

        public static Request test(
            String name,
            String display,
            String message,
            int fillerBytes,
            int step,
            int totalSteps,
            String runId,
            List<String> clientEntrypoints,
            List<String> serverEntrypoints
        ) {
            return new Request(
                name, display, message, fillerBytes, step, totalSteps, true, runId, List.copyOf(clientEntrypoints), List.copyOf(serverEntrypoints)
            );
        }
    }

    public record PreparedRevision(
        Request request,
        String serverId,
        String worldId,
        String runId,
        String patchId,
        long parentRevision,
        long revision,
        byte[] clientArtifact,
        byte[] serverArtifact,
        String clientSha256,
        String serverSha256,
        Path clientArtifactPath,
        Path serverArtifactPath,
        Path stagedSnapshot,
        List<String> changedFiles,
        List<String> deletedFiles,
        List<String> movedFiles,
        SideBuild client,
        SideBuild server,
        long elapsedMillis
    ) {
    }

    public record SideBuild(
        Map<String, byte[]> classes,
        Map<String, byte[]> tombstones,
        Map<String, byte[]> previousDefinitions,
        Map<String, byte[]> resources,
        List<String> deletedResources,
        List<String> deletedClasses,
        List<String> addedClasses,
        List<String> compiledSources,
        Hooks hooks,
        boolean cacheHit,
        long compilationMillis
    ) {
    }

    public record Hooks(List<String> prepare, List<String> migrate, List<String> commit, List<String> rollback) {
        private static final Hooks EMPTY = new Hooks(List.of(), List.of(), List.of(), List.of());

        private JsonObject toJson() {
            JsonObject result = new JsonObject();
            result.add("prepare", strings(this.prepare));
            result.add("migrate", strings(this.migrate));
            result.add("commit", strings(this.commit));
            result.add("rollback", strings(this.rollback));
            return result;
        }
    }

    private record SourceSnapshot(long revision, Map<String, FileState> files) {
    }

    private record FileState(String sha256, long size, String scope, String kind, List<String> classNames) {
        private boolean appliesTo(Side side) {
            return this.scope.equals(side.id) || this.scope.equals("shared");
        }

        private FileState withClassNames(List<String> values) {
            return new FileState(this.sha256, this.size, this.scope, this.kind, List.copyOf(values));
        }
    }

    private record Diff(List<String> changed, List<String> deleted, List<String> moved) {
    }

    private record Compilation(Map<String, byte[]> classes, boolean cacheHit, long elapsedMillis) {
        private static Compilation empty() {
            return new Compilation(Map.of(), true, 0L);
        }
    }

    private enum Side {
        CLIENT("client"),
        SERVER("server");

        private final String id;

        Side(String id) {
            this.id = id;
        }
    }

    private enum SideScope {
        CLIENT("client"),
        SERVER("server"),
        SHARED("shared"),
        CONTROL("control");

        private final String id;

        SideScope(String id) {
            this.id = id;
        }
    }
}
