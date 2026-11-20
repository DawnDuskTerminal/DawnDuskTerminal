package com.dawnduskterminal.client;

import com.dawnduskterminal.DawnDuskTerminal;
import com.dawnduskterminal.block.PortalWaterfallBlock;
import com.dawnduskterminal.fluid.WaterfallFluid;
import com.dawnduskterminal.registry.ModBlocks;
import com.dawnduskterminal.registry.ModDimensions;
import com.mojang.blaze3d.vertex.VertexConsumer;
import it.unimi.dsi.fastutil.longs.Long2LongMap;
import it.unimi.dsi.fastutil.longs.Long2LongMaps;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.util.Mth;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;
import net.neoforged.neoforge.client.event.AddSectionGeometryEvent;
import net.neoforged.neoforge.client.model.pipeline.VertexConsumerWrapper;

/** 在客户端区块网格内将瀑布视作连续流体，世界中仍然只有装饰锚点。 */
public final class PortalWaterfallMesh implements AddSectionGeometryEvent.AdditionalSectionRenderer {
    private static final ThreadLocal<RenderView> ACTIVE_VIEW = new ThreadLocal<>();
    private static final int FADE_BITS = 6;
    private static final int FADE_MASK = (1 << FADE_BITS) - 1;
    private static final long SCORCHING_FLAG = 1L << (2 * FADE_BITS);
    private static final long ABSENT = -1L;
    private static final net.minecraft.resources.ResourceLocation TEXTURE =
            DawnDuskTerminal.id("block/portal_fluid_fall");

    private final BlockPos origin;
    private final Long2LongMap cells;

    private PortalWaterfallMesh(BlockPos origin, Long2LongMap cells) {
        this.origin = origin;
        this.cells = cells;
    }

    public static void addSectionGeometry(AddSectionGeometryEvent event) {
        if (!(event.getLevel() instanceof ClientLevel level)
                || !level.dimension().equals(ModDimensions.CHRONO)) {
            return;
        }
        BlockPos origin = event.getSectionOrigin();
        int minX = origin.getX() - 1;
        int maxX = origin.getX() + 16;
        int minY = origin.getY() - 1;
        int maxY = origin.getY() + 16;
        int minZ = origin.getZ() - 1;
        int maxZ = origin.getZ() + 16;
        Long2LongMap cells = null;
        for (int cx = SectionPos.blockToSectionCoord(minX); cx <= SectionPos.blockToSectionCoord(maxX); cx++) {
            for (int cz = SectionPos.blockToSectionCoord(minZ); cz <= SectionPos.blockToSectionCoord(maxZ); cz++) {
                var chunk = level.getChunkSource().getChunk(cx, cz, false);
                if (chunk == null) {
                    continue;
                }
                for (BlockPos anchor : chunk.getBlockEntities().keySet()) {
                    if (anchor.getX() < minX
                            || anchor.getX() > maxX
                            || anchor.getZ() < minZ
                            || anchor.getZ() > maxZ
                            || anchor.getY() < minY
                            || anchor.getY() - PortalWaterfallBlock.MAX_LENGTH + 1 > maxY) {
                        continue;
                    }
                    BlockState state = chunk.getBlockState(anchor);
                    if (!state.is(ModBlocks.PORTAL_WATERFALL.get())) {
                        continue;
                    }
                    int length = state.getValue(PortalWaterfallBlock.LENGTH);
                    long fluidFlag =
                            state.getValue(PortalWaterfallBlock.FLUID) == WaterfallFluid.SCORCHING ? SCORCHING_FLAG : 0;
                    int bottom = Math.max(minY, anchor.getY() - length + 1);
                    int top = Math.min(maxY, anchor.getY());
                    if (bottom > top) {
                        continue;
                    }
                    if (cells == null) {
                        cells = new Long2LongOpenHashMap();
                        cells.defaultReturnValue(ABSENT);
                    }
                    for (int y = bottom; y <= top; y++) {
                        int depth = anchor.getY() - y;
                        cells.put(
                                BlockPos.asLong(anchor.getX(), y, anchor.getZ()),
                                fluidFlag | (depth << FADE_BITS) | length);
                    }
                }
            }
        }
        if (cells != null) {
            event.addRenderer(new PortalWaterfallMesh(origin.immutable(), cells));
        }
    }

    public static void dirtyIfChanged(ClientLevel level, BlockPos pos, BlockState oldState, BlockState newState) {
        int oldLength = length(oldState);
        int newLength = length(newState);
        if ((oldLength != newLength
                        || (oldLength != 0
                                && oldState.getValue(PortalWaterfallBlock.FLUID)
                                        != newState.getValue(PortalWaterfallBlock.FLUID)))
                && Minecraft.getInstance().level == level) {
            Minecraft.getInstance()
                    .levelRenderer
                    .setBlocksDirty(
                            pos.getX() - 1,
                            pos.getY() - Math.max(oldLength, newLength),
                            pos.getZ() - 1,
                            pos.getX() + 1,
                            pos.getY() + 1,
                            pos.getZ() + 1);
        }
    }

