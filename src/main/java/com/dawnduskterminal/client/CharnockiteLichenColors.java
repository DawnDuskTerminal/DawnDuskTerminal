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

    public static int fromSeedHash(long seedHash) {
        Random random = new Random(seedHash);
        int red;
        int green;
        int blue;
        int channelSpread;
        do {
            red = random.nextInt(88, 233);
            green = random.nextInt(88, 233);
            blue = random.nextInt(88, 233);
            channelSpread = Math.max(red, Math.max(green, blue)) - Math.min(red, Math.min(green, blue));
        } while (channelSpread < 96);
        return FastColor.ARGB32.color(255, red, green, blue);
    }

    static int current() {
        ClientLevel level = Minecraft.getInstance().level;
        return level instanceof WorldColor color ? color.ddt$lichenColor() : 0xAAC8B2;
    }
}
