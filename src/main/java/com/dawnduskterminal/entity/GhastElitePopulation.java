package com.dawnduskterminal.entity;

import java.util.HashMap;
import java.util.Map;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

/**
 * 「大恶魂」的自然生成容量：同一维度的加载范围内最多同时存在 {@value #CAP} 只
 *
 * <p>实现方式与 {@link GhastFruityPopulation} 相同，仅上限取值不同。自然生成上限为 1，
 * 因此加载范围内已存在一只时，群系 monster 表的抽取结果不会再生成第二只；该只被击杀后，
 * 至多经过一个游戏刻即可补充。
 *
 * <p>{@code getMaxSpawnClusterSize} 约束的是单次生成的数量，无法限制累计数量，本类负责后者。
 *
 * <p>快照以维度为键常驻于进程之内，随进程结束而释放，且只在服务端主线程访问。
 */
public final class GhastElitePopulation {
    /** 上限：同一维度的加载范围内最多 1 只 */
    public static final int CAP = 1;

    /** 各维度最近一次统计的结果 */
    private static final Map<ResourceKey<Level>, Snapshot> SNAPSHOTS = new HashMap<>();

    /** 当前加载中的个体数量。不读取缓存，供测试与调试使用 */
    public static int loaded(ServerLevel level) {
        return level.getEntitiesOfClass(GhastEliteEntity.class, WORLD_BOUNDS).size();
    }

    /**
     * 判断是否还能再生成一只
     *
     * @return {@code true} 表示尚未达到上限，并同时占用该名额
     */
    public static boolean tryReserve(ServerLevel level) {
        long now = level.getGameTime();
        Snapshot snapshot = SNAPSHOTS.computeIfAbsent(level.dimension(), key -> new Snapshot());
        if (snapshot.gameTime != now) {
            snapshot.live = loaded(level);
            snapshot.reserved = 0;
            snapshot.gameTime = now;
        }
        if (snapshot.live + snapshot.reserved >= CAP) {
            return false;
        }
        snapshot.reserved++;
        return true;
    }

    /** 单个维度的统计结果：加载中的个体数量与本游戏刻已保留的名额数量 */
    private static final class Snapshot {
        private long gameTime = Long.MIN_VALUE;
        private int live;
        private int reserved;
    }

    /** 与 {@link GhastFruityPopulation} 使用同一包围盒：实体管理器仅遍历已加载的区段 */
    private static final AABB WORLD_BOUNDS = new AABB(-3.0E7D, -3.0E7D, -3.0E7D, 3.0E7D, 3.0E7D, 3.0E7D);

    private GhastElitePopulation() {}
}
