package net.minecraft.allcraft;

import com.mojang.logging.LogUtils;
import java.io.File;
import java.io.IOException;
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
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import org.slf4j.Logger;

/** Server-side compiler for real source-changing Allcraft test patches. */
public final class AllcraftPatchCompiler {
    public static final List<String> RUNTIME_TEST_NAMES = List.of("double-jump", "no-world-gen", "flying-boats", "new-class");
    private static final String CACHE_FORMAT = "allcraft-javac-cache-v2";
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String LOCAL_PLAYER = "client/net/minecraft/client/player/LocalPlayer.java";
    private static final String DOUBLE_JUMP_HELPER = "client/net/minecraft/client/player/AllcraftDoubleJump.java";
    private static final String CLIENT_NOISE_GENERATOR = "client/net/minecraft/world/level/levelgen/NoiseBasedChunkGenerator.java";
    private static final String SERVER_NOISE_GENERATOR = "server/net/minecraft/world/level/levelgen/NoiseBasedChunkGenerator.java";
    private static final String CLIENT_CHUNK_GENERATOR = "client/net/minecraft/world/level/chunk/ChunkGenerator.java";
    private static final String SERVER_CHUNK_GENERATOR = "server/net/minecraft/world/level/chunk/ChunkGenerator.java";

    private AllcraftPatchCompiler() {
    }

    public static Build compile(Path worldSource, Path workRoot, String testName) throws IOException {
        if (!RUNTIME_TEST_NAMES.contains(testName)) {
            throw new IOException("Unknown runtime patch test " + testName);
        }

        if (!Files.isDirectory(worldSource)) {
            throw new IOException("World source directory is missing: " + worldSource);
        }

        List<SourceEdit> edits = switch (testName) {
            case "double-jump" -> doubleJumpEdits(worldSource);
            case "flying-boats" -> flyingBoatEdits(worldSource);
            case "no-world-gen" -> noWorldGenerationEdits(worldSource);
            case "new-class" -> newClassEdits(worldSource);
            default -> throw new IOException("Unknown runtime patch test " + testName);
        };

        applyEdits(edits);
        try {
            List<Path> clientSources = clientSources(worldSource, testName);
            List<Path> serverSources = serverSources(worldSource, testName);
            Compilation clientCompilation = clientSources.isEmpty()
                ? Compilation.empty()
                : compileJava(clientSources, workRoot.resolve("client"));
            Compilation serverCompilation = serverSources.isEmpty()
                ? Compilation.empty()
                : compileJava(serverSources, workRoot.resolve("server"));
            List<String> changedFiles = edits.stream().map(edit -> worldSource.relativize(edit.path()).toString()).sorted().toList();
            return new Build(
                clientCompilation.classes(),
                serverCompilation.classes(),
                changedFiles,
                clientEntrypoints(testName),
                serverEntrypoints(testName),
                instructions(testName),
                clientCompilation.cacheHit(),
                serverCompilation.cacheHit(),
                clientCompilation.elapsedMillis() + serverCompilation.elapsedMillis()
            );
        } catch (Exception e) {
            restoreEdits(edits);
            if (e instanceof IOException ioException) {
                throw ioException;
            }

            throw new IOException("Failed to compile runtime patch " + testName, e);
        }
    }

    private static List<SourceEdit> doubleJumpEdits(Path sourceRoot) throws IOException {
        List<SourceEdit> edits = new ArrayList<>();
        edits.add(
            editExisting(sourceRoot, LOCAL_PLAYER, source -> {
                String behavior = "        // ALLCRAFT PATCH: double-jump behavior\n"
                    + "        AllcraftDoubleJump.tick(\n"
                    + "            this, this.input.keyPresses.jump(), this.onGround(), this.isPassenger(), abilities.flying\n"
                    + "        );\n\n";
                return replaceOrInsertMarkedBlock(source, "ALLCRAFT PATCH: double-jump behavior", "        super.aiStep();", behavior);
            })
        );
        edits.add(
            editGenerated(
                sourceRoot,
                DOUBLE_JUMP_HELPER,
                "package net.minecraft.client.player;\n\n"
                    + "import java.util.Collections;\n"
                    + "import java.util.Map;\n"
                    + "import java.util.WeakHashMap;\n\n"
                    + "/** Added to the running game by the Allcraft double-jump patch. */\n"
                    + "public final class AllcraftDoubleJump {\n"
                    + "    private static final Map<LocalPlayer, State> STATES = Collections.synchronizedMap(new WeakHashMap<>());\n\n"
                    + "    private AllcraftDoubleJump() {\n"
                    + "    }\n\n"
                    + "    public static void tick(LocalPlayer player, boolean pressed, boolean onGround, boolean passenger, boolean flying) {\n"
                    + "        State state = STATES.computeIfAbsent(player, ignored -> new State());\n"
                    + "        if (onGround) {\n"
                    + "            state.ready = true;\n"
                    + "        } else if (pressed && !state.wasPressed && state.ready && !passenger && !flying) {\n"
                    + "            player.setDeltaMovement(\n"
                    + "                player.getDeltaMovement().x, Math.max(player.getDeltaMovement().y, 0.62D), player.getDeltaMovement().z\n"
                    + "            );\n"
                    + "            state.ready = false;\n"
                    + "        }\n"
                    + "        state.wasPressed = pressed;\n"
                    + "    }\n\n"
                    + "    public static void allcraftActivate() {\n"
                    + "        System.setProperty(\"allcraft.runtime.double-jump\", \"activated\");\n"
                    + "    }\n"
                    + "\n"
                    + "    private static final class State {\n"
                    + "        private boolean ready;\n"
                    + "        private boolean wasPressed;\n"
                    + "    }\n"
                    + "}\n"
            )
        );
        return edits;
    }

