package com.dawnduskterminal.gametest;

import com.dawnduskterminal.DawnDuskTerminal;
import com.dawnduskterminal.structure.BandProcessor;
import com.dawnduskterminal.structure.GridStructure;
import com.dawnduskterminal.structure.StructureExclusions;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.HashSet;
import java.util.List;
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
    /** 这件是 1×7×1 的纯空气，凹口那一列靠它擦 */
    private static final String NOTCH = "air/notch";

    /** 宝箱测试的样本量与固定种子：定死才不会随测试世界的种子飘 */
    private static final int CHEST_SAMPLES = 12;

    /** 带箱子的那间房件：箱子是件里自带的方块 */
    private static final String CHEST_ROOM = "room/rect_furn_06";

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
     * 宝箱房保底：任意一座网格屋都至少有一间 `room/rect_furn_06`（那间房自带一只箱子），层数落在 1..3
     *
     * <p>箱子不是另外摆的，是宝箱房件里原有的方块——这里只钉住"至少有一次挑中那间房"。
     * 用固定种子直接生成 {@value #CHEST_SAMPLES} 座；生成走 {@code Structure#generate}，
     * 不必真的铺区块，`biome -> true` 跳过群系检查。
     */
    @GameTest(template = "ddt_empty")
    public static void everyGridStructureHasAChestRoom(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Structure structure =
                level.registryAccess().registryOrThrow(Registries.STRUCTURE).get(DawnDuskTerminal.id("grid_rooms"));
        ChunkGenerator generator = level.getChunkSource().getGenerator();
        int baseX = helper.absolutePos(BlockPos.ZERO).getX();
        int baseZ = helper.absolutePos(BlockPos.ZERO).getZ();
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
                }
            }
            helper.assertTrue(chestRooms >= 1, "整座结构至少该有一间宝箱房，实际 " + chestRooms + "（" + chunkPos + "）");
            int levels = roomHeights.size();
            helper.assertTrue(levels >= 1 && levels <= 3, "层数应当落在 1..3，实际 " + levels + "（" + chunkPos + "）");
        }
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
        PoolElementStructurePiece shaft = piece(templates, DawnDuskTerminal.id("shaft/small_rope_03"), min);
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
