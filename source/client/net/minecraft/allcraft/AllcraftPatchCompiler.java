package net.minecraft.allcraft;

import com.mojang.logging.LogUtils;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
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
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import javax.imageio.ImageIO;
import org.slf4j.Logger;

/** Server-side compiler for real source-changing Allcraft test patches. */
public final class AllcraftPatchCompiler {
    public static final List<String> RUNTIME_TEST_NAMES = List.of("double-jump", "no-world-gen", "flying-boats", "new-class");
    public static final List<String> RESOURCE_TEST_NAMES = List.of(
        "live-texture", "live-model", "live-sound", "live-language", "live-recipe", "live-resource-delete",
        "asset-new-sprite", "asset-resized-sprite", "asset-animated-sprite", "asset-atlas-delete", "asset-font",
        "asset-shader", "asset-particle", "asset-gui", "asset-live-sound", "asset-mass-model", "asset-atlas-manifest"
    );
    public static final List<String> PATCH_TEST_NAMES = Stream.concat(RUNTIME_TEST_NAMES.stream(), RESOURCE_TEST_NAMES.stream()).toList();
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
        if (!PATCH_TEST_NAMES.contains(testName)) {
            throw new IOException("Unknown source/resource patch test " + testName);
        }

        if (!Files.isDirectory(worldSource)) {
            throw new IOException("World source directory is missing: " + worldSource);
        }

        List<SourceEdit> edits = switch (testName) {
            case "double-jump" -> doubleJumpEdits(worldSource);
            case "flying-boats" -> flyingBoatEdits(worldSource);
            case "no-world-gen" -> noWorldGenerationEdits(worldSource);
            case "new-class" -> newClassEdits(worldSource);
            default -> List.of();
        };
        List<ResourceEdit> resourceEdits = resourceEdits(worldSource, testName);
        List<ResourceDeletion> resourceDeletions = resourceDeletions(worldSource, testName);

        try {
            applyEdits(edits);
            applyResourceEdits(resourceEdits);
            applyResourceDeletions(resourceDeletions);
            List<Path> clientSources = clientSources(worldSource, testName);
            List<Path> serverSources = serverSources(worldSource, testName);
            Compilation clientCompilation = clientSources.isEmpty()
                ? Compilation.empty()
                : compileJava(clientSources, workRoot.resolve("client"));
            Compilation serverCompilation = serverSources.isEmpty()
                ? Compilation.empty()
                : compileJava(serverSources, workRoot.resolve("server"));
            List<String> changedFiles = Stream.concat(
                    edits.stream().map(edit -> worldSource.relativize(edit.path()).toString()),
                    Stream.concat(
                        resourceEdits.stream().map(edit -> worldSource.relativize(edit.path()).toString()),
                        resourceDeletions.stream().map(edit -> worldSource.relativize(edit.path()).toString())
                    )
                )
                .sorted()
                .toList();
            return new Build(
                clientCompilation.classes(),
                serverCompilation.classes(),
                collectResources(worldSource, resourceEdits, "client/assets/", "assets/"),
                collectResources(worldSource, resourceEdits, "server/data/", "data/"),
                collectDeletedResources(worldSource, resourceDeletions, "client/assets/", "assets/"),
                collectDeletedResources(worldSource, resourceDeletions, "server/data/", "data/"),
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
            restoreResourceEdits(resourceEdits);
            restoreResourceDeletions(resourceDeletions);
            if (e instanceof IOException ioException) {
                throw ioException;
            }

            throw new IOException("Failed to compile runtime patch " + testName, e);
        }
    }

