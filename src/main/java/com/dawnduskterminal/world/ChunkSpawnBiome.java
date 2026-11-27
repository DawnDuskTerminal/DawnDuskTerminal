package com.dawnduskterminal.world;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * 区块生成阶段刷动物时该用哪一份群系表
 *
 * <h2>原版为什么要问群系</h2>
 *
 * <p>原版 {@code NoiseBasedChunkGenerator#spawnOriginalMobs} 按整块区块刷一包动物，取表用的群系是
 * {@code chunkPos.getWorldPosition().atY(getMaxBuildHeight() - 1)}，也就是<b>世界顶端那一格</b>。
 * 这在主世界成立：群系由气候决定，顶端那一格与地表的群系相同。
 *
 * <h2>本维度为什么不成立</h2>
 *
 * <p>晨昏线维度的顶端高度是 319。{@code overworld/depth} 的 y 梯度在 320 处为 −1.5，海洋列的
 * offset 约 −0.46 至 −0.73，于是顶端那一格的采样深度约 −1.95 至 −2.22，恒低于 −1.6，
 * {@link PaletteBiomeSource} 因此直接返回天门群系（{@code dawnduskterminal:floatrees}）。
 * 若照抄原版取样点，河海、海岸乃至 {@code deep_dark} 这些本该空表的区块都会拿到天门那份动物表，
 * 一包鹿就落在河海群系的霞草地上——落点判定只查 {@code #minecraft:animals_spawnable_on} 与亮度，
 * 从不复核落点那一格的群系。
 *
 * <h2>改法</h2>
 *
 * <p>改问该区块中心列的地表群系。这一格既是玩家实际踏到的那一层，也与群系表的其余用法一致。
 * y 的换算单独拆成 {@link #spawnY}，以便游戏测试直接断言「不许取建筑上限减一」。
 *
 * @see com.dawnduskterminal.mixin.ChunkSpawnBiomeMixin
 */
public final class ChunkSpawnBiome {
    private ChunkSpawnBiome() {}

    /**
     * 该区块中心列地表那一格（含其上第一格）的群系
     *
     * @param level 区块生成期的世界视图，只读、不加载任何区块
     * @param pos 区块坐标
     */
    public static Holder<Biome> of(WorldGenRegion level, ChunkPos pos) {
        int x = pos.getMiddleBlockX();
        int z = pos.getMiddleBlockZ();
        int y = spawnY(
                level.getHeight(Heightmap.Types.WORLD_SURFACE_WG, x, z),
                level.getMinBuildHeight(),
                level.getMaxBuildHeight());
        return level.getBiome(new BlockPos(x, y, z));
    }

    /**
     * 由高度图取的「第一格可用高度」换算出要采样的 y
     *
     * <p>{@link Heightmap.Types#WORLD_SURFACE_WG} 返回的是地表之上第一格，因此减一才落在实体方块上；
     * 结果再夹进世界高度范围，满列不会给出建筑上限之外的坐标。
     *
     * @param firstAvailable 高度图给出的第一格可用高度
     * @param minY 世界最低高度
     * @param maxY 世界的建筑上限（不含）
     */
    public static int spawnY(int firstAvailable, int minY, int maxY) {
        return Mth.clamp(firstAvailable - 1, minY, maxY - 1);
    }
}
