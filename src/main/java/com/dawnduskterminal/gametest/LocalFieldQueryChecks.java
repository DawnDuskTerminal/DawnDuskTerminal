package com.dawnduskterminal.gametest;

import com.dawnduskterminal.block.CampCarminiteFireBlock;
import com.dawnduskterminal.registry.ModBlocks;
import com.dawnduskterminal.world.CampCarminiteFireAura;
import com.dawnduskterminal.world.CubeField;
import java.lang.reflect.Field;
import java.util.AbstractSet;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/** 局部场的对照测量与索引检查；所有计数和反射均限定在测试中。 */
final class LocalFieldQueryChecks {
    private static final int QUERIES = 20_000;
    private static volatile int hits;

    private LocalFieldQueryChecks() {}

    static void measure(GameTestHelper helper, boolean aura) throws ReflectiveOperationException {
        try (Fixture fixture = new Fixture(helper, aura)) {
            BlockPos at = helper.absolutePos(new BlockPos(-32, 4, 0));
            fixture.playerAt(Vec3.atCenterOf(at));
            BooleanSupplier query = () -> fixture.covers(at);
            int placed = 0;
            for (int size : new int[] {0, 10, 100, 1000}) {
                while (placed < size) {
                    BlockPos pos = helper.absolutePos(new BlockPos(24 + placed % 8, 4 + placed / 256, placed / 8 % 32));
                    helper.assertTrue(
                            fixture.level.getChunkSource().hasChunk(pos.getX() >> 4, pos.getZ() >> 4), "测量场心必须位于已加载区块");
                    fixture.place(pos);
                    placed++;
                }
                Visits visits = fixture.visits(query);
                runQueries(query);
                long[] samples = new long[5];
                int loaded = fixture.level.getChunkSource().getLoadedChunksCount();
                for (int sample = 0; sample < samples.length; sample++) {
                    long start = System.nanoTime();
                    helper.assertTrue(runQueries(query) == 0, "远端场心不得覆盖固定查询位置");
                    samples[sample] = (System.nanoTime() - start) / QUERIES;
                }
                Arrays.sort(samples);
                System.out.printf(
                        "[R07] field=%s centres=%d candidates=%d bucketLookups=%d loadedChunks=%d queries=%d repeats=5 nsPerQuery=%d min=%d max=%d%n",
                        aura ? "aura" : "cube",
                        size,
                        visits.centres,
                        visits.buckets,
                        loaded,
                        QUERIES,
                        samples[2],
                        samples[0],
                        samples[4]);
                helper.assertTrue(visits.centres == 0, "增加远端场心不得增加本地候选场心数");
                helper.assertTrue(visits.buckets == (size == 0 ? 0 : aura ? 9 : 4), "候选桶数量必须固定且由半径限定");
            }
        }
        helper.succeed();
    }

    static void checkCube(GameTestHelper helper) throws ReflectiveOperationException {
        try (Fixture fixture = new Fixture(helper, false)) {
            BlockPos centre = helper.absolutePos(new BlockPos(15, 4, 15));
            fixture.place(centre);
            for (int dx = -9; dx <= 9; dx++) {
                for (int dy = -9; dy <= 9; dy++) {
                    for (int dz = -9; dz <= 9; dz++) {
                        boolean expected = Math.abs(dx) <= CubeField.RADIUS
                                && Math.abs(dy) <= CubeField.RADIUS
                                && Math.abs(dz) <= CubeField.RADIUS;
                        helper.assertTrue(
                                fixture.covers(centre.offset(dx, dy, dz)) == expected,
                                "分桶后的立方覆盖必须与全量坐标判断一致：" + dx + "," + dy + "," + dz);
                    }
                }
            }
            fixture.checkOverlapping(centre);
            fixture.checkLifecycle(centre);
            fixture.checkUnloaded();
        }
        helper.succeed();
    }

