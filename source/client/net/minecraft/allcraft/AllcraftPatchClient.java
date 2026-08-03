package net.minecraft.allcraft;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.AllcraftPayloads;
import net.minecraft.sounds.SoundEvents;
import org.slf4j.Logger;

public final class AllcraftPatchClient {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Gson COMPACT_GSON = new Gson();
    private static final long MAX_PATCH_BYTES = 64L * 1024L * 1024L;
    private static final Map<PatchKey, IncomingPatch> INCOMING = new HashMap<>();
    private static final Map<PatchKey, StagedPatch> STAGED = new HashMap<>();
    private static final Map<PatchKey, ActivePatch> ACTIVE = new HashMap<>();
    private static final Map<PatchKey, ActivePatch> COMMITTED = new HashMap<>();
    private static final Map<PatchKey, AllcraftPayloads.PatchControl> SCHEDULED = new HashMap<>();

    private AllcraftPatchClient() {
    }

    /** Reconciles process-lifetime JVM/resource state when leaving any remote or integrated world. */
    public static void disconnect(Minecraft minecraft) {
        for (ActivePatch active : Stream.concat(ACTIVE.values().stream(), COMMITTED.values().stream()).toList()) {
            try {
                active.runtime.rollback();
            } catch (Exception e) {
                LOGGER.error("Failed to roll back an in-flight Allcraft client transaction on disconnect", e);
            }
        }
        ACTIVE.clear();
        COMMITTED.clear();
        STAGED.clear();
        SCHEDULED.clear();
        INCOMING.clear();
        try {
            AllcraftRuntime.resetToBase();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to reconcile Allcraft classes on disconnect", e);
        }
        minecraft.allcraftRestoreResourceState(new Minecraft.AllcraftResourceState(java.util.List.of(), java.util.Set.of()))
            .exceptionally(failure -> {
                LOGGER.error("Failed to restore base resources on disconnect", failure);
                return null;
            });
    }

    public static void handleChunk(Minecraft minecraft, ClientPacketListener connection, AllcraftPayloads.PatchChunk payload) {
        try {
            PatchKey key = PatchKey.from(payload.serverId(), payload.worldId(), payload.patchId(), payload.revision());
            validateHash(payload.sha256());
            if (payload.totalSteps() < 1
                || payload.totalSteps() > 1000
                || payload.step() < 1
                || payload.step() > payload.totalSteps()
                || payload.chunkCount() < 1
                || payload.chunkCount() > 256) {
                throw new IOException("Invalid patch chunk metadata");
            }

            if (payload.chunkIndex() < 0 || payload.chunkIndex() >= payload.chunkCount()) {
                throw new IOException("Invalid patch chunk index " + payload.chunkIndex());
            }

            IncomingPatch incoming = INCOMING.computeIfAbsent(
                key,
                ignored -> new IncomingPatch(payload.testName(), payload.step(), payload.totalSteps(), payload.chunkCount(), payload.sha256())
            );
            incoming.accept(payload);
            if (!incoming.complete()) {
                return;
            }

            byte[] artifact = incoming.assemble();
            if (!sha256(artifact).equals(payload.sha256())) {
                throw new IOException("Patch SHA-256 mismatch");
            }

            Path artifactPath = cacheArtifact(key, payload.testName(), payload.step(), payload.totalSteps(), payload.sha256(), artifact);
            INCOMING.remove(key);
            AllcraftRuntime.Transaction runtime = AllcraftRuntime.stage(artifactPath, payload.sha256());
            AllcraftClientResources.PreflightResult resources = AllcraftClientResources.preflight(artifactPath);
            STAGED.put(key, new StagedPatch(artifactPath, runtime, minecraft.allcraftCaptureResourceState(), resources));
            sendAck(
                connection,
                payload,
                AllcraftPayloads.AckStatus.READY,
                "staged " + artifact.length + " bytes, " + resources.changedResources() + " resource(s)"
            );
            minecraft.showDebugChat(
                Component.literal(
                        "[Allcraft] READY "
                            + payload.testName()
                            + " "
                            + payload.step()
                            + "/"
                            + payload.totalSteps()
                            + ", revision "
                            + payload.revision()
                    )
                    .withStyle(ChatFormatting.DARK_GRAY)
            );
        } catch (Exception e) {
            LOGGER.error("Failed to stage Allcraft patch {}", payload.patchId(), e);
            sendAck(connection, payload, AllcraftPayloads.AckStatus.FAILED, conciseMessage(e));
            minecraft.showDebugChat(Component.literal("[Allcraft] Patch staging failed: " + conciseMessage(e)).withStyle(ChatFormatting.RED));
        }
    }

