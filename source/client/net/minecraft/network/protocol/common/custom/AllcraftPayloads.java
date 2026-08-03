package net.minecraft.network.protocol.common.custom;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;

public final class AllcraftPayloads {
    public static final int MAX_CHUNK_BYTES = 700000;

    private AllcraftPayloads() {
    }

    private static <T extends CustomPacketPayload> CustomPacketPayload.Type<T> type(String path) {
        return new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("allcraft", path));
    }

    public enum ControlAction {
        SCHEDULE,
        ACTIVATE,
        COMMIT,
        FINALIZE,
        ABORT
    }

    public enum AckStatus {
        READY,
        APPLIED,
        COMMITTED,
        ROLLED_BACK,
        FAILED
    }

    public record PatchChunk(
        String serverId,
        String worldId,
        String patchId,
        long revision,
        String testName,
        int step,
        int totalSteps,
        int chunkIndex,
        int chunkCount,
        String sha256,
        byte[] data
    ) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<AllcraftPayloads.PatchChunk> TYPE = AllcraftPayloads.type("patch_chunk");
        public static final StreamCodec<RegistryFriendlyByteBuf, AllcraftPayloads.PatchChunk> STREAM_CODEC = CustomPacketPayload.codec(
            AllcraftPayloads.PatchChunk::write, AllcraftPayloads.PatchChunk::new
        );

        private PatchChunk(RegistryFriendlyByteBuf input) {
            this(
                input.readUtf(36),
                input.readUtf(36),
                input.readUtf(36),
                input.readVarLong(),
                input.readUtf(32),
                input.readVarInt(),
                input.readVarInt(),
                input.readVarInt(),
                input.readVarInt(),
                input.readUtf(64),
                input.readByteArray(MAX_CHUNK_BYTES)
            );
        }

        private void write(RegistryFriendlyByteBuf output) {
            output.writeUtf(this.serverId, 36);
            output.writeUtf(this.worldId, 36);
            output.writeUtf(this.patchId, 36);
            output.writeVarLong(this.revision);
            output.writeUtf(this.testName, 32);
            output.writeVarInt(this.step);
            output.writeVarInt(this.totalSteps);
            output.writeVarInt(this.chunkIndex);
            output.writeVarInt(this.chunkCount);
            output.writeUtf(this.sha256, 64);
            output.writeByteArray(this.data);
        }

        @Override
        public CustomPacketPayload.Type<AllcraftPayloads.PatchChunk> type() {
            return TYPE;
        }
    }

    public record PatchControl(
        ControlAction action,
        String serverId,
        String worldId,
        String patchId,
        long revision,
        String testName,
        int step,
        int totalSteps,
        long activationTick,
        String sha256
    ) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<AllcraftPayloads.PatchControl> TYPE = AllcraftPayloads.type("patch_control");
        public static final StreamCodec<RegistryFriendlyByteBuf, AllcraftPayloads.PatchControl> STREAM_CODEC = CustomPacketPayload.codec(
            AllcraftPayloads.PatchControl::write, AllcraftPayloads.PatchControl::new
        );

        private PatchControl(RegistryFriendlyByteBuf input) {
            this(
                readControlAction(input),
                input.readUtf(36),
                input.readUtf(36),
                input.readUtf(36),
                input.readVarLong(),
                input.readUtf(32),
                input.readVarInt(),
                input.readVarInt(),
                input.readVarLong(),
                input.readUtf(64)
            );
        }

        private void write(RegistryFriendlyByteBuf output) {
            output.writeVarInt(this.action.ordinal());
            output.writeUtf(this.serverId, 36);
            output.writeUtf(this.worldId, 36);
            output.writeUtf(this.patchId, 36);
            output.writeVarLong(this.revision);
            output.writeUtf(this.testName, 32);
            output.writeVarInt(this.step);
            output.writeVarInt(this.totalSteps);
            output.writeVarLong(this.activationTick);
            output.writeUtf(this.sha256, 64);
        }

        @Override
        public CustomPacketPayload.Type<AllcraftPayloads.PatchControl> type() {
            return TYPE;
        }
    }

    public record PatchAck(
        AckStatus status,
        String serverId,
        String worldId,
        String patchId,
        long revision,
        String sha256,
        String message
    ) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<AllcraftPayloads.PatchAck> TYPE = AllcraftPayloads.type("patch_ack");
        public static final StreamCodec<FriendlyByteBuf, AllcraftPayloads.PatchAck> STREAM_CODEC = CustomPacketPayload.codec(
            AllcraftPayloads.PatchAck::write, AllcraftPayloads.PatchAck::new
        );

        private PatchAck(FriendlyByteBuf input) {
            this(
                readAckStatus(input),
                input.readUtf(36),
                input.readUtf(36),
                input.readUtf(36),
                input.readVarLong(),
                input.readUtf(64),
                input.readUtf(512)
            );
        }

        private void write(FriendlyByteBuf output) {
            output.writeVarInt(this.status.ordinal());
            output.writeUtf(this.serverId, 36);
            output.writeUtf(this.worldId, 36);
            output.writeUtf(this.patchId, 36);
            output.writeVarLong(this.revision);
            output.writeUtf(this.sha256, 64);
            output.writeUtf(this.message, 512);
        }

        @Override
        public CustomPacketPayload.Type<AllcraftPayloads.PatchAck> type() {
            return TYPE;
        }
    }

    private static ControlAction readControlAction(FriendlyByteBuf input) {
        int ordinal = input.readVarInt();
        ControlAction[] values = ControlAction.values();
        if (ordinal < 0 || ordinal >= values.length) {
            throw new IllegalArgumentException("Unknown Allcraft control action " + ordinal);
        }

        return values[ordinal];
    }

    private static AckStatus readAckStatus(FriendlyByteBuf input) {
        int ordinal = input.readVarInt();
        AckStatus[] values = AckStatus.values();
        if (ordinal < 0 || ordinal >= values.length) {
            throw new IllegalArgumentException("Unknown Allcraft acknowledgement status " + ordinal);
        }

        return values[ordinal];
    }
}
