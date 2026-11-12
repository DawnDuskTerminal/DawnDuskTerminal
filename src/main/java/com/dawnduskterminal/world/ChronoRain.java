package com.dawnduskterminal.world;

import com.dawnduskterminal.registry.ModDamageTypes;
import com.dawnduskterminal.registry.ModDimensions;
import com.dawnduskterminal.registry.ModEffects;
import com.dawnduskterminal.registry.ModSounds;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.EntityTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.FlyingMob;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

/**
 * 晨昏线维度的雨天：判定一条规则「算不算淋雨」，以及淋雨的那一下伤害
 *
 * <h2>什么算淋雨</h2>
 *
 * <p>三个条件同时成立才算：在这个维度里、这一层正在下雨（{@link Level#isRaining()}，随雨势渐入渐出）、
 * 这一格头顶是露天的、所在群系落的是雨（不是雪、也不是没有降水）。
 *
 * <p><b>不能直接用 {@link Level#isRainingAt(BlockPos)}</b>：它先查 {@code canSeeSky}，而 {@code canSeeSky}
 * 判的是「天光 ≥ 15」；这个维度的天光被 {@code LightEngineSkyLightMixin} 压到 12~15，露天处也够不到 15，
 * 于是那句判定在这里永远为假。露天改用 {@code MOTION_BLOCKING} 高度图（{@link #isOpenSky}），
 * 降水类型仍是原版的 {@code Biome#getPrecipitationAt}（{@link #rainsAt}）
 *
 * <h2>谁挨这一下</h2>
 *
 * <ul>
 *   <li><b>玩家一律挨</b>：滑翔、飞行、坐船都不豁免。玩家自己会看天气，豁免玩家等于给玩家开挂
 *   <li><b>会走地面的生物也挨</b>：按寻路方式认，{@link GroundPathNavigation} 的那一类才算。
 *       拿寻路方式而不是 {@code SpawnPlacementTypes.ON_GROUND} 当判据，是因为后者登记在实体类型上：
 *       恶魂、蝙蝠、蜜蜂都是 {@code ON_GROUND} 出生却常年飘着
 *   <li><b>飞行的与水生的不挨</b>：它们本来就能自己离开雨区，给它们写「躲雨」是另一套复杂的行为逻辑，
 *       这一版不做——所以它们也不吃这一下。水生用 {@code #minecraft:aquatic} 兜掉在岸上搁浅的鱼
 *   <li><b>带稳定效果的免疫</b>：{@link ModEffects#STABILITY} 就是传送门流体的那条稳定效果，
 *       有它的玩家与生物都不挨。这是唯一合法的豁免方式
 * </ul>
 *
 * <h2>伤害</h2>
 *
 * <p>每 {@value #DAMAGE_INTERVAL_TICKS} tick 一点，伤害类型取传送门流体的接触伤害
 * {@link ModDamageTypes#STABILITY}，与站在 {@code portal_fluid} 里挨的是同一类。伤害本身不产生击退：
 * 没有攻击者的伤害在 {@code LivingEntity#hurt} 里照样会走那段 {@code knockback(0.4, 0, 0)}，
 * 把竖直速度抬一下，所以结算完把速度原样放回去，见 {@link #hurtInRain(LivingEntity)}
 *
 * <p>「弦」的那几个变种对 {@code stability} 免疫（见 {@code StringEntity#isInvulnerableTo}），
 * 这里不必特判：原版会替我们挡掉
 */
public final class ChronoRain {
    /** 每次接触伤害的点数 */
    public static final float DAMAGE = 1.0F;

    /**
     * 两次伤害之间隔多少 tick
     *
     * <p>2 秒。与传送门流体那一条（40 tick 一次、2 点）同一个节拍，只是量减半：淋雨只是催促进屋，
     * 不是站在液体里那种惩罚
     */
    public static final int DAMAGE_INTERVAL_TICKS = 40;

    private ChronoRain() {}

    /** 这个维度此刻正在下雨 */
    public static boolean isRainy(Level level) {
        return level.dimension().equals(ModDimensions.CHRONO) && level.isRaining();
    }

