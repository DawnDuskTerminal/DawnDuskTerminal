package com.dawnduskterminal.structure;

import com.dawnduskterminal.DawnDuskTerminal;
import com.dawnduskterminal.registry.ModStructures;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.ArrayList;
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
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
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
                    Codec.intRange(3, 16).fieldOf("columns").forGetter(s -> s.columns),
                    Codec.intRange(3, 16).fieldOf("rows").forGetter(s -> s.rows),
                    Codec.intRange(1, 4).fieldOf("storeys").forGetter(s -> s.storeys),
                    Codec.intRange(-128, 320).fieldOf("y_min").forGetter(s -> s.yMin),
                    Codec.intRange(-128, 320).fieldOf("y_max").forGetter(s -> s.yMax))
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

    /** 带箱子的那间房件：箱子是件里自带的方块，`LootTable` 写在件里 */
    private static final String CHEST_ROOM = "room/rect_furn_06";

    /** 上一层比下一层的列数/行数各偏多少：正态分布的标准差，取 1 就是大多数时候只差一格 */
    private static final double STOREY_SPREAD = 1.0;

    /** 往下接柱子时要求正下方这一圈（外扩这么多格）里没有房间，免得柱子贴着邻居的墙面 */
    private static final int SUPPORT_CLEARANCE = 3;

    /** 门、上下楼格子的数量：每这么多格一个，至少一个 */
    private static final float DOOR_PER_CELLS = 10.0f;

    private static final float CLIMB_PER_CELLS = 5.0f;

    /** 非必需格里空地的占比：0.75 就是四分之一是家具房 */
    private static final float EMPTY_SHARE = 0.75f;

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
    /** 层数上限；实际层数按权重在 1..上限里挑 */
    private final int storeys;
    /** 生成高度范围：这栋房子是地下的，y_min/y_max 之间随机取一层的地板高度 */
    private final int yMin;

    private final int yMax;

    /** 每栋房子一颗：结构缺失率（千分之几）与蜘蛛网盐 */
    private static final ThreadLocal<Weathering> WEATHERING = new ThreadLocal<>();

    public GridStructure(StructureSettings settings, int columns, int rows, int storeys, int yMin, int yMax) {
        super(settings);
        this.columns = columns;
        this.rows = rows;
        this.storeys = storeys;
        this.yMin = Math.min(yMin, yMax);
        this.yMax = Math.max(yMin, yMax);
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
    private static void extendSupports(
            WorldGenLevel level, BoundingBox box, PiecesContainer pieces, ChunkPos chunkPos) {
        for (StructurePiece piece : pieces.pieces()) {
            if (!(piece instanceof PoolElementStructurePiece pool)) {
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
        // 绳梯件：盒子里那一列绳/梯往下铺到第一个实心方块，否则底层那根绳子吊不到地面
        if (element.contains("shaft/")) {
            ropeDown(level, pieceBox, minX, maxX, minZ, maxZ);
            return;
        }
        // 柱子件往下接：绳梯的 climb/rope 也是 1x1，按几何判断会把它接成一根孤柱
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
     * 把绳梯那一列往下铺到第一个实心方块：盒子里已经是绳/梯的格就是那一列
     *
     * <p>同样是「先探再落笔」：一路往下探到第一个实心方块为止，中途遇到液体就不接，
     * 探到 {@link #SUPPORT_MAX_DROP} 格还没落地也不接——不往水里、岩浆里、虚空里吊绳子。
     *
     * <p>搜索范围收在 {@code minX..maxX} / {@code minZ..maxZ}（当前区块那一截）：读到区块外会抛异常，
     * 见 {@link #afterPlace}。往下那一截与搜索格同列，整根柱都在当前区块里，不算越界。
     */
    private static void ropeDown(WorldGenLevel level, BoundingBox box, int minX, int maxX, int minZ, int maxZ) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int y = box.minY(); y <= box.maxY(); y++) {
                    BlockState state = level.getBlockState(cursor.set(x, y, z));
                    if (!state.is(TFBlocks.ROPE.get()) && !state.is(Blocks.LADDER)) {
                        continue;
                    }
                    int top = Math.min(box.minY() - 1, box.maxY());
                    int landing = ropeLandingY(level, x, z, top);
                    if (landing != NO_LANDING) {
                        for (int down = top; down >= landing; down--) {
                            level.setBlock(cursor.set(x, down, z), state, 2);
                        }
                    }
                    return;
                }
            }
        }
    }

    /**
     * 绳梯往下探到哪一格，规则与 {@link #landingY} 同：探到实心才算落地
     *
     * <p>绳/梯自身算能走：`placeShafts` 在绳梯件正下方补过一格 `climb/rope`，
     * 只认空气的话，第一格就撞上它，绳子再也铺不下去。
     *
     * @return 绳梯应该铺到的最低 y（含）；{@link #NO_LANDING} 表示这一条不接
     */
    private static int ropeLandingY(WorldGenLevel level, int x, int z, int top) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        int limit = Math.max(level.getMinBuildHeight(), top - SUPPORT_MAX_DROP);
        for (int y = top; y >= limit; y--) {
            cursor.set(x, y, z);
            BlockState state = level.getBlockState(cursor);
            if (state.is(TFBlocks.ROPE.get()) || state.is(Blocks.LADDER)) {
                continue;
            }
            if (!state.getFluidState().isEmpty()) {
                return NO_LANDING;
            }
            if (!state.canBeReplaced()) {
                return y >= top ? NO_LANDING : y + 1;
            }
        }
        return NO_LANDING;
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
        // 宝箱房保底：整座结构至少一间 `room/rect_furn_06`（箱子是那间房自带的方块，这里不凭空造箱子）。
        // 必须在落笔之前定，改了 `plan.rooms` 之后件才会按新的房间名摆
        boolean forcedChestRoom = ensureChestRoom(manager, plans);
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
        placeShafts(context, pieces, plans, origin);
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
        // 一行摘要：世界生成出的结构一旦"缺层"，先比这条日志与另一端的日志
        // （层数、原点、包围盒），能立刻分清是「件表就不一样」还是「件一样但没落上」
        DawnDuskTerminal.LOGGER.info(
                "DawnDuskTerminal grid structure at chunk {}: {} level(s), origin y={}, bbox {}, decay={}‰, cobwebs={}, chestRoom={}",
                context.chunkPos(),
                plans.size(),
                origin.getY(),
                pieces.getBoundingBox(),
                WEATHER_DECAY_PERMILLE,
                webSeed == 0L ? "off" : "on",
                forcedChestRoom ? "forced" : "planned");
    }

    /**
     * 宝箱房保底：整座结构至少有一格用 `room/rect_furn_06`（那间房里带一只箱子）
     *
     * <p>箱子不是另外摆的：它是这间房件自带的方块，`LootTable` 写在件里。
     * 装配时房间是从 `room/<体型>_<角色>_<n>` 变体里随机挑的，格子少、层数少的时候可能一次都没挑中它，
     * 于是整座房子一只箱子也没有——这里在那之后再补一次：找一格「rect 体型 + 家具房」的，
     * 把它换成宝箱房。同体型同角色、朝向不变，所以地板、天花板、墙与门都不受影响。
     *
     * @return 有没有真的动过（日志用）
     */
    private static boolean ensureChestRoom(StructureTemplateManager manager, List<Plan> plans) {
        ResourceLocation chestRoom = DawnDuskTerminal.id(CHEST_ROOM);
        if (manager.get(chestRoom).isEmpty()) {
            if (MISSING.add(chestRoom)) {
                DawnDuskTerminal.LOGGER.warn("DawnDuskTerminal grid structure is missing template {}", chestRoom);
            }
            return false;
        }
        for (Plan plan : plans) {
            for (ResourceLocation room : plan.rooms.values()) {
                if (chestRoom.equals(room)) {
                    return false;
                }
            }
        }
        // 优先换一间「rect 家具房」；没有就换任意一间没被楼梯/门/跨层占用的 rect 房
        for (int pass = 0; pass < 2; pass++) {
            for (Plan plan : plans) {
                for (GridFrame.Cell cell : plan.cells) {
                    ResourceLocation room = plan.rooms.get(cell);
                    if (room == null
                            || cell.kind() != GridFrame.Kind.RECT
                            || room.getPath().contains("_stair")
                            || plan.climbs.containsKey(cell)
                            || plan.stairs.contains(cell)
                            || plan.doorCells.contains(cell)) {
                        continue;
                    }
                    if (pass == 0 && !room.getPath().contains("_furn_")) {
                        continue;
                    }
                    plan.rooms.put(cell, chestRoom);
                    if (hasBorderFurniture(manager, chestRoom)) {
                        plan.border.add(cell);
                    } else {
                        plan.border.remove(cell);
                    }
                    return true;
                }
            }
        }
        return false;
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
        // 上一层的楼梯口正落在这一层的哪一格：那几格直接换成 stairtop 件，
        // 也不摆门、不当空地——楼梯口要的是顶层那半间地板
        // 上一层的楼梯口正好落在这一层的哪一格（位置、体型、朝向都对得上）：那格换成 stairtop 件。
        // 找不到对得上的格子说明这一层没有楼梯（alignLevel 已经挪整齐一对，抓不到就是没有 rect）
        Map<GridFrame.Cell, ResourceLocation> arrivals = new HashMap<>();
        Map<GridFrame.Cell, Rotation> arrivalRotations = new HashMap<>();
        if (k > 0) {
            for (GridFrame.Cell stair : plans.get(k - 1).stairs) {
                GridFrame.Cell above = aligned(cells, levels, k, stair);
                if (above != null) {
                    ResourceLocation top =
                            stairTop(manager, plans.get(k - 1).rooms.get(stair));
                    if (top != null) {
                        arrivals.put(above, top);
                        // 朝向沿用楼梯那一格：上下两格可能一个横向一个竖向，各转各的洞口就对不上
                        arrivalRotations.put(above, plans.get(k - 1).rotations.get(stair));
                    }
                    continue;
                }
                // 对不上就不放楼梯（alignLevel 已经把某对 rect 挪整齐，抓不到的说明这层没有楼梯）
                continue;
            }
        }

        // 上下楼：必修项先放——最下面那个上层至少一条绳/梯，顶层以外的每层至少一部楼梯。
        // 其余名额只补楼梯与洞口：绳子和梯子只出现在最下面那个上层，再往上都用楼梯
        List<GridFrame.Cell> order2 = new ArrayList<>(cells);
        shuffle(order2, random);
        int climbs = clamp(Math.round(cells.size() / (float) CLIMB_PER_CELLS), 1, cells.size());
        boolean up = k + 1 < levels.size();
        // 先摆楼梯再摆绳梯：stairs 是唯一「往上走」的路，而且它必须落在和上一层对齐的那格 rect 上；
        // 那格要是先被绳梯占了，这一层就再也接不上了
        if (up) {
            climb(manager, levels, plans, noClimb, forceEmpty, k, order2, plan, List.of(Role.STAIR), random);
        }
        // 只有底层必修一部绳梯；上层想吊只能靠挑空探出去的格子，由 extras 去补
        if (k == 0) {
            climb(
                    manager,
                    levels,
                    plans,
                    noClimb,
                    forceEmpty,
                    k,
                    order2,
                    plan,
                    List.of(Role.ROPE, Role.LADDER),
                    random);
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
                    || plan.climbs.containsKey(cell)
                    || arrivals.containsKey(cell)) {
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
            extras.add(Role.LADDER);
        }
        if (!extras.isEmpty()) {
            for (GridFrame.Cell cell : order2) {
                if (plan.climbs.size() >= climbs) {
                    break;
                }
                if (plan.doorCells.contains(cell) || plan.climbs.containsKey(cell) || arrivals.containsKey(cell)) {
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
            room = arrivals.getOrDefault(cell, pickRoom(manager, plan, cell, random));
            plan.rooms.put(cell, room);
            // 家具靠 east，朝向按这一面去贴格子真正露在外面的那面墙；大格没有外墙，随机转一个方向
            Rotation rotation = orient(plan, cell);
            if (cell.kind() == GridFrame.Kind.LARGE) {
                rotation = ROTATIONS[(rotation.ordinal() + random.nextInt(4)) & 3];
            }
            plan.rotations.put(cell, arrivalRotations.getOrDefault(cell, rotation));
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
     * 楼梯：件在上一层还有一半（`_stairtop_`），所以上一层必须有一格位置、体型、朝向都对得上；
     * 那一格直接换成楼梯口的地板（中间带开口），于是它不摆门、也不当空地。
     */
    /**
     * 在这一层里挑第一个空位放指定角色的跨层房间
     *
     * @return 有没有放成
     */
    private static boolean climb(
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

    private static boolean placeClimb(
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
            if (id == null || !id.getPath().contains("_empty_")) {
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
        // 梯子靠墙，下层不是同一间房就贴不住
        boolean same = below.size() == 1
                && below.get(0).kind() == cell.kind()
                && below.get(0).turns() == cell.turns();
        if (empty && same && !roomsOf(manager, cell, Role.LADDER).isEmpty()) {
            possible.add(Role.LADDER);
        }
        List<ResourceLocation> stairs = List.of();
        if (k + 1 < levels.size()) {
            // 先找到条件符合的那一格（位置、体型、朝向都对得上），再决定放楼梯
            // 对得上就用整间 stairtop；对不上就只用它的地板层，但上一层压着的格子必须能当空地
            stairs = roomsOf(manager, cell, Role.STAIR);
            if (!stairs.isEmpty() && k + 1 < levels.size() && aligned(levels.get(k + 1), levels, k + 1, cell) != null) {
                possible.add(Role.STAIR);
            }
        }
        possible.retainAll(allowed);
        if (possible.isEmpty()) {
            return false;
        }
        Role role = possible.get(random.nextInt(possible.size()));
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

    /** 同一层里贴着这一格、往 (dx, dz) 方向的那一格；相邻两格共用一条框架线，所以步进是边长减一 */
    private static GridFrame.Cell neighbour(List<GridFrame.Cell> level, GridFrame.Cell cell, int dx, int dz) {
        int x = cell.x() + dx * (cell.w() - 1);
        int z = cell.z() + dz * (cell.d() - 1);
        for (GridFrame.Cell other : level) {
            if (other.x() == x && other.z() == z) {
                return other;
            }
        }
        return null;
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

    /** 一格的房间件：门后与楼梯顶上必须是空地，剩下的格子一半空地、一半家具 */
    private static ResourceLocation pickRoom(
            StructureTemplateManager manager, Plan plan, GridFrame.Cell cell, RandomSource random) {
        if (cell.kind() == GridFrame.Kind.INNER_CORNER) {
            return DawnDuskTerminal.id("room/inner_corner");
        }
        ResourceLocation climbing = plan.rooms.get(cell);
        if (climbing != null) {
            return climbing;
        }
        boolean mustBeEmpty = plan.doorCells.contains(cell) || plan.forcedEmpty.contains(cell);
        Role role = mustBeEmpty || random.nextFloat() < EMPTY_SHARE ? Role.EMPTY : Role.FURNITURE;
        List<ResourceLocation> fit = roomsOf(manager, cell, role);
        if (fit.isEmpty()) {
            fit = roomsOf(manager, cell, Role.EMPTY);
        }
        if (fit.isEmpty()) {
            return DawnDuskTerminal.id("room/" + cell.kind().path());
        }
        return fit.get(random.nextInt(fit.size()));
    }

    /**
     * 某个体型的某个角色的件
     *
     * <p>角色写在件名里（{@code room/small_rope_03}）：体型、角色、序号三段。
     * 这样不用去解析模板内容就知道一件是空地、绳、梯、楼梯还是普通家具。
     */
    private static List<ResourceLocation> roomsOf(StructureTemplateManager manager, GridFrame.Cell cell, Role role) {
        return variants(manager, "room/" + cell.kind().path() + "_" + role.tag() + "_");
    }

    /** 摆这一层的房间件；{@code phase} 决定只落空气还是只落实体方块 */
    private static void placeCells(
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
        for (GridFrame.Cell cell : plan.cells) {
            ResourceLocation id = plan.rooms.get(cell);
            if (id == null) {
                continue;
            }
            boolean near = roomNear(levels, k, cell);
            Rotation rotation = plan.rotations.get(cell);
            String path = id.getPath().substring("room/".length());
            BlockPos cellFloor = floor.offset(cell.x(), 0, cell.z());
            // 地板与天花板共用 plane（ceiling/<体型>）；带开口的格子换成卡口件
            ResourceLocation plane = planeOf(manager, cell, id);
            // 共用的地板/天花板件按格子自己的朝向转：rect 是 5 宽 7 深，7 宽 5 深的格子不转就只盖住一半。
            // 自己的卡口地板要跟着房间转，洞口才对得上
            Rotation planeRotation = plane.getPath().startsWith("floor/") ? rotation : ROTATIONS[cell.turns()];
            placeAll(context, pieces, plane, planeRotation, cellFloor.offset(0, FLOOR_Y, 0), phase);
            // 绳梯接下去那三格：落在下层室内最上面三行
            if (shaft(id)) {
                placeAll(context, pieces, DawnDuskTerminal.id("shaft/" + path), rotation, cellFloor, phase);
            }
            // 地基只在地基露出来的时候摆
            ResourceLocation ground = near ? null : baseOf(cell, id);
            if (ground != null) {
                placeAll(context, pieces, ground, rotation, cellFloor, phase);
            }
            if (cell.kind() == GridFrame.Kind.INNER_CORNER) {
                placeAll(
                        context,
                        pieces,
                        DawnDuskTerminal.id("parapet/inner_corner"),
                        rotation,
                        cellFloor.offset(0, PARAPET_Y, 0),
                        phase);
            }
            // 房间件只剩家具与内空，地板已经在上面单独摆过了
            placeAll(context, pieces, id, rotation, cellFloor.offset(0, ROOM_Y, 0), phase);
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
    private static void clearLevel(
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
            ResourceLocation air = DawnDuskTerminal.id("air/" + airKind(cell));
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
     * 凹角的凹口：把那一列（局部 6,0,6 到 6,4,6）擦成空气
     *
     * <p>凹口在凹角件自己的格子内部，写得到这里的也只有这一格自己的件：天花板件（`ceiling/large` 的角上
     * 就是一根原木）、女儿墙件。它们比房间件落笔晚，会把凹角件里存的空气盖回去，
     * 所以整个结构摆完之后再统一擦一次，谁后摆都一样。范围从这个格子的第一行楼面一直到女儿墙顶。
     */
    private static void placeNotches(
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
            place(
                    context,
                    pieces,
                    DawnDuskTerminal.id("air/notch"),
                    Rotation.NONE,
                    originOf.offset(at),
                    Integer.MIN_VALUE,
                    Integer.MAX_VALUE,
                    BandProcessor.Phase.AIR);
        }
    }

    /** 地板件：有 floor/<房间件名> 就用它（卡口、楼梯口、凹角），否则用与天花板共用的 plane */
    private static ResourceLocation planeOf(
            StructureTemplateManager manager, GridFrame.Cell cell, ResourceLocation room) {
        ResourceLocation own = DawnDuskTerminal.id("floor/" + room.getPath().substring("room/".length()));
        return manager.get(own).isPresent() ? own : ceiling(cell);
    }

    /** 地基件：按体型共用；楼梯格没有往下跨越的结构，不摆地基 */
    private static ResourceLocation baseOf(GridFrame.Cell cell, ResourceLocation room) {
        if (room.getPath().contains("_stair")) {
            return null;
        }
        return switch (cell.kind()) {
            case RECT -> DawnDuskTerminal.id("base/rect");
            case SMALL -> DawnDuskTerminal.id("base/small");
            case INNER_CORNER -> DawnDuskTerminal.id("base/inner_corner");
            default -> null;
        };
    }

    /** 绳梯房间：y=0..2 是绳/梯接下去的那三格 */
    private static boolean shaft(ResourceLocation id) {
        String path = id.getPath();
        return path.contains("_rope_") || path.contains("_ladder_");
    }

    private static String airKind(GridFrame.Cell cell) {
        return cell.kind() == GridFrame.Kind.INNER_CORNER
                ? "large"
                : cell.kind().path();
    }

    /** 天花板与墙：同样分两遍落，墙里的空气要能把装饰面背后的缝挖开 */
    private static void placeShell(
            Structure.GenerationContext context,
            StructurePiecesBuilder pieces,
            Plan plan,
            List<List<GridFrame.Cell>> levels,
            int k,
            BlockPos origin,
            BandProcessor.Phase phase) {
        BlockPos floor = origin.offset(0, k * STOREY_HEIGHT, 0);
        for (GridFrame.Cell cell : plan.cells) {
            ResourceLocation id = plan.rooms.get(cell);
            if (id == null) {
                continue;
            }
            // 楼梯格的 y=8 是楼梯口，上一层的地板就落在同一个平面上，这里不摆天花板
            if (true) {
                // 天花板件只有 y=8 这一层，直接摆在地板行上方的第 8 行
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
        }
        for (WallRun run : plan.runs) {
            placeWall(context, pieces, levels, k, run, floor, plan.doorRuns.contains(run), phase);
        }
    }

    /** 悬空的地基往下接柱子 */
    private static void placeSupports(
            Structure.GenerationContext context,
            StructurePiecesBuilder pieces,
            Plan plan,
            List<List<GridFrame.Cell>> levels,
            int k,
            BlockPos origin,
            Set<Long> posts) {
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
    private static void placeBorder(
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
                    floor.offset(cell.x(), ROOM_Y, cell.z()),
                    Integer.MIN_VALUE,
                    Integer.MAX_VALUE,
                    BandProcessor.Phase.SOLID);
        }
    }

    /**
     * 这件在框架线上有没有家具
     *
     * <p>框架线是格子最外那一圈，四角是原木框架、下面还有地板，只查地板以上五行、贴着墙那几列的边上：
     * 那里出现非空方块就是摆在边框上的家具。模板里的空气能数出来，
     * 所以“非空”= 不在空气集合里；房间件把整格都存满了，这个判断成立。
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
            for (int y = 1; y <= 4; y++) {
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
    private static void placeWall(
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
        // 外挑与墙脚各一件：墙脚只在地基露出来（下面没有房间）时摆
        placeAll(
                context,
                pieces,
                DawnDuskTerminal.id("edge/" + run.length()),
                rotation,
                anchor.offset(0, FLOOR_Y, 0),
                phase);
        if (!near) {
            placeAll(
                    context,
                    pieces,
                    DawnDuskTerminal.id("foot/" + run.length()),
                    rotation,
                    anchor.offset(0, 1, 0),
                    phase);
        }
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
                anchor.offset(0, 4, 0),
                phase);
        // 女儿墙照摆：上一层的墙身在同样的两行是「内列空气 / 框架列实心 / 外列空气」，
        // 对齐的那条边被它盖掉，没对齐的那条边留着当这层的墙顶。
        // 不能按「上方有没有房间」整格跳过——上层只要盖住一列，整面墙就会空出两行
        placeAll(
                context,
                pieces,
                DawnDuskTerminal.id("parapet/" + run.length()),
                rotation,
                anchor.offset(0, 9, 0),
                phase);
    }

    /** 墙面件：门与无门各一套，靠件名里那一段区分；要门却没有就退回全部 */
    private static List<ResourceLocation> wallVariants(StructureTemplateManager manager, String size, boolean door) {
        List<ResourceLocation> wanted = new ArrayList<>();
        for (ResourceLocation id : variants(manager, "wall/" + size + "_")) {
            if (id.getPath().contains("_door_") == door) {
                wanted.add(id);
            }
        }
        return wanted.isEmpty() ? variants(manager, "wall/" + size + "_") : wanted;
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

    /**
     * 绳梯往下接到下层地板
     *
     * <p>绳梯件的 y=0..2 落在下层室内的最上面三格，离下层地板还差一格。
     * 这里按件里绳/梯所在的那一列往下补够到地板（{@code climb/rope}、{@code climb/ladder}）。
     */
    private static void placeShafts(
            Structure.GenerationContext context, StructurePiecesBuilder pieces, List<Plan> plans, BlockPos origin) {
        StructureTemplateManager manager = context.structureTemplateManager();
        for (int k = 1; k < plans.size(); k++) {
            BlockPos floor = origin.offset(0, k * STOREY_HEIGHT, 0);
            for (Map.Entry<GridFrame.Cell, Role> entry : plans.get(k).climbs.entrySet()) {
                Role role = entry.getValue();
                if (role != Role.ROPE && role != Role.LADDER) {
                    continue;
                }
                GridFrame.Cell cell = entry.getKey();
                ResourceLocation room = plans.get(k).rooms.get(cell);
                String path = room.getPath().substring("room/".length());
                BlockPos local = shaft(manager, DawnDuskTerminal.id("shaft/" + path), role);
                if (local == null) {
                    continue;
                }
                Rotation rotation = plans.get(k).rotations.get(cell);
                // 件的落笔位置是「旋转后包围盒的下角对齐到 min」，绳梯列必须用同一个原点换算，
                // 否则转过 90 度会整体错开
                BlockPos cellFloor = floor.offset(cell.x(), 0, cell.z());
                BlockPos originOf = pieceOrigin(context, DawnDuskTerminal.id("shaft/" + path), rotation, cellFloor);
                BlockPos at = StructureTemplate.transform(local, Mirror.NONE, rotation, BlockPos.ZERO);
                String piece = role == Role.ROPE ? "climb/rope" : "climb/ladder";
                place(
                        context,
                        pieces,
                        DawnDuskTerminal.id(piece),
                        rotation,
                        new BlockPos(originOf.getX() + at.getX(), originOf.getY() - 1, originOf.getZ() + at.getZ()));
            }
        }
    }

    /** 件里绳/梯所在的那一列（局部坐标） */
    private static BlockPos shaft(StructureTemplateManager manager, ResourceLocation id, Role role) {
        if (id == null) {
            return null;
        }
        StructureTemplate template = manager.get(id).orElse(null);
        if (template == null) {
            return null;
        }
        List<StructureTemplate.StructureBlockInfo> found = template.filterBlocks(
                BlockPos.ZERO, new StructurePlaceSettings(), role == Role.ROPE ? TFBlocks.ROPE.get() : Blocks.LADDER);
        return found.isEmpty() ? null : found.get(0).pos();
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

    /** 楼梯件在上一层的那一半：件名里把 {@code _stair_} 换成 {@code _stairtop_} */
    private static ResourceLocation stairTop(StructureTemplateManager manager, ResourceLocation stair) {
        if (stair == null) {
            return null;
        }
        ResourceLocation top = DawnDuskTerminal.id(stair.getPath().replace("_stair_", "_stairtop_"));
        return manager.get(top).isPresent() ? top : null;
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
        ROPE("rope"),
        LADDER("ladder"),
        DROP("drop"),
        STAIR("stair"),
        FURNITURE("furn");

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

        private Plan(List<GridFrame.Cell> cells) {
            this.cells = cells;
        }
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
    private static void support(
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
            BlockPos origin = pieceOrigin(
                    context, DawnDuskTerminal.id("base/inner_corner"), rotation, floor.offset(cell.x(), 0, cell.z()));
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
            place(context, pieces, DawnDuskTerminal.id("post/column_1"), Rotation.NONE, new BlockPos(x, top, z));
        }
    }

    /** 规划坐标下的净跨度盒：探测邻居与算落笔角都按它来 */
    private static BoundingBox spanBox(GridFrame.Cell cell) {
        return new BoundingBox(cell.x(), 0, cell.z(), cell.x() + cell.w() - 1, 0, cell.z() + cell.d() - 1);
    }

    private static ResourceLocation ceiling(GridFrame.Cell cell) {
        // 凹角格用自己那份：凹口那一角是空的，铺 large 那份会在这里补回一根原木
        if (cell.kind() == GridFrame.Kind.INNER_CORNER) {
            return DawnDuskTerminal.id("ceiling/inner_corner");
        }
        return DawnDuskTerminal.id("ceiling/" + airKind(cell));
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
     * <p>墙件是三列：本地 x=0 是内侧面（朝房间），x=1 压在框架线上，x=2 是外装饰层。落笔角与探测格都按净跨度盒算：
     * 凹口两侧的格子会把件整体挑 2 格，按规划格算会差出几格。
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
            // 东、南两侧的落笔角要比另两侧往里收一格：件有内、框、外三列，转过 0/90 度后内侧落在低坐标那一头
            new Side(maxZ - minZ + 1, maxX + 1, minZ, 0, 1, maxX - 1, minZ, y, Rotation.NONE),
            new Side(maxZ - minZ + 1, minX - 1, minZ, 0, 1, minX - 1, minZ, y, Rotation.CLOCKWISE_180),
            new Side(maxX - minX + 1, minX, maxZ + 1, 1, 0, minX, maxZ - 1, y, Rotation.CLOCKWISE_90),
            new Side(maxX - minX + 1, minX, minZ - 1, 1, 0, minX, minZ - 1, y, Rotation.COUNTERCLOCKWISE_90),
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
    private record Side(
            int length, int testX, int testZ, int dx, int dz, int anchorX, int anchorZ, int y, Rotation rotation) {}

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
