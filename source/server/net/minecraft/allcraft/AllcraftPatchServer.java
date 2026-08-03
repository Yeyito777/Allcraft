package net.minecraft.allcraft;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Stream;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.AllcraftPayloads;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;

public final class AllcraftPatchServer {
    private static final List<String> NETWORK_TEST_NAMES = List.of("basic", "ordering", "payload", "cache", "timing");
    public static final List<String> TEST_NAMES = Stream.concat(NETWORK_TEST_NAMES.stream(), AllcraftPatchCompiler.PATCH_TEST_NAMES.stream()).toList();
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Gson COMPACT_GSON = new Gson();
    private static final int NETWORK_PATCH_COUNT = 5;
    private static final int ACK_TIMEOUT_TICKS = 1200;
    private static final ExecutorService COMPILER_EXECUTOR = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "Allcraft Compiler Coordinator");
        thread.setDaemon(true);
        return thread;
    });
    private static final Map<MinecraftServer, TestRun> ACTIVE_TESTS = new IdentityHashMap<>();
    private static final Map<MinecraftServer, CompilationJob> COMPILING_TESTS = new IdentityHashMap<>();
    private static final Map<MinecraftServer, ResourceRestore> PENDING_RESOURCE_RESTORES = new IdentityHashMap<>();
    private static final Map<MinecraftServer, CompletableFuture<Void>> ACTIVE_RESOURCE_RESTORES = new IdentityHashMap<>();

    private AllcraftPatchServer() {
    }

    public static int startTest(CommandSourceStack source, String testName) {
        MinecraftServer server = source.getServer();
        if (!TEST_NAMES.contains(testName)) {
            source.sendFailure(Component.literal("Unknown Allcraft test '" + testName + "'. Expected: " + String.join(", ", TEST_NAMES)));
            return 0;
        }

        if (ACTIVE_TESTS.containsKey(server) || COMPILING_TESTS.containsKey(server)) {
            source.sendFailure(Component.literal("An Allcraft patch test is already running"));
            return 0;
        }

        if (server.getPlayerList().getPlayerCount() == 0) {
            source.sendFailure(Component.literal("At least one client must be connected to run an Allcraft patch test"));
            return 0;
        }

        boolean runtimeTest = AllcraftPatchCompiler.PATCH_TEST_NAMES.contains(testName);
        Path worldRoot = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
        CompletableFuture<TestRun> future = CompletableFuture.supplyAsync(() -> {
            try {
                return prepareTest(worldRoot, testName);
            } catch (IOException e) {
                throw new CompletionException(e);
            }
        }, COMPILER_EXECUTOR);
        COMPILING_TESTS.put(server, new CompilationJob(testName, future));
        source.sendSuccess(
            () -> Component.literal(
                    runtimeTest
                        ? "Queued background client/server compilation for Allcraft test '" + testName + "'"
                        : "Preparing Allcraft test '" + testName + "' in the background"
                )
                .withStyle(ChatFormatting.AQUA),
            false
        );
        return 1;
    }

    /** Production entrypoint: build whatever has changed in the authoritative world source. */
    public static int startApply(CommandSourceStack source, String label) {
        MinecraftServer server = source.getServer();
        if (ACTIVE_TESTS.containsKey(server) || COMPILING_TESTS.containsKey(server)) {
            source.sendFailure(Component.literal("An Allcraft revision is already running"));
            return 0;
        }
        if (server.getPlayerList().getPlayerCount() == 0) {
            source.sendFailure(Component.literal("At least one client must be connected to publish an Allcraft revision"));
            return 0;
        }
        Path worldRoot = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
        CompletableFuture<TestRun> future = CompletableFuture.supplyAsync(() -> {
            try {
                AllcraftRevisionBuilder.PreparedRevision prepared = AllcraftRevisionBuilder.prepare(
                    worldRoot, AllcraftRevisionBuilder.Request.production(label)
                );
                return runFromPrepared(worldRoot, prepared, 1);
            } catch (IOException e) {
                throw new CompletionException(e);
            }
        }, COMPILER_EXECUTOR);
        COMPILING_TESTS.put(server, new CompilationJob(label, future));
        source.sendSuccess(
            () -> Component.literal("Queued arbitrary world-source revision '" + label + "'").withStyle(ChatFormatting.AQUA), false
        );
        return 1;
    }

    public static void restoreWorldArtifacts(MinecraftServer server, Path worldRoot) {
        Path patchesRoot = worldRoot.toAbsolutePath().normalize().resolve("patches");
        Path manifestPath = patchesRoot.resolve("manifest.json");
        if (!Files.isRegularFile(manifestPath)) {
            return;
        }

        try {
            Path interrupted = patchesRoot.resolve("transaction.json");
            if (Files.isRegularFile(interrupted)) {
                JsonObject transaction = readJson(interrupted);
                String phase = transaction.has("phase") ? transaction.get("phase").getAsString() : "unknown";
                LOGGER.warn(
                    "Recovering interrupted Allcraft transaction {} in phase {}; committed manifest remains authoritative",
                    transaction.has("patchId") ? transaction.get("patchId").getAsString() : "unknown",
                    phase
                );
                if ((phase.equals("publishing") || phase.equals("committing") || phase.equals("rolling-back"))
                    && transaction.has("serverSha256")
                    && transaction.has("revision")
                    && transaction.has("patchId")) {
                    Path interruptedArtifact = patchesRoot.resolve("artifacts/server").resolve(
                        String.format(
                            "%08d-%s.jar", transaction.get("revision").getAsLong(), transaction.get("patchId").getAsString()
                        )
                    );
                    if (Files.isRegularFile(interruptedArtifact)) {
                        AllcraftRuntime.recoverRollback(interruptedArtifact, transaction.get("serverSha256").getAsString());
                    }
                }
                Files.deleteIfExists(interrupted);
            }
            AllcraftRuntime.resetToBase();
            JsonObject manifest = readJson(manifestPath);
            JsonArray patches = manifest.has("patches") ? manifest.getAsJsonArray("patches") : new JsonArray();
            boolean integratedClient = classExists("net.minecraft.client.Minecraft");
            int serverArtifacts = 0;
            int clientArtifacts = 0;
            List<Path> serverResourceArtifacts = new ArrayList<>();
            List<Path> clientResourceArtifacts = new ArrayList<>();
            for (int index = 0; index < patches.size(); index++) {
                JsonObject patch = patches.get(index).getAsJsonObject();
                long revision = patch.get("revision").getAsLong();
                String patchId = patch.get("patchId").getAsString();
                String stem = String.format("%08d-%s.jar", revision, patchId);
                Path serverArtifact = patchesRoot.resolve("artifacts/server").resolve(stem);
                if (Files.isRegularFile(serverArtifact)) {
                    AllcraftRuntime.apply(serverArtifact, hashFromManifestOrFile(patch, "serverSha256", serverArtifact));
                    serverArtifacts++;
                    serverResourceArtifacts.add(serverArtifact);
                }

                if (integratedClient) {
                    Path clientArtifact = patchesRoot.resolve("artifacts/client").resolve(stem);
                    if (Files.isRegularFile(clientArtifact)) {
                        AllcraftRuntime.apply(clientArtifact, hashFromManifestOrFile(patch, "clientSha256", clientArtifact));
                        clientArtifacts++;
                        clientResourceArtifacts.add(clientArtifact);
                    }
                }
            }

            if (serverArtifacts > 0 || clientArtifacts > 0) {
                LOGGER.info(
                    "Restored Allcraft world revision {} from {} server and {} integrated-client artifact(s)",
                    manifest.get("currentRevision").getAsLong(),
                    serverArtifacts,
                    clientArtifacts
                );
            }
            // An empty resource set is meaningful: it clears overlays left by the previously
            // opened world and restores selected-pack assets before this world begins ticking.
            PENDING_RESOURCE_RESTORES.put(server, new ResourceRestore(serverResourceArtifacts, clientResourceArtifacts));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to restore Allcraft runtime artifacts for " + worldRoot, e);
        }
    }

    public static void tick(MinecraftServer server) {
        CompletableFuture<Void> restoring = ACTIVE_RESOURCE_RESTORES.get(server);
        if (restoring != null) {
            if (!restoring.isDone()) {
                return;
            }
            ACTIVE_RESOURCE_RESTORES.remove(server);
            restoring.join();
        }
        ResourceRestore pendingRestore = PENDING_RESOURCE_RESTORES.remove(server);
        if (pendingRestore != null) {
            CompletableFuture<?> serverResources = AllcraftServerResources.restore(server, pendingRestore.serverArtifacts());
            CompletableFuture<?> clientResources = restoreIntegratedClientResources(pendingRestore.clientArtifacts());
            CompletableFuture<Void> future = CompletableFuture.allOf(serverResources, clientResources);
            ACTIVE_RESOURCE_RESTORES.put(server, future);
            return;
        }

        CompilationJob compilation = COMPILING_TESTS.get(server);
        if (compilation != null) {
            if (!compilation.future().isDone()) {
                return;
            }

            COMPILING_TESTS.remove(server);
            try {
                TestRun compiledRun = compilation.future().join();
                ACTIVE_TESTS.put(server, compiledRun);
                announce(
                    server,
                    "[Allcraft] Prepared "
                        + compiledRun.testName
                        + " in "
                        + compiledRun.compilationMillis
                        + " ms (client cache "
                        + (compiledRun.clientCacheHit ? "hit" : "miss")
                        + ", server cache "
                        + (compiledRun.serverCacheHit ? "hit" : "miss")
                        + ")",
                    ChatFormatting.AQUA
                );
                stageCurrentPatch(server, compiledRun);
            } catch (CompletionException e) {
                Throwable cause = e.getCause() == null ? e : e.getCause();
                String message = cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
                announce(server, "[Allcraft] FAIL " + compilation.testName() + ": " + message, ChatFormatting.RED);
                LOGGER.error("Failed to prepare Allcraft test {}", compilation.testName(), cause);
            }
            return;
        }

        TestRun run = ACTIVE_TESTS.get(server);
        if (run == null) {
            return;
        }

        try {
            long tick = server.getTickCount();
            Patch patch = run.current();
            switch (run.phase) {
                case WAITING_FOR_READY -> {
                    if (run.readyPlayers.containsAll(run.expectedPlayers)) {
                        patch.activationTick = tick + patch.spec.activationDelayTicks();
                        broadcastControl(server, run, patch, AllcraftPayloads.ControlAction.SCHEDULE);
                        run.phase = Phase.SCHEDULED;
                        run.phaseStartedAt = tick;
                        writeTransactionState(run, patch, "scheduled", null);
                        announce(
                            server,
                            "[Allcraft] "
                                + run.testName
                                + " "
                                + patch.step
                                + "/"
                                + run.totalSteps
                                + " READY; activation scheduled for server tick "
                                + patch.activationTick,
                            ChatFormatting.GRAY
                        );
                    } else {
                        checkTimeout(server, run, tick, "READY");
                    }
                }
                case SCHEDULED -> {
                    if (tick >= patch.activationTick) {
                        writeTransactionState(run, patch, "publishing", null);
                        if (patch.serverTransaction == null) {
                            patch.serverTransaction = AllcraftRuntime.stage(patch.serverArtifactPath, patch.serverSha256);
                        }
                        patch.serverApplyResult = patch.serverTransaction.publish();
                        if (patch.serverTransaction.hasRegistryMutations()) {
                            AllcraftRegistries.refreshComponents(server.registryAccess());
                        }
                        patch.registryPlan = patch.serverTransaction.registryPlan();
                        patch.registryDigest = AllcraftRegistries.fingerprint();
                        patch.serverResourceFuture = AllcraftServerResources.apply(
                            server, run.patchesRoot, patch.serverArtifactPath, run.testName
                        );
                        broadcastControl(server, run, patch, AllcraftPayloads.ControlAction.ACTIVATE);
                        run.phase = Phase.WAITING_FOR_APPLIED;
                        run.phaseStartedAt = tick;
                    }
                }
                case WAITING_FOR_APPLIED -> {
                    if (patch.serverResourceFuture.isCompletedExceptionally()) {
                        patch.serverResourceFuture.join();
                    }
                    if (run.appliedPlayers.containsAll(run.expectedPlayers) && patch.serverResourceFuture.isDone()) {
                        patch.serverResourceResult = patch.serverResourceFuture.join();
                        if (patch.serverTransaction != null) {
                            patch.serverTransaction.finish();
                        }
                        writeTransactionState(run, patch, "committing", null);
                        broadcastControl(server, run, patch, AllcraftPayloads.ControlAction.COMMIT);
                        run.phase = Phase.WAITING_FOR_COMMITTED;
                        run.phaseStartedAt = tick;
                    } else {
                        checkTimeout(server, run, tick, "APPLIED");
                    }
                }
                case WAITING_FOR_COMMITTED -> {
                    if (run.committedPlayers.containsAll(run.expectedPlayers)) {
                        commitRevision(run, patch);
                        if (patch.serverTransaction != null) {
                            patch.serverTransaction.seal();
                        }
                        broadcastControl(server, run, patch, AllcraftPayloads.ControlAction.FINALIZE);
                        clearTransactionState(run);
                        if (patch.step >= run.totalSteps) {
                            finishTest(server, run);
                        } else {
                            run.phase = Phase.BETWEEN_PATCHES;
                            run.readyPlayers.clear();
                            run.appliedPlayers.clear();
                            run.committedPlayers.clear();
                            run.nextPatchFuture = CompletableFuture.supplyAsync(() -> {
                                try {
                                    return prepareNextFixture(run, patch.step + 1);
                                } catch (IOException e) {
                                    throw new CompletionException(e);
                                }
                            }, COMPILER_EXECUTOR);
                        }
                    } else {
                        checkTimeout(server, run, tick, "COMMITTED");
                    }
                }
                case WAITING_FOR_ROLLBACK -> {
                    if (patch.serverResourceFuture != null && patch.serverResourceFuture.isCompletedExceptionally()) {
                        patch.serverResourceFuture.join();
                    }
                    boolean resourcesDone = patch.serverResourceFuture == null || patch.serverResourceFuture.isDone();
                    if (resourcesDone && run.rollbackPlayers.containsAll(run.expectedPlayers)) {
                        failTest(server, run, run.failureReason == null ? "transaction rolled back" : run.failureReason);
                    } else {
                        checkTimeout(server, run, tick, "ROLLBACK");
                    }
                }
                case BETWEEN_PATCHES -> {
                    if (run.nextPatchFuture != null && run.nextPatchFuture.isDone()) {
                        Patch next;
                        try {
                            next = run.nextPatchFuture.join();
                        } catch (CompletionException failure) {
                            Throwable cause = failure.getCause() == null ? failure : failure.getCause();
                            failTest(server, run, "next revision did not compile: " + conciseMessage(cause));
                            return;
                        }
                        run.nextPatchFuture = null;
                        run.patches.add(next);
                        run.patchIndex++;
                        run.clientCacheHit &= next.clientCacheHit;
                        run.serverCacheHit &= next.serverCacheHit;
                        run.compilationMillis += next.compilationMillis;
                        stageCurrentPatch(server, run);
                    }
                }
            }
        } catch (Exception e) {
            abortRun(server, run, conciseMessage(e));
            LOGGER.error("Allcraft patch test {} failed", run.testName, e);
        }
    }

    public static void handleAck(MinecraftServer server, ServerPlayer player, AllcraftPayloads.PatchAck ack) {
        TestRun run = ACTIVE_TESTS.get(server);
        if (run == null) {
            return;
        }

        Patch patch = run.current();
        if (!run.serverId.equals(ack.serverId())
            || !run.worldId.equals(ack.worldId())
            || !patch.patchId.equals(ack.patchId())
            || patch.revision != ack.revision()
            || !patch.clientSha256.equals(ack.sha256())
            || !run.expectedPlayers.contains(player.getUUID())) {
            return;
        }

        if (ack.status() == AllcraftPayloads.AckStatus.FAILED) {
            abortRun(server, run, player.getName().getString() + " reported failure: " + ack.message());
        } else if (ack.status() == AllcraftPayloads.AckStatus.READY && run.phase == Phase.WAITING_FOR_READY) {
            run.readyPlayers.add(player.getUUID());
        } else if (ack.status() == AllcraftPayloads.AckStatus.APPLIED && run.phase == Phase.WAITING_FOR_APPLIED) {
            if (!patch.registryDigest.equals(ack.registryDigest())) {
                abortRun(
                    server,
                    run,
                    player.getName().getString()
                        + " applied a different registry ID map (server "
                        + patch.registryDigest
                        + ", client "
                        + ack.registryDigest()
                        + ")"
                );
            } else {
                run.appliedPlayers.add(player.getUUID());
            }
        } else if (ack.status() == AllcraftPayloads.AckStatus.COMMITTED && run.phase == Phase.WAITING_FOR_COMMITTED) {
            run.committedPlayers.add(player.getUUID());
        } else if (ack.status() == AllcraftPayloads.AckStatus.ROLLED_BACK && run.phase == Phase.WAITING_FOR_ROLLBACK) {
            run.rollbackPlayers.add(player.getUUID());
        }
    }

    private static TestRun prepareTest(Path worldRoot, String testName) throws IOException {
        String runId = UUID.randomUUID().toString();
        int patchCount = NETWORK_TEST_NAMES.contains(testName) ? NETWORK_PATCH_COUNT : 1;
        StepSpec spec = specs(testName, patchCount).getFirst();
        AllcraftRevisionBuilder.initializeBaseline(worldRoot);
        if (AllcraftPatchCompiler.PATCH_TEST_NAMES.contains(testName)) {
            AllcraftPatchCompiler.Fixture fixture = AllcraftPatchCompiler.applyFixture(worldRoot.resolve("source"), testName);
            spec = new StepSpec(spec.display(), fixture.instructions(), spec.fillerBytes(), spec.activationDelayTicks());
            writeTestFixture(worldRoot.resolve("source"), testName, runId, 1, patchCount, spec);
            AllcraftRevisionBuilder.PreparedRevision prepared = prepareFixture(
                worldRoot, testName, runId, 1, patchCount, spec, fixture.clientEntrypoints(), fixture.serverEntrypoints()
            );
            return runFromPrepared(worldRoot, prepared, patchCount);
        }
        writeTestFixture(worldRoot.resolve("source"), testName, runId, 1, patchCount, spec);
        AllcraftRevisionBuilder.PreparedRevision prepared = prepareFixture(
            worldRoot, testName, runId, 1, patchCount, spec, List.of(), List.of()
        );
        return runFromPrepared(worldRoot, prepared, patchCount);
    }

    private static AllcraftRevisionBuilder.PreparedRevision prepareFixture(
        Path worldRoot,
        String testName,
        String runId,
        int step,
        int totalSteps,
        StepSpec spec,
        List<String> clientEntrypoints,
        List<String> serverEntrypoints
    ) throws IOException {
        return AllcraftRevisionBuilder.prepare(
            worldRoot,
            AllcraftRevisionBuilder.Request.test(
                testName,
                spec.display(),
                spec.message(),
                spec.fillerBytes(),
                step,
                totalSteps,
                runId,
                clientEntrypoints,
                serverEntrypoints
            )
        );
    }

    private static Patch prepareNextFixture(TestRun run, int step) throws IOException {
        StepSpec spec = specs(run.testName, run.totalSteps).get(step - 1);
        writeTestFixture(run.worldRoot.resolve("source"), run.testName, run.runId, step, run.totalSteps, spec);
        return patchFromPrepared(
            prepareFixture(run.worldRoot, run.testName, run.runId, step, run.totalSteps, spec, List.of(), List.of())
        );
    }

    private static void writeTestFixture(
        Path sourceRoot, String testName, String runId, int step, int totalSteps, StepSpec spec
    ) throws IOException {
        JsonObject fixture = new JsonObject();
        fixture.addProperty("kind", "test-fixture");
        fixture.addProperty("testName", testName);
        fixture.addProperty("runId", runId);
        fixture.addProperty("step", step);
        fixture.addProperty("totalSteps", totalSteps);
        fixture.addProperty("message", spec.message());
        writeJsonAtomically(sourceRoot.resolve("allcraft-test-fixture.json"), fixture);
    }

    private static TestRun runFromPrepared(Path worldRoot, AllcraftRevisionBuilder.PreparedRevision prepared, int totalSteps) throws IOException {
        Patch patch = patchFromPrepared(prepared);
        return new TestRun(
            prepared.request().label(),
            prepared.runId(),
            prepared.serverId(),
            prepared.worldId(),
            new ArrayList<>(List.of(patch)),
            worldRoot,
            totalSteps,
            prepared.client().cacheHit(),
            prepared.server().cacheHit(),
            prepared.client().compilationMillis() + prepared.server().compilationMillis()
        );
    }

    private static Patch patchFromPrepared(AllcraftRevisionBuilder.PreparedRevision prepared) throws IOException {
        int activationDelay = prepared.request().testFixture()
            ? specs(prepared.request().label(), prepared.request().totalSteps()).get(prepared.request().step() - 1).activationDelayTicks()
            : 20;
        Patch patch = new Patch(
            prepared.patchId(),
            prepared.revision(),
            prepared.request().step(),
            new StepSpec(
                prepared.request().display(), prepared.request().message(), prepared.request().fillerBytes(), activationDelay
            ),
            prepared.clientSha256(),
            prepared.serverSha256(),
            prepared.clientArtifact(),
            prepared.serverArtifact(),
            Stream.concat(prepared.changedFiles().stream(), prepared.deletedFiles().stream()).sorted().toList(),
            prepared.client().classes().size(),
            prepared.server().classes().size(),
            prepared.client().resources().size(),
            prepared.server().resources().size(),
            prepared.client().deletedResources().size(),
            prepared.server().deletedResources().size()
        );
        patch.serverArtifactPath = prepared.serverArtifactPath();
        patch.preparedRevision = prepared;
        patch.clientCacheHit = prepared.client().cacheHit();
        patch.serverCacheHit = prepared.server().cacheHit();
        patch.compilationMillis = prepared.client().compilationMillis() + prepared.server().compilationMillis();
        try {
            patch.serverTransaction = AllcraftRuntime.stage(prepared.serverArtifactPath(), prepared.serverSha256());
            patch.serverResourcePreflight = AllcraftServerResources.preflight(prepared.serverArtifactPath());
        } catch (Exception e) {
            AllcraftRevisionBuilder.discard(prepared);
            throw e instanceof IOException ioException ? ioException : new IOException("Server revision staging failed", e);
        }
        return patch;
    }

    private static List<StepSpec> specs(String testName, int patchCount) {
        List<StepSpec> specs = new ArrayList<>(patchCount);
        int[] payloadSizes = {1024, 32768, 262144, 900000, 1800000};
        int[] timingDelays = {10, 20, 30, 40, 50};
        for (int step = 1; step <= patchCount; step++) {
            specs.add(
                switch (testName) {
                    case "basic" -> new StepSpec("chat", "Basic patch " + step + " arrived and applied", step * 128, 20);
                    case "ordering" -> new StepSpec("title", "Expected ordered patch " + step, step * 512, 20);
                    case "payload" -> new StepSpec(
                        "actionbar", "Payload size test " + payloadSizes[step - 1] + " bytes", payloadSizes[step - 1], 20
                    );
                    case "cache" -> new StepSpec("chat", "Cached artifact " + step + " is active", step * 8192, 20);
                    case "timing" -> new StepSpec(
                        "title", "Timing patch delayed by " + timingDelays[step - 1] + " ticks", step * 256, timingDelays[step - 1]
                    );
                    case "double-jump", "no-world-gen", "flying-boats", "new-class", "registry-block",
                        "live-texture", "live-model", "live-sound", "live-language", "live-recipe", "live-resource-delete",
                        "asset-new-sprite", "asset-resized-sprite", "asset-animated-sprite", "asset-atlas-delete", "asset-font",
                        "asset-shader", "asset-particle", "asset-gui", "asset-live-sound", "asset-mass-model", "asset-atlas-manifest" -> new StepSpec(
                            "title", testName, 0, 20
                        );
                    default -> throw new IllegalArgumentException("Unknown test " + testName);
                }
            );
        }

        return specs;
    }

    private static void stageCurrentPatch(MinecraftServer server, TestRun run) {
        Patch patch = run.current();
        run.expectedPlayers.clear();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            run.expectedPlayers.add(player.getUUID());
        }

        if (run.expectedPlayers.isEmpty()) {
            failTest(server, run, "No clients remain connected");
            return;
        }

        int chunkCount = Math.max(
            1, (patch.clientArtifact.length + AllcraftPayloads.MAX_CHUNK_BYTES - 1) / AllcraftPayloads.MAX_CHUNK_BYTES
        );
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (!run.expectedPlayers.contains(player.getUUID())) {
                continue;
            }

            for (int chunkIndex = 0; chunkIndex < chunkCount; chunkIndex++) {
                int start = chunkIndex * AllcraftPayloads.MAX_CHUNK_BYTES;
                int end = Math.min(patch.clientArtifact.length, start + AllcraftPayloads.MAX_CHUNK_BYTES);
                byte[] chunk = Arrays.copyOfRange(patch.clientArtifact, start, end);
                player.connection
                    .send(
                        new ClientboundCustomPayloadPacket(
                            new AllcraftPayloads.PatchChunk(
                                run.serverId,
                                run.worldId,
                                patch.patchId,
                                patch.revision,
                                run.testName,
                                patch.step,
                                run.totalSteps,
                                chunkIndex,
                                chunkCount,
                                patch.clientSha256,
                                chunk
                            )
                        )
                    );
            }
        }

        run.phase = Phase.WAITING_FOR_READY;
        run.phaseStartedAt = server.getTickCount();
        try {
            writeTransactionState(run, patch, "staged", null);
        } catch (IOException e) {
            abortRun(server, run, conciseMessage(e));
            return;
        }
        announce(
            server,
            "[Allcraft] Streaming "
                + run.testName
                + " patch "
                + patch.step
                + "/"
                + run.totalSteps
                + ": "
                + patch.clientArtifact.length
                + " bytes in "
                + chunkCount
                + " chunk(s)",
            ChatFormatting.YELLOW
        );
    }

    private static void broadcastControl(MinecraftServer server, TestRun run, Patch patch, AllcraftPayloads.ControlAction action) {
        ClientboundCustomPayloadPacket packet = new ClientboundCustomPayloadPacket(
            new AllcraftPayloads.PatchControl(
                action,
                run.serverId,
                run.worldId,
                patch.patchId,
                patch.revision,
                run.testName,
                patch.step,
                run.totalSteps,
                patch.activationTick,
                patch.clientSha256,
                patch.registryPlan,
                patch.registryDigest
            )
        );
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (run.expectedPlayers.contains(player.getUUID())) {
                player.connection.send(packet);
            }
        }
    }

    private static void commitRevision(TestRun run, Patch patch) throws IOException {
        if (patch.preparedRevision != null) {
            AllcraftRevisionBuilder.commit(patch.preparedRevision);
        }
        Path manifestPath = run.patchesRoot.resolve("manifest.json");
        JsonObject manifest = readJson(manifestPath);
        manifest.addProperty("currentRevision", patch.revision);
        JsonArray patches = manifest.has("patches") ? manifest.getAsJsonArray("patches") : new JsonArray();
        JsonObject entry = new JsonObject();
        entry.addProperty("revision", patch.revision);
        entry.addProperty("patchId", patch.patchId);
        entry.addProperty("kind", "source-revision");
        entry.addProperty("testName", run.testName);
        entry.addProperty("runId", run.runId);
        entry.addProperty("step", patch.step);
        entry.addProperty("clientSha256", patch.clientSha256);
        entry.addProperty("serverSha256", patch.serverSha256);
        entry.addProperty("sha256", patch.clientSha256);
        entry.addProperty("activationTick", patch.activationTick);
        entry.addProperty("activatedAt", Instant.now().toString());
        patches.add(entry);
        manifest.add("patches", patches);
        writeJsonAtomically(manifestPath, manifest);

        JsonObject result = new JsonObject();
        result.addProperty("testName", run.testName);
        result.addProperty("runId", run.runId);
        result.addProperty("step", patch.step);
        result.addProperty("totalSteps", run.totalSteps);
        result.addProperty("revision", patch.revision);
        result.addProperty("patchId", patch.patchId);
        result.addProperty("clientSha256", patch.clientSha256);
        result.addProperty("serverSha256", patch.serverSha256);
        result.addProperty("activationTick", patch.activationTick);
        result.addProperty("serverRuntime", patch.serverApplyResult == null ? "not applied" : patch.serverApplyResult.summary());
        result.addProperty("serverResources", patch.serverResourceResult == null ? "not applied" : patch.serverResourceResult.summary());
        result.addProperty("compilationMillis", run.compilationMillis);
        result.addProperty("clientCompilerCacheHit", run.clientCacheHit);
        result.addProperty("serverCompilerCacheHit", run.serverCacheHit);
        result.addProperty("serverAppliedAt", Instant.now().toString());
        Path results = run.patchesRoot.resolve("test-results");
        Files.createDirectories(results);
        Files.writeString(
            results.resolve(run.testName + ".jsonl"),
            COMPACT_GSON.toJson(result) + System.lineSeparator(),
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND
        );
    }

    private static void checkTimeout(MinecraftServer server, TestRun run, long tick, String status) {
        if (tick - run.phaseStartedAt > ACK_TIMEOUT_TICKS) {
            if (run.phase == Phase.WAITING_FOR_ROLLBACK) {
                failTest(server, run, "Timed out waiting for " + status + " acknowledgements");
            } else {
                abortRun(server, run, "Timed out waiting for " + status + " acknowledgements");
            }
        }
    }

    private static void finishTest(MinecraftServer server, TestRun run) {
        ACTIVE_TESTS.remove(server);
        announce(
            server,
            "[Allcraft] PASS "
                + run.testName
                + ": all "
                + run.totalSteps
                + " patch(es) were compiled, cached, scheduled, activated, and acknowledged by every client",
            ChatFormatting.GREEN
        );
    }

    private static void abortRun(MinecraftServer server, TestRun run, String reason) {
        if (run.phase == Phase.WAITING_FOR_ROLLBACK) {
            return;
        }
        Patch patch = run.current();
        run.failureReason = reason;
        try {
            if (patch.serverTransaction != null && patch.serverTransaction.started()) {
                patch.serverTransaction.rollback();
            }
        } catch (Exception e) {
            LOGGER.error("Failed to roll back server classes for {}", patch.patchId, e);
            run.failureReason += "; server class rollback failed: " + conciseMessage(e);
        }
        CompletableFuture<AllcraftServerResources.ApplyResult> inFlight = patch.serverResourceFuture;
        patch.serverResourceFuture = inFlight == null
            ? AllcraftServerResources.rollback(server, run.patchesRoot)
            : inFlight.handle((unused, failure) -> null)
                .thenCompose(unused -> AllcraftServerResources.rollback(server, run.patchesRoot));
        broadcastControl(server, run, patch, AllcraftPayloads.ControlAction.ABORT);
        run.phase = Phase.WAITING_FOR_ROLLBACK;
        run.phaseStartedAt = server.getTickCount();
        run.rollbackPlayers.clear();
        try {
            writeTransactionState(run, patch, "rolling-back", reason);
        } catch (IOException e) {
            LOGGER.error("Failed to record Allcraft rollback state", e);
        }
    }

    private static void failTest(MinecraftServer server, TestRun run, String reason) {
        if (ACTIVE_TESTS.remove(server) != null) {
            Patch patch = run.current();
            if (patch.preparedRevision != null) {
                AllcraftRevisionBuilder.discard(patch.preparedRevision);
            }
            try {
                clearTransactionState(run);
            } catch (IOException e) {
                LOGGER.warn("Failed to clear transaction journal", e);
            }
            announce(server, "[Allcraft] FAIL " + run.testName + ": " + reason, ChatFormatting.RED);
        }
    }

    private static void writeTransactionState(TestRun run, Patch patch, String phase, String failure) throws IOException {
        JsonObject state = new JsonObject();
        state.addProperty("format", 1);
        state.addProperty("phase", phase);
        state.addProperty("serverId", run.serverId);
        state.addProperty("worldId", run.worldId);
        state.addProperty("patchId", patch.patchId);
        state.addProperty("revision", patch.revision);
        state.addProperty("parentRevision", patch.preparedRevision == null ? patch.revision - 1L : patch.preparedRevision.parentRevision());
        state.addProperty("activationTick", patch.activationTick);
        state.addProperty("serverSha256", patch.serverSha256);
        state.addProperty("clientSha256", patch.clientSha256);
        state.addProperty("updatedAt", Instant.now().toString());
        if (failure != null) {
            state.addProperty("failure", failure);
        }
        writeJsonAtomically(run.patchesRoot.resolve("transaction.json"), state);
    }

    private static void clearTransactionState(TestRun run) throws IOException {
        Files.deleteIfExists(run.patchesRoot.resolve("transaction.json"));
    }

    private static void announce(MinecraftServer server, String message, ChatFormatting color) {
        server.getPlayerList().broadcastSystemMessage(Component.literal(message).withStyle(color), false);
    }

    private static JsonObject readJson(Path path) throws IOException {
        try {
            return JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (RuntimeException e) {
            throw new IOException("Invalid Allcraft JSON file " + path, e);
        }
    }

    private static String requireUuid(JsonObject object, String property, Path path) throws IOException {
        if (!object.has(property)) {
            throw new IOException("Missing " + property + " in " + path);
        }

        String value = object.get(property).getAsString();
        try {
            UUID.fromString(value);
            return value;
        } catch (IllegalArgumentException e) {
            throw new IOException("Invalid " + property + " in " + path, e);
        }
    }

    private static String hashFromManifestOrFile(JsonObject patch, String property, Path artifact) throws IOException {
        if (patch.has(property)) {
            return patch.get(property).getAsString();
        }
        if (patch.has("sha256")) {
            String legacy = patch.get("sha256").getAsString();
            if (legacy.equals(sha256(artifact))) {
                return legacy;
            }
        }
        return sha256(artifact);
    }

    private static boolean classExists(String className) {
        try {
            Class.forName(className, false, AllcraftPatchServer.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    private static CompletableFuture<?> restoreIntegratedClientResources(List<Path> artifacts) {
        if (!classExists("net.minecraft.client.Minecraft")) {
            return CompletableFuture.completedFuture(null);
        }
        try {
            ClassLoader loader = AllcraftPatchServer.class.getClassLoader();
            Class<?> minecraftClass = Class.forName("net.minecraft.client.Minecraft", false, loader);
            Object minecraft = minecraftClass.getMethod("getInstance").invoke(null);
            Class<?> resourcesClass = Class.forName("net.minecraft.allcraft.AllcraftClientResources", false, loader);
            Object result = resourcesClass.getMethod("restoreIntegrated", minecraftClass, List.class).invoke(null, minecraft, artifacts);
            return (CompletableFuture<?>)result;
        } catch (ReflectiveOperationException e) {
            return CompletableFuture.failedFuture(new IllegalStateException("Failed to restore integrated-client resources", e));
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
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

    private static void writeJsonAtomically(Path path, JsonObject object) throws IOException {
        writeAtomically(path, (GSON.toJson(object) + System.lineSeparator()).getBytes(StandardCharsets.UTF_8));
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

    private static String conciseMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null ? throwable.getClass().getSimpleName() : message.substring(0, Math.min(message.length(), 500));
    }

    private enum Phase {
        WAITING_FOR_READY,
        SCHEDULED,
        WAITING_FOR_APPLIED,
        WAITING_FOR_COMMITTED,
        WAITING_FOR_ROLLBACK,
        BETWEEN_PATCHES
    }

    private record StepSpec(String display, String message, int fillerBytes, int activationDelayTicks) {
    }

    private record CompilationJob(String testName, CompletableFuture<TestRun> future) {
    }

    private static final class Patch {
        private final String patchId;
        private final long revision;
        private final int step;
        private final StepSpec spec;
        private final String clientSha256;
        private final String serverSha256;
        private final byte[] clientArtifact;
        private final byte[] serverArtifact;
        private final List<String> changedFiles;
        private final int clientClassCount;
        private final int serverClassCount;
        private final int clientResourceCount;
        private final int serverResourceCount;
        private final int deletedClientResourceCount;
        private final int deletedServerResourceCount;
        private Path serverArtifactPath;
        private long activationTick;
        private AllcraftRuntime.ApplyResult serverApplyResult;
        private CompletableFuture<AllcraftServerResources.ApplyResult> serverResourceFuture;
        private AllcraftServerResources.ApplyResult serverResourceResult;
        private AllcraftRevisionBuilder.PreparedRevision preparedRevision;
        private AllcraftRuntime.Transaction serverTransaction;
        private AllcraftServerResources.PreflightResult serverResourcePreflight;
        private boolean clientCacheHit;
        private boolean serverCacheHit;
        private long compilationMillis;
        private String registryPlan = "[]";
        private String registryDigest = "";

        private Patch(
            String patchId,
            long revision,
            int step,
            StepSpec spec,
            String clientSha256,
            String serverSha256,
            byte[] clientArtifact,
            byte[] serverArtifact,
            List<String> changedFiles,
            int clientClassCount,
            int serverClassCount,
            int clientResourceCount,
            int serverResourceCount,
            int deletedClientResourceCount,
            int deletedServerResourceCount
        ) {
            this.patchId = patchId;
            this.revision = revision;
            this.step = step;
            this.spec = spec;
            this.clientSha256 = clientSha256;
            this.serverSha256 = serverSha256;
            this.clientArtifact = clientArtifact;
            this.serverArtifact = serverArtifact;
            this.changedFiles = changedFiles;
            this.clientClassCount = clientClassCount;
            this.serverClassCount = serverClassCount;
            this.clientResourceCount = clientResourceCount;
            this.serverResourceCount = serverResourceCount;
            this.deletedClientResourceCount = deletedClientResourceCount;
            this.deletedServerResourceCount = deletedServerResourceCount;
        }
    }

    private static final class TestRun {
        private final String testName;
        private final String runId;
        private final String serverId;
        private final String worldId;
        private final List<Patch> patches;
        private final Path worldRoot;
        private final Path patchesRoot;
        private final int totalSteps;
        private boolean clientCacheHit;
        private boolean serverCacheHit;
        private long compilationMillis;
        private final Set<UUID> expectedPlayers = new HashSet<>();
        private final Set<UUID> readyPlayers = new HashSet<>();
        private final Set<UUID> appliedPlayers = new HashSet<>();
        private final Set<UUID> committedPlayers = new HashSet<>();
        private final Set<UUID> rollbackPlayers = new HashSet<>();
        private int patchIndex;
        private Phase phase = Phase.BETWEEN_PATCHES;
        private long phaseStartedAt;
        private CompletableFuture<Patch> nextPatchFuture;
        private String failureReason;

        private TestRun(
            String testName,
            String runId,
            String serverId,
            String worldId,
            List<Patch> patches,
            Path worldRoot,
            int totalSteps,
            boolean clientCacheHit,
            boolean serverCacheHit,
            long compilationMillis
        ) {
            this.testName = testName;
            this.runId = runId;
            this.serverId = serverId;
            this.worldId = worldId;
            this.patches = patches;
            this.worldRoot = worldRoot;
            this.patchesRoot = worldRoot.resolve("patches");
            this.totalSteps = totalSteps;
            this.clientCacheHit = clientCacheHit;
            this.serverCacheHit = serverCacheHit;
            this.compilationMillis = compilationMillis;
        }

        private Patch current() {
            return this.patches.get(this.patchIndex);
        }
    }

    private record ResourceRestore(List<Path> serverArtifacts, List<Path> clientArtifacts) {
    }
}
