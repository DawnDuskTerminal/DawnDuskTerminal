package com.dawnduskterminal.world;

import com.dawnduskterminal.config.DdtConfig;
import net.minecraft.util.Mth;

/**
 * 晨昏线的天光
 *
 * <h2>两份「暗」</h2>
 *
 * <p>一份是<b>存进区块的天光</b>，由 {@link #maxSkyLight} 按坐标压到 12~15，光照引擎、渲染与作物生长都读它。
 * 另一份是<b>压暗量</b>，由 {@link #localSkyDarken} 按坐标给出，量纲与原版 {@code Level#skyDarken} 相同（0~11）。
 *
 * <h2>为什么刷怪判定要单独算一份</h2>
 *
 * <p>原版里这两份来自同一个来源：时刻决定压暗量，压暗量再去减天光。
 * 本维度的时刻由 {@code fixed_time} 锁在 12000，服务端 {@code Level#updateSkyBrightness} 因此算出晴天恒为 0，
 * 于是原版刷怪判定读到的是天光本身（12~15），夜侧在它眼里依旧通亮。
 * 玩家看到的夜侧之暗来自客户端按坐标重算的 {@code timeOfDay}（{@code ClientLevelTimeOfDayMixin}），
 * 而刷怪判定跑在服务端，读的是存进区块的那一份，两者对不上。
 *
 * <p>{@code MonsterSpawnLightMixin} 因此在原版判定的位置改用 {@link #localSkyDarken}，
 * 「玩家看到的暗」与「刷怪认定的暗」重新变成同一个量。天光上限不动，视觉与作物生长都不受影响。
 */
public final class ChronoSkyLight {
    private ChronoSkyLight() {}

    /**
     * 天光上限：这一格的天光最多到多少
     *
     * <p>昼侧 {@code day}、夜侧 {@code night}，两者之间按晨昏线参数线性过渡，晨昏线本身取 {@code center}
     */
    public static int maxSkyLight(ChronoLine line, int x, int z) {
        DdtConfig.SkyLightConfig config = DdtConfig.skyLightConfig();
        if (!config.byDistance()) {
            return 15;
        }
        double param = line.param(x, z);
        double center = config.center();
        double value =
                param >= 0.0D ? center + (config.day() - center) * param : center + (center - config.night()) * param;
        return Mth.clamp((int) Math.round(value), 0, 15);
    }

    /**
     * 该坐标在一天中的哪个时刻
     *
     * <p>与原版 {@code DimensionType#timeOfDay} 的取值域一致：0.25 为正午，0.0 与 0.5 为午夜。
     * 晨昏线参数为 0 时落在日出与日落，因此 {@code 0.25 - param * 太阳高度角 / 360} 正好让太阳仰角等于
     * {@code param * 太阳高度角}
     *
     * <p>客户端 {@code ClientLevelTimeOfDayMixin} 用的是同一条式子，两处必须一致，否则天色与刷怪又会分家
     */
    public static float timeOfDay(double param) {
        return 0.25F - (float) (param * DdtConfig.sunAngleMax() / 360.0D);
    }

    /**
     * 原版 {@code Level#updateSkyBrightness} 的公式：时刻与雨势给出压暗量
     *
     * <p>晴天正午为 0，晴天午夜为 11，数值越大越暗
     */
    public static int skyDarken(float timeOfDay, float rainLevel, float thunderLevel) {
        double rain = 1.0D - (double) (rainLevel * 5.0F) / 16.0D;
        double thunder = 1.0D - (double) (thunderLevel * 5.0F) / 16.0D;
        double curve = 0.5D + 2.0D * Mth.clamp((double) Mth.cos(timeOfDay * ((float) Math.PI * 2.0F)), -0.25D, 0.25D);
        return (int) ((1.0D - curve * rain * thunder) * 11.0D);
    }

    /**
     * 这一格该按多暗来判定
     *
     * <p>昼侧为 0，夜侧为 11，晨昏线上为 5（默认配置下）
     *
     * @param rainLevel 当前雨势，见 {@code Level#getRainLevel}
     * @param thunderLevel 当前雷势，见 {@code Level#getThunderLevel}
     */
    public static int localSkyDarken(ChronoLine line, int x, int z, float rainLevel, float thunderLevel) {
        return skyDarken(timeOfDay(line.param(x, z)), rainLevel, thunderLevel);
    }

    /**
     * 原版 {@code Monster#isDarkEnoughToSpawn} 的最后一步：天光减去压暗量之后够不够暗
     *
     * <p>方块光同样算进来，取两者的较大值。{@code sample} 是维度类型的
     * {@code monster_spawn_light_level} 抽样值，本维度为 0 到 7 的 uniform
     *
     * @param sky 存进区块的天光（0~15）
     * @param block 存进区块的方块光（0~15）
     * @param skyDarken 压暗量，天光要减掉它
     * @param sample {@code monster_spawn_light_level} 的抽样值
     */
    public static boolean lightAllowsSpawn(int sky, int block, int skyDarken, int sample) {
        return Math.max(block, sky - skyDarken) <= sample;
    }
}
