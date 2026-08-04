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
    public static final List<String> RUNTIME_TEST_NAMES = List.of(
        "double-jump", "no-world-gen", "flying-boats", "new-class", "registry-block", "new-item", "new-particle", "new-mob",
        "new-music-disc", "new-keybind", "lapis-crafting-table"
    );
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
            case "registry-block" -> registryBlockEdits(worldSource);
            case "new-item" -> newItemEdits(worldSource);
            case "new-particle" -> newParticleEdits(worldSource);
            case "new-mob" -> newMobEdits(worldSource);
            case "new-music-disc" -> newMusicDiscEdits(worldSource);
            case "new-keybind" -> newKeybindEdits(worldSource);
            case "lapis-crafting-table" -> lapisCraftingTableEdits(worldSource);
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
            case "registry-block" -> registryBlockEdits(worldSource);
            case "new-item" -> newItemEdits(worldSource);
            case "new-particle" -> newParticleEdits(worldSource);
            case "new-mob" -> newMobEdits(worldSource);
            case "new-music-disc" -> newMusicDiscEdits(worldSource);
            case "new-keybind" -> newKeybindEdits(worldSource);
            case "lapis-crafting-table" -> lapisCraftingTableEdits(worldSource);
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

    private static List<SourceEdit> registryBlockEdits(Path sourceRoot) throws IOException {
        return List.of(
            editGenerated(
                sourceRoot,
                "shared/net/minecraft/allcraft/generated/SharedRegistryBlock.java",
                registryBlockSource()
            ),
            editGenerated(
                sourceRoot,
                "client/net/minecraft/allcraft/generated/ClientRegistryBlock.java",
                registryBlockWrapper("ClientRegistryBlock", "client")
            ),
            editGenerated(
                sourceRoot,
                "server/net/minecraft/allcraft/generated/ServerRegistryBlock.java",
                registryBlockWrapper("ServerRegistryBlock", "server")
            )
        );
    }

    private static List<SourceEdit> newItemEdits(Path sourceRoot) throws IOException {
        return List.of(
            editGenerated(
                sourceRoot,
                "shared/net/minecraft/allcraft/generated/SharedNewItem.java",
                """
                package net.minecraft.allcraft.generated;

                import net.minecraft.allcraft.AllcraftRegistries;
                import net.minecraft.core.registries.BuiltInRegistries;
                import net.minecraft.core.registries.Registries;
                import net.minecraft.resources.Identifier;
                import net.minecraft.resources.ResourceKey;
                import net.minecraft.world.item.Item;

                public final class SharedNewItem {
                    private static final Identifier ID = Identifier.fromNamespaceAndPath("allcraft", "runtime_crystal");
                    private static final ResourceKey<Item> KEY = ResourceKey.create(Registries.ITEM, ID);
                    public static Item item;

                    private SharedNewItem() {
                    }

                    public static void activate() {
                        item = AllcraftRegistries.registerLazy(
                            BuiltInRegistries.ITEM, KEY, () -> new Item(new Item.Properties().setId(KEY).stacksTo(16))
                        );
                    }
                }
                """
            ),
            editGenerated(
                sourceRoot,
                "client/net/minecraft/allcraft/generated/ClientNewItem.java",
                newItemSource("ClientNewItem", "client")
            ),
            editGenerated(
                sourceRoot,
                "server/net/minecraft/allcraft/generated/ServerNewItem.java",
                newItemSource("ServerNewItem", "server")
            )
        );
    }

    private static String newItemSource(String className, String side) {
        return "package net.minecraft.allcraft.generated;\n\n"
            + "import net.minecraft.core.registries.BuiltInRegistries;\n"
            + "\n"
            + "public final class " + className + " {\n"
            + "    private " + className + "() {\n"
            + "    }\n\n"
            + "    public static void allcraftActivate() {\n"
            + "        SharedNewItem.activate();\n"
            + "        System.setProperty(\"allcraft.new-item." + side + "\", Integer.toString(BuiltInRegistries.ITEM.getId(SharedNewItem.item)));\n"
            + "    }\n"
            + "}\n";
    }

    private static List<SourceEdit> newParticleEdits(Path sourceRoot) throws IOException {
        return List.of(
            editGenerated(
                sourceRoot,
                "shared/net/minecraft/allcraft/generated/SharedNewParticle.java",
                """
                package net.minecraft.allcraft.generated;

                import net.minecraft.allcraft.AllcraftRegistries;
                import net.minecraft.core.particles.ParticleType;
                import net.minecraft.core.particles.SimpleParticleType;
                import net.minecraft.core.registries.BuiltInRegistries;
                import net.minecraft.core.registries.Registries;
                import net.minecraft.resources.Identifier;
                import net.minecraft.resources.ResourceKey;

                public final class SharedNewParticle {
                    private static final Identifier ID = Identifier.fromNamespaceAndPath("allcraft", "runtime_spark");
                    private static final ResourceKey<ParticleType<?>> KEY = ResourceKey.create(Registries.PARTICLE_TYPE, ID);
                    public static SimpleParticleType type;

                    private SharedNewParticle() {
                    }

                    @SuppressWarnings("unchecked")
                    public static void activate() {
                        type = (SimpleParticleType)AllcraftRegistries.registerLazy(
                            BuiltInRegistries.PARTICLE_TYPE, KEY, RuntimeParticleType::new
                        );
                    }

                    public static final class RuntimeParticleType extends SimpleParticleType {
                        public RuntimeParticleType() {
                            super(false);
                        }
                    }
                }
                """
            ),
            editGenerated(
                sourceRoot,
                "client/net/minecraft/allcraft/generated/ClientNewParticle.java",
                newParticleSource("ClientNewParticle", "client", true)
            ),
            editGenerated(
                sourceRoot,
                "server/net/minecraft/allcraft/generated/ServerNewParticle.java",
                newParticleSource("ServerNewParticle", "server", false)
            )
        );
    }

    private static String newParticleSource(String className, String side, boolean client) {
        return "package net.minecraft.allcraft.generated;\n\n"
            + (client ? "import net.minecraft.client.Minecraft;\nimport net.minecraft.client.particle.FlameParticle;\n" : "")
            + "import net.minecraft.core.registries.BuiltInRegistries;\n"
            + "\n"
            + "public final class " + className + " {\n"
            + "    private " + className + "() {\n"
            + "    }\n\n"
            + "    public static void allcraftActivate() {\n"
            + "        SharedNewParticle.activate();\n"
            + (client ? "        Minecraft.getInstance().particleEngine.allcraftRegister(SharedNewParticle.type, FlameParticle.Provider::new);\n" : "")
            + "        System.setProperty(\"allcraft.new-particle." + side + "\", Integer.toString(BuiltInRegistries.PARTICLE_TYPE.getId(SharedNewParticle.type)));\n"
            + "    }\n"
            + "}\n";
    }

    private static List<SourceEdit> newMobEdits(Path sourceRoot) throws IOException {
        return List.of(
            editGenerated(sourceRoot, "shared/net/minecraft/allcraft/generated/SharedNewMob.java", sharedNewMobSource()),
            editGenerated(sourceRoot, "client/net/minecraft/allcraft/generated/ClientNewMob.java", newMobSource("ClientNewMob", "client", true)),
            editGenerated(sourceRoot, "server/net/minecraft/allcraft/generated/ServerNewMob.java", newMobSource("ServerNewMob", "server", false))
        );
    }

    private static String sharedNewMobSource() {
        return """
            package net.minecraft.allcraft.generated;

            import net.minecraft.allcraft.AllcraftRegistries;
            import net.minecraft.core.registries.BuiltInRegistries;
            import net.minecraft.core.registries.Registries;
            import net.minecraft.resources.Identifier;
            import net.minecraft.resources.ResourceKey;
            import net.minecraft.world.entity.EntityType;
            import net.minecraft.world.entity.MobCategory;
            import net.minecraft.world.entity.SpawnPlacementTypes;
            import net.minecraft.world.entity.SpawnPlacements;
            import net.minecraft.world.entity.ai.attributes.DefaultAttributes;
            import net.minecraft.world.entity.animal.Animal;
            import net.minecraft.world.entity.animal.cow.Cow;
            import net.minecraft.world.level.Level;
            import net.minecraft.world.level.levelgen.Heightmap;

            public final class SharedNewMob {
                private static final Identifier ID = Identifier.fromNamespaceAndPath("allcraft", "runtime_cow");
                private static final ResourceKey<EntityType<?>> KEY = ResourceKey.create(Registries.ENTITY_TYPE, ID);
                public static EntityType<RuntimeCow> type;

                private SharedNewMob() {
                }

                @SuppressWarnings("unchecked")
                public static void activate() {
                    type = (EntityType<RuntimeCow>)(EntityType<?>)AllcraftRegistries.registerLazy(
                        BuiltInRegistries.ENTITY_TYPE, KEY,
                        () -> EntityType.Builder.of(RuntimeCow::new, MobCategory.CREATURE)
                            .sized(0.9F, 1.4F).eyeHeight(1.3F).passengerAttachments(1.36875F).clientTrackingRange(10).build(KEY)
                    );
                    DefaultAttributes.register(type, Cow.createAttributes().build());
                    SpawnPlacements.register(
                        type, SpawnPlacementTypes.ON_GROUND, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, Animal::checkAnimalSpawnRules
                    );
                }

                public static final class RuntimeCow extends Cow {
                    public RuntimeCow(EntityType<? extends Cow> type, Level level) {
                        super(type, level);
                    }
                }
            }
            """;
    }

    private static String newMobSource(String className, String side, boolean client) {
        String clientImports = client
            ? "import net.minecraft.client.renderer.entity.CowRenderer;\nimport net.minecraft.client.renderer.entity.EntityRenderers;\n"
            : "";
        String clientRegistration = client ? "        EntityRenderers.register(SharedNewMob.type, CowRenderer::new);\n" : "";
        return ("""
            package net.minecraft.allcraft.generated;

            %simport net.minecraft.core.registries.BuiltInRegistries;

            public final class %s {
                private %s() {
                }

                public static void allcraftActivate() {
                    SharedNewMob.activate();
            %s        System.setProperty("allcraft.new-mob.%s", Integer.toString(BuiltInRegistries.ENTITY_TYPE.getId(SharedNewMob.type)));
                }
            }
            """).formatted(clientImports, className, className, clientRegistration, side);
    }

    private static List<SourceEdit> newKeybindEdits(Path sourceRoot) throws IOException {
        return List.of(
            editGenerated(
                sourceRoot,
                "client/net/minecraft/allcraft/generated/ClientNewKeybind.java",
                """
                package net.minecraft.allcraft.generated;

                import com.mojang.blaze3d.platform.InputConstants;
                import net.minecraft.allcraft.AllcraftKeyMappings;
                import net.minecraft.client.Minecraft;
                import net.minecraft.client.KeyMapping;
                import net.minecraft.network.chat.Component;
                import net.minecraft.resources.Identifier;

                public final class ClientNewKeybind {
                    public static KeyMapping mapping;

                    private ClientNewKeybind() {
                    }

                    public static void allcraftActivate() {
                        KeyMapping.Category category = AllcraftKeyMappings.category(
                            Identifier.fromNamespaceAndPath("allcraft", "runtime")
                        );
                        mapping = AllcraftKeyMappings.register(
                            "key.allcraft.runtime_launch", InputConstants.Type.KEYSYM, 75, category, ClientNewKeybind::launch
                        );
                        System.setProperty("allcraft.new-keybind.client", mapping.saveString());
                    }

                    private static void launch() {
                        Minecraft minecraft = Minecraft.getInstance();
                        if (minecraft.player != null) {
                            minecraft.player.setDeltaMovement(minecraft.player.getDeltaMovement().add(0.0, 1.0, 0.0));
                            minecraft.gui.hud.setOverlayMessage(Component.literal("Runtime Launch!"), false);
                            int count = Integer.parseInt(System.getProperty("allcraft.new-keybind.activations", "0"));
                            System.setProperty("allcraft.new-keybind.activations", Integer.toString(count + 1));
                        }
                    }
                }
                """
            )
        );
    }

    private static List<SourceEdit> lapisCraftingTableEdits(Path sourceRoot) throws IOException {
        return List.of(
            editGenerated(
                sourceRoot,
                "shared/net/minecraft/allcraft/generated/SharedLapisCraftingTable.java",
                lapisCraftingTableSource()
            ),
            editGenerated(
                sourceRoot,
                "client/net/minecraft/allcraft/generated/ClientLapisCraftingTable.java",
                lapisCraftingTableWrapper("ClientLapisCraftingTable", "client", true)
            ),
            editGenerated(
                sourceRoot,
                "server/net/minecraft/allcraft/generated/ServerLapisCraftingTable.java",
                lapisCraftingTableWrapper("ServerLapisCraftingTable", "server", false)
            )
        );
    }

    private static String lapisCraftingTableSource() {
        return ("""
            package net.minecraft.allcraft.generated;

            import com.mojang.serialization.MapCodec;
            import java.util.List;
            import java.util.Optional;
            import java.util.Set;
            import net.minecraft.allcraft.AllcraftRegistries;
            import net.minecraft.core.BlockPos;
            import net.minecraft.core.registries.BuiltInRegistries;
            import net.minecraft.core.registries.Registries;
            import net.minecraft.network.RegistryFriendlyByteBuf;
            import net.minecraft.network.chat.Component;
            import net.minecraft.network.codec.StreamCodec;
            import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
            import net.minecraft.resources.Identifier;
            import net.minecraft.resources.ResourceKey;
            import net.minecraft.server.level.ServerLevel;
            import net.minecraft.server.level.ServerPlayer;
            import net.minecraft.world.Container;
            import net.minecraft.world.InteractionResult;
            import net.minecraft.world.MenuProvider;
            import net.minecraft.world.entity.player.Inventory;
            import net.minecraft.world.entity.player.Player;
            import net.minecraft.world.flag.FeatureFlags;
            import net.minecraft.world.inventory.AbstractContainerMenu;
            import net.minecraft.world.inventory.AbstractCraftingMenu;
            import net.minecraft.world.inventory.ContainerLevelAccess;
            import net.minecraft.world.inventory.CraftingContainer;
            import net.minecraft.world.inventory.MenuType;
            import net.minecraft.world.inventory.RecipeBookType;
            import net.minecraft.world.inventory.ResultContainer;
            import net.minecraft.world.inventory.Slot;
            import net.minecraft.world.item.BlockItem;
            import net.minecraft.world.item.Item;
            import net.minecraft.world.item.ItemStack;
            import net.minecraft.world.item.Items;
            import net.minecraft.world.item.crafting.CraftingInput;
            import net.minecraft.world.item.crafting.CraftingRecipe;
            import net.minecraft.world.item.crafting.CustomRecipe;
            import net.minecraft.world.item.crafting.RecipeHolder;
            import net.minecraft.world.item.crafting.RecipeSerializer;
            import net.minecraft.world.item.crafting.RecipeType;
            import net.minecraft.world.level.Level;
            import net.minecraft.world.level.block.Block;
            import net.minecraft.world.level.block.Blocks;
            import net.minecraft.world.level.block.EntityBlock;
            import net.minecraft.world.level.block.entity.BlockEntity;
            import net.minecraft.world.level.block.entity.BlockEntityType;
            import net.minecraft.world.level.block.state.BlockBehaviour;
            import net.minecraft.world.level.block.state.BlockState;
            import net.minecraft.world.phys.BlockHitResult;

            public final class SharedLapisCraftingTable {
                private static final Identifier ID = Identifier.fromNamespaceAndPath("allcraft", "lapis_crafting_table");
                private static final Identifier RECIPE_ID = Identifier.fromNamespaceAndPath("allcraft", "lapis_table");
                private static final ResourceKey<Block> BLOCK_KEY = ResourceKey.create(Registries.BLOCK, ID);
                private static final ResourceKey<Item> ITEM_KEY = ResourceKey.create(Registries.ITEM, ID);
                private static final ResourceKey<BlockEntityType<?>> BLOCK_ENTITY_KEY = ResourceKey.create(Registries.BLOCK_ENTITY_TYPE, ID);
                private static final ResourceKey<MenuType<?>> MENU_KEY = ResourceKey.create(Registries.MENU, ID);
                private static final ResourceKey<RecipeType<?>> RECIPE_TYPE_KEY = ResourceKey.create(Registries.RECIPE_TYPE, RECIPE_ID);
                private static final ResourceKey<RecipeSerializer<?>> RECIPE_SERIALIZER_KEY = ResourceKey.create(Registries.RECIPE_SERIALIZER, RECIPE_ID);
                public static Block block;
                public static Item item;
                public static BlockEntityType<RuntimeBlockEntity> blockEntityType;
                public static MenuType<RuntimeMenu> menuType;
                public static RecipeType<CraftingRecipe> recipeType;
                public static RecipeSerializer<RuntimeRecipe> recipeSerializer;

                private SharedLapisCraftingTable() {
                }

                @SuppressWarnings("unchecked")
                public static void activate() {
                    block = AllcraftRegistries.registerLazy(
                        BuiltInRegistries.BLOCK, BLOCK_KEY,
                        () -> new RuntimeBlock(BlockBehaviour.Properties.ofFullCopy(Blocks.CRAFTING_TABLE).setId(BLOCK_KEY))
                    );
                    blockEntityType = (BlockEntityType<RuntimeBlockEntity>)(BlockEntityType<?>)AllcraftRegistries.registerLazy(
                        BuiltInRegistries.BLOCK_ENTITY_TYPE, BLOCK_ENTITY_KEY,
                        () -> new BlockEntityType<>(RuntimeBlockEntity::new, Set.of(block))
                    );
                    menuType = (MenuType<RuntimeMenu>)(MenuType<?>)AllcraftRegistries.registerLazy(
                        BuiltInRegistries.MENU, MENU_KEY,
                        () -> new MenuType<>(RuntimeMenu::new, FeatureFlags.VANILLA_SET)
                    );
                    item = AllcraftRegistries.registerLazy(
                        BuiltInRegistries.ITEM, ITEM_KEY,
                        () -> new BlockItem(block, new Item.Properties().setId(ITEM_KEY).useBlockDescriptionPrefix())
                    );
                    recipeType = (RecipeType<CraftingRecipe>)(RecipeType<?>)AllcraftRegistries.registerLazy(
                        BuiltInRegistries.RECIPE_TYPE, RECIPE_TYPE_KEY, () -> new RecipeType<CraftingRecipe>() {
                            @Override
                            public String toString() {
                                return RECIPE_ID.toString();
                            }
                        }
                    );
                    recipeSerializer = (RecipeSerializer<RuntimeRecipe>)(RecipeSerializer<?>)AllcraftRegistries.registerLazy(
                        BuiltInRegistries.RECIPE_SERIALIZER, RECIPE_SERIALIZER_KEY,
                        () -> new RecipeSerializer<>(RuntimeRecipe.MAP_CODEC, RuntimeRecipe.STREAM_CODEC)
                    );
                }

                public static final class RuntimeBlock extends Block implements EntityBlock {
                    public RuntimeBlock(BlockBehaviour.Properties properties) {
                        super(properties);
                    }

                    @Override
                    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
                        return new RuntimeBlockEntity(pos, state);
                    }

                    @Override
                    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
                        if (!level.isClientSide() && level.getBlockEntity(pos) instanceof RuntimeBlockEntity blockEntity) {
                            player.openMenu(blockEntity);
                        }
                        return InteractionResult.SUCCESS;
                    }
                }

                public static final class RuntimeBlockEntity extends BlockEntity implements MenuProvider {
                    public RuntimeBlockEntity(BlockPos pos, BlockState state) {
                        super(blockEntityType, pos, state);
                    }

                    @Override
                    public Component getDisplayName() {
                        return Component.translatable("container.allcraft.lapis_crafting_table");
                    }

                    @Override
                    public AbstractContainerMenu createMenu(int containerId, Inventory inventory, Player player) {
                        return new RuntimeMenu(containerId, inventory, ContainerLevelAccess.create(player.level(), this.getBlockPos()));
                    }
                }

                public static final class RuntimeMenu extends AbstractCraftingMenu {
                    private final ContainerLevelAccess access;
                    private final Player player;
                    private boolean placingRecipe;

                    public RuntimeMenu(int containerId, Inventory inventory) {
                        this(containerId, inventory, ContainerLevelAccess.NULL);
                    }

                    public RuntimeMenu(int containerId, Inventory inventory, ContainerLevelAccess access) {
                        super(menuType, containerId, 3, 3);
                        this.access = access;
                        this.player = inventory.player;
                        this.addResultSlot(this.player, 124, 35);
                        this.addCraftingGridSlots(30, 17);
                        this.addStandardInventorySlots(inventory, 8, 84);
                    }

                    private static void updateResult(
                        AbstractContainerMenu menu, ServerLevel level, Player player, CraftingContainer inputSlots, ResultContainer resultSlots,
                        RecipeHolder<CraftingRecipe> recipeHint
                    ) {
                        CraftingInput input = inputSlots.asCraftInput();
                        ServerPlayer serverPlayer = (ServerPlayer)player;
                        ItemStack result = ItemStack.EMPTY;
                        Optional<RecipeHolder<CraftingRecipe>> recipe = level.getServer().getRecipeManager()
                            .getRecipeFor(recipeType, input, level, recipeHint);
                        if (recipe.isPresent() && resultSlots.setRecipeUsed(serverPlayer, recipe.get())) {
                            result = recipe.get().value().assemble(input);
                        }
                        resultSlots.setItem(0, result);
                        menu.setRemoteSlot(0, result);
                        serverPlayer.connection.send(new ClientboundContainerSetSlotPacket(menu.containerId, menu.incrementStateId(), 0, result));
                    }

                    @Override
                    public void slotsChanged(Container container) {
                        if (!this.placingRecipe) {
                            this.access.execute((level, pos) -> {
                                if (level instanceof ServerLevel serverLevel) {
                                    updateResult(this, serverLevel, this.player, this.craftSlots, this.resultSlots, null);
                                }
                            });
                        }
                    }

                    @Override
                    protected void beginPlacingRecipe() {
                        this.placingRecipe = true;
                    }

                    @Override
                    protected void finishPlacingRecipe(ServerLevel level, RecipeHolder<CraftingRecipe> recipe) {
                        this.placingRecipe = false;
                        updateResult(this, level, this.player, this.craftSlots, this.resultSlots, recipe);
                    }

                    @Override
                    public void removed(Player player) {
                        super.removed(player);
                        this.access.execute((level, pos) -> this.clearContainer(player, this.craftSlots));
                    }

                    @Override
                    public boolean stillValid(Player player) {
                        return stillValid(this.access, player, block);
                    }

                    @Override
                    public ItemStack quickMoveStack(Player player, int slotIndex) {
                        ItemStack clicked = ItemStack.EMPTY;
                        Slot slot = this.slots.get(slotIndex);
                        if (slot != null && slot.hasItem()) {
                            ItemStack stack = slot.getItem();
                            clicked = stack.copy();
                            if (slotIndex == 0) {
                                stack.getItem().onCraftedBy(stack, player);
                                if (!this.moveItemStackTo(stack, 10, 46, true)) return ItemStack.EMPTY;
                                slot.onQuickCraft(stack, clicked);
                            } else if (slotIndex >= 10 && slotIndex < 46) {
                                if (!this.moveItemStackTo(stack, 1, 10, false)) {
                                    if (slotIndex < 37) {
                                        if (!this.moveItemStackTo(stack, 37, 46, false)) return ItemStack.EMPTY;
                                    } else if (!this.moveItemStackTo(stack, 10, 37, false)) return ItemStack.EMPTY;
                                }
                            } else if (!this.moveItemStackTo(stack, 10, 46, false)) return ItemStack.EMPTY;
                            if (stack.isEmpty()) slot.setByPlayer(ItemStack.EMPTY); else slot.setChanged();
                            if (stack.getCount() == clicked.getCount()) return ItemStack.EMPTY;
                            slot.onTake(player, stack);
                            if (slotIndex == 0) player.drop(stack, false);
                        }
                        return clicked;
                    }

                    @Override
                    public boolean canTakeItemForPickAll(ItemStack carried, Slot target) {
                        return target.container != this.resultSlots && super.canTakeItemForPickAll(carried, target);
                    }

                    @Override
                    public Slot getResultSlot() {
                        return this.slots.get(0);
                    }

                    @Override
                    public List<Slot> getInputGridSlots() {
                        return this.slots.subList(1, 10);
                    }

                    @Override
                    public RecipeBookType getRecipeBookType() {
                        return RecipeBookType.CRAFTING;
                    }

                    @Override
                    protected Player owner() {
                        return this.player;
                    }
                }

                public static final class RuntimeRecipe extends CustomRecipe {
                    public static final RuntimeRecipe INSTANCE = new RuntimeRecipe();
                    public static final MapCodec<RuntimeRecipe> MAP_CODEC = MapCodec.unit(INSTANCE);
                    public static final StreamCodec<RegistryFriendlyByteBuf, RuntimeRecipe> STREAM_CODEC = StreamCodec.unit(INSTANCE);

                    @Override
                    public boolean matches(CraftingInput input, Level level) {
                        return input.ingredientCount() == 1 && input.items().stream().anyMatch(stack -> stack.is(Items.LAPIS_LAZULI));
                    }

                    @Override
                    public ItemStack assemble(CraftingInput input) {
                        return new ItemStack(Items.DIAMOND);
                    }

                    @Override
                    public RecipeSerializer<RuntimeRecipe> getSerializer() {
                        return recipeSerializer;
                    }

                    @Override
                    public RecipeType<CraftingRecipe> getType() {
                        return recipeType;
                    }
                }
            }
            """);
    }

    private static String lapisCraftingTableWrapper(String className, String side, boolean client) {
        if (!client) {
            return "package net.minecraft.allcraft.generated;\n\n"
                + "import net.minecraft.core.registries.BuiltInRegistries;\n\n"
                + "public final class " + className + " {\n"
                + "    private " + className + "() { }\n"
                + "    public static void allcraftActivate() {\n"
                + "        SharedLapisCraftingTable.activate();\n"
                + "        System.setProperty(\"allcraft.lapis-crafting-table." + side + "\", Integer.toString(BuiltInRegistries.MENU.getId(SharedLapisCraftingTable.menuType)));\n"
                + "    }\n"
                + "}\n";
        }
        return """
            package net.minecraft.allcraft.generated;

            import net.minecraft.client.gui.GuiGraphicsExtractor;
            import net.minecraft.client.gui.screens.MenuScreens;
            import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
            import net.minecraft.client.renderer.RenderPipelines;
            import net.minecraft.core.registries.BuiltInRegistries;
            import net.minecraft.network.chat.Component;
            import net.minecraft.resources.Identifier;
            import net.minecraft.world.entity.player.Inventory;

            public final class ClientLapisCraftingTable {
                private ClientLapisCraftingTable() {
                }

                public static void allcraftActivate() {
                    SharedLapisCraftingTable.activate();
                    MenuScreens.register(SharedLapisCraftingTable.menuType, RuntimeScreen::new);
                    System.setProperty(
                        "allcraft.lapis-crafting-table.client",
                        Integer.toString(BuiltInRegistries.MENU.getId(SharedLapisCraftingTable.menuType))
                    );
                }

                public static final class RuntimeScreen extends AbstractContainerScreen<SharedLapisCraftingTable.RuntimeMenu> {
                    private static final Identifier TEXTURE = Identifier.withDefaultNamespace("textures/gui/container/crafting_table.png");

                    public RuntimeScreen(SharedLapisCraftingTable.RuntimeMenu menu, Inventory inventory, Component title) {
                        super(menu, inventory, title);
                    }

                    @Override
                    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
                        super.extractBackground(graphics, mouseX, mouseY, a);
                        graphics.blit(
                            RenderPipelines.GUI_TEXTURED, TEXTURE, this.leftPos, this.topPos,
                            0.0F, 0.0F, this.imageWidth, this.imageHeight, 256, 256
                        );
                    }
                }
            }
            """;
    }

    private static List<SourceEdit> newMusicDiscEdits(Path sourceRoot) throws IOException {
        return List.of(
            editGenerated(
                sourceRoot,
                "shared/net/minecraft/allcraft/generated/SharedMusicDisc.java",
                newMusicDiscSource()
            ),
            editGenerated(
                sourceRoot,
                "client/net/minecraft/allcraft/generated/ClientMusicDisc.java",
                newMusicDiscWrapper("ClientMusicDisc", "client")
            ),
            editGenerated(
                sourceRoot,
                "server/net/minecraft/allcraft/generated/ServerMusicDisc.java",
                newMusicDiscWrapper("ServerMusicDisc", "server")
            )
        );
    }

    private static String newMusicDiscSource() {
        return "package net.minecraft.allcraft.generated;\n\n"
            + "import net.minecraft.allcraft.AllcraftRegistries;\n"
            + "import net.minecraft.core.Holder;\n"
            + "import net.minecraft.core.registries.BuiltInRegistries;\n"
            + "import net.minecraft.core.registries.Registries;\n"
            + "import net.minecraft.network.chat.Component;\n"
            + "import net.minecraft.resources.Identifier;\n"
            + "import net.minecraft.resources.ResourceKey;\n"
            + "import net.minecraft.sounds.SoundEvent;\n"
            + "import net.minecraft.world.item.Item;\n"
            + "import net.minecraft.world.item.JukeboxSong;\n\n"
            + "public final class SharedMusicDisc {\n"
            + "    private static final Identifier ITEM_ID = Identifier.fromNamespaceAndPath(\"allcraft\", \"runtime_music_disc\");\n"
            + "    private static final Identifier SOUND_ID = Identifier.fromNamespaceAndPath(\"allcraft\", \"music_disc.runtime\");\n"
            + "    private static final ResourceKey<Item> ITEM_KEY = ResourceKey.create(Registries.ITEM, ITEM_ID);\n"
            + "    private static final ResourceKey<SoundEvent> SOUND_KEY = ResourceKey.create(Registries.SOUND_EVENT, SOUND_ID);\n"
            + "    private static final ResourceKey<JukeboxSong> SONG_KEY = ResourceKey.create(Registries.JUKEBOX_SONG, ITEM_ID);\n"
            + "    public static Item item;\n\n"
            + "    private SharedMusicDisc() {\n"
            + "    }\n\n"
            + "    public static void activate() {\n"
            + "        AllcraftRegistries.registerLazy(\n"
            + "            BuiltInRegistries.SOUND_EVENT, SOUND_KEY, () -> SoundEvent.createVariableRangeEvent(SOUND_ID)\n"
            + "        );\n"
            + "        Holder<SoundEvent> soundHolder = BuiltInRegistries.SOUND_EVENT.get(SOUND_KEY).orElseThrow();\n"
            + "        AllcraftRegistries.registerLazy(\n"
            + "            Registries.JUKEBOX_SONG, SONG_KEY,\n"
            + "            () -> new JukeboxSong(soundHolder, Component.literal(\"Runtime Symphony\"), 6.0F, 14)\n"
            + "        );\n"
            + "        item = AllcraftRegistries.registerLazy(\n"
            + "            BuiltInRegistries.ITEM, ITEM_KEY,\n"
            + "            () -> new Item(new Item.Properties().setId(ITEM_KEY).stacksTo(1).jukeboxPlayable(SONG_KEY))\n"
            + "        );\n"
            + "    }\n"
            + "}\n";
    }

    private static String newMusicDiscWrapper(String className, String side) {
        return "package net.minecraft.allcraft.generated;\n\n"
            + "import net.minecraft.core.registries.BuiltInRegistries;\n\n"
            + "public final class " + className + " {\n"
            + "    private " + className + "() { }\n"
            + "    public static void allcraftActivate() {\n"
            + "        SharedMusicDisc.activate();\n"
            + "        System.setProperty(\"allcraft.new-music-disc." + side + "\", Integer.toString(BuiltInRegistries.ITEM.getId(SharedMusicDisc.item)));\n"
            + "    }\n"
            + "}\n";
    }

    private static String registryBlockSource() {
        return "package net.minecraft.allcraft.generated;\n\n"
            + "import net.minecraft.allcraft.AllcraftRegistries;\n"
            + "import net.minecraft.core.registries.BuiltInRegistries;\n"
            + "import net.minecraft.core.registries.Registries;\n"
            + "import net.minecraft.resources.Identifier;\n"
            + "import net.minecraft.resources.ResourceKey;\n"
            + "import net.minecraft.world.item.BlockItem;\n"
            + "import net.minecraft.world.item.Item;\n"
            + "import net.minecraft.world.level.block.Block;\n"
            + "import net.minecraft.world.level.block.Blocks;\n"
            + "import net.minecraft.world.level.block.state.BlockBehaviour;\n\n"
            + "public final class SharedRegistryBlock {\n"
            + "    private static final Identifier ID = Identifier.fromNamespaceAndPath(\"allcraft\", \"runtime_block\");\n"
            + "    private static final ResourceKey<Block> BLOCK_KEY = ResourceKey.create(Registries.BLOCK, ID);\n"
            + "    private static final ResourceKey<Item> ITEM_KEY = ResourceKey.create(Registries.ITEM, ID);\n"
            + "    public static Block block;\n"
            + "    public static Item item;\n\n"
            + "    private SharedRegistryBlock() {\n"
            + "    }\n\n"
            + "    public static void activate() {\n"
            + "        block = AllcraftRegistries.registerLazy(\n"
            + "            BuiltInRegistries.BLOCK, BLOCK_KEY,\n"
            + "            () -> new Block(BlockBehaviour.Properties.ofFullCopy(Blocks.DIAMOND_BLOCK).setId(BLOCK_KEY))\n"
            + "        );\n"
            + "        item = AllcraftRegistries.registerLazy(\n"
            + "            BuiltInRegistries.ITEM, ITEM_KEY,\n"
            + "            () -> new BlockItem(block, new Item.Properties().setId(ITEM_KEY).useBlockDescriptionPrefix())\n"
            + "        );\n"
            + "    }\n"
            + "}\n";
    }

    private static String registryBlockWrapper(String className, String side) {
        return "package net.minecraft.allcraft.generated;\n\n"
            + "import net.minecraft.core.registries.BuiltInRegistries;\n\n"
            + "public final class " + className + " {\n"
            + "    private " + className + "() { }\n"
            + "    public static void allcraftActivate() {\n"
            + "        SharedRegistryBlock.activate();\n"
            + "        System.setProperty(\"allcraft.registry-block." + side + "\", Integer.toString(BuiltInRegistries.BLOCK.getId(SharedRegistryBlock.block)));\n"
            + "    }\n"
            + "}\n";
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
            case "new-item" -> {
                byte[] recipe = ("{\n"
                        + "  \"type\": \"minecraft:crafting_shapeless\",\n"
                        + "  \"category\": \"misc\",\n"
                        + "  \"ingredients\": [\"minecraft:amethyst_shard\", \"minecraft:redstone\"],\n"
                        + "  \"result\": {\"count\": 1, \"id\": \"allcraft:runtime_crystal\"}\n"
                        + "}\n")
                    .getBytes(StandardCharsets.UTF_8);
                yield List.of(
                    resourceEditGenerated(
                        sourceRoot,
                        "client/assets/allcraft/textures/item/runtime_crystal.png",
                        checkerTexture(16, 16, 0xFF40E0FF, 0xFF9030FF)
                    ),
                    resourceEditGenerated(
                        sourceRoot,
                        "client/assets/allcraft/models/item/runtime_crystal.json",
                        ("{\n"
                                + "  \"parent\": \"minecraft:item/generated\",\n"
                                + "  \"textures\": {\"layer0\": \"allcraft:item/runtime_crystal\"}\n"
                                + "}\n")
                            .getBytes(StandardCharsets.UTF_8)
                    ),
                    resourceEditGenerated(
                        sourceRoot,
                        "client/assets/allcraft/items/runtime_crystal.json",
                        "{\n  \"model\": {\"type\": \"minecraft:model\", \"model\": \"allcraft:item/runtime_crystal\"}\n}\n"
                            .getBytes(StandardCharsets.UTF_8)
                    ),
                    languageEntry(sourceRoot, "item.allcraft.runtime_crystal", "Runtime Crystal"),
                    resourceEditGenerated(sourceRoot, "client/data/allcraft/recipe/runtime_crystal.json", recipe),
                    resourceEditGenerated(sourceRoot, "server/data/allcraft/recipe/runtime_crystal.json", recipe)
                );
            }
            case "new-particle" -> List.of(
                resourceEditGenerated(
                    sourceRoot,
                    "client/assets/allcraft/textures/particle/runtime_spark.png",
                    checkerTexture(16, 16, 0xFFFF5020, 0xFFFFFF40)
                ),
                resourceEditGenerated(
                    sourceRoot,
                    "client/assets/allcraft/particles/runtime_spark.json",
                    "{\n  \"textures\": [\"allcraft:runtime_spark\"]\n}\n".getBytes(StandardCharsets.UTF_8)
                )
            );
            case "new-mob" -> List.of(languageEntry(sourceRoot, "entity.allcraft.runtime_cow", "Runtime Cow"));
            case "new-keybind" -> List.of(
                languageEntries(
                    sourceRoot,
                    Map.of("key.allcraft.runtime_launch", "Runtime Launch", "key.category.allcraft.runtime", "Allcraft Runtime")
                )
            );
            case "lapis-crafting-table" -> {
                byte[] tableRecipe = ("{\n"
                        + "  \"type\": \"minecraft:crafting_shaped\",\n"
                        + "  \"category\": \"misc\",\n"
                        + "  \"pattern\": [\"LLL\", \"LCL\", \"LLL\"],\n"
                        + "  \"key\": {\"L\": \"minecraft:lapis_lazuli\", \"C\": \"minecraft:crafting_table\"},\n"
                        + "  \"result\": {\"count\": 1, \"id\": \"allcraft:lapis_crafting_table\"}\n"
                        + "}\n")
                    .getBytes(StandardCharsets.UTF_8);
                byte[] workstationRecipe = "{\n  \"type\": \"allcraft:lapis_table\"\n}\n".getBytes(StandardCharsets.UTF_8);
                yield List.of(
                    resourceEditGenerated(
                        sourceRoot,
                        "client/assets/allcraft/blockstates/lapis_crafting_table.json",
                        "{\n  \"variants\": {\"\": {\"model\": \"allcraft:block/lapis_crafting_table\"}}\n}\n"
                            .getBytes(StandardCharsets.UTF_8)
                    ),
                    resourceEditGenerated(
                        sourceRoot,
                        "client/assets/allcraft/models/block/lapis_crafting_table.json",
                        cubeAllModel("minecraft:block/lapis_block")
                    ),
                    resourceEditGenerated(
                        sourceRoot,
                        "client/assets/allcraft/items/lapis_crafting_table.json",
                        "{\n  \"model\": {\"type\": \"minecraft:model\", \"model\": \"allcraft:block/lapis_crafting_table\"}\n}\n"
                            .getBytes(StandardCharsets.UTF_8)
                    ),
                    languageEntries(
                        sourceRoot,
                        Map.of(
                            "block.allcraft.lapis_crafting_table", "Lapis Crafting Table",
                            "container.allcraft.lapis_crafting_table", "Lapis Crafting Table"
                        )
                    ),
                    resourceEditGenerated(sourceRoot, "client/data/allcraft/recipe/lapis_crafting_table.json", tableRecipe),
                    resourceEditGenerated(sourceRoot, "server/data/allcraft/recipe/lapis_crafting_table.json", tableRecipe),
                    resourceEditGenerated(sourceRoot, "client/data/allcraft/recipe/lapis_table_diamond.json", workstationRecipe),
                    resourceEditGenerated(sourceRoot, "server/data/allcraft/recipe/lapis_table_diamond.json", workstationRecipe)
                );
            }
            case "new-music-disc" -> {
                byte[] recipe = ("{\n"
                        + "  \"type\": \"minecraft:crafting_shapeless\",\n"
                        + "  \"category\": \"misc\",\n"
                        + "  \"ingredients\": [\"minecraft:diamond\", \"minecraft:note_block\"],\n"
                        + "  \"result\": {\"count\": 1, \"id\": \"allcraft:runtime_music_disc\"}\n"
                        + "}\n")
                    .getBytes(StandardCharsets.UTF_8);
                yield List.of(
                    resourceEditGenerated(
                        sourceRoot,
                        "client/assets/allcraft/sounds.json",
                        ("{\n"
                                + "  \"music_disc.runtime\": {\n"
                                + "    \"sounds\": [{\"name\": \"allcraft:records/runtime_music_disc\", \"stream\": true}]\n"
                                + "  }\n"
                                + "}\n")
                            .getBytes(StandardCharsets.UTF_8)
                    ),
                    resourceEditGenerated(
                        sourceRoot,
                        "client/assets/allcraft/sounds/records/runtime_music_disc.ogg",
                        Files.readAllBytes(sourceRoot.resolve("client/assets/minecraft/sounds/event/mob_effects/bad_omen.ogg"))
                    ),
                    resourceEditGenerated(
                        sourceRoot,
                        "client/assets/allcraft/textures/item/runtime_music_disc.png",
                        checkerTexture(16, 16, 0xFF101020, 0xFF30E0A0)
                    ),
                    resourceEditGenerated(
                        sourceRoot,
                        "client/assets/allcraft/models/item/runtime_music_disc.json",
                        ("{\n"
                                + "  \"parent\": \"minecraft:item/generated\",\n"
                                + "  \"textures\": {\"layer0\": \"allcraft:item/runtime_music_disc\"}\n"
                                + "}\n")
                            .getBytes(StandardCharsets.UTF_8)
                    ),
                    resourceEditGenerated(
                        sourceRoot,
                        "client/assets/allcraft/items/runtime_music_disc.json",
                        "{\n  \"model\": {\"type\": \"minecraft:model\", \"model\": \"allcraft:item/runtime_music_disc\"}\n}\n"
                            .getBytes(StandardCharsets.UTF_8)
                    ),
                    languageEntry(sourceRoot, "item.allcraft.runtime_music_disc", "Runtime Music Disc"),
                    resourceEditGenerated(sourceRoot, "client/data/allcraft/recipe/runtime_music_disc.json", recipe),
                    resourceEditGenerated(sourceRoot, "server/data/allcraft/recipe/runtime_music_disc.json", recipe)
                );
            }
            case "registry-block" -> {
                byte[] recipe = ("{\n"
                        + "  \"type\": \"minecraft:crafting_shapeless\",\n"
                        + "  \"category\": \"building\",\n"
                        + "  \"ingredients\": [\"minecraft:dirt\"],\n"
                        + "  \"result\": {\"count\": 1, \"id\": \"allcraft:runtime_block\"}\n"
                        + "}\n")
                    .getBytes(StandardCharsets.UTF_8);
                yield List.of(
                    resourceEditGenerated(
                        sourceRoot,
                        "client/assets/allcraft/blockstates/runtime_block.json",
                        "{\n  \"variants\": {\n    \"\": {\"model\": \"allcraft:block/runtime_block\"}\n  }\n}\n".getBytes(StandardCharsets.UTF_8)
                    ),
                    resourceEditGenerated(
                        sourceRoot,
                        "client/assets/allcraft/models/block/runtime_block.json",
                        cubeAllModel("minecraft:block/diamond_block")
                    ),
                    resourceEditGenerated(
                        sourceRoot,
                        "client/assets/allcraft/items/runtime_block.json",
                        "{\n  \"model\": {\"type\": \"minecraft:model\", \"model\": \"allcraft:block/runtime_block\"}\n}\n".getBytes(StandardCharsets.UTF_8)
                    ),
                    resourceEditGenerated(
                        sourceRoot,
                        "client/assets/allcraft/lang/en_us.json",
                        "{\n  \"block.allcraft.runtime_block\": \"Runtime Block\"\n}\n".getBytes(StandardCharsets.UTF_8)
                    ),
                    resourceEditGenerated(sourceRoot, "client/data/allcraft/recipe/runtime_block.json", recipe),
                    resourceEditGenerated(sourceRoot, "server/data/allcraft/recipe/runtime_block.json", recipe)
                );
            }
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

    private static ResourceEdit languageEntry(Path sourceRoot, String key, String value) throws IOException {
        return languageEntries(sourceRoot, Map.of(key, value));
    }

    private static ResourceEdit languageEntries(Path sourceRoot, Map<String, String> entries) throws IOException {
        String relative = "client/assets/allcraft/lang/en_us.json";
        Path path = sourceRoot.resolve(relative);
        if (!Files.isRegularFile(path)) {
            StringBuilder language = new StringBuilder("{\n");
            int index = 0;
            for (Map.Entry<String, String> entry : entries.entrySet().stream().sorted(Map.Entry.comparingByKey()).toList()) {
                if (index++ > 0) {
                    language.append(",\n");
                }
                language.append("  \"").append(entry.getKey()).append("\": \"").append(entry.getValue()).append('"');
            }
            language.append("\n}\n");
            return resourceEditGenerated(sourceRoot, relative, language.toString().getBytes(StandardCharsets.UTF_8));
        }
        return resourceEditExisting(sourceRoot, relative, bytes -> {
            String language = new String(bytes, StandardCharsets.UTF_8);
            int end = language.lastIndexOf('}');
            if (end < 0) {
                throw new IllegalArgumentException("invalid allcraft language JSON");
            }
            String prefix = language.substring(0, end).stripTrailing();
            boolean hasEntries = prefix.lastIndexOf('{') < prefix.length() - 1;
            StringBuilder updated = new StringBuilder(prefix);
            for (Map.Entry<String, String> entry : entries.entrySet().stream().sorted(Map.Entry.comparingByKey()).toList()) {
                if (!language.contains("\"" + entry.getKey() + "\"")) {
                    updated.append(hasEntries ? "," : "").append("\n  \"").append(entry.getKey()).append("\": \"").append(entry.getValue()).append('"');
                    hasEntries = true;
                }
            }
            return updated.append("\n}\n").toString().getBytes(StandardCharsets.UTF_8);
        });
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
            case "registry-block" -> List.of(
                sourceRoot.resolve("shared/net/minecraft/allcraft/generated/SharedRegistryBlock.java"),
                sourceRoot.resolve("client/net/minecraft/allcraft/generated/ClientRegistryBlock.java")
            );
            case "new-item" -> List.of(
                sourceRoot.resolve("shared/net/minecraft/allcraft/generated/SharedNewItem.java"),
                sourceRoot.resolve("client/net/minecraft/allcraft/generated/ClientNewItem.java")
            );
            case "new-particle" -> List.of(
                sourceRoot.resolve("shared/net/minecraft/allcraft/generated/SharedNewParticle.java"),
                sourceRoot.resolve("client/net/minecraft/allcraft/generated/ClientNewParticle.java")
            );
            case "new-mob" -> List.of(
                sourceRoot.resolve("shared/net/minecraft/allcraft/generated/SharedNewMob.java"),
                sourceRoot.resolve("client/net/minecraft/allcraft/generated/ClientNewMob.java")
            );
            case "new-music-disc" -> List.of(
                sourceRoot.resolve("shared/net/minecraft/allcraft/generated/SharedMusicDisc.java"),
                sourceRoot.resolve("client/net/minecraft/allcraft/generated/ClientMusicDisc.java")
            );
            case "new-keybind" -> List.of(sourceRoot.resolve("client/net/minecraft/allcraft/generated/ClientNewKeybind.java"));
            case "lapis-crafting-table" -> List.of(
                sourceRoot.resolve("shared/net/minecraft/allcraft/generated/SharedLapisCraftingTable.java"),
                sourceRoot.resolve("client/net/minecraft/allcraft/generated/ClientLapisCraftingTable.java")
            );
            default -> List.of();
        };
    }

    private static List<Path> serverSources(Path sourceRoot, String testName) {
        return switch (testName) {
            case "no-world-gen" -> List.of(sourceRoot.resolve(SERVER_NOISE_GENERATOR), sourceRoot.resolve(SERVER_CHUNK_GENERATOR));
            case "new-class" -> List.of(sourceRoot.resolve("server/net/minecraft/allcraft/generated/ServerRuntimeProbe.java"));
            case "registry-block" -> List.of(
                sourceRoot.resolve("shared/net/minecraft/allcraft/generated/SharedRegistryBlock.java"),
                sourceRoot.resolve("server/net/minecraft/allcraft/generated/ServerRegistryBlock.java")
            );
            case "new-item" -> List.of(
                sourceRoot.resolve("shared/net/minecraft/allcraft/generated/SharedNewItem.java"),
                sourceRoot.resolve("server/net/minecraft/allcraft/generated/ServerNewItem.java")
            );
            case "new-particle" -> List.of(
                sourceRoot.resolve("shared/net/minecraft/allcraft/generated/SharedNewParticle.java"),
                sourceRoot.resolve("server/net/minecraft/allcraft/generated/ServerNewParticle.java")
            );
            case "new-mob" -> List.of(
                sourceRoot.resolve("shared/net/minecraft/allcraft/generated/SharedNewMob.java"),
                sourceRoot.resolve("server/net/minecraft/allcraft/generated/ServerNewMob.java")
            );
            case "new-music-disc" -> List.of(
                sourceRoot.resolve("shared/net/minecraft/allcraft/generated/SharedMusicDisc.java"),
                sourceRoot.resolve("server/net/minecraft/allcraft/generated/ServerMusicDisc.java")
            );
            case "lapis-crafting-table" -> List.of(
                sourceRoot.resolve("shared/net/minecraft/allcraft/generated/SharedLapisCraftingTable.java"),
                sourceRoot.resolve("server/net/minecraft/allcraft/generated/ServerLapisCraftingTable.java")
            );
            default -> List.of();
        };
    }

    private static List<String> clientEntrypoints(String testName) {
        return switch (testName) {
            case "double-jump" -> List.of("net.minecraft.client.player.AllcraftDoubleJump");
            case "new-class" -> List.of("net.minecraft.allcraft.generated.ClientRuntimeProbe");
            case "registry-block" -> List.of("net.minecraft.allcraft.generated.ClientRegistryBlock");
            case "new-item" -> List.of("net.minecraft.allcraft.generated.ClientNewItem");
            case "new-particle" -> List.of("net.minecraft.allcraft.generated.ClientNewParticle");
            case "new-mob" -> List.of("net.minecraft.allcraft.generated.ClientNewMob");
            case "new-music-disc" -> List.of("net.minecraft.allcraft.generated.ClientMusicDisc");
            case "new-keybind" -> List.of("net.minecraft.allcraft.generated.ClientNewKeybind");
            case "lapis-crafting-table" -> List.of("net.minecraft.allcraft.generated.ClientLapisCraftingTable");
            default -> List.of();
        };
    }

    private static List<String> serverEntrypoints(String testName) {
        return switch (testName) {
            case "new-class" -> List.of("net.minecraft.allcraft.generated.ServerRuntimeProbe");
            case "registry-block" -> List.of("net.minecraft.allcraft.generated.ServerRegistryBlock");
            case "new-item" -> List.of("net.minecraft.allcraft.generated.ServerNewItem");
            case "new-particle" -> List.of("net.minecraft.allcraft.generated.ServerNewParticle");
            case "new-mob" -> List.of("net.minecraft.allcraft.generated.ServerNewMob");
            case "new-music-disc" -> List.of("net.minecraft.allcraft.generated.ServerMusicDisc");
            case "lapis-crafting-table" -> List.of("net.minecraft.allcraft.generated.ServerLapisCraftingTable");
            default -> List.of();
        };
    }

    private static String instructions(String testName) {
        return switch (testName) {
            case "double-jump" -> "Jump, release Space while airborne, then press Space again";
            case "flying-boats" -> "Ride a boat: hold Space to rise and Shift to descend";
            case "no-world-gen" -> "Travel into never-generated chunks; new terrain should be empty";
            case "new-class" -> "New client and server classes were loaded and their activation methods ran";
            case "registry-block" -> "Craft one dirt or run /give @s allcraft:runtime_block, then place the new synchronized block";
            case "new-item" -> "Craft amethyst plus redstone or run /give @s allcraft:runtime_crystal; the new item should have its live model";
            case "new-particle" -> "Run /particle allcraft:runtime_spark ~ ~1 ~ 0.5 0.5 0.5 0.02 100; the new orange/yellow particle should render";
            case "new-mob" -> "Run /summon allcraft:runtime_cow; the new synchronized mob should spawn and render as a cow";
            case "new-music-disc" -> "Craft a diamond plus note block or run /give @s allcraft:runtime_music_disc, then play it in a jukebox";
            case "new-keybind" -> "Open Controls to find Allcraft Runtime > Runtime Launch, then press K in-game to launch upward";
            case "lapis-crafting-table" -> "Craft or /give @s allcraft:lapis_crafting_table, open it, and craft one lapis lazuli into one diamond";
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
