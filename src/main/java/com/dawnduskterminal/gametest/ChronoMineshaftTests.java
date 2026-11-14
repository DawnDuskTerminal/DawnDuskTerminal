package com.dawnduskterminal.gametest;

import com.dawnduskterminal.DawnDuskTerminal;
import com.dawnduskterminal.config.DdtConfig;
import com.dawnduskterminal.registry.ModStructures;
import com.dawnduskterminal.structure.ChronoMineshaftPieces;
import com.dawnduskterminal.structure.ChronoMineshaftStructure;
import com.dawnduskterminal.structure.ChronoMineshaftWoods;
import com.dawnduskterminal.world.ChronoLine;
import com.dawnduskterminal.world.ChronoLineState;
import com.dawnduskterminal.world.PaletteBiomeSource;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterList;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.pieces.PiecesContainer;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceType;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * 晨昏线废弃矿井的几道硬约束
 *
 * <p>矿井是复制出来的结构，木材套组按设定挑：黑森林与红草树林用黑木，沼泽类用红树，寒冷区域用苍穹木，
 * 其余暮色橡木。这几条是内容约定，代码里改一行就能错，所以在这里钉住。另一道是方块类型：走廊会在栅栏上
 * 直接 {@code setValue(FenceBlock.WEST, ...)}，选错方块类型是生成时抛异常。
 *
 * <p>群系名单与箱车这两项也钉在这里：名单一旦回到「挑过的群系表」，矿井就会由起点那一格的地下群系决定生
 * 不生；箱车那张表一旦写错，箱车照样刷出来，打开却是空的——而且它必须逐条写着物品，不许嵌套引用别的表。
 */
@GameTestHolder("dawnduskterminal")
@PrefixGameTestTemplate(false)
public final class ChronoMineshaftTests {
    private static final String MOD_ID = "dawnduskterminal";

    private static final ResourceLocation CHRONO_SETTINGS = DawnDuskTerminal.id("chrono");

    private static final long SEED = 0x5EED_1234L;

    private static final TagKey<Biome> PALETTE =
            TagKey.create(Registries.BIOME, ResourceLocation.fromNamespaceAndPath(MOD_ID, "palette_biomes"));

    private static final TagKey<Biome> MINESHAFT_BIOMES =
            TagKey.create(Registries.BIOME, ResourceLocation.fromNamespaceAndPath(MOD_ID, "has_structure/mineshaft"));

    /** 原版的「这些群系里不生矿井」表，本维度不得往里塞调色板群系 */
    private static final TagKey<Biome> MINESHAFT_BLOCKING =
            TagKey.create(Registries.BIOME, ResourceLocation.withDefaultNamespace("mineshaft_blocking"));

