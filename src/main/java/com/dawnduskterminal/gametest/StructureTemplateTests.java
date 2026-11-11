package com.dawnduskterminal.gametest;

import com.dawnduskterminal.DawnDuskTerminal;
import com.dawnduskterminal.structure.BandProcessor;
import com.dawnduskterminal.structure.GridPlacer;
import com.dawnduskterminal.structure.GridStructure;
import com.dawnduskterminal.structure.StructureExclusions;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.SectionPos;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.RandomizableContainer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.vehicle.MinecartChest;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
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
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.phys.AABB;
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
                // 地板、天花板、地基各自成件，按它们数会把每层数成好几层
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
     * 边框家具只看件里那几行
     *
     * <p>{@code hasBorderFurniture} 决定这一格要不要在墙之后把房间件再落一遍实体方块：
     * 家具正贴着框架线时，墙件的空气会把它们擦掉。房间件只有 4 行，
     * 查到件外那一行会把每件都判成带边框家具——件外那几格不在空气集合里，于是“非空”。
     *
     * <p>两个样本：空地房只有四角原木（那几格本来就被跳过），边框上摆着木头的 {@code furn_04}
     * 该判成有。改件之后这两条跟着改，否则这里会先报出来。
     */
    @GameTest(template = "ddt_empty")
    public static void borderFurnitureIsJudgedInsideThePiece(GameTestHelper helper) {
        StructureTemplateManager manager = helper.getLevel().getStructureManager();
        helper.assertFalse(
                hasBorderFurniture(manager, DawnDuskTerminal.id(FAMILY + "room/rect/empty")),
                "空地房只有四角原木，不该判成带边框家具（件外那一行被当真了）");
        helper.assertTrue(
                hasBorderFurniture(manager, DawnDuskTerminal.id(FAMILY + "room/rect/furn_04")),
                "furn_04 的边框上摆着木头，该判成带边框家具");
        helper.succeed();
    }

    /** {@code GridStructure#hasBorderFurniture} 是私有的，缓存也在里面，只能反射调 */
    private static boolean hasBorderFurniture(StructureTemplateManager manager, ResourceLocation id) {
        try {
            Method method = GridStructure.class.getDeclaredMethod(
                    "hasBorderFurniture", StructureTemplateManager.class, ResourceLocation.class);
            method.setAccessible(true);
            return (boolean) method.invoke(null, manager, id);
        } catch (ReflectiveOperationException error) {
            throw new AssertionError("GridStructure#hasBorderFurniture 调不到", error);
        }
    }

    /**
     * 塔木那套能真的拼出房子：房间、墙、地基都有，楼梯房配得上它的楼梯口
     *
     * <p>这套件是手导的，缺件不会报错、只会让房子少一块，所以在这里钉一遍：
     * 每座至少有一件 room、一件 wall，摆出了楼梯就必须有配套的带洞楼板——
     * 整间 stairtop 或者只有那一层楼板的 `floor/rect/stairtop` 都算。
     *
     * <p>另钉一件塔木那族自己的事：它是浮空的，不该出现往下接的柱子（`"posts": false`）。
     * 宝箱房是两族的共同约定，钉在 {@link #everyBuildingHasAChestRoom}。
     */
    @GameTest(template = "ddt_empty")
    public static void towerwoodRoomsAssemble(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Structure structure = level.registryAccess()
                .registryOrThrow(Registries.STRUCTURE)
                .get(DawnDuskTerminal.id("towerwood_rooms"));
        helper.assertTrue(structure instanceof GridStructure, "towerwood_rooms 应当是 GridStructure");
        ChunkGenerator generator = level.getChunkSource().getGenerator();
        for (int i = 0; i < 6; i++) {
            StructureStart start = structure.generate(
                    level.registryAccess(),
                    generator,
                    generator.getBiomeSource(),
                    level.getChunkSource().randomState(),
                    level.getStructureManager(),
                    8800L + i,
                    new ChunkPos(i * 11 + 2, 7),
                    0,
                    level,
                    biome -> true);
            helper.assertTrue(start.isValid(), "第 " + i + " 座塔木屋群应当生成出来");
            boolean room = false;
            boolean wall = false;
            boolean stair = false;
            boolean stairTop = false;
            for (StructurePiece piece : start.getPieces()) {
                if (!(piece instanceof PoolElementStructurePiece pool)) {
                    continue;
                }
                String path = pool.getElement().toString();
                room |= path.contains("/room/");
                wall |= path.contains("/wall/");
                stair |= path.contains("room/rect/stair");
                stairTop |= path.contains("room/rect/stairtop") || path.contains("floor/rect/stairtop");
                helper.assertFalse(path.contains("/post/column"), "塔木那族是浮空的，不该摆柱子（结构 JSON 的 posts 应当是 false）");
            }
            helper.assertTrue(room, "第 " + i + " 座应当摆出房间件");
            helper.assertTrue(wall, "第 " + i + " 座应当摆出墙件");
            helper.assertTrue(!stair || stairTop, "摆了楼梯房就必须有配套的带洞楼板");
        }
        helper.succeed();
    }

    /** 带机关的那间房里自带两辆宝箱矿车：哪次重新导出漏掉实体，这条会先报出来 */
    @GameTest(template = "ddt_empty")
    public static void furnRoomKeepsItsMinecarts(GameTestHelper helper) {
        String path = "/data/dawnduskterminal/structure/towerwood_rooms/room/rect/furn_06.nbt";
        try (InputStream in = StructureTemplateTests.class.getResourceAsStream(path)) {
            helper.assertTrue(in != null, "找不到机关房件 " + path);
            CompoundTag root = NbtIo.readCompressed(in, NbtAccounter.unlimitedHeap());
            ListTag entities = root.getList("entities", Tag.TAG_COMPOUND);
            helper.assertTrue(entities.size() == 2, "熔炉房应当带两辆矿车，实际 " + entities.size() + " 个实体");
            for (int i = 0; i < entities.size(); i++) {
                // 件里的实体是 {pos, blockPos, nbt} 三件套，id 在内层 nbt 里
                CompoundTag nbt = entities.getCompound(i).getCompound("nbt");
                helper.assertTrue(
                        "minecraft:chest_minecart".equals(nbt.getString("id")),
                        "实体 " + i + " 应当是宝箱矿车，实际 " + nbt.getString("id"));
                // 存档里带出来的 UUID 与开发期模组的附着数据不该跟着件走
                helper.assertFalse(nbt.contains("UUID"), "矿车不该带存档里的 UUID");
                helper.assertFalse(nbt.contains("neoforge:attachments"), "矿车不该带开发期模组的附着数据");
            }
        } catch (IOException error) {
            throw new AssertionError("机关房件读不出来", error);
        }
        helper.succeed();
    }

    /**
     * 同一格的房间件只落一遍
     *
     * <p>以前房间件落两遍：空气一遍（内空靠件里存的空气清）、实体方块一遍。多出来那遍的代价有两个：
     * 箱子这类带 nbt 的方块第二遍会先清旧方块实体再盖 barrier，第一遍已经 roll 好的内容物被抖到地上；
     * 件里的实体会被 spawn 两次（处理器只管方块，管不到实体），件里两辆宝箱矿车变成四辆。
     * 现在内空由 `air/<体型>` 清，房间件只在实体方块那一遍落一次——件列表里每格正好一条。
     */
    @GameTest(template = "ddt_empty")
    public static void roomsPlaceExactlyOnce(GameTestHelper helper) {
        int rooms = 0;
        for (String name : List.of("grid_rooms", "towerwood_rooms")) {
            GridStructure grid = gridStructure(helper, name);
            for (int i = 0; i < 8; i++) {
                StructureStart start = generate(helper, grid, 18000L + i, new ChunkPos(i * 19 + 11, 37));
                helper.assertTrue(start.isValid(), name + " 第 " + i + " 座应当生成出来");
                Map<String, Integer> passes = new HashMap<>();
                for (PoolElementStructurePiece piece : poolPieces(start)) {
                    String path = path(piece);
                    if (!path.contains("/room/")) {
                        continue;
                    }
                    BoundingBox box = piece.getBoundingBox();
                    passes.merge(path + "@" + box.minX() + "," + box.minY() + "," + box.minZ(), 1, Integer::sum);
                    rooms++;
                }
                for (Map.Entry<String, Integer> entry : passes.entrySet()) {
                    helper.assertTrue(
                            entry.getValue() == 1,
                            name + " 的 " + entry.getKey() + " 落了 " + entry.getValue() + " 遍（房间件只该落一遍实体方块）");
                }
            }
        }
        helper.assertTrue(rooms > 0, "一座房间件都没数到，这条用例证明不了什么");
        helper.succeed();
    }

    /**
     * 这间机关房摆进世界后不该有东西弹出来
     *
     * <p>件里是一整套红石机关：拉杆、红石块、动力铁轨、漏斗、熔炉、两辆宝箱矿车。
     * 拉杆这类需要附着面的方块，附着面没落上就会在摆完的方块更新里自己弹掉，变成地上的掉落物；
     * 房间件落两遍也会把箱子第一遍的内容物抖出来。实机里看到的就是「箱子附近散着东西」。
     * 这里真把件摆进世界、跑几 tick，数一遍掉落物。
     *
     * <p>顺带钉住另外两件事：箱子带着 loot 表（否则开出来是空的），件里两辆矿车世界里也只有两辆。
     */
    @GameTest(template = "ddt_empty")
    public static void furnRoomPlacesNothingThatPops(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        StructureTemplateManager templates = level.getStructureManager();
        ChunkGenerator generator = level.getChunkSource().getGenerator();
        BlockPos anchor = helper.absolutePos(new BlockPos(2, 4, 2));
        ChunkPos chunkPos = new ChunkPos(anchor);
        BoundingBox area = writableArea(level, chunkPos);
        BlockPos min = new BlockPos(area.minX() + 1, anchor.getY(), area.minZ() + 1);
        PoolElementStructurePiece room =
                piece(templates, DawnDuskTerminal.id("towerwood_rooms/room/rect/furn_06"), min);
        room.postProcess(level, level.structureManager(), generator, level.random, area, chunkPos, min);

        BoundingBox box = room.getBoundingBox();
        boolean chest = false;
        for (BlockPos cursor :
                BlockPos.betweenClosed(box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ())) {
            if (level.getBlockEntity(cursor) instanceof RandomizableContainer container) {
                chest |= container.getLootTable() != null;
            }
        }
        helper.assertTrue(chest, "机关房的箱子应当带着 loot 表（否则开出来是空的）");

        // 件里两辆宝箱矿车、两段动力铁轨（局部 (3,2,1) 与 (2,3,1)）。
        // 摆一遍就该只有两辆；多出来说明同一条实体被 spawn 了两次
        AABB reach = new AABB(
                min.getX() - 2, min.getY() - 2, min.getZ() - 2, min.getX() + 7, min.getY() + 8, min.getZ() + 9);
        helper.assertTrue(level.getEntitiesOfClass(MinecartChest.class, reach).size() == 2, "件里两辆宝箱矿车，世界里应当也是两辆");

        helper.runAfterDelay(5, () -> {
            List<ItemEntity> popped = level.getEntitiesOfClass(
                    ItemEntity.class,
                    new AABB(
                            box.minX() - 2,
                            box.minY() - 2,
                            box.minZ() - 2,
                            box.maxX() + 3,
                            box.maxY() + 3,
                            box.maxZ() + 3));
            helper.assertTrue(
                    popped.isEmpty(),
                    "机关房摆完有东西弹出来了："
                            + popped.stream()
                                    .map(entity -> entity.getItem().toString())
                                    .toList());
            // 两段铁轨还在原位、还是动力铁轨。形状不看：这两段一格高、一格西（(3,2,1) 与 (2,3,1)），
            // 原版铁轨 onPlace 会自己算成 ascending_west 的坡（与 setBlock 的 update flags 无关），
            // 件里写的 north_south 保不住。要两段各自平放得挪件，见 docs/worldgen.md
            for (BlockPos rail : List.of(min.offset(3, 2, 1), min.offset(2, 3, 1))) {
                BlockState state = level.getBlockState(rail);
                helper.assertTrue(state.is(Blocks.POWERED_RAIL), rail + " 应当是动力铁轨，实际 " + state);
            }
            helper.assertTrue(
                    level.getEntitiesOfClass(MinecartChest.class, reach).size() == 2, "跑了几 tick 之后宝箱矿车应当还是两辆");
            helper.succeed();
        });
    }

    /**
     * 宝箱里装什么全看件里写的那张 loot 表，件里只存一个 id，写错不会报错、箱子只是空的
     *
     * <p>两件事一起钉：
     *
     * <ul>
     *   <li>件里的宝箱写的 {@code LootTable} 必须在注册表里；
     *   <li>模组自己那几张宝箱表里引用的每个 item / 嵌套表都要解析得开——20 个试验物品是逐个手写的，
     *       写错一个号不会报错，只会少一样东西。
     * </ul>
     */
    @GameTest(template = "ddt_empty")
    public static void chestLootTablesResolve(GameTestHelper helper) {
        HolderGetter<LootTable> tables =
                helper.getLevel().getServer().reloadableRegistries().lookup().lookupOrThrow(Registries.LOOT_TABLE);
        // 件里的宝箱：件路径 → 里面应当写的那张表
        Map<String, String> pieces = Map.of(
                "/data/dawnduskterminal/structure/grid_rooms/room/rect/chest.nbt",
                "chests/grid_rooms",
                "/data/dawnduskterminal/structure/towerwood_rooms/room/rect/chest_02.nbt",
                "chests/towerwood_rooms",
                "/data/dawnduskterminal/structure/towerwood_rooms/room/rect/furn_06.nbt",
                "chests/towerwood_furnace");
        for (Map.Entry<String, String> entry : pieces.entrySet()) {
            String expected = DawnDuskTerminal.id(entry.getValue()).toString();
            try (InputStream in = StructureTemplateTests.class.getResourceAsStream(entry.getKey())) {
                helper.assertTrue(in != null, "找不到件 " + entry.getKey());
                CompoundTag root = NbtIo.readCompressed(in, NbtAccounter.unlimitedHeap());
                ListTag blocks = root.getList("blocks", Tag.TAG_COMPOUND);
                boolean found = false;
                for (int i = 0; i < blocks.size(); i++) {
                    String table = blocks.getCompound(i).getCompound("nbt").getString("LootTable");
                    if (table.isEmpty()) {
                        continue;
                    }
                    found |= expected.equals(table);
                    helper.assertTrue(
                            tables.get(ResourceKey.create(Registries.LOOT_TABLE, ResourceLocation.parse(table)))
                                    .isPresent(),
                            entry.getKey() + " 的宝箱指向 " + table + "，这张表不在注册表里");
                }
                helper.assertTrue(found, entry.getKey() + " 的宝箱里没有写 " + expected);
            } catch (IOException error) {
                throw new AssertionError(entry.getKey() + " 读不出来", error);
            }
        }
        // 我们自己那几张表的内容：每个 item 与嵌套表都要能解析
        int checked = 0;
        for (String name : List.of("chests/grid_rooms", "chests/towerwood_rooms", "chests/towerwood_furnace")) {
            String resource = "/data/dawnduskterminal/loot_table/" + name + ".json";
            try (InputStream in = StructureTemplateTests.class.getResourceAsStream(resource)) {
                helper.assertTrue(in != null, "找不到 " + resource);
                JsonObject json = JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8))
                        .getAsJsonObject();
                for (JsonElement pool : json.getAsJsonArray("pools")) {
                    for (JsonElement element : pool.getAsJsonObject().getAsJsonArray("entries")) {
                        JsonObject loot = element.getAsJsonObject();
                        String type = loot.get("type").getAsString();
                        if ("minecraft:item".equals(type)) {
                            ResourceLocation id =
                                    ResourceLocation.parse(loot.get("name").getAsString());
                            helper.assertTrue(BuiltInRegistries.ITEM.containsKey(id), name + " 里的物品 " + id + " 没有注册");
                            checked++;
                        } else if ("minecraft:loot_table".equals(type)) {
                            ResourceLocation id =
                                    ResourceLocation.parse(loot.get("value").getAsString());
                            helper.assertTrue(
                                    tables.get(ResourceKey.create(Registries.LOOT_TABLE, id))
                                            .isPresent(),
                                    name + " 引用的表 " + id + " 不在注册表里");
                            checked++;
                        }
                    }
                }
            } catch (IOException error) {
                throw new AssertionError(resource + " 读不出来", error);
            }
        }
        helper.assertTrue(checked > 20, "样本太少，只检查到 " + checked + " 条 loot 项");
        helper.succeed();
    }

    /**
     * 每座房子保底一间宝箱房：`grid_rooms` 与 `towerwood_rooms` 都一样
     *
     * <p>宝箱房只是个权重（rect 每格 3/88），抽不到时由 `ensureChestRoom` 拿一间 rect 空地房换掉，
     * 所以这里可以断言「每一座都有」，而不是「样本里算是有」。两族结构各自跑六座。
     */
    @GameTest(template = "ddt_empty")
    public static void everyBuildingHasAChestRoom(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ChunkGenerator generator = level.getChunkSource().getGenerator();
        for (String name : List.of("grid_rooms", "towerwood_rooms")) {
            Structure structure =
                    level.registryAccess().registryOrThrow(Registries.STRUCTURE).get(DawnDuskTerminal.id(name));
            helper.assertTrue(structure instanceof GridStructure, name + " 应当是 GridStructure");
            for (int i = 0; i < 6; i++) {
                StructureStart start = structure.generate(
                        level.registryAccess(),
                        generator,
                        generator.getBiomeSource(),
                        level.getChunkSource().randomState(),
                        level.getStructureManager(),
                        9300L + i,
                        new ChunkPos(i * 13 + 4, 23),
                        0,
                        level,
                        biome -> true);
                helper.assertTrue(start.isValid(), name + " 第 " + i + " 座应当生成出来");
                boolean chest = false;
                for (StructurePiece piece : start.getPieces()) {
                    if (piece instanceof PoolElementStructurePiece pool) {
                        chest |= pool.getElement().toString().contains("/room/rect/chest");
                    }
                }
                helper.assertTrue(chest, name + " 第 " + i + " 座应当有一间宝箱房");
            }
        }
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
     * 每格都有一件地板、一件天花板，而且与格子同样大
     *
     * <p>地板与天花板是两套件：天花是倒扣台阶，地板是平铺板材。平层的楼板曾经回落到
     * `ceiling/<体型>`，于是脚底下铺的是天花纹；件被重新导出成小一圈（内角件导成 6×6）时，
     * 世界里只是角落缺一格，不会报任何错。这里按包围盒把两条一起钉住：
     * 每个房间件下面一格有 `floor/*`、上面四格有 `ceiling/*`，且两件的 x/z 跨度与房间件相同。
     */
    @GameTest(template = "ddt_empty")
    public static void everyCellHasAFloorAndACeiling(GameTestHelper helper) {
        for (String name : List.of("grid_rooms", "towerwood_rooms")) {
            GridStructure grid = gridStructure(helper, name);
            for (int i = 0; i < 6; i++) {
                StructureStart start = generate(helper, grid, 13000L + i, new ChunkPos(i * 13 + 5, 17));
                helper.assertTrue(start.isValid(), name + " 第 " + i + " 座应当生成出来");
                List<PoolElementStructurePiece> pieces = poolPieces(start);
                int cells = 0;
                for (PoolElementStructurePiece room : pieces) {
                    BoundingBox box = room.getBoundingBox();
                    if (!path(room).contains("/room/")) {
                        continue;
                    }
                    assertPlane(helper, name, pieces, box, box.minY() - 1, "/floor/");
                    assertPlane(helper, name, pieces, box, box.minY() + 4, "/ceiling/");
                    cells++;
                }
                helper.assertTrue(cells > 0, name + " 第 " + i + " 座应当摆出房间件");
            }
        }
        helper.succeed();
    }

    /** 与这一格同位置、同一行的那件楼板/天花板，尺寸必须是格子尺寸 */
    private static void assertPlane(
            GameTestHelper helper,
            String grid,
            List<PoolElementStructurePiece> pieces,
            BoundingBox room,
            int y,
            String kind) {
        for (PoolElementStructurePiece piece : pieces) {
            BoundingBox box = piece.getBoundingBox();
            if (box.minY() != y
                    || box.minX() != room.minX()
                    || box.minZ() != room.minZ()
                    || !path(piece).contains(kind)) {
                continue;
            }
            helper.assertTrue(
                    box.getXSpan() == room.getXSpan() && box.getZSpan() == room.getZSpan(),
                    grid + " 的 " + path(piece) + " 应当与格子同宽同深（" + room.getXSpan() + "x" + room.getZSpan() + "），实际 "
                            + box);
            return;
        }
        helper.assertTrue(false, grid + " 的格子 " + room + " 在 y=" + y + " 没有 " + kind + " 件（平层楼板是不是又回落到 ceiling 了）");
    }

    /**
     * 楼梯两端的邻格必须是空地房
     *
     * <p>楼梯件沿长边从一头走到另一头：本层两端是进出口，上一层两端是下楼口，共四格。
     * `markStairEnds` 把这四格记成「必须是空地房」，但它算邻格时拿的是楼梯格自己的跨度，
     * 而一行里的跨度是 5、7、5 混排，往负方向那一侧整整差 2 格，查出来永远是 null——
     * 家具照旧摆进进出口。这里按包围盒找邻格，与 `GridFrame#adjacent` 判据一致：
     * 端上确实有房间，就必须是 `/empty`（凹角格例外，见下）；被切掉的角上没有房间，跳过。
     */
    @GameTest(template = "ddt_empty")
    public static void stairEndsAreEmptyRooms(GameTestHelper helper) {
        int ends = 0;
        for (String name : List.of("grid_rooms", "towerwood_rooms")) {
            GridStructure grid = gridStructure(helper, name);
            for (int i = 0; i < 8; i++) {
                StructureStart start = generate(helper, grid, 14000L + i, new ChunkPos(i * 11 + 4, 19));
                helper.assertTrue(start.isValid(), name + " 第 " + i + " 座应当生成出来");
                List<PoolElementStructurePiece> pieces = poolPieces(start);
                for (PoolElementStructurePiece stair : pieces) {
                    BoundingBox box = stair.getBoundingBox();
                    if (!path(stair).contains("/room/")
                            || !hasPiece(pieces, "/floor/rect/stairtop", box.minX(), box.minY() + 4, box.minZ())) {
                        continue;
                    }
                    // 楼梯沿长边走：件是 5×7，转过 90 度就是 7×5
                    boolean alongZ = box.getZSpan() > box.getXSpan();
                    for (int sign = -1; sign <= 1; sign += 2) {
                        for (int dy = 0; dy <= 5; dy += 5) {
                            PoolElementStructurePiece end = endAt(pieces, box, alongZ, sign, box.minY() + dy);
                            if (end == null) {
                                continue;
                            }
                            // 凹角格只有 `room/inner_corner/corner` 一件，格内只有凹角那一道石阶与栏杆、没有家具，
                            // 本身就能走，`pickRoom` 对凹角格也直接用它（没有 `inner_corner/empty` 那件）
                            String endPath = path(end);
                            helper.assertTrue(
                                    endPath.contains("/empty") || endPath.contains("/inner_corner/corner"),
                                    name + " 楼梯 " + box + " 的进出口应当换成空地房，实际是 " + endPath);
                            ends++;
                        }
                    }
                }
            }
        }
        helper.assertTrue(ends > 4, "样本太少，只检查到 " + ends + " 个楼梯进出口");
        helper.succeed();
    }

    /** 这一格在 {@code (alongZ ? z : x)} 方向那一侧的邻格：共用框架线的两件在包围盒上重叠一行/一列 */
    private static PoolElementStructurePiece endAt(
            List<PoolElementStructurePiece> pieces, BoundingBox cell, boolean alongZ, int sign, int y) {
        for (PoolElementStructurePiece piece : pieces) {
            if (!path(piece).contains("/room/")) {
                continue;
            }
            BoundingBox box = piece.getBoundingBox();
            if (box.minY() != y) {
                continue;
            }
            if (alongZ) {
                boolean touches = sign < 0 ? box.maxZ() == cell.minZ() : box.minZ() == cell.maxZ();
                if (touches && span(box.minX(), box.maxX(), cell.minX(), cell.maxX()) >= 2) {
                    return piece;
                }
            } else {
                boolean touches = sign < 0 ? box.maxX() == cell.minX() : box.minX() == cell.maxX();
                if (touches && span(box.minZ(), box.maxZ(), cell.minZ(), cell.maxZ()) >= 2) {
                    return piece;
                }
            }
        }
        return null;
    }

    /** 两段闭区间重叠几格 */
    private static int span(int minA, int maxA, int minB, int maxB) {
        return Math.min(maxA, maxB) - Math.max(minA, minB) + 1;
    }

    /**
     * 底层的楼梯格也要有地基
     *
     * <p>`baseOf` 曾经对楼梯格返回 null，理由是「没有往下跨越的结构」——那说的是绳梯。
     * 后果是底层楼梯那一格悬在土里：从下面、从侧面看都是个洞。地基的顶行贴在楼板下面一行，
     * 所以这里只按「同一格、顶行 = 房间件下面两行」找；上层格子不摆地基（下面是室内），不看。
     */
    @GameTest(template = "ddt_empty")
    public static void stairRoomsSitOnABase(GameTestHelper helper) {
        int checked = 0;
        for (String name : List.of("grid_rooms", "towerwood_rooms")) {
            GridStructure grid = gridStructure(helper, name);
            for (int i = 0; i < 8; i++) {
                StructureStart start = generate(helper, grid, 15000L + i, new ChunkPos(i * 9 + 3, 23));
                helper.assertTrue(start.isValid(), name + " 第 " + i + " 座应当生成出来");
                List<PoolElementStructurePiece> pieces = poolPieces(start);
                int lowest = Integer.MAX_VALUE;
                for (PoolElementStructurePiece piece : pieces) {
                    if (path(piece).contains("/room/")) {
                        lowest = Math.min(lowest, piece.getBoundingBox().minY());
                    }
                }
                for (PoolElementStructurePiece stair : pieces) {
                    BoundingBox box = stair.getBoundingBox();
                    if (!path(stair).contains("/room/")
                            || box.minY() != lowest
                            || !hasPiece(pieces, "/floor/rect/stairtop", box.minX(), box.minY() + 4, box.minZ())) {
                        continue;
                    }
                    boolean base = false;
                    for (PoolElementStructurePiece piece : pieces) {
                        BoundingBox below = piece.getBoundingBox();
                        base |= path(piece).contains("/base/")
                                && below.maxY() == box.minY() - 2
                                && Math.abs(below.minX() - box.minX()) <= 1
                                && Math.abs(below.minZ() - box.minZ()) <= 1;
                    }
                    helper.assertTrue(base, name + " 的底层楼梯格 " + box + " 下面没有地基");
                    checked++;
                }
            }
        }
        helper.assertTrue(checked > 3, "样本太少，只检查到 " + checked + " 个底层楼梯格");
        helper.succeed();
    }

    /**
     * 凹角格最后落笔：同一层里凹角的天花板件排在其他天花板件后面
     *
     * <p>凹角件在凹口那一角摆的方块（塔木那族是警示塔木）压在两条框架线上，而紧邻的两格自己也要画
     * 那两条线，谁后摆谁赢。排在前面的位置上，凹角那一角就被邻居的框架盖掉了。
     *
     * <p>只看天花板件：房间件另有一遍「带边框家具的格子最后再落一次」（`placeBorder`），
     * 那一遍故意把普通房间件排到最后，拿房间件比顺序会误报。
     *
     * <p>一件分两遍落（空气一遍、实体方块一遍），同一格的天花板件因此出现两次；按行分组只看最后一件，
     * 两遍各自都应该以凹角件收尾。
     */
    @GameTest(template = "ddt_empty")
    public static void innerCornerCeilingsArePlacedLast(GameTestHelper helper) {
        int corners = 0;
        for (String name : List.of("grid_rooms", "towerwood_rooms")) {
            GridStructure grid = gridStructure(helper, name);
            for (int i = 0; i < 6; i++) {
                StructureStart start = generate(helper, grid, 16000L + i, new ChunkPos(i * 15 + 7, 29));
                helper.assertTrue(start.isValid(), name + " 第 " + i + " 座应当生成出来");
                // 行 → 那一行最后一件天花板件的路径，以及那一行天花板件的最大序号
                Map<Integer, String> lastCeiling = new HashMap<>();
                Map<Integer, Integer> lastCeilingIndex = new HashMap<>();
                Set<Integer> cornerRows = new HashSet<>();
                List<PoolElementStructurePiece> pieces = poolPieces(start);
                for (int index = 0; index < pieces.size(); index++) {
                    PoolElementStructurePiece piece = pieces.get(index);
                    String path = path(piece);
                    if (!path.contains("/ceiling/")) {
                        continue;
                    }
                    int y = piece.getBoundingBox().minY();
                    lastCeiling.put(y, path);
                    lastCeilingIndex.put(y, index);
                    if (path.contains("inner_corner")) {
                        cornerRows.add(y);
                    }
                }
                for (int y : cornerRows) {
                    helper.assertTrue(
                            lastCeiling.get(y).contains("inner_corner"),
                            name + " 第 " + i + " 座的 y=" + y + " 那层最后摆的天花板是 " + lastCeiling.get(y) + "，凹角那一角会被它盖掉");
                    // 同一层墙件的落笔行比天花板低 4 格（+4 vs +8），墙的最上一行正是錾制/封装塔木梁；
                    // 凹角天花板必须排在那些墙件之后
                    for (int index = 0; index < pieces.size(); index++) {
                        PoolElementStructurePiece other = pieces.get(index);
                        if (path(other).contains("/wall/")
                                && other.getBoundingBox().minY() == y - 4
                                && index > lastCeilingIndex.get(y)) {
                            helper.assertTrue(false, name + " 第 " + i + " 座的墙件 " + path(other) + " 排在凹角天花板之后，会把警示塔木盖掉");
                        }
                    }
                    corners++;
                }
            }
        }
        helper.assertTrue(corners > 0, "一座带凹角格的结构都没测到，这条用例证明不了什么");
        helper.succeed();
    }

    /** 某个位置上有没有这一件 */
    private static boolean hasPiece(List<PoolElementStructurePiece> pieces, String kind, int x, int y, int z) {
        for (PoolElementStructurePiece piece : pieces) {
            BoundingBox box = piece.getBoundingBox();
            if (box.minX() == x
                    && box.minY() == y
                    && box.minZ() == z
                    && path(piece).contains(kind)) {
                return true;
            }
        }
        return false;
    }

    private static List<PoolElementStructurePiece> poolPieces(StructureStart start) {
        List<PoolElementStructurePiece> out = new ArrayList<>();
        for (StructurePiece piece : start.getPieces()) {
            if (piece instanceof PoolElementStructurePiece pool) {
                out.add(pool);
            }
        }
        return out;
    }

    private static String path(PoolElementStructurePiece piece) {
        return piece.getElement().toString();
    }

    private static GridStructure gridStructure(GameTestHelper helper, String name) {
        Structure structure = helper.getLevel()
                .registryAccess()
                .registryOrThrow(Registries.STRUCTURE)
                .get(DawnDuskTerminal.id(name));
        helper.assertTrue(structure instanceof GridStructure, name + " 应当是 GridStructure");
        return (GridStructure) structure;
    }

    private static StructureStart generate(GameTestHelper helper, Structure structure, long seed, ChunkPos chunkPos) {
        ServerLevel level = helper.getLevel();
        ChunkGenerator generator = level.getChunkSource().getGenerator();
        return structure.generate(
                level.registryAccess(),
                generator,
                generator.getBiomeSource(),
                level.getChunkSource().randomState(),
                level.getStructureManager(),
                seed,
                chunkPos,
                0,
                level,
                biome -> true);
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
