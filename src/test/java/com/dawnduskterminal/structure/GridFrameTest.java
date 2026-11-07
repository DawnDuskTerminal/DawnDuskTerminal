package com.dawnduskterminal.structure;

import java.util.List;

/**
 * {@link GridFrame} 的不变量检查
 *
 * <p>仓库里没有 JUnit（新增依赖要重写 `gradle/verification-metadata.xml`），所以这个类自带断言，
 * 用 {@code ./gradlew frameCheck} 跑，失败时以非零码退出。
 *
 * <p>「相邻」在这里只算整条边贴合：两个房间共用一条框架线。错开一格、只在角上碰到的两个房间
 * 视为不相邻，那种情况共用的是同一根角柱，不构成连接。
 */
public final class GridFrameTest {
    private static int failures;

    private GridFrameTest() {}

    public static void main(String[] args) {
        if (args.length > 0 && args[0].equals("show")) {
            for (long seed = 1; seed <= 6; seed++) {
                show(seed, 11, 11);
            }
        }
        for (int columns = 5; columns <= 13; columns += 2) {
            for (int rows = 5; rows <= 13; rows += 2) {
                for (long seed = 0; seed < 12; seed++) {
                    check(columns, rows, seed);
                }
            }
        }
        geometry();
        if (failures > 0) {
            System.out.println("FAILED: " + failures + " case(s)");
            System.exit(1);
        }
        System.out.println("GridFrame invariants hold");
    }

    /**
     * 相邻与贴边：{@code maxX} 是末格加一，所以「贴边」是差一格，「隔一格」还不算贴边
     *
     * <p>跨层房间不许挨着就是靠这两条判据，边界算错会漏判（两个楼梯并排长出来）。
     */
    private static void geometry() {
        GridFrame.Cell a = new GridFrame.Cell(0, 0, 5, 5, GridFrame.Kind.SMALL, 0);
        GridFrame.Cell east = new GridFrame.Cell(4, 0, 5, 5, GridFrame.Kind.SMALL, 0);
        GridFrame.Cell south = new GridFrame.Cell(0, 4, 5, 5, GridFrame.Kind.SMALL, 0);
        GridFrame.Cell diagonal = new GridFrame.Cell(4, 4, 5, 5, GridFrame.Kind.SMALL, 0);
        GridFrame.Cell gap = new GridFrame.Cell(5, 0, 5, 5, GridFrame.Kind.SMALL, 0);
        expect("东侧贴边相邻", GridFrame.adjacent(a, east), true);
        expect("南侧贴边相邻", GridFrame.adjacent(a, south), true);
        expect("只共角不算相邻", GridFrame.adjacent(a, diagonal), false);
        expect("隔一格不算相邻", GridFrame.adjacent(a, gap), false);
        expect("共角算贴边", GridFrame.touches(a, diagonal), true);
        expect("隔一格算贴边", GridFrame.touches(a, gap), true);
        GridFrame.Cell far = new GridFrame.Cell(10, 0, 5, 5, GridFrame.Kind.SMALL, 0);
        expect("隔两格就不算贴边", GridFrame.touches(a, far), false);
    }

    private static void expect(String what, boolean actual, boolean wanted) {
        if (actual != wanted) {
            System.out.println("  FAIL " + what + ": expected " + wanted + ", got " + actual);
            failures++;
        }
    }

    private static void check(int columns, int rows, long seed) {
        String where = columns + "x" + rows + " seed=" + seed;
        List<GridFrame.Cell> cells = GridFrame.plan(seed, columns, rows);
        if (cells.size() < 4) {
            fail(where, "only " + cells.size() + " cells kept");
            return;
        }
        for (GridFrame.Cell cell : cells) {
            if (cell.w() != GridFrame.S && cell.w() != GridFrame.L) {
                fail(where, "width " + cell.w());
            }
            if (cell.d() != GridFrame.S && cell.d() != GridFrame.L) {
                fail(where, "depth " + cell.d());
            }
            if (cell.kind() == GridFrame.Kind.INNER_CORNER && (cell.w() != GridFrame.L || cell.d() != GridFrame.L)) {
                fail(where, "inner corner must sit on a 7x7 cell");
            }
            if (cell.kind() == GridFrame.Kind.LARGE && boundary(cells, cell)) {
                fail(where, "LARGE cell " + cell.x() + "," + cell.z() + " touches the outline");
            }
        }
        overlaps(where, cells);
        sides(where, cells);
        deterministic(where, columns, rows, seed, cells);
    }

