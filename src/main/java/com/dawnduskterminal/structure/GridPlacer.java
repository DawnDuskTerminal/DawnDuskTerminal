package com.dawnduskterminal.structure;

import com.dawnduskterminal.DawnDuskTerminal;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.entity.StructureBlockEntity;
import net.minecraft.world.level.block.state.properties.StructureMode;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;

/**
 * 结构相关的开发命令：摆出全部件的目录，以及把结构里每一件的包围盒打出来
 *
 * <p>目录供手工改件用（见 {@link #placeCatalogue}），世界生成走 {@code GridStructure}。
 */
public final class GridPlacer {
    private GridPlacer() {}

    /**
     * 把玩家所在结构里每一件的包围盒打出来
     *
     * <p>排查拼接问题时用：2 格厚的盒就是墙，1 格厚的是转角柱，方形的是地板框架与天花。
     */
    public static String dump(ServerLevel level, BlockPos pos) {
        Structure structure =
                level.registryAccess().registryOrThrow(Registries.STRUCTURE).get(DawnDuskTerminal.id("grid_rooms"));
        if (structure == null) {
            return "no grid_rooms structure registered";
        }
        // 结构可以铺到 90 格开外，getStructureAt 只查玩家所在那一个区块，所以按区块半径扫一遍
        StructureStart containing = StructureStart.INVALID_START;
        StructureStart fallback = StructureStart.INVALID_START;
        ChunkPos centre = new ChunkPos(pos);
        // 结构在地下（y 可以低到 -63），section y 写死 0 会永远查不到
        int sectionY = SectionPos.blockToSectionCoord(pos.getY());
        scan:
        for (int dx = -6; dx <= 6; dx++) {
            for (int dz = -6; dz <= 6; dz++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (StructureStart candidate : level.structureManager()
                            .startsForStructure(
                                    SectionPos.of(centre.x + dx, sectionY + dy, centre.z + dz), structure)) {
                        if (!fallback.isValid()) {
                            fallback = candidate;
                        }
                        if (candidate.getBoundingBox().isInside(pos)) {
                            containing = candidate;
                            break scan;
                        }
                    }
                }
            }
        }
        StructureStart start = containing.isValid() ? containing : fallback;
        if (!start.isValid()) {
            return "no grid_rooms structure within 6 chunks of " + pos;
        }
        List<StructurePiece> pieces = new ArrayList<>(start.getPieces());
        pieces.sort(Comparator.comparingInt(
                        (StructurePiece piece) -> piece.getBoundingBox().minY())
                .thenComparingInt(piece -> piece.getBoundingBox().minX())
                .thenComparingInt(piece -> piece.getBoundingBox().minZ()));
        StringBuilder out = new StringBuilder("# player " + pos.getX() + " " + pos.getY() + " " + pos.getZ() + "\n");
        out.append("# count ").append(pieces.size()).append('\n');
        out.append("# minX minY maxX minZ maxY maxZ  xSpan ySpan zSpan\n");
        for (StructurePiece piece : pieces) {
            BoundingBox box = piece.getBoundingBox();
            out.append(box.minX())
                    .append(' ')
                    .append(box.minY())
                    .append(' ')
                    .append(box.maxX())
                    .append(' ')
                    .append(box.minZ())
                    .append(' ')
                    .append(box.maxY())
                    .append(' ')
                    .append(box.maxZ())
                    .append("  ")
                    .append(box.getXSpan())
                    .append('x')
                    .append(box.getYSpan())
                    .append('x')
                    .append(box.getZSpan())
                    .append('\n');
        }
        Path path = level.getServer().getServerDirectory().resolve("griddump.txt");
        try {
            Files.writeString(path, out.toString());
        } catch (IOException error) {
            return "failed to write " + path + ": " + error.getMessage();
        }
        return "wrote " + pieces.size() + " piece(s) to " + path;
    }

    /** 每件占一格：格子 14 宽，一行 8 格 */
    private static final int CATALOGUE_STEP = 14;

    private static final int CATALOGUE_PER_ROW = 8;

    /**
     * 把所有拆出来的件摆成一张网格，每件配一个结构方块、一块告示牌，并用红羊毛框出格子
     *
     * <p>改完直接在结构方块里按 SAVE 就会写到 {@code generated/<ns>/structures/<路径>.nbt}，
     * 拷回 {@code data/<ns>/structure/} 即可。方块位置保持件的本地原点，否则保存下来会把偏移写进件里。
     */
    public static String placeCatalogue(ServerLevel level, BlockPos origin) {
        StructureTemplateManager manager = level.getStructureManager();
        List<ResourceLocation> ids = manager.listTemplates()
                .filter(id -> id.getNamespace().equals(DawnDuskTerminal.MOD_ID))
                .sorted(Comparator.comparing(ResourceLocation::getPath))
                .toList();
        int placed = 0;
        int skipped = 0;
        for (int i = 0; i < ids.size(); i++) {
            ResourceLocation id = ids.get(i);
            Optional<StructureTemplate> optional = manager.get(id);
            if (optional.isEmpty()) {
                skipped++;
                continue;
            }
            Vec3i size = optional.get().getSize();
            if (size.getX() <= 0 || size.getY() <= 0 || size.getZ() <= 0) {
                skipped++;
                continue;
            }
            BlockPos min = origin.offset(
                    (i % CATALOGUE_PER_ROW) * CATALOGUE_STEP, 0, (i / CATALOGUE_PER_ROW) * CATALOGUE_STEP);
            loadChunks(level, min, size);
            frame(level, min, size);
            optional.get().placeInWorld(level, min, min, new StructurePlaceSettings(), level.getRandom(), 2);
            marker(level, id, min, size);
            sign(level, id, min.offset(-1, 0, -1), size);
            placed++;
        }
        return String.format(
                "catalogue: %d piece(s) placed, %d skipped; red wool = cell, structure block saves to "
                        + "generated/%s/structures/<path>.nbt",
                placed, skipped, DawnDuskTerminal.MOD_ID);
    }

    /** 红羊毛框：贴在模板包围盒外一圈，画在原点下面一格，免得压住件最底那一层 */
    private static void frame(ServerLevel level, BlockPos min, Vec3i size) {
        int x0 = min.getX() - 1;
        int x1 = min.getX() + size.getX();
        int z0 = min.getZ() - 1;
        int z1 = min.getZ() + size.getZ();
        int y = min.getY() - 1;
        for (int x = x0; x <= x1; x++) {
            level.setBlockAndUpdate(new BlockPos(x, y, z0), Blocks.RED_WOOL.defaultBlockState());
            level.setBlockAndUpdate(new BlockPos(x, y, z1), Blocks.RED_WOOL.defaultBlockState());
        }
        for (int z = z0; z <= z1; z++) {
            level.setBlockAndUpdate(new BlockPos(x0, y, z), Blocks.RED_WOOL.defaultBlockState());
            level.setBlockAndUpdate(new BlockPos(x1, y, z), Blocks.RED_WOOL.defaultBlockState());
        }
    }

    /**
     * 两个结构方块：躺在件对角线两端、各出格一格（y 也一样）
     *
     * <p>一头是 SAVE：名字就是件的 id，原点与尺寸都按件的包围盒填好，点 SAVE 直接写对。
     * 另一头是 CORNER，点 DETECT 时用来复核同一个盒子。
     * 两个都在件的外面，不会被存进件里。
     */
    private static void marker(ServerLevel level, ResourceLocation id, BlockPos min, Vec3i size) {
        BlockPos save = min.offset(-1, -1, -1);
        BlockPos corner = min.offset(size.getX(), size.getY(), size.getZ());
        structureBlock(level, save, StructureMode.SAVE, id, save, min, size);
        structureBlock(level, corner, StructureMode.CORNER, id, save, min, size);
    }

    private static void structureBlock(
            ServerLevel level,
            BlockPos pos,
            StructureMode mode,
            ResourceLocation id,
            BlockPos anchor,
            BlockPos min,
            Vec3i size) {
        level.setBlockAndUpdate(pos, Blocks.STRUCTURE_BLOCK.defaultBlockState());
        if (level.getBlockEntity(pos) instanceof StructureBlockEntity block) {
            block.setMode(mode);
            block.setStructureName(id);
            // 结构方块里的原点与角点都是相对自己的坐标
            block.setStructurePos((mode == StructureMode.SAVE ? min : anchor).subtract(pos));
            block.setStructureSize(size);
            block.setRotation(Rotation.NONE);
            block.setMirror(Mirror.NONE);
            block.setShowAir(true);
            block.setIgnoreEntities(false);
            block.setShowBoundingBox(true);
            block.setChanged();
            level.sendBlockUpdated(pos, level.getBlockState(pos), level.getBlockState(pos), 3);
        }
    }

    /** 告示牌：目录 / 件名 / 尺寸 */
    private static void sign(ServerLevel level, ResourceLocation id, BlockPos pos, Vec3i size) {
        level.setBlockAndUpdate(pos, Blocks.OAK_SIGN.defaultBlockState());
        if (level.getBlockEntity(pos) instanceof SignBlockEntity sign) {
            String path = id.getPath();
            int slash = path.lastIndexOf('/');
            SignText text = new SignText()
                    .setMessage(0, Component.literal(slash < 0 ? DawnDuskTerminal.MOD_ID : path.substring(0, slash)))
                    .setMessage(1, Component.literal(slash < 0 ? path : path.substring(slash + 1)))
                    .setMessage(2, Component.literal(size.getX() + "x" + size.getY() + "x" + size.getZ()));
            sign.setText(text, true);
            sign.setChanged();
            level.sendBlockUpdated(pos, level.getBlockState(pos), level.getBlockState(pos), 3);
        }
    }

    /** 幕布外的格子也要摆：命令一次性铺满整片，没加载的区块里 setBlock 会静默失败 */
    private static void loadChunks(ServerLevel level, BlockPos min, Vec3i size) {
        int x0 = SectionPos.blockToSectionCoord(min.getX() - 1);
        int x1 = SectionPos.blockToSectionCoord(min.getX() + size.getX());
        int z0 = SectionPos.blockToSectionCoord(min.getZ() - 1);
        int z1 = SectionPos.blockToSectionCoord(min.getZ() + size.getZ());
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                level.getChunk(x, z);
            }
        }
    }
}
