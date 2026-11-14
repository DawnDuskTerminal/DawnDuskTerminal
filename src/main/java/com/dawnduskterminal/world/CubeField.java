package com.dawnduskterminal.world;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;

/**
 * 试验物品 065 与 069 共用的 17×17×17 立方场，方块所在位置为场心。
 *
 * <p>场心按维度和区块登记；查询只访问半径范围内的候选桶，再按三轴坐标差判断覆盖。
 * 方块及方块实体负责登记与撤登记，查询时校验场心方块并清除幽灵条目，但不加载区块。
 * 没有场心的维度只进行一次映射查询，不扫描世界或构造临时集合。
 */
public final class CubeField {
    /** 半边长度：17×17×17 就是中心那一格往每个方向各推 8 格 */
    public static final int RADIUS = 8;

    /** 边长：17 */
    public static final int SIZE = RADIUS * 2 + 1;

    /** 按维度、区块索引场心；仅由服务端线程维护。 */
    private final Map<ResourceKey<Level>, Map<Long, Set<BlockPos>>> centres = new HashMap<>();

    /** 场心那一格该是什么方块：判定时回头核一遍，用来认幽灵场 */
    private final Supplier<Block> block;

    private CubeField(Supplier<Block> block) {
        this.block = block;
    }

    /**
     * 开一张场
     *
     * <p>方块用 {@link Supplier} 传进来而不是直接传实例：注册表里的方块要等到注册完才有值，
     * 这两张场在类初始化时就建出来了
     */
    public static CubeField of(Supplier<Block> block) {
        return new CubeField(block);
    }

    /** 登记一个场心。方块实体装载、方块刚摆下时各来一次，重复登记没有额外代价（就是个集合） */
    public void track(Level level, BlockPos pos) {
        if (level == null || level.isClientSide) {
            return;
        }
        BlockPos centre = pos.immutable();
        this.centres
                .computeIfAbsent(level.dimension(), key -> new HashMap<>())
                .computeIfAbsent(bucketKey(centre), key -> new HashSet<>())
                .add(centre);
    }

    /** 撤掉一个场心。方块实体卸载、方块被挖掉或推走时各来一次，撤不存在的没有代价 */
    public void untrack(Level level, BlockPos pos) {
        if (level == null || level.isClientSide) {
            return;
        }
        Map<Long, Set<BlockPos>> buckets = this.centres.get(level.dimension());
        if (buckets == null) {
            return;
        }
        long key = bucketKey(pos);
        Set<BlockPos> bucket = buckets.get(key);
        if (bucket == null) {
            return;
        }
        bucket.remove(pos);
        if (bucket.isEmpty()) {
            buckets.remove(key);
            if (buckets.isEmpty()) {
                this.centres.remove(level.dimension());
            }
        }
    }

    /** 服务器停下：整张表清干净，别带到下一次启动 */
    public void clear() {
        this.centres.clear();
    }

    /**
     * 这一格在不在某个场里
     *
     * <p>先按方块坐标卡 17³（{@code |dx|、|dy|、|dz| <= 8}），再核那一格还是不是本方块；方块没了就把这条
     * 场心顺手丢掉，幽灵场只可能活到它下一次被问到
     */
    public boolean covers(Level level, BlockPos at) {
        Map<Long, Set<BlockPos>> buckets = this.centres.get(level.dimension());
        if (buckets == null || buckets.isEmpty()) {
            return false;
        }
        int minX = SectionPos.blockToSectionCoord(at.getX() - RADIUS);
        int maxX = SectionPos.blockToSectionCoord(at.getX() + RADIUS);
        int minZ = SectionPos.blockToSectionCoord(at.getZ() - RADIUS);
        int maxZ = SectionPos.blockToSectionCoord(at.getZ() + RADIUS);
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                long key = ChunkPos.asLong(x, z);
                Set<BlockPos> bucket = buckets.get(key);
                if (bucket == null) {
                    continue;
                }
                Iterator<BlockPos> iterator = bucket.iterator();
                while (iterator.hasNext()) {
                    BlockPos centre = iterator.next();
                    if (Math.abs(at.getX() - centre.getX()) > RADIUS
                            || Math.abs(at.getY() - centre.getY()) > RADIUS
                            || Math.abs(at.getZ() - centre.getZ()) > RADIUS) {
                        continue;
                    }
                    if (!level.getChunkSource().hasChunk(x, z)) {
                        continue;
                    }
                    if (!level.getBlockState(centre).is(this.block.get())) {
                        iterator.remove();
                        continue;
                    }
                    return true;
                }
                if (bucket.isEmpty()) {
                    buckets.remove(key);
                }
            }
        }
        if (buckets.isEmpty()) {
            this.centres.remove(level.dimension());
        }
        return false;
    }

    private static long bucketKey(BlockPos pos) {
        return ChunkPos.asLong(SectionPos.blockToSectionCoord(pos.getX()), SectionPos.blockToSectionCoord(pos.getZ()));
    }
}
