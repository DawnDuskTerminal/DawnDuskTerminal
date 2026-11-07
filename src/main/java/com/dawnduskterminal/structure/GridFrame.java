package com.dawnduskterminal.structure;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * 框架的平面规划，只算坐标与取向，不碰方块、模板与 Minecraft 的任何类
 *
 * <p>短边 {@link #S} 与长边 {@link #L} 是框架到框架的净跨度，相邻两个房间共用同一条框架，
 * 所以步进是「边长减一」。格子按列宽、行深统一铺开：首尾两条边是 {@link #S}，中间一律 {@link #L}。
 * 这条统一性不是随手取的——共用框架要求相邻边等长，而件只有 5 与 7 两种跨度，
 * 一旦让某一格单独收窄 2 格，上下左右都得跟着收，最后整栋塌成 5×5。
 *
 * <p>轮廓是「大矩形减四个角的切块」：每个角两条边各缩进 0%–100%，互相独立。
 * 每个切块按阶梯语法落地——内角件占一个内部格，往角走一条臂摆 rect、最外一格摆 small，
 * 臂外那一整块挖掉。朝外的边要么是 5、要么是整条 7，不会出现 5 与 7 共线。
 */
public final class GridFrame {
    /** 短边 */
    public static final int S = 5;

    /** 长边 */
    public static final int L = 7;

    private GridFrame() {}

    public enum Kind {
        SMALL("small", S, S),
        RECT("rect", S, L),
        LARGE("large", L, L),
        INNER_CORNER("inner_corner", L, L);

        private final String path;
        private final int width;
        private final int depth;

        Kind(String path, int width, int depth) {
            this.path = path;
            this.width = width;
            this.depth = depth;
        }

        public String path() {
            return this.path;
        }

        public int width() {
            return this.width;
        }

        public int depth() {
            return this.depth;
        }
    }

    /** 一格框架。{@code x}/{@code z} 是西北那条框架的位置，{@code w}/{@code d} 是净跨度 */
    public record Cell(int x, int z, int w, int d, Kind kind, int turns) {
        public int maxX() {
            return this.x + this.w;
        }

        public int maxZ() {
            return this.z + this.d;
        }

        public boolean contains(int px, int pz) {
            return px >= this.x && px < maxX() && pz >= this.z && pz < maxZ();
        }
    }

    /** 一个内角件：凹角朝哪个角、落在哪个内部格 */
    public record Spec(Corner corner, int column, int row) {}

    /** 内角件的凹角朝哪个建筑角 */
    public enum Corner {
        NORTH_WEST(-1, -1, 2),
        NORTH_EAST(1, -1, 3),
        SOUTH_WEST(-1, 1, 1),
        SOUTH_EAST(1, 1, 0);

        private final int dx;
        private final int dz;
        private final int turns;

        Corner(int dx, int dz, int turns) {
            this.dx = dx;
            this.dz = dz;
            this.turns = turns;
        }

        public int dx() {
            return this.dx;
        }

        public int dz() {
            return this.dz;
        }

        public int turns() {
            return this.turns;
        }

        /** 这一格是否落在被挖掉的那一块：两个方向都比内角件更靠外 */
        public boolean removed(int column, int row, int innerColumn, int innerRow) {
            boolean outX = this.dx > 0 ? column > innerColumn : column < innerColumn;
            boolean outZ = this.dz > 0 ? row > innerRow : row < innerRow;
            return outX && outZ;
        }

        /** 是否在同一条臂上：与内角件同一列或同一行，且在朝外那一侧 */
        public boolean onArm(int column, int row, int innerColumn, int innerRow) {
            boolean outX = this.dx > 0 ? column > innerColumn : column < innerColumn;
            boolean outZ = this.dz > 0 ? row > innerRow : row < innerRow;
            return (column == innerColumn && outZ) || (row == innerRow && outX);
        }

        /** 臂上最靠外的那一格，摆 small */
        public boolean outermost(int column, int row, int innerColumn, int innerRow, int columns, int rows) {
            if (column == innerColumn && (this.dz > 0 ? row > innerRow : row < innerRow)) {
                return row == (this.dz > 0 ? rows - 1 : 0);
            }
            if (row == innerRow && (this.dx > 0 ? column > innerColumn : column < innerColumn)) {
                return column == (this.dx > 0 ? columns - 1 : 0);
            }
            return false;
        }
    }

    /**
     * 两格沿 x 或 z 贴边：共一整条边
     *
     * <p>{@code maxX}/{@code maxZ} 是末格加一，所以贴边是差一格。
     * 另一条轴上还要求至少两格重叠：格子的步进是「边长减一」，只搭上一格的算共角，不算相连。
     */
    public static boolean adjacent(Cell a, Cell b) {
        int overlapX = Math.min(a.maxX(), b.maxX()) - Math.max(a.x(), b.x());
        int overlapZ = Math.min(a.maxZ(), b.maxZ()) - Math.max(a.z(), b.z());
        return (overlapX >= 2 && (a.z() == b.maxZ() - 1 || b.z() == a.maxZ() - 1))
                || (overlapZ >= 2 && (a.x() == b.maxX() - 1 || b.x() == a.maxX() - 1));
    }

    /** 两格重叠，或者中间只隔一格；用来给跨层房间留空档 */
    public static boolean touches(Cell a, Cell b) {
        return a.x() - 1 < b.maxX() && b.x() - 1 < a.maxX() && a.z() - 1 < b.maxZ() && b.z() - 1 < a.maxZ();
    }

    /**
     * 一层怎么铺：大矩形减四个角的切块
     *
     * <p>每个角独立挖：x 边与 y 边各取矩形边长的 0%–100% 作为收缩量，收到 0 的那条边等于不挖。
     * 于是同一栋里既有整块不切的角，也有切成窄条的角。切块交给 {@link #plan(int, int, List)} 按阶梯语法落地。
     */
    public static List<Cell> plan(long seed, int columns, int rows) {
        Random random = new Random(seed);
        List<Spec> specs = new ArrayList<>();
        for (Corner corner : Corner.values()) {
            // 挖角就是两条边各自收缩：x、y 各取 0%–100% 的矩形边长，互相独立。
            // 收到 0 的那条边等于不挖，两边都收得动才落下这一角
            int innerColumn = Math.min((int) Math.round(random.nextDouble() * (columns - 1)), columns - 2);
            int innerRow = Math.min((int) Math.round(random.nextDouble() * (rows - 1)), rows - 2);
            if (innerColumn < 1 || innerRow < 1) {
                continue;
            }
            specs.add(new Spec(corner, innerColumn, innerRow));
        }
        return plan(columns, rows, specs);
    }

    /**
     * 按内角清单铺一张框架图
     *
     * <p>多个内角按顺序占位，先到先得；两个内角的臂或挖块相撞时，后来的整个丢掉。
     */
    public static List<Cell> plan(int columns, int rows, List<Spec> inners) {
        if (columns < 3 || rows < 3) {
            throw new IllegalArgumentException("need at least 3 columns and 3 rows, got " + columns + "x" + rows);
        }
        int[] widths = edges(columns);
        int[] depths = edges(rows);
        int[] xs = offsets(widths);
        int[] zs = offsets(depths);

        Claim claims = new Claim(columns, rows);
        for (Spec spec : inners) {
            claims.add(spec);
        }

        List<Cell> cells = new ArrayList<>();
        for (int i = 0; i < columns; i++) {
            for (int j = 0; j < rows; j++) {
                Claim.Role role = claims.role(i, j);
                if (role == Claim.Role.CUT) {
                    continue;
                }
                int w = widths[i];
                int d = depths[j];
                if (role == Claim.Role.INNER) {
                    cells.add(new Cell(xs[i], zs[j], w, d, Kind.INNER_CORNER, claims.turns(i, j)));
                    continue;
                }
                if (role == Claim.Role.ARM) {
                    cells.add(arm(claims.arm(i, j), xs[i], zs[j], w, d));
                    continue;
                }
                cells.add(new Cell(xs[i], zs[j], w, d, kindOf(w, d), turnsOf(w, d)));
            }
        }
        return cells;
    }

    /**
     * 臂上的一格：最外那格摆 small，其余摆 rect
     *
     * <p>件比规划格窄 2 格时，格子本身就按件的位置与大小重新铺：宽窄不一致的规划格会在臂旁边留下一道
     * 2 格宽的空带，那道空带既不是房间也不是外面，墙会沿着它长到建筑内部去。
     */
    private static Cell arm(Claim.Arm arm, int x, int z, int w, int d) {
        Corner corner = arm.corner();
        int shiftX = 0;
        int shiftZ = 0;
        if (arm.alongColumn()) {
            shiftX = corner.dx() > 0 || w != L ? 0 : L - S;
        } else {
            shiftZ = corner.dz() < 0 && d == L ? L - S : 0;
        }
        if (arm.outermost()) {
            return new Cell(x + shiftX, z + shiftZ, S, S, Kind.SMALL, 0);
        }
        // rect 默认 5 宽 7 深，沿列那条臂正好用得上；沿行时要转 90°，占地就变成 7 宽 5 深
        int turns = arm.alongColumn() ? 0 : 1;
        return new Cell(x + shiftX, z + shiftZ, turns == 0 ? S : L, turns == 0 ? L : S, Kind.RECT, turns);
    }

    private static Kind kindOf(int width, int depth) {
        if (width == S && depth == S) {
            return Kind.SMALL;
        }
        if (width == L && depth == L) {
            return Kind.LARGE;
        }
        return Kind.RECT;
    }

    /** 长边落在 x 上的长房间要转 90°，框架件是按长边沿 z 建模的 */
    private static int turnsOf(int width, int depth) {
        return width == L && depth == S ? 1 : 0;
    }

    /** 首尾两条边是短边，中间一律长边 */
    private static int[] edges(int count) {
        int[] out = new int[count];
        for (int i = 0; i < count; i++) {
            out[i] = (i == 0 || i == count - 1) ? S : L;
        }
        return out;
    }

    /** 相邻两条框架线之间的步进是边长减一 */
    private static int[] offsets(int[] edges) {
        int[] out = new int[edges.length];
        int cursor = 0;
        for (int i = 0; i < edges.length; i++) {
            out[i] = cursor;
            cursor += edges[i] - 1;
        }
        return out;
    }

    /** 占位表：内角格、两条臂、臂外那一整块 */
    private static final class Claim {
        enum Role {
            INNER,
            ARM,
            CUT,
            PLAIN
        }

        record Arm(Corner corner, boolean alongColumn, boolean outermost) {}

        private final int columns;
        private final int rows;
        private final Map<Long, Spec> inners = new HashMap<>();
        private final Map<Long, Arm> arms = new HashMap<>();
        private final Set<Long> cuts = new HashSet<>();

        Claim(int columns, int rows) {
            this.columns = columns;
            this.rows = rows;
        }

        Role role(int i, int j) {
            long key = key(i, j);
            if (this.cuts.contains(key)) {
                return Role.CUT;
            }
            if (this.inners.containsKey(key)) {
                return Role.INNER;
            }
            if (this.arms.containsKey(key)) {
                return Role.ARM;
            }
            return Role.PLAIN;
        }

        int turns(int i, int j) {
            return this.inners.get(key(i, j)).corner().turns();
        }

        Arm arm(int i, int j) {
            return this.arms.get(key(i, j));
        }

        /** 占位；任一处已被占则整个放弃 */
        void add(Spec spec) {
            if (spec.corner() == null
                    || spec.column() < 1
                    || spec.column() > this.columns - 2
                    || spec.row() < 1
                    || spec.row() > this.rows - 2) {
                return;
            }
            Corner corner = spec.corner();
            List<long[]> armCells = new ArrayList<>();
            List<long[]> cutCells = new ArrayList<>();
            for (int i = 0; i < this.columns; i++) {
                for (int j = 0; j < this.rows; j++) {
                    if (i == spec.column() && j == spec.row()) {
                        continue;
                    }
                    if (corner.removed(i, j, spec.column(), spec.row())) {
                        cutCells.add(new long[] {i, j});
                    } else if (corner.onArm(i, j, spec.column(), spec.row())) {
                        armCells.add(new long[] {i, j});
                    }
                }
            }
            List<Long> keys = new ArrayList<>();
            keys.add(key(spec.column(), spec.row()));
            for (long[] cell : armCells) {
                keys.add(key((int) cell[0], (int) cell[1]));
            }
            for (long[] cell : cutCells) {
                keys.add(key((int) cell[0], (int) cell[1]));
            }
            for (long claim : keys) {
                if (this.cuts.contains(claim) || this.inners.containsKey(claim) || this.arms.containsKey(claim)) {
                    return;
                }
            }
            this.inners.put(key(spec.column(), spec.row()), spec);
            for (long[] cell : armCells) {
                int i = (int) cell[0];
                int j = (int) cell[1];
                boolean alongColumn = i == spec.column();
                this.arms.put(
                        key(i, j),
                        new Arm(
                                corner,
                                alongColumn,
                                corner.outermost(i, j, spec.column(), spec.row(), this.columns, this.rows)));
            }
            for (long[] cell : cutCells) {
                this.cuts.add(key((int) cell[0], (int) cell[1]));
            }
        }

        private static long key(int column, int row) {
            return ((long) column << 32) | (row & 0xFFFFFFFFL);
        }
    }
}