    public static void handleControl(Minecraft minecraft, ClientPacketListener connection, AllcraftPayloads.PatchControl payload) {
        try {
            PatchKey key = PatchKey.from(payload.serverId(), payload.worldId(), payload.patchId(), payload.revision());
            validateHash(payload.sha256());
            if (payload.action() == AllcraftPayloads.ControlAction.ABORT) {
                abort(minecraft, connection, key, payload);
                return;
            }
            StagedPatch staged = STAGED.get(key);
            Path artifact = staged == null ? artifactPath(key) : staged.artifact;
            if (!Files.isRegularFile(artifact) || !sha256(artifact).equals(payload.sha256())) {
                throw new IOException("Staged artifact is missing or does not match its hash");
            }

            if (payload.action() == AllcraftPayloads.ControlAction.SCHEDULE) {
                if (staged == null) {
                    staged = new StagedPatch(
                        artifact,
                        AllcraftRuntime.stage(artifact, payload.sha256()),
                        minecraft.allcraftCaptureResourceState(),
                        AllcraftClientResources.preflight(artifact)
                    );
                    STAGED.put(key, staged);
                }
                SCHEDULED.put(key, payload);
                minecraft.showDebugChat(
                    Component.literal(
                            "[Allcraft] SCHEDULED "
                                + payload.testName()
                                + " "
                                + payload.step()
                                + "/"
                                + payload.totalSteps()
                                + " for server tick "
                                + payload.activationTick()
                        )
                        .withStyle(ChatFormatting.GRAY)
                );
                return;
            }

            if (payload.action() == AllcraftPayloads.ControlAction.COMMIT) {
                ActivePatch active = ACTIVE.get(key);
                if (active == null) {
                    throw new IOException("No published transaction is waiting for commit");
                }
                active.runtime.finish();
                ACTIVE.remove(key);
                COMMITTED.put(key, active);
                sendAck(connection, payload, AllcraftPayloads.AckStatus.COMMITTED, "revision committed");
                return;
            }

            if (payload.action() == AllcraftPayloads.ControlAction.FINALIZE) {
                ActivePatch active = COMMITTED.remove(key);
                if (active == null) {
                    throw new IOException("No committed transaction is waiting for finalization");
                }
                try {
                    AllcraftClientResources.commit(
                        payload.serverId(), payload.worldId(), payload.revision(), active.artifact, payload.sha256(), active.resourceResult
                    );
                    writeCurrentRevision(key, payload, active.artifact);
                    appendResult(key, payload, active.artifact, active.runtimeResult, active.resourceResult);
                } catch (IOException metadataFailure) {
                    // The server manifest is already authoritative at FINALIZE. A local metadata
                    // write can be reconstructed on reconnect and must never undo committed code.
                    LOGGER.error("Failed to persist finalized Allcraft client metadata for {}", payload.patchId(), metadataFailure);
                } finally {
                    active.runtime.seal();
                    STAGED.remove(key);
                }
                return;
            }

            AllcraftPayloads.PatchControl schedule = SCHEDULED.remove(key);
            if (schedule == null || schedule.activationTick() != payload.activationTick()) {
                throw new IOException("Patch activation did not match its schedule");
            }
            if (staged == null) {
                throw new IOException("Patch was not staged before activation");
            }

            staged.runtime.expectRegistryPlan(payload.registryPlan());
            AllcraftRuntime.ApplyResult runtimeResult = staged.runtime.publish();
            if (staged.runtime.hasRegistryMutations() && minecraft.level != null) {
                AllcraftRegistries.refreshComponents(minecraft.level.registryAccess());
            }
            StagedPatch activation = staged;
            ACTIVE.put(key, new ActivePatch(artifact, activation.runtime, activation.resourcesBefore, runtimeResult, null));
            AllcraftClientResources.apply(
                    minecraft, artifact, payload.serverId(), payload.worldId(), payload.revision(), payload.sha256()
                )
                .whenCompleteAsync((resourceResult, error) -> {
                    if (error != null) {
                        Throwable cause = error instanceof java.util.concurrent.CompletionException && error.getCause() != null
                            ? error.getCause()
                            : error;
                        failAndRollback(minecraft, connection, key, payload, activation, cause, "Resource activation failed");
                        return;
                    }

                    try {
                        applyTestArtifact(minecraft, artifact, payload);
                        ACTIVE.put(
                            key,
                            new ActivePatch(artifact, activation.runtime, activation.resourcesBefore, runtimeResult, resourceResult)
                        );
                        sendAck(
                            connection,
                            payload,
                            AllcraftPayloads.AckStatus.APPLIED,
                            "published at server tick "
                                + payload.activationTick()
                                + ": "
                                + runtimeResult.summary()
                                + "; "
                                + resourceResult.summary()
                        );
                    } catch (Exception e) {
                        failAndRollback(minecraft, connection, key, payload, activation, e, "Patch activation failed");
                    }
                }, minecraft);
        } catch (Exception e) {
            PatchKey key = new PatchKey(payload.serverId(), payload.worldId(), payload.patchId(), payload.revision());
            StagedPatch failed = STAGED.get(key);
            if (failed != null && failed.runtime.started()) {
                failAndRollback(minecraft, connection, key, payload, failed, e, "Patch activation failed");
                return;
            }
            LOGGER.error("Failed to activate Allcraft patch {}", payload.patchId(), e);
            sendAck(connection, payload, AllcraftPayloads.AckStatus.FAILED, conciseMessage(e));
            minecraft.showDebugChat(Component.literal("[Allcraft] Patch activation failed: " + conciseMessage(e)).withStyle(ChatFormatting.RED));
        }
    }