    private static List<SourceEdit> flyingBoatEdits(Path sourceRoot) throws IOException {
        return List.of(
            editExisting(
                sourceRoot,
                LOCAL_PLAYER,
                source -> insertAfterOnce(
                    source,
                    "ALLCRAFT PATCH: flying-boats behavior",
                    "            boat.setInput(this.input.keyPresses.left(), this.input.keyPresses.right(), this.input.keyPresses.forward(), this.input.keyPresses.backward());",
                    "\n            // ALLCRAFT PATCH: flying-boats behavior\n"
                        + "            Vec3 allcraftBoatVelocity = boat.getDeltaMovement();\n"
                        + "            double allcraftBoatLift = this.input.keyPresses.jump() ? 0.30D : this.input.keyPresses.shift() ? -0.20D : 0.0D;\n"
                        + "            boat.setDeltaMovement(allcraftBoatVelocity.x, allcraftBoatLift, allcraftBoatVelocity.z);"
                )
            )
        );
    }

    private static List<SourceEdit> noWorldGenerationEdits(Path sourceRoot) throws IOException {
        List<SourceEdit> edits = new ArrayList<>();
        for (String path : List.of(CLIENT_NOISE_GENERATOR, SERVER_NOISE_GENERATOR)) {
            edits.add(
                editExisting(sourceRoot, path, source -> {
                    String updated = replaceMethodBodyOnce(
                        source,
                        "ALLCRAFT PATCH: no-world-gen fill",
                        "public CompletableFuture<ChunkAccess> fillFromNoise",
                        "// ALLCRAFT PATCH: no-world-gen fill\nreturn CompletableFuture.completedFuture(centerChunk);"
                    );
                    updated = replaceMethodBodyOnce(
                        updated,
                        "ALLCRAFT PATCH: no-world-gen surface",
                        "public void buildSurface(",
                        "// ALLCRAFT PATCH: no-world-gen surface"
                    );
                    updated = replaceMethodBodyOnce(
                        updated,
                        "ALLCRAFT PATCH: no-world-gen carvers",
                        "public void applyCarvers(",
                        "// ALLCRAFT PATCH: no-world-gen carvers"
                    );
                    return replaceMethodBodyOnce(
                        updated,
                        "ALLCRAFT PATCH: no-world-gen mobs",
                        "public void spawnOriginalMobs(",
                        "// ALLCRAFT PATCH: no-world-gen mobs"
                    );
                })
            );
        }

        for (String path : List.of(CLIENT_CHUNK_GENERATOR, SERVER_CHUNK_GENERATOR)) {
            edits.add(
                editExisting(sourceRoot, path, source -> {
                    String updated = replaceMethodBodyOnce(
                        source,
                        "ALLCRAFT PATCH: no-world-gen decoration",
                        "public void applyBiomeDecoration(",
                        "// ALLCRAFT PATCH: no-world-gen decoration"
                    );
                    updated = replaceMethodBodyOnce(
                        updated,
                        "ALLCRAFT PATCH: no-world-gen structures",
                        "public void createStructures(",
                        "// ALLCRAFT PATCH: no-world-gen structures"
                    );
                    return replaceMethodBodyOnce(
                        updated,
                        "ALLCRAFT PATCH: no-world-gen references",
                        "public void createReferences(",
                        "// ALLCRAFT PATCH: no-world-gen references"
                    );
                })
            );
        }

        return edits;
    }

