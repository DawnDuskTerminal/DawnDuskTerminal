package com.dawnduskterminal.world;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.level.ServerLevelAccessor;
import net.neoforged.neoforge.event.entity.living.MobSpawnEvent;

/**
 * 雨天压制露天生长的生物
 *
 * <h2>规则</h2>
 *
 * <p>晨昏线维度下雨时，{@code 露天} 的格子一律不许自然刷怪：判定用
 * {@link ChronoRain#isExposedGeometry(net.minecraft.world.level.LevelReader, BlockPos)}
 * 与 {@link ChronoRain#isRainy(ServerLevel)}，与淋雨伤害、飞行型的刷出用的是同一套「算不算淋雨」，
 * 所以雨丝飘到哪里，压制就到哪里——洞穴、树冠下、屋里照常刷
 *
 * <h2>为什么挂在 {@code PositionCheck} 上</h2>
 *
 * <p>原版 {@code NaturalSpawner} 判定完 {@code SpawnPlacementType} 之后走
 * {@code EventHooks.checkSpawnPosition}，那里会发 {@link MobSpawnEvent.PositionCheck}；
 * 判 {@code FAIL} 就等于「这一下别刷」。比在 {@code FinalizeSpawn} 里把实体抹掉省一次实体构造，
 * 也比改群系刷怪表干净：表是按群系写的，而「露天」是随天气变的运行时状态
 *
 * <h2>只拦 {@link MobSpawnType#NATURAL}</h2>
 *
 * <p>刷怪蛋、{@code /summon}、刷怪笼是玩家与结构摆的，不该被天气卡住。
 * {@link MobSpawnType#CHUNK_GENERATION} 也不拦：那是世界生成那一路，生成那一刻的天气与落地之后世界的状态毫无关系，
 * 拿当时的雨把生物压掉纯属偶然，而且它跑在生成线程上——见下一条
 *
 * <h2>绝不能在这一路上碰活服的高度图</h2>
 *
 * <p>世界生成时 {@code NaturalSpawner.spawnMobsForChunkGeneration} 传进来的是
 * {@code WorldGenRegion}，它经 {@code WorldGenLevel} 也是 {@link ServerLevelAccessor}。
 * 曾经这里写成 {@code event.getLevel().getLevel()}，把生成期的安全视图丢掉、换成活的 {@code ServerLevel}
 * 再问高度图：生成线程因此调进 {@code ServerChunkCache#getChunk(..., load = true)}，
 * 与同样在等区块的主线程互等，整条区块生成管线卡死，表现为游戏失去响应、存档卡在「保存并退出」。
 * 现在几何判据只拿调用方给的 accessor，天气与维度另从 {@link ServerLevelAccessor#getLevel()} 读（那只是取维度，不碰区块），
 * 并且先用 {@link #suppresses} 里的 {@code hasChunk} 挡一道：不为了判定去加载任何区块
 *
 * <p>「弦」的飞行型不从这里出：它由 {@link StringSpawner} 自己扫列刷出，压根不走原版刷怪器，
 * 见 {@code StringSpawner#trySpawnFlyerAround}
 */
public final class ChronoRainSpawns {
    private ChronoRainSpawns() {}

    /**
     * 这一下生成要不要被天气压掉
     *
     * <p>拆成纯函数是为了能在游戏测试里直接断言「世界生成那一路永不压制」，
     * 不必构造事件、也不必真的开雨
     */
    public static boolean suppresses(ServerLevelAccessor accessor, MobSpawnType spawnType, BlockPos pos) {
        if (spawnType != MobSpawnType.NATURAL) {
            return false;
        }
        if (!ChronoRain.isRainy(accessor.getLevel())) {
            return false;
        }
        if (!accessor.hasChunk(pos.getX() >> 4, pos.getZ() >> 4)) {
            // 不为了判定去加载区块：没加载说明这一下也不归天气管
            return false;
        }
        return ChronoRain.isExposedGeometry(accessor, pos);
    }

    public static void onPositionCheck(MobSpawnEvent.PositionCheck event) {
        if (suppresses(event.getLevel(), event.getSpawnType(), event.getEntity().blockPosition())) {
            // Result 只有 SUCCEED / DEFAULT / FAIL：FAIL 就是「这一下不许生成」
            event.setResult(MobSpawnEvent.PositionCheck.Result.FAIL);
        }
    }
}