    private static void failAndRollback(
        Minecraft minecraft,
        ClientPacketListener connection,
        PatchKey key,
        AllcraftPayloads.PatchControl payload,
        StagedPatch staged,
        Throwable failure,
        String display
    ) {
        ACTIVE.remove(key);
        COMMITTED.remove(key);
        try {
            staged.runtime.rollback();
        } catch (Exception rollbackError) {
            failure.addSuppressed(rollbackError);
        }
        minecraft.allcraftRestoreResourceState(staged.resourcesBefore).whenCompleteAsync((unused, restoreFailure) -> {
            if (restoreFailure != null) {
                failure.addSuppressed(restoreFailure);
            }
            LOGGER.error("{} for Allcraft patch {}", display, payload.patchId(), failure);
            sendAck(connection, payload, AllcraftPayloads.AckStatus.FAILED, conciseMessage(failure));
            minecraft.showDebugChat(Component.literal("[Allcraft] " + display + ": " + conciseMessage(failure)).withStyle(ChatFormatting.RED));
        }, minecraft);
    }

    private static void abort(
        Minecraft minecraft, ClientPacketListener connection, PatchKey key, AllcraftPayloads.PatchControl payload
    ) {
        ActivePatch active = ACTIVE.remove(key);
        if (active == null) {
            active = COMMITTED.remove(key);
        }
        STAGED.remove(key);
        SCHEDULED.remove(key);
        if (active == null) {
            sendAck(connection, payload, AllcraftPayloads.AckStatus.ROLLED_BACK, "staged transaction discarded");
            return;
        }
        ActivePatch rollback = active;
        try {
            rollback.runtime.rollback();
        } catch (Exception e) {
            LOGGER.error("Failed to roll back Allcraft classes for {}", payload.patchId(), e);
        }
        minecraft.allcraftRestoreResourceState(rollback.resourcesBefore).whenCompleteAsync((unused, failure) -> {
            if (failure != null) {
                sendAck(connection, payload, AllcraftPayloads.AckStatus.FAILED, "rollback failed: " + conciseMessage(failure));
            } else {
                sendAck(connection, payload, AllcraftPayloads.AckStatus.ROLLED_BACK, "published transaction rolled back");
            }
        }, minecraft);
    }

    private static Path cacheArtifact(PatchKey key, String testName, int step, int totalSteps, String hash, byte[] artifact) throws IOException {
        Path root = cacheRoot(key);
        Path revisions = root.resolve("revisions");
        Path manifests = root.resolve("manifests");
        Files.createDirectories(revisions);
        Files.createDirectories(manifests);

        Path artifactPath = artifactPath(key);
        writeAtomically(artifactPath, artifact);

        JsonObject manifest = new JsonObject();
        manifest.addProperty("serverId", key.serverId());
        manifest.addProperty("worldId", key.worldId());
        manifest.addProperty("patchId", key.patchId());
        manifest.addProperty("revision", key.revision());
        manifest.addProperty("testName", testName);
        manifest.addProperty("step", step);
        manifest.addProperty("totalSteps", totalSteps);
        manifest.addProperty("sha256", hash);
        manifest.addProperty("size", artifact.length);
        manifest.addProperty("cachedAt", Instant.now().toString());
        writeJsonAtomically(manifests.resolve(fileStem(key) + ".json"), manifest);
        return artifactPath;
    }