    static void checkAura(GameTestHelper helper) throws ReflectiveOperationException {
        try (Fixture fixture = new Fixture(helper, true)) {
            BlockPos centre = helper.absolutePos(new BlockPos(15, 4, 15));
            fixture.place(centre);
            for (double dx : new double[] {-16.001, -16, -15.999, -12, 0, 12, 15.999, 16, 16.001}) {
                for (double dy : new double[] {-16, 0, 16}) {
                    for (double dz : new double[] {-16.001, -16, 0, 16, 16.001}) {
                        fixture.playerAt(Vec3.atCenterOf(centre).add(dx, dy, dz));
                        boolean expected = dx * dx + dy * dy + dz * dz <= 16 * 16;
                        helper.assertTrue(fixture.covers(centre) == expected, "分桶后的篝火覆盖必须保持方块中心到玩家的欧氏距离判断");
                    }
                }
            }
            fixture.playerAt(Vec3.atCenterOf(centre));
            fixture.level.setBlock(
                    centre,
                    fixture.level.getBlockState(centre).setValue(CampCarminiteFireBlock.LIT, false),
                    Block.UPDATE_CLIENTS);
            helper.assertFalse(fixture.covers(centre), "未点燃的篝火不得生效");
            fixture.level.setBlock(
                    centre,
                    fixture.level.getBlockState(centre).setValue(CampCarminiteFireBlock.LIT, true),
                    Block.UPDATE_CLIENTS);
            var entity = fixture.level.getBlockEntity(centre);
            helper.assertTrue(entity != null, "篝火必须具有方块实体");
            entity.setRemoved();
            helper.assertFalse(fixture.covers(centre), "方块实体卸载回调必须撤登记");
            entity.clearRemoved();
            entity.onLoad();
            helper.assertTrue(fixture.covers(centre), "方块实体重新加载后必须恢复登记");
            fixture.checkOverlapping(centre);
            fixture.checkLifecycle(centre);
            fixture.checkUnloaded();
        }
        helper.succeed();
    }

    private static int runQueries(BooleanSupplier query) {
        int result = 0;
        for (int i = 0; i < QUERIES; i++) {
            if (query.getAsBoolean()) {
                result++;
            }
        }
        hits = result;
        return result;
    }

    private static final class Visits {
        private int centres;
        private int buckets;

        private Set<BlockPos> count(Set<BlockPos> positions) {
            return new AbstractSet<>() {
                @Override
                public Iterator<BlockPos> iterator() {
                    Iterator<BlockPos> source = positions.iterator();
                    return new Iterator<>() {
                        public boolean hasNext() {
                            return source.hasNext();
                        }

                        public BlockPos next() {
                            centres++;
                            return source.next();
                        }

                        public void remove() {
                            source.remove();
                        }
                    };
                }

                @Override
                public int size() {
                    return positions.size();
                }
            };
        }
    }

    private static final class Fixture implements AutoCloseable {
        private final GameTestHelper helper;
        private final ServerLevel level;
        private final CubeField cube = CubeField.of(() -> Blocks.STONE);
        private final Player player;
        private final Map<Object, Object> index;
        private final Object saved;
        private final Map<BlockPos, BlockState> blocks = new LinkedHashMap<>();

        @SuppressWarnings("unchecked")
        private Fixture(GameTestHelper helper, boolean aura) throws ReflectiveOperationException {
            this.helper = helper;
            this.level = helper.getLevel();
            this.player = aura ? helper.makeMockPlayer(GameType.SURVIVAL) : null;
            Field field = (aura ? CampCarminiteFireAura.class : CubeField.class)
                    .getDeclaredField(aura ? "CENTRES" : "centres");
            field.setAccessible(true);
            this.index = (Map<Object, Object>) field.get(aura ? null : this.cube);
            this.saved = this.index.remove(this.level.dimension());
        }

