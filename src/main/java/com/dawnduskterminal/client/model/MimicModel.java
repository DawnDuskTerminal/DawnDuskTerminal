package com.dawnduskterminal.client.model;

import com.dawnduskterminal.block.MimicBlock;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
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

public final class MimicModel extends BakedModelWrapper<BakedModel> {
    private static final ModelProperty<TextureAtlasSprite> ORE_SPRITE = new ModelProperty<>();
    private static final int VERTEX_STRIDE = DefaultVertexFormat.BLOCK.getVertexSize() / Integer.BYTES;
    private static final int U_OFFSET = 4;
    private static final int V_OFFSET = 5;

    public MimicModel(BakedModel original) {
        super(original);
    }

    @Override
    public ModelData getModelData(BlockAndTintGetter level, BlockPos pos, BlockState state, ModelData data) {
        var ore = MimicBlock.disguise(level, state, pos).defaultBlockState();
        TextureAtlasSprite sprite =
                Minecraft.getInstance().getBlockRenderer().getBlockModel(ore).getParticleIcon(ModelData.EMPTY);
        return data.derive().with(ORE_SPRITE, sprite).build();
    }

    @Override
    public TextureAtlasSprite getParticleIcon(ModelData data) {
        TextureAtlasSprite sprite = data.get(ORE_SPRITE);
        return sprite == null ? super.getParticleIcon(data) : sprite;
    }

    @Override
    public List<BakedQuad> getQuads(
            @Nullable BlockState state,
            @Nullable Direction side,
            RandomSource random,
            ModelData data,
            @Nullable RenderType renderType) {
        List<BakedQuad> quads = super.getQuads(state, side, random, data, renderType);
        TextureAtlasSprite sprite = data.get(ORE_SPRITE);
        if (quads.isEmpty() || sprite == null) {
            return quads;
        }
        List<BakedQuad> result = new ArrayList<>(quads.size());
        for (BakedQuad quad : quads) {
            TextureAtlasSprite source = quad.getSprite();
            int[] vertices = quad.getVertices().clone();
            for (int offset = 0; offset < vertices.length; offset += VERTEX_STRIDE) {
                vertices[offset + U_OFFSET] = remap(
                        vertices[offset + U_OFFSET], source.getU0(), source.getU1(), sprite.getU0(), sprite.getU1());
                vertices[offset + V_OFFSET] = remap(
                        vertices[offset + V_OFFSET], source.getV0(), source.getV1(), sprite.getV0(), sprite.getV1());
            }
            result.add(new BakedQuad(
                    vertices,
                    quad.getTintIndex(),
                    quad.getDirection(),
                    sprite,
                    quad.isShade(),
                    quad.hasAmbientOcclusion()));
        }
        return result;
    }

    private static int remap(int bits, float oldMin, float oldMax, float newMin, float newMax) {
        float fraction = (Float.intBitsToFloat(bits) - oldMin) / (oldMax - oldMin);
        return Float.floatToRawIntBits(newMin + fraction * (newMax - newMin));
    }
}
