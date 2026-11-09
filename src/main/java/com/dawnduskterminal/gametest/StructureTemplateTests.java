package com.dawnduskterminal.gametest;

import com.dawnduskterminal.DawnDuskTerminal;
import com.dawnduskterminal.structure.BandProcessor;
import com.dawnduskterminal.structure.GridPlacer;
import com.dawnduskterminal.structure.GridStructure;
import com.dawnduskterminal.structure.StructureExclusions;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.pieces.PiecesContainer;
import net.minecraft.world.level.levelgen.structure.pools.StructurePoolElement;
import net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool;
import net.minecraft.world.level.levelgen.structure.templatesystem.LiquidSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessorType;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import twilightforest.init.TFBlocks;

/**
 * 结构件模板在 jar 里读得出来，`afterPlace` 也只动当前区块那一截
 *
 * <p>读不出来的件是静默的：{@code StructureTemplateManager.get} 把异常吞成空的 {@code Optional}，
 * {@code GridStructure.place} 只写一行日志就跳过那一件。
 * 症状是一整类件（房间、墙、地基，或者只有凹口那一列）在世界里根本不出现，
 * 而不是构建失败或者启动报错——{@code tools/structure} 的校验脚本也只看内存里的模板、不看压缩格式。
 * 目前踩过的一次是 {@code air/notch.nbt} 存成了未压缩的 NBT：原版 {@code readStructure} 走
 * {@code NbtIo.readCompressed}，读到裸 NBT 就抛 {@code ZipException: Not in GZIP format}。
 * 结构方块 SAVE 写出来的本来就是 gzip（{@code StructureTemplateManager.save} 用
 * {@code NbtIo.writeCompressed}），拿脚本另存件的时候必须一样压缩。
 *
 * <p>{@code afterPlace} 那条更凶：它在结构覆盖的每个区块上各跑一遍，交进来的 {@code box} 只有当前区块，
 * 而世界生成期手里的 {@code level} 是 {@code WorldGenRegion}。扫整件包围盒就会读到区块外，
 * 抛 {@code IllegalStateException: Requested chunk unavailable during world generation}，
 * 整个区块的生成被打断，单人游戏里表现为被集成服务端踢回主菜单。
 * 这里用一个只在 {@code box} 之内放行的代理包住真的 {@code level}：
 * 真要读到区块外，测试当场失败，而不是等到玩家跑图时才发现。
 */
@GameTestHolder("dawnduskterminal")
@PrefixGameTestTemplate(false)
public final class StructureTemplateTests {
    /** 件所在的家族目录：一个目录就是一整套件，结构 JSON 的 {@code templates} 指的也是它 */
    private static final String FAMILY = "grid_rooms/";

    /** 这件是 1×7×1 的纯空气，凹口那一列靠它擦 */
    private static final String NOTCH = FAMILY + "air/notch";

    /** 宝箱测试的样本量与固定种子：定死才不会随测试世界的种子飘 */
    private static final int CHEST_SAMPLES = 24;

    /** 带箱子的那间房件：箱子是件里自带的方块（按子串认，路径前缀在这里无关） */
    private static final String CHEST_ROOM = "room/rect/chest";

    private static final long CHEST_SAMPLE_SEED = 0x5EEDC0DEL;

    /** 绳梯件的绳在件中间的局部 (2,0..2,2) 那一列 */
    private static final int SHAFT_ROPE_X = 2;

    private static final int SHAFT_ROPE_Z = 2;

    /** 件往区块外挑出两格：包围盒跨过边界，绳那一列还落在区块里 */
    private static final int SHAFT_OVERHANG = 2;

    @GameTest(template = "ddt_empty")
    public static void everyPieceTemplateLoads(GameTestHelper helper) {
        StructureTemplateManager manager = helper.getLevel().getStructureManager();
        List<ResourceLocation> ids = manager.listTemplates()
                .filter(id -> id.getNamespace().equals(DawnDuskTerminal.MOD_ID))
                .sorted()
                .toList();
        helper.assertTrue(!ids.isEmpty(), "data/dawnduskterminal/structure 下一件模板都没列出来，件的路径或后缀变了");
        for (ResourceLocation id : ids) {
            helper.assertTrue(manager.get(id).isPresent(), "结构件 " + id + " 读不出来，多半不是 gzip 压缩的 NBT");
        }
        helper.succeed();
    }

    @GameTest(template = "ddt_empty")
    public static void notchIsAOneBySevenColumn(GameTestHelper helper) {
        StructureTemplateManager manager = helper.getLevel().getStructureManager();
        ResourceLocation id = DawnDuskTerminal.id(NOTCH);
        helper.assertTrue(manager.get(id).isPresent(), "结构件 " + id + " 读不出来");
        StructureTemplate notch = manager.get(id).orElseThrow();
        Vec3i size = notch.getSize();
        helper.assertTrue(size.equals(new Vec3i(1, 7, 1)), "结构件 " + NOTCH + " 应为 1×7×1（凹口那一列，楼面到女儿墙顶），实际 " + size);
        helper.succeed();
    }

