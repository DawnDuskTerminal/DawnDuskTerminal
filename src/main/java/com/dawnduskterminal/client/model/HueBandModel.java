package com.dawnduskterminal.client.model;

import com.dawnduskterminal.DawnDuskTerminal;
import com.dawnduskterminal.world.ChronoPlantTints;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.ToIntBiFunction;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.model.BakedModelWrapper;
import net.neoforged.neoforge.client.model.data.ModelData;
import net.neoforged.neoforge.client.model.data.ModelProperty;
import org.jetbrains.annotations.Nullable;

/**
 * 「按坐标取色相带里的某一档」的模型：一张图纵向排了 N 条完整的色相变体，六面按坐标挑一条贴上
 *
 * <p>色相偏移不经过顶点色（那是乘法），而是烘进贴图里：{@code tools/generate_hue_sheets.py}
 * 把原图转成 25 条竖直排列的带，每一带只是把每个像素的 HSV 色相转过固定角度，明度 V 与饱和度 S 不动。
 * 模型这边的贴图指向那张带图，这个包装器负责把 UV 从「整张带图」缩到「其中一带」
 *
 * <p>用哪一档不存进方块状态，而是渲染时现算：档号来自 {@code bands}（方块自己给的纯函数，
 * 参数是坐标），它在 {@link #getModelData} 里算一次，再由 {@link #getQuads} 按档号平移 V。
 * 所以同一根柱子上下同色，活塞推走一块、{@code /setblock} 换坐标，色相也都跟着走
 *
 * <p>只有 {@code _hues} 那张图的面才重映射：模型里 {@code particle} 另指了原图（16×16 或 32×32 的单图），
 * 破坏粒子走的就是它，不会被压成带图里的一条
 *
 * <p>拿不到坐标时（物品图标、别模组借用这个模型）一律落到原色那一档，也就是贴图原样
 */
public class HueBandModel extends BakedModelWrapper<BakedModel> {
    /** 色相带按 {@link ChronoPlantTints#DIANTHUS_BANDS} 分档，与生成脚本一致 */
    private static final int BANDS = ChronoPlantTints.DIANTHUS_BANDS;

    /** 建模时用的那张单图名的后缀：带图的贴图名都以它收尾 */
    private static final String SHEET_SUFFIX = "_hues";

    /** 该坐标该用哪一档 */
    private static final ModelProperty<Integer> BAND = new ModelProperty<>();

    /** 带图形状对不上时只警告一次 */
    private static final AtomicBoolean SHEET_SHAPE_WARNED = new AtomicBoolean();

    // DefaultVertexFormat.BLOCK：每个顶点 8 个 int（3 位置 + 1 颜色 + 2 UV + 1 光照 + 1 法线）
    private static final int VERTEX_STRIDE = 8;
    private static final int U_OFFSET = 4;
    private static final int V_OFFSET = 5;

    /** 某个坐标该用哪一档，取值 {@code 0..BANDS-1} */
    private final ToIntBiFunction<Integer, Integer> bands;

    public HueBandModel(BakedModel original, ToIntBiFunction<Integer, Integer> bands) {
        super(original);
        this.bands = bands;
    }

    /**
     * 这一格该用哪一档
     *
     * <p>区块网格在每块方块上只问一次这里；{@link #getQuads} 那边按档号平移 V
     */
    @Override
    public ModelData getModelData(BlockAndTintGetter level, BlockPos pos, BlockState state, ModelData modelData) {
        return ModelData.of(BAND, this.bands.applyAsInt(pos.getX(), pos.getZ()));
    }

    /**
     * 没有 {@code ModelData} 的那条路兜底
     *
     * <p>{@code BakedModelWrapper} 的三参数 {@code getQuads} 直接把调用转给原模型，<b>不经过</b>子类覆写的
     * 五参数版本，所以只覆写五参数的话，任何不带方块上下文的调用（手里、物品栏）都会把整张带图糊上去
     */
    @Override
    public List<BakedQuad> getQuads(@Nullable BlockState state, @Nullable Direction side, RandomSource rand) {
        return getQuads(state, side, rand, ModelData.EMPTY, null);
    }

    /**
     * 按档号重映射 UV：V 从「整张带图」缩到 1/N 并平移到目标那一条
     *
     * <p>只用带图 {@link #SHEET_SUFFIX} 的那些面才重映射；{@code particle} 那张单图原样保留。
     * 拿不到档号时落到原色那一档
     */
    @Override
    public List<BakedQuad> getQuads(
            @Nullable BlockState state,
            @Nullable Direction side,
            RandomSource rand,
            ModelData data,
            @Nullable RenderType renderType) {
        List<BakedQuad> quads = super.getQuads(state, side, rand, data, renderType);
        if (quads.isEmpty()) {
            return quads;
        }

        Integer band = data.get(BAND);
        int index = band == null ? ChronoPlantTints.DIANTHUS_NEUTRAL_BAND : band;
        List<BakedQuad> out = new ArrayList<>(quads.size());
        for (BakedQuad quad : quads) {
            if (usesSheet(quad)) {
                checkSheetShape(quad);
                out.add(remap(quad, index));
            } else {
                out.add(quad);
            }
        }
        return out;
    }

    private static boolean usesSheet(BakedQuad quad) {
        return quad.getSprite().contents().name().getPath().endsWith(SHEET_SUFFIX);
    }

    /** 带图必须是「宽度不变、高度 = 宽度 × 档数」，与 Java 侧档数对不上时只警告一次，免得错切 */
    private static void checkSheetShape(BakedQuad quad) {
        if (SHEET_SHAPE_WARNED.get()) {
            return;
        }
        var contents = quad.getSprite().contents();
        int expected = contents.width() * BANDS;
        if (contents.height() != expected) {
            SHEET_SHAPE_WARNED.set(true);
            DawnDuskTerminal.LOGGER.warn(
                    "hue sheet {} is {}x{}, expected height {} ({} bands of {}); check tools/generate_hue_sheets.py"
                            + " against ChronoPlantTints.DIANTHUS_BANDS",
                    contents.name(),
                    contents.width(),
                    contents.height(),
                    expected,
                    BANDS,
                    contents.width());
        }
    }

    private static BakedQuad remap(BakedQuad quad, int band) {
        var sprite = quad.getSprite();
        float v0 = sprite.getV0();
        float v1 = sprite.getV1();
        float step = (v1 - v0) / BANDS;
        float bandV = v0 + band * step;

        int[] vertices = quad.getVertices().clone();
        for (int vertex = 0; vertex < 4; vertex++) {
            int offset = vertex * VERTEX_STRIDE;
            // 顶点 UV 铺满整张带图，缩到 1/N 再平移到目标那一条
            float v = Float.intBitsToFloat(vertices[offset + V_OFFSET]);
            vertices[offset + V_OFFSET] = Float.floatToRawIntBits(bandV + (v - v0) / BANDS);
        }
        return new BakedQuad(vertices, quad.getTintIndex(), quad.getDirection(), sprite, quad.isShade());
    }
}