    /** 结构注册、件类型注册、群系标签三件套都得对上，否则矿井在维度里一次都不生成 */
    @GameTest(template = "ddt_empty")
    public static void theStructureIsRegisteredOnTheChronoBiomes(GameTestHelper helper) {
        var access = helper.getLevel().getServer().registryAccess();
        Registry<Structure> structures = access.registryOrThrow(Registries.STRUCTURE);
        ResourceLocation id = DawnDuskTerminal.id("mineshaft");
        Structure structure = structures.get(id);
        helper.assertTrue(
                structure instanceof ChronoMineshaftStructure, id + " must be our own mineshaft, got " + structure);
        helper.assertTrue(
                structure.type() == ModStructures.MINESHAFT.get(),
                "the mineshaft must use dawnduskterminal:mineshaft as its structure type, got " + structure.type());

        HolderSet<Biome> biomes = access.registryOrThrow(Registries.BIOME)
                .getTag(MINESHAFT_BIOMES)
                .orElseThrow();
        helper.assertTrue(
                structure.biomes() == biomes,
                "the structure JSON must list the chrono palette through #" + MINESHAFT_BIOMES.location());
        helper.assertTrue(
                biomes.size() >= 10,
                "#" + MINESHAFT_BIOMES.location() + " must list the biome copies, got " + biomes.size());
        for (Holder<Biome> biome : biomes) {
            helper.assertTrue(
                    ChronoMineshaftWoods.isChronoBiome(ChronoMineshaftWoods.biomeId(biome)),
                    "a chrono mineshaft must be listed on chrono biome copies only, got "
                            + ChronoMineshaftWoods.biomeId(biome));
        }

        // 群系名单必须就是整张调色板。矿井的群系判定看的是结构起点那一格，而起点在 y -64 到 -10，
        // 那里是洞穴群系；只列一部分群系就等于让地下群系决定矿井生不生
        Registry<Biome> biomeRegistry = access.registryOrThrow(Registries.BIOME);
        Set<ResourceLocation> missing = ids(biomeRegistry.getTag(PALETTE).orElseThrow());
        missing.removeAll(ids(biomes));
        String missingMessage = "#" + MINESHAFT_BIOMES.location() + " 少了这些调色板群系，矿井会按地下群系生：" + missing;
        helper.assertTrue(missing.isEmpty(), missingMessage);

        // mineshaft_blocking 同理：那条判定会把落在地下洞穴群系里的件整块跳过
        biomeRegistry.getTag(MINESHAFT_BLOCKING).ifPresent(blocked -> {
            for (Holder<Biome> biome : blocked) {
                ResourceLocation blockedId = ChronoMineshaftWoods.biomeId(biome);
                helper.assertTrue(
                        !ChronoMineshaftWoods.isChronoBiome(blockedId),
                        "地下群系 " + blockedId + " 仍然在 #" + MINESHAFT_BLOCKING.location() + " 里挡着矿井");
            }
        });

        // 件类型必须注册在自己的命名空间下，存档里的件才认得回来
        Registry<StructurePieceType> pieceTypes = access.registryOrThrow(Registries.STRUCTURE_PIECE);
        for (var holder : List.of(
                ModStructures.MINESHAFT_ROOM,
                ModStructures.MINESHAFT_CORRIDOR,
                ModStructures.MINESHAFT_CROSSING,
                ModStructures.MINESHAFT_STAIRS)) {
            ResourceLocation key = pieceTypes.getKey(holder.get());
            helper.assertTrue(
                    key != null && MOD_ID.equals(key.getNamespace()),
                    "every mineshaft piece type must be registered under " + MOD_ID + ", got " + key);
        }

        // 结构集也得在：结构与结构集是两样东西，只注册结构不会有任何矿井生成
        StructureSet set = access.registryOrThrow(Registries.STRUCTURE_SET).get(DawnDuskTerminal.id("mineshafts"));
        helper.assertTrue(set != null, "structure set dawnduskterminal:mineshafts is missing");
        helper.assertTrue(
                set.structures().size() == 1
                        && set.structures().get(0).structure().is(id),
                "the structure set must hold exactly " + id + ", got " + set.structures());
        helper.assertTrue(
                set.placement() instanceof RandomSpreadStructurePlacement,
                "the mineshaft must use random_spread, got " + set.placement());
        helper.assertTrue(
                ((RandomSpreadStructurePlacement) set.placement()).spacing() == 1,
                "vanilla mineshafts are placed with spacing 1, the chrono one must match");
        helper.succeed();
    }

