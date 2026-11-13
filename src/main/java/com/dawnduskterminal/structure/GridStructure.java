package com.dawnduskterminal.structure;

import com.dawnduskterminal.DawnDuskTerminal;
import com.dawnduskterminal.registry.ModStructures;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.Vec3i;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureType;
import net.minecraft.world.level.levelgen.structure.pieces.PiecesContainer;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePiecesBuilder;
import net.minecraft.world.level.levelgen.structure.pools.StructurePoolElement;
import net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool;
import net.minecraft.world.level.levelgen.structure.templatesystem.LiquidSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessorList;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import twilightforest.init.TFBlocks;

/**
 * 由 {@link GridFrame} 规划出的房间图落成建筑（单层）
 *
 * <p>每格四件事：一件房间（地基 + 地板 + 原木角柱 + 家具）、一件天花板、每条外露的边一段墙、
 * 以及墙底下那一条外伸（{@code edge/}）。件的局部原点一致——地板在 y=3、天花板在 y=8、
 * 墙身 4..8、女儿墙 9..10——所以每一件都直接落在格子原点上，没有按件调整的偏移。
 * 地基、女儿墙这些“只在某些层出现”的部分用 {@link BandProcessor} 按世界 y 裁，不切件。
 *
 * <p>房间件的角色写在件名里（`room/<体型>_<角色>_<序号>`）：门后的格子与楼梯顶上的格子必须是空地，
 * 绳梯与楼梯跨层靠配对约束，见 {@code docs/worldgen.md} 的「装配」与「上下楼」。
 *
 * <p>多层时每层各自规划一次（种子 `seed + 131k`），所以每层的形状都是随机的，
 * 上下两层不必嵌套，上层可以伸出去、悬空。层内用包围盒的 y 范围做两件事：
 * 地基只留底层（上层裁掉 y<3），女儿墙只在「上方没有房间」的格子保留（否则裁到 y<=8）。
 *
 * <p>原木框架是相邻格子共用的：格子的步进是「边长减一」，相邻两格共享同一列原木。
 * 那一列只由西侧/北侧那一格画，东侧/南侧的格子用包围盒把自己那一列裁掉，
 * 否则同一列会被两格各画一遍，两块地板材料不同就会拼出一列补丁。
 *
 * <p>不走 jigsaw：{@code StructurePiecesBuilder} 不检查碰撞，也不会打乱顺序，格子先后完全由规划决定，
 * 因此重放同一颗种子得到同一栋楼。
 */
