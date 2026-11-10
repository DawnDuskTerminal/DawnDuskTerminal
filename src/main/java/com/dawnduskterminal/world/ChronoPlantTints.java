package com.dawnduskterminal.world;

import com.dawnduskterminal.DawnDuskTerminal;
import java.util.Set;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import twilightforest.util.ColorUtil;

/**
 * 晨昏线维度的植物着色：草方块与树叶两套按群系的 tint，再加蓝色沙漠三种植物的按坐标蓝绿色相
 *
 * <p>群系那两套规则按群系 ID 的<b>路径 + 命名空间</b>判：只有 {@code dawnduskterminal:*} 下的副本会变，
 * 主世界那些同名群系（{@code minecraft:sparse_jungle}、{@code minecraft:river} …）一律原样交回
 *
 * <ul>
 *   <li>{@code sparse_jungle}：草与树叶都走彩虹着色，逐格变色</li>
 *   <li>河海类（{@code river}、{@code frozen_river} 与九个海洋）：草与树叶固定为 {@link #PURPLE}，
 *       取的是 {@code portal_fluid} 的浸没雾色</li>
 *   <li>{@code jungle}：树叶沿用暮色森林魔法森林的蓝色渐变，草不动（原有行为）</li>
 *   <li>{@code dark_forest}：树叶固定染成 {@code 0xE94E14}，草不动（原有行为）</li>
 *   <li>其余群系原样返回，交给群系自己的 {@code grass_color} / {@code foliage_color}</li>
 * </ul>
 *
 * <p>{@link #fairyDianthusBand} 是另一套：不吃群系，只看坐标。仙灵石竹两种方块与夜光灌木共用它，
 * 返回的是贴图里那条烘好的色相带（{@code tools/generate_hue_sheets.py}），不是乘数。
 *
 * <p>这里全是纯函数，客户端取色器（{@code client/ChronoFoliageColors}、{@code client/DdtClient}）只负责把
 * 群系换成 ID、把方块接到坐标上。计算放在公共包里是为了能在游戏测试里直接断言：客户端类在专用服上根本不加载
 */
public final class ChronoPlantTints {
    /** 河海类的植物着色：{@code portal_fluid} 的浸没雾色 {@code 0x7F159E}（127, 21, 158） */
    public static final int PURPLE = 0x7F159E;

    /** 黑森林腹地的树叶色，暮色森林 {@code darkForestCenterFoliage} 的常量结果 */
    public static final int DARK_FOREST_FOLIAGE = 0xE94E14;

    /**
     * 仙灵石竹蓝绿色相的噪声尺度，单位是格
     *
     * <p>比彩虹色那块 48 格密一档：彩虹是整片丛林一起变色，仙灵石竹一丛只有十几格宽，
     * 一丛里能看出两三种蓝色才算是「按坐标偏移」。整根柱子上下共用一个值（见 {@link #fairyDianthus}）
     */
    private static final double DIANTHUS_SCALE = 20.0D;

    /**
     * 色相带的档数
     *
     * <p>与 {@code tools/generate_hue_sheets.py} 的 {@code BANDS} 必须一致：那边把偏移量烘成
     * 25 条竖直排列的色相带，这边只负责按坐标挑其中一条。25 档 × 5° 刚好覆盖 −60°~+60°，
     * 相邻档差 5°，不会有看得出来的台阶
     */
    public static final int DIANTHUS_BANDS = 25;

    /** 每档的色相步长，单位度 */
    public static final float DIANTHUS_HUE_STEP = 5.0F;

    /** 偏移 0° 的那一档（正中青色，也就是底图原样） */
    public static final int DIANTHUS_NEUTRAL_BAND = DIANTHUS_BANDS / 2;

    private static final String SPARSE_JUNGLE = "sparse_jungle";
    private static final String JUNGLE = "jungle";
    private static final String DARK_FOREST = "dark_forest";

    /** 彩虹色最粗那一层色块的尺度，单位是格；再叠两层细节 */
    private static final double RAINBOW_SCALE = 48.0D;

    /** 色相放大倍数。色相是环形的，绕几圈都看不出接缝，所以放大不会留下缝 */
    private static final double RAINBOW_HUE_GAIN = 2.4D;

    /**
     * 三层细节之间的频率比，故意取非整数
     *
     * <p>整数倍（2.0）会让第二层、第三层的格点落在第一层的格点上，在轴上对齐成一张看得见的网；
     * 2.07 之后三层互不同相，整张图找不到可复现的格子
     */
    private static final double RAINBOW_OCTAVE_RATIO = 2.07D;