    /**
     * 四套木头都得能直接拿来落笔：栅栏要带 {@code WEST}/{@code EAST}（支撑框会设置它们），原木要是柱状方块，
     * 而且四套必须都来自暮色森林——这个维度是暮色森林附属，不该再出现原版橡木
     */
    @GameTest(template = "ddt_empty")
    public static void woodSetsFitWhatThePiecesWrite(GameTestHelper helper) {
        for (ChronoMineshaftWoods woods : ChronoMineshaftWoods.values()) {
            helper.assertTrue(
                    woods.fence().getBlock() instanceof FenceBlock,
                    woods.id() + " must use a fence block, got " + woods.fence());
            helper.assertTrue(
                    woods.fence().hasProperty(FenceBlock.WEST) && woods.fence().hasProperty(FenceBlock.EAST),
                    woods.id() + " fence must carry the WEST and EAST properties the corridor sets");
            helper.assertTrue(
                    woods.log().getBlock() instanceof RotatedPillarBlock,
                    woods.id() + " must use a pillar block for the support columns, got " + woods.log());
            for (var state : List.of(woods.log(), woods.planks(), woods.fence())) {
                ResourceLocation key = BuiltInRegistries.BLOCK.getKey(state.getBlock());
                helper.assertTrue(
                        "twilightforest".equals(key.getNamespace()),
                        woods.id() + " must use twilight forest blocks, got " + key);
            }
            helper.assertTrue(
                    ChronoMineshaftWoods.byId(woods.id()) == woods,
                    "a piece must be able to read back " + woods.id() + " from its own NBT");
        }
        helper.assertTrue(
                ChronoMineshaftWoods.byId(null) == ChronoMineshaftWoods.TWILIGHT_OAK,
                "a missing NBT wood tag must fall back to twilight oak");
        helper.assertTrue(
                ChronoMineshaftWoods.byId("ddt_no_such_wood") == ChronoMineshaftWoods.TWILIGHT_OAK,
                "an unknown wood id must fall back to twilight oak");
        helper.succeed();
    }

    /** 按设定挑木头：黑森林与红草树林是黑木，沼泽类是红树，寒冷区域是苍穹木，其余暮色橡木 */
    @GameTest(template = "ddt_empty")
    public static void biomeRulesFollowTheDesign(GameTestHelper helper) {
        Registry<Biome> biomes = helper.getLevel().getServer().registryAccess().registryOrThrow(Registries.BIOME);
        record Expectation(String biome, ChronoMineshaftWoods woods) {}
        Expectation[] cases = {
            new Expectation("dark_forest", ChronoMineshaftWoods.DARK_WOOD),
            new Expectation("old_growth_birch_forest", ChronoMineshaftWoods.DARK_WOOD),
            new Expectation("swamp", ChronoMineshaftWoods.MANGROVE),
            new Expectation("mangrove_swamp", ChronoMineshaftWoods.MANGROVE),
            new Expectation("snowy_plains", ChronoMineshaftWoods.CANOPY),
            new Expectation("snowy_taiga", ChronoMineshaftWoods.CANOPY),
            new Expectation("ice_spikes", ChronoMineshaftWoods.CANOPY),
            new Expectation("frozen_peaks", ChronoMineshaftWoods.CANOPY),
            new Expectation("grove", ChronoMineshaftWoods.CANOPY),
            new Expectation("blue_desert", ChronoMineshaftWoods.CANOPY),
            new Expectation("plains", ChronoMineshaftWoods.TWILIGHT_OAK),
            new Expectation("sunflower_plains", ChronoMineshaftWoods.TWILIGHT_OAK),
            new Expectation("savanna", ChronoMineshaftWoods.TWILIGHT_OAK),
            new Expectation("stony_shore", ChronoMineshaftWoods.TWILIGHT_OAK),
            new Expectation("mushroom_fields", ChronoMineshaftWoods.TWILIGHT_OAK),
        };
        for (Expectation expectation : cases) {
            Holder<Biome> biome = biomes.getHolder(ResourceKey.create(
                            Registries.BIOME, ResourceLocation.fromNamespaceAndPath(MOD_ID, expectation.biome())))
                    .orElse(null);
            helper.assertTrue(biome != null, "biome " + expectation.biome() + " must be registered");
            helper.assertTrue(
                    ChronoMineshaftWoods.forBiome(biome) == expectation.woods(),
                    expectation.biome() + " must pick " + expectation.woods().id() + ", got "
                            + ChronoMineshaftWoods.forBiome(biome).id());
        }

        // 调色板里每个副本都要落进四套之一，规则与上面一致
        int checked = 0;
        for (Holder<Biome> biome : biomes.getTag(PALETTE).orElseThrow()) {
            String path = biome.unwrapKey().map(key -> key.location().getPath()).orElse("");
            ChronoMineshaftWoods expected = ChronoMineshaftWoods.forBiome(biome);
            boolean dark = path.equals("dark_forest") || path.equals("old_growth_birch_forest");
            boolean mangrove = path.equals("swamp") || path.equals("mangrove_swamp");
            boolean cold = biome.value().getBaseTemperature() < ChronoMineshaftWoods.COLD_TEMPERATURE;
            ChronoMineshaftWoods wanted = dark
                    ? ChronoMineshaftWoods.DARK_WOOD
                    : mangrove
                            ? ChronoMineshaftWoods.MANGROVE
                            : cold ? ChronoMineshaftWoods.CANOPY : ChronoMineshaftWoods.TWILIGHT_OAK;
            helper.assertTrue(
                    expected == wanted, path + " resolved to " + expected.id() + " but the design says " + wanted.id());
            checked++;
        }
        helper.assertTrue(checked > 40, "the palette tag must resolve to the biome copies, got " + checked);
        helper.succeed();
    }

