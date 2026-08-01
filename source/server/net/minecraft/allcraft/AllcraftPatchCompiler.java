package net.minecraft.allcraft;

import com.mojang.logging.LogUtils;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import org.slf4j.Logger;

/** Server-side compiler for real source-changing Allcraft test patches. */
public final class AllcraftPatchCompiler {
    public static final List<String> RUNTIME_TEST_NAMES = List.of("double-jump", "no-world-gen", "flying-boats", "new-class");
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
            Path clientOutput = workRoot.resolve("classes/client");
            Path serverOutput = workRoot.resolve("classes/server");
            deleteTree(clientOutput);
            deleteTree(serverOutput);

            List<Path> clientSources = clientSources(worldSource, testName);
            List<Path> serverSources = serverSources(worldSource, testName);
            Map<String, byte[]> clientClasses = clientSources.isEmpty() ? Map.of() : compileJava(clientSources, clientOutput);
            Map<String, byte[]> serverClasses = serverSources.isEmpty() ? Map.of() : compileJava(serverSources, serverOutput);
            List<String> changedFiles = edits.stream().map(edit -> worldSource.relativize(edit.path()).toString()).sorted().toList();
            return new Build(
                clientClasses,
                serverClasses,
                changedFiles,
                clientEntrypoints(testName),
                serverEntrypoints(testName),
                instructions(testName)
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
                String updated = insertBeforeOnce(
                    source,
                    "ALLCRAFT PATCH: double-jump fields",
                    "    private boolean doLimitedCrafting = false;",
                    "    // ALLCRAFT PATCH: double-jump fields\n"
                        + "    private boolean allcraftDoubleJumpReady;\n"
                        + "    private boolean allcraftDoubleJumpWasPressed;\n\n"
                );
                return insertBeforeOnce(
                    updated,
                    "ALLCRAFT PATCH: double-jump behavior",
                    "        super.aiStep();",
                    "        // ALLCRAFT PATCH: double-jump behavior\n"
                        + "        boolean allcraftDoubleJumpPressed = this.input.keyPresses.jump();\n"
                        + "        if (this.onGround()) {\n"
                        + "            this.allcraftDoubleJumpReady = true;\n"
                        + "        } else if (allcraftDoubleJumpPressed\n"
                        + "            && !this.allcraftDoubleJumpWasPressed\n"
                        + "            && this.allcraftDoubleJumpReady\n"
                        + "            && !this.isPassenger()\n"
                        + "            && !abilities.flying) {\n"
                        + "            this.setDeltaMovement(\n"
                        + "                this.getDeltaMovement().x,\n"
                        + "                AllcraftDoubleJump.verticalVelocity(this.getDeltaMovement().y),\n"
                        + "                this.getDeltaMovement().z\n"
                        + "            );\n"
                        + "            this.allcraftDoubleJumpReady = false;\n"
                        + "        }\n"
                        + "        this.allcraftDoubleJumpWasPressed = allcraftDoubleJumpPressed;\n\n"
                );
            })
        );
        edits.add(
            editGenerated(
                sourceRoot,
                DOUBLE_JUMP_HELPER,
                "package net.minecraft.client.player;\n\n"
                    + "/** Added to the running game by the Allcraft double-jump patch. */\n"
                    + "public final class AllcraftDoubleJump {\n"
                    + "    private AllcraftDoubleJump() {\n"
                    + "    }\n\n"
                    + "    public static double verticalVelocity(double currentVelocity) {\n"
                    + "        return Math.max(currentVelocity, 0.62D);\n"
                    + "    }\n\n"
                    + "    public static void allcraftActivate() {\n"
                    + "        System.setProperty(\"allcraft.runtime.double-jump\", \"activated\");\n"
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

    private static Map<String, byte[]> compileJava(List<Path> sourceFiles, Path output) throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IOException("The bundled Allcraft SDK does not provide the Java compiler");
        }

        Files.createDirectories(output);
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager files = compiler.getStandardFileManager(diagnostics, Locale.ROOT, StandardCharsets.UTF_8)) {
            files.setLocationFromPaths(StandardLocation.CLASS_OUTPUT, List.of(output));
            Iterable<? extends JavaFileObject> units = files.getJavaFileObjectsFromPaths(sourceFiles);
            List<String> options = List.of(
                "-classpath",
                System.getProperty("java.class.path"),
                "-encoding",
                "UTF-8",
                "-g",
                "-parameters",
                "-proc:none",
                "-implicit:none"
            );
            boolean success = Boolean.TRUE.equals(compiler.getTask(null, files, diagnostics, options, null, units).call());
            if (!success) {
                StringBuilder message = new StringBuilder("Runtime patch compilation failed");
                for (Diagnostic<? extends JavaFileObject> diagnostic : diagnostics.getDiagnostics()) {
                    message.append(System.lineSeparator())
                        .append(diagnostic.getKind())
                        .append(" ")
                        .append(diagnostic.getSource() == null ? "" : diagnostic.getSource().getName())
                        .append(":")
                        .append(diagnostic.getLineNumber())
                        .append(" ")
                        .append(diagnostic.getMessage(Locale.ROOT));
                }
                throw new IOException(message.toString());
            }
        }

        Map<String, byte[]> classes = new LinkedHashMap<>();
        try (Stream<Path> paths = Files.walk(output)) {
            for (Path classFile : paths.filter(Files::isRegularFile).filter(path -> path.toString().endsWith(".class")).sorted().toList()) {
                String entry = output.relativize(classFile).toString().replace(classFile.getFileSystem().getSeparator(), "/");
                classes.put(entry, Files.readAllBytes(classFile));
            }
        }
        if (classes.isEmpty()) {
            throw new IOException("Runtime patch compiler produced no class files for " + sourceFiles);
        }

        return classes;
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
        String instructions
    ) {
    }

    private record SourceEdit(Path path, String original, String updated, boolean existed) {
    }
}