    /**
     * 结构件的 `BandProcessor` 必须把自己的窗口与相位一起写进存档
     *
     * <p>件是跟着结构 start 一起存进区块的。玩家分次探索时，start 是从磁盘读回来再落笔的；
     * 只要编解码器漏掉 `min_y` / `max_y` / `phase`，读回来就全变成「无窗口 + ALL」——
     * 清空件会多写两行空气、把上一层的天花板擦掉，同一份种子在不同探索顺序下长出的房子就不一样。
     * 这条测试直接走原版的 {@code StructureProcessorType.SINGLE_CODEC}，与区块 NBT 的那条路一致。
     */
    @GameTest(template = "ddt_empty")
    public static void bandProcessorKeepsItsWindowAcrossSerialization(GameTestHelper helper) {
        BandProcessor original = new BandProcessor(-40, -30, BandProcessor.Phase.AIR);
        Tag encoded = StructureProcessorType.SINGLE_CODEC
                .encodeStart(NbtOps.INSTANCE, original)
                .getOrThrow(error -> new IllegalStateException("band processor 写不出来：" + error));
        StructureProcessor readBack = StructureProcessorType.SINGLE_CODEC
                .parse(NbtOps.INSTANCE, encoded)
                .getOrThrow(error -> new IllegalStateException("band processor 读不回来：" + error));
        helper.assertTrue(readBack instanceof BandProcessor, "读回来应当是 BandProcessor，实际 " + readBack.getClass());
        BandProcessor band = (BandProcessor) readBack;
        helper.assertTrue(
                band.minY() == -40 && band.maxY() == -30,
                "读回来 y 窗口应为 -40..-30，实际 " + band.minY() + ".." + band.maxY() + "（编码结果 " + encoded + "）");
        helper.assertTrue(band.phase() == BandProcessor.Phase.AIR, "读回来相位应为 AIR，实际 " + band.phase());
        // 旧存档里只写了 processor_type、没有参数：要能按默认值读回，不能把整座结构丢掉
        CompoundTag legacy = new CompoundTag();
        legacy.putString("processor_type", DawnDuskTerminal.MOD_ID + ":band");
        StructureProcessor old = StructureProcessorType.SINGLE_CODEC
                .parse(NbtOps.INSTANCE, legacy)
                .getOrThrow(error -> new IllegalStateException("旧存档的 band processor 读不回来：" + error));
        helper.assertTrue(
                old instanceof BandProcessor band2
                        && band2.minY() == Integer.MIN_VALUE
                        && band2.maxY() == Integer.MAX_VALUE
                        && band2.phase() == BandProcessor.Phase.ALL,
                "旧存档缺参数时应当按「无窗口 + ALL」读回，实际 " + old);
        helper.succeed();
    }

    /**
     * 破损：随机缺失方块，其中四分之一的位置换成蜘蛛网
     *
     * <p>缺失率给满（千分之千）时每个实体方块都该被处理掉，约四分之一变成蜘蛛网；
     * 带 nbt 的方块（宝箱）不参与缺失；缺失率为 0 时一块都不该动。
     */
    @GameTest(template = "ddt_empty")
    public static void bandProcessorDecaysAndWebs(GameTestHelper helper) {
        BandProcessor weathered =
                new BandProcessor(Integer.MIN_VALUE, Integer.MAX_VALUE, BandProcessor.Phase.SOLID, 1000, 0x5DEECE66DL);
        int removed = 0;
        int webs = 0;
        for (int i = 0; i < 400; i++) {
            BlockPos pos = helper.absolutePos(new BlockPos(1 + i % 20, 3, 1 + i / 20));
            StructureTemplate.StructureBlockInfo raw =
                    new StructureTemplate.StructureBlockInfo(pos, Blocks.STONE.defaultBlockState(), null);
            StructureTemplate.StructureBlockInfo out = weathered.process(
                    helper.getLevel(), pos, BlockPos.ZERO, raw, raw, new StructurePlaceSettings(), null);
            if (out == null) {
                removed++;
            } else if (out.state().is(Blocks.COBWEB)) {
                webs++;
            }
        }
        helper.assertTrue(removed + webs == 400, "缺失率给满时每个实体方块都该被处理，实际只处理了 " + (removed + webs));
        helper.assertTrue(webs > 50 && webs < 150, "四分之一的位置该是蜘蛛网，实际 " + webs + "/400");
        BlockPos chestPos = helper.absolutePos(new BlockPos(2, 3, 2));
        StructureTemplate.StructureBlockInfo chest =
                new StructureTemplate.StructureBlockInfo(chestPos, Blocks.CHEST.defaultBlockState(), new CompoundTag());
        helper.assertTrue(
                weathered.process(
                                helper.getLevel(),
                                chestPos,
                                BlockPos.ZERO,
                                chest,
                                chest,
                                new StructurePlaceSettings(),
                                null)
                        != null,
                "带 nbt 的方块（宝箱）不该被缺失");
        BandProcessor clean = new BandProcessor(Integer.MIN_VALUE, Integer.MAX_VALUE, BandProcessor.Phase.SOLID);
        StructureTemplate.StructureBlockInfo stone =
                new StructureTemplate.StructureBlockInfo(chestPos, Blocks.STONE.defaultBlockState(), null);
        helper.assertTrue(
                clean.process(
                                helper.getLevel(),
                                chestPos,
                                BlockPos.ZERO,
                                stone,
                                stone,
                                new StructurePlaceSettings(),
                                null)
                        != null,
                "缺失率为 0 时不该动任何方块");
        helper.succeed();
    }