    /**
     * 这一格头顶是不是露天的：看 {@code MOTION_BLOCKING} 高度图，<b>不看 {@code canSeeSky}</b>
     *
     * <p>不能用 {@code canSeeSky}，也不能用 {@link Level#isRainingAt(BlockPos)}：那两个判的是
     * 「天光亮度 ≥ 15」，而这个维度的天光被 {@code LightEngineSkyLightMixin} 按晨昏线压到 12~15
     * （默认中心 14、夜侧 12）。于是<b>露天处 {@code canSeeSky} 也是假</b>，拿它当判据等于让雨天永远不生效。
     * 高度图问的是「这一列最高的挡住移动的方块在哪」，与天光无关
     */
    public static boolean isOpenSky(Level level, BlockPos pos) {
        return level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING, pos).getY() <= pos.getY();
    }

    /** 这一格所在群系落的是雨：不是雪，也不是没有降水 */
    public static boolean rainsAt(Level level, BlockPos pos) {
        return level.getBiome(pos).value().getPrecipitationAt(pos) == Biome.Precipitation.RAIN;
    }

    /** 这一格是露天的、所在群系会下雨、并且此刻正在下雨 */
    public static boolean isExposed(Level level, BlockPos pos) {
        return isRainy(level) && isOpenSky(level, pos) && rainsAt(level, pos);
    }

    /** 这只生物整个人露在雨里 */
    public static boolean isExposed(LivingEntity entity) {
        return isExposed(entity.level(), entity.blockPosition());
    }

    /** 带稳定效果：传送门流体那条稳定效果，淋雨也免 */
    public static boolean isRainImmune(LivingEntity entity) {
        return entity.hasEffect(ModEffects.STABILITY);
    }

    /**
     * 这只生物是不是「在地面上走」的那一类（不含玩家）
     *
     * <p>玩家由 {@link #takesRainDamage} 直接放进来，因为玩家不参与「谁会躲雨」这套判断
     */
    public static boolean isWalkingMob(LivingEntity entity) {
        if (!(entity instanceof Mob mob)) {
            return false;
        }
        if (mob instanceof FlyingMob || mob.getType().is(EntityTypeTags.AQUATIC)) {
            return false;
        }
        return mob.getNavigation() instanceof GroundPathNavigation;
    }

    /**
     * 这一下是不是落在它身上：玩家一律算，飞行的水生的不算，带稳定效果的免疫
     *
     * <p>与「露不露天」分开写：躲雨那一侧要用「会不会走地面」，伤害这一侧还要额外管玩家与免疫
     */
    public static boolean takesRainDamage(LivingEntity entity) {
        if (isRainImmune(entity)) {
            return false;
        }
        if (entity instanceof Player) {
            return true;
        }
        return isWalkingMob(entity);
    }

    /** 这一 tick 该不该挨那一点雨滴伤害：会挨、并且此刻露天淋着雨 */
    public static boolean shouldHurt(LivingEntity entity) {
        return takesRainDamage(entity) && isExposed(entity);
    }

    /**
     * 淋雨的那一下：{@link ModDamageTypes#STABILITY} 接触伤害，1 点，不产生击退
     *
     * <p>没有攻击者的伤害来源在 {@code LivingEntity#hurt} 里仍然会走到那段
     * {@code knockback(0.4, 0, 0)}（水平方向是零向量，竖直方向被抬 0.4），所以先记下速度、
     * 结算完再放回去。这一下不会改动作 AI 的任何状态，{@code hurt} 自己负责受伤动画与音效
     *
     * <p>落地之后补一声 {@code dawnduskterminal:portal_hurt}：传送门流体的接触伤害本来就有这个声音
     * （见 {@code PortalBlock#entityInside}），淋雨是同一个伤害类型，也该有同一个反馈。
     * 音量与音高的随机范围照抄那边，只有真的掉血了才播
     *
     * @return 真的掉血了才返回 {@code true}；创造模式、已死、免疫 {@code stability} 的都返回 {@code false}
     */
    public static boolean hurtInRain(LivingEntity entity) {
        DamageSource source = entity.damageSources().source(ModDamageTypes.STABILITY);
        Vec3 motion = entity.getDeltaMovement();
        if (!entity.hurt(source, DAMAGE)) {
            return false;
        }
        entity.setDeltaMovement(motion);
        // 与站在 portal_fluid 里挨的那一下同一个反馈：传送门流体的接触伤害是有声音的，
        // 见 PortalBlock#entityInside；音量 1.0、音高 0.8~1.2 的随机也照抄
        entity.level()
                .playSound(
                        null,
                        entity.getX(),
                        entity.getY(),
                        entity.getZ(),
                        ModSounds.PORTAL_HURT.get(),
                        entity.getSoundSource(),
                        1.0F,
                        0.8F + entity.getRandom().nextFloat() * 0.4F);
        return true;
    }
}
