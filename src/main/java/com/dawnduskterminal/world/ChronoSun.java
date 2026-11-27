package com.dawnduskterminal.world;

import com.dawnduskterminal.config.DdtConfig;
import net.minecraft.world.phys.Vec3;

/**
 * 晨昏线维度里太阳与月亮的方向
 *
 * <h2>太阳方向是怎么来的</h2>
 *
 * <p>原版把太阳四边形画在 {@code y = +100} 的平面上，再对 pose 依次做
 * {@code rotY(-90)} 与 {@code rotX(360 × timeOfDay)}（{@code LevelRenderer#renderSky}），
 * 于是太阳方向是 {@code (-sin 360t, cos 360t, 0)}，仰角 {@code 90 - 360t}。
 *
 * <p>{@code ClientLevelTimeOfDayMixin} 把这个维度里的 {@code timeOfDay} 换成
 * {@code t = 0.25 - param × sun_angle_max / 360}，代进去仰角正好是 {@code param × sun_angle_max}；
 * {@code LevelRendererSkyAlignMixin} 再做一次绕 Y 的 {@code yaw = atan2(n_z, -n_x)}，把方位从
 * {@code (-1, 0)} 转到晨昏线法向。两步合起来，太阳方向的水平分量就是法向乘上仰角的余弦：
 *
 * <pre>d = (n_x cos a, sin a, n_z cos a)，a = param(x, z) × sun_angle_max</pre>
 *
 * <p>也就是说方位角在同一个世界里恒定（就是法向），只有仰角随坐标变。
 * 太阳在时间上不动，所以「朝向太阳」是坐标的纯函数，不需要每刻求解。
 *
 * <p>月亮在太阳的正对面：原版太阳画在 {@code +100}、月亮画在 {@code -100}，共用同一个 pose。
 * 夜侧（{@code param < 0}）太阳仰角为负，月亮的仰角就是 {@code -a > 0}，始终在地平线以上。
 */
public final class ChronoSun {
    /**
     * 花盘相对太阳或月亮再抬高的角度
     *
     * <p>太阳的仰角只有 {@code sun_angle_max}（默认 20 度），照原样贴上去，花盘几乎是平放的。
     * 向日葵的花盘本来就是抬头的，所以再抬高这 13.25 度。
     */
    public static final double HEAD_TILT_DEGREES = 13.25D;

    private ChronoSun() {}

    /** 太阳方向的单位向量；{@code param} 为负时落在法向的反方向、仰角为负 */
    public static Vec3 sunDirection(ChronoLine line, double x, double z) {
        double radians = Math.toRadians(line.param(x, z) * DdtConfig.sunAngleMax());
        double horizontal = Math.cos(radians);
        return new Vec3(line.normalX() * horizontal, Math.sin(radians), line.normalZ() * horizontal);
    }

    /** 月亮方向：太阳的正对面 */
    public static Vec3 moonDirection(ChronoLine line, double x, double z) {
        return sunDirection(line, x, z).scale(-1.0D);
    }

    /**
     * 这一格在夜侧吗
     *
     * <p>与 {@link ChronoSkyLight#maxSkyLight} 的昼/夜划分同一个判据：晨昏线参数小于零即夜侧。
     */
    public static boolean isNight(ChronoLine line, double x, double z) {
        return line.param(x, z) < 0.0D;
    }

    /**
     * 花盘该朝哪边
     *
     * <p>昼侧朝太阳（方位是法向）、夜侧朝月亮（方位是法向的反方向），仰角取绝对值再抬高
     * {@link #HEAD_TILT_DEGREES}。两侧的仰角因此在同一高度上，花盘不会一头朝地面。
     */
    public static Vec3 headDirection(ChronoLine line, double x, double z) {
        double param = line.param(x, z);
        double radians = Math.toRadians(Math.abs(param) * DdtConfig.sunAngleMax() + HEAD_TILT_DEGREES);
        double horizontal = Math.cos(radians);
        double sign = param < 0.0D ? -1.0D : 1.0D;
        return new Vec3(sign * line.normalX() * horizontal, Math.sin(radians), sign * line.normalZ() * horizontal);
    }
}