    /**
     * 三种平层房间都抽得到：宝箱房、空地房、家具房，层数落在 1..3
     *
     * <p>箱子不是另外摆的，是宝箱房件里原有的方块。宝箱房现在是一个普通角色、按体型的权重抽，
     * 所以单座房子不保证有；这里钉的是「{@value #CHEST_SAMPLES} 座里三种平层房间都出现过」。
     * 生成走 {@code Structure#generate}，不必真的铺区块，`biome -> true` 跳过群系检查。
     */
    @GameTest(template = "ddt_empty")
    public static void everyGridStructureHasItsRoomRoles(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Structure structure =
                level.registryAccess().registryOrThrow(Registries.STRUCTURE).get(DawnDuskTerminal.id("grid_rooms"));
        ChunkGenerator generator = level.getChunkSource().getGenerator();
        int baseX = helper.absolutePos(BlockPos.ZERO).getX();
        int baseZ = helper.absolutePos(BlockPos.ZERO).getZ();
        int chestTotal = 0;
        int emptyTotal = 0;
        int furnTotal = 0;
        for (int i = 0; i < CHEST_SAMPLES; i++) {
            ChunkPos chunkPos =
                    new ChunkPos(SectionPos.blockToSectionCoord(baseX) + i * 7, SectionPos.blockToSectionCoord(baseZ));
            StructureStart start = structure.generate(
                    level.registryAccess(),
                    generator,
                    generator.getBiomeSource(),
                    level.getChunkSource().randomState(),
                    level.getStructureManager(),
                    CHEST_SAMPLE_SEED + i,
                    chunkPos,
                    0,
                    level,
                    biome -> true);
            helper.assertTrue(start.isValid(), "第 " + i + " 座结构应当生成出来：" + chunkPos);
            int chestRooms = 0;
            int emptyRooms = 0;
            int furnRooms = 0;
            Set<Integer> roomHeights = new HashSet<>();
            for (StructurePiece piece : start.getPieces()) {
                if (!(piece instanceof PoolElementStructurePiece pool)) {
                    continue;
                }
                String id = pool.getElement().toString();
                if (id.contains(CHEST_ROOM)) {
                    chestRooms++;
                }
                // 层数用「房间件的落笔行」数：每层所有房间件都在 origin + k*5 + 4 这一行，
                // 而 `ceiling/*` 同时被当成地板件用，按它数会把每层数成两层
                if (id.contains("room/")) {
                    roomHeights.add(piece.getBoundingBox().minY());
                    if (id.contains("/empty")) {
                        emptyRooms++;
                    } else if (id.contains("/furn_")) {
                        furnRooms++;
                    }
                }
            }
            chestTotal += chestRooms;
            emptyTotal += emptyRooms;
            furnTotal += furnRooms;
            int levels = roomHeights.size();
            helper.assertTrue(levels >= 1 && levels <= 3, "层数应当落在 1..3，实际 " + levels + "（" + chunkPos + "）");
        }
        helper.assertTrue(
                chestTotal > 0 && emptyTotal > 0 && furnTotal > 0,
                "三种平层房间都该出现过，实际 宝箱=" + chestTotal + " 空地=" + emptyTotal + " 家具=" + furnTotal);
        // 上下两段高度是两个结构、两套结构集：它们互不抢丰度，各自都要在登记表里
        assertBand(helper, level, generator, helper.absolutePos(BlockPos.ZERO), "grid_rooms", -65, -25);
        assertBand(helper, level, generator, helper.absolutePos(BlockPos.ZERO), "grid_rooms_upper", 5, 45);
        // 两个 id 都必须进白名单：StructureExclusionMixin 会压制不在白名单里的结构，
        // 上一版就是因为新加的 grid_rooms_upper 漏登记，整个上层结构在晨昏线维度里一次都没生成
        helper.assertTrue(
                StructureExclusions.isAllowed(DawnDuskTerminal.id("grid_rooms")),
                "grid_rooms 必须在 StructureExclusions 的白名单里");
        helper.assertTrue(
                StructureExclusions.isAllowed(DawnDuskTerminal.id("grid_rooms_upper")),
                "grid_rooms_upper 必须在 StructureExclusions 的白名单里，否则会被压制");
        helper.succeed();
    }

    /** 这个结构在给定区块生成出来，且落点落在期望的高度窗口里 */
    private static void assertBand(
            GameTestHelper helper,
            ServerLevel level,
            ChunkGenerator generator,
            BlockPos base,
            String path,
            int minY,
            int maxY) {
        Structure structure =
                level.registryAccess().registryOrThrow(Registries.STRUCTURE).get(DawnDuskTerminal.id(path));
        helper.assertTrue(structure != null, "结构 " + path + " 应当已在登记表里");
        ChunkPos chunkPos =
                new ChunkPos(SectionPos.blockToSectionCoord(base.getX()), SectionPos.blockToSectionCoord(base.getZ()));
        StructureStart start = structure.generate(
                level.registryAccess(),
                generator,
                generator.getBiomeSource(),
                level.getChunkSource().randomState(),
                level.getStructureManager(),
                level.getSeed(),
                chunkPos,
                0,
                level,
                biome -> true);
        helper.assertTrue(start.isValid(), "结构 " + path + " 应当生成出来：" + chunkPos);
        int top = start.getBoundingBox().minY();
        helper.assertTrue(top >= minY && top <= maxY, "结构 " + path + " 的落点应当在 y " + minY + ".." + maxY + "，实际 " + top);
    }