    /**
     * 整条链路跑一遍：用真实的晨昏线生成器造矿井，看每个件是不是都拿到同一套木头、是不是都压在 y 带里，
     * 以及件存盘再读回来之后套组还在不在
     *
     * <p>游戏测试的世界是原版超平坦，没有晨昏线维度，所以按维度 JSON 的写法手工拼一个生成器：
     * {@code PaletteBiomeSource} 加 {@code dawnduskterminal:chrono} 噪声设置
     */
    @GameTest(template = "ddt_empty")
    public static void piecesCarryTheSurfaceWoodSetAndSurviveTheRoundTrip(GameTestHelper helper) {
        var access = helper.getLevel().getServer().registryAccess();
        Registry<Biome> biomes = access.registryOrThrow(Registries.BIOME);
        Holder<MultiNoiseBiomeSourceParameterList> preset = access.registryOrThrow(
                        Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST)
                .getHolderOrThrow(ResourceKey.create(
                        Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST,
                        ResourceLocation.withDefaultNamespace("overworld")));
        Holder<NoiseGeneratorSettings> settings = access.registryOrThrow(Registries.NOISE_SETTINGS)
                .getHolderOrThrow(ResourceKey.create(Registries.NOISE_SETTINGS, CHRONO_SETTINGS));
        PaletteBiomeSource source =
                new PaletteBiomeSource(preset, biomes.getTag(PALETTE).orElseThrow());
        NoiseBasedChunkGenerator generator = new NoiseBasedChunkGenerator(source, settings);
        RandomState randomState = RandomState.create(settings.value(), access.lookupOrThrow(Registries.NOISE), SEED);
        ChronoMineshaftStructure mineshaft = new ChronoMineshaftStructure(
                new Structure.StructureSettings(biomes.getTag(MINESHAFT_BIOMES).orElseThrow()));
        StructurePieceSerializationContext saveContext =
                StructurePieceSerializationContext.fromLevel(helper.getLevel());

        // 群系源选格要看晨昏线；测试服本来有一条，这里钉成固定的那条，跑完再放回去
        ChronoLine previous = ChronoLineState.hasServerLine() ? ChronoLineState.serverLine() : null;
        ChronoLineState.setServerLine(ChronoLine.fallback());
        Set<ChronoMineshaftWoods> chosen = new LinkedHashSet<>();
        try {
            for (ChunkPos chunk :
                    new ChunkPos[] {new ChunkPos(0, 0), new ChunkPos(-6000, 1500), new ChunkPos(7000, -2500)}) {
                Structure.GenerationContext context = new Structure.GenerationContext(
                        access,
                        generator,
                        source,
                        randomState,
                        helper.getLevel().getStructureManager(),
                        new WorldgenRandom(new LegacyRandomSource(SEED)),
                        SEED,
                        chunk,
                        helper.getLevel(),
                        biome -> true);
                Optional<Structure.GenerationStub> stub = mineshaft.findGenerationPoint(context);
                helper.assertTrue(stub.isPresent(), "the chrono mineshaft must produce a stub at " + chunk);

                Holder<Biome> biome = ChronoMineshaftWoods.surfaceBiome(context);
                helper.assertTrue(biome != null, "the surface biome at " + chunk + " must resolve");
                helper.assertTrue(
                        ChronoMineshaftWoods.isChronoBiome(ChronoMineshaftWoods.biomeId(biome)),
                        "the surface biome at " + chunk + " must come from the chrono palette, got "
                                + ChronoMineshaftWoods.biomeId(biome));
                ChronoMineshaftWoods expected = ChronoMineshaftWoods.atSurface(context);
                chosen.add(expected);

                PiecesContainer container = stub.get().getPiecesBuilder().build();
                List<net.minecraft.world.level.levelgen.structure.StructurePiece> pieces = container.pieces();
                helper.assertTrue(
                        pieces.size() > 1,
                        "a mineshaft must grow beyond the centre room at " + chunk + ", got " + pieces.size());
                for (var piece : pieces) {
                    helper.assertTrue(
                            piece instanceof ChronoMineshaftPieces.MineShaftPiece,
                            "every piece must be one of ours, got " + piece);
                    ChronoMineshaftWoods woods = ((ChronoMineshaftPieces.MineShaftPiece) piece).woods();
                    helper.assertTrue(
                            woods == expected,
                            "one shaft must use one wood set: " + ChronoMineshaftWoods.biomeId(biome) + " maps to "
                                    + expected.id() + ", but " + piece + " carries " + woods.id());
                }

                var box = container.calculateBoundingBox();
                helper.assertTrue(
                        box.minY() >= ChronoMineshaftStructure.BAND_MIN_Y
                                && box.maxY() <= ChronoMineshaftStructure.BAND_MAX_Y,
                        "the mineshaft at " + chunk + " must stay inside y " + ChronoMineshaftStructure.BAND_MIN_Y
                                + ".." + ChronoMineshaftStructure.BAND_MAX_Y + ", got " + box);

                Tag saved = container.save(saveContext);
                PiecesContainer loaded = PiecesContainer.load((ListTag) saved, saveContext);
                helper.assertTrue(
                        loaded.pieces().size() == pieces.size(),
                        "every piece must survive the trip through NBT, " + pieces.size() + " became "
                                + loaded.pieces().size());
                for (var piece : loaded.pieces()) {
                    helper.assertTrue(
                            piece instanceof ChronoMineshaftPieces.MineShaftPiece,
                            "a loaded piece lost its type: " + piece);
                    helper.assertTrue(
                            ((ChronoMineshaftPieces.MineShaftPiece) piece).woods() == expected,
                            "a loaded piece must keep the wood set " + expected.id() + ", got "
                                    + ((ChronoMineshaftPieces.MineShaftPiece) piece)
                                            .woods()
                                            .id());
                }
            }
        } finally {
            if (previous != null) {
                ChronoLineState.setServerLine(previous);
            } else {
                ChronoLineState.resetServerLine();
            }
        }
        helper.assertTrue(
                chosen.size() > 1, "three far apart shafts must not all end up on the same wood set, got " + chosen);
        helper.succeed();
    }