    /**
     * Applies a named test's source fixture only. The production revision differ/compiler then
     * discovers and builds these edits exactly as it does arbitrary externally edited source.
     */
    public static Fixture applyFixture(Path worldSource, String testName) throws IOException {
        if (!PATCH_TEST_NAMES.contains(testName)) {
            throw new IOException("Unknown source/resource patch fixture " + testName);
        }
        if (!Files.isDirectory(worldSource)) {
            throw new IOException("World source directory is missing: " + worldSource);
        }
        List<SourceEdit> edits = switch (testName) {
            case "double-jump" -> doubleJumpEdits(worldSource);
            case "flying-boats" -> flyingBoatEdits(worldSource);
            case "no-world-gen" -> noWorldGenerationEdits(worldSource);
            case "new-class" -> newClassEdits(worldSource);
            default -> List.of();
        };
        List<ResourceEdit> resourceEdits = resourceEdits(worldSource, testName);
        List<ResourceDeletion> resourceDeletions = resourceDeletions(worldSource, testName);
        try {
            applyEdits(edits);
            applyResourceEdits(resourceEdits);
            applyResourceDeletions(resourceDeletions);
            return new Fixture(instructions(testName), clientEntrypoints(testName), serverEntrypoints(testName));
        } catch (Exception e) {
            restoreEdits(edits);
            restoreResourceEdits(resourceEdits);
            restoreResourceDeletions(resourceDeletions);
            if (e instanceof IOException ioException) {
                throw ioException;
            }
            throw new IOException("Failed to apply source fixture " + testName, e);
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

    private static List<ResourceEdit> resourceEdits(Path sourceRoot, String testName) throws IOException {
        return switch (testName) {
            case "live-texture" -> List.of(
                resourceEditGenerated(
                    sourceRoot,
                    "client/assets/minecraft/textures/block/dirt.png",
                    Base64.getDecoder()
                        .decode("iVBORw0KGgoAAAANSUhEUgAAABAAAAAQCAYAAAAf8/9hAAAAJ0lEQVR42mP4z/D/PzIWEBBAwYTkGYaBAaRqQJcfDgaMpoPRdADEAACUFh+QovJGAAAAAElFTkSuQmCC")
                )
            );
            case "live-model" -> List.of(
                resourceEditGenerated(
                    sourceRoot,
                    "client/assets/minecraft/models/block/dirt.json",
                    ("{\n"
                            + "  \"parent\": \"minecraft:block/cube_all\",\n"
                            + "  \"textures\": {\n"
                            + "    \"all\": \"minecraft:block/diamond_block\"\n"
                            + "  }\n"
                            + "}\n")
                        .getBytes(StandardCharsets.UTF_8)
                )
            );
            case "live-sound" -> List.of(
                resourceEditGenerated(
                    sourceRoot,
                    "client/assets/minecraft/sounds/random/orb.ogg",
                    Files.readAllBytes(sourceRoot.resolve("client/assets/minecraft/sounds/event/mob_effects/bad_omen.ogg"))
                )
            );
            case "live-language" -> List.of(
                resourceEditExisting(sourceRoot, "client/assets/minecraft/lang/en_us.json", bytes -> {
                    String language = new String(bytes, StandardCharsets.UTF_8);
                    String original = "\"block.minecraft.dirt\": \"Dirt\"";
                    String replacement = "\"block.minecraft.dirt\": \"Allcraft Live Dirt\"";
                    if (language.contains(original)) {
                        language = language.replace(original, replacement);
                    } else if (!language.contains(replacement)) {
                        throw new IllegalArgumentException("missing dirt translation");
                    }
                    return language.getBytes(StandardCharsets.UTF_8);
                })
            );
            case "live-recipe" -> {
                byte[] recipe = ("{\n"
                        + "  \"type\": \"minecraft:crafting_shapeless\",\n"
                        + "  \"category\": \"misc\",\n"
                        + "  \"ingredients\": [\n"
                        + "    \"minecraft:dirt\"\n"
                        + "  ],\n"
                        + "  \"result\": {\n"
                        + "    \"count\": 1,\n"
                        + "    \"id\": \"minecraft:diamond\"\n"
                        + "  }\n"
                        + "}\n")
                    .getBytes(StandardCharsets.UTF_8);
                yield List.of(
                    resourceEditGenerated(sourceRoot, "client/data/allcraft/recipe/dirt_to_diamond.json", recipe),
                    resourceEditGenerated(sourceRoot, "server/data/allcraft/recipe/dirt_to_diamond.json", recipe)
                );
            }
            case "asset-new-sprite" -> List.of(
                resourceEditGenerated(
                    sourceRoot,
                    "client/assets/allcraft/textures/block/live_block.png",
                    checkerTexture(16, 16, 0xFFFF7F00, 0xFF00FFFF)
                ),
                resourceEditGenerated(
                    sourceRoot, "client/assets/minecraft/models/block/dirt.json", cubeAllModel("allcraft:block/live_block")
                )
            );
            case "asset-resized-sprite" -> List.of(
                resourceEditGenerated(
                    sourceRoot,
                    "client/assets/minecraft/textures/block/dirt.png",
                    checkerTexture(32, 32, 0xFF30D050, 0xFF102070)
                )
            );
            case "asset-animated-sprite" -> List.of(
                resourceEditGenerated(
                    sourceRoot, "client/assets/minecraft/textures/block/dirt.png", animatedCheckerTexture()
                ),
                resourceEditGenerated(
                    sourceRoot,
                    "client/assets/minecraft/textures/block/dirt.png.mcmeta",
                    "{\n  \"animation\": {\n    \"frametime\": 4,\n    \"interpolate\": false\n  }\n}\n".getBytes(StandardCharsets.UTF_8)
                )
            );
            case "asset-font" -> List.of(
                resourceEditGenerated(
                    sourceRoot,
                    "client/assets/allcraft/font/live.json",
                    Files.readAllBytes(sourceRoot.resolve("client/assets/minecraft/font/default.json"))
                )
            );
            case "asset-shader" -> List.of(
                resourceEditExisting(sourceRoot, "client/assets/minecraft/shaders/include/fog.glsl", bytes -> {
                    String shader = new String(bytes, StandardCharsets.UTF_8);
                    return ("// ALLCRAFT LIVE SHADER REVISION\n" + shader).getBytes(StandardCharsets.UTF_8);
                })
            );
            case "asset-particle" -> List.of(
                resourceEditGenerated(
                    sourceRoot,
                    "client/assets/allcraft/textures/particle/live.png",
                    checkerTexture(16, 16, 0xFFFF00FF, 0xFFFFFF00)
                ),
                resourceEditGenerated(
                    sourceRoot,
                    "client/assets/minecraft/particles/flame.json",
                    "{\n  \"textures\": [\n    \"allcraft:live\"\n  ]\n}\n".getBytes(StandardCharsets.UTF_8)
                )
            );
            case "asset-gui" -> List.of(
                resourceEditGenerated(
                    sourceRoot,
                    "client/assets/minecraft/textures/gui/sprites/hud/crosshair.png",
                    checkerTexture(15, 15, 0xFFFF2020, 0xFFFFFFFF)
                )
            );
            case "asset-live-sound" -> List.of(
                resourceEditGenerated(
                    sourceRoot,
                    "client/assets/minecraft/sounds/records/cat.ogg",
                    Files.readAllBytes(sourceRoot.resolve("client/assets/minecraft/sounds/event/mob_effects/bad_omen.ogg"))
                )
            );
            case "asset-mass-model" -> List.of(
                resourceEditGenerated(
                    sourceRoot, "client/assets/minecraft/models/block/stone.json", cubeAllModel("minecraft:block/emerald_block")
                )
            );
            case "asset-atlas-manifest" -> List.of(
                resourceEditGenerated(
                    sourceRoot,
                    "client/assets/allcraft/textures/manifest_sprite.png",
                    checkerTexture(16, 16, 0xFF40A0FF, 0xFFFFA040)
                ),
                resourceEditExisting(sourceRoot, "client/assets/minecraft/atlases/blocks.json", bytes -> {
                    String atlas = new String(bytes, StandardCharsets.UTF_8);
                    String ending = "\n  ]\n}";
                    String source = ",\n    {\n      \"type\": \"minecraft:single\",\n      \"resource\": \"allcraft:manifest_sprite\"\n    }";
                    if (!atlas.contains("allcraft:manifest_sprite")) {
                        atlas = atlas.replace(ending, source + ending);
                    }
                    return atlas.getBytes(StandardCharsets.UTF_8);
                }),
                resourceEditGenerated(
                    sourceRoot, "client/assets/minecraft/models/block/dirt.json", cubeAllModel("allcraft:manifest_sprite")
                )
            );
            default -> List.of();
        };
    }

    private static List<ResourceDeletion> resourceDeletions(Path sourceRoot, String testName) throws IOException {
        Path path = switch (testName) {
            case "live-resource-delete" -> sourceRoot.resolve("client/assets/minecraft/textures/misc/forcefield.png");
            case "asset-atlas-delete" -> sourceRoot.resolve("client/assets/minecraft/textures/block/dirt.png");
            default -> null;
        };
        if (path == null) {
            return List.of();
        }
        if (!Files.isRegularFile(path)) {
            throw new IOException("Patch resource file is missing: " + path);
        }
        return List.of(new ResourceDeletion(path, Files.readAllBytes(path)));
    }

    private static byte[] cubeAllModel(String texture) {
        return ("{\n"
                + "  \"parent\": \"minecraft:block/cube_all\",\n"
                + "  \"textures\": {\n"
                + "    \"all\": \""
                + texture
                + "\"\n"
                + "  }\n"
                + "}\n")
            .getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] checkerTexture(int width, int height, int first, int second) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setRGB(x, y, ((x / 4) + (y / 4) & 1) == 0 ? first : second);
            }
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        if (!ImageIO.write(image, "png", output)) {
            throw new IOException("PNG encoder is unavailable");
        }
        return output.toByteArray();
    }