    private static List<SourceEdit> newClassEdits(Path sourceRoot) throws IOException {
        return List.of(
            editGenerated(
                sourceRoot,
                "client/net/minecraft/allcraft/generated/ClientRuntimeProbe.java",
                probeSource("ClientRuntimeProbe", "client")
            ),
            editGenerated(
                sourceRoot,
                "server/net/minecraft/allcraft/generated/ServerRuntimeProbe.java",
                probeSource("ServerRuntimeProbe", "server")
            )
        );
    }

    private static String probeSource(String className, String side) {
        return "package net.minecraft.allcraft.generated;\n\n"
            + "/** A class that does not exist in the installed Allcraft JAR. */\n"
            + "public final class "
            + className
            + " {\n"
            + "    private "
            + className
            + "() {\n"
            + "    }\n\n"
            + "    public static void allcraftActivate() {\n"
            + "        System.setProperty(\"allcraft.runtime.probe."
            + side
            + "\", \"activated\");\n"
            + "        System.out.println(\"[Allcraft] Dynamically added "
            + side
            + " class activated\");\n"
            + "    }\n"
            + "}\n";
    }

    private static List<Path> clientSources(Path sourceRoot, String testName) {
        return switch (testName) {
            case "double-jump", "flying-boats" -> {
                List<Path> sources = new ArrayList<>();
                sources.add(sourceRoot.resolve(LOCAL_PLAYER));
                Path helper = sourceRoot.resolve(DOUBLE_JUMP_HELPER);
                if (Files.isRegularFile(helper)) {
                    sources.add(helper);
                }
                yield sources;
            }
            case "new-class" -> List.of(sourceRoot.resolve("client/net/minecraft/allcraft/generated/ClientRuntimeProbe.java"));
            default -> List.of();
        };
    }

    private static List<Path> serverSources(Path sourceRoot, String testName) {
        return switch (testName) {
            case "no-world-gen" -> List.of(sourceRoot.resolve(SERVER_NOISE_GENERATOR), sourceRoot.resolve(SERVER_CHUNK_GENERATOR));
            case "new-class" -> List.of(sourceRoot.resolve("server/net/minecraft/allcraft/generated/ServerRuntimeProbe.java"));
            default -> List.of();
        };
    }

    private static List<String> clientEntrypoints(String testName) {
        return switch (testName) {
            case "double-jump" -> List.of("net.minecraft.client.player.AllcraftDoubleJump");
            case "new-class" -> List.of("net.minecraft.allcraft.generated.ClientRuntimeProbe");
            default -> List.of();
        };
    }

    private static List<String> serverEntrypoints(String testName) {
        return testName.equals("new-class") ? List.of("net.minecraft.allcraft.generated.ServerRuntimeProbe") : List.of();
    }

    private static String instructions(String testName) {
        return switch (testName) {
            case "double-jump" -> "Jump, release Space while airborne, then press Space again";
            case "flying-boats" -> "Ride a boat: hold Space to rise and Shift to descend";
            case "no-world-gen" -> "Travel into never-generated chunks; new terrain should be empty";
            case "new-class" -> "New client and server classes were loaded and their activation methods ran";
            default -> testName;
        };
    }

    private static SourceEdit editExisting(Path sourceRoot, String relative, UnaryOperator<String> transform) throws IOException {
        Path path = sourceRoot.resolve(relative);
        if (!Files.isRegularFile(path)) {
            throw new IOException("Patch source file is missing: " + path);
        }

        String original = Files.readString(path, StandardCharsets.UTF_8);
        String updated;
        try {
            updated = transform.apply(original);
        } catch (RuntimeException e) {
            throw new IOException("Cannot apply source patch to " + relative + ": " + e.getMessage(), e);
        }
        return new SourceEdit(path, original, updated, true);
    }

    private static SourceEdit editGenerated(Path sourceRoot, String relative, String generated) throws IOException {
        Path path = sourceRoot.resolve(relative);
        boolean existed = Files.isRegularFile(path);
        String original = existed ? Files.readString(path, StandardCharsets.UTF_8) : "";
        return new SourceEdit(path, original, generated, existed);
    }

    private static void applyEdits(List<SourceEdit> edits) throws IOException {
        List<SourceEdit> written = new ArrayList<>();
        try {
            for (SourceEdit edit : edits) {
                if (!edit.updated().equals(edit.original()) || !edit.existed()) {
                    writeAtomically(edit.path(), edit.updated());
                    written.add(edit);
                }
            }
        } catch (IOException e) {
            restoreEdits(written);
            throw e;
        }
    }