    /**
     * `/ddt pieces <结构> [目录]` 的摆位：同一个分类排在同一段行里，换分类另起一行
     *
     * <p>摆位只是坐标计算，这里拿假 id 直接问，不往世界里摆方块——摆一整套要在区块里铺满上千格，
     * 那些方块会污染隔壁用例的场地
     */
    @GameTest(template = "ddt_empty")
    public static void catalogueStartsANewRowPerCategory(GameTestHelper helper) {
        List<ResourceLocation> ids = List.of(
                DawnDuskTerminal.id(FAMILY + "ceiling/rect"),
                DawnDuskTerminal.id(FAMILY + "floor/rect/stairtop"),
                DawnDuskTerminal.id(FAMILY + "room/rect/furn_01"),
                DawnDuskTerminal.id(FAMILY + "room/small/furn_01"),
                DawnDuskTerminal.id(FAMILY + "room/small/empty"),
                DawnDuskTerminal.id(FAMILY + "room/rect/chest"),
                DawnDuskTerminal.id(FAMILY + "wall/small_plain_01"),
                DawnDuskTerminal.id(FAMILY + "wall/large_plain_01"));
        List<GridPlacer.Cell> cells = GridPlacer.layout(ids);
        helper.assertTrue(cells.size() == ids.size(), "每个件都该有一个格子，实际 " + cells.size());
        Map<ResourceLocation, GridPlacer.Cell> byId = new HashMap<>();
        for (GridPlacer.Cell cell : cells) {
            byId.put(cell.id(), cell);
        }
        // 天花板与楼板同组、同一行
        GridPlacer.Cell ceiling = byId.get(DawnDuskTerminal.id(FAMILY + "ceiling/rect"));
        GridPlacer.Cell floor = byId.get(DawnDuskTerminal.id(FAMILY + "floor/rect/stairtop"));
        helper.assertTrue(
                ceiling.group().equals("grid_rooms/ceiling+floor/rect")
                        && floor.group().equals(ceiling.group())
                        && ceiling.row() == floor.row(),
                "天花板与楼板该在同一组同一行，实际 " + ceiling + " / " + floor);
        // 家具房按体型分组，两组不同行
        GridPlacer.Cell rectFurn = byId.get(DawnDuskTerminal.id(FAMILY + "room/rect/furn_01"));
        GridPlacer.Cell smallFurn = byId.get(DawnDuskTerminal.id(FAMILY + "room/small/furn_01"));
        helper.assertTrue(
                rectFurn.group().equals("grid_rooms/room/rect")
                        && smallFurn.group().equals("grid_rooms/room/small"),
                "家具房该按体型分组，实际 " + rectFurn.group() + " / " + smallFurn.group());
        helper.assertTrue(rectFurn.row() != smallFurn.row(), "两个体型该各占一行");
        // 空地房与宝箱房不按体型
        GridPlacer.Cell empty = byId.get(DawnDuskTerminal.id(FAMILY + "room/small/empty"));
        GridPlacer.Cell chest = byId.get(DawnDuskTerminal.id(FAMILY + "room/rect/chest"));
        helper.assertTrue(
                empty.group().equals("grid_rooms/room/empty") && chest.group().equals("grid_rooms/room/chest"),
                "空地房与宝箱房该各自一块，实际 " + empty.group() + " / " + chest.group());
        // 墙按长边/短边
        GridPlacer.Cell smallWall = byId.get(DawnDuskTerminal.id(FAMILY + "wall/small_plain_01"));
        GridPlacer.Cell largeWall = byId.get(DawnDuskTerminal.id(FAMILY + "wall/large_plain_01"));
        helper.assertTrue(
                smallWall.group().equals("grid_rooms/wall/small")
                        && largeWall.group().equals("grid_rooms/wall/large"),
                "墙该按长边/短边分组，实际 " + smallWall.group() + " / " + largeWall.group());
        // 每一组只能连续出现一段，否则同一个组会画成两块
        String previous = null;
        Set<String> seen = new HashSet<>();
        for (GridPlacer.Cell cell : cells) {
            if (!cell.group().equals(previous)) {
                helper.assertTrue(seen.add(cell.group()), "组 " + cell.group() + " 被拆成了两段");
                previous = cell.group();
            }
        }
        helper.succeed();
    }

