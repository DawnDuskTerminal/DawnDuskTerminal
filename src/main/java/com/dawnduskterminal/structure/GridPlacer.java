package com.dawnduskterminal.structure;

import com.dawnduskterminal.DawnDuskTerminal;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
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
import net.minecraft.world.level.block.StandingSignBlock;
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

    /** 网格从玩家脚前几格起铺：推出去两格，第一件不会压住你站的地方 */
    private static final int CATALOGUE_GAP = 2;

    /** 垫层画在件底下面几格：贴着一格，件里的洞不会变成坑 */
    private static final int PAD_DEPTH = 1;

    /** 一个件在目录里占的格子：第 {@code column} 列、第 {@code row} 行（都是格子数，不是方块） */
    public record Cell(ResourceLocation id, String group, int column, int row) {}

    /** 摆全部件，各分类的第一个件都从新的一行开始 */
    public static String placeCatalogue(ServerLevel level, BlockPos origin) {
        return placeCatalogue(level, origin, null, null, Direction.SOUTH);
    }

    /**
     * 把指定结构（与可选的分类目录）的件摆成一张网格，每件配一个结构方块、一块告示牌，并用红羊毛垫满格子
     *
     * <p>{@code structureId} 为空时摆全部件；{@code dir} 为空时摆该结构的所有分类。
     * 同一个分类排在同一段行里，换分类另起一行，所以看不出分类边界的旧排法已经换掉。
     *
     * <p>网格朝着 {@code facing} 铺：行沿你看的方向一格一格远去，列排在右手边，
     * 起点在你面前 {@value #CATALOGUE_GAP} 格，所以范围由你站着朝哪看决定，不会朝背后长出去。
     * 件本身一律不转：结构方块的 SAVE 只按世界里的方块读，转过一次就会把旋转烘进件文件
     * （{@code StructureBlockEntity#saveStructure} 走 {@code fillFromWorld}，不看 rotation）。
     *
     * <p>改完直接在结构方块里按 SAVE 就会写到 {@code generated/<ns>/structures/<路径>.nbt}，
     * 拷回 {@code data/<ns>/structure/<家族>/} 即可。方块位置保持件的本地原点，否则保存下来会把偏移写进件里。
     */
    public static String placeCatalogue(
            ServerLevel level,
            BlockPos origin,
            @Nullable ResourceLocation structureId,
            @Nullable String dir,
            Direction facing) {
        GridStructure family = null;
        if (structureId != null) {
            Structure registered =
                    level.registryAccess().registryOrThrow(Registries.STRUCTURE).get(structureId);
            if (registered == null) {
                return "no such structure: " + structureId;
            }
            if (!(registered instanceof GridStructure grid)) {
                return structureId + " is not a " + DawnDuskTerminal.id("grid")
                        + " structure, its pieces cannot be listed";
            }
            family = grid;
        }
        if (dir != null && !GridStructure.PIECE_DIRS.contains(dir)) {
            return "unknown piece category: " + dir + "; expected one of " + GridStructure.PIECE_DIRS;
        }
        StructureTemplateManager manager = level.getStructureManager();
        List<ResourceLocation> ids = catalogueIds(manager, family, dir);
        if (ids.isEmpty()) {
            return "catalogue: no piece matched " + (structureId == null ? "" : structureId + " ")
                    + (dir == null ? "" : dir) + "; is the family directory present?";
        }
        Map<String, Integer> groups = new LinkedHashMap<>();
        Direction rows = facing;
        Direction columns = facing.getClockWise();
        BlockPos start = origin.relative(rows, CATALOGUE_GAP);
        int placed = 0;
        int skipped = 0;
        for (Cell cell : layout(ids)) {
            Optional<StructureTemplate> optional = manager.get(cell.id());
            if (optional.isEmpty()) {
                skipped++;
                continue;
            }
            Vec3i size = optional.get().getSize();
            if (size.getX() <= 0 || size.getY() <= 0 || size.getZ() <= 0) {
                skipped++;
                continue;
            }
            int alongRows = cell.row() * CATALOGUE_STEP;
            int alongColumns = cell.column() * CATALOGUE_STEP;
            BlockPos min = start.offset(
                    rows.getStepX() * alongRows + columns.getStepX() * alongColumns,
                    0,
                    rows.getStepZ() * alongRows + columns.getStepZ() * alongColumns);
            loadChunks(level, min, size);
            frame(level, min, size);
            // 结构方块与告示牌先放：它们会触发方块更新，梯子、活板门这种要附着面的一更新就掉，
            // 所以件本身最后摆，摆完不再碰这一格
            marker(level, cell.id(), min, size, facing);
            // 井段件（post/well*）里是梯子与活板门，半空里没有隔壁就自己掉下来，摆之前先垫一格红羊毛
            if (wellPiece(cell.id())) {
                level.setBlock(min.offset(0, size.getY(), 0), Blocks.RED_WOOL.defaultBlockState(), 2);
            }
            optional.get().placeInWorld(level, min, min, new StructurePlaceSettings(), level.getRandom(), 2);
            groups.merge(cell.group(), 1, Integer::sum);
            placed++;
        }
        return String.format(
                "catalogue: %d piece(s) placed, %d skipped, groups %s; red wool = cell, structure block saves to "
                        + "generated/%s/structures/<path>.nbt",
                placed, skipped, groups, DawnDuskTerminal.MOD_ID);
    }

    /**
     * 挑出要摆的件
     *
     * <p>{@code family} 为空时整个命名空间的件都算（旧用法）；否则只算这个家族目录下的。
     * {@code dir} 非空时再按分类目录筛一道
     */
    public static List<ResourceLocation> catalogueIds(
            StructureTemplateManager manager, @Nullable GridStructure family, @Nullable String dir) {
        String prefix = family == null ? null : family.templates() + "/";
        return manager.listTemplates()
                .filter(id -> id.getNamespace().equals(DawnDuskTerminal.MOD_ID))
                .filter(id -> prefix == null || id.getPath().startsWith(prefix))
                .filter(id -> dir == null || withoutFamily(id, prefix).startsWith(dir + "/"))
                .sorted(Comparator.comparing(ResourceLocation::getPath))
                .toList();
    }

    /** 体型顺序：楼板、天花板、地基、房间都按这个排 */
    private static final List<String> KIND_ORDER = List.of("small", "rect", "large", "inner_corner");

    /** 房间组里的角色顺序：空地 → 宝箱 → 楼梯 → 掉落口 → 绳梯 → 家具，其余排最后 */
    private static final List<String> ROLE_ORDER = List.of("empty", "chest", "stair", "drop", "rope", "furn");

    /**
     * 摆位：按段分块，同段内按体型、同组内按角色，换块另起一行
     *
     * <p>只算坐标、不碰世界，所以能直接拿假 id 测。每行 {@value #CATALOGUE_PER_ROW} 件；
     * 换组时上一行已经用掉一半也换行。
     *
     * <p>段的顺序见 {@link #sectionRank}：特殊件 → 地板 → 天花板 → 地基 → 外挑 → 女儿墙 → 房间 → 墙。
     */
    public static List<Cell> layout(List<ResourceLocation> ids) {
        List<Cell> cells = new ArrayList<>(ids.size());
        // 先按「段、体型、角色、路径」排一遍：同一个组必须排在相邻的位置上，
        // 否则同组会被路径排序拆到两头，画成两块
        List<ResourceLocation> ordered = new ArrayList<>(ids);
        ordered.sort(Comparator.comparingInt(GridPlacer::sectionRank)
                .thenComparingInt(GridPlacer::kindRank)
                .thenComparingInt(GridPlacer::roleRank)
                .thenComparing(ResourceLocation::getPath));
        String group = null;
        int row = 0;
        int column = 0;
        for (ResourceLocation id : ordered) {
            String key = groupOf(id);
            if (!key.equals(group)) {
                if (column > 0) {
                    row++;
                }
                group = key;
                column = 0;
            }
            if (column == CATALOGUE_PER_ROW) {
                column = 0;
                row++;
            }
            cells.add(new Cell(id, key, column, row));
            column++;
        }
        return cells;
    }

    /**
     * 段顺序：剩下的特殊件排最前，接着是地板、天花板、地基、外挑、女儿墙、房间，墙最后
     *
     * <p>特殊件（`post/`、`air/`、根目录的测试模板）不按段摆，合成一块放在最前面，
     * 因为它们件数少、也不是拿来比样式的。
     */
    private static int sectionRank(ResourceLocation id) {
        return switch (dirOf(id)) {
            case "floor" -> 1;
            case "ceiling" -> 2;
            case "base" -> 3;
            case "edge" -> 4;
            case "parapet" -> 5;
            case "room" -> 6;
            case "wall" -> 7;
            default -> 0;
        };
    }

    /** 体型顺序：small → rect → large → inner_corner；纯数字（墙段长度 5 / 7）排在它们前面 */
    private static int kindRank(ResourceLocation id) {
        String[] parts = id.getPath().split("/");
        for (String part : parts) {
            int rank = KIND_ORDER.indexOf(part);
            if (rank >= 0) {
                return KIND_ORDER.size() + rank;
            }
        }
        String name = parts[parts.length - 1];
        // 墙件的长短边写在件名里（{@code small_plain_01}），没有单独一层目录
        int underscore = name.indexOf('_');
        if (underscore > 0) {
            int rank = KIND_ORDER.indexOf(name.substring(0, underscore));
            if (rank >= 0) {
                return KIND_ORDER.size() + rank;
            }
        }
        return name.chars().allMatch(Character::isDigit) ? Integer.parseInt(name) : 0;
    }

    /** 房间组里的角色顺序；不是房间件就一律 0（段内再按体型、路径排） */
    private static int roleRank(ResourceLocation id) {
        if (!"room".equals(dirOf(id))) {
            return 0;
        }
        String[] parts = id.getPath().split("/");
        int rank = ROLE_ORDER.indexOf(roleOf(parts[parts.length - 1]));
        return rank < 0 ? ROLE_ORDER.size() : rank;
    }

    /** 分类目录：路径第二段（{@code <家族>/<目录>/…}）；根目录的测试模板没有这一段 */
    private static String dirOf(ResourceLocation id) {
        String[] parts = id.getPath().split("/");
        return parts.length >= 2 ? parts[1] : "";
    }

    /** 房间件的体型：{@code <家族>/room/<体型>/<件名>} */
    private static String kindOf(ResourceLocation id) {
        String[] parts = id.getPath().split("/");
        return parts.length >= 4 ? parts[2] : "other";
    }

    /** 墙件的长短边：{@code wall/small_plain_01} → {@code small} */
    private static String wallSize(ResourceLocation id) {
        String[] parts = id.getPath().split("/");
        String name = parts[parts.length - 1];
        int underscore = name.indexOf('_');
        return underscore > 0 ? name.substring(0, underscore) : name;
    }

    /**
     * 摆位分块用的组：同一组排在同一段行里
     *
     * <ul>
     *   <li>地板、天花板、地基、外挑、女儿墙各是一组；墙按短边 / 长边分两组；
     *   <li>房间按体型分组（{@code room/small}、{@code room/rect}、{@code room/large}、
     *       {@code room/inner_corner}），组内再按角色排；
     *   <li>其余件（`post/`、`air/`、根目录的测试模板）合成最前面那一组。
     * </ul>
     */
    private static String groupOf(ResourceLocation id) {
        String family = id.getPath().split("/")[0];
        return switch (dirOf(id)) {
            case "room" -> family + "/room/" + kindOf(id);
            case "floor", "ceiling", "base", "edge", "parapet" -> family + "/" + dirOf(id);
            case "wall" -> family + "/wall/" + wallSize(id);
            default -> family + "/other";
        };
    }

    /** 井段件：{@code post/well}、{@code post/well_ladder}，件里是要附着面的梯子与活板门 */
    private static boolean wellPiece(ResourceLocation id) {
        return id.getPath().contains("/post/well");
    }

    /** 件名去掉序号：{@code furn_04} → {@code furn}，{@code stair} → {@code stair} */
    private static String roleOf(String name) {
        int underscore = name.lastIndexOf('_');
        if (underscore > 0 && name.substring(underscore + 1).chars().allMatch(Character::isDigit)) {
            return name.substring(0, underscore);
        }
        return name;
    }

    /** 去掉家族前缀的相对路径，没带前缀就原样返回 */
    private static String withoutFamily(ResourceLocation id, @Nullable String prefix) {
        String path = id.getPath();
        return prefix != null && path.startsWith(prefix) ? path.substring(prefix.length()) : path;
    }

    /**
     * 垫层：边框红羊毛、中间玻璃，画在件底下面 {@value #PAD_DEPTH} 格
     *
     * <p>整格都要铺满不要只画一圈：件里常有一格是空的（凹角件的凹口、带洞的楼板），
     * 空心时往下摆方块会直接掉进去，改件的人得先自己垫一层。
     *
     * <p>中间用玻璃是为了看见件的底面与最底那一层：玻璃是实心的，掉下来的方块与手滑放的方块都有人接住，
     * 又透光。红羊毛那一圈留着当格子的边界。
     */
    private static void frame(ServerLevel level, BlockPos min, Vec3i size) {
        int x0 = min.getX() - 1;
        int x1 = min.getX() + size.getX();
        int z0 = min.getZ() - 1;
        int z1 = min.getZ() + size.getZ();
        int y = min.getY() - PAD_DEPTH;
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                boolean border = x == x0 || x == x1 || z == z0 || z == z1;
                level.setBlock(new BlockPos(x, y, z), (border ? Blocks.RED_WOOL : Blocks.GLASS).defaultBlockState(), 2);
            }
        }
    }

    /**
     * 两个结构方块：躺在件对角线两端、各出格一格（y 也一样），每个上面一块告示牌
     *
     * <p>一头是 SAVE：名字就是件的 id，原点与尺寸都按件的包围盒填好，点 SAVE 直接写对。
     * 另一头是 CORNER，点 DETECT 时用来复核同一个盒子。
     * 两个都在件的外面，不会被存进件里；牌子立在方块上面，朝向跟摆命令的人一样。
     */
    private static void marker(ServerLevel level, ResourceLocation id, BlockPos min, Vec3i size, Direction facing) {
        BlockPos save = min.offset(-1, -1, -1);
        BlockPos corner = min.offset(size.getX(), size.getY(), size.getZ());
        structureBlock(level, save, StructureMode.SAVE, id, save, min, size);
        structureBlock(level, corner, StructureMode.CORNER, id, save, min, size);
        sign(level, id, save.above(), size, facing);
        sign(level, id, corner.above(), size, facing);
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

    /**
     * 告示牌：中文名 / 分类与件名 / 尺寸
     *
     * <p>牌子立在结构方块上面，正面朝着摆命令的人：网格往你面朝的方向铺，你站在原点这边看，
     * 每块牌子都正对着你
     */
    private static void sign(ServerLevel level, ResourceLocation id, BlockPos pos, Vec3i size, Direction facing) {
        level.setBlockAndUpdate(
                pos, Blocks.OAK_SIGN.defaultBlockState().setValue(StandingSignBlock.ROTATION, signRotation(facing)));
        if (level.getBlockEntity(pos) instanceof SignBlockEntity sign) {
            String path = id.getPath();
            int slash = path.lastIndexOf('/');
            SignText text = new SignText()
                    .setMessage(0, Component.literal(chineseName(path)))
                    .setMessage(1, Component.literal(path))
                    .setMessage(2, Component.literal(size.getX() + "x" + size.getY() + "x" + size.getZ()));
            sign.setText(text, true);
            sign.setChanged();
            level.sendBlockUpdated(pos, level.getBlockState(pos), level.getBlockState(pos), 3);
        }
    }

    /**
     * 告示牌的 {@code ROTATION}：与「玩家自己放牌子」同一套
     *
     * <p>原版是 {@code RotationSegment.convertToSegment(玩家 yaw + 180)}，四个朝向换算下来就是这四个值。
     */
    private static int signRotation(Direction facing) {
        return switch (facing) {
            case NORTH -> 0;
            case EAST -> 4;
            case SOUTH -> 8;
            case WEST -> 12;
            default -> 8;
        };
    }

    /**
     * 件路径的中文名：逐段查词表拼出来，比如 {@code grid_rooms/room/rect/furn_04} → 「房间·矩形·家具 04」
     *
     * <p>不走语言文件：这是一张开发用的目录，件是随手加的，写死 60 多条 lang 键不如一张词表；
     * 词表里没有的段照原样拼上，看到英文就说明该补词表了
     */
    private static String chineseName(String path) {
        List<String> words = new ArrayList<>();
        String[] segments = path.split("/");
        for (int i = 0; i < segments.length; i++) {
            String segment = segments[i];
            if (i == 0) {
                // 第一段是家族目录，不重复写
                continue;
            }
            String name = segment;
            String number = "";
            int underscore = segment.lastIndexOf('_');
            if (underscore > 0 && segment.substring(underscore + 1).chars().allMatch(Character::isDigit)) {
                name = segment.substring(0, underscore);
                number = segment.substring(underscore + 1);
            }
            String translated = PIECE_WORDS.getOrDefault(name, name);
            words.add(number.isEmpty() ? translated : translated + " " + number);
        }
        return String.join("·", words);
    }

    /** 件路径各段的中文名 */
    private static final Map<String, String> PIECE_WORDS = Map.ofEntries(
            // 分类目录
            Map.entry("room", "房间"),
            Map.entry("wall", "墙"),
            Map.entry("parapet", "女儿墙"),
            Map.entry("ceiling", "天花板"),
            Map.entry("floor", "楼板"),
            Map.entry("base", "地基"),
            Map.entry("edge", "外挑"),
            Map.entry("post", "柱子"),
            Map.entry("well", "绳梯井"),
            Map.entry("air", "空气"),
            // 体型与角色
            Map.entry("large", "大型"),
            Map.entry("rect", "矩形"),
            Map.entry("small", "小型"),
            Map.entry("inner_corner", "内角"),
            Map.entry("empty", "空地"),
            Map.entry("furn", "家具"),
            Map.entry("chest", "宝箱"),
            Map.entry("drop", "掉落口"),
            Map.entry("rope", "绳梯"),
            Map.entry("stair", "楼梯"),
            Map.entry("corner", "内角"),
            Map.entry("notch", "凹口"),
            Map.entry("column", "柱"),
            Map.entry("door", "带门"),
            Map.entry("plain", "无门"));

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