public class GridStructure extends Structure {
    public static final MapCodec<GridStructure> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                    settingsCodec(instance),
                    Codec.STRING.fieldOf("templates").forGetter(s -> s.templates),
                    Codec.intRange(3, 16).fieldOf("columns").forGetter(s -> s.columns),
                    Codec.intRange(3, 16).fieldOf("rows").forGetter(s -> s.rows),
                    Codec.intRange(1, 4).fieldOf("storeys").forGetter(s -> s.storeys),
                    Codec.intRange(-128, 320).fieldOf("y_min").forGetter(s -> s.yMin),
                    Codec.intRange(-128, 320).fieldOf("y_max").forGetter(s -> s.yMax),
                    Codec.BOOL.optionalFieldOf("posts", true).forGetter(s -> s.posts))
            .apply(instance, GridStructure::new));

    /** 件的局部 y：地板平面 */
    private static final int FLOOR_Y = 3;

    /** 房间件从地板上面一行起（地板与天花板单独成件） */
    private static final int ROOM_Y = FLOOR_Y + 1;

    /** 女儿墙件的落笔行 */
    private static final int PARAPET_Y = 9;

    /** 天花板平面；也是上一层的地板 */
    private static final int CEIL_Y = 8;

    /** 层高：地板 y=3、天花 y=8，上一层地板正好落在 y=8，两层共用这一层楼板 */
    private static final int STOREY_HEIGHT = 5;

    /** 层数权重：下标是层数。一层最常见，两层其次，三层偶尔露出一次 */
    private static final int[] STOREY_WEIGHTS = {0, 6, 3, 1};

    /** 随机缺失的比例（千分之几）：几百块里掉几块，够旧但不至于漏风 */
    private static final int WEATHER_DECAY_PERMILLE = 35;

    /** 每栋房子要不要在缺失处挂蜘蛛网：一半一半 */
    private static final float WEBBED_CHANCE = 0.5f;

    /**
     * 件的分类目录名，也是 `/ddt pieces` 的摆放顺序：结构件在前、清理用的空气件在最后
     *
     * <p>目录名就是件相对路径的第一段（`<家族>/<目录>/<件名>`）。新加一个目录要同时加进这里，
     * 否则 `/ddt pieces <结构> <目录>` 不认它
     */
    public static final List<String> PIECE_DIRS =
            List.of("room", "wall", "parapet", "ceiling", "floor", "base", "edge", "post", "air");

    /** 上一层比下一层的列数/行数各偏多少：正态分布的标准差，取 1 就是大多数时候只差一格 */
    private static final double STOREY_SPREAD = 1.0;

    /** 往下接柱子时要求正下方这一圈（外扩这么多格）里没有房间，免得柱子贴着邻居的墙面 */
    private static final int SUPPORT_CLEARANCE = 3;

    /** 门、上下楼格子的数量：每这么多格一个，至少一个 */
    private static final float DOOR_PER_CELLS = 10.0f;

    private static final float CLIMB_PER_CELLS = 5.0f;

    /**
     * 房间角色的权重，按体型各一套，顺序是 空地 / 家具 / 宝箱 / 掉落口 / 绳梯 / 楼梯
     *
     * <p>不需要凑成 100，只按比例算。某个角色在这个体型下没有件时会被自动跳过，
     * 所以 {@code large} 与 {@code small} 的宝箱房写 0（它们没有宝箱房件）。
     * 宝箱房比三种跨层件更稀；空房间在 {@code large} 最多、{@code rect} 最少——大格空着不显空，
     * 矩形格空着只会显得没做完。
     */
    private static final Map<GridFrame.Kind, Map<Role, Integer>> ROOM_WEIGHTS = Map.of(
            GridFrame.Kind.LARGE, weights(45, 42, 0, 6, 3, 4),
            GridFrame.Kind.RECT, weights(15, 70, 3, 3, 4, 5),
            GridFrame.Kind.SMALL, weights(20, 65, 0, 3, 9, 3));

    /** 凹角件凹口所在的局部列（`room/inner_corner` 的角落） */
    private static final int NOTCH_X = 6;

    private static final int NOTCH_Z = 6;

    /** 天花板上面、地板下面各清空几层 */
    private static final int ROOF_CLEAR = 4;

    /** `air/<体型>` 一件有多高；要清的行数比它多时按件高一段段摆 */
    private static final int AIR_ROWS = 3;

    /** `sidesOf` 给每条边定的旋转，都是「把 east 转过去」的那一个 */
    private static final Direction PIECE_WALL = Direction.EAST;

    /** `rect` 件的家具靠 east；`small` 件要靠 south，也就是整件再逆时针转一格才对得上外墙 */
    private static final Direction PIECE_WALL_RECT = Direction.EAST;

    private static final Direction PIECE_WALL_SMALL = Direction.SOUTH;

    private static final Rotation[] ROTATIONS = {
        Rotation.NONE, Rotation.CLOCKWISE_90, Rotation.CLOCKWISE_180, Rotation.COUNTERCLOCKWISE_90
    };

    private static final LiquidSettings LIQUID = LiquidSettings.IGNORE_WATERLOGGING;

    private static final Map<String, List<ResourceLocation>> VARIANTS = new ConcurrentHashMap<>();

    /** 件在框架线上有没有家具（第六层以内、不是四角原木的非空方块） */
    private static final Map<ResourceLocation, Boolean> BORDER = new ConcurrentHashMap<>();

    private static final Set<ResourceLocation> MISSING = ConcurrentHashMap.newKeySet();

    private static final Set<String> PARTIAL = ConcurrentHashMap.newKeySet();

    private final int columns;
    private final int rows;
    /**
     * 件所在的家族目录名，件 id 是 {@code <namespace>:<templates>/<分类>/<件名>}
     *
     * <p>一个家族就是一整套件（分类目录齐全），{@code grid_rooms} 与 {@code grid_rooms_upper} 共用一套；
     * 以后换材质的变体另开一个目录、只改这个字段。必填，不给默认值：漏写的前缀只会让件一次都找不到
     */
    private final String templates;
    /** 层数上限；实际层数按权重在 1..上限里挑 */
    private final int storeys;
    /** 生成高度范围：这栋房子是地下的，y_min/y_max 之间随机取一层的地板高度 */
    private final int yMin;

    private final int yMax;

    /** 悬空格子要不要往下接柱子到地面：浮空的建筑（塔木那族）关掉它 */
    private final boolean posts;

    /** 每栋房子一颗：结构缺失率（千分之几）与蜘蛛网盐 */
    private static final ThreadLocal<Weathering> WEATHERING = new ThreadLocal<>();

    public GridStructure(
            StructureSettings settings,
            String templates,
            int columns,
            int rows,
            int storeys,
            int yMin,
            int yMax,
            boolean posts) {
        super(settings);
        this.templates = templates.endsWith("/") ? templates.substring(0, templates.length() - 1) : templates;
        this.columns = columns;
        this.rows = rows;
        this.storeys = storeys;
        this.yMin = Math.min(yMin, yMax);
        this.yMax = Math.max(yMin, yMax);
        this.posts = posts;
    }

    /** 件 id：家族目录下的相对路径，{@code piece("room/rect/furn_04")} */
    private ResourceLocation piece(String relative) {
        return DawnDuskTerminal.id(this.templates + "/" + relative);
    }

    /** 家族目录名，{@code grid_rooms}。{@code GridPlacer} 按它筛出这一整套件 */
    public String templates() {
        return this.templates;
    }

    /** 反过来：从件 id 取回家族目录之下的相对路径，{@code room/rect/furn_04} */
    private String relative(ResourceLocation id) {
        String prefix = this.templates + "/";
        String path = id.getPath();
        return path.startsWith(prefix) ? path.substring(prefix.length()) : path;
    }

    /** 一栋房子的破损：缺失率与蜘蛛网盐，{@code webSeed == 0} 表示这栋不长蜘蛛网 */
    private record Weathering(int decayPermille, long webSeed) {
        static final Weathering NONE = new Weathering(0, 0L);
    }

    @Override
    public Optional<Structure.GenerationStub> findGenerationPoint(Structure.GenerationContext context) {
        RandomSource random = context.random();
        int x = context.chunkPos().getMiddleBlockX();
        int z = context.chunkPos().getMiddleBlockZ();
        // 这一段高度只由本结构的 JSON 决定；y>0 那一段是另一个结构（grid_rooms_upper），
        // 各自有自己的结构集与间距，所以两段互不抢丰度
        int span = this.yMax - this.yMin + 1;
        int originY = this.yMin + random.nextInt(Math.max(1, span));
        BlockPos origin = new BlockPos(x, originY, z);
        return Optional.of(new Structure.GenerationStub(origin, pieces -> build(context, pieces, origin)));
    }

    /**
     * 地基柱与绳梯往下接到第一个实心方块
     *
     * <p><b>只在当前区块那一截里做。</b>{@code afterPlace} 会在结构覆盖的每个区块上各跑一遍，
     * 交进来的 {@code box} 只有当前区块那一块（{@code ChunkGenerator#getWritableArea}，整根柱高）。
     * 世界生成期手里的 {@code level} 是 {@code WorldGenRegion}，它只认得自己那一圈区块：
     * 读到圈外直接抛 {@code Requested chunk unavailable during world generation}，
     * 那个异常会打断整个区块的生成，单人游戏里就是被集成服务端踢回主菜单。
     * 原版的两种 {@code afterPlace}（{@code WoodlandMansionStructure}、{@code DesertPyramidStructure}）
     * 也只扫 {@code box}，这里照做：每根柱子、每条绳梯由它自己所在的那个区块负责接。
     */
    @Override
    public void afterPlace(
            WorldGenLevel level,
            StructureManager manager,
            ChunkGenerator generator,
            RandomSource random,
            BoundingBox box,
            ChunkPos chunkPos,
            PiecesContainer pieces) {
        // 这里只做“把柱子与绳梯往下接到地面”这一件收尾事。件本身已经落完了，
        // 本方法抛出的异常却会让 `applyBiomeDecoration` 整步作废：玩家被集成服务端踢出去，
        // 而且（走崩溃/紧急保存那条路时）会留下半成品的结构区块。所以每个件在
        // `extendSupports` 里各包一层 try/catch，真出意外就让那一根短一截。
        extendSupports(level, box, pieces, chunkPos);
    }

    /** 柱子与绳梯往下接：只看落在 {@code box}（当前区块）那一截里的件 */
    private void extendSupports(WorldGenLevel level, BoundingBox box, PiecesContainer pieces, ChunkPos chunkPos) {
        for (StructurePiece piece : pieces.pieces()) {
            if (!(piece instanceof PoolElementStructurePiece pool)) {
                continue;
            }
            // 浮空建筑不往下接柱子；绳子照接，不然底层那根吊不到地面
            if (!this.posts && pool.getElement().toString().contains("post/column")) {
                continue;
            }
            // 一个件出意外只让它自己短一截：整段收尾抛出去会让 `applyBiomeDecoration` 整步作废，
            // 结果是玩家被集成服务端踢出去，还要留下半成品的结构区块
            try {
                extendSupport(level, box, piece, pool);
            } catch (RuntimeException error) {
                DawnDuskTerminal.LOGGER.warn(
                        "DawnDuskTerminal grid structure failed to extend {} at chunk {}, leaving it short",
                        pool.getElement(),
                        chunkPos,
                        error);
            }
        }
    }

    /**
     * 绳梯房地板下面那一小段井
     *
     * <p>地板那一行只有洞（有的房型洞里是梯子，有的是一根绳），活板门那一列从地板下面才开始，
     * 所以井口下面放一小件 {@code post/well}（一格绳）或 {@code post/well_ladder}（梯子 + 活板门），
     * 再往下的那一段由 {@code wellDown} 在 {@code afterPlace} 里照它的横截面铺下去。
     *
     * <p>这一件要摆在地板下面一行，并与房间件里那一列对齐：件是按「旋转后包围盒的下角对齐」落的，
     * 所以房间里那一列的局部坐标得用 {@link #pieceOrigin} 换算成世界坐标，
     * 井段件自己的锚点也一样换算，两者相减就是落笔点。
     */
    private void placeWells(
            Structure.GenerationContext context, StructurePiecesBuilder pieces, List<Plan> plans, BlockPos origin) {
        StructureTemplateManager manager = context.structureTemplateManager();
        for (int k = 0; k < plans.size(); k++) {
            BlockPos floor = origin.offset(0, k * STOREY_HEIGHT, 0);
            for (Map.Entry<GridFrame.Cell, Role> entry : plans.get(k).climbs.entrySet()) {
                if (entry.getValue() != Role.ROPE) {
                    continue;
                }
                GridFrame.Cell cell = entry.getKey();
                ResourceLocation room = plans.get(k).rooms.get(cell);
                StructureTemplate.StructureBlockInfo mouth = climbable(manager, room);
                if (mouth == null) {
                    continue;
                }
                ResourceLocation well =
                        mouth.state().is(TFBlocks.ROPE.get()) ? piece("post/well") : piece("post/well_ladder");
                StructureTemplate.StructureBlockInfo anchor = climbable(manager, well);
                if (anchor == null) {
                    continue;
                }
                Rotation rotation = plans.get(k).rotations.get(cell);
                BlockPos cellFloor = floor.offset(cell.x(), 0, cell.z());
                // 绳梯房自带楼板，房间件的原点在地板行；井段件要落在地板**下面**一行
                int roomRow = carriesFloor(room) ? FLOOR_Y : ROOM_Y;
                BlockPos roomAt = pieceOrigin(context, room, rotation, cellFloor.offset(0, roomRow, 0));
                BlockPos mouthAt =
                        roomAt.offset(StructureTemplate.transform(mouth.pos(), Mirror.NONE, rotation, BlockPos.ZERO));
                BlockPos anchorAt = StructureTemplate.transform(anchor.pos(), Mirror.NONE, rotation, BlockPos.ZERO);
                placeAll(
                        context,
                        pieces,
                        well,
                        rotation,
                        new BlockPos(
                                mouthAt.getX() - anchorAt.getX(), roomAt.getY() - 1, mouthAt.getZ() - anchorAt.getZ()),
                        BandProcessor.Phase.SOLID);
            }
        }
    }

    /** 件里第一格绳或梯子（局部坐标 + 状态），没有就 null */
    private static StructureTemplate.StructureBlockInfo climbable(
            StructureTemplateManager manager, ResourceLocation id) {
        if (id == null) {
            return null;
        }
        StructureTemplate template = manager.get(id).orElse(null);
        if (template == null) {
            return null;
        }
        StructurePlaceSettings settings = new StructurePlaceSettings();
        for (Block block : List.of(TFBlocks.ROPE.get(), Blocks.LADDER)) {
            List<StructureTemplate.StructureBlockInfo> found = template.filterBlocks(BlockPos.ZERO, settings, block);
            if (!found.isEmpty()) {
                return found.get(0);
            }
        }
        return null;
    }

    /** 一件：绳梯件把那一列绳/梯铺下去，柱子件往下接到地面 */
    private static void extendSupport(
            WorldGenLevel level, BoundingBox box, StructurePiece piece, PoolElementStructurePiece pool) {
        BlockPos.MutableBlockPos column = new BlockPos.MutableBlockPos();
        String element = pool.getElement().toString();
        BoundingBox pieceBox = piece.getBoundingBox();
        // 这一件落在当前区块里的那一截，不相交就整件跳过
        int minX = Math.max(pieceBox.minX(), box.minX());
        int maxX = Math.min(pieceBox.maxX(), box.maxX());
        int minZ = Math.max(pieceBox.minZ(), box.minZ());
        int maxZ = Math.min(pieceBox.maxZ(), box.maxZ());
        if (minX > maxX || minZ > maxZ) {
            return;
        }
        // 井段件（post/well*）：这一件就是井口下面那一小段，整段横截面（绳/梯子 + 活板门）照样往下铺
        if (element.contains("/post/well")) {
            wellDown(level, pieceBox, minX, maxX, minZ, maxZ);
            return;
        }
        // 柱子件往下接：井段件也是 1x1，按几何判断会把它接成一根孤柱，所以上一支先把它挑走
        if (!element.contains("post/column")) {
            return;
        }
        if (pieceBox.getXSpan() != 1 || pieceBox.getZSpan() != 1) {
            return;
        }
        int x = pieceBox.minX();
        int z = pieceBox.minZ();
        // 柱子本身在这一截之外：交给它自己所在的那个区块
        if (x < minX || x > maxX || z < minZ || z > maxZ) {
            return;
        }
        int top = Math.min(pieceBox.minY() - 1, box.maxY());
        int landing = landingY(level, x, z, top);
        if (landing == NO_LANDING) {
            return;
        }
        for (int y = top; y >= landing; y--) {
            level.setBlock(
                    column.set(x, y, z),
                    TFBlocks.STRIPPED_TWILIGHT_OAK_LOG.get().defaultBlockState(),
                    2);
        }
    }

    /** 探不到落脚点：液体挡路，或者一路探到上限/基岩都还是空气 */
    private static final int NO_LANDING = Integer.MIN_VALUE;

    /** 柱子最多往下接这么多格。再深就不是「接到地面」，而是往虚空里吊柱子 */
    private static final int SUPPORT_MAX_DROP = 24;

    /**
     * 井段够不到地面时只铺几行，算上井口下面那一件
     *
     * <p>和柱子共用同一条上限 {@link #SUPPORT_MAX_DROP}：上限之内一路接到地面；
     * 超过上限（或者一路都没落脚点）就只铺地基这一层，不在虚空里吊一根到基岩的绳子。
     */
    private static final int WELL_ROWS = 3;

    /**
     * 先探再落笔：这根柱子应该铺到哪一格
     *
     * <p>照原版废弃矿井的做法——先看下面是什么，再决定接不接：一路往下探到第一个实心方块为止，
     * <b>中途遇到液体就整根不接</b>（不在水面上立柱子、也不往岩浆里吊），
     * 探到 {@link #SUPPORT_MAX_DROP} 格或基岩还没落地同样不接。
     * 只有「脚下确实是实地」时才把柱子接过去，悬空与液体上方都留给原本的样子。
     *
     * @return 柱子应该铺到的最低 y（含）；{@link #NO_LANDING} 表示这一根不接
     */
    private static int landingY(WorldGenLevel level, int x, int z, int top) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        int limit = Math.max(level.getMinBuildHeight(), top - SUPPORT_MAX_DROP);
        for (int y = top; y >= limit; y--) {
            cursor.set(x, y, z);
            BlockState state = level.getBlockState(cursor);
            if (!state.getFluidState().isEmpty()) {
                return NO_LANDING;
            }
            if (!state.canBeReplaced()) {
                // 下面这一格已经是实心：柱子从它上面一格开始；已经贴着地就不用再接
                return y >= top ? NO_LANDING : y + 1;
            }
        }
        return NO_LANDING;
    }

    /**
     * 把井口那一列往下铺到第一个能落脚的地方
     *
     * <p>井口就在绳梯房的地板那一行（件的最后一行）：那一行里属于井的方块有两种——
     * 绳/梯子本体，以及并排那几格活板门（当梯子背后的挡板用）。这里把这一整段横截面
     * 一格一格往下复制，直到撞上实心方块，于是往下一路都是「绳梯 + 活板门」的原样，
     * 不必再为每一段高度单独做一件梯井。
     *
     * <p>同样是「先探再落笔」：液体、可替换方块都算能走，撞到实心方块就停在它上面一格。
     * 上限与柱子共用一条（{@value #SUPPORT_MAX_DROP} 格）：上限之内一路接到地面；
     * 超过上限、或者一路都没有落脚点，就只铺 {@value #WELL_ROWS} 行——正好是地基那一层，
     * 不在虚空里吊一根到基岩的绳子。
     *
     * <p>搜索范围收在 {@code minX..maxX} / {@code minZ..maxZ}（当前区块那一截）：读到区块外会抛异常，
     * 见 {@link #afterPlace}。往下那一截与搜索格同列，整根柱都在当前区块里，不算越界。
     */
    private static void wellDown(WorldGenLevel level, BoundingBox box, int minX, int maxX, int minZ, int maxZ) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        // 井口在地板那一行：件的最后一行（合并过楼板的绳梯房，这一行就是 y=3 那块地板）
        int floorY = box.minY();
        boolean well = false;
        for (int x = minX; x <= maxX && !well; x++) {
            for (int z = minZ; z <= maxZ && !well; z++) {
                well = isClimbable(level.getBlockState(cursor.set(x, floorY, z)));
            }
        }
        if (!well) {
            return;
        }
        int top = floorY - 1;
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                BlockState carried = level.getBlockState(cursor.set(x, floorY, z));
                if (!isClimbable(carried) && !(carried.getBlock() instanceof TrapDoorBlock)) {
                    continue;
                }
                int landing = ropeLandingY(level, x, z, top);
                // 和柱子共用一条上限：上限之内一路接到地面；太深或者一路没落脚点就只铺地基那一层
                int base = floorY - (WELL_ROWS - 1);
                boolean reachable = landing != NO_LANDING && landing >= top - SUPPORT_MAX_DROP;
                int bottom = reachable ? landing : base;
                for (int down = top; down >= bottom; down--) {
                    level.setBlock(cursor.set(x, down, z), carried, 2);
                }
            }
        }
    }

    /**
     * 绳梯往下探到哪一格
     *
     * <p><b>绳子比柱子宽松</b>：液体算能走，一路探到基岩为止。这个维度的地下铺满含水层，
     * 悬空房子的梯井下面十有八九先是水再是石；照柱子那套「遇水不接」的话，绳子会停在半空，
     * 从井口跳下去等于摔进水里。绳子、梯子自己也都能走，否则第一格就撞上自己，
     * 往下那一截永远铺不下去。
     *
     * @return 绳梯应该铺到的最低 y（含）；{@link #NO_LANDING} 表示下面从头到尾都是空的
     */
    private static int ropeLandingY(WorldGenLevel level, int x, int z, int top) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        int limit = level.getMinBuildHeight();
        int landing = NO_LANDING;
        for (int y = top; y >= limit; y--) {
            cursor.set(x, y, z);
            BlockState state = level.getBlockState(cursor);
            if (!state.getFluidState().isEmpty() || state.canBeReplaced() || isClimbable(state)) {
                landing = y;
                continue;
            }
            // 撞到实心方块就停：绳子挂在它上面那一格
            break;
        }
        return landing;
    }

    /** 按权重挑层数：下限 1，上限是配置里的 {@code storeys} */
    private int pickStoreys(RandomSource random) {
        int cap = Math.min(Math.max(1, this.storeys), STOREY_WEIGHTS.length - 1);
        int total = 0;
        for (int k = 1; k <= cap; k++) {
            total += STOREY_WEIGHTS[k];
        }
        int roll = random.nextInt(total);
        for (int k = 1; k <= cap; k++) {
            roll -= STOREY_WEIGHTS[k];
            if (roll < 0) {
                return k;
            }
        }
        return cap;
    }

    private void build(Structure.GenerationContext context, StructurePiecesBuilder pieces, BlockPos origin) {
        RandomSource random = context.random();
        long seed = random.nextLong();
        int storeys = pickStoreys(random);
        // 破损：缺失率固定，蜘蛛网每栋独立掷一次（掷中才挂）
        long webSeed = random.nextFloat() < WEBBED_CHANCE ? random.nextLong() : 0L;
        Weathering previous = WEATHERING.get();
        WEATHERING.set(new Weathering(WEATHER_DECAY_PERMILLE, webSeed));
        // worldgen 的工作线程是复用的：件规划中途抛出去也要把这颗盐收回来，
        // 否则下一次在这条线程上生成的房子会读到上一座的那一颗
        try {
            buildPieces(context, pieces, origin, random, seed, storeys, webSeed);
        } finally {
            if (previous == null) {
                WEATHERING.remove();
            } else {
                WEATHERING.set(previous);
            }
        }
    }

    /** {@link #build} 的主体：规划与落笔。破损参数由调用方放进 {@link #WEATHERING} */
    private void buildPieces(
            Structure.GenerationContext context,
            StructurePiecesBuilder pieces,
            BlockPos origin,
            RandomSource random,
            long seed,
            int storeys,
            long webSeed) {
        int maxColumns = Math.max(3, this.columns);
        int maxRows = Math.max(3, this.rows);
        // 底层在 [3, 配置上限] 里随机；往上每层只比下层多/少一点点：
        // 长边（列数）与短边（行数）各自按均值 0、标准差 STOREY_SPREAD 的正态函数取整后累加
        int columns = 3 + random.nextInt(Math.max(1, maxColumns - 2));
        int rows = 3 + random.nextInt(Math.max(1, maxRows - 2));
        // 每层各自规划一次：四个角各自挖不挖、挖多深都独立算，但矩形大小逐层只微调，
        // 层与层不互相收边，所以上层仍可能探出去或缩进来一点
        List<List<GridFrame.Cell>> levels = new ArrayList<>();
        for (int k = 0; k < storeys; k++) {
            if (k > 0) {
                columns = clampSpan(columns + (int) Math.round(random.nextGaussian() * STOREY_SPREAD), maxColumns);
                rows = clampSpan(rows + (int) Math.round(random.nextGaussian() * STOREY_SPREAD), maxRows);
            }
            List<GridFrame.Cell> level = GridFrame.plan(seed + k * 131L, columns, rows);
            if (level.isEmpty()) {
                break;
            }
            levels.add(level);
        }
        // 每层的形状依旧独立随机，只是整层平移一下，让某一对 rect 上下对上，用来放楼梯
        for (int k = 1; k < levels.size(); k++) {
            alignLevel(levels.get(k - 1), levels.get(k));
        }
        StructureTemplateManager manager = context.structureTemplateManager();
        // 先把每层摆什么定下来：门、上下楼的格子、其余房间
        // 穿越两层的房间彼此要空开：某层放了之后，把上下与同层挨着的格子记成“不许再放”
        List<Set<GridFrame.Cell>> noClimb = new ArrayList<>();
        for (int k = 0; k < levels.size(); k++) {
            noClimb.add(new HashSet<>());
        }
        List<Set<GridFrame.Cell>> forceEmpty = new ArrayList<>();
        for (int k = 0; k < levels.size(); k++) {
            forceEmpty.add(new HashSet<>());
        }
        List<Plan> plans = new ArrayList<>();
        for (int k = 0; k < levels.size(); k++) {
            plans.add(plan(manager, levels, plans, noClimb, forceEmpty, k, random));
        }
        ensureChestRoom(manager, plans, random);
        // 先把每一层的清空做完，再落任何一件：清空是纯空气，全部提前做完就绝不会擦到已经摆好的东西，
        // 也不必给清空算「哪些列要放过」——下层自己的件在下面几遍里会重新摆回来
        for (int k = 0; k < plans.size(); k++) {
            clearLevel(context, pieces, plans.get(k), k, plans.size(), origin);
        }
        // 落笔分遍：先只落空气把地形挖开，再只落实体方块。
        // 顺序反过来的话，后摆那件的空气会把前一件摆在框架线上的家具（树皮、栏杆、门）擦掉
        // 逐层落：这一层的空气 → 这一层的实体方块。上一层的空气正好把下一层多出来的
        // 女儿墙擦掉，而上一层的地板压在下一层的天花板上，于是看不到重复的框架
        for (int k = 0; k < plans.size(); k++) {
            placeCells(context, pieces, plans.get(k), levels, k, origin, BandProcessor.Phase.AIR);
            placeShell(context, pieces, plans.get(k), levels, k, origin, BandProcessor.Phase.AIR);
            placeCells(context, pieces, plans.get(k), levels, k, origin, BandProcessor.Phase.SOLID);
            placeShell(context, pieces, plans.get(k), levels, k, origin, BandProcessor.Phase.SOLID);
        }
        // 悬空的地基往下接到地面；绳梯往下接到下层地板
        Set<Long> posts = new HashSet<>();
        for (int k = 0; k < plans.size(); k++) {
            placeSupports(context, pieces, plans.get(k), levels, k, origin, posts);
        }
        // 绳梯房地板下面那一小段：井口从这里开始往下接（见 wellDown）
        placeWells(context, pieces, plans, origin);
        // 带边框家具的房间最后再落一遍实体方块：它们的家具就在框架线上，
        // 邻居的空气与实体方块都已经摆完，谁也盖不掉
        for (int k = 0; k < plans.size(); k++) {
            placeBorder(context, pieces, plans.get(k), levels, k, origin);
        }
        // 凹角的凹口最后再擦一遍：那一列在凹角件自己的格子里，但这一格的天花板件与女儿墙件
        // 比房间件落笔晚，会把凹角件里存的空气盖回去
        for (int k = 0; k < plans.size(); k++) {
            placeNotches(context, pieces, plans.get(k), k, origin);
        }
        // 楼梯房也最后落：件自己的最后一行（y=8）是带洞的天花板，与上一层的地板同一平面，
        // 落笔晚才盖得住它、把洞口挖成空气
        for (int k = 0; k < plans.size(); k++) {
            placeStairs(context, pieces, plans.get(k), k, origin);
        }
        // 一行摘要：世界生成出的结构一旦"缺层"，先比这条日志与另一端的日志
        // （层数、原点、包围盒），能立刻分清是「件表就不一样」还是「件一样但没落上」
        DawnDuskTerminal.LOGGER.info(
                "DawnDuskTerminal grid structure at chunk {}: {} level(s), origin y={}, bbox {}, decay={}‰, cobwebs={}",
                context.chunkPos(),
                plans.size(),
                origin.getY(),
                pieces.getBoundingBox(),
                WEATHER_DECAY_PERMILLE,
                webSeed == 0L ? "off" : "on");
    }

    /**
     * 定一层摆什么
     *
     * <p>先门、再上下楼的格子、最后剩下的房间：门后的那一格必须是空地，楼梯顶上的那一格也必须是空地，
     * 所以这两件事先排，空地率只对没被它们占掉的格子生效。
     */
    private Plan plan(
            StructureTemplateManager manager,
            List<List<GridFrame.Cell>> levels,
            List<Plan> plans,
            List<Set<GridFrame.Cell>> noClimb,
            List<Set<GridFrame.Cell>> forceEmpty,
            int k,
            RandomSource random) {
        List<GridFrame.Cell> cells = levels.get(k);
        Plan plan = new Plan(cells);
        plan.forcedEmpty.addAll(forceEmpty.get(k));
        plan.runs.addAll(wallRuns(cells));
        // 楼梯正上方那一格必须是空地房：楼梯件自己的最后一行（y=8）是带洞的天花板，
        // 落笔晚于上一层，盖掉那一格的地板、把洞口挖成空气，玩家上来就站在空房间里。
        // 于是不再需要楼梯口件（{@code stairtop}）与那一块带洞楼板，只要求「位置、体型、朝向都对得上」；
        // 对不上说明这一层没楼梯（alignLevel 已经把某对 rect 挪整齐，抓不到的说明这层没有 rect）
        Set<GridFrame.Cell> arrivals = new HashSet<>();
        if (k > 0) {
            for (GridFrame.Cell stair : plans.get(k - 1).stairs) {
                GridFrame.Cell above = aligned(cells, levels, k, stair);
                if (above != null) {
                    arrivals.add(above);
                    plan.forcedEmpty.add(above);
                    forceEmpty.get(k).add(above);
                }
            }
        }

        // 挑格子的先后：宝箱房 → 楼梯 → 门。宝箱房先占一格 rect，楼梯与门再绕开它，
        // 免得后面几轮把它挤到边角或者挤没。一座房子只要一间，所以前面的层挑到了就不再挑
        if (plans.stream().noneMatch(previous -> previous.chest != null)) {
            pickChest(manager, plan, arrivals, random);
        }
        // 上下楼：必修项先放——最下面那个上层至少一条绳/梯，顶层以外的每层至少一部楼梯。
        // 其余名额只补楼梯与洞口：绳子和梯子只出现在最下面那个上层，再往上都用楼梯
        List<GridFrame.Cell> order2 = new ArrayList<>(cells);
        shuffle(order2, random);
        if (plan.chest != null) {
            order2.remove(plan.chest);
        }
        int climbs = clamp(Math.round(cells.size() / (float) CLIMB_PER_CELLS), 1, cells.size());
        boolean up = k + 1 < levels.size();
        // 先摆楼梯再摆绳梯：stairs 是唯一「往上走」的路，而且它必须落在和上一层对齐的那格 rect 上；
        // 那格要是先被绳梯占了，这一层就再也接不上了
        if (up) {
            climb(manager, levels, plans, noClimb, forceEmpty, k, order2, plan, List.of(Role.STAIR), random);
        }
        // 只有底层必修一部绳梯；上层想吊只能靠挑空探出去的格子，由 extras 去补
        if (k == 0) {
            climb(manager, levels, plans, noClimb, forceEmpty, k, order2, plan, List.of(Role.ROPE), random);
        }
        // 门：数量与该层格子数成正比，至少一个
        int doors = clamp(Math.round(cells.size() / (float) DOOR_PER_CELLS), 1, cells.size());
        List<WallRun> order = new ArrayList<>(plan.runs);
        shuffle(order, random);
        for (WallRun run : order) {
            if (plan.doorRuns.size() >= doors) {
                break;
            }
            GridFrame.Cell cell = run.cell();
            if (cell.kind() == GridFrame.Kind.INNER_CORNER
                    || cell.equals(plan.chest)
                    || plan.climbs.containsKey(cell)
                    || arrivals.contains(cell)) {
                continue;
            }
            if (plan.doorCells.add(cell)) {
                plan.doorRuns.add(run);
            }
        }

        List<Role> extras = new ArrayList<>();
        if (up) {
            extras.add(Role.STAIR);
        }
        if (k > 0) {
            extras.add(Role.DROP);
            extras.add(Role.ROPE);
        }
        if (!extras.isEmpty()) {
            for (GridFrame.Cell cell : order2) {
                if (plan.climbs.size() >= climbs) {
                    break;
                }
                if (plan.doorCells.contains(cell) || plan.climbs.containsKey(cell) || arrivals.contains(cell)) {
                    continue;
                }
                placeClimb(manager, levels, plans, noClimb, forceEmpty, k, cell, plan, extras, random);
            }
        }

        // 多层时每层都该有一条上下楼的路，接不上就是件名或空地下层的条件太紧，值得报一次
        if (plan.climbs.isEmpty() && (k > 0 || levels.size() > 1)) {
            DawnDuskTerminal.LOGGER.warn(
                    "DawnDuskTerminal grid structure storey {} of {} has no room that connects storeys",
                    k,
                    levels.size());
        } else if (up && plan.stairs.isEmpty()) {
            DawnDuskTerminal.LOGGER.warn(
                    "DawnDuskTerminal grid structure storey {} of {} has no stair to the storey above",
                    k,
                    levels.size());
        }

        for (GridFrame.Cell cell : cells) {
            ResourceLocation room;
            room = pickRoom(manager, plan, cell, random);
            plan.rooms.put(cell, room);
            // 家具靠 east，朝向按这一面去贴格子真正露在外面的那面墙；大格没有外墙，随机转一个方向
            Rotation rotation = orient(plan, cell);
            if (cell.kind() == GridFrame.Kind.LARGE) {
                rotation = ROTATIONS[(rotation.ordinal() + random.nextInt(4)) & 3];
            }
            plan.rotations.put(cell, rotation);
            if (hasBorderFurniture(manager, room)) {
                plan.border.add(cell);
            }
        }
        return plan;
    }

    /**
     * 把这一格定成上下楼的格子（能定就定）
     *
     * <p>绳、跳下的洞口：下层同位置必须是空地。绳随便穿；洞口就是直接掉下去。
     * 梯子：还要下层同体型同朝向——梯子靠墙，不是同一间房就贴不住。
     * 穿越两层的房间彼此要空开：上面、下面、同一层挨着的格子都不能再有。
     * 楼梯：件自己的最后一行是带洞的天花板，所以上一层必须有一格位置、体型、朝向都对得上；
     * 那一格强制成空地房，洞口正对着它。
     */
    /**
     * 在这一层里挑第一个空位放指定角色的跨层房间
     *
     * @return 有没有放成
     */
    private boolean climb(
            StructureTemplateManager manager,
            List<List<GridFrame.Cell>> levels,
            List<Plan> plans,
            List<Set<GridFrame.Cell>> noClimb,
            List<Set<GridFrame.Cell>> forceEmpty,
            int k,
            List<GridFrame.Cell> candidates,
            Plan plan,
            List<Role> allowed,
            RandomSource random) {
        for (GridFrame.Cell cell : candidates) {
            if (plan.doorCells.contains(cell)) {
                continue;
            }
            if (placeClimb(manager, levels, plans, noClimb, forceEmpty, k, cell, plan, allowed, random)) {
                return true;
            }
        }
        return false;
    }

    private boolean placeClimb(
            StructureTemplateManager manager,
            List<List<GridFrame.Cell>> levels,
            List<Plan> plans,
            List<Set<GridFrame.Cell>> noClimb,
            List<Set<GridFrame.Cell>> forceEmpty,
            int k,
            GridFrame.Cell cell,
            Plan plan,
            List<Role> allowed,
            RandomSource random) {
        if (blocked(levels, plans, plan, noClimb, k, cell)) {
            return false;
        }
        List<Role> possible = new ArrayList<>();
        // 下层有建筑时，绳梯的 y=0..2 会落在下层室内最上面三行，压到家具房就撞家具，
        // 所以那时候下层重叠的格子必须全是空地房；下面没有建筑（最底层、或伸出去的悬空格）就不必
        List<GridFrame.Cell> below = k > 0 ? overlaps(levels.get(k - 1), cell) : List.of();
        boolean empty = true;
        for (GridFrame.Cell other : below) {
            ResourceLocation id = plans.get(k - 1).rooms.get(other);
            if (id == null || !relative(id).endsWith("/empty")) {
                empty = false;
                break;
            }
        }
        if (k > 0 && empty && !roomsOf(manager, cell, Role.DROP).isEmpty()) {
            possible.add(Role.DROP);
        }
        // 绳梯是「从下面进来」的：y=0..2 落进下层重叠格子的最上面三行，所以那些格子必须是空地房。
        // 底层没有下层，天然满足；上层下方完全没建筑时也算，绳梯直接吊到地面
        if (empty && !roomsOf(manager, cell, Role.ROPE).isEmpty()) {
            possible.add(Role.ROPE);
        }
        List<ResourceLocation> stairs = List.of();
        if (k + 1 < levels.size()) {
            // 上一层得有一格位置、体型、朝向都对得上（{@code aligned}），那格会被定成空地房；
            // 楼梯件最后落笔，用自己的天花板行盖掉那格的地板并切出洞口
            stairs = roomsOf(manager, cell, Role.STAIR);
            if (!stairs.isEmpty() && aligned(levels.get(k + 1), levels, k + 1, cell) != null) {
                possible.add(Role.STAIR);
            }
        }
        possible.retainAll(allowed);
        // 楼梯两端的格子要留空走路，宝箱房占着的那格不能让楼梯压上来。
        // 这条要在挑定真放哪一件的地方拦：补名额那一轮不走 `climb`，直接调本方法，
        // 拦在 `climb` 里时它拦不到——宝箱房会正好落在楼梯出入口上。
        if (possible.contains(Role.STAIR) && stairHitsChest(levels, k, cell, plan)) {
            possible.remove(Role.STAIR);
        }
        if (possible.isEmpty()) {
            return false;
        }
        Role role = rollRoomRole(cell.kind(), possible, random);
        if (role == null) {
            return false;
        }
        ResourceLocation id;
        if (role == Role.STAIR) {
            id = stairs.get(random.nextInt(stairs.size()));
            plan.stairs.add(cell);
            markStairEnds(levels, forceEmpty, k, cell, plan);
        } else {
            List<ResourceLocation> pieces = roomsOf(manager, cell, role);
            id = pieces.get(random.nextInt(pieces.size()));
        }
        plan.climbs.put(cell, role);
        plan.rooms.put(cell, id);
        block(levels, noClimb, k, cell);
        return true;
    }

    /**
     * 楼梯沿长边往上走：两端各是一格进出口，那两格在本层和上一层都必须换成空地房
     *
     * <p>楼梯件里只有楼梯与栏杆，玩家是从一端的邻格踏上第一级、从另一端的邻格走出楼梯口，
     * 那两格要是家具房，走上来就撞在家具上。所以两端都强制空地，不猜哪头是上、哪头是下。
     */
    private static void markStairEnds(
            List<List<GridFrame.Cell>> levels,
            List<Set<GridFrame.Cell>> forceEmpty,
            int k,
            GridFrame.Cell cell,
            Plan plan) {
        boolean alongZ = cell.d() == GridFrame.L;
        for (int sign = -1; sign <= 1; sign += 2) {
            GridFrame.Cell here = neighbour(levels.get(k), cell, alongZ ? 0 : sign, alongZ ? sign : 0);
            if (here != null) {
                plan.forcedEmpty.add(here);
            }
            if (k + 1 < levels.size()) {
                GridFrame.Cell above = neighbour(levels.get(k + 1), cell, alongZ ? 0 : sign, alongZ ? sign : 0);
                if (above != null) {
                    forceEmpty.get(k + 1).add(above);
                }
            }
        }
    }

    /**
     * 同一层里贴着这一格、往 (dx, dz) 方向的那一格
     *
     * <p>相邻两格共用一条框架线，但两格的跨度不一定一样（一行里是 5、7、5），
     * 往负方向挪的距离是<b>邻格</b>的跨度减一，不是这一格自己的。
     * 拿这一格自己的跨度去算，5 与 7 相接的那一侧整整差 2 格，查出来永远是 null——
     * 楼梯进出口就这样拿不到「必须是空地」，家具照旧摆进去。按共用框架线找没有这个问题，两侧对称。
     */
    private static GridFrame.Cell neighbour(List<GridFrame.Cell> level, GridFrame.Cell cell, int dx, int dz) {
        for (GridFrame.Cell other : level) {
            if (other.equals(cell)) {
                continue;
            }
            if (dx != 0) {
                // 共用框架线：往正方向是这一格的末格，往负方向是这一格的首格
                int frame = dx > 0 ? cell.maxX() - 1 : cell.x() + 1;
                boolean touches = (dx > 0 ? other.x() : other.maxX()) == frame;
                if (touches && overlap(other.z(), other.maxZ(), cell.z(), cell.maxZ()) >= 2) {
                    return other;
                }
            } else {
                int frame = dz > 0 ? cell.maxZ() - 1 : cell.z() + 1;
                boolean touches = (dz > 0 ? other.z() : other.maxZ()) == frame;
                if (touches && overlap(other.x(), other.maxX(), cell.x(), cell.maxX()) >= 2) {
                    return other;
                }
            }
        }
        return null;
    }

    /** 两段区间重叠几格；只搭上一格的算共角，{@link GridFrame#adjacent} 用的是同一个阈值 */
    private static int overlap(int minA, int maxA, int minB, int maxB) {
        return Math.min(maxA, maxB) - Math.max(minA, minB);
    }

    /** 把这一格上下与四周的格子都记成“不许再放穿越两层的房间” */
    private static void block(
            List<List<GridFrame.Cell>> levels, List<Set<GridFrame.Cell>> noClimb, int k, GridFrame.Cell cell) {
        // 只挡同一层：同一层的两个洞口开在同一块楼板上，挨着会连成一片；
        // 上下层的洞口开在不同的楼板平面（这一层的地板 / 天花板），互不影响，
        // 连上也别挡——挡了会出现「下层有绳梯、上层就摆不出楼梯」的情况
        for (GridFrame.Cell other : levels.get(k)) {
            if (GridFrame.touches(other, cell)) {
                noClimb.get(k).add(other);
            }
        }
    }

    /** 这一格会不会和已有的穿越两层的房间撞上 */
    private static boolean blocked(
            List<List<GridFrame.Cell>> levels,
            List<Plan> plans,
            Plan plan,
            List<Set<GridFrame.Cell>> noClimb,
            int k,
            GridFrame.Cell cell) {
        if (noClimb.get(k).contains(cell)) {
            return true;
        }
        for (GridFrame.Cell other : plan.climbs.keySet()) {
            if (GridFrame.adjacent(other, cell)) {
                return true;
            }
        }
        for (int j = Math.max(0, k - 1); j < k; j++) {
            for (GridFrame.Cell other : plans.get(j).climbs.keySet()) {
                if (GridFrame.adjacent(other, cell)) {
                    return true;
                }
            }
        }
        if (k > 0) {
            GridFrame.Cell below = covering(levels.get(k - 1), cell);
            if (below != null && plans.get(k - 1).climbs.containsKey(below)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 这一格里的件该转多少
     *
     * <p>件里外墙固定在同一侧——`rect` 在 east，`small` 在 east 与 north——家具都靠着那一边，
     * 所以要把“件朝外的那一边”转到格子实际朝外的那一边，家具才会一直贴墙。
     * `large` 没有外墙，保持规划给的朝向（规划时才加随机四分之一转）。凹角件自带两面墙，也照规划。
     */
    /**
     * 这一格里的件该转多少
     *
     * <p>件里的家具固定靠在 {@code lean} 那一面（多数是 east，少数件是 west），
     * 要把它转到格子真正露在外面的那面墙上去，家具才贴着外墙而不是朝着邻间。
     *
     * <p>「露在外面」不要求整条边都空：臂上的格会被挑 2 格，一条边常常只是部分露出来，
     * 那种边照样会摆墙（`wallRuns` 会挑最长的那一段），所以按露出的长度排序，家具对着最长的那一段。
     *
     * <p>rect 件是 5 宽 7 深：7 宽 5 深的格子必须转 90 度，5 宽 7 深的只能取 0 / 180，
     * 否则件会挑出格子两格，空气落在邻居身上。
     */
    private static Rotation orient(Plan plan, GridFrame.Cell cell) {
        if (cell.kind() != GridFrame.Kind.RECT && cell.kind() != GridFrame.Kind.SMALL) {
            return ROTATIONS[cell.turns()];
        }
        // 直接看这一格四面里摆了哪几段墙，不再按邻居自己推一遍
        List<WallRun> walls = new ArrayList<>();
        for (WallRun run : plan.runs) {
            if (run.cell().equals(cell)) {
                walls.add(run);
            }
        }
        if (walls.isEmpty()) {
            return ROTATIONS[cell.turns()];
        }
        walls.sort((left, right) -> Integer.compare(right.length(), left.length()));
        boolean rect = cell.kind() == GridFrame.Kind.RECT;
        boolean swapped = rect && cell.w() == GridFrame.L;
        // small 的两面墙是个直角：主面靠 south，第二面是它逆时针那一侧（east）
        Direction primary = cell.kind() == GridFrame.Kind.SMALL ? PIECE_WALL_SMALL : PIECE_WALL_RECT;
        Direction second = primary.getCounterClockWise();
        // 第一遍要求 small 的那两条墙成对，第二遍只看主墙
        for (int pass = 0; pass < 2; pass++) {
            for (WallRun run : walls) {
                Direction side = directionOf(run.side());
                for (int r = 0; r < 4; r++) {
                    if (rect && ((r & 1) == 1) != swapped) {
                        continue;
                    }
                    if (ROTATIONS[r].rotate(primary) != side) {
                        continue;
                    }
                    if (pass == 0
                            && cell.kind() == GridFrame.Kind.SMALL
                            && !hasWall(walls, ROTATIONS[r].rotate(second))) {
                        continue;
                    }
                    return ROTATIONS[r];
                }
            }
        }
        return ROTATIONS[cell.turns()];
    }

    /** 这一格在这条边上摆没摆墙 */
    private static boolean hasWall(List<WallRun> walls, Direction side) {
        for (WallRun run : walls) {
            if (directionOf(run.side()) == side) {
                return true;
            }
        }
        return false;
    }

    /** {@link #sidesOf} 给每条边定的旋转正好是「把 east 转过去」的那一个，反过来就能查出朝向 */
    private static Direction directionOf(Side side) {
        return side.rotation().rotate(PIECE_WALL);
    }

    /** 一张权重表：顺序是 空地 / 家具 / 宝箱 / 掉落口 / 绳梯 / 楼梯 */
    private static Map<Role, Integer> weights(int empty, int furn, int chest, int drop, int rope, int stair) {
        Map<Role, Integer> map = new EnumMap<>(Role.class);
        map.put(Role.EMPTY, empty);
        map.put(Role.FURNITURE, furn);
        map.put(Role.CHEST, chest);
        map.put(Role.DROP, drop);
        map.put(Role.ROPE, rope);
        map.put(Role.STAIR, stair);
        return Map.copyOf(map);
    }

    /** 这个体型里某个角色的权重，没写就是 0 */
    private static int weight(GridFrame.Kind kind, Role role) {
        Map<Role, Integer> table = ROOM_WEIGHTS.get(kind);
        return table == null ? 0 : table.getOrDefault(role, 0);
    }

    /**
     * 在给定角色里按体型的权重抽一个
     *
     * <p>{@code roles} 的顺序必须稳定（调用方传的都是固定顺序的列表），否则同一颗种子出不了同一栋楼
     */
    private static Role rollRoomRole(GridFrame.Kind kind, List<Role> roles, RandomSource random) {
        int total = 0;
        for (Role role : roles) {
            total += weight(kind, role);
        }
        if (total <= 0) {
            return null;
        }
        int roll = random.nextInt(total);
        for (Role role : roles) {
            roll -= weight(kind, role);
            if (roll < 0) {
                return role;
            }
        }
        return null;
    }

    /**
     * 一格的房间件：门后与楼梯顶上必须是空地，剩下的格子按体型的权重抽角色
     *
     * <p>凹角件是整间（两面墙、檐口、凹口），有就整格用它；那一族没摆这件时退回普通抽法。
     *
     * <p>宝箱房不靠权重保证：整栋一间都没抽到时由 {@link #ensureChestRoom} 补。
     */
    private ResourceLocation pickRoom(
            StructureTemplateManager manager, Plan plan, GridFrame.Cell cell, RandomSource random) {
        if (cell.equals(plan.chest)) {
            return plan.chestRoom;
        }
        if (cell.kind() == GridFrame.Kind.INNER_CORNER) {
            ResourceLocation corner = piece("room/inner_corner/corner");
            if (manager.get(corner).isPresent()) {
                return corner;
            }
        }
        ResourceLocation climbing = plan.rooms.get(cell);
        if (climbing != null) {
            return climbing;
        }
        boolean mustBeEmpty = plan.doorCells.contains(cell) || plan.forcedEmpty.contains(cell);
        List<Role> candidates = mustBeEmpty ? List.of(Role.EMPTY) : List.of(Role.EMPTY, Role.FURNITURE, Role.CHEST);
        List<Role> available = new ArrayList<>();
        for (Role candidate : candidates) {
            if (weight(cell.kind(), candidate) > 0
                    && !roomsOf(manager, cell, candidate).isEmpty()) {
                available.add(candidate);
            }
        }
        Role role = rollRoomRole(cell.kind(), available, random);
        if (role == null) {
            role = Role.EMPTY;
        }
        List<ResourceLocation> fit = roomsOf(manager, cell, role);
        if (fit.isEmpty()) {
            fit = roomsOf(manager, cell, Role.EMPTY);
        }
        if (fit.isEmpty()) {
            return piece("room/" + cell.kind().path() + "/empty");
        }
        return fit.get(random.nextInt(fit.size()));
    }

    /**
     * 这一层先挑一格当宝箱房
     *
     * <p>挑格子的顺序是宝箱房 → 楼梯 → 门：宝箱房先占一格 rect，后面两轮绕开它。
     * 下层已经定好的楼梯两端（{@code forcedEmpty}）与上一层的楼梯口（{@code arrivals}）
     * 都不能放宝箱房，那几格得留空走路。
     */
    private void pickChest(
            StructureTemplateManager manager, Plan plan, Set<GridFrame.Cell> arrivals, RandomSource random) {
        List<GridFrame.Cell> free = new ArrayList<>();
        for (GridFrame.Cell cell : plan.cells) {
            if (cell.kind() != GridFrame.Kind.RECT
                    || plan.forcedEmpty.contains(cell)
                    || arrivals.contains(cell)
                    || roomsOf(manager, cell, Role.CHEST).isEmpty()) {
                continue;
            }
            free.add(cell);
        }
        if (free.isEmpty()) {
            return;
        }
        GridFrame.Cell cell = free.get(random.nextInt(free.size()));
        List<ResourceLocation> chests = roomsOf(manager, cell, Role.CHEST);
        plan.chest = cell;
        plan.chestRoom = chests.get(random.nextInt(chests.size()));
        if (hasBorderFurniture(manager, plan.chestRoom)) {
            plan.border.add(cell);
        }
    }

    /** 这一部楼梯的两端会不会压到宝箱房：本层与上一层各两个进出口 */
    private static boolean stairHitsChest(List<List<GridFrame.Cell>> levels, int k, GridFrame.Cell cell, Plan plan) {
        if (plan.chest == null) {
            return false;
        }
        boolean alongZ = cell.d() == GridFrame.L;
        for (int sign = -1; sign <= 1; sign += 2) {
            if (plan.chest.equals(neighbour(levels.get(k), cell, alongZ ? 0 : sign, alongZ ? sign : 0))) {
                return true;
            }
            if (k + 1 < levels.size()
                    && plan.chest.equals(neighbour(levels.get(k + 1), cell, alongZ ? 0 : sign, alongZ ? sign : 0))) {
                return true;
            }
        }
        return false;
    }

    /**
     * 每座房子至少一间宝箱房
     *
     * <p>权重表里宝箱只占 rect 的 3/88，一座房子十几间房也可能一间都抽不到，所以规划完再补：
     * 整栋一间都没有时，拿一间 rect 房换掉（先找空地房，没空地房再拿家具房）。门后、楼梯两端、
     * 上下楼那几格换成宝箱房会堵住通路，不碰。
     */
    private void ensureChestRoom(StructureTemplateManager manager, List<Plan> plans, RandomSource random) {
        for (Plan plan : plans) {
            for (ResourceLocation id : plan.rooms.values()) {
                if (isRole(id, Role.CHEST)) {
                    return;
                }
            }
        }
        // 优先拿空地房换，没空地房了再拿家具房换
        List<Slot> empties = new ArrayList<>();
        List<Slot> others = new ArrayList<>();
        for (Plan plan : plans) {
            for (GridFrame.Cell cell : plan.cells) {
                if (chestSlot(manager, plan, cell)) {
                    boolean empty = isRole(plan.rooms.get(cell), Role.EMPTY);
                    (empty ? empties : others).add(new Slot(plan, cell));
                }
            }
        }
        List<Slot> pool = empties.isEmpty() ? others : empties;
        if (pool.isEmpty()) {
            DawnDuskTerminal.LOGGER.warn(
                    "DawnDuskTerminal grid structure has no rect room left to turn into a chest room");
            return;
        }
        Slot slot = pool.get(random.nextInt(pool.size()));
        List<ResourceLocation> chests = roomsOf(manager, slot.cell(), Role.CHEST);
        ResourceLocation id = chests.get(random.nextInt(chests.size()));
        slot.plan().rooms.put(slot.cell(), id);
        if (hasBorderFurniture(manager, id)) {
            slot.plan().border.add(slot.cell());
        } else {
            slot.plan().border.remove(slot.cell());
        }
    }

    /** 一个能换成宝箱房的格子 */
    private record Slot(Plan plan, GridFrame.Cell cell) {}

    /**
     * 能换成宝箱房的格子：`rect` 房，且不是门后、楼梯两端、上下楼那几格
     *
     * <p>楼梯口那格是强制空地房，也排除。
     */
    private boolean chestSlot(StructureTemplateManager manager, Plan plan, GridFrame.Cell cell) {
        if (cell.kind() != GridFrame.Kind.RECT
                || plan.doorCells.contains(cell)
                || plan.forcedEmpty.contains(cell)
                || plan.climbs.containsKey(cell)) {
            return false;
        }
        return plan.rooms.get(cell) != null
                && !roomsOf(manager, cell, Role.CHEST).isEmpty();
    }

    /**
     * 某个体型的某个角色的件
     *
     * <p>角色写在件名里（{@code room/small_rope_03}）：体型、角色、序号三段。
     * 这样不用去解析模板内容就知道一件是空地、绳、梯、楼梯还是普通家具。
     */
    private List<ResourceLocation> roomsOf(StructureTemplateManager manager, GridFrame.Cell cell, Role role) {
        String prefix = this.templates + "/room/" + cell.kind().path() + "/";
        List<ResourceLocation> out = new ArrayList<>();
        for (ResourceLocation id : variants(manager, prefix)) {
            if (isRole(id, role)) {
                out.add(id);
            }
        }
        return out;
    }

    /** 摆这一层的房间件；{@code phase} 决定只落空气还是只落实体方块 */
    private void placeCells(
            Structure.GenerationContext context,
            StructurePiecesBuilder pieces,
            Plan plan,
            List<List<GridFrame.Cell>> levels,
            int k,
            BlockPos origin,
            BandProcessor.Phase phase) {
        BlockPos floor = origin.offset(0, k * STOREY_HEIGHT, 0);
        int floorY = floor.getY();
        StructureTemplateManager manager = context.structureTemplateManager();
        for (GridFrame.Cell cell : plan.order) {
            ResourceLocation id = plan.rooms.get(cell);
            if (id == null) {
                continue;
            }
            boolean near = roomNear(levels, k, cell);
            Rotation rotation = plan.rotations.get(cell);
            BlockPos cellFloor = floor.offset(cell.x(), 0, cell.z());
            // 掉落口、绳梯、内角这三类房间自带楼板那一行（洞口只对得上自己那一间），件从地板行起
            boolean ownFloor = carriesFloor(id);
            // 楼板：平铺件按体型共用（floor/<体型>）；自带楼板的那三类不再单独摆
            if (!ownFloor) {
                ResourceLocation plane = planeOf(manager, cell);
                // 共用的地板/天花板件按格子自己的朝向转：rect 是 5 宽 7 深，7 宽 5 深的格子不转就只盖住一半
                Rotation planeRotation = relative(plane).startsWith("floor/") ? rotation : ROTATIONS[cell.turns()];
                placeAll(context, pieces, plane, planeRotation, cellFloor.offset(0, FLOOR_Y, 0), phase);
            }
            // 地基只在地基露出来的时候摆
            ResourceLocation ground = near ? null : baseOf(cell);
            if (ground != null) {
                placeAll(
                        context,
                        pieces,
                        ground,
                        rotation,
                        cellAnchored(context, ground, rotation, cellFloor, cell.kind()),
                        phase);
            }
            if (cell.kind() == GridFrame.Kind.INNER_CORNER) {
                placeAll(
                        context,
                        pieces,
                        piece("parapet/inner_corner"),
                        rotation,
                        cellFloor.offset(0, PARAPET_Y, 0),
                        phase);
            }
            // 内空：这一格的四行整块清一遍，用 air/<体型> 而不是房间件自己存的空气。
            // 房间件在这一遍里落下的话，件里的实体会被 spawn 一遍（处理器只管方块，管不到实体），
            // 而实体方块那一遍还要再 spawn 一次——件里两辆宝箱矿车会变成四辆
            if (phase == BandProcessor.Phase.AIR) {
                // 自带楼板的房间要连地板那一行一起清：掉落口与绳梯房件里的洞是真的空气，
                // 不清掉的话平铺楼板（或者地形）会盖在洞口上。内角件用的是结构空位（不落笔），
                // 那一行不能清，否则凹口那一列会把下层天花板擦出个洞来
                clearBand(
                        context,
                        pieces,
                        piece("air/" + airKind(cell)),
                        ROTATIONS[cell.turns()],
                        cellFloor,
                        holesInFloor(id) ? ROOM_Y - 1 : ROOM_Y,
                        ROOM_Y + 3);
                continue;
            }
            // 房间件只剩家具，地板与内空都已经单独摆过/清过了。
            // 带边框家具的格子，实体方块留给 placeBorder 在墙之后落一遍，这里不能再落第二遍
            if (plan.border.contains(cell)) {
                continue;
            }
            placeAll(context, pieces, id, rotation, cellFloor.offset(0, ownFloor ? FLOOR_Y : ROOM_Y, 0), phase);
        }
    }

    /**
     * 一层的清空：天花板上面 ROOF_CLEAR 行、地板下面 ROOF_CLEAR 行，每格都清
     *
     * <p>这一遍在所有件的落笔之前跑，所以清到下层室内也无所谓——下层自己的件随后会摆回来。
     * 挑空、离房间很近的格子同样要清，它们的下面没有自己的地基与柱子。
     *
     * <p><b>只有两段清空落在房子外面，这里不清：</b>顶层天花板以上的那一段、底层地板以下的那一段。
     * 清掉它们就是在地里给房子掏出一圈空腔；本意是要「埋在地里」的结构，顶上与底下都该是实心岩层。
     * 其余各层的两段清空都在室内（上一层的地板上方就是这一层的室内），照清。
     */
    private void clearLevel(
            Structure.GenerationContext context,
            StructurePiecesBuilder pieces,
            Plan plan,
            int k,
            int storeys,
            BlockPos origin) {
        BlockPos floor = origin.offset(0, k * STOREY_HEIGHT, 0);
        boolean top = k >= storeys - 1;
        boolean bottom = k <= 0;
        for (GridFrame.Cell cell : plan.cells) {
            BlockPos cellFloor = floor.offset(cell.x(), 0, cell.z());
            ResourceLocation air = piece("air/" + airKind(cell));
            Rotation airRotation = ROTATIONS[cell.turns()];
            // 顶层天花板以上是室外：不清，房子埋在地里
            if (!top) {
                clearBand(context, pieces, air, airRotation, cellFloor, CEIL_Y + 1, CEIL_Y + ROOF_CLEAR);
            }
            // 底层地板以下是室外：同样不清
            if (!bottom) {
                clearBand(context, pieces, air, airRotation, cellFloor, FLOOR_Y - ROOF_CLEAR, FLOOR_Y - 1);
            }
        }
    }

    /**
     * 把这一格局部 y 在 [from, to] 里的环境方块清成空气
     *
     * <p>空气件只有三行，清的行数可能更多，所以按件高一段段往上摆；
     * 每段都用 {@code BandProcessor} 裁到这一段，越界的行不会落笔。
     */
    private static void clearBand(
            Structure.GenerationContext context,
            StructurePiecesBuilder pieces,
            ResourceLocation air,
            Rotation rotation,
            BlockPos cellFloor,
            int from,
            int to) {
        int floorY = cellFloor.getY();
        for (int y = from; y <= to; y += AIR_ROWS) {
            place(
                    context,
                    pieces,
                    air,
                    rotation,
                    cellFloor.offset(0, y, 0),
                    floorY + from,
                    floorY + to,
                    BandProcessor.Phase.AIR);
        }
    }

    /** 列数/行数收进 [3, 上限] */
    private static int clampSpan(int value, int max) {
        return Math.max(3, Math.min(max, value));
    }

    /**
     * 凹角的凹口：那一列（局部 6,0,6 到 6,4,6）最后按件里的内容重写一遍
     *
     * <p>凹口在凹角件自己的格子内部，写得到这一列的有三类：这一格自己的天花板件与女儿墙件、
     * 紧邻两格画在共用框架线上的框架、以及上一层的地板。它们各自按自己的需要落笔，
     * 最后谁落在这一列全看摆的先后。
     *
     * <p>所以整座结构摆完之后，用家族自己的 {@code air/notch} 把这一列（楼面往上到女儿墙顶）
     * 再写一遍，由件决定这一列到底长什么样：
     * 橡木那族整列是空气（凹口本来就该是空的），塔木那族在楼板/天花板那一行是警示塔木。
     * 因为楼板与上一层的天花板就是同一行，那一行也是两块警示塔木共用的位置。
     */
    private void placeNotches(
            Structure.GenerationContext context, StructurePiecesBuilder pieces, Plan plan, int k, BlockPos origin) {
        BlockPos floor = origin.offset(0, k * STOREY_HEIGHT, 0);
        for (GridFrame.Cell cell : plan.cells) {
            ResourceLocation room = plan.rooms.get(cell);
            if (room == null || cell.kind() != GridFrame.Kind.INNER_CORNER) {
                continue;
            }
            Rotation rotation = plan.rotations.get(cell);
            BlockPos cellFloor = floor.offset(cell.x(), 0, cell.z());
            BlockPos originOf = pieceOrigin(context, room, rotation, cellFloor.offset(0, ROOM_Y, 0));
            BlockPos at = StructureTemplate.transform(
                    new BlockPos(NOTCH_X, 0, NOTCH_Z), Mirror.NONE, rotation, BlockPos.ZERO);
            // 这一遍既可能落空气也可能落方块（塔木那族的警示塔木），所以是 ALL 而不是 AIR
            place(
                    context,
                    pieces,
                    piece("air/notch"),
                    Rotation.NONE,
                    originOf.offset(at),
                    Integer.MIN_VALUE,
                    Integer.MAX_VALUE,
                    BandProcessor.Phase.ALL);
        }
    }

    /**
     * 楼梯房的天花板行重写一遍，排在所有件之后
     *
     * <p>件里除了楼梯与栏杆，最后一行（y=8）是带洞的天花板：它与上一层的地板同一平面，
     * 又比上一层落笔晚，于是盖掉那一格的地板、把洞口开成空气。
     *
     * <p>只重写这一行（{@code minY == maxY == 天花板行}）：件里的空气要用 ALL 才会真的落下，
     * 而整件重放会把邻居摆在框架线上的墙与家具一起擦掉。
     */
    private void placeStairs(
            Structure.GenerationContext context, StructurePiecesBuilder pieces, Plan plan, int k, BlockPos origin) {
        BlockPos floor = origin.offset(0, k * STOREY_HEIGHT, 0);
        int ceilingY = floor.getY() + CEIL_Y;
        for (GridFrame.Cell cell : plan.stairs) {
            ResourceLocation id = plan.rooms.get(cell);
            if (id == null) {
                continue;
            }
            place(
                    context,
                    pieces,
                    id,
                    plan.rotations.getOrDefault(cell, Rotation.NONE),
                    floor.offset(cell.x(), ROOM_Y, cell.z()),
                    ceilingY,
                    ceilingY,
                    BandProcessor.Phase.ALL);
        }
    }

    /**
     * 平层楼板：按体型共用，凹角格是 `floor/inner_corner`；没有就退回天花板件
     *
     * <p>带开口的楼板（掉落口、绳梯）已经并进房间件本身，见 {@link #carriesFloor}。
     * 所以这里只剩按体型共用的那几件。
     *
     * <p>最后一档只是兜底：天花板与地板是两套纹理（天花是倒扣台阶，地板是平铺板材），
     * 一旦回落过去，脚底下铺的就是天花纹。
     */
    private ResourceLocation planeOf(StructureTemplateManager manager, GridFrame.Cell cell) {
        ResourceLocation id = piece(floorKind(cell));
        return manager.get(id).isPresent() ? id : ceiling(cell);
    }

    /** 按体型共用的平铺楼板；凹角格的大件叫 `floor/inner_corner` */
    private static String floorKind(GridFrame.Cell cell) {
        return cell.kind() == GridFrame.Kind.INNER_CORNER
                ? "floor/inner_corner"
                : "floor/" + cell.kind().path();
    }

    /**
     * 地基件：按体型共用
     *
     * <p>外墙的踢脚（脚）画在 base 件里，所以这里的地基比格子大一圈：
     * {@code base/rect} 是 6×3×7（本地东侧多一列）、{@code base/small} 是 6×3×6（本地南 + 东各多一列），
     * {@code base/inner_corner} 与 {@code base/large} 没有露出边，仍是原尺寸。
     * 露出边的条数是确定的（rect 1 条、small 2 条相邻、large 与内角件 0 条），
     * 所以一件件转过去就能对上——base 用的是 {@code plan.rotations}，和房间、家具同一个朝向。
     */
    /**
     * 带脚的地基件比格子大一圈，落笔点要把「格子那一块落在世界里哪」反推出来
     *
     * <p>{@code place()} 是把「旋转后的包围盒最小角」对齐到给定坐标的，而旋转本身是按<b>件的尺寸</b>做的
     * （{@code Rotation.CLOCKWISE_180.rotate(x, z, sizeX, sizeZ)} 用的是件宽件深，不是格子宽深）。
     * 所以这里把格子在本地的四个角按件尺寸转过去、取分量最小，再反推落笔点——
     * 不补这一步，180 度那两格地基会整体往里挪一格。
     */
    private BlockPos cellAnchored(
            Structure.GenerationContext context,
            ResourceLocation id,
            Rotation rotation,
            BlockPos cellMin,
            GridFrame.Kind kind) {
        Vec3i piece = context.structureTemplateManager()
                .get(id)
                .map(StructureTemplate::getSize)
                .orElse(Vec3i.ZERO);
        // 格子在本地的宽深按体型定：件永远是「5×7 / 5×5 / 7×7」那一套，
        // 世界里的宽深由朝向决定，不能用 cell.w()/cell.d() 反推
        int localW = kind == GridFrame.Kind.SMALL ? 5 : 7;
        int localD = kind == GridFrame.Kind.SMALL ? 5 : 7;
        if (kind == GridFrame.Kind.RECT) {
            localW = 5;
            localD = 7;
        }
        int minX = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        for (int[] corner : new int[][] {{0, 0}, {localW - 1, 0}, {0, localD - 1}, {localW - 1, localD - 1}}) {
            int[] rotated =
                    switch (rotation) {
                        case CLOCKWISE_90 -> new int[] {piece.getZ() - 1 - corner[1], corner[0]};
                        case CLOCKWISE_180 -> new int[] {piece.getX() - 1 - corner[0], piece.getZ() - 1 - corner[1]};
                        case COUNTERCLOCKWISE_90 -> new int[] {corner[1], piece.getX() - 1 - corner[0]};
                        default -> new int[] {corner[0], corner[1]};
                    };
            minX = Math.min(minX, rotated[0]);
            minZ = Math.min(minZ, rotated[1]);
        }
        // 竖直方向按「顶面贴着地板那一行」摆：3 行的地基照旧落在 0..2，
        // 1 行的地基就落在 +2，直接垫在楼板下面（塔木那几族的地基高度不一样）
        return cellMin.offset(-minX, FLOOR_Y - piece.getY(), -minZ);
    }

    private ResourceLocation baseOf(GridFrame.Cell cell) {
        return switch (cell.kind()) {
            case RECT -> piece("base/rect");
            case SMALL -> piece("base/small");
            case INNER_CORNER -> piece("base/inner_corner");
            default -> null;
        };
    }

    /**
     * 件名的最后一段（角色那一段）：{@code grid_rooms/room/rect/furn_04} → {@code furn_04}
     *
     * <p>判角色一律走这里，不要拿整条路径去 {@code contains}：
     * 件前面还挂着家族目录与体型目录，写成 {@code contains("_rope_")} 之类的老判断会全部落空
     */
    private String lastName(ResourceLocation id) {
        String relative = relative(id);
        int slash = relative.lastIndexOf('/');
        return slash < 0 ? relative : relative.substring(slash + 1);
    }

    /**
     * 房间件自己带着楼板那一行
     *
     * <p>掉落口、绳梯这两种房间的楼板与房间是一一搭配的（洞口只对得上自己那间），
     * 所以并成一件：件从地板行起，比普通房间件高一行。内角格的楼板与天花板仍按体型共用
     * （`floor/inner_corner`、`ceiling/inner_corner`），不并入房间件。
     */
    private boolean carriesFloor(ResourceLocation id) {
        return isRole(id, Role.DROP) || isRole(id, Role.ROPE);
    }

    /**
     * 楼板那一行里有真的空气洞：内空清空要连地板行一起清
     *
     * <p>内角格不算：它的楼板是按体型共用的 `floor/inner_corner`，凹口那一列存的是结构空位（不落笔），
     * 需要留着下层的天花板。
     */
    private boolean holesInFloor(ResourceLocation id) {
        return isRole(id, Role.DROP) || isRole(id, Role.ROPE);
    }

    /** 件名最后一段是不是这个角色：{@code chest}、{@code chest_01}、{@code furn_04} */
    private boolean isRole(ResourceLocation id, Role role) {
        String name = lastName(id);
        return name.equals(role.tag()) || name.startsWith(role.tag() + "_");
    }

    private static String airKind(GridFrame.Cell cell) {
        return cell.kind() == GridFrame.Kind.INNER_CORNER
                ? "large"
                : cell.kind().path();
    }

    /** 天花板与墙：同样分两遍落，墙里的空气要能把装饰面背后的缝挖开 */
    private void placeShell(
            Structure.GenerationContext context,
            StructurePiecesBuilder pieces,
            Plan plan,
            List<List<GridFrame.Cell>> levels,
            int k,
            BlockPos origin,
            BandProcessor.Phase phase) {
        BlockPos floor = origin.offset(0, k * STOREY_HEIGHT, 0);
        // 普通格的天花板先摆，墙随后，凹角格的天花板最后。
        // 墙件最上面那一行（+8）是錾制/封装塔木梁，正压在框架线上，比普通格的天花板落笔晚；
        // 凹角件在凹口那一角摆的警示塔木就在同一行，得排在墙后面才盖得住
        for (GridFrame.Cell cell : plan.order) {
            if (cell.kind() != GridFrame.Kind.INNER_CORNER) {
                placeCeiling(context, pieces, plan, cell, floor, phase);
            }
        }
        for (WallRun run : plan.runs) {
            placeWall(context, pieces, levels, k, run, floor, plan.doorRuns.contains(run), phase);
        }
        for (GridFrame.Cell cell : plan.order) {
            if (cell.kind() == GridFrame.Kind.INNER_CORNER) {
                placeCeiling(context, pieces, plan, cell, floor, phase);
            }
        }
    }

    /**
     * 一格的天花板：件只有 y=8 这一行，直接摆在地板行上方的第 8 行
     *
     * <p>楼梯格的 y=8 是楼梯口，上一层的地板就落在同一个平面上；那一行最终由上一层的地板件盖掉。
     */
    private void placeCeiling(
            Structure.GenerationContext context,
            StructurePiecesBuilder pieces,
            Plan plan,
            GridFrame.Cell cell,
            BlockPos floor,
            BandProcessor.Phase phase) {
        if (plan.rooms.get(cell) == null) {
            return;
        }
        place(
                context,
                pieces,
                ceiling(cell),
                ROTATIONS[cell.turns()],
                floor.offset(cell.x(), CEIL_Y, cell.z()),
                Integer.MIN_VALUE,
                Integer.MAX_VALUE,
                phase);
    }

    /** 悬空的地基往下接柱子 */
    private void placeSupports(
            Structure.GenerationContext context,
            StructurePiecesBuilder pieces,
            Plan plan,
            List<List<GridFrame.Cell>> levels,
            int k,
            BlockPos origin,
            Set<Long> posts) {
        if (!this.posts) {
            return;
        }
        BlockPos floor = origin.offset(0, k * STOREY_HEIGHT, 0);
        for (GridFrame.Cell cell : plan.cells) {
            if (plan.rooms.get(cell) == null || cell.kind() == GridFrame.Kind.LARGE) {
                continue;
            }
            // 地基与支撑柱用同一条判据：正下方连同外面 3 格的一圈里没有房间才长，
            // 否则贴着邻居房子还挂一圈地基，柱子也会贴着人家的墙面
            if (!roomNear(levels, k, cell)) {
                support(context, pieces, cell, plan.rotations.getOrDefault(cell, Rotation.NONE), floor, posts);
            }
        }
    }

    /** 带边框家具的格子再落一遍实体方块（同一件、同一个朝向，重复落是幂等的） */
    private void placeBorder(
            Structure.GenerationContext context,
            StructurePiecesBuilder pieces,
            Plan plan,
            List<List<GridFrame.Cell>> levels,
            int k,
            BlockPos origin) {
        if (plan.border.isEmpty()) {
            return;
        }
        BlockPos floor = origin.offset(0, k * STOREY_HEIGHT, 0);
        int floorY = floor.getY();
        for (GridFrame.Cell cell : plan.border) {
            ResourceLocation id = plan.rooms.get(cell);
            if (id == null) {
                continue;
            }
            boolean near = roomNear(levels, k, cell);
            place(
                    context,
                    pieces,
                    id,
                    plan.rotations.get(cell),
                    floor.offset(cell.x(), carriesFloor(id) ? FLOOR_Y : ROOM_Y, cell.z()),
                    Integer.MIN_VALUE,
                    Integer.MAX_VALUE,
                    BandProcessor.Phase.SOLID);
        }
    }

    /**
     * 这件在框架线上有没有家具
     *
     * <p>框架线是格子最外那一圈，四角是原木框架、下面还有地板，只查地板以上那几行、贴着墙那几列的边上：
     * 那里出现非空方块就是摆在边框上的家具。模板里的空气能数出来，
     * 所以“非空”= 不在空气集合里；房间件把整格都存满了，这个判断成立。
     *
     * <p>查到件顶为止。房间件只有 4 行，写死查到第 4 行的话，越界那几格
     * 不在空气集合里，会被当成“这儿有家具”，整件都白白重落一遍。
     */
    private static boolean hasBorderFurniture(StructureTemplateManager manager, ResourceLocation id) {
        return BORDER.computeIfAbsent(id, key -> {
            StructureTemplate template = manager.get(key).orElse(null);
            if (template == null) {
                return false;
            }
            Set<BlockPos> air = new HashSet<>();
            for (StructureTemplate.StructureBlockInfo info :
                    template.filterBlocks(BlockPos.ZERO, new StructurePlaceSettings(), Blocks.AIR)) {
                air.add(info.pos());
            }
            Vec3i size = template.getSize();
            int top = Math.min(4, size.getY() - 1);
            for (int y = 1; y <= top; y++) {
                for (int x = 0; x < size.getX(); x++) {
                    for (int z = 0; z < size.getZ(); z++) {
                        boolean edgeX = x == 0 || x == size.getX() - 1;
                        boolean edgeZ = z == 0 || z == size.getZ() - 1;
                        if (edgeX == edgeZ) {
                            continue;
                        }
                        if (!air.contains(new BlockPos(x, y, z))) {
                            return true;
                        }
                    }
                }
            }
            return false;
        });
    }

    /** 一段墙：地板外伸那一条（{@code edge/}）加墙身与女儿墙（{@code wall/}） */
    private void placeWall(
            Structure.GenerationContext context,
            StructurePiecesBuilder pieces,
            List<List<GridFrame.Cell>> levels,
            int k,
            WallRun run,
            BlockPos floor,
            boolean door,
            BandProcessor.Phase phase) {
        GridFrame.Cell cell = run.cell();
        Side side = run.side();
        boolean near = roomNear(levels, k, cell);
        Rotation rotation = side.rotation();
        BlockPos anchor = floor.offset(
                side.anchorX() + side.dx() * run.start(), side.y(), side.anchorZ() + side.dz() * run.start());
        // 三族件的落笔角只差在沿外侧方向挪几格：外挑 2 格（它只剩最外那一列）、墙脚 0 格（还是三列）、
        // 墙与女儿墙 1 格（两列，框列在本地 x=0）；西、北两侧转过 180 度，不用挪
        Direction outward = directionOf(side);
        int shrink = side.forward() ? 1 : 0;
        int ox = outward.getStepX();
        int oz = outward.getStepZ();
        BlockPos outer = anchor.offset(ox * 2 * shrink, 0, oz * 2 * shrink);
        BlockPos skin = anchor.offset(ox * shrink, 0, oz * shrink);
        // 外挑一件；墙脚已经并进 base（见 baseOf），这里不再单独摆
        placeAll(context, pieces, piece("edge/" + run.length()), rotation, outer.offset(0, FLOOR_Y, 0), phase);
        String size = run.length() == 5 ? "small" : "large";
        List<ResourceLocation> walls = wallVariants(context.structureTemplateManager(), size, door);
        if (walls.isEmpty()) {
            return;
        }
        placeAll(
                context,
                pieces,
                walls.get(context.random().nextInt(walls.size())),
                rotation,
                skin.offset(0, 4, 0),
                phase);
        // 女儿墙照摆：两层的件在同样的两行会叠在一起——对齐时上层的墙身整列盖掉它，
        // 错开时留下的那条边正好当这层的墙顶。
        // 不能按「上方有没有房间」整格跳过——上层只要盖住一列，整面墙就会空出两行
        placeAll(context, pieces, piece("parapet/" + run.length()), rotation, skin.offset(0, 9, 0), phase);
    }

    /** 墙面件：门与无门各一套，靠件名里那一段区分；要门却没有就退回全部 */
    private List<ResourceLocation> wallVariants(StructureTemplateManager manager, String size, boolean door) {
        List<ResourceLocation> wanted = new ArrayList<>();
        for (ResourceLocation id : variants(manager, this.templates + "/wall/" + size + "_")) {
            if (id.getPath().contains("_door_") == door) {
                wanted.add(id);
            }
        }
        return wanted.isEmpty() ? variants(manager, this.templates + "/wall/" + size + "_") : wanted;
    }

    /** 这一层所有露在外面的边，一段墙一件墙件 */
    private static List<WallRun> wallRuns(List<GridFrame.Cell> cells) {
        List<WallRun> runs = new ArrayList<>();
        for (GridFrame.Cell cell : cells) {
            for (Side side : sidesOf(spanBox(cell))) {
                int covered = coveredCount(cells, cell, side);
                if (covered == side.length()) {
                    continue;
                }
                int start = 0;
                int length = side.length();
                // 只有内角件那一格挡着时（也就是凹口那一侧的边）不算被挡：整条照摆，
                // 靠凹角的两三格由凹角件自己的栏杆接上，摆个 5 格件会在凹角前缺一截
                if (covered > 0 && blockedByRoom(cells, cell, side)) {
                    int run = 0;
                    int best = 0;
                    int bestStart = 0;
                    for (int i = 0; i < side.length(); i++) {
                        if (coveredExcept(cells, cell, side.testX() + side.dx() * i, side.testZ() + side.dz() * i)) {
                            run = 0;
                            continue;
                        }
                        run++;
                        if (run > best) {
                            best = run;
                            bestStart = i - run + 1;
                        }
                    }
                    if (best < 5) {
                        if (PARTIAL.add(side.length() + "@" + side.testX() + "," + side.testZ())) {
                            DawnDuskTerminal.LOGGER.warn(
                                    "DawnDuskTerminal grid structure leaves a {}-long exposed side at {},{} without a wall",
                                    best,
                                    side.testX(),
                                    side.testZ());
                        }
                        continue;
                    }
                    start = bestStart;
                    length = 5;
                }
                runs.add(new WallRun(cell, side, start, length));
            }
        }
        return runs;
    }

    /** 绳梯列：原版的绳与梯子都算，往下延伸时同等对待 */
    private static boolean isClimbable(BlockState state) {
        return state.is(TFBlocks.ROPE.get()) || state.is(Blocks.LADDER);
    }

    /**
     * 正下方这一格、连同外面一圈里有没有更下层的房间
     *
     * <p>柱子落在格角上，下面一圈有房间时柱子会贴着人家的墙面，看起来像从墙里长出来；
     * 所以只在周围一圈都空着的时候才往下接。
     */
    private static boolean roomNear(List<List<GridFrame.Cell>> levels, int k, GridFrame.Cell cell) {
        for (int j = 0; j < k; j++) {
            for (GridFrame.Cell other : levels.get(j)) {
                if (other.x() < cell.maxX() + SUPPORT_CLEARANCE
                        && cell.x() - SUPPORT_CLEARANCE < other.maxX()
                        && other.z() < cell.maxZ() + SUPPORT_CLEARANCE
                        && cell.z() - SUPPORT_CLEARANCE < other.maxZ()) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 上一层里与这一格完全对得上的那一格：位置、体型、朝向都一样，楼梯口才落得准 */
    private static GridFrame.Cell aligned(
            List<GridFrame.Cell> level, List<List<GridFrame.Cell>> levels, int k, GridFrame.Cell cell) {
        if (k >= levels.size()) {
            return null;
        }
        for (GridFrame.Cell other : level) {
            // 位置、体型、朝向都得一样：只比 kind 的话，5×7 和 7×5 会被当成同一格，
            // 楼梯口和上面的卡口地板转不到一块，开口会落在格子外面
            if (other.x() == cell.x()
                    && other.z() == cell.z()
                    && other.kind() == cell.kind()
                    && other.w() == cell.w()
                    && other.d() == cell.d()) {
                return other;
            }
        }
        return null;
    }

    /**
     * 把上面那层整体平移，让某一格 rect 正好压在下面那一格 rect 上
     *
     * <p>每层的形状还是随机生成的，只是整层挪一段距离：楼梯两件要在两层之间对得准，
     * 靠“碰巧有一对格子对上”太难得，挪一下就有了。
     */
    private static void alignLevel(List<GridFrame.Cell> below, List<GridFrame.Cell> above) {
        for (GridFrame.Cell lower : below) {
            if (lower.kind() != GridFrame.Kind.RECT) {
                continue;
            }
            for (GridFrame.Cell upper : above) {
                // 只有同朝向的 rect 才配对：5 宽 7 深 与 7 宽 5 深是同一种 kind、同一个 x/z，
                // 但对不上楼梯口——硬配过去楼梯会落在格子外面
                if (upper.kind() != GridFrame.Kind.RECT || upper.w() != lower.w() || upper.d() != lower.d()) {
                    continue;
                }
                int dx = lower.x() - upper.x();
                int dz = lower.z() - upper.z();
                if (dx == 0 && dz == 0) {
                    return;
                }
                List<GridFrame.Cell> moved = new ArrayList<>();
                for (GridFrame.Cell cell : above) {
                    moved.add(new GridFrame.Cell(
                            cell.x() + dx, cell.z() + dz, cell.w(), cell.d(), cell.kind(), cell.turns()));
                }
                above.clear();
                above.addAll(moved);
                return;
            }
        }
    }

    /** 某一层里所有与 {@code cell} 重叠的格子 */
    private static List<GridFrame.Cell> overlaps(List<GridFrame.Cell> level, GridFrame.Cell cell) {
        List<GridFrame.Cell> out = new ArrayList<>();
        for (GridFrame.Cell other : level) {
            if (other.x() < cell.maxX()
                    && cell.x() < other.maxX()
                    && other.z() < cell.maxZ()
                    && cell.z() < other.maxZ()) {
                out.add(other);
            }
        }
        return out;
    }

    /** 某一层里压在 {@code cell} 位置上的那一格 */
    private static GridFrame.Cell covering(List<GridFrame.Cell> level, GridFrame.Cell cell) {
        for (GridFrame.Cell other : level) {
            if (other.x() < cell.maxX()
                    && cell.x() < other.maxX()
                    && other.z() < cell.maxZ()
                    && cell.z() < other.maxZ()) {
                return other;
            }
        }
        return null;
    }

    private static <T> void shuffle(List<T> list, RandomSource random) {
        for (int i = list.size() - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            T tmp = list.get(i);
            list.set(i, list.get(j));
            list.set(j, tmp);
        }
    }

    private static int clamp(long value, int min, int max) {
        return (int) Math.max(min, Math.min(max, value));
    }

    /** 房间件在装配里的角色；{@link #tag()} 是件名里那一段 */
    private enum Role {
        EMPTY("empty"),
        FURNITURE("furn"),
        CHEST("chest"),
        DROP("drop"),
        ROPE("rope"),
        STAIR("stair");

        private final String tag;

        Role(String tag) {
            this.tag = tag;
        }

        String tag() {
            return this.tag;
        }
    }

    /** 一层摆什么：房间、墙段、门与上下楼的格子 */
    private static final class Plan {
        private final List<GridFrame.Cell> cells;
        /** 落笔顺序：凹角格排在最后，它的件才盖得住邻居的框架 */
        private final List<GridFrame.Cell> order;

        private final List<WallRun> runs = new ArrayList<>();
        private final Map<GridFrame.Cell, ResourceLocation> rooms = new HashMap<>();
        private final Map<GridFrame.Cell, Rotation> rotations = new HashMap<>();
        /** 件在框架线上带家具的格子：它们的实体方块要最后再落一遍 */
        private final Set<GridFrame.Cell> border = new HashSet<>();

        private final Set<GridFrame.Cell> doorCells = new HashSet<>();
        /** 楼梯出入口的两侧：必须是空地房，走上来不会撞家具 */
        private final Set<GridFrame.Cell> forcedEmpty = new HashSet<>();

        private final Set<WallRun> doorRuns = new HashSet<>();
        private final Map<GridFrame.Cell, Role> climbs = new LinkedHashMap<>();
        private final Set<GridFrame.Cell> stairs = new HashSet<>();

        /** 先挑好的宝箱房：这一格用 {@link #chestRoom}，楼梯与门都绕开它 */
        private GridFrame.Cell chest;

        private ResourceLocation chestRoom;

        private Plan(List<GridFrame.Cell> cells) {
            this.cells = cells;
            this.order = innerCornersLast(cells);
        }
    }

    /**
     * 落笔顺序：凹角格排到序列末尾
     *
     * <p>凹角件在凹口那一角摆的方块（塔木那族是警示塔木）正好压在两条框架线上，
     * 而紧邻的两格自己也要画那两条线，谁后摆谁赢。按规划顺序摆，凹角格常常排在前面
     * （西北角就是第一格），它的角就被邻居的框架盖掉。换成最后摆，就是它盖住框架。
     */
    private static List<GridFrame.Cell> innerCornersLast(List<GridFrame.Cell> cells) {
        List<GridFrame.Cell> out = new ArrayList<>(cells.size());
        for (GridFrame.Cell cell : cells) {
            if (cell.kind() != GridFrame.Kind.INNER_CORNER) {
                out.add(cell);
            }
        }
        for (GridFrame.Cell cell : cells) {
            if (cell.kind() == GridFrame.Kind.INNER_CORNER) {
                out.add(cell);
            }
        }
        return out;
    }

    /** 一段墙：哪一格的哪条边、从第几格起、多长 */
    private record WallRun(GridFrame.Cell cell, Side side, int start, int length) {}

    /**
     * 下面没有房间时，把四个角往下接到地面
     *
     * <p>这里只摆一格柱子当锚点：往下是接到环境方块还是掉进虚空，要等地形铺好才知道，
     * 所以真正往下的延伸在 {@link #afterPlace} 里做，扫到第一个实心方块为止。
     * 同一个格角会被相邻几格各算一次，按方块坐标去重。
     */
    private void support(
            Structure.GenerationContext context,
            StructurePiecesBuilder pieces,
            GridFrame.Cell cell,
            Rotation rotation,
            BlockPos floor,
            Set<Long> posts) {
        // 柱顶接在地基最下面一格的下面
        int top = floor.getY() - 1;
        int[][] corners = {
            {cell.x(), cell.z()},
            {cell.x() + cell.w() - 1, cell.z()},
            {cell.x(), cell.z() + cell.d() - 1},
            {cell.x() + cell.w() - 1, cell.z() + cell.d() - 1},
        };
        // 凹角件是 L 形，凹口那一角在件里没有地基柱（三行都是空），
        // 跟着件的旋转映射到世界坐标后跳过，否则那一角会多出一根悬空的柱子
        BlockPos notch = null;
        if (cell.kind() == GridFrame.Kind.INNER_CORNER) {
            BlockPos local = new BlockPos(cell.w() - 1, 0, cell.d() - 1);
            BlockPos origin =
                    pieceOrigin(context, piece("base/inner_corner"), rotation, floor.offset(cell.x(), 0, cell.z()));
            notch = origin.offset(StructureTemplate.transform(local, Mirror.NONE, rotation, BlockPos.ZERO));
        }
        for (int[] corner : corners) {
            int x = floor.getX() + corner[0];
            int z = floor.getZ() + corner[1];
            if (notch != null && notch.getX() == x && notch.getZ() == z) {
                continue;
            }
            if (!posts.add(BlockPos.asLong(x, top, z))) {
                continue;
            }
            place(context, pieces, piece("post/column"), Rotation.NONE, new BlockPos(x, top, z));
        }
    }

    /** 规划坐标下的净跨度盒：探测邻居与算落笔角都按它来 */
    private static BoundingBox spanBox(GridFrame.Cell cell) {
        return new BoundingBox(cell.x(), 0, cell.z(), cell.x() + cell.w() - 1, 0, cell.z() + cell.d() - 1);
    }

    private ResourceLocation ceiling(GridFrame.Cell cell) {
        // 凹角格用自己那份：凹口那一角是空的，铺 large 那份会在这里补回一根原木
        if (cell.kind() == GridFrame.Kind.INNER_CORNER) {
            return piece("ceiling/inner_corner");
        }
        return piece("ceiling/" + airKind(cell));
    }

    /**
     * 放一件
     *
     * <p>先量一次零点处的包围盒，把件的下角对齐到 {@code min}。
     * 这里不再用包围盒裁剪：{@code PoolElementStructurePiece#place} 实际把<b>区块</b>的包围盒
     * 传给模板，自己那件的盒子只用于结构范围与区块相交判断，裁不出方块来。
     * 所以地基、女儿墙这些“只在某些层出现”的部分必须在模板里就是独立的件。
     */
    private static void place(
            Structure.GenerationContext context,
            StructurePiecesBuilder pieces,
            ResourceLocation id,
            Rotation rotation,
            BlockPos min) {
        place(context, pieces, id, rotation, min, Integer.MIN_VALUE, Integer.MAX_VALUE, BandProcessor.Phase.SOLID);
    }

    /** 整件落一遍，不按世界 y 裁：拆出来的件各自只有自己那一段 */
    private static void placeAll(
            Structure.GenerationContext context,
            StructurePiecesBuilder pieces,
            ResourceLocation id,
            Rotation rotation,
            BlockPos min,
            BandProcessor.Phase phase) {
        place(context, pieces, id, rotation, min, Integer.MIN_VALUE, Integer.MAX_VALUE, phase);
    }

    /** 同上，但只保留世界 y 落在 [minY, maxY] 里的方块（房间件的地基按层开关） */
    private static void place(
            Structure.GenerationContext context,
            StructurePiecesBuilder pieces,
            ResourceLocation id,
            Rotation rotation,
            BlockPos min,
            int minY,
            int maxY) {
        place(context, pieces, id, rotation, min, minY, maxY, BandProcessor.Phase.ALL);
    }

    /** 同上，另外交代这一遍只落空气还是只落实体方块 */
    private static void place(
            Structure.GenerationContext context,
            StructurePiecesBuilder pieces,
            ResourceLocation id,
            Rotation rotation,
            BlockPos min,
            int minY,
            int maxY,
            BandProcessor.Phase phase) {
        StructureTemplateManager manager = context.structureTemplateManager();
        if (manager.get(id).isEmpty()) {
            if (MISSING.add(id)) {
                DawnDuskTerminal.LOGGER.warn("DawnDuskTerminal grid structure is missing template {}", id);
            }
            return;
        }
        Holder<StructureProcessorList> processors =
                Holder.direct(new StructureProcessorList(List.of(weatheringProcessor(minY, maxY, phase))));
        StructurePoolElement element =
                StructurePoolElement.single(id.toString(), processors).apply(StructureTemplatePool.Projection.RIGID);
        BoundingBox zero = element.getBoundingBox(manager, BlockPos.ZERO, rotation);
        BlockPos pos = min.offset(-zero.minX(), -zero.minY(), -zero.minZ());
        BoundingBox box = zero.moved(pos.getX(), pos.getY(), pos.getZ());
        pieces.addPiece(new PoolElementStructurePiece(manager, element, pos, 0, rotation, box, LIQUID));
    }

    /** 这一栋房子当前的破损参数；不在生成过程中（例如调试）就按「不破损」处理 */
    private static BandProcessor weatheringProcessor(int minY, int maxY, BandProcessor.Phase phase) {
        Weathering weathering = WEATHERING.get();
        if (weathering == null) {
            return new BandProcessor(minY, maxY, phase);
        }
        return new BandProcessor(minY, maxY, phase, weathering.decayPermille(), weathering.webSeed());
    }

    /** 件的落笔位置：旋转后包围盒的下角对齐到 {@code min} */
    private static BlockPos pieceOrigin(
            Structure.GenerationContext context, ResourceLocation id, Rotation rotation, BlockPos min) {
        StructureTemplateManager manager = context.structureTemplateManager();
        StructurePoolElement element =
                StructurePoolElement.single(id.toString()).apply(StructureTemplatePool.Projection.RIGID);
        BoundingBox zero = element.getBoundingBox(manager, BlockPos.ZERO, rotation);
        return min.offset(-zero.minX(), -zero.minY(), -zero.minZ());
    }

    /**
     * 四条边：长度、探测起点与方向（看盒外那一格有没有别的房间）、落笔角、朝向
     *
     * <p>落笔角按「本地 x=0 落在哪一列」来定，三族件各不相同：
     * {@code wall}/{@code parapet} 是两列（框列在 x=0、外装饰在 x=1），{@code edge} 只剩最外那一列，
     * 三列的时代 {@code foot} 也在这一族里，现在脚已经并进 base，只剩墙、女儿墙、外挑三族。
     * 同一条边要两个不同的落笔角，差在沿外侧方向的挪动量：墙与女儿墙 1 格、外挑 2 格——
     * 而且只有「本地 x 与世界坐标同向」的东、南两侧才需要挪
     * （{@link Side#forward()}），西、北两侧转过 180 度，件缩列后落笔角正好不变。
     *
     * <p>落笔角与探测格都按净跨度盒算：凹口两侧的格子会把件整体挑 2 格，按规划格算会差出几格。
     *
     * <p>传进来的盒必须是规划坐标（原点为零）：探测格要和 {@link GridFrame.Cell} 比，
     * 落笔角再经 {@code origin.offset} 换回世界坐标。
     */
    private static Side[] sidesOf(BoundingBox box) {
        int minX = box.minX();
        int minZ = box.minZ();
        int maxX = box.maxX();
        int maxZ = box.maxZ();
        int y = box.minY();
        return new Side[] {
            // 东、西两侧沿 z 铺，南、北两侧沿 x 铺；探测格取盒外那一格。
            // 落笔角是「三列件（墙脚）的落笔角」：本地 x=1 压在框架线上
            new Side(maxZ - minZ + 1, maxX + 1, minZ, 0, 1, maxX - 1, minZ, y, true, Rotation.NONE),
            new Side(maxZ - minZ + 1, minX - 1, minZ, 0, 1, minX - 1, minZ, y, false, Rotation.CLOCKWISE_180),
            new Side(maxX - minX + 1, minX, maxZ + 1, 1, 0, minX, maxZ - 1, y, true, Rotation.CLOCKWISE_90),
            new Side(maxX - minX + 1, minX, minZ - 1, 1, 0, minX, minZ - 1, y, false, Rotation.COUNTERCLOCKWISE_90),
        };
    }

    /** 这条边被挡住的格里，有没有普通房间；内角件在凹口那一面的遮挡不算 */
    private static boolean blockedByRoom(List<GridFrame.Cell> cells, GridFrame.Cell self, Side side) {
        for (int i = 0; i < side.length(); i++) {
            int x = side.testX() + side.dx() * i;
            int z = side.testZ() + side.dz() * i;
            for (GridFrame.Cell other : cells) {
                if (other != self && other.kind() != GridFrame.Kind.INNER_CORNER && other.contains(x, z)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 这条边被多少格挡住：逐格查，0 就是整条面向外侧 */
    private static int coveredCount(List<GridFrame.Cell> cells, GridFrame.Cell self, Side side) {
        int covered = 0;
        for (int i = 0; i < side.length(); i++) {
            if (coveredExcept(cells, self, side.testX() + side.dx() * i, side.testZ() + side.dz() * i)) {
                covered++;
            }
        }
        return covered;
    }

    /** 本格自己的规划格不算邻居：件被挑进格内时，格内剩下的那条带不会把墙堵住 */
    private static boolean coveredExcept(List<GridFrame.Cell> cells, GridFrame.Cell self, int x, int z) {
        for (GridFrame.Cell other : cells) {
            if (other != self && other.contains(x, z)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 一条边：长度、探测起点与方向（看外面那一格有没有邻居）、落笔角、朝向
     *
     * <p>落笔角与探测格不是一个位置：东、南两侧的落笔角比探测格靠内一格，因为墙面件的框架列要压在框架线上。
     */
    /**
     * @param forward 本地 x 轴是否与世界坐标同向（东、南两侧同向）；件缩列后只有这两侧要挪落笔角
     */
    private record Side(
            int length,
            int testX,
            int testZ,
            int dx,
            int dz,
            int anchorX,
            int anchorZ,
            int y,
            boolean forward,
            Rotation rotation) {}

    private static List<ResourceLocation> variants(StructureTemplateManager manager, String prefix) {
        return VARIANTS.computeIfAbsent(prefix, key -> manager.listTemplates()
                .filter(id -> id.getNamespace().equals(DawnDuskTerminal.MOD_ID))
                .filter(id -> id.getPath().startsWith(key))
                .sorted()
                .toList());
    }

    @Override
    public StructureType<?> type() {
        return ModStructures.GRID.get();
    }
}
