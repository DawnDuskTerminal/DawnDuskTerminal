package com.dawnduskterminal.mixin;

import com.dawnduskterminal.registry.ModDimensions;
import com.dawnduskterminal.world.ChronoLine;
import com.dawnduskterminal.world.ChronoLineState;
import com.dawnduskterminal.world.ChronoSkyLight;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.dimension.DimensionType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 晨昏线的刷怪判定要按坐标算压暗量
 *
 * <h2>原版这一步错在哪里</h2>
 *
 * <p>原版 {@code Monster#isDarkEnoughToSpawn} 的三步是：天光不得高于一个 0 到 31 的随机数、
 * 方块光不得超过维度类型的上限、天光减去服务端的压暗量之后不得高于 0 到 7 的抽样值。
 * 第三步读的 {@code Level#getMaxLocalRawBrightness} 用的是<b>全维度一个</b>压暗量，
 * 而本维度的时刻被 {@code fixed_time} 锁在 12000，服务端算出来晴天恒为 0，
 * 于是判定看到的就是天光本身。
 *
 * <p>天光上限默认昼侧 15、晨昏线 14、夜侧 12，三者都高于第三步的上限 7，
 * 结果就是这个维度<b>露天的地表一只敌对生物也刷不出来</b>，只有洞穴与屋檐下（天光降到 7 以下）才会刷。
 * 夜侧在玩家眼里是暗的，那是客户端按坐标重算 {@code timeOfDay} 的效果，服务端并不知道。
 *
 * <h2>改法</h2>
 *
 * <p>只在晨昏线维度里接管：前两步照抄原版，第三步把压暗量换成 {@link ChronoSkyLight#localSkyDarken}，
 * 即与客户端天色同一条公式、按落点坐标取值的量。天光上限不动，视觉、作物生长与动物判定都不受影响。
 *
 * <p>所有吃光线的敌对生物都收在这一步之下：原版与暮色森林的怪物走
 * {@code Monster#checkMonsterSpawnRules}，冬狼与砷铅铁恶魂幼体直接调用本方法，
 * 因此不必逐个实体类型改落点判定。和平难度、刷怪蛋、刷怪笼与 {@code /summon} 不经过本方法。
 */
@Mixin(Monster.class)
public abstract class MonsterSpawnLightMixin {
    @Inject(method = "isDarkEnoughToSpawn", at = @At("HEAD"), cancellable = true)
    private static void ddt$chronoDarkEnoughToSpawn(
            ServerLevelAccessor accessor, BlockPos pos, RandomSource random, CallbackInfoReturnable<Boolean> cir) {
        ServerLevel level = accessor.getLevel();
        if (!level.dimension().equals(ModDimensions.CHRONO)) {
            return;
        }
        // 第一步：天光不得高于 0 到 31 的随机数
        int sky = accessor.getBrightness(LightLayer.SKY, pos);
        if (sky > random.nextInt(32)) {
            cir.setReturnValue(false);
            return;
        }
        // 第二步：方块光不得超过维度类型给的限（本维度为 0，与原版主世界一致）
        DimensionType type = accessor.dimensionType();
        int block = accessor.getBrightness(LightLayer.BLOCK, pos);
        int blockLimit = type.monsterSpawnBlockLightLimit();
        if (blockLimit < 15 && block > blockLimit) {
            cir.setReturnValue(false);
            return;
        }
        // 第三步：压暗量按落点坐标取，雷雨照原版再加 10
        ChronoLine line = ChronoLineState.serverLine();
        int skyDarken = ChronoSkyLight.localSkyDarken(
                line, pos.getX(), pos.getZ(), level.getRainLevel(1.0F), level.getThunderLevel(1.0F));
        if (level.isThundering()) {
            skyDarken = Math.max(skyDarken, 10);
        }
        cir.setReturnValue(ChronoSkyLight.lightAllowsSpawn(
                sky, block, skyDarken, type.monsterSpawnLightTest().sample(random)));
    }
}