    /** 同一个种子必须给出同一张图 */
    private static void deterministic(String where, int columns, int rows, long seed, List<GridFrame.Cell> cells) {
        if (!GridFrame.plan(seed, columns, rows).equals(cells)) {
            fail(where, "plan is not deterministic");
        }
    }

    /** 相邻两侧的边长必须相等；内角件两条内侧是 L、两条外侧是 S，不参与这条检查 */
    private static void sides(String where, List<GridFrame.Cell> cells) {
        for (GridFrame.Cell p : cells) {
            for (GridFrame.Cell q : cells) {
                if (p == q) {
                    continue;
                }
                if (p.kind() == GridFrame.Kind.INNER_CORNER || q.kind() == GridFrame.Kind.INNER_CORNER) {
                    continue;
                }
                if ((qCoversE(p, q) || qCoversW(p, q)) && p.d() != q.d()) {
                    fail(where, "east/west side " + p.d() + " meets " + q.d());
                }
                if ((qCoversS(p, q) || qCoversN(p, q)) && p.w() != q.w()) {
                    fail(where, "north/south side " + p.w() + " meets " + q.w());
                }
            }
        }
    }

    /** 任意两格最多共用一条框架线或一根角柱 */
    private static void overlaps(String where, List<GridFrame.Cell> cells) {
        for (int a = 0; a < cells.size(); a++) {
            for (int b = a + 1; b < cells.size(); b++) {
                GridFrame.Cell p = cells.get(a);
                GridFrame.Cell q = cells.get(b);
                int w = Math.min(p.maxX(), q.maxX()) - Math.max(p.x(), q.x());
                int d = Math.min(p.maxZ(), q.maxZ()) - Math.max(p.z(), q.z());
                if (w > 0 && d > 0 && w != 1 && d != 1) {
                    fail(where, "cells overlap " + w + "x" + d);
                }
            }
        }
    }

    private static boolean boundary(List<GridFrame.Cell> cells, GridFrame.Cell cell) {
        boolean east = false;
        boolean west = false;
        boolean south = false;
        boolean north = false;
        for (GridFrame.Cell other : cells) {
            if (other == cell) {
                continue;
            }
            east |= qCoversE(cell, other);
            west |= qCoversW(cell, other);
            south |= qCoversS(cell, other);
            north |= qCoversN(cell, other);
        }
        return !east || !west || !south || !north;
    }

    private static boolean qCoversE(GridFrame.Cell p, GridFrame.Cell q) {
        return q.x() == p.maxX() - 1 && q.z() <= p.z() && q.maxZ() >= p.maxZ();
    }

    private static boolean qCoversW(GridFrame.Cell p, GridFrame.Cell q) {
        return q.maxX() == p.x() + 1 && q.z() <= p.z() && q.maxZ() >= p.maxZ();
    }

    private static boolean qCoversS(GridFrame.Cell p, GridFrame.Cell q) {
        return q.z() == p.maxZ() - 1 && q.x() <= p.x() && q.maxX() >= p.maxX();
    }

    private static boolean qCoversN(GridFrame.Cell p, GridFrame.Cell q) {
        return q.maxZ() == p.z() + 1 && q.x() <= p.x() && q.maxX() >= p.maxX();
    }

    private static void show(long seed, int columns, int rows) {
        List<GridFrame.Cell> cells = GridFrame.plan(seed, columns, rows);
        int size = 0;
        for (GridFrame.Cell cell : cells) {
            size = Math.max(size, Math.max(cell.maxX(), cell.maxZ()));
        }
        System.out.println("=== seed=" + seed + " " + columns + "x" + rows + " cells=" + cells.size());
        for (int z = 0; z < size; z++) {
            StringBuilder line = new StringBuilder();
            for (int x = 0; x < size; x++) {
                char ch = '.';
                for (GridFrame.Cell cell : cells) {
                    if (cell.contains(x, z)) {
                        ch = switch (cell.kind()) {
                            case SMALL -> 's';
                            case RECT -> 'r';
                            case LARGE -> 'L';
                            case INNER_CORNER -> 'i';
                        };
                        if (x == cell.x() || z == cell.z()) {
                            ch = Character.toUpperCase(ch);
                        }
                    }
                }
                line.append(ch);
            }
            System.out.println("   " + line);
        }
    }

    private static void fail(String where, String message) {
        failures++;
        if (failures <= 10) {
            System.out.println("  " + where + ": " + message);
        }
    }
}