    private static byte[] animatedCheckerTexture() throws IOException {
        BufferedImage image = new BufferedImage(16, 32, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 32; y++) {
            for (int x = 0; x < 16; x++) {
                boolean alternate = ((x / 4) + ((y % 16) / 4) & 1) != 0;
                int color = y < 16 ? (alternate ? 0xFF101010 : 0xFFFF00FF) : (alternate ? 0xFF0030FF : 0xFFFFFF00);
                image.setRGB(x, y, color);
            }
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        if (!ImageIO.write(image, "png", output)) {
            throw new IOException("PNG encoder is unavailable");
        }
        return output.toByteArray();
    }

    private static ResourceEdit resourceEditExisting(Path sourceRoot, String relative, UnaryOperator<byte[]> transform) throws IOException {
        Path path = sourceRoot.resolve(relative);
        if (!Files.isRegularFile(path)) {
            throw new IOException("Patch resource file is missing: " + path);
        }
        byte[] original = Files.readAllBytes(path);
        byte[] updated;
        try {
            updated = transform.apply(original);
        } catch (RuntimeException e) {
            throw new IOException("Cannot apply resource patch to " + relative + ": " + e.getMessage(), e);
        }
        return new ResourceEdit(path, original, updated, true);
    }

    private static ResourceEdit resourceEditGenerated(Path sourceRoot, String relative, byte[] generated) throws IOException {
        Path path = sourceRoot.resolve(relative);
        boolean existed = Files.isRegularFile(path);
        byte[] original = existed ? Files.readAllBytes(path) : new byte[0];
        return new ResourceEdit(path, original, generated, existed);
    }

    private static void applyResourceEdits(List<ResourceEdit> edits) throws IOException {
        List<ResourceEdit> written = new ArrayList<>();
        try {
            for (ResourceEdit edit : edits) {
                if (!java.util.Arrays.equals(edit.updated(), edit.original()) || !edit.existed()) {
                    writeAtomically(edit.path(), edit.updated());
                    written.add(edit);
                }
            }
        } catch (IOException e) {
            restoreResourceEdits(written);
            throw e;
        }
    }

    private static void restoreResourceEdits(List<ResourceEdit> edits) {
        for (int index = edits.size() - 1; index >= 0; index--) {
            ResourceEdit edit = edits.get(index);
            try {
                if (edit.existed()) {
                    writeAtomically(edit.path(), edit.original());
                } else {
                    Files.deleteIfExists(edit.path());
                }
            } catch (IOException restoreError) {
                LOGGER.error("Failed to restore world resource {}", edit.path(), restoreError);
            }
        }
    }

    private static void applyResourceDeletions(List<ResourceDeletion> deletions) throws IOException {
        List<ResourceDeletion> deleted = new ArrayList<>();
        try {
            for (ResourceDeletion deletion : deletions) {
                if (Files.deleteIfExists(deletion.path())) {
                    deleted.add(deletion);
                }
            }
        } catch (IOException e) {
            restoreResourceDeletions(deleted);
            throw e;
        }
    }

    private static void restoreResourceDeletions(List<ResourceDeletion> deletions) {
        for (ResourceDeletion deletion : deletions) {
            try {
                writeAtomically(deletion.path(), deletion.original());
            } catch (IOException restoreError) {
                LOGGER.error("Failed to restore deleted world resource {}", deletion.path(), restoreError);
            }
        }
    }

    private static Map<String, byte[]> collectResources(
        Path sourceRoot, List<ResourceEdit> edits, String sourcePrefix, String artifactPrefix
    ) {
        Map<String, byte[]> resources = new LinkedHashMap<>();
        for (ResourceEdit edit : edits) {
            String relative = sourceRoot.relativize(edit.path()).toString().replace(File.separatorChar, '/');
            if (relative.startsWith(sourcePrefix)) {
                resources.put(artifactPrefix + relative.substring(sourcePrefix.length()), edit.updated());
            }
        }
        return Map.copyOf(resources);
    }

    private static List<String> collectDeletedResources(
        Path sourceRoot, List<ResourceDeletion> deletions, String sourcePrefix, String artifactPrefix
    ) {
        List<String> resources = new ArrayList<>();
        for (ResourceDeletion deletion : deletions) {
            String relative = sourceRoot.relativize(deletion.path()).toString().replace(File.separatorChar, '/');
            if (relative.startsWith(sourcePrefix)) {
                resources.add(artifactPrefix + relative.substring(sourcePrefix.length()));
            }
        }
        return List.copyOf(resources);
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
            case "live-texture" -> "Dirt textures should become a magenta-and-black checkerboard immediately";
            case "live-model" -> "Dirt blocks should immediately render with the diamond-block model texture";
            case "live-sound" -> "The automatic experience-orb preview should play the ominous-effect sound";
            case "live-language" -> "Dirt should now be named 'Allcraft Live Dirt' in inventories";
            case "live-recipe" -> "Craft one dirt by itself to receive one diamond";
            case "live-resource-delete" -> "The forcefield texture should be absent from the active resource manager";
            case "asset-new-sprite" -> "Dirt should use the newly added orange-and-cyan allcraft:live_block atlas sprite";
            case "asset-resized-sprite" -> "The dirt sprite should change from 16x16 to a green-and-blue 32x32 sprite without atlas restitch";
            case "asset-animated-sprite" -> "Dirt should animate between magenta/black and yellow/blue frames";
            case "asset-atlas-delete" -> "Dirt should use the missing sprite while old meshes remain valid until atomic model activation";
            case "asset-font" -> "The new allcraft:live font definition should be available immediately";
            case "asset-shader" -> "The fog include should be transactionally recompiled with rendering uninterrupted";
            case "asset-particle" -> "Spawn minecraft:flame particles; they should use the new allcraft:live sprite";
            case "asset-gui" -> "The HUD crosshair should become a red-and-white checker";
            case "asset-live-sound" -> "A currently playing records/cat sound should restart using the replacement audio";
            case "asset-mass-model" -> "All visible stone should switch atomically to the emerald-block model";
            case "asset-atlas-manifest" -> "A sprite introduced by an atlases/blocks.json single source should render on dirt";
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

    private static void writeAtomically(Path path, byte[] content) throws IOException {
        Files.createDirectories(path.getParent());
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
        Files.write(
            temporary,
            content,
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
        Map<String, byte[]> clientResources,
        Map<String, byte[]> serverResources,
        List<String> deletedClientResources,
        List<String> deletedServerResources,
        List<String> changedFiles,
        List<String> clientEntrypoints,
        List<String> serverEntrypoints,
        String instructions,
        boolean clientCacheHit,
        boolean serverCacheHit,
        long compilationMillis
    ) {
    }

    public record Fixture(String instructions, List<String> clientEntrypoints, List<String> serverEntrypoints) {
    }

    private record Compilation(Map<String, byte[]> classes, boolean cacheHit, long elapsedMillis) {
        private static Compilation empty() {
            return new Compilation(Map.of(), true, 0L);
        }
    }

    private record SourceEdit(Path path, String original, String updated, boolean existed) {
    }

    private record ResourceEdit(Path path, byte[] original, byte[] updated, boolean existed) {
    }

    private record ResourceDeletion(Path path, byte[] original) {
    }
}
