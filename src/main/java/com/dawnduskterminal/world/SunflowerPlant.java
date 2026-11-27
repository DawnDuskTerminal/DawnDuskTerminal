package com.dawnduskterminal.world;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;

/**
 * 向日葵的随机全部由株底坐标推出来
 *
 * <h2>整株偏移照原版</h2>
 *
 * <p>原版的 {@code offsetType(XZ)}（{@code BlockBehaviour.Properties#offsetType}）是这样算的：
 *
 * <pre>
 * i  = Mth.getSeed(pos.getX(), 0, pos.getZ())          // y 固定传 0
 * f  = getMaxHorizontalOffset()                        // 默认 0.25
 * d0 = clamp(((i &amp; 15) / 15 - 0.5) * 0.5, -f, f)
 * d1 = clamp(((i &gt;&gt; 8 &amp; 15) / 15 - 0.5) * 0.5, -f, f)
 * </pre>
 *
 * <p>y 置 0 就是原版双层植物上下两半对齐的原因，也正是「整株共用一个偏移」。
 * 结果落在 {@code ±0.25} 格（±4 像素）上，横纵各 16 档，步长约 0.53 像素。
 * 偏移只作用于几何，不作用于碰撞箱与瞄准框。
 *
 * <h2>叶片模板</h2>
 *
 * <p>每片叶的参数来自 {@code assets/dawnduskterminal/sunflower/leaves.json} 的模板，
 * 按 {@code (株底坐标, 段号)} 抽一组，再逐片叠一点微扰。
 *
 * <p>读不到模板时退回 {@link #fallback()}。
 */
public final class SunflowerPlant {
    /** 原版 {@code getMaxHorizontalOffset} 的默认值，单位是格 */
    public static final double MAX_HORIZONTAL_OFFSET = 0.25D;

    /** 一片叶：偏航角、法线偏离竖直的倾角、缩放、段内高度（像素） */
    public record Leaf(float yaw, float pitch, float scale, float y) {}

    /** 一组叶片模板 */
    public record Template(List<Leaf> leaves) {}

    /** 模板文件读不到时的兜底，与 leaves.json 的前四组一致 */
    private static final List<Template> FALLBACK = List.of(
            new Template(List.of(new Leaf(20.0F, 26.0F, 1.0F, 7.0F))),
            new Template(List.of(new Leaf(200.0F, 32.0F, 0.85F, 10.0F))),
            new Template(List.of(new Leaf(10.0F, 22.0F, 1.1F, 5.0F), new Leaf(190.0F, 30.0F, 0.9F, 11.0F))),
            new Template(List.of(new Leaf(95.0F, 28.0F, 1.05F, 8.0F), new Leaf(275.0F, 20.0F, 1.0F, 4.0F))));

    private SunflowerPlant() {}

    public static List<Template> fallback() {
        return FALLBACK;
    }

    /**
     * 株底坐标的种子
     *
     * <p>就是原版 {@code Mth.getSeed(x, 0, z)} 那一套，这里自己写一遍是因为那个方法带
     * {@code @Deprecated}。y 不参与，所以一根柱子的每一段拿到同一个种子。
     */
    public static long seed(BlockPos base) {
        long value = (long) (base.getX() * 3129871) ^ (long) base.getZ() * 116129781L;
        value = value * value * 42317861L + value * 11L;
        return value >> 16;
    }

    /** 整株在 X 方向的偏移，单位是格 */
    public static double offsetX(long seed) {
        return Mth.clamp(
                ((double) ((float) (seed & 15L) / 15.0F) - 0.5D) * 0.5D, -MAX_HORIZONTAL_OFFSET, MAX_HORIZONTAL_OFFSET);
    }

    /** 整株在 Z 方向的偏移，单位是格 */
    public static double offsetZ(long seed) {
        return Mth.clamp(
                ((double) ((float) (seed >> 8 & 15L) / 15.0F) - 0.5D) * 0.5D,
                -MAX_HORIZONTAL_OFFSET,
                MAX_HORIZONTAL_OFFSET);
    }

    /** 这一段的叶片模板：{@code templates} 为空时退回兜底 */
    public static Template template(long seed, int segment, List<Template> templates) {
        if (templates.isEmpty()) {
            return FALLBACK.get(Math.floorMod(mix(seed, segment, 0, 0), FALLBACK.size()));
        }
        return templates.get((int) Math.floorMod(mix(seed, segment, 0, 0), templates.size()));
    }

    /** 第 {@code leaf} 片叶在第 {@code channel} 个参数上的微扰，取值 {@code [-1, 1]} */
    public static float jitter(long seed, int segment, int leaf, int channel) {
        return unit(mix(seed, segment, leaf, channel)) * 2.0F - 1.0F;
    }

    /** 花盘绕自己视线轴的自转角，±12 度 */
    public static float headRoll(long seed) {
        return jitter(seed, -1, 0, 0) * 12.0F;
    }

    /** 花盘的俯仰偏置，±3 度，让花盘不完全互相平行 */
    public static float headPitchBias(long seed) {
        return jitter(seed, -2, 0, 0) * 3.0F;
    }

    /** 把三段参数搅进 64 位，同一个种子永远同一结果 */
    private static long mix(long seed, int segment, int leaf, int channel) {
        long value = seed
                ^ 0x9E3779B97F4A7C15L * (segment + 1L)
                ^ 0xC2B2AE3D27D4EB4FL * (leaf + 1L)
                ^ 0x165667B19E3779F9L * (channel + 1L);
        value = (value ^ (value >>> 30)) * 0xBF58476D1CE4E5B9L;
        value = (value ^ (value >>> 27)) * 0x94D049BB133111EBL;
        return value ^ (value >>> 31);
    }

    /** 64 位哈希取 {@code [0, 1)} */
    private static float unit(long hash) {
        return (float) ((hash >>> 11) * 0x1.0p-53);
    }
}