    /** 河海类：两条河与九个海洋。沙滩与石岸不算，它们不是「河海」 */
    private static final Set<String> RIVER_OR_OCEAN = Set.of(
            "river",
            "frozen_river",
            "ocean",
            "deep_ocean",
            "cold_ocean",
            "deep_cold_ocean",
            "lukewarm_ocean",
            "deep_lukewarm_ocean",
            "warm_ocean",
            "frozen_ocean",
            "deep_frozen_ocean");

    private ChronoPlantTints() {}

    /**
     * 这个群系是不是晨昏线自己的副本
     *
     * <p>规则必须同时看命名空间：只看路径的话，主世界的 {@code minecraft:sparse_jungle}、{@code minecraft:river}
     * 会被一起染上，等于把原版世界改了。副本都在 {@code dawnduskterminal} 命名空间下（见 {@code biomes.md}）
     */
    public static boolean isChrono(@Nullable ResourceLocation biome) {
        return biome != null && DawnDuskTerminal.MOD_ID.equals(biome.getNamespace());
    }

    /** 树叶色 */
    public static int foliage(@Nullable ResourceLocation biome, int x, int z, int original) {
        if (!isChrono(biome)) {
            return original;
        }
        String path = path(biome);
        if (SPARSE_JUNGLE.equals(path)) {
            return rainbow(x, z);
        }
        if (RIVER_OR_OCEAN.contains(path)) {
            return PURPLE;
        }
        if (JUNGLE.equals(path)) {
            return enchanted(original, x, z);
        }
        if (DARK_FOREST.equals(path)) {
            return DARK_FOREST_FOLIAGE;
        }
        return original;
    }

    /** 草方块色。稀疏丛林与河海类跟树叶同步，其余群系不动 */
    public static int grass(@Nullable ResourceLocation biome, int x, int z, int original) {
        if (!isChrono(biome)) {
            return original;
        }
        String path = path(biome);
        if (SPARSE_JUNGLE.equals(path)) {
            return rainbow(x, z);
        }
        if (RIVER_OR_OCEAN.contains(path)) {
            return PURPLE;
        }
        return original;
    }

    /** 河海类判定，日志与校验用 */
    public static boolean isRiverOrOcean(@Nullable ResourceLocation biome) {
        return isChrono(biome) && RIVER_OR_OCEAN.contains(path(biome));
    }

    /**
     * 彩虹色：世界坐标上的三层值噪声给色相，色相绕环
     *
     * <p>为什么不用暮色森林彩虹橡树叶那个公式：它是纯线性的三角折返，
     * {@code red = fold(x*32)}、{@code green = fold(z*16)}、{@code blue = fold(x*16 + z*32)}，
     * 三条的周期都是 512，折算到方块上就是 x 每 16 或 32 格、z 每 16 或 32 格重复一次——
     * 整张图是 32×32 的同一块贴图平铺，站在地图上一眼就能认出来
     *
     * <p>这里改成位置哈希驱动的值噪声：哈希与坐标之间没有任何线性关系，所以整张图没有可复现的周期；
     * 三层细节分别落在约 48、23、11 格上，频率比取 2.07 而不是 2.0，免得三层格点在轴上对齐又连成网。
     * 色相 {@code Mth.frac} 绕环，而色相本身是环形的，绕回 0 时不会出现接缝
     *
     * <p>颜色仍在暮色森林的 HSB 空间里取（{@code ColorUtil.hsvToRGB}），饱和度固定 0.9、明度 1.0，
     * 与彩虹叶那种满饱和的观感一致
     */
    public static int rainbow(int x, int z) {
        double hue = Mth.frac(fbm(x, z) * RAINBOW_HUE_GAIN + 0.05D);
        return ColorUtil.hsvToRGB((float) hue, 0.9F, 1.0F) & 0xFFFFFF;
    }

    /**
     * 蓝色沙漠三种植物的色相偏移：同一张图在不同坐标上偏蓝一点或偏绿一点
     *
     * <p>只吃 x、z，不吃 y：整根柱子（4~12 格）上下同一个色相，站在地上不会看到一节一节的分界。
     * 取值是坐标的纯函数（三层值噪声），区块网格重建、重进存档算出来的都是同一档
     *
     * <p><b>色相是转出来的，不是乘出来的</b>：偏移在贴图里烘好（{@code tools/generate_hue_sheets.py}），
     * 每个像素只换 HSV 的 h，明度 V 与饱和度 S 原样带回——所以一个通道到 255 之后会被固定住，
     * 色相继续转只能去压其余通道，亮度不掉。这里返回的就是该用哪一档
     *
     * <p>档位与色相：{@link #DIANTHUS_NEUTRAL_BAND} 是 0°（底图原样），两端各 60°，
     * 也就是色相 180° 的青色分别转到 120° 与 240°。取值范围是 0~{@link #DIANTHUS_BANDS}−1
     */
    public static int fairyDianthusBand(int x, int z) {
        double t = Mth.clamp(fbm(x, z, DIANTHUS_SCALE), 0.0D, 1.0D);
        return Mth.clamp((int) Math.round(t * (DIANTHUS_BANDS - 1)), 0, DIANTHUS_BANDS - 1);
    }

