package com.dawnduskterminal.registry;

import com.dawnduskterminal.DawnDuskTerminal;
import com.dawnduskterminal.structure.ChronoMineshaftPieces;
import com.dawnduskterminal.structure.ChronoMineshaftStructure;
import com.dawnduskterminal.structure.GridStructure;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.levelgen.structure.StructureType;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModStructures {
    public static final DeferredRegister<StructureType<?>> STRUCTURE_TYPES =
            DeferredRegister.create(Registries.STRUCTURE_TYPE, DawnDuskTerminal.MOD_ID);

    /** 矿井的四类件；存档读回来的件靠这四个类型认领自己 */
    public static final DeferredRegister<StructurePieceType> PIECE_TYPES =
            DeferredRegister.create(Registries.STRUCTURE_PIECE, DawnDuskTerminal.MOD_ID);

    public static final DeferredHolder<StructureType<?>, StructureType<?>> GRID =
            STRUCTURE_TYPES.register("grid", () -> (StructureType<GridStructure>) () -> GridStructure.CODEC);

    public static final DeferredHolder<StructureType<?>, StructureType<?>> MINESHAFT = STRUCTURE_TYPES.register(
            "mineshaft", () -> (StructureType<ChronoMineshaftStructure>) () -> ChronoMineshaftStructure.CODEC);

    public static final DeferredHolder<StructurePieceType, StructurePieceType> MINESHAFT_ROOM = PIECE_TYPES.register(
            "mineshaft_room", () -> (context, tag) -> new ChronoMineshaftPieces.MineShaftRoom(tag));

    public static final DeferredHolder<StructurePieceType, StructurePieceType> MINESHAFT_CORRIDOR =
            PIECE_TYPES.register(
                    "mineshaft_corridor", () -> (context, tag) -> new ChronoMineshaftPieces.MineShaftCorridor(tag));

    public static final DeferredHolder<StructurePieceType, StructurePieceType> MINESHAFT_CROSSING =
            PIECE_TYPES.register(
                    "mineshaft_crossing", () -> (context, tag) -> new ChronoMineshaftPieces.MineShaftCrossing(tag));

    public static final DeferredHolder<StructurePieceType, StructurePieceType> MINESHAFT_STAIRS = PIECE_TYPES.register(
            "mineshaft_stairs", () -> (context, tag) -> new ChronoMineshaftPieces.MineShaftStairs(tag));

    private ModStructures() {}

    public static void register(IEventBus bus) {
        STRUCTURE_TYPES.register(bus);
        PIECE_TYPES.register(bus);
    }
}