    /** 按结构筛件：指定家族只出这个家族的件，再指定目录只出那个目录的件 */
    @GameTest(template = "ddt_empty")
    public static void catalogueIdsFollowTheFamilyAndCategory(GameTestHelper helper) {
        StructureTemplateManager manager = helper.getLevel().getStructureManager();
        Structure structure = helper.getLevel()
                .registryAccess()
                .registryOrThrow(Registries.STRUCTURE)
                .get(DawnDuskTerminal.id("grid_rooms"));
        helper.assertTrue(structure instanceof GridStructure, "grid_rooms 应当还是 GridStructure");
        GridStructure grid = (GridStructure) structure;
        List<ResourceLocation> all = GridPlacer.catalogueIds(manager, grid, null);
        helper.assertTrue(!all.isEmpty(), "按家族筛出来不该是空的");
        helper.assertTrue(
                all.stream().allMatch(id -> id.getPath().startsWith(FAMILY)), "筛选结果应当都在 " + FAMILY + " 下，实际 " + all);
        List<ResourceLocation> rooms = GridPlacer.catalogueIds(manager, grid, "room");
        helper.assertTrue(!rooms.isEmpty(), "room 目录不该是空的");
        helper.assertTrue(
                rooms.stream().allMatch(id -> id.getPath().startsWith(FAMILY + "room/")),
                "指定 room 时只该出 room/ 下的件，实际 " + rooms);
        helper.assertTrue(GridPlacer.catalogueIds(manager, grid, "nope").isEmpty(), "不认识的目录不该挑出任何件");
        helper.succeed();
    }

    /**
     * 派生件必须跟着房间件走：地板件的朝向 = 房间件的朝向，绳梯房间下面必须有梯井件
     *
     * <p>这两条都被「件按家族目录分层」那轮改坏过：`plane.getPath().startsWith("floor/")`
     * 与 `contains("_rope_")` 都是按老路径写的，件前面多了家族与体型目录之后全部落空——
     * 地板件改用格子自己的 `turns()` 转，跟楼梯件差出 180 度；梯井件干脆一件都不摆。
     * 这里拿生成出来的件直接比对，不再靠肉眼。
     */
    @GameTest(template = "ddt_empty")
    public static void derivedPiecesFollowTheirRoom(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Structure structure =
                level.registryAccess().registryOrThrow(Registries.STRUCTURE).get(DawnDuskTerminal.id("grid_rooms"));
        ChunkGenerator generator = level.getChunkSource().getGenerator();
        int floors = 0;
        int shafts = 0;
        for (int i = 0; i < 8; i++) {
            ChunkPos chunkPos = new ChunkPos(i * 9 + 3, 11);
            StructureStart start = structure.generate(
                    level.registryAccess(),
                    generator,
                    generator.getBiomeSource(),
                    level.getChunkSource().randomState(),
                    level.getStructureManager(),
                    9100L + i,
                    chunkPos,
                    0,
                    level,
                    biome -> true);
            List<PoolElementStructurePiece> pools = new ArrayList<>();
            for (StructurePiece piece : start.getPieces()) {
                if (piece instanceof PoolElementStructurePiece pool) {
                    pools.add(pool);
                }
            }
            for (PoolElementStructurePiece floor : pools) {
                String path = floor.getElement().toString();
                if (!path.contains("/floor/")) {
                    continue;
                }
                BoundingBox box = floor.getBoundingBox();
                // 同一格、比它高一层的那件房间：地板的 +3 与房间的 +4 差一格
                PoolElementStructurePiece room = null;
                for (PoolElementStructurePiece other : pools) {
                    if (other.getElement().toString().contains("/room/")
                            && other.getBoundingBox().minX() == box.minX()
                            && other.getBoundingBox().minZ() == box.minZ()
                            && other.getBoundingBox().minY() == box.minY() + 1) {
                        room = other;
                        break;
                    }
                }
                helper.assertTrue(room != null, "地板件 " + path + " 上面应当有同一格的房间件（" + box + "）");
                helper.assertTrue(
                        room.getRotation() == floor.getRotation(),
                        "地板件 " + path + " 的朝向应当跟房间件一致，实际地板 " + floor.getRotation() + " / 房间 " + room.getRotation());
                floors++;
            }
            for (PoolElementStructurePiece shaft : pools) {
                if (!shaft.getElement().toString().contains("/shaft/")) {
                    continue;
                }
                BoundingBox box = shaft.getBoundingBox();
                // 梯井件落在格子地板上（+0），房间件在 +4
                boolean hasRoom = pools.stream()
                        .anyMatch(other -> other.getElement().toString().contains("/room/")
                                && other.getBoundingBox().minX() == box.minX()
                                && other.getBoundingBox().minZ() == box.minZ()
                                && other.getBoundingBox().minY() == box.minY() + 4);
                helper.assertTrue(hasRoom, "梯井件 " + shaft.getElement() + " 上面应当有绳梯房间（" + box + "）");
                shafts++;
            }
        }
        helper.assertTrue(floors > 0, "样本里一件带开口的地板都没有，这条用例证明不了什么");
        helper.succeed();
    }