        private void place(BlockPos pos) {
            this.blocks.putIfAbsent(pos.immutable(), this.level.getBlockState(pos));
            this.level.setBlock(
                    pos,
                    (this.player == null ? Blocks.STONE : ModBlocks.CAMP_CARMINITE_FIRE.get()).defaultBlockState(),
                    Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
            track(pos);
        }

        private void track(BlockPos pos) {
            if (this.player == null) {
                this.cube.track(this.level, pos);
            } else {
                CampCarminiteFireAura.track(this.level, pos);
            }
        }

        private void untrack(BlockPos pos) {
            if (this.player == null) {
                this.cube.untrack(this.level, pos);
            } else {
                CampCarminiteFireAura.untrack(this.level, pos);
            }
        }

        private void playerAt(Vec3 pos) {
            if (this.player != null) {
                this.player.setPos(pos);
            }
        }

        private boolean covers(BlockPos at) {
            return this.player == null ? this.cube.covers(this.level, at) : CampCarminiteFireAura.covers(this.player);
        }

        @SuppressWarnings("unchecked")
        private Visits visits(BooleanSupplier query) {
            Visits visits = new Visits();
            Object original = this.index.get(this.level.dimension());
            if (original instanceof Set<?> positions) {
                this.index.put(this.level.dimension(), visits.count((Set<BlockPos>) positions));
            } else if (original instanceof Map<?, ?> buckets) {
                Map<Long, Set<BlockPos>> counted = new HashMap<>() {
                    @Override
                    public Set<BlockPos> get(Object key) {
                        visits.buckets++;
                        return super.get(key);
                    }
                };
                buckets.forEach((key, value) -> counted.put((Long) key, visits.count((Set<BlockPos>) value)));
                this.index.put(this.level.dimension(), counted);
            }
            try {
                query.getAsBoolean();
            } finally {
                if (original == null) {
                    this.index.remove(this.level.dimension());
                } else {
                    this.index.put(this.level.dimension(), original);
                }
            }
            return visits;
        }

        /** 使用同一批已加载场心和查询坐标，对照分桶前的全量遍历。 */
        private void checkOverlapping(BlockPos centre) {
            BlockPos other = centre.offset(-13, 4, -13);
            place(other);
            for (int dx = -17; dx <= 17; dx += 4) {
                for (int dy = -8; dy <= 8; dy += 8) {
                    for (int dz = -17; dz <= 17; dz += 4) {
                        BlockPos at = centre.offset(dx, dy, dz);
                        playerAt(Vec3.atCenterOf(at));
                        this.helper.assertTrue(covers(at) == flatCovers(at), "多场心覆盖必须与原全量遍历一致");
                    }
                }
            }
            untrack(other);
            playerAt(Vec3.atCenterOf(centre));
        }

        private boolean flatCovers(BlockPos at) {
            for (BlockPos centre : this.blocks.keySet()) {
                if (this.player == null) {
                    if (Math.abs(at.getX() - centre.getX()) > CubeField.RADIUS
                            || Math.abs(at.getY() - centre.getY()) > CubeField.RADIUS
                            || Math.abs(at.getZ() - centre.getZ()) > CubeField.RADIUS) {
                        continue;
                    }
                } else {
                    double dx = this.player.getX() - (centre.getX() + 0.5D);
                    double dy = this.player.getY() - (centre.getY() + 0.5D);
                    double dz = this.player.getZ() - (centre.getZ() + 0.5D);
                    if (dx * dx + dy * dy + dz * dz > CampCarminiteFireAura.RADIUS * CampCarminiteFireAura.RADIUS) {
                        continue;
                    }
                }
                if (!this.level.getChunkSource().hasChunk(centre.getX() >> 4, centre.getZ() >> 4)) {
                    continue;
                }
                BlockState state = this.level.getBlockState(centre);
                if (state.is(this.player == null ? Blocks.STONE : ModBlocks.CAMP_CARMINITE_FIRE.get())
                        && (this.player == null || state.getValue(CampCarminiteFireBlock.LIT))) {
                    return true;
                }
            }
            return false;
        }

        private void checkLifecycle(BlockPos centre) {
            untrack(centre);
            BlockPos.MutableBlockPos mutable = centre.mutable();
            track(mutable);
            mutable.move(100, 0, 100);
            this.helper.assertTrue(covers(centre), "登记必须保存不可变坐标，外部修改不得改变场心");
            track(centre);
            untrack(centre);
            untrack(centre);
            this.helper.assertFalse(covers(centre), "重复登记和撤登记必须保持幂等");
            this.helper.assertFalse(this.index.containsKey(this.level.dimension()), "最后一个场心撤登记后必须清除空桶与维度");
            this.level.setBlock(centre, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
            track(centre);
            this.helper.assertFalse(covers(centre), "查询必须清除方块已不存在的幽灵场心");
            this.helper.assertFalse(this.index.containsKey(this.level.dimension()), "幽灵场心清除后不得保留空桶与维度");
        }

        private void checkUnloaded() {
            for (int x : new int[] {-29_000_017, -29_000_016, -29_000_001, 29_000_000, 29_000_015, 29_000_016}) {
                BlockPos pos = new BlockPos(x, 64, x);
                this.helper.assertTrue(
                        this.level.getChunkSource().getChunkNow(x >> 4, x >> 4) == null, "卸载检查必须使用未加载区块");
                track(pos);
                int radius = this.player == null ? CubeField.RADIUS : (int) CampCarminiteFireAura.RADIUS;
                for (int offset : new int[] {-radius, 0, radius}) {
                    BlockPos at = pos.offset(offset, 0, 0);
                    playerAt(Vec3.atCenterOf(at));
                    Visits visits = visits(() -> covers(at));
                    this.helper.assertTrue(visits.centres == 1, "正负坐标与区块边界均必须访问正确的候选桶");
                }
                playerAt(Vec3.atCenterOf(pos));
                this.helper.assertFalse(covers(pos), "未加载场心不得生效");
                this.helper.assertTrue(this.level.getChunkSource().getChunkNow(x >> 4, x >> 4) == null, "查询不得加载未加载区块");
                untrack(pos);
                this.helper.assertFalse(this.index.containsKey(this.level.dimension()), "卸载撤登记后必须清除空桶");
            }
        }

        @Override
        public void close() {
            try {
                this.blocks.forEach((pos, state) ->
                        this.level.setBlock(pos, state, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE));
            } finally {
                this.index.remove(this.level.dimension());
                if (this.saved != null) {
                    this.index.put(this.level.dimension(), this.saved);
                }
            }
        }
    }
}
