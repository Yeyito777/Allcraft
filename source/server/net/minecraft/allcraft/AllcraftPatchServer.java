package net.minecraft.allcraft;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import java.io.ByteArrayOutputStream;
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
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.zip.CRC32;
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
    public static final List<String> TEST_NAMES = Stream.concat(NETWORK_TEST_NAMES.stream(), AllcraftPatchCompiler.RUNTIME_TEST_NAMES.stream()).toList();
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Gson COMPACT_GSON = new Gson();
    private static final int NETWORK_PATCH_COUNT = 5;
    private static final int ACK_TIMEOUT_TICKS = 600;
    private static final ExecutorService COMPILER_EXECUTOR = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "Allcraft Compiler Coordinator");
        thread.setDaemon(true);
        return thread;
    });
    private static final Map<MinecraftServer, TestRun> ACTIVE_TESTS = new IdentityHashMap<>();
    private static final Map<MinecraftServer, CompilationJob> COMPILING_TESTS = new IdentityHashMap<>();

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

        boolean runtimeTest = AllcraftPatchCompiler.RUNTIME_TEST_NAMES.contains(testName);
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

    public static void restoreWorldArtifacts(Path worldRoot) {
        Path patchesRoot = worldRoot.toAbsolutePath().normalize().resolve("patches");
        Path manifestPath = patchesRoot.resolve("manifest.json");
        if (!Files.isRegularFile(manifestPath)) {
            return;
        }

        try {
            AllcraftRuntime.resetToBase();
            JsonObject manifest = readJson(manifestPath);
            JsonArray patches = manifest.has("patches") ? manifest.getAsJsonArray("patches") : new JsonArray();
            boolean integratedClient = classExists("net.minecraft.client.Minecraft");
            int serverArtifacts = 0;
            int clientArtifacts = 0;
            for (int index = 0; index < patches.size(); index++) {
                JsonObject patch = patches.get(index).getAsJsonObject();
                long revision = patch.get("revision").getAsLong();
                String patchId = patch.get("patchId").getAsString();
                String stem = String.format("%08d-%s.jar", revision, patchId);
                Path serverArtifact = patchesRoot.resolve("artifacts/server").resolve(stem);
                if (Files.isRegularFile(serverArtifact)) {
                    AllcraftRuntime.apply(serverArtifact, hashFromManifestOrFile(patch, "serverSha256", serverArtifact));
                    serverArtifacts++;
                }

                if (integratedClient) {
                    Path clientArtifact = patchesRoot.resolve("artifacts/client").resolve(stem);
                    if (Files.isRegularFile(clientArtifact)) {
                        AllcraftRuntime.apply(clientArtifact, hashFromManifestOrFile(patch, "clientSha256", clientArtifact));
                        clientArtifacts++;
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
        } catch (Exception e) {
            throw new IllegalStateException("Failed to restore Allcraft runtime artifacts for " + worldRoot, e);
        }
    }

    public static void tick(MinecraftServer server) {
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
                        announce(
                            server,
                            "[Allcraft] "
                                + run.testName
                                + " "
                                + patch.step
                                + "/"
                                + run.patches.size()
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
                        patch.serverApplyResult = AllcraftRuntime.apply(patch.serverArtifactPath, patch.serverSha256);
                        broadcastControl(server, run, patch, AllcraftPayloads.ControlAction.ACTIVATE);
                        commitRevision(run, patch);
                        run.phase = Phase.WAITING_FOR_APPLIED;
                        run.phaseStartedAt = tick;
                    }
                }
                case WAITING_FOR_APPLIED -> {
                    if (run.appliedPlayers.containsAll(run.expectedPlayers)) {
                        if (run.patchIndex + 1 == run.patches.size()) {
                            finishTest(server, run);
                        } else {
                            run.patchIndex++;
                            run.phase = Phase.BETWEEN_PATCHES;
                            run.nextStageTick = tick + 10L;
                            run.readyPlayers.clear();
                            run.appliedPlayers.clear();
                        }
                    } else {
                        checkTimeout(server, run, tick, "APPLIED");
                    }
                }
                case BETWEEN_PATCHES -> {
                    if (tick >= run.nextStageTick) {
                        stageCurrentPatch(server, run);
                    }
                }
            }
        } catch (Exception e) {
            failTest(server, run, conciseMessage(e));
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
            failTest(server, run, player.getName().getString() + " reported failure: " + ack.message());
        } else if (ack.status() == AllcraftPayloads.AckStatus.READY && run.phase == Phase.WAITING_FOR_READY) {
            run.readyPlayers.add(player.getUUID());
        } else if (ack.status() == AllcraftPayloads.AckStatus.APPLIED && run.phase == Phase.WAITING_FOR_APPLIED) {
            run.appliedPlayers.add(player.getUUID());
        }
    }

    private static TestRun prepareTest(Path worldRoot, String testName) throws IOException {
        Path patchesRoot = worldRoot.resolve("patches");
        Path manifestPath = patchesRoot.resolve("manifest.json");
        JsonObject manifest = readJson(manifestPath);
        String serverId = requireUuid(manifest, "serverId", manifestPath);
        String worldId = requireUuid(manifest, "worldId", manifestPath);
        long baseRevision = manifest.get("currentRevision").getAsLong();
        String runId = UUID.randomUUID().toString();
        int patchCount = NETWORK_TEST_NAMES.contains(testName) ? NETWORK_PATCH_COUNT : 1;
        List<StepSpec> specs = specs(testName, patchCount);
        List<Patch> patches = new ArrayList<>(patchCount);
        boolean clientCacheHit = true;
        boolean serverCacheHit = true;
        long compilationMillis = 0L;

        for (int index = 0; index < patchCount; index++) {
            int step = index + 1;
            long revision = baseRevision + step;
            String patchId = UUID.randomUUID().toString();
            StepSpec spec = specs.get(index);
            Map<String, byte[]> clientClasses = Map.of();
            Map<String, byte[]> serverClasses = Map.of();
            List<String> changedFiles = List.of();
            List<String> clientEntrypoints = List.of();
            List<String> serverEntrypoints = List.of();
            if (AllcraftPatchCompiler.RUNTIME_TEST_NAMES.contains(testName)) {
                AllcraftPatchCompiler.Build build = AllcraftPatchCompiler.compile(
                    worldRoot.resolve("source"), patchesRoot.resolve("build-cache"), testName
                );
                clientClasses = build.clientClasses();
                serverClasses = build.serverClasses();
                changedFiles = build.changedFiles();
                clientEntrypoints = build.clientEntrypoints();
                serverEntrypoints = build.serverEntrypoints();
                spec = new StepSpec("title", build.instructions(), 0, 20);
                clientCacheHit &= build.clientCacheHit();
                serverCacheHit &= build.serverCacheHit();
                compilationMillis += build.compilationMillis();
            }

            byte[] clientArtifact = createArtifact(
                "client", testName, runId, patchId, revision, step, patchCount, spec, clientClasses, clientEntrypoints, changedFiles
            );
            byte[] serverArtifact = createArtifact(
                "server", testName, runId, patchId, revision, step, patchCount, spec, serverClasses, serverEntrypoints, changedFiles
            );
            Patch patch = new Patch(
                patchId,
                revision,
                step,
                spec,
                sha256(clientArtifact),
                sha256(serverArtifact),
                clientArtifact,
                serverArtifact,
                changedFiles,
                clientClasses.size(),
                serverClasses.size()
            );
            patches.add(patch);
            persistPreparedPatch(patchesRoot, serverId, worldId, testName, runId, patch);
        }

        return new TestRun(
            testName, runId, serverId, worldId, patches, patchesRoot, clientCacheHit, serverCacheHit, compilationMillis
        );
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
                    case "double-jump", "no-world-gen", "flying-boats", "new-class" -> new StepSpec("title", testName, 0, 20);
                    default -> throw new IllegalArgumentException("Unknown test " + testName);
                }
            );
        }

        return specs;
    }

    private static byte[] createArtifact(
        String side,
        String testName,
        String runId,
        String patchId,
        long revision,
        int step,
        int totalSteps,
        StepSpec spec,
        Map<String, byte[]> classes,
        List<String> entrypoints,
        List<String> changedFiles
    ) throws IOException {
        JsonObject descriptor = new JsonObject();
        descriptor.addProperty("format", 1);
        descriptor.addProperty("kind", AllcraftPatchCompiler.RUNTIME_TEST_NAMES.contains(testName) ? "runtime-code" : "network-test");
        descriptor.addProperty("side", side);
        descriptor.addProperty("testName", testName);
        descriptor.addProperty("runId", runId);
        descriptor.addProperty("patchId", patchId);
        descriptor.addProperty("revision", revision);
        descriptor.addProperty("step", step);
        descriptor.addProperty("totalSteps", totalSteps);
        descriptor.addProperty("display", spec.display());
        descriptor.addProperty("message", spec.message());
        descriptor.addProperty("fillerBytes", spec.fillerBytes());
        descriptor.addProperty("classCount", classes.size());
        JsonArray entrypointArray = new JsonArray();
        entrypoints.forEach(entrypointArray::add);
        descriptor.add("entrypoints", entrypointArray);
        JsonArray changedFileArray = new JsonArray();
        changedFiles.forEach(changedFileArray::add);
        descriptor.add("changedFiles", changedFileArray);

        byte[] json = (GSON.toJson(descriptor) + System.lineSeparator()).getBytes(StandardCharsets.UTF_8);
        int classBytes = classes.values().stream().mapToInt(bytes -> bytes.length).sum();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(json.length + spec.fillerBytes() + classBytes + 1024);
        try (JarOutputStream jar = new JarOutputStream(bytes)) {
            writeJarEntry(jar, "META-INF/allcraft-patch.json", json);
            writeJarEntry(jar, "allcraft/test-patch.json", json);
            for (Map.Entry<String, byte[]> entry : classes.entrySet()) {
                writeJarEntry(jar, entry.getKey(), entry.getValue());
            }
            if (spec.fillerBytes() > 0) {
                byte[] filler = new byte[spec.fillerBytes()];
                new Random(31L * revision + step).nextBytes(filler);
                writeStoredJarEntry(jar, "allcraft/test-filler.bin", filler);
            }
        }

        return bytes.toByteArray();
    }

    private static void writeJarEntry(JarOutputStream jar, String name, byte[] data) throws IOException {
        JarEntry entry = new JarEntry(name);
        jar.putNextEntry(entry);
        jar.write(data);
        jar.closeEntry();
    }

    private static void writeStoredJarEntry(JarOutputStream jar, String name, byte[] data) throws IOException {
        CRC32 crc = new CRC32();
        crc.update(data);
        JarEntry entry = new JarEntry(name);
        entry.setMethod(JarEntry.STORED);
        entry.setSize(data.length);
        entry.setCompressedSize(data.length);
        entry.setCrc(crc.getValue());
        jar.putNextEntry(entry);
        jar.write(data);
        jar.closeEntry();
    }

    private static void persistPreparedPatch(
        Path patchesRoot, String serverId, String worldId, String testName, String runId, Patch patch
    ) throws IOException {
        String stem = String.format("%08d-%s", patch.revision, patch.patchId);
        Path clientPath = patchesRoot.resolve("artifacts/client").resolve(stem + ".jar");
        Path serverPath = patchesRoot.resolve("artifacts/server").resolve(stem + ".jar");
        writeAtomically(clientPath, patch.clientArtifact);
        writeAtomically(serverPath, patch.serverArtifact);
        patch.serverArtifactPath = serverPath;

        JsonObject sourceDescriptor = new JsonObject();
        sourceDescriptor.addProperty("kind", AllcraftPatchCompiler.RUNTIME_TEST_NAMES.contains(testName) ? "runtime-code" : "network-test");
        sourceDescriptor.addProperty("serverId", serverId);
        sourceDescriptor.addProperty("worldId", worldId);
        sourceDescriptor.addProperty("testName", testName);
        sourceDescriptor.addProperty("runId", runId);
        sourceDescriptor.addProperty("patchId", patch.patchId);
        sourceDescriptor.addProperty("revision", patch.revision);
        sourceDescriptor.addProperty("step", patch.step);
        sourceDescriptor.addProperty("clientSha256", patch.clientSha256);
        sourceDescriptor.addProperty("serverSha256", patch.serverSha256);
        sourceDescriptor.addProperty("clientSize", patch.clientArtifact.length);
        sourceDescriptor.addProperty("serverSize", patch.serverArtifact.length);
        sourceDescriptor.addProperty("clientClasses", patch.clientClassCount);
        sourceDescriptor.addProperty("serverClasses", patch.serverClassCount);
        JsonArray files = new JsonArray();
        patch.changedFiles.forEach(files::add);
        sourceDescriptor.add("changedFiles", files);
        sourceDescriptor.addProperty("createdAt", Instant.now().toString());
        writeJsonAtomically(patchesRoot.resolve("source").resolve(stem + ".json"), sourceDescriptor);
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
                                run.patches.size(),
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
        announce(
            server,
            "[Allcraft] Streaming "
                + run.testName
                + " patch "
                + patch.step
                + "/"
                + run.patches.size()
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
                run.patches.size(),
                patch.activationTick,
                patch.clientSha256
            )
        );
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (run.expectedPlayers.contains(player.getUUID())) {
                player.connection.send(packet);
            }
        }
    }

    private static void commitRevision(TestRun run, Patch patch) throws IOException {
        Path manifestPath = run.patchesRoot.resolve("manifest.json");
        JsonObject manifest = readJson(manifestPath);
        manifest.addProperty("currentRevision", patch.revision);
        JsonArray patches = manifest.has("patches") ? manifest.getAsJsonArray("patches") : new JsonArray();
        JsonObject entry = new JsonObject();
        entry.addProperty("revision", patch.revision);
        entry.addProperty("patchId", patch.patchId);
        entry.addProperty("kind", AllcraftPatchCompiler.RUNTIME_TEST_NAMES.contains(run.testName) ? "runtime-code" : "network-test");
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
        result.addProperty("totalSteps", run.patches.size());
        result.addProperty("revision", patch.revision);
        result.addProperty("patchId", patch.patchId);
        result.addProperty("clientSha256", patch.clientSha256);
        result.addProperty("serverSha256", patch.serverSha256);
        result.addProperty("activationTick", patch.activationTick);
        result.addProperty("serverRuntime", patch.serverApplyResult == null ? "not applied" : patch.serverApplyResult.summary());
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
            failTest(server, run, "Timed out waiting for " + status + " acknowledgements");
        }
    }

    private static void finishTest(MinecraftServer server, TestRun run) {
        ACTIVE_TESTS.remove(server);
        announce(
            server,
            "[Allcraft] PASS "
                + run.testName
                + ": all "
                + run.patches.size()
                + " patch(es) were compiled, cached, scheduled, activated, and acknowledged by every client",
            ChatFormatting.GREEN
        );
    }

    private static void failTest(MinecraftServer server, TestRun run, String reason) {
        if (ACTIVE_TESTS.remove(server) != null) {
            announce(server, "[Allcraft] FAIL " + run.testName + ": " + reason, ChatFormatting.RED);
        }
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

    private static String conciseMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null ? exception.getClass().getSimpleName() : message.substring(0, Math.min(message.length(), 500));
    }

    private enum Phase {
        WAITING_FOR_READY,
        SCHEDULED,
        WAITING_FOR_APPLIED,
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
        private Path serverArtifactPath;
        private long activationTick;
        private AllcraftRuntime.ApplyResult serverApplyResult;

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
            int serverClassCount
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
        }
    }

    private static final class TestRun {
        private final String testName;
        private final String runId;
        private final String serverId;
        private final String worldId;
        private final List<Patch> patches;
        private final Path patchesRoot;
        private final boolean clientCacheHit;
        private final boolean serverCacheHit;
        private final long compilationMillis;
        private final Set<UUID> expectedPlayers = new HashSet<>();
        private final Set<UUID> readyPlayers = new HashSet<>();
        private final Set<UUID> appliedPlayers = new HashSet<>();
        private int patchIndex;
        private Phase phase = Phase.BETWEEN_PATCHES;
        private long phaseStartedAt;
        private long nextStageTick;

        private TestRun(
            String testName,
            String runId,
            String serverId,
            String worldId,
            List<Patch> patches,
            Path patchesRoot,
            boolean clientCacheHit,
            boolean serverCacheHit,
            long compilationMillis
        ) {
            this.testName = testName;
            this.runId = runId;
            this.serverId = serverId;
            this.worldId = worldId;
            this.patches = patches;
            this.patchesRoot = patchesRoot;
            this.clientCacheHit = clientCacheHit;
            this.serverCacheHit = serverCacheHit;
            this.compilationMillis = compilationMillis;
        }

        private Patch current() {
            return this.patches.get(this.patchIndex);
        }
    }
}