    /** 某一档的色相偏移量：低档偏蓝、高档偏绿，{@link #DIANTHUS_NEUTRAL_BAND} 是 0°，两端各 {@code 60°} */
    public static float fairyDianthusHueShift(int band) {
        return (DIANTHUS_NEUTRAL_BAND - Mth.clamp(band, 0, DIANTHUS_BANDS - 1)) * DIANTHUS_HUE_STEP;
    }

    /** 三层值噪声，按彩虹色那一档的尺度，返回 0~1 */
    private static double fbm(double x, double z) {
        return fbm(x, z, RAINBOW_SCALE);
    }

    /** 三层值噪声，返回 0~1；{@code scale} 是最粗那一层的格数 */
    private static double fbm(double x, double z, double scale) {
        double sum = 0.0D;
        double amplitude = 1.0D;
        double norm = 0.0D;
        double fx = x / scale;
        double fz = z / scale;
        for (int octave = 0; octave < 3; octave++) {
            sum += amplitude * sample(fx, fz);
            norm += amplitude;
            amplitude *= 0.5D;
            fx *= RAINBOW_OCTAVE_RATIO;
            fz *= RAINBOW_OCTAVE_RATIO;
        }
        return sum / norm;
    }

    /** 双线性插值的值噪声，输入是格为单位的坐标，返回 0~1 */
    private static double sample(double x, double z) {
        int x0 = Mth.floor(x);
        int z0 = Mth.floor(z);
        double fx = x - x0;
        double fz = z - z0;
        double u = fx * fx * (3.0D - 2.0D * fx);
        double v = fz * fz * (3.0D - 2.0D * fz);
        double n00 = hash(x0, z0);
        double n10 = hash(x0 + 1, z0);
        double n01 = hash(x0, z0 + 1);
        double n11 = hash(x0 + 1, z0 + 1);
        return Mth.lerp(v, Mth.lerp(u, n00, n10), Mth.lerp(u, n01, n11));
    }

    /** 整数坐标散列，返回 0~1。没有周期，也没有轴向条纹 */
    private static double hash(int x, int z) {
        long h = x * 0x9E3779B97F4A7C15L ^ z * 0xC2B2AE3D27D4EB4FL;
        h ^= h >>> 31;
        h *= 0xD6E8FEB86659FD93L;
        h ^= h >>> 29;
        return (h >>> 40) / (double) (1L << 24);
    }

    private static String path(@Nullable ResourceLocation biome) {
        return biome == null ? "" : biome.getPath();
    }

    /** 暮色森林的 {@code BiomeColorAlgorithms.enchanted}：按坐标在整个群系上做蓝色渐变 */
    private static int enchanted(int original, int x, int z) {
        BlockPos center = nearestCenterXZ(x >> 4, z >> 4);
        int cx = center.getX();
        int cz = center.getZ();
        int dist = (int) Mth.sqrt((cx - x) * (cx - x) + (cz - z) * (cz - z));
        int color = dist * 16;
        color %= 512;
        if (color > 255) {
            color = 511 - color;
        }
        color = 255 - color;
        return (original & 0xFFFF00) + color;
    }

    /** 暮色森林的 {@code LegacyLandmarkPlacements.getNearestCenterXZ}：确定性的、不依赖种子的栅格中心 */
    private static BlockPos nearestCenterXZ(int chunkX, int chunkZ) {
        int regionX = (chunkX + 8) >> 4;
        int regionZ = (chunkZ + 8) >> 4;
        long seed = regionX * 3129871L ^ regionZ * 116129781L;
        seed = seed * seed * 42317861L + seed * 7L;
        int num0 = (int) (seed >> 12 & 3L);
        int num1 = (int) (seed >> 15 & 3L);
        int num2 = (int) (seed >> 18 & 3L);
        int num3 = (int) (seed >> 21 & 3L);
        int centerX = 8 + num0 - num1;
        int centerZ = 8 + num2 - num3;
        int ccz = regionZ >= 0 ? (regionZ * 16 + centerZ - 8) * 16 + 8 : (regionZ * 16 + (16 - centerZ) - 8) * 16 + 9;
        int ccx = regionX >= 0 ? (regionX * 16 + centerX - 8) * 16 + 8 : (regionX * 16 + (16 - centerX) - 8) * 16 + 9;
        return new BlockPos(ccx, 0, ccz);
    }
}