    /**
     * 地基件带着外墙脚：尺寸比格子大一圈，而且不再摆 {@code foot/*}
     *
     * <p>脚并进 base 之后靠「base 与房间同一个朝向」对齐，露出边数是固定的
     * （rect 1 条、small 2 条相邻、large 与内角件 0 条），这条用例钉住三件事：
     * 件的尺寸、房间里出现过的 base 尺寸、以及再也不会有 {@code foot/} 件被摆出来。
     */
    @GameTest(template = "ddt_empty")
    public static void baseCarriesTheFoot(GameTestHelper helper) {
        StructureTemplateManager manager = helper.getLevel().getStructureManager();
        Structure structure = helper.getLevel()
                .registryAccess()
                .registryOrThrow(Registries.STRUCTURE)
                .get(DawnDuskTerminal.id("grid_rooms"));
        GridStructure grid = (GridStructure) structure;
        helper.assertTrue(
                sizeOf(manager, grid, "base/rect").equals(new Vec3i(6, 3, 7)),
                "base/rect 应当是 6x3x7，实际 " + sizeOf(manager, grid, "base/rect"));
        helper.assertTrue(
                sizeOf(manager, grid, "base/small").equals(new Vec3i(6, 3, 6)),
                "base/small 应当是 6x3x6，实际 " + sizeOf(manager, grid, "base/small"));
        helper.assertTrue(
                sizeOf(manager, grid, "base/inner_corner").equals(new Vec3i(7, 3, 7)),
                "base/inner_corner 没有露出边，应当还是 7x3x7");
        helper.assertTrue(GridPlacer.catalogueIds(manager, grid, "foot").isEmpty(), "foot 目录应当已经空了（脚并进 base）");
        // 生成一批，确认没有 foot 件被摆出来
        ChunkGenerator generator = helper.getLevel().getChunkSource().getGenerator();
        for (int i = 0; i < 6; i++) {
            StructureStart start = structure.generate(
                    helper.getLevel().registryAccess(),
                    generator,
                    generator.getBiomeSource(),
                    helper.getLevel().getChunkSource().randomState(),
                    manager,
                    5100L + i,
                    new ChunkPos(i * 9 + 1, 5),
                    0,
                    helper.getLevel(),
                    biome -> true);
            List<PoolElementStructurePiece> pools = new ArrayList<>();
            for (StructurePiece piece : start.getPieces()) {
                if (piece instanceof PoolElementStructurePiece pool) {
                    pools.add(pool);
                    helper.assertTrue(
                            !pool.getElement().toString().contains("/foot/"), "不该再摆 foot 件，实际摆出了 " + pool.getElement());
                }
            }
            // 地基要把同一格的房间整个包住，只在至多两个方向上各多出 1 格（那就是脚）
            for (PoolElementStructurePiece base : pools) {
                if (!base.getElement().toString().contains("/base/")) {
                    continue;
                }
                BoundingBox box = base.getBoundingBox();
                PoolElementStructurePiece room = null;
                for (PoolElementStructurePiece other : pools) {
                    BoundingBox inner = other.getBoundingBox();
                    if (other.getElement().toString().contains("/room/")
                            && inner.minY() == box.minY() + 4
                            && inner.minX() >= box.minX()
                            && inner.maxX() <= box.maxX()
                            && inner.minZ() >= box.minZ()
                            && inner.maxZ() <= box.maxZ()) {
                        room = other;
                        break;
                    }
                }
                helper.assertTrue(room != null, "地基 " + base.getElement() + " 应当把同一格的房间包住（" + box + "）");
                BoundingBox cells = room.getBoundingBox();
                int lowX = cells.minX() - box.minX();
                int highX = box.maxX() - cells.maxX();
                int lowZ = cells.minZ() - box.minZ();
                int highZ = box.maxZ() - cells.maxZ();
                // 每一侧最多多 1 格，而且「多出来的一侧」总数必须等于 0/1/2（脚占几条边）
                helper.assertTrue(
                        lowX >= 0
                                && lowX <= 1
                                && highX >= 0
                                && highX <= 1
                                && lowZ >= 0
                                && lowZ <= 1
                                && highZ >= 0
                                && highZ <= 1,
                        "地基每侧最多比房间多 1 格，实际 x-" + lowX + " x+" + highX + " z-" + lowZ + " z+" + highZ + "（" + box + "）");
                int sides = (lowX > 0 ? 1 : 0) + (highX > 0 ? 1 : 0) + (lowZ > 0 ? 1 : 0) + (highZ > 0 ? 1 : 0);
                helper.assertTrue(sides <= 2, "地基最多带两条边的脚，实际 " + sides + " 条（" + box + " vs " + cells + "）");
            }
        }
        helper.succeed();
    }

    private static Vec3i sizeOf(StructureTemplateManager manager, GridStructure grid, String relative) {
        return manager.get(DawnDuskTerminal.id(grid.templates() + "/" + relative))
                .orElseThrow(() -> new IllegalStateException(relative + " 不存在"))
                .getSize();
    }

