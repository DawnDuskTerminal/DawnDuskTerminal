package com.dawnduskterminal.gametest;

import com.dawnduskterminal.DawnDuskTerminal;
import com.dawnduskterminal.structure.BandProcessor;
import com.dawnduskterminal.structure.GridPlacer;
import com.dawnduskterminal.structure.GridStructure;
import com.dawnduskterminal.structure.GridTemplateCatalog;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
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
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.PathPackResources;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.resources.MultiPackResourceManager;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.datafix.DataFixers;
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
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext;
import net.minecraft.world.level.levelgen.structure.pools.StructurePoolElement;
import net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool;
import net.minecraft.world.level.levelgen.structure.templatesystem.LiquidSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessorType;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import net.minecraft.world.level.storage.LevelStorageSource;
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
            List<Integer> roomRows = new ArrayList<>();
            for (StructurePiece piece : start.getPieces()) {
                if (!(piece instanceof PoolElementStructurePiece pool)) {
                    continue;
                }
                String id = pool.getElement().toString();
                if (id.contains(CHEST_ROOM)) {
                    chestRooms++;
                }
                // 层数用「房间件的落笔行」数：普通房间件在 origin + k*5 + 4 这一行，
                // 自带楼板的那几类（掉落口、绳梯、内角）低一行在 +3，所以差 1 的两行算同一层
                if (id.contains("room/")) {
                    roomRows.add(piece.getBoundingBox().minY());
                    if (id.contains("/empty")) {
                        emptyRooms++;
                    } else if (id.contains("/furn_")) {
                        furnRooms++;
                    }
                }
            }
            int lowestRow = roomRows.stream().mapToInt(Integer::intValue).min().orElse(0);
            for (int row : roomRows) {
                roomHeights.add((row - lowestRow) / 5);
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
     * `/ddt pieces <结构> [目录]` 的摆位：特殊件 → 地板 → 天花板 → 地基 → 外挑 → 女儿墙 → 房间 → 墙
     *
     * <p>只算坐标、不碰世界：摆一整套要在区块里铺满上千格，那些方块会污染隔壁用例的场地。
     */
    @GameTest(template = "ddt_empty")
    public static void catalogueOrdersPiecesBySection(GameTestHelper helper) {
        List<ResourceLocation> ids = List.of(
                DawnDuskTerminal.id(FAMILY + "wall/small_plain_01"),
                DawnDuskTerminal.id(FAMILY + "wall/large_plain_01"),
                DawnDuskTerminal.id(FAMILY + "room/rect/furn_01"),
                DawnDuskTerminal.id(FAMILY + "ceiling/large"),
                DawnDuskTerminal.id(FAMILY + "room/rect/empty"),
                DawnDuskTerminal.id(FAMILY + "floor/inner_corner"),
                DawnDuskTerminal.id(FAMILY + "floor/small"),
                DawnDuskTerminal.id(FAMILY + "floor/rect"),
                DawnDuskTerminal.id(FAMILY + "room/small/empty"),
                DawnDuskTerminal.id(FAMILY + "post/column"),
                DawnDuskTerminal.id(FAMILY + "base/rect"));
        List<GridPlacer.Cell> cells = GridPlacer.layout(ids);
        helper.assertTrue(cells.size() == ids.size(), "每个件都该有一个格子，实际 " + cells.size());
        Map<ResourceLocation, GridPlacer.Cell> byId = new HashMap<>();
        for (GridPlacer.Cell cell : cells) {
            byId.put(cell.id(), cell);
        }
        GridPlacer.Cell special = byId.get(DawnDuskTerminal.id(FAMILY + "post/column"));
        GridPlacer.Cell floorSmall = byId.get(DawnDuskTerminal.id(FAMILY + "floor/small"));
        GridPlacer.Cell floorRect = byId.get(DawnDuskTerminal.id(FAMILY + "floor/rect"));
        GridPlacer.Cell floorInner = byId.get(DawnDuskTerminal.id(FAMILY + "floor/inner_corner"));
        GridPlacer.Cell ceiling = byId.get(DawnDuskTerminal.id(FAMILY + "ceiling/large"));
        GridPlacer.Cell base = byId.get(DawnDuskTerminal.id(FAMILY + "base/rect"));
        GridPlacer.Cell smallEmpty = byId.get(DawnDuskTerminal.id(FAMILY + "room/small/empty"));
        GridPlacer.Cell rectEmpty = byId.get(DawnDuskTerminal.id(FAMILY + "room/rect/empty"));
        GridPlacer.Cell rectFurn = byId.get(DawnDuskTerminal.id(FAMILY + "room/rect/furn_01"));
        GridPlacer.Cell smallWall = byId.get(DawnDuskTerminal.id(FAMILY + "wall/small_plain_01"));
        GridPlacer.Cell largeWall = byId.get(DawnDuskTerminal.id(FAMILY + "wall/large_plain_01"));
        // 其余件（柱子、井段、空气件）合成最前面那一组
        helper.assertTrue(special.row() == 0 && special.column() == 0, "特殊件排最前，实际 " + special);
        // 地板一组，按 small → rect → large → inner 的顺序
        helper.assertTrue(
                floorSmall.row() == 1
                        && floorSmall.column() == 0
                        && floorRect.row() == 1
                        && floorRect.column() == 1
                        && floorInner.row() == 1
                        && floorInner.column() == 2,
                "地板该按 small → rect → large → inner 排在一行，实际 " + floorSmall + " / " + floorRect + " / " + floorInner);
        helper.assertTrue(ceiling.row() == 2 && ceiling.column() == 0, "天花板另起一行，实际 " + ceiling);
        helper.assertTrue(base.row() == 3 && base.column() == 0, "地基另起一行，实际 " + base);
        // 房间按体型分组、小的在前；组内按 空地 → 宝箱 → 楼梯 → 掉落口 → 绳梯 → 家具
        helper.assertTrue(
                smallEmpty.row() == 4 && rectEmpty.row() == 5 && rectEmpty.column() == 0,
                "房间该按体型各占一行、small 在前，实际 " + smallEmpty + " / " + rectEmpty);
        helper.assertTrue(
                rectFurn.row() == 5 && rectFurn.column() == 1, "同一体型里空地房排在前面，实际 " + rectEmpty + " / " + rectFurn);
        // 墙排最后，分两组：短边在前、长边在后
        helper.assertTrue(
                smallWall.row() == 6 && smallWall.column() == 0 && largeWall.row() == 7 && largeWall.column() == 0,
                "墙该按短边 / 长边各占一行，实际 " + smallWall + " / " + largeWall);
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
     * 派生件必须跟着房间件走：同一格的地板件与房间件朝向一致
     *
     * <p>这条被「件按家族目录分层」那轮改坏过：`plane.getPath().startsWith("floor/")`
     * 是按老路径写的，件前面多了家族与体型目录之后全部落空——地板件改用格子自己的 `turns()` 转，
     * 跟房间件差出 180 度。这里拿生成出来的件直接比对，不再靠肉眼。
     */
    @GameTest(template = "ddt_empty")
    public static void derivedPiecesFollowTheirRoom(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Structure structure =
                level.registryAccess().registryOrThrow(Registries.STRUCTURE).get(DawnDuskTerminal.id("grid_rooms"));
        ChunkGenerator generator = level.getChunkSource().getGenerator();
        int floors = 0;
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

    /** 相同资源 ID 在不同管理器中必须分别枚举并判断模板内容。 */
    @GameTest(template = "ddt_empty")
    public static void templateCatalogsAreIsolated(GameTestHelper helper) throws Exception {
        ResourceLocation room = DawnDuskTerminal.id("r02/isolation/room");
        ResourceLocation extra = DawnDuskTerminal.id("r02/isolation/extra");
        try (TemplateFixture first = new TemplateFixture(helper);
                TemplateFixture second = new TemplateFixture(helper)) {
            first.copy(room, FAMILY + "room/rect/empty");
            second.copy(room, FAMILY + "room/rect/furn_04");
            second.copy(extra, FAMILY + "room/rect/empty");
            Object a = templateCatalog(first.manager);
            Object b = templateCatalog(second.manager);
            helper.assertTrue(variants(a, "r02/isolation/").equals(List.of(room)), "首个管理器的目录不符");
            helper.assertTrue(variants(b, "r02/isolation/").equals(List.of(extra, room)), "不同管理器不得共用目录缓存");
            helper.assertFalse(borderFurniture(a, room), "首个管理器的空房间不应含边框家具");
            helper.assertTrue(borderFurniture(b, room), "不同管理器不得共用模板内容判断");
            helper.assertTrue(variants(a, "r02/isolation/").equals(List.of(room)), "第二个目录不得污染首个目录");
        }
        helper.succeed();
    }

    /** 重载必须刷新同一管理器的目录、已有模板与缺失模板。 */
    @GameTest(template = "ddt_empty")
    public static void templateCatalogFollowsResourceReload(GameTestHelper helper) throws Exception {
        ResourceLocation room = DawnDuskTerminal.id("r02/reload/room");
        ResourceLocation added = DawnDuskTerminal.id("r02/reload/added");
        try (TemplateFixture fixture = new TemplateFixture(helper)) {
            fixture.copy(room, FAMILY + "room/rect/empty");
            Object before = templateCatalog(fixture.manager);
            helper.assertTrue(variants(before, "r02/reload/").equals(List.of(room)), "重载前目录不符");
            helper.assertFalse(borderFurniture(before, room), "重载前应为空房间");
            helper.assertFalse(borderFurniture(before, added), "缺失模板应返回 false");
            fixture.copy(room, FAMILY + "room/rect/furn_04");
            fixture.copy(added, FAMILY + "room/rect/furn_04");
            fixture.reload();
            Object after = templateCatalog(fixture.manager);
            helper.assertTrue(variants(after, "r02/reload/").equals(List.of(added, room)), "重载后应重新枚举目录");
            helper.assertTrue(variants(before, "r02/reload/").equals(List.of(room)), "旧任务的目录快照不得被重载原地改写");
            helper.assertTrue(borderFurniture(after, room), "同一管理器重载后应重新判断模板内容");
            helper.assertTrue(borderFurniture(after, added), "重载后补入的模板不得沿用缺失结果");
        }
        helper.succeed();
    }

    /** 原版 remove 刷新单件后，下一次生成也不得沿用缺失结果。 */
    @GameTest(template = "ddt_empty")
    public static void templateCatalogRetriesMissingTemplates(GameTestHelper helper) throws Exception {
        ResourceLocation id = DawnDuskTerminal.id("r02/missing/room");
        try (TemplateFixture fixture = new TemplateFixture(helper)) {
            helper.assertFalse(borderFurniture(templateCatalog(fixture.manager), id), "缺失模板应返回 false");
            fixture.copy(id, FAMILY + "room/rect/furn_04");
            fixture.manager.remove(id);
            helper.assertTrue(borderFurniture(templateCatalog(fixture.manager), id), "下一次生成应读取补入的模板");
        }
        helper.succeed();
    }

    /** 原版允许直接编辑已存在的内存模板；下一次生成必须重新判断家具。 */
    @GameTest(template = "ddt_empty")
    public static void templateCatalogSeesInMemoryEdits(GameTestHelper helper) throws Exception {
        ResourceLocation room = DawnDuskTerminal.id("r02/edit/room");
        ResourceLocation source = DawnDuskTerminal.id("r02/edit/source");
        try (TemplateFixture fixture = new TemplateFixture(helper)) {
            fixture.copy(room, FAMILY + "room/rect/empty");
            fixture.copy(source, FAMILY + "room/rect/furn_04");
            Object before = templateCatalog(fixture.manager);
            helper.assertFalse(borderFurniture(before, room), "编辑前应为空房间");
            var resources = templateResources(fixture.manager);
            StructureTemplate template = fixture.manager.getOrCreate(room);
            template.load(
                    helper.getLevel().registryAccess().lookupOrThrow(Registries.BLOCK),
                    fixture.manager.get(source).orElseThrow().save(new CompoundTag()));
            helper.assertTrue(resources == templateResources(fixture.manager), "读取已有模板不得更换目录代次");
            helper.assertTrue(borderFurniture(templateCatalog(fixture.manager), room), "下一次生成必须看到内存编辑");
            helper.assertFalse(borderFurniture(before, room), "已计算的生成局部判断不得被其他任务原地修改");
        }
        helper.succeed();
    }

    /** 创建模板刷新缺失诊断，成功保存后目录必须包含新增文件。 */
    @GameTest(template = "ddt_empty")
    public static void templateCatalogFollowsCreateAndSave(GameTestHelper helper) throws Exception {
        ResourceLocation room = DawnDuskTerminal.id("r02/save/room");
        ResourceLocation source = DawnDuskTerminal.id("r02/source/room");
        try (TemplateFixture fixture = new TemplateFixture(helper)) {
            fixture.copy(source, FAMILY + "room/rect/furn_04");
            Object before = templateCatalog(fixture.manager);
            helper.assertTrue(variants(before, "r02/save/").isEmpty(), "创建前不应枚举出模板");
            helper.assertFalse(borderFurniture(before, room), "创建前模板应缺失");
            var original = templateResources(fixture.manager);
            StructureTemplate template = fixture.manager.getOrCreate(room);
            var created = templateResources(fixture.manager);
            helper.assertTrue(original != created, "getOrCreate 实际创建模板时必须更换代次");
            template.load(
                    helper.getLevel().registryAccess().lookupOrThrow(Registries.BLOCK),
                    fixture.manager.get(source).orElseThrow().save(new CompoundTag()));
            Object inMemory = templateCatalog(fixture.manager);
            helper.assertTrue(borderFurniture(inMemory, room), "新建内存模板不得沿用缺失判断");
            helper.assertTrue(variants(inMemory, "r02/save/").isEmpty(), "未保存的内存模板不属于原版目录枚举结果");
            helper.assertTrue(fixture.manager.save(room), "模板应成功写入隔离的临时目录");
            helper.assertTrue(created != templateResources(fixture.manager), "成功保存后必须更换目录代次");
            helper.assertTrue(
                    variants(templateCatalog(fixture.manager), "r02/save/").equals(List.of(room)), "保存后应枚举出新增模板");
        }
        helper.succeed();
    }

    /** 重载不等待旧目录查询；旧任务结束后也不得回填新代次。 */
    @GameTest(template = "ddt_empty")
    public static void templateReloadIsolatesInFlightCatalog(GameTestHelper helper) throws Exception {
        ResourceLocation room = DawnDuskTerminal.id("r02/inflight/room");
        ResourceLocation added = DawnDuskTerminal.id("r02/inflight/added");
        try (TemplateFixture fixture = new TemplateFixture(helper)) {
            fixture.copy(room, FAMILY + "room/rect/empty");
            Object before = templateCatalog(fixture.manager);
            CountDownLatch captured = new CountDownLatch(1);
            CountDownLatch resume = new CountDownLatch(1);
            fixture.manager.afterListing = () -> {
                captured.countDown();
                try {
                    if (!resume.await(5, TimeUnit.SECONDS)) {
                        throw new AssertionError("旧目录查询未获释放");
                    }
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError("旧目录查询被中断", error);
                }
            };
            CompletableFuture<List<ResourceLocation>> pending =
                    CompletableFuture.supplyAsync(() -> variants(before, "r02/inflight/"));
            try {
                helper.assertTrue(captured.await(5, TimeUnit.SECONDS), "旧任务应先捕获重载前目录");
                fixture.manager.afterListing = null;
                fixture.copy(added, FAMILY + "room/rect/furn_04");
                fixture.reload();
                Object after = templateCatalog(fixture.manager);
                helper.assertTrue(variants(after, "r02/inflight/").equals(List.of(added, room)), "旧任务未结束时，新代次也应可读取新目录");
                resume.countDown();
                helper.assertTrue(pending.get(5, TimeUnit.SECONDS).equals(List.of(room)), "旧任务应保留其已捕获的目录");
                helper.assertTrue(variants(after, "r02/inflight/").equals(List.of(added, room)), "旧任务完成后不得污染新代次");
            } finally {
                resume.countDown();
                pending.get(5, TimeUnit.SECONDS);
            }
        }
        helper.succeed();
    }

    /** 同一资源代次只枚举一次目录；同次生成内复用模板的边框判断。 */
    @GameTest(template = "ddt_empty")
    public static void templateCatalogReusesGenerationMetadata(GameTestHelper helper) throws Exception {
        ResourceLocation id = DawnDuskTerminal.id("r02/reuse/room");
        try (TemplateFixture fixture = new TemplateFixture(helper)) {
            fixture.copy(id, FAMILY + "room/rect/furn_04");
            Object catalog = templateCatalog(fixture.manager);
            variants(catalog, "r02/reuse/");
            variants(catalog, "r02/reuse/room");
            variants(templateCatalog(fixture.manager), "r02/reuse/");
            helper.assertTrue(fixture.manager.listings == 1, "同一资源代次不得跨生成或按前缀重复枚举目录");
            helper.assertTrue(borderFurniture(catalog, id), "测试模板应包含边框家具");
            int queries = fixture.manager.queries;
            helper.assertTrue(borderFurniture(catalog, id), "重复判断的模板内容必须一致");
            helper.assertTrue(fixture.manager.queries == queries, "同一模板的边框判断必须复用");
        }
        helper.succeed();
    }

    /** 诊断在当前管理器的资源代次内去重，重载后重新报告。 */
    @GameTest(template = "ddt_empty")
    public static void templateDiagnosticsAreResourceScoped(GameTestHelper helper) throws Exception {
        try (TemplateFixture fixture = new TemplateFixture(helper)) {
            Object first = templateCatalog(fixture.manager);
            fixture.reload();
            Object second = templateCatalog(fixture.manager);
            assertDiagnosticScope(helper, first, second, "missing", DawnDuskTerminal.id("r02/diagnostic"));
            assertDiagnosticScope(helper, first, second, "partial", "7@1,2");
            Object third = templateCatalog(fixture.manager);
            for (String name : List.of("missing", "partial")) {
                var field = second.getClass().getDeclaredField(name);
                field.setAccessible(true);
                helper.assertTrue(field.get(second) == field.get(third), "同一资源代次的诊断应跨生成复用：" + name);
            }
        }
        helper.succeed();
    }

    @SuppressWarnings("unchecked")
    private static void assertDiagnosticScope(
            GameTestHelper helper, Object first, Object second, String name, Object key) throws Exception {
        var field = first.getClass().getDeclaredField(name);
        field.setAccessible(true);
        Set<Object> initial = (Set<Object>) field.get(first);
        Set<Object> next = (Set<Object>) field.get(second);
        helper.assertTrue(initial.add(key), name + " 的首次诊断必须保留");
        helper.assertFalse(initial.add(key), name + " 在同一资源代次内必须去重");
        helper.assertTrue(next.add(key), name + " 不得压制下一资源代次的诊断");
    }

    /** 比较完整结构件 NBT，覆盖件顺序、ID、位置、旋转及处理器参数。 */
    @GameTest(template = "ddt_empty")
    public static void templateAssemblyIsStableAcrossGenerations(GameTestHelper helper) throws IOException {
        ServerLevel level = helper.getLevel();
        var serialization = StructurePieceSerializationContext.fromLevel(level);
        try (var storage = LevelStorageSource.createDefault(Files.createTempDirectory("ddt-r02-assembly-"))
                .createAccess("templates")) {
            var manager = new CountingTemplateManager(level.getServer().getResourceManager(), storage, level);
            for (String name : List.of("grid_rooms", "grid_rooms_upper", "towerwood_rooms", "towerwood_rooms_upper")) {
                Structure structure = level.registryAccess()
                        .registryOrThrow(Registries.STRUCTURE)
                        .get(DawnDuskTerminal.id(name));
                for (int i = 0; i < 8; i++) {
                    StructureStart first = generateSample(level, structure, manager, i);
                    int parses = manager.parses;
                    StructureStart second = generateSample(level, structure, manager, i);
                    helper.assertTrue(first.isValid() && second.isValid(), name + " 的固定种子样本必须有效");
                    helper.assertTrue(
                            first.getPieces().size() == second.getPieces().size(), "重复生成的结构件数量必须一致");
                    for (int j = 0; j < first.getPieces().size(); j++) {
                        CompoundTag expected = first.getPieces().get(j).createTag(serialization);
                        CompoundTag actual = second.getPieces().get(j).createTag(serialization);
                        helper.assertTrue(actual.equals(expected), name + " 样本 " + i + " 第 " + j + " 件重复生成结果不符");
                    }
                    helper.assertTrue(manager.parses == parses, "重复生成不得绕过原版模板解析缓存");
                }
            }
            helper.assertTrue(manager.listings == 1, "正常生成中的 getOrCreate 读取不得使目录缓存失效");
        }
        helper.succeed();
    }

    private static StructureStart generateSample(
            ServerLevel level, Structure structure, StructureTemplateManager manager, int sample) {
        ChunkGenerator generator = level.getChunkSource().getGenerator();
        return structure.generate(
                level.registryAccess(),
                generator,
                generator.getBiomeSource(),
                level.getChunkSource().randomState(),
                manager,
                CHEST_SAMPLE_SEED + sample,
                new ChunkPos(sample * 13 - 48, sample * 7 - 23),
                0,
                level,
                biome -> true);
    }

    /** 临时目录保留用于失败诊断；关闭资源与存档锁，不删除任何世界文件。 */
    private static final class TemplateFixture implements AutoCloseable {
        private final Path pack;
        private final LevelStorageSource.LevelStorageAccess storage;
        private MultiPackResourceManager resources;
        private final CountingTemplateManager manager;

        private TemplateFixture(GameTestHelper helper) throws IOException {
            Path root = Files.createTempDirectory("ddt-r02-templates-");
            pack = Files.createDirectories(root.resolve("pack"));
            Files.createDirectories(pack.resolve("data/dawnduskterminal"));
            storage = LevelStorageSource.createDefault(root.resolve("worlds")).createAccess("templates");
            resources = openResources();
            manager = new CountingTemplateManager(resources, storage, helper.getLevel());
        }

        private MultiPackResourceManager openResources() {
            var info = new PackLocationInfo("r02", Component.literal("R02"), PackSource.DEFAULT, Optional.empty());
            return new MultiPackResourceManager(PackType.SERVER_DATA, List.of(new PathPackResources(info, pack)));
        }

        private void copy(ResourceLocation id, String source) throws IOException {
            Path file = pack.resolve("data/" + id.getNamespace() + "/structure/" + id.getPath() + ".nbt");
            Files.createDirectories(file.getParent());
            try (InputStream stream = StructureTemplateTests.class.getResourceAsStream(
                    "/data/dawnduskterminal/structure/" + source + ".nbt")) {
                if (stream == null) {
                    throw new IOException("测试模板不存在：" + source);
                }
                Files.copy(stream, file, StandardCopyOption.REPLACE_EXISTING);
            }
        }

        private void reload() {
            MultiPackResourceManager previous = resources;
            resources = openResources();
            manager.onResourceManagerReload(resources);
            previous.close();
        }

        @Override
        public void close() throws IOException {
            try {
                resources.close();
            } finally {
                storage.close();
            }
        }
    }

    private static final class CountingTemplateManager extends StructureTemplateManager {
        private int listings;
        private int queries;
        private int parses;
        private volatile Runnable afterListing;

        private CountingTemplateManager(
                ResourceManager resources, LevelStorageSource.LevelStorageAccess storage, ServerLevel level) {
            super(
                    resources,
                    storage,
                    DataFixers.getDataFixer(),
                    level.registryAccess().lookupOrThrow(Registries.BLOCK));
        }

        @Override
        public Stream<ResourceLocation> listTemplates() {
            listings++;
            Runnable hook = afterListing;
            if (hook == null) {
                return super.listTemplates();
            }
            List<ResourceLocation> snapshot;
            try (var stream = super.listTemplates()) {
                snapshot = stream.toList();
            }
            hook.run();
            return snapshot.stream();
        }

        @Override
        public Optional<StructureTemplate> get(ResourceLocation id) {
            queries++;
            return super.get(id);
        }

        @Override
        public StructureTemplate readStructure(CompoundTag tag) {
            parses++;
            return super.readStructure(tag);
        }
    }

    private static GridTemplateCatalog.Resources templateResources(StructureTemplateManager manager) {
        return ((GridTemplateCatalog.Owner) manager).ddt$templateResources();
    }

    private static Object templateCatalog(StructureTemplateManager manager) {
        try {
            Class<?> type = Class.forName("com.dawnduskterminal.structure.GridTemplateCatalog");
            var constructor = type.getDeclaredConstructor(StructureTemplateManager.class);
            constructor.setAccessible(true);
            return constructor.newInstance(manager);
        } catch (ReflectiveOperationException error) {
            throw new AssertionError("无法创建模板目录视图", error);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<ResourceLocation> variants(Object catalog, String prefix) {
        try {
            Method method = catalog.getClass().getDeclaredMethod("variants", String.class);
            method.setAccessible(true);
            return (List<ResourceLocation>) method.invoke(catalog, prefix);
        } catch (ReflectiveOperationException error) {
            throw new AssertionError("无法读取模板目录", error);
        }
    }

    private static boolean borderFurniture(Object catalog, ResourceLocation id) {
        try {
            Method method = catalog.getClass().getDeclaredMethod("hasBorderFurniture", ResourceLocation.class);
            method.setAccessible(true);
            return (boolean) method.invoke(catalog, id);
        } catch (ReflectiveOperationException error) {
            throw new AssertionError("无法判断模板边框家具", error);
        }
    }

    /** 私有模板元数据通过反射检查，不为测试扩大生产 API。 */
    private static boolean hasBorderFurniture(StructureTemplateManager manager, ResourceLocation id) {
        return borderFurniture(templateCatalog(manager), id);
    }

    /**
     * 塔木那套能真的拼出房子：房间、墙、地基都有，楼梯房自己带着带洞的天花板
     *
     * <p>这套件是手导的，缺件不会报错、只会让房子少一块，所以在这里钉一遍：
     * 每座至少有一件 room、一件 wall，楼梯件必须高到天花板那一行（y=8 的那行就是带洞的天花板），
     * 而 {@code stairtop} 那一族件已经删掉了。
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
            boolean leftover = false;
            for (StructurePiece piece : start.getPieces()) {
                if (!(piece instanceof PoolElementStructurePiece pool)) {
                    continue;
                }
                String path = pool.getElement().toString();
                room |= path.contains("/room/");
                wall |= path.contains("/wall/");
                stair |= path.contains("room/rect/stair");
                leftover |= path.contains("stairtop");
                helper.assertFalse(path.contains("/post/column"), "塔木那族是浮空的，不该摆柱子（结构 JSON 的 posts 应当是 false）");
            }
            helper.assertTrue(room, "第 " + i + " 座应当摆出房间件");
            helper.assertTrue(wall, "第 " + i + " 座应当摆出墙件");
            helper.assertFalse(leftover, "第 " + i + " 座摆出了 stairtop 件，那两件已经删了");
        }
        // 楼梯件自己负责切开天花板：它必须高到 y=8 那一行
        Vec3i stairSize = level.getStructureManager()
                .get(DawnDuskTerminal.id("towerwood_rooms/room/rect/stair"))
                .map(StructureTemplate::getSize)
                .orElse(Vec3i.ZERO);
        helper.assertTrue(stairSize.getY() == 5, "楼梯件应当是 5 高（含天花板那一行），实际 " + stairSize);
        helper.succeed();
    }

    /**
     * 楼梯件自己带着带洞的天花板：最后一行中间是空气，四边与框列都是实的
     *
     * <p>洞口原来是单独一件 `floor/rect/stairtop`，落在上一层的地板上；现在并进楼梯件的最后一行，
     * 由 {@code placeStairs} 在最后一遍重写。件矮一行、或者洞里没有空气，楼梯就永远上不去。
     */
    @GameTest(template = "ddt_empty")
    public static void stairPieceCarriesAHoledCeiling(GameTestHelper helper) {
        int checked = 0;
        for (String name : List.of("grid_rooms", "towerwood_rooms")) {
            StructureTemplate template = helper.getLevel()
                    .getStructureManager()
                    .get(DawnDuskTerminal.id(name + "/room/rect/stair"))
                    .orElseThrow();
            Vec3i size = template.getSize();
            helper.assertTrue(size.getY() == 5, name + " 的楼梯件应当是 5 高（含天花板那一行），实际 " + size);
            int y = size.getY() - 1;
            int air = 0;
            for (StructureTemplate.StructureBlockInfo info :
                    template.filterBlocks(BlockPos.ZERO, new StructurePlaceSettings(), Blocks.AIR, true)) {
                BlockPos pos = info.pos();
                if (pos.getY() != y) {
                    continue;
                }
                helper.assertTrue(
                        pos.getX() > 0
                                && pos.getX() < size.getX() - 1
                                && pos.getZ() > 0
                                && pos.getZ() < size.getZ() - 1,
                        name + " 的洞口不该开到件的边上：" + pos);
                air++;
            }
            helper.assertTrue(air >= 4, name + " 的楼梯件天花板那一行没有洞口，实际空气 " + air + " 格");
            checked++;
        }
        helper.assertTrue(checked == 2, "两族楼梯件都该检查到");
        helper.succeed();
    }

    /**
     * 掉落口 / 绳梯两种房间自带楼板那一行：件 5 高，最低那一行里有洞
     *
     * <p>这两类的楼板与房间是一一搭配的（洞口只对得上自己那间），所以并成了一件；
     * 平铺楼板 `floor/<体型>` 就不该再盖上去，否刚洞会被堵上。
     * 洞口有三种存法：空气、结构空位（不落笔的格）、塔木那族的 `reappearing_block`。
     */
    @GameTest(template = "ddt_empty")
    public static void specialRoomsCarryTheirFloor(GameTestHelper helper) {
        int checked = 0;
        for (String name : List.of("grid_rooms", "towerwood_rooms")) {
            for (String path : List.of("room/large/drop", "room/small/drop_01", "room/small/rope_01")) {
                StructureTemplate template = helper.getLevel()
                        .getStructureManager()
                        .get(DawnDuskTerminal.id(name + "/" + path))
                        .orElse(null);
                if (template == null) {
                    continue;
                }
                Vec3i size = template.getSize();
                helper.assertTrue(size.getY() == 5, name + " 的 " + path + " 应当自带楼板（5 高），实际 " + size);
                int holes = 0;
                // 洞口的存法：空气、结构空位（不落笔的格）、塔木那族踩上就消失的方块，
                // 以及绳梯房里那一格绳子或梯子（洞里挂着绳/梯，一样下得去）
                for (var hole : List.of(
                        Blocks.AIR,
                        Blocks.STRUCTURE_VOID,
                        TFBlocks.REAPPEARING_BLOCK.get(),
                        TFBlocks.ROPE.get(),
                        Blocks.LADDER)) {
                    for (StructureTemplate.StructureBlockInfo info :
                            template.filterBlocks(BlockPos.ZERO, new StructurePlaceSettings(), hole, true)) {
                        if (info.pos().getY() == 0) {
                            holes++;
                        }
                    }
                }
                helper.assertTrue(holes > 0, name + " 的 " + path + " 楼板那一行没有洞口，洞口丢了");
                checked++;
            }
        }
        helper.assertTrue(checked >= 4, "两族加起来至少该检查到 4 件自带楼板的房间，实际 " + checked);
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
                    // 楼梯件整间落一遍，末尾还要只重写它自己那一行天花板（`placeStairs`），
                    // 记成两遍是设计如此；那一行里没有方块实体，也不会再 spawn 实体
                    if (path.contains("room/rect/stair")) {
                        rooms++;
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
     * <p>三件事一起钉：
     *
     * <ul>
     *   <li>件里的宝箱写的 {@code LootTable} 必须在注册表里；
     *   <li>模组自己那几张宝箱表必须逐条写着物品，不许出现 {@code minecraft:loot_table} 嵌套引用：
     *       开箱掉什么要能在 {@code data/dawnduskterminal/loot_table/chests/} 的那一个文件里逐行读到；
     *   <li>表里写的每个 item 都要在物品注册表里——20 个试验物品是逐个手写的，写错一个号不会报错，
     *       只会少一样东西。
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
        // 我们自己那几张表的内容：每个 item 都要能解析，而且不许嵌套引用别的表
        int checked = 0;
        for (String name : List.of(
                "chests/grid_rooms", "chests/towerwood_rooms", "chests/towerwood_furnace", "chests/chrono_mineshaft")) {
            String resource = "/data/dawnduskterminal/loot_table/" + name + ".json";
            try (InputStream in = StructureTemplateTests.class.getResourceAsStream(resource)) {
                helper.assertTrue(in != null, "找不到 " + resource);
                JsonObject json = JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8))
                        .getAsJsonObject();
                for (JsonElement pool : json.getAsJsonArray("pools")) {
                    for (JsonElement element : pool.getAsJsonObject().getAsJsonArray("entries")) {
                        JsonObject loot = element.getAsJsonObject();
                        String type = loot.get("type").getAsString();
                        helper.assertTrue(
                                !"minecraft:loot_table".equals(type),
                                name + " 里嵌套引用了 " + loot.get("value") + "，宝箱内容要逐条写在这张表里");
                        if ("minecraft:item".equals(type)) {
                            ResourceLocation id =
                                    ResourceLocation.parse(loot.get("name").getAsString());
                            helper.assertTrue(BuiltInRegistries.ITEM.containsKey(id), name + " 里的物品 " + id + " 没有注册");
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
                            // 地基在格子地板行（+0），房间件在 +4；自带楼板的那几类低一行在 +3
                            && (inner.minY() == box.minY() + 4 || inner.minY() == box.minY() + 3)
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
                    if (!carriesOwnFloor(path(room))) {
                        assertPlane(helper, name, pieces, box, box.minY() - 1, "/floor/");
                    }
                    // 自带楼板的那几类件从地板行起，天花板也跟着高一格
                    assertPlane(
                            helper, name, pieces, box, box.minY() + (carriesOwnFloor(path(room)) ? 5 : 4), "/ceiling/");
                    cells++;
                }
                helper.assertTrue(cells > 0, name + " 第 " + i + " 座应当摆出房间件");
            }
        }
        helper.succeed();
    }

    /** 自带楼板那一行的房间：掉落口、绳梯（楼板与房间一一搭配，并成了一件；内角仍用共用的 floor/inner_corner） */
    private static boolean carriesOwnFloor(String path) {
        return path.contains("/drop") || path.contains("/rope_");
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
     * 楼梯两端的邻格必须是空地房，楼梯正上方那一格也必须是空地房
     *
     * <p>楼梯件沿长边从一头走到另一头：本层两端是进出口，上一层两端是下楼口，共四格。
     * `markStairEnds` 把这四格记成「必须是空地房」，但它算邻格时拿的是楼梯格自己的跨度，
     * 而一行里的跨度是 5、7、5 混排，往负方向那一侧整整差 2 格，查出来永远是 null——
     * 家具照旧摆进进出口。这里按包围盒找邻格，与 `GridFrame#adjacent` 判据一致：
     * 端上确实有房间，就必须是 `/empty`（凹角格例外，见下）；被切掉的角上没有房间，跳过。
     *
     * <p>正上方那一格（隔一个层高、同一角）必须整间空地：楼梯件的最后一行是带洞的天花板，
     * 洞口正对着它，摆上家具人上不来。
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
                    if (!path(stair).contains("room/rect/stair")) {
                        continue;
                    }
                    // 洞口开给正上方那一格：两件同角、隔一个层高
                    helper.assertTrue(
                            hasPiece(pieces, "/room/", box.minX(), box.minY() + 5, box.minZ())
                                    && hasPiece(pieces, "/empty", box.minX(), box.minY() + 5, box.minZ()),
                            name + " 楼梯 " + box + " 正上方那一格应当是空地房");
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
     * 井段够不到地面时只铺地基那一层
     *
     * <p>上限与柱子共用一条（`SUPPORT_MAX_DROP`）：上限之内一路接到地面；
     * 超过上限或者一路都没落脚点，就只铺 `WELL_ROWS`（3 行，正好是地基那一层），
     * 不在虚空里吊一根到底的绳子。
     */
    @GameTest(template = "ddt_empty")
    public static void wellFallsBackToTheBaseLayer(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        StructureTemplateManager templates = level.getStructureManager();
        StructureManager structures = level.structureManager();
        ChunkGenerator generator = level.getChunkSource().getGenerator();
        // 可写范围按井段件自己所在的区块取：`postProcess` 只往这个盒子里摆方块
        BlockPos anchor = helper.absolutePos(new BlockPos(4, 2, 4));
        ChunkPos chunkPos = new ChunkPos(anchor);
        BoundingBox area = writableArea(level, chunkPos);
        // 摆在区块顶部，往下清 26 格也不会掉出可写范围（`afterPlace` 读到区块外会抛异常）
        BlockPos min = new BlockPos(anchor.getX(), area.maxY() - 2, anchor.getZ());
        // 把下面那一柱清成空气，再把石头埋到 27 格以下：落差超过柱子的上限（24 格）
        for (int down = 1; down <= 26; down++) {
            level.setBlockAndUpdate(min.below(down), Blocks.AIR.defaultBlockState());
        }
        level.setBlockAndUpdate(min.below(27), Blocks.STONE.defaultBlockState());

        PoolElementStructurePiece well = piece(templates, DawnDuskTerminal.id(FAMILY + "post/well"), min);
        well.postProcess(level, structures, generator, level.random, area, chunkPos, min);
        Structure grid = level.registryAccess()
                .registryOrThrow(Registries.STRUCTURE)
                .getOptional(DawnDuskTerminal.id("grid_rooms"))
                .orElseThrow(() -> new IllegalStateException("dawnduskterminal:grid_rooms 没有注册"));
        grid.afterPlace(
                guarded(level, area),
                structures,
                generator,
                level.random,
                area,
                chunkPos,
                new PiecesContainer(List.of(well)));

        StringBuilder dump = new StringBuilder();
        for (int down = 0; down <= 4; down++) {
            dump.append(down)
                    .append('=')
                    .append(level.getBlockState(min.below(down)))
                    .append(' ');
        }
        helper.assertTrue(level.getBlockState(min).is(TFBlocks.ROPE.get()), "井段件自己那一行应当是绳（件没摆上？）：" + dump);
        helper.assertTrue(level.getBlockState(min.below(1)).is(TFBlocks.ROPE.get()), "井段件下面第一行要接上： " + dump);
        helper.assertTrue(
                level.getBlockState(min.below(2)).is(TFBlocks.ROPE.get()),
                "井段最多铺到地基那一层，第二行也该有绳，实际 " + level.getBlockState(min.below(2)));
        helper.assertFalse(
                level.getBlockState(min.below(3)).is(TFBlocks.ROPE.get()),
                "够不到地面时只铺地基那一层，第三行不该再有绳，实际 " + level.getBlockState(min.below(3)));
        helper.succeed();
    }

    /**
     * 井段件贴在绳梯房地板行**下面**一行，而且落在地板那个洞的正下方
     *
     * <p>`post/well*` 摆错一行就会盖掉地板行里的原木或台阶（塔木那族那次就是梯子 + 活板门
     * 顶到了地板那一行），所以这里按包围盒钉：井段件的顶行比房间件的底行低一行，
     * 而且整个落在房间件的 x/z 范围里。
     */
    @GameTest(template = "ddt_empty")
    public static void wellSitsUnderTheFloorRow(GameTestHelper helper) {
        int checked = 0;
        for (String name : List.of("grid_rooms", "towerwood_rooms")) {
            GridStructure grid = gridStructure(helper, name);
            for (int i = 0; i < 6; i++) {
                StructureStart start = generate(helper, grid, 16000L + i, new ChunkPos(i * 13 + 7, 21));
                helper.assertTrue(start.isValid(), name + " 第 " + i + " 座应当生成出来");
                List<PoolElementStructurePiece> pieces = poolPieces(start);
                for (PoolElementStructurePiece well : pieces) {
                    if (!path(well).contains("/post/well")) {
                        continue;
                    }
                    BoundingBox box = well.getBoundingBox();
                    boolean under = pieces.stream()
                            .anyMatch(other -> path(other).contains("/room/")
                                    && other.getBoundingBox().minY() == box.minY() + 1
                                    && other.getBoundingBox().minX() <= box.minX()
                                    && box.maxX() <= other.getBoundingBox().maxX()
                                    && other.getBoundingBox().minZ() <= box.minZ()
                                    && box.maxZ() <= other.getBoundingBox().maxZ());
                    helper.assertTrue(under, name + " 的井段件 " + box + " 应当正好贴在绳梯房地板行下面");
                    checked++;
                }
            }
        }
        helper.assertTrue(checked > 0, "样本里一件井段件都没有，这条用例证明不了什么");
        helper.succeed();
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
                    // 自带楼板的房间比楼梯低一行，所以「是不是底层」按差不到一个层高判
                    if (box.minY() - lowest >= 5 || !path(stair).contains("room/rect/stair")) {
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
     * <p>件整体往区块外挑一格：井段件 {@code post/well_ladder} 的活板门那一列（局部 `0,*,0`）落在区块外，
     * 梯子那一列（局部 `1,*,0`）在区块里。真的 {@code afterPlace} 扫整件包围盒的话，
     * 第一格越界的读就会撞上代理的断言。
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
        BlockPos min = new BlockPos(area.minX() - 1, y, area.minZ() + 1);
        PoolElementStructurePiece shaft = piece(templates, DawnDuskTerminal.id(FAMILY + "post/well_ladder"), min);
        helper.assertTrue(shaft.getBoundingBox().minX() < area.minX(), "测试前提：这件要跨出区块边界，现在没有");

        // 件自己先摆一遍（原版 `StructureStart#placeInChunk` 干的事），`afterPlace` 只负责往下接
        shaft.postProcess(level, structures, generator, level.random, area, chunkPos, min);
        // 摆完之后在区块里扫出梯子那一列（活板门那一列在区块外，本来就该跳过）
        BlockPos rope = null;
        for (int x = area.minX(); x <= area.maxX() && rope == null; x++) {
            for (int z = area.minZ(); z <= area.maxZ() && rope == null; z++) {
                BlockPos candidate = new BlockPos(x, min.getY(), z);
                if (level.getBlockState(candidate).is(Blocks.LADDER)) {
                    rope = candidate;
                }
            }
        }
        helper.assertTrue(rope != null, "件摆完以后区块里应当已经有梯子那一列，实际一格都没找到");
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
                level.getBlockState(below1).is(Blocks.LADDER),
                "梯子要往下接一格，实际 " + level.getBlockState(below1) + " @ " + below1);
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
