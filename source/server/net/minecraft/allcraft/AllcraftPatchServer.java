package net.minecraft.allcraft;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
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
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.zip.CRC32;
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
    public static final List<String> TEST_NAMES = List.of("basic", "ordering", "payload", "cache", "timing");
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Gson COMPACT_GSON = new Gson();
    private static final int PATCH_COUNT = 5;
    private static final int ACK_TIMEOUT_TICKS = 600;
    private static final Map<MinecraftServer, TestRun> ACTIVE_TESTS = new IdentityHashMap<>();

    private AllcraftPatchServer() {
    }

    public static int startTest(CommandSourceStack source, String testName) {
        MinecraftServer server = source.getServer();
        if (!TEST_NAMES.contains(testName)) {
            source.sendFailure(Component.literal("Unknown Allcraft test '" + testName + "'. Expected: " + String.join(", ", TEST_NAMES)));
            return 0;
        }

        if (ACTIVE_TESTS.containsKey(server)) {
            source.sendFailure(Component.literal("An Allcraft patch test is already running"));
            return 0;
        }

        if (server.getPlayerList().getPlayerCount() == 0) {
            source.sendFailure(Component.literal("At least one client must be connected to run an Allcraft patch test"));
            return 0;
        }

        try {
            TestRun run = prepareTest(server, testName);
            ACTIVE_TESTS.put(server, run);
            source.sendSuccess(
                () -> Component.literal(
                        "Starting Allcraft test '" + testName + "': five compiled network patches will be staged and activated"
                    )
                    .withStyle(ChatFormatting.AQUA),
                false
            );
            stageCurrentPatch(server, run);
            return 1;
        } catch (Exception e) {
            LOGGER.error("Failed to start Allcraft test {}", testName, e);
            source.sendFailure(Component.literal("Failed to start Allcraft test: " + conciseMessage(e)));
            return 0;
        }
    }

    public static void tick(MinecraftServer server) {
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
                                + "/5 READY; activation scheduled for server tick "
                                + patch.activationTick,
                            ChatFormatting.GRAY
                        );
                    } else {
                        checkTimeout(server, run, tick, "READY");
                    }
                }
                case SCHEDULED -> {
                    if (tick >= patch.activationTick) {
                        broadcastControl(server, run, patch, AllcraftPayloads.ControlAction.ACTIVATE);
                        commitRevision(server, run, patch);
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
            || !patch.sha256.equals(ack.sha256())
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

    private static TestRun prepareTest(MinecraftServer server, String testName) throws IOException {
        Path patchesRoot = server.getWorldPath(LevelResource.ROOT).resolve("patches");
        Path manifestPath = patchesRoot.resolve("manifest.json");
        JsonObject manifest = readJson(manifestPath);
        String serverId = requireUuid(manifest, "serverId", manifestPath);
        String worldId = requireUuid(manifest, "worldId", manifestPath);
        long baseRevision = manifest.get("currentRevision").getAsLong();
        String runId = UUID.randomUUID().toString();
        List<StepSpec> specs = specs(testName);
        List<Patch> patches = new ArrayList<>(PATCH_COUNT);

        for (int index = 0; index < PATCH_COUNT; index++) {
            int step = index + 1;
            long revision = baseRevision + step;
            String patchId = UUID.randomUUID().toString();
            StepSpec spec = specs.get(index);
            byte[] artifact = createArtifact(testName, runId, patchId, revision, step, spec);
            String hash = sha256(artifact);
            Patch patch = new Patch(patchId, revision, step, spec, hash, artifact);
            patches.add(patch);
            persistPreparedPatch(patchesRoot, serverId, worldId, testName, runId, patch);
        }

        return new TestRun(testName, runId, serverId, worldId, patches, patchesRoot);
    }

    private static List<StepSpec> specs(String testName) {
        List<StepSpec> specs = new ArrayList<>(PATCH_COUNT);
        int[] payloadSizes = {1024, 32768, 262144, 900000, 1800000};
        int[] timingDelays = {10, 20, 30, 40, 50};
        for (int step = 1; step <= PATCH_COUNT; step++) {
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
                    default -> throw new IllegalArgumentException("Unknown test " + testName);
                }
            );
        }

        return specs;
    }

    private static byte[] createArtifact(String testName, String runId, String patchId, long revision, int step, StepSpec spec) throws IOException {
        JsonObject descriptor = new JsonObject();
        descriptor.addProperty("format", 1);
        descriptor.addProperty("testName", testName);
        descriptor.addProperty("runId", runId);
        descriptor.addProperty("patchId", patchId);
        descriptor.addProperty("revision", revision);
        descriptor.addProperty("step", step);
        descriptor.addProperty("totalSteps", PATCH_COUNT);
        descriptor.addProperty("display", spec.display());
        descriptor.addProperty("message", spec.message());
        descriptor.addProperty("fillerBytes", spec.fillerBytes());

        byte[] json = (GSON.toJson(descriptor) + System.lineSeparator()).getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(json.length + spec.fillerBytes() + 512);
        try (JarOutputStream jar = new JarOutputStream(bytes)) {
            writeJarEntry(jar, "META-INF/allcraft-patch.json", json);
            writeJarEntry(jar, "allcraft/test-patch.json", json);
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
        writeAtomically(patchesRoot.resolve("artifacts/client").resolve(stem + ".jar"), patch.artifact);
        writeAtomically(patchesRoot.resolve("artifacts/server").resolve(stem + ".jar"), patch.artifact);

        JsonObject sourceDescriptor = new JsonObject();
        sourceDescriptor.addProperty("kind", "network-test");
        sourceDescriptor.addProperty("serverId", serverId);
        sourceDescriptor.addProperty("worldId", worldId);
        sourceDescriptor.addProperty("testName", testName);
        sourceDescriptor.addProperty("runId", runId);
        sourceDescriptor.addProperty("patchId", patch.patchId);
        sourceDescriptor.addProperty("revision", patch.revision);
        sourceDescriptor.addProperty("step", patch.step);
        sourceDescriptor.addProperty("sha256", patch.sha256);
        sourceDescriptor.addProperty("size", patch.artifact.length);
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

        int chunkCount = Math.max(1, (patch.artifact.length + AllcraftPayloads.MAX_CHUNK_BYTES - 1) / AllcraftPayloads.MAX_CHUNK_BYTES);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (!run.expectedPlayers.contains(player.getUUID())) {
                continue;
            }

            for (int chunkIndex = 0; chunkIndex < chunkCount; chunkIndex++) {
                int start = chunkIndex * AllcraftPayloads.MAX_CHUNK_BYTES;
                int end = Math.min(patch.artifact.length, start + AllcraftPayloads.MAX_CHUNK_BYTES);
                byte[] chunk = Arrays.copyOfRange(patch.artifact, start, end);
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
                                chunkIndex,
                                chunkCount,
                                patch.sha256,
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
                + "/5: "
                + patch.artifact.length
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
                patch.activationTick,
                patch.sha256
            )
        );
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (run.expectedPlayers.contains(player.getUUID())) {
                player.connection.send(packet);
            }
        }
    }

    private static void commitRevision(MinecraftServer server, TestRun run, Patch patch) throws IOException {
        Path manifestPath = run.patchesRoot.resolve("manifest.json");
        JsonObject manifest = readJson(manifestPath);
        manifest.addProperty("currentRevision", patch.revision);
        JsonArray patches = manifest.has("patches") ? manifest.getAsJsonArray("patches") : new JsonArray();
        JsonObject entry = new JsonObject();
        entry.addProperty("revision", patch.revision);
        entry.addProperty("patchId", patch.patchId);
        entry.addProperty("kind", "network-test");
        entry.addProperty("testName", run.testName);
        entry.addProperty("runId", run.runId);
        entry.addProperty("step", patch.step);
        entry.addProperty("sha256", patch.sha256);
        entry.addProperty("activationTick", patch.activationTick);
        entry.addProperty("activatedAt", Instant.now().toString());
        patches.add(entry);
        manifest.add("patches", patches);
        writeJsonAtomically(manifestPath, manifest);

        JsonObject result = new JsonObject();
        result.addProperty("testName", run.testName);
        result.addProperty("runId", run.runId);
        result.addProperty("step", patch.step);
        result.addProperty("revision", patch.revision);
        result.addProperty("patchId", patch.patchId);
        result.addProperty("sha256", patch.sha256);
        result.addProperty("activationTick", patch.activationTick);
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
            "[Allcraft] PASS " + run.testName + ": all five patches were cached, scheduled, activated, and acknowledged by every client",
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

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
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

    private static final class Patch {
        private final String patchId;
        private final long revision;
        private final int step;
        private final StepSpec spec;
        private final String sha256;
        private final byte[] artifact;
        private long activationTick;

        private Patch(String patchId, long revision, int step, StepSpec spec, String sha256, byte[] artifact) {
            this.patchId = patchId;
            this.revision = revision;
            this.step = step;
            this.spec = spec;
            this.sha256 = sha256;
            this.artifact = artifact;
        }
    }

    private static final class TestRun {
        private final String testName;
        private final String runId;
        private final String serverId;
        private final String worldId;
        private final List<Patch> patches;
        private final Path patchesRoot;
        private final Set<UUID> expectedPlayers = new HashSet<>();
        private final Set<UUID> readyPlayers = new HashSet<>();
        private final Set<UUID> appliedPlayers = new HashSet<>();
        private int patchIndex;
        private Phase phase = Phase.BETWEEN_PATCHES;
        private long phaseStartedAt;
        private long nextStageTick;

        private TestRun(String testName, String runId, String serverId, String worldId, List<Patch> patches, Path patchesRoot) {
            this.testName = testName;
            this.runId = runId;
            this.serverId = serverId;
            this.worldId = worldId;
            this.patches = patches;
            this.patchesRoot = patchesRoot;
        }

        private Patch current() {
            return this.patches.get(this.patchIndex);
        }
    }
}
