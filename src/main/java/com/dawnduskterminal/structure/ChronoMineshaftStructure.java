package com.dawnduskterminal.structure;

import com.dawnduskterminal.registry.ModStructures;
import com.mojang.datafixers.util.Either;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureType;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePiecesBuilder;

/**
 * 晨昏线自己的废弃矿井
 *
 * <p>复制自 1.21.1 的 {@code MineshaftStructure}，不做拼图、不摆件模板，件由
 * {@link ChronoMineshaftPieces} 现场长出来。与原版的三处差别：
 * <ul>
 *   <li>木材套组由中心房间那一列的地表群系决定（{@link ChronoMineshaftWoods#atSurface}），原版只有
 *       {@code mineshaft_type} 那两个值</li>
 *   <li>整条矿井被压进 y {@link #BAND_MIN_Y} 到 {@link #BAND_MAX_Y}：设定里 y<0 是地下浮岛区，矿井不该
 *       顶到海平面。竖向放不下时优先保住顶部（顶部对齐 {@link #BAND_MAX_Y}），因为过长的矿井只要顶部
 *       不越界就仍在地下的空腔里</li>
 *   <li>JSON 里没有 {@code mineshaft_type} 字段，一个结构 id 走遍全维度</li>
 * </ul>
 *
 * <p>件的战利品表换成 {@code dawnduskterminal:chests/chrono_mineshaft}，见
 * {@link ChronoMineshaftPieces#LOOT}。
 */
public class ChronoMineshaftStructure extends Structure {
    public static final MapCodec<ChronoMineshaftStructure> CODEC = RecordCodecBuilder.mapCodec(
            instance -> instance.group(settingsCodec(instance)).apply(instance, ChronoMineshaftStructure::new));

    /** 矿井允许占的竖向带 */
    public static final int BAND_MIN_Y = -64;

    public static final int BAND_MAX_Y = -10;

    public ChronoMineshaftStructure(Structure.StructureSettings settings) {
        super(settings);
    }

    @Override
    public Optional<Structure.GenerationStub> findGenerationPoint(Structure.GenerationContext context) {
        context.random().nextDouble();
        ChunkPos chunk = context.chunkPos();
        BlockPos origin =
                new BlockPos(chunk.getMiddleBlockX(), ChronoMineshaftPieces.MAGIC_START_Y, chunk.getMinBlockZ());
        StructurePiecesBuilder builder = new StructurePiecesBuilder();
        int offset = this.generatePiecesAndAdjust(builder, context);
        return Optional.of(new Structure.GenerationStub(origin.offset(0, offset, 0), Either.right(builder)));
    }

    /** 造完整条矿井再整体平移到竖向带里，返回平移量（原版用它把件对齐到海平面以下） */
    private int generatePiecesAndAdjust(StructurePiecesBuilder builder, Structure.GenerationContext context) {
        ChunkPos chunk = context.chunkPos();
        WorldgenRandom random = context.random();
        ChronoMineshaftWoods woods = ChronoMineshaftWoods.atSurface(context);
        ChronoMineshaftPieces.MineShaftRoom room =
                new ChronoMineshaftPieces.MineShaftRoom(0, random, chunk.getBlockX(2), chunk.getBlockZ(2), woods);
        builder.addPiece(room);
        room.addChildren(room, builder, random);

        int boxMin = builder.getBoundingBox().minY();
        int span = builder.getBoundingBox().maxY() - boxMin + 1;
        int slack = BAND_MAX_Y - BAND_MIN_Y + 1 - span;
        // slack 大于 1 才有真正的选择余地；等于 1 时上下都不能动
        int targetMin = slack > 1 ? BAND_MIN_Y + random.nextInt(slack) : Math.min(BAND_MIN_Y, BAND_MAX_Y - span + 1);
        int delta = targetMin - boxMin;
        builder.offsetPiecesVertically(delta);
        return delta;
    }

    @Override
    public StructureType<?> type() {
        return ModStructures.MINESHAFT.get();
    }
}