    /**
     * 墙与女儿墙的框列必须压在框架线上
     *
     * <p>件的列数一改，落笔角就得跟着挪（`Side#forward` 那 0/1/2 格），挪错一格整面墙就偏一格，
     * 而且偏得很像「本来就这样」。这里把框架线从房间件的包围盒里取出来（房间件就是整格，
     * 两条边就是框架线），再要求每件墙的框列正好落在其中一条上。
     */
    @GameTest(template = "ddt_empty")
    public static void wallPiecesSitOnTheFrameLine(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Structure structure =
                level.registryAccess().registryOrThrow(Registries.STRUCTURE).get(DawnDuskTerminal.id("grid_rooms"));
        ChunkGenerator generator = level.getChunkSource().getGenerator();
        int checked = 0;
        for (int i = 0; i < 8; i++) {
            ChunkPos chunkPos = new ChunkPos(i * 9, 3);
            StructureStart start = structure.generate(
                    level.registryAccess(),
                    generator,
                    generator.getBiomeSource(),
                    level.getChunkSource().randomState(),
                    level.getStructureManager(),
                    7000L + i,
                    chunkPos,
                    0,
                    level,
                    biome -> true);
            Set<Integer> frameX = new HashSet<>();
            Set<Integer> frameZ = new HashSet<>();
            List<BoundingBox> walls = new ArrayList<>();
            for (StructurePiece piece : start.getPieces()) {
                if (!(piece instanceof PoolElementStructurePiece pool)) {
                    continue;
                }
                String path = pool.getElement().toString();
                BoundingBox box = piece.getBoundingBox();
                if (path.contains("/room/")) {
                    frameX.add(box.minX());
                    frameX.add(box.maxX());
                    frameZ.add(box.minZ());
                    frameZ.add(box.maxZ());
                } else if (path.contains("/wall/") || (path.contains("/parapet/") && !path.contains("inner_corner"))) {
                    walls.add(box);
                }
            }
            for (BoundingBox box : walls) {
                boolean alongZ = box.getZSpan() > box.getXSpan();
                Set<Integer> frame = alongZ ? frameX : frameZ;
                int low = alongZ ? box.minX() : box.minZ();
                int high = alongZ ? box.maxX() : box.maxZ();
                helper.assertTrue(
                        frame.contains(low) != frame.contains(high),
                        "墙 " + box + " 的框列应当正好压在框架线上（" + (alongZ ? "x" : "z") + " 方向：" + low + ".." + high + "）");
                checked++;
            }
        }
        helper.assertTrue(checked > 20, "样本太少，没检查到几面墙：" + checked);
        helper.succeed();
    }

    /**
     * 墙族件的列数：墙与女儿墙两列、外挑一列、墙脚三列
     *
     * <p>墙与女儿墙是沿边的局部 x 方向摆的，删掉一列就得同时改落笔角（`GridStructure#placeWall` 里
     * 按 `Side#forward` 挪 0/1/2 格）。这里把列数钉住：件被重新导出回三列时，先红的是这条，
     * 而不是等到世界里看见墙偏了一格。`foot` 那族已经并进 `base`，不在这里比。
     */
    @GameTest(template = "ddt_empty")
    public static void wallFamilyKeepsItsColumnCount(GameTestHelper helper) {
        StructureTemplateManager manager = helper.getLevel().getStructureManager();
        Structure structure = helper.getLevel()
                .registryAccess()
                .registryOrThrow(Registries.STRUCTURE)
                .get(DawnDuskTerminal.id("grid_rooms"));
        GridStructure grid = (GridStructure) structure;
        assertColumns(helper, manager, grid, "wall", 2);
        assertColumns(helper, manager, grid, "edge", 1);
        // 女儿墙除 inner_corner 那件（凹角整格 7×2×7，按格子摆，不跟边锚点）之外都是两列
        for (ResourceLocation id : GridPlacer.catalogueIds(manager, grid, "parapet")) {
            if (id.getPath().contains("inner_corner")) {
                continue;
            }
            Vec3i size = manager.get(id).orElseThrow().getSize();
            helper.assertTrue(size.getX() == 2, id + " 应当是两列，实际 " + size.getX() + " 列（" + size + "）");
        }
        helper.succeed();
    }

    private static void assertColumns(
            GameTestHelper helper, StructureTemplateManager manager, GridStructure grid, String dir, int columns) {
        List<ResourceLocation> ids = GridPlacer.catalogueIds(manager, grid, dir);
        helper.assertTrue(!ids.isEmpty(), dir + " 目录不该是空的");
        for (ResourceLocation id : ids) {
            Vec3i size = manager.get(id).orElseThrow().getSize();
            helper.assertTrue(
                    size.getX() == columns, id + " 应当是 " + columns + " 列，实际 " + size.getX() + " 列（" + size + "）");
        }
    }