    private static int length(BlockState state) {
        return state.is(ModBlocks.PORTAL_WATERFALL.get()) ? state.getValue(PortalWaterfallBlock.LENGTH) : 0;
    }

    public static RenderView activeView() {
        return ACTIVE_VIEW.get();
    }

    public static void setActiveView(RenderView view) {
        if (view == null) {
            ACTIVE_VIEW.remove();
        } else {
            ACTIVE_VIEW.set(view);
        }
    }

    public RenderView view(BlockAndTintGetter region) {
        return new RenderView(region, cells);
    }

    public static TextureAtlasSprite[] waterfallSprites(TextureAtlasSprite[] original) {
        var sprites = original.clone();
        sprites[1] = Minecraft.getInstance()
                .getTextureAtlas(InventoryMenu.BLOCK_ATLAS)
                .apply(TEXTURE);
        return sprites;
    }

    @Override
    public void render(AddSectionGeometryEvent.SectionRenderingContext context) {
        RenderView view = activeView();
        if (view == null) {
            view = view(context.getRegion());
        }
        var renderer = Minecraft.getInstance().getBlockRenderer();
        VertexConsumer buffer = null;
        for (var entry : Long2LongMaps.fastIterable(cells)) {
            BlockPos pos = BlockPos.of(entry.getLongKey());
            if (SectionPos.blockToSectionCoord(pos.getX()) != SectionPos.blockToSectionCoord(origin.getX())
                    || SectionPos.blockToSectionCoord(pos.getY()) != SectionPos.blockToSectionCoord(origin.getY())
                    || SectionPos.blockToSectionCoord(pos.getZ()) != SectionPos.blockToSectionCoord(origin.getZ())
                    || !view.canRenderAt(pos)) {
                continue;
            }
            long cell = entry.getLongValue();
            BlockState falling = view.falling(cell);
            FluidState fluid = falling.getFluidState();
            if (buffer == null) {
                buffer = context.getOrCreateChunkBuffer(ItemBlockRenderTypes.getRenderLayer(fluid));
            }
            var fading = new FadeVertices(
                    buffer, pos.getY() & 15, (int) (cell >> FADE_BITS & FADE_MASK), (int) (cell & FADE_MASK));
            renderer.renderLiquid(pos, view, fading, falling, fluid);
        }
    }

    public static final class RenderView implements BlockAndTintGetter {
        private final BlockAndTintGetter region;
        private final Long2LongMap cells;
        private final BlockState portal =
                WaterfallFluid.PORTAL.flowing().getFlowing(8, true).createLegacyBlock();
        private final BlockState scorching =
                WaterfallFluid.SCORCHING.flowing().getFlowing(8, true).createLegacyBlock();

        private RenderView(BlockAndTintGetter region, Long2LongMap cells) {
            this.region = region;
            this.cells = cells;
        }

        public boolean usesPortalWaterfallSprite(BlockPos pos) {
            long cell = cells.get(pos.asLong());
            return cell != ABSENT && (cell & SCORCHING_FLAG) == 0 && canRenderAt(pos);
        }

        private BlockState falling(long cell) {
            return (cell & SCORCHING_FLAG) == 0 ? portal : scorching;
        }

        private boolean canRenderAt(BlockPos pos) {
            BlockState real = region.getBlockState(pos);
            return real.isAir() || real.is(ModBlocks.PORTAL_WATERFALL.get());
        }

        @Override
        public BlockState getBlockState(BlockPos pos) {
            long cell = cells.get(pos.asLong());
            return cell != ABSENT && canRenderAt(pos) ? falling(cell) : region.getBlockState(pos);
        }

        @Override
        public FluidState getFluidState(BlockPos pos) {
            return getBlockState(pos).getFluidState();
        }

        @Override
        public BlockEntity getBlockEntity(BlockPos pos) {
            return region.getBlockEntity(pos);
        }

        @Override
        public float getShade(Direction direction, boolean shade) {
            return region.getShade(direction, shade);
        }

        @Override
        public float getShade(float x, float y, float z, boolean shade) {
            return region.getShade(x, y, z, shade);
        }

        @Override
        public LevelLightEngine getLightEngine() {
            return region.getLightEngine();
        }

        @Override
        public int getBlockTint(BlockPos pos, ColorResolver resolver) {
            return region.getBlockTint(pos, resolver);
        }

        @Override
        public int getHeight() {
            return region.getHeight();
        }

        @Override
        public int getMinBuildHeight() {
            return region.getMinBuildHeight();
        }
    }

    private static final class FadeVertices extends VertexConsumerWrapper {
        private final int baseY;
        private final int depth;
        private final int length;
        private float opacity;

        private FadeVertices(VertexConsumer parent, int baseY, int depth, int length) {
            super(parent);
            this.baseY = baseY;
            this.depth = depth;
            this.length = length;
        }

        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            float t = Mth.clamp(1 - (depth + 1 - (y - baseY)) / length, 0, 1);
            opacity = t * t;
            return super.addVertex(x, y, z);
        }

        @Override
        public VertexConsumer setColor(int red, int green, int blue, int alpha) {
            return super.setColor(red, green, blue, Math.round(alpha * opacity));
        }
    }
}