    /**
     * 箱车里的东西必须逐条写在 {@code chests/chrono_mineshaft.json} 里，内容对着暮色森林大型空心矿山
     *
     * <p>三件事一起钉：
     *
     * <ul>
     *   <li>表里不许出现 {@code minecraft:loot_table} 这种嵌套引用——宝箱掉什么要能在那一个文件里逐行读到；
     *   <li>三个池的投掷次数必须是大型空心矿山那三档 4 / 2 / 1；
     *   <li>几样招牌货得在：火把浆果、铁木稿、魔法地图、月虫女王、铁木块、金粒。
     * </ul>
     *
     * <p>最后再真抽几次：表 id 写错、物品 id 写错，游戏都不会报错，箱车照常刷出来、打开是空的
     */
    @GameTest(template = "ddt_empty")
    public static void minecartsCarryTheLargeHollowHillLoot(GameTestHelper helper) {
        HolderGetter<LootTable> tables =
                helper.getLevel().getServer().reloadableRegistries().lookup().lookupOrThrow(Registries.LOOT_TABLE);
        Holder<LootTable> table = tables.get(ChronoMineshaftPieces.LOOT).orElse(null);
        helper.assertTrue(table != null, "箱车写的 " + ChronoMineshaftPieces.LOOT.location() + " 没有被加载");

        String resource = "/data/dawnduskterminal/loot_table/chests/chrono_mineshaft.json";
        Set<String> names = new LinkedHashSet<>();
        List<Double> rolls = new ArrayList<>();
        try (InputStream in = ChronoMineshaftTests.class.getResourceAsStream(resource)) {
            helper.assertTrue(in != null, "找不到 " + resource);
            JsonObject json = JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8))
                    .getAsJsonObject();
            for (JsonElement pool : json.getAsJsonArray("pools")) {
                JsonObject poolObject = pool.getAsJsonObject();
                rolls.add(poolObject.get("rolls").getAsDouble());
                for (JsonElement element : poolObject.getAsJsonArray("entries")) {
                    JsonObject entry = element.getAsJsonObject();
                    String type = entry.get("type").getAsString();
                    helper.assertTrue(
                            !"minecraft:loot_table".equals(type),
                            resource + " 里出现了嵌套引用 " + entry.get("value") + "，宝箱内容要逐条写在表里");
                    helper.assertTrue(
                            "minecraft:item".equals(type) || "minecraft:empty".equals(type),
                            resource + " 里的 " + type + " 不是这一张表该有的条目");
                    if ("minecraft:item".equals(type)) {
                        names.add(entry.get("name").getAsString());
                    }
                }
            }
        } catch (IOException error) {
            throw new AssertionError(resource + " 读不出来", error);
        }

        helper.assertTrue(rolls.equals(List.of(4.0D, 2.0D, 1.0D)), "三个池的投掷次数应当是大型空心矿山的 4 / 2 / 1，实际 " + rolls);
        for (String wanted : List.of(
                "twilightforest:torchberries",
                "twilightforest:ironwood_pickaxe",
                "twilightforest:magic_map",
                "twilightforest:moonworm_queen",
                "twilightforest:ironwood_block",
                "minecraft:gold_nugget")) {
            helper.assertTrue(names.contains(wanted), resource + " 少了大空心矿山的 " + wanted);
        }

        // 真抽几次：抽得出东西才算「货在」
        LootParams params = new LootParams.Builder(helper.getLevel())
                .withParameter(LootContextParams.ORIGIN, Vec3.atCenterOf(helper.absolutePos(new BlockPos(4, 0, 4))))
                .create(LootContextParamSets.CHEST);
        for (long seed : new long[] {1L, 7L, 42L}) {
            var items = table.value().getRandomItems(params, seed);
            helper.assertTrue(!items.isEmpty(), "种子 " + seed + " 抽出来的箱车是空的");
            helper.assertTrue(items.size() >= 3, "三个池一共至少 7 堆东西，种子 " + seed + " 只抽到 " + items.size() + " 堆");
        }
        helper.succeed();
    }

    /**
     * 箱车概率是配置项，默认与原版的 1/100 一致
     *
     * <p>抽取顺序修好之后（见 {@code ChronoMineshaftPieces#placeChestMinecarts}），概率按原版的意思生效就够了；
     * 这里只钉取值范围，免得配置项被写成 0（一只箱车都不出）或超过 1
     */
    @GameTest(template = "ddt_empty")
    public static void chestChanceIsSane(GameTestHelper helper) {
        double chance = DdtConfig.mineshaftChestChance();
        helper.assertTrue(chance > 0.0D, "箱车概率不能是 0，当前 " + chance);
        helper.assertTrue(chance <= 1.0D, "箱车概率不能超过 1，当前 " + chance);
        helper.succeed();
    }

    /** 一组群系 holder 的 id 集合，比较两张标签用 */
    private static Set<ResourceLocation> ids(HolderSet<Biome> biomes) {
        Set<ResourceLocation> ids = new LinkedHashSet<>();
        for (Holder<Biome> biome : biomes) {
            ids.add(ChronoMineshaftWoods.biomeId(biome));
        }
        return ids;
    }
}
