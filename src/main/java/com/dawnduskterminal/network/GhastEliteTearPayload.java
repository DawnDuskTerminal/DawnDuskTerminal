package com.dawnduskterminal.network;

import com.dawnduskterminal.DawnDuskTerminal;
import com.dawnduskterminal.client.GhastEliteTearParticles;
import com.dawnduskterminal.entity.GhastEliteTear;
import java.util.UUID;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** 单向的泪滴视觉状态；身份、维度及服务端时间共同避免重复显示与过期状态残留。 */
public record GhastEliteTearPayload(
        ResourceKey<Level> dimension,
        UUID id,
        int entityId,
        int kind,
        Vec3 position,
        Vec3 velocity,
        int age,
        int lifetime,
        long gameTime)
        implements CustomPacketPayload {
    public static final int STATE = 0;
    public static final int BREAK = 1;
    public static final int END = 2;
    public static final Type<GhastEliteTearPayload> TYPE = new Type<>(DawnDuskTerminal.id("ghast_elite_tear"));
    public static final StreamCodec<RegistryFriendlyByteBuf, GhastEliteTearPayload> STREAM_CODEC = StreamCodec.of(
            (buffer, payload) -> {
                buffer.writeResourceKey(payload.dimension());
                buffer.writeUUID(payload.id());
                buffer.writeVarInt(payload.entityId());
                buffer.writeByte(payload.kind());
                buffer.writeVec3(payload.position());
                buffer.writeVec3(payload.velocity());
                buffer.writeVarInt(payload.age());
                buffer.writeVarInt(payload.lifetime());
                buffer.writeLong(payload.gameTime());
            },
            buffer -> new GhastEliteTearPayload(
                    buffer.readResourceKey(Registries.DIMENSION),
                    buffer.readUUID(),
                    buffer.readVarInt(),
                    buffer.readUnsignedByte(),
                    buffer.readVec3(),
                    buffer.readVec3(),
                    buffer.readVarInt(),
                    buffer.readVarInt(),
                    buffer.readLong()));

    public boolean valid() {
        return this.kind >= STATE
                && this.kind <= END
                && this.entityId >= 0
                && finitePosition(this.position)
                && finiteVelocity(this.velocity)
                && this.lifetime >= GhastEliteTear.MIN_LIFETIME
                && this.lifetime <= GhastEliteTear.MAX_LIFETIME
                && this.age >= 0
                && this.age <= this.lifetime
                && this.gameTime >= 0;
    }

    public static boolean finitePosition(Vec3 position) {
        return Double.isFinite(position.x)
                && Double.isFinite(position.y)
                && Double.isFinite(position.z)
                && Math.abs(position.x) <= 30_000_000.0D
                && Math.abs(position.y) <= 30_000_000.0D
                && Math.abs(position.z) <= 30_000_000.0D;
    }

    public static boolean finiteVelocity(Vec3 velocity) {
        return Double.isFinite(velocity.x)
                && Double.isFinite(velocity.y)
                && Double.isFinite(velocity.z)
                && velocity.lengthSqr() <= 256.0D;
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(GhastEliteTearPayload payload, IPayloadContext context) {
        if (payload.valid()) {
            context.enqueueWork(() -> GhastEliteTearParticles.accept(payload));
        }
    }
}