    private static void restoreEdits(List<SourceEdit> edits) {
        for (int index = edits.size() - 1; index >= 0; index--) {
            SourceEdit edit = edits.get(index);
            try {
                if (edit.existed()) {
                    writeAtomically(edit.path(), edit.original());
                } else {
                    Files.deleteIfExists(edit.path());
                }
            } catch (IOException restoreError) {
                LOGGER.error("Failed to restore world source {}", edit.path(), restoreError);
            }
        }
    }

    private static String insertBeforeOnce(String source, String marker, String anchor, String insertion) {
        if (source.contains(marker)) {
            return source;
        }

        int index = source.indexOf(anchor);
        if (index < 0) {
            throw new IllegalArgumentException("missing anchor: " + anchor);
        }

        return source.substring(0, index) + insertion + source.substring(index);
    }

    private static String insertAfterOnce(String source, String marker, String anchor, String insertion) {
        if (source.contains(marker)) {
            return source;
        }

        int index = source.indexOf(anchor);
        if (index < 0) {
            throw new IllegalArgumentException("missing anchor: " + anchor);
        }

        int end = index + anchor.length();
        return source.substring(0, end) + insertion + source.substring(end);
    }

    private static String replaceOrInsertMarkedBlock(String source, String marker, String anchor, String replacement) {
        int markerIndex = source.indexOf(marker);
        if (markerIndex < 0) {
            return insertBeforeOnce(source, marker, anchor, replacement);
        }

        int blockStart = source.lastIndexOf('\n', markerIndex);
        blockStart = blockStart < 0 ? 0 : blockStart + 1;
        int anchorIndex = source.indexOf(anchor, markerIndex);
        if (anchorIndex < 0) {
            throw new IllegalArgumentException("missing anchor after marker: " + anchor);
        }
        return source.substring(0, blockStart) + replacement + source.substring(anchorIndex);
    }

    private static String replaceMethodBodyOnce(String source, String marker, String signature, String body) {
        if (source.contains(marker)) {
            return source;
        }

        int signatureIndex = source.indexOf(signature);
        if (signatureIndex < 0) {
            throw new IllegalArgumentException("missing method: " + signature);
        }

        int openBrace = source.indexOf('{', signatureIndex + signature.length());
        if (openBrace < 0) {
            throw new IllegalArgumentException("missing method body: " + signature);
        }

        int depth = 1;
        int closeBrace = openBrace + 1;
        while (closeBrace < source.length() && depth > 0) {
            char value = source.charAt(closeBrace);
            if (value == '{') {
                depth++;
            } else if (value == '}') {
                depth--;
            }
            closeBrace++;
        }

        if (depth != 0) {
            throw new IllegalArgumentException("unterminated method body: " + signature);
        }

        String indent = lineIndent(source, signatureIndex);
        String bodyIndent = indent + (indent.contains("\t") ? "\t" : "    ");
        String formattedBody = indentLines(body, bodyIndent);
        String replacement = "{\n" + formattedBody + "\n" + indent + "}";
        return source.substring(0, openBrace) + replacement + source.substring(closeBrace);
    }

    private static String lineIndent(String source, int index) {
        int lineStart = source.lastIndexOf('\n', index);
        lineStart = lineStart < 0 ? 0 : lineStart + 1;
        int cursor = lineStart;
        while (cursor < source.length()) {
            char value = source.charAt(cursor);
            if (value != ' ' && value != '\t') {
                break;
            }
            cursor++;
        }

        return source.substring(lineStart, cursor);
    }

    private static String indentLines(String value, String indent) {
        return indent + value.replace("\n", "\n" + indent);
    }