    /**
     * 跨区块的绳梯件：`afterPlace` 只能碰当前区块那一截，绳照样要往下接
     *
     * <p>件整体往区块外挑出两格，绳那一列（局部 `2,*,2`）仍在区块里。真的 {@code afterPlace}
     * 扫整件包围盒的话，第一格越界的读就会撞上代理的断言。
     */
    @GameTest(template = "ddt_empty")
    public static void afterPlaceStaysInsideTheWritableArea(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        StructureTemplateManager templates = level.getStructureManager();
        StructureManager structures = level.structureManager();
        ChunkGenerator generator = level.getChunkSource().getGenerator();
        ChunkPos chunkPos = new ChunkPos(helper.absolutePos(new BlockPos(2, 2, 2)));
        BoundingBox area = writableArea(level, chunkPos);

        int y = helper.absolutePos(new BlockPos(2, 4, 2)).getY();
        BlockPos min = new BlockPos(area.minX() - SHAFT_OVERHANG, y, area.minZ() + 1);
        PoolElementStructurePiece shaft = piece(templates, DawnDuskTerminal.id(FAMILY + "shaft/small/rope_01"), min);
        helper.assertTrue(shaft.getBoundingBox().minX() < area.minX(), "测试前提：这件要跨出区块边界，现在没有");
        BlockPos rope = min.offset(SHAFT_ROPE_X, 0, SHAFT_ROPE_Z);
        helper.assertTrue(area.isInside(rope), "测试前提：绳那一列要落在区块里，现在落在 " + rope + "，区块是 " + chunkPos);

        // 件自己先摆一遍（原版 `StructureStart#placeInChunk` 干的事），`afterPlace` 只负责往下接
        shaft.postProcess(level, structures, generator, level.random, area, chunkPos, min);
        helper.assertTrue(
                level.getBlockState(rope).is(TFBlocks.ROPE.get()),
                "件摆完以后绳那一列应当已经是绳，实际 " + level.getBlockState(rope) + " @ " + rope);
        // 绳下面一格清成空气、再下面一格堵上石头：往下接正好接一格就停
        BlockPos below1 = rope.below();
        BlockPos below2 = rope.below(2);
        level.setBlock(below2, Blocks.STONE.defaultBlockState(), 3);
        level.setBlock(below1, Blocks.AIR.defaultBlockState(), 3);

        Structure grid = level.registryAccess()
                .registryOrThrow(Registries.STRUCTURE)
                .getOptional(DawnDuskTerminal.id("grid_rooms"))
                .orElseThrow(() -> new IllegalStateException("dawnduskterminal:grid_rooms 没有注册"));
        helper.assertTrue(grid instanceof GridStructure, "dawnduskterminal:grid_rooms 应当还是 GridStructure");
        grid.afterPlace(
                guarded(level, area),
                structures,
                generator,
                level.random,
                area,
                chunkPos,
                new PiecesContainer(List.of(shaft)));

        helper.assertTrue(
                level.getBlockState(below1).is(TFBlocks.ROPE.get()),
                "绳要往下接一格，实际 " + level.getBlockState(below1) + " @ " + below1);
        helper.assertTrue(
                level.getBlockState(below2).is(Blocks.STONE),
                "撞到石头就该停，实际 " + level.getBlockState(below2) + " @ " + below2);

        // 代理本身得是有效的：验一次越界读确实会被拦下，否则上面那条证明不了什么
        boolean armed = false;
        try {
            guarded(level, area).getBlockState(new BlockPos(area.minX() - 1, y, rope.getZ()));
        } catch (IllegalStateException expected) {
            armed = true;
        }
        helper.assertTrue(armed, "代理没有拦住区块外的读，这条测试就证明不了任何事");
        helper.succeed();
    }

    /** 与 {@code ChunkGenerator#getWritableArea} 一致：当前区块、整根柱高 */
    private static BoundingBox writableArea(ServerLevel level, ChunkPos chunkPos) {
        return new BoundingBox(
                chunkPos.getMinBlockX(),
                level.getMinBuildHeight() + 1,
                chunkPos.getMinBlockZ(),
                chunkPos.getMaxBlockX(),
                level.getMaxBuildHeight() - 1,
                chunkPos.getMaxBlockZ());
    }

    /** 与 `GridStructure.place` 同样的拼法：包围盒下角对齐到 {@code min} */
    private static PoolElementStructurePiece piece(
            StructureTemplateManager templates, ResourceLocation id, BlockPos min) {
        StructurePoolElement element =
                StructurePoolElement.single(id.toString()).apply(StructureTemplatePool.Projection.RIGID);
        BoundingBox zero = element.getBoundingBox(templates, BlockPos.ZERO, Rotation.NONE);
        BlockPos pos = min.offset(-zero.minX(), -zero.minY(), -zero.minZ());
        BoundingBox box = zero.moved(pos.getX(), pos.getY(), pos.getZ());
        return new PoolElementStructurePiece(
                templates, element, pos, 0, Rotation.NONE, box, LiquidSettings.IGNORE_WATERLOGGING);
    }

    /**
     * 只在 {@code area} 之内放行的 level
     *
     * <p>`WorldGenRegion` 不许读区块外的方块，读到就抛异常；代理把这条规则提前摆进测试，
     * 越界的那一次读在哪一行都漏不过去。
     */
    private static WorldGenLevel guarded(WorldGenLevel level, BoundingBox area) {
        return (WorldGenLevel) Proxy.newProxyInstance(
                WorldGenLevel.class.getClassLoader(), new Class<?>[] {WorldGenLevel.class}, (proxy, method, args) -> {
                    guard(area, method.getName(), args);
                    try {
                        return method.invoke(level, args);
                    } catch (InvocationTargetException wrapped) {
                        throw wrapped.getCause();
                    }
                });
    }

    private static void guard(BoundingBox area, String method, Object[] args) {
        if (args == null || args.length == 0 || !(args[0] instanceof BlockPos pos)) {
            return;
        }
        boolean touchesBlocks = method.equals("getBlockState")
                || method.equals("getFluidState")
                || method.equals("setBlock")
                || method.equals("getBlockEntity");
        if (!touchesBlocks || area.isInside(pos)) {
            return;
        }
        throw new IllegalStateException(method + " 越出了 " + area + "：" + pos);
    }
}
