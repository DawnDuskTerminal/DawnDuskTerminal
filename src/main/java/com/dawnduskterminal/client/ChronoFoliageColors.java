package com.dawnduskterminal.client;

import com.dawnduskterminal.world.ChronoPlantTints;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nullable;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.biome.Biome;

/**
 * 把晨昏线维度的群系副本接到 {@link ChronoPlantTints} 上
 *
 * <p>暮色森林用 ASM 改写 {@code BiomeColors} 的两个取色器，再按它自己的群系 ID 查表上色。晨昏线的副本在
 * {@code dawnduskterminal:*} 命名空间下，查表必然落空，草与树叶只会用普通的颜色。这里补上 ID 解析，
 * 规则本身在公共包 {@link ChronoPlantTints} 里（那里能跑游戏测试）
 *
 * <p>接入点是 {@code BiomeColorsPlantMixin}：两个取色器都被包了一层，原取色器照常算，只对我们认得出来的
 * 群系改结果，因此暮色森林自己的补丁与其他所有群系都不受影响
 */
public final class ChronoFoliageColors {
    private static final Map<Biome, ResourceLocation> ID_CACHE = new ConcurrentHashMap<>();

    private ChronoFoliageColors() {}

    /** 树叶色 */
    public static int resolve(int original, Biome biome, double x, double z) {
        return ChronoPlantTints.foliage(id(biome), (int) x, (int) z, original);
    }

    /** 草方块色 */
    public static int resolveGrass(int original, Biome biome, double x, double z) {
        return ChronoPlantTints.grass(id(biome), (int) x, (int) z, original);
    }

    @Nullable
    private static ResourceLocation id(Biome biome) {
        ResourceLocation cached = ID_CACHE.get(biome);
        if (cached != null) {
            return cached;
        }
        // 取色跑在区块网格重建的工作线程上，主线程随时可能把 level 置空。先取到局部变量再判空与解引用，
        // 否则判空和解引用之间会撞上 NPE 打断区块编译
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            return null;
        }
        ResourceLocation id =
                level.registryAccess().registryOrThrow(Registries.BIOME).getKey(biome);
        if (id != null) {
            ID_CACHE.put(biome, id);
        }
        return id;
    }

    /** 断开连接时清空；旧世界的 Biome 实例作为 key 会一直留住整张缓存 */
    public static void clearCache() {
        ID_CACHE.clear();
    }
}
