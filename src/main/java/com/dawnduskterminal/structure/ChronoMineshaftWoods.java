package com.dawnduskterminal.structure;

import com.dawnduskterminal.DawnDuskTerminal;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import javax.annotation.Nullable;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.Structure;
import twilightforest.init.TFBlocks;

/**
 * 晨昏线废弃矿井用的木材套组，一共四套，全部来自暮色森林
 *
 * <p>原版矿井的木材写死在 {@code MineshaftStructure.Type} 里，只有橡木与深色橡木两个值。这里换成四套：
 * 暮色橡木、黑木、苍穹木、红树。落笔点见 {@link ChronoMineshaftPieces}，挑哪一套由<b>中心房间那一列的地表
 * 群系</b>决定（{@link #atSurface}）
 *
 * <p>规则按设定写死，不做自动推断：
 * <ul>
 *   <li>{@code dark_forest}、{@code old_growth_birch_forest}：黑木</li>
 *   <li>沼泽类（{@code swamp}、{@code mangrove_swamp}）：红树</li>
 *   <li>寒冷区域（气温低于 {@link #COLD_TEMPERATURE}，即原版判「会下雪」的那条线）：苍穹木</li>
 *   <li>其余一律暮色橡木</li>
 * </ul>
 * 前两条先于气温判断：黑森林与沼泽都不冷，但设定上就该是黑木与红树。
 *
 * <p>读地表群系而不是房间所在高度的群系：房间被压进 y -64 到 -10（{@link ChronoMineshaftStructure}），
 * 那一层上是滴水石洞、繁茂洞穴一类，与地面上长的树无关。
 */
public enum ChronoMineshaftWoods {
    TWILIGHT_OAK("twilight_oak", TFBlocks.TWILIGHT_OAK_LOG, TFBlocks.TWILIGHT_OAK_PLANKS, TFBlocks.TWILIGHT_OAK_FENCE),
    DARK_WOOD("dark_wood", TFBlocks.DARK_LOG, TFBlocks.DARK_PLANKS, TFBlocks.DARK_FENCE),
    CANOPY("canopy", TFBlocks.CANOPY_LOG, TFBlocks.CANOPY_PLANKS, TFBlocks.CANOPY_FENCE),
    MANGROVE("mangrove", TFBlocks.MANGROVE_LOG, TFBlocks.MANGROVE_PLANKS, TFBlocks.MANGROVE_FENCE);

    /** 原版判「这一格会不会下雪」用的门槛，暖温带以上不算寒冷 */
    public static final double COLD_TEMPERATURE = 0.15D;

    /** 黑木：这两处的地面是暮色森林的黑森林与红草树林 */
    private static final Set<String> DARK_WOOD_BIOMES = Set.of("dark_forest", "old_growth_birch_forest");

    /** 红树：火沼泽与其同类 */
    private static final Set<String> MANGROVE_BIOMES = Set.of("swamp", "mangrove_swamp");

    /** 兜底，也是表外群系的套组 */
    private static final ChronoMineshaftWoods DEFAULT = TWILIGHT_OAK;

    private static final Map<String, ChronoMineshaftWoods> BY_ID = new HashMap<>();

    static {
        for (ChronoMineshaftWoods woods : values()) {
            BY_ID.put(woods.id, woods);
        }
    }

    private final String id;
    private final Supplier<? extends Block> log;
    private final Supplier<? extends Block> planks;
    private final Supplier<? extends Block> fence;

    ChronoMineshaftWoods(
            String id,
            Supplier<? extends Block> log,
            Supplier<? extends Block> planks,
            Supplier<? extends Block> fence) {
        this.id = id;
        this.log = log;
        this.planks = planks;
        this.fence = fence;
    }

    /** 写进件 NBT 的名字，也是唯一标识；不是资源 ID，所以没有命名空间 */
    public String id() {
        return this.id;
    }

    /** 支撑柱与柱子：原版这里读 {@code Type#getWoodState} */
    public BlockState log() {
        return this.log.get().defaultBlockState();
    }

    /** 地板、横梁与楼板：原版这里读 {@code Type#getPlanksState} */
    public BlockState planks() {
        return this.planks.get().defaultBlockState();
    }

    /** 支撑框与吊链挂点：原版这里读 {@code Type#getFenceState} */
    public BlockState fence() {
        return this.fence.get().defaultBlockState();
    }

    /** 反查 NBT 里的名字；认不出来一律按暮色橡木，省得旧存档里的件没木头可摆 */
    public static ChronoMineshaftWoods byId(@Nullable String id) {
        if (id == null) {
            return DEFAULT;
        }
        return BY_ID.getOrDefault(id, DEFAULT);
    }

    /** 按群系挑一套，规则见类注释 */
    public static ChronoMineshaftWoods forBiome(@Nullable Holder<Biome> biome) {
        if (biome == null) {
            return DEFAULT;
        }
        String path = biome.unwrapKey().map(key -> key.location().getPath()).orElse("");
        if (DARK_WOOD_BIOMES.contains(path)) {
            return DARK_WOOD;
        }
        if (MANGROVE_BIOMES.contains(path)) {
            return MANGROVE;
        }
        if (biome.value().getBaseTemperature() < COLD_TEMPERATURE) {
            return CANOPY;
        }
        return DEFAULT;
    }

    /** 中心房间那一列的地表群系决定整套木头 */
    public static ChronoMineshaftWoods atSurface(Structure.GenerationContext context) {
        return forBiome(surfaceBiome(context));
    }

    /**
     * 中心房间那一列的地表群系
     *
     * <p>列坐标与 {@code generatePiecesAndAdjust} 造房间时用的坐标一致（{@code chunkPos.getBlockX(2)}、
     * {@code getBlockZ(2)}）。高度取 {@code WORLD_SURFACE_WG}：树与草不会把列顶撑高，判出来的就是这片地面
     * 本身的群系
     */
    @Nullable
    public static Holder<Biome> surfaceBiome(Structure.GenerationContext context) {
        ChunkPos chunk = context.chunkPos();
        int x = chunk.getBlockX(2);
        int z = chunk.getBlockZ(2);
        int surface = context.chunkGenerator()
                .getBaseHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG, context.heightAccessor(), context.randomState());
        return context.biomeSource()
                .getNoiseBiome(
                        QuartPos.fromBlock(x),
                        QuartPos.fromBlock(surface),
                        QuartPos.fromBlock(z),
                        context.randomState().sampler());
    }

    /** 日志与校验用：群系 ID，取不到 key 时返回 null */
    @Nullable
    public static ResourceLocation biomeId(@Nullable Holder<Biome> biome) {
        return biome == null
                ? null
                : biome.unwrapKey().map(ResourceKey::location).orElse(null);
    }

    /** 调色板副本的命名空间；判「是不是晨昏线自己的群系」时用 */
    public static boolean isChronoBiome(@Nullable ResourceLocation biome) {
        return biome != null && DawnDuskTerminal.MOD_ID.equals(biome.getNamespace());
    }
}
