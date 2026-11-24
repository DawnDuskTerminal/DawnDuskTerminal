package com.dawnduskterminal.world;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;

/** 生命饱和度与消耗余数随玩家保存，客户端只接收绘制血条所需的数值。 */
public final class HuiVitals {
    public static final Codec<HuiVitals> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                    Codec.floatRange(0, 20).fieldOf("shield").forGetter((HuiVitals vitals) -> vitals.shield),
                    Codec.intRange(0, 3).fieldOf("exhaustion").forGetter(vitals -> vitals.exhaustion),
                    Codec.BOOL.fieldOf("downed").forGetter(vitals -> vitals.downed))
            .apply(instance, HuiVitals::new));
    public static final StreamCodec<RegistryFriendlyByteBuf, HuiVitals> STREAM_CODEC = StreamCodec.of(
            (buf, vitals) -> {
                buf.writeFloat(vitals.shield);
                buf.writeBoolean(vitals.downed);
            },
            buf -> new HuiVitals(buf.readFloat(), 0, buf.readBoolean()));

    float shield;
    int exhaustion;
    boolean downed;
    int drainProgress;
    float damageRemainder;
    int recoveryTicks;
    int primaryTicks;
    int primaryInterval;
    boolean recoveringHealth;
    boolean armoredHit;
    boolean finalizingDeath;

    public HuiVitals() {}

    private HuiVitals(float shield, int exhaustion, boolean downed) {
        this.shield = shield;
        this.exhaustion = exhaustion;
        this.downed = downed;
    }
}
