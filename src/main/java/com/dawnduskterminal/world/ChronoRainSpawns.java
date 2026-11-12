package com.dawnduskterminal.world;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.MobSpawnType;
import net.neoforged.neoforge.event.entity.living.MobSpawnEvent;

/**
 * 雨天压制露天生长的生物
 *
 * <h2>规则</h2>
 *
 * <p>晨昏线维度下雨时，{@code 露天} 的格子一律不许自然刷怪：判定用
 * {@link ChronoRain#isExposed(net.minecraft.world.level.Level, net.minecraft.core.BlockPos)}，
 * 与淋雨伤害、飞行型的刷出用的是同一套「算不算淋雨」，所以雨丝飘到哪里，压制就到哪里——
 * 洞穴、树冠下、屋里照常刷
 *
 * <h2>为什么挂在 {@code PositionCheck} 上</h2>
 *
 * <p>原版 {@code NaturalSpawner} 判定完 {@code SpawnPlacementType} 之后走
 * {@code EventHooks.checkSpawnPosition}，那里会发 {@link MobSpawnEvent.PositionCheck}；
 * 判 {@code FAIL} 就等于「这一下别刷」。比在 {@code FinalizeSpawn} 里把实体抹掉省一次实体构造，
 * 也比改群系刷怪表干净：表是按群系写的，而「露天」是随天气变的运行时状态
 *
 * <p>只拦 {@link MobSpawnType#NATURAL} 与 {@link MobSpawnType#CHUNK_GENERATION} 两条自然的路：
 * 刷怪蛋、{@code /summon}、刷怪笼是玩家与结构摆的，不该被天气卡住
 *
 * <p>「弦」的飞行型不从这里出：它由 {@link StringSpawner} 自己扫列刷出，压根不走原版刷怪器，
 * 见 {@code StringSpawner#trySpawnFlyerAround}
 */
public final class ChronoRainSpawns {
    private ChronoRainSpawns() {}

    public static void onPositionCheck(MobSpawnEvent.PositionCheck event) {
        MobSpawnType spawnType = event.getSpawnType();
        if (spawnType != MobSpawnType.NATURAL && spawnType != MobSpawnType.CHUNK_GENERATION) {
            return;
        }
        ServerLevel level = event.getLevel().getLevel();
        if (!ChronoRain.isRainy(level)) {
            return;
        }
        if (!ChronoRain.isExposed(level, event.getEntity().blockPosition())) {
            return;
        }
        // Result 只有 SUCCEED / DEFAULT / FAIL：FAIL 就是「这一下不许生成」
        event.setResult(MobSpawnEvent.PositionCheck.Result.FAIL);
    }
}
