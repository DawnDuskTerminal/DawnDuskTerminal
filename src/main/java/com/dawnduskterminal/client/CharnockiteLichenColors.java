package com.dawnduskterminal.client;

import java.util.Random;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.FastColor;

public final class CharnockiteLichenColors {
    private CharnockiteLichenColors() {}

    public interface WorldColor {
        int ddt$lichenColor();
    }

    /**
     * 一局一色：以世界种子为随机源，三个通道各自在 0 到 255 上均匀抽取，不加区间、不加纯度门槛。
     *
     * <p>限制通道区间等于给色度封顶：每个通道都落在 88 至 232 时，色度最多 144，
     * 因此抽出来的颜色永远到不了纯色。这里改为完全不限制
     */
    public static int fromSeedHash(long seedHash) {
        Random random = new Random(seedHash);
        return FastColor.ARGB32.color(255, random.nextInt(256), random.nextInt(256), random.nextInt(256));
    }

    static int current() {
        ClientLevel level = Minecraft.getInstance().level;
        return level instanceof WorldColor color ? color.ddt$lichenColor() : 0xAAC8B2;
    }
}
