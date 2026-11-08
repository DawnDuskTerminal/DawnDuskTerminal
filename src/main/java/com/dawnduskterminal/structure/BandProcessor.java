package com.dawnduskterminal.structure;

import com.dawnduskterminal.registry.ModProcessors;
import com.mojang.serialization.MapCodec;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessorType;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

/**
 * 按世界 y 取舍方块，并负责“只落空气 / 只落实体方块”这两遍
 *
 * <p>件已经按 y 段拆开，摆不摆由格子的处境决定；这里只负责三类通用的过滤：
 * 按世界 y 裁出要清的那几行、把一件拆成「只落空气 / 只落实体方块」两遍、以及跳过结构空位。
 * 不能用件的包围盒裁——{@code PoolElementStructurePiece#place} 传给模板的是<b>区块</b>的包围盒，
 * 自己那件的盒子裁不掉方块。
 *
 * <p>结构空位（{@code minecraft:structure_void}）一律不落笔：原版只在拼图里认这个块，
 * 本模组直接往 {@code StructurePiecesBuilder} 里塞件，所以在这里补上同一条语义。
 *
 * <p>{@link Phase} 把一件拆成两遍落笔：整栋房子先只落空气把地形挖开，再只落实体方块。
 * 顺序反过来的话，后摆那件的空气会把前一件摆在框架线上的边框家具（树皮、栏杆、门）擦掉。
 */
public final class BandProcessor extends StructureProcessor {
    /** 运行时构造，不走序列化；给编解码器一个占位实例即可 */
    public static final MapCodec<BandProcessor> CODEC =
            MapCodec.unit(new BandProcessor(Integer.MIN_VALUE, Integer.MAX_VALUE, Phase.ALL));

    /** 这一遍只落哪一半 */
    public enum Phase {
        ALL,
        AIR,
        SOLID
    }

    private final int minY;
    private final int maxY;

    private final Phase phase;

    public BandProcessor(int minY, int maxY, Phase phase) {
        this.minY = minY;
        this.maxY = maxY;
        this.phase = phase;
    }

    @Override
    protected StructureProcessorType<?> getType() {
        return ModProcessors.BAND.get();
    }

    @Override
    @Nullable
    public StructureTemplate.StructureBlockInfo process(
            LevelReader level,
            BlockPos pos,
            BlockPos pivot,
            StructureTemplate.StructureBlockInfo raw,
            StructureTemplate.StructureBlockInfo current,
            StructurePlaceSettings settings,
            @Nullable StructureTemplate template) {
        if (raw.state().is(Blocks.STRUCTURE_VOID)) {
            return null;
        }
        int y = current.pos().getY();
        if (y < this.minY || y > this.maxY) {
            return null;
        }
        boolean air = raw.state().isAir();
        if ((this.phase == Phase.AIR && !air) || (this.phase == Phase.SOLID && air)) {
            return null;
        }
        return current;
    }
}