    private static Compilation compileJava(List<Path> sourceFiles, Path cacheRoot) throws IOException {
        long startedAt = System.nanoTime();
        String key = cacheKey(sourceFiles);
        Path entry = cacheRoot.resolve(key);
        Path output = entry.resolve("classes");
        Path complete = entry.resolve("complete");
        if (Files.isRegularFile(complete)) {
            Map<String, byte[]> cached = readClasses(output);
            if (!cached.isEmpty()) {
                LOGGER.info("Allcraft compiler cache hit {} for {} source file(s)", key.substring(0, 12), sourceFiles.size());
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
        command.add("-J-Xmx768m");
        command.add("-J-XX:ActiveProcessorCount=2");
        command.add("-J-XX:+UseSerialGC");
        command.add("-classpath");
        command.add(System.getProperty("java.class.path"));
        command.add("-d");
        command.add(temporaryOutput.toString());
        command.add("-encoding");
        command.add("UTF-8");
        command.add("-g");
        command.add("-parameters");
        command.add("-proc:none");
        command.add("-implicit:none");
        sourceFiles.stream().map(path -> path.toAbsolutePath().normalize().toString()).forEach(command::add);

        Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(compilerLog.toFile()).start();
        boolean finished;
        try {
            finished = process.waitFor(5L, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            deleteTree(temporary);
            throw new IOException("Interrupted while compiling runtime patch", e);
        }
        if (!finished) {
            process.destroyForcibly();
            deleteTree(temporary);
            throw new IOException("Runtime patch compilation timed out after five minutes");
        }
        if (process.exitValue() != 0) {
            String outputText = Files.isRegularFile(compilerLog) ? Files.readString(compilerLog, StandardCharsets.UTF_8) : "";
            deleteTree(temporary);
            throw new IOException("Runtime patch compilation failed:\n" + outputText.substring(0, Math.min(outputText.length(), 12000)));
        }

        Map<String, byte[]> classes = readClasses(temporaryOutput);
        if (classes.isEmpty()) {
            deleteTree(temporary);
            throw new IOException("Runtime patch compiler produced no class files for " + sourceFiles);
        }
        Files.writeString(temporary.resolve("complete"), key + System.lineSeparator(), StandardCharsets.UTF_8);
        deleteTree(entry);
        try {
            Files.move(temporary, entry, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temporary, entry);
        }
        LOGGER.info(
            "Allcraft external compiler built {} class(es) from {} source file(s) in {} ms",
            classes.size(),
            sourceFiles.size(),
            elapsedMillis(startedAt)
        );
        return new Compilation(classes, false, elapsedMillis(startedAt));
    }

    private static Map<String, byte[]> readClasses(Path output) throws IOException {
        Map<String, byte[]> classes = new LinkedHashMap<>();
        if (!Files.isDirectory(output)) {
            return classes;
        }
        try (Stream<Path> paths = Files.walk(output)) {
            for (Path classFile : paths.filter(Files::isRegularFile).filter(path -> path.toString().endsWith(".class")).sorted().toList()) {
                String entry = output.relativize(classFile).toString().replace(classFile.getFileSystem().getSeparator(), "/");
                classes.put(entry, Files.readAllBytes(classFile));
            }
        }
        return classes;
    }

    private static Path configuredJavac() throws IOException {
        String configured = System.getProperty("allcraft.javac");
        Path javac = configured == null || configured.isBlank()
            ? Path.of(System.getProperty("java.home"), "bin", isWindows() ? "javac.exe" : "javac")
            : Path.of(configured);
        javac = javac.toAbsolutePath().normalize();
        if (!Files.isExecutable(javac)) {
            throw new IOException("Allcraft javac is missing or not executable: " + javac);
        }
        return javac;
    }

    private static String cacheKey(List<Path> sourceFiles) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            updateDigest(digest, CACHE_FORMAT);
            updateDigest(digest, configuredJavac().toString());
            for (Path source : sourceFiles.stream().map(path -> path.toAbsolutePath().normalize()).sorted().toList()) {
                updateDigest(digest, source.toString());
                digest.update(Files.readAllBytes(source));
            }
            String classPath = System.getProperty("java.class.path");
            updateDigest(digest, classPath);
            for (String value : classPath.split(java.util.regex.Pattern.quote(File.pathSeparator))) {
                Path entry = Path.of(value);
                if (Files.exists(entry)) {
                    updateDigest(digest, entry.toAbsolutePath().normalize().toString());
                    updateDigest(digest, Long.toString(Files.size(entry)));
                    updateDigest(digest, Long.toString(Files.getLastModifiedTime(entry).toMillis()));
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void updateDigest(MessageDigest digest, String value) {
        digest.update(value.getBytes(StandardCharsets.UTF_8));
        digest.update((byte)0);
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
    }

    private static long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000L;
    }

    private static void writeAtomically(Path path, String content) throws IOException {
        Files.createDirectories(path.getParent());
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
        Files.writeString(
            temporary,
            content,
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE
        );
        try {
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
        }
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

    public record Build(
        Map<String, byte[]> clientClasses,
        Map<String, byte[]> serverClasses,
        List<String> changedFiles,
        List<String> clientEntrypoints,
        List<String> serverEntrypoints,
        String instructions,
        boolean clientCacheHit,
        boolean serverCacheHit,
        long compilationMillis
    ) {
    }

    private record Compilation(Map<String, byte[]> classes, boolean cacheHit, long elapsedMillis) {
        private static Compilation empty() {
            return new Compilation(Map.of(), true, 0L);
        }
    }

    private record SourceEdit(Path path, String original, String updated, boolean existed) {
    }
}