    private static void applyTestArtifact(Minecraft minecraft, Path artifact, AllcraftPayloads.PatchControl control) throws IOException {
        JsonObject testPatch;
        try (JarFile jar = new JarFile(artifact.toFile())) {
            JarEntry entry = jar.getJarEntry("allcraft/test-patch.json");
            if (entry == null) {
                minecraft.showDebugChat(
                    Component.literal("[Allcraft] Published source revision " + control.revision()).withStyle(ChatFormatting.AQUA)
                );
                return;
            }

            try (Reader reader = new InputStreamReader(jar.getInputStream(entry), StandardCharsets.UTF_8)) {
                testPatch = JsonParser.parseReader(reader).getAsJsonObject();
            }
        }

        String display = testPatch.get("display").getAsString();
        String message = testPatch.get("message").getAsString();
        Component component = Component.literal(
                "[Allcraft "
                    + control.testName()
                    + " "
                    + control.step()
                    + "/"
                    + control.totalSteps()
                    + "] "
                    + message
                    + " (revision "
                    + control.revision()
                    + ")"
            )
            .withStyle(ChatFormatting.AQUA);

        minecraft.showDebugChat(component);
        if (control.testName().equals("live-sound")) {
            minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.EXPERIENCE_ORB_PICKUP, 1.0F, 1.0F));
        }
        switch (display) {
            case "actionbar" -> minecraft.gui.hud.setOverlayMessage(component, false);
            case "title" -> {
                minecraft.gui.hud.setTimes(5, 30, 5);
                minecraft.gui.hud.setSubtitle(Component.literal("Server tick " + control.activationTick()).withStyle(ChatFormatting.GRAY));
                minecraft.gui.hud.setTitle(component);
            }
            default -> {
            }
        }
    }

    private static void writeCurrentRevision(PatchKey key, AllcraftPayloads.PatchControl control, Path artifact) throws IOException {
        JsonObject current = new JsonObject();
        current.addProperty("serverId", key.serverId());
        current.addProperty("worldId", key.worldId());
        current.addProperty("patchId", key.patchId());
        current.addProperty("currentRevision", key.revision());
        current.addProperty("sha256", control.sha256());
        current.addProperty("artifact", artifact.getFileName().toString());
        current.addProperty("activationTick", control.activationTick());
        current.addProperty("appliedAt", Instant.now().toString());
        writeJsonAtomically(cacheRoot(key).resolve("current.json"), current);
    }

    private static void appendResult(
        PatchKey key,
        AllcraftPayloads.PatchControl control,
        Path artifact,
        AllcraftRuntime.ApplyResult runtimeResult,
        AllcraftClientResources.ApplyResult resourceResult
    ) throws IOException {
        Path results = cacheRoot(key).resolve("test-results");
        Files.createDirectories(results);
        JsonObject result = new JsonObject();
        result.addProperty("testName", control.testName());
        result.addProperty("step", control.step());
        result.addProperty("totalSteps", control.totalSteps());
        result.addProperty("revision", control.revision());
        result.addProperty("patchId", control.patchId());
        result.addProperty("activationTick", control.activationTick());
        result.addProperty("sha256", control.sha256());
        result.addProperty("artifact", artifact.getFileName().toString());
        result.addProperty("redefinedClasses", runtimeResult.redefinedClasses());
        result.addProperty("addedClasses", runtimeResult.addedClasses());
        result.addProperty("unchangedClasses", runtimeResult.unchangedClasses());
        result.addProperty("invokedEntrypoints", runtimeResult.invokedEntrypoints().size());
        result.addProperty("runtimeTotalMillis", runtimeResult.totalMillis());
        result.addProperty("runtimeRedefineMillis", runtimeResult.redefineMillis());
        result.addProperty("runtimeGcCollections", runtimeResult.gcCollections());
        result.addProperty("runtimeGcMillis", runtimeResult.gcMillis());
        result.addProperty("resourceReloaded", resourceResult.reloaded());
        result.addProperty("resourceChanged", resourceResult.changedResources());
        result.addProperty("resourceDeleted", resourceResult.deletedResources());
        result.addProperty("resourceReloadMillis", resourceResult.reloadMillis());
        result.addProperty("resourceNoLoadingScreen", resourceResult.noLoadingScreen());
        result.addProperty("appliedAt", Instant.now().toString());
        Files.writeString(
            results.resolve(control.testName() + ".jsonl"),
            COMPACT_GSON.toJson(result) + System.lineSeparator(),
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND
        );
    }

    private static Path cacheRoot(PatchKey key) throws IOException {
        String gameDir = System.getProperty("allcraft.gameDir");
        if (gameDir == null || gameDir.isBlank()) {
            throw new IOException("Missing JVM property allcraft.gameDir");
        }

        return Path.of(gameDir).toAbsolutePath().normalize().resolve("patches").resolve(key.serverId()).resolve(key.worldId());
    }

    private static Path artifactPath(PatchKey key) throws IOException {
        return cacheRoot(key).resolve("revisions").resolve(fileStem(key) + ".jar");
    }

    private static String fileStem(PatchKey key) {
        return String.format("%08d-%s", key.revision(), key.patchId());
    }

    private static void sendAck(
        ClientPacketListener connection, AllcraftPayloads.PatchChunk payload, AllcraftPayloads.AckStatus status, String message
    ) {
        connection.send(
            new ServerboundCustomPayloadPacket(
                new AllcraftPayloads.PatchAck(
                    status,
                    payload.serverId(),
                    payload.worldId(),
                    payload.patchId(),
                    payload.revision(),
                    payload.sha256(),
                    AllcraftRegistries.fingerprint(),
                    message
                )
            )
        );
    }

    private static void sendAck(
        ClientPacketListener connection, AllcraftPayloads.PatchControl payload, AllcraftPayloads.AckStatus status, String message
    ) {
        connection.send(
            new ServerboundCustomPayloadPacket(
                new AllcraftPayloads.PatchAck(
                    status,
                    payload.serverId(),
                    payload.worldId(),
                    payload.patchId(),
                    payload.revision(),
                    payload.sha256(),
                    AllcraftRegistries.fingerprint(),
                    message
                )
            )
        );
    }

    private static void validateHash(String hash) throws IOException {
        if (hash.length() != 64 || !hash.matches("[0-9a-f]{64}")) {
            throw new IOException("Invalid SHA-256 value");
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
        return sha256(Files.readAllBytes(path));
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

    private record PatchKey(String serverId, String worldId, String patchId, long revision) {
        private static PatchKey from(String serverId, String worldId, String patchId, long revision) throws IOException {
            try {
                UUID.fromString(serverId);
                UUID.fromString(worldId);
                UUID.fromString(patchId);
            } catch (IllegalArgumentException e) {
                throw new IOException("Invalid Allcraft patch identity", e);
            }

            if (revision < 0L) {
                throw new IOException("Invalid Allcraft patch revision");
            }

            return new PatchKey(serverId, worldId, patchId, revision);
        }
    }

    private record StagedPatch(
        Path artifact,
        AllcraftRuntime.Transaction runtime,
        Minecraft.AllcraftResourceState resourcesBefore,
        AllcraftClientResources.PreflightResult preflight
    ) {
    }

    private record ActivePatch(
        Path artifact,
        AllcraftRuntime.Transaction runtime,
        Minecraft.AllcraftResourceState resourcesBefore,
        AllcraftRuntime.ApplyResult runtimeResult,
        AllcraftClientResources.ApplyResult resourceResult
    ) {
    }

    private static final class IncomingPatch {
        private final String testName;
        private final int step;
        private final int totalSteps;
        private final String sha256;
        private final byte[][] chunks;
        private long totalBytes;
        private int receivedChunks;

        private IncomingPatch(String testName, int step, int totalSteps, int chunkCount, String sha256) {
            this.testName = testName;
            this.step = step;
            this.totalSteps = totalSteps;
            this.sha256 = sha256;
            this.chunks = new byte[chunkCount][];
        }

        private void accept(AllcraftPayloads.PatchChunk payload) throws IOException {
            if (!this.testName.equals(payload.testName())
                || this.step != payload.step()
                || this.totalSteps != payload.totalSteps()
                || !this.sha256.equals(payload.sha256())
                || this.chunks.length != payload.chunkCount()) {
                throw new IOException("Inconsistent patch chunk metadata");
            }

            byte[] existing = this.chunks[payload.chunkIndex()];
            if (existing != null) {
                if (!Arrays.equals(existing, payload.data())) {
                    throw new IOException("Conflicting duplicate patch chunk");
                }

                return;
            }

            this.totalBytes += payload.data().length;
            if (this.totalBytes > MAX_PATCH_BYTES) {
                throw new IOException("Patch exceeds the client size limit");
            }

            this.chunks[payload.chunkIndex()] = payload.data();
            this.receivedChunks++;
        }

        private boolean complete() {
            return this.receivedChunks == this.chunks.length;
        }

        private byte[] assemble() throws IOException {
            ByteArrayOutputStream output = new ByteArrayOutputStream(Math.toIntExact(this.totalBytes));
            for (byte[] chunk : this.chunks) {
                if (chunk == null) {
                    throw new IOException("Patch is missing a chunk");
                }

                output.write(chunk);
            }

            return output.toByteArray();
        }
    }
}
