package com.dawnduskterminal.world;

/**
 * 流体接触反应的闸门：批量改方块期间把它拉下来，改完再推上去
 *
 * <p>原版与新版的流体接触反应（水碰岩浆变黑曜石、不稳定液体碰水变脆弱的苍穹等）挂在
 * {@code FluidInteractionRegistry#canInteract} 上，而那个方法由 {@code LiquidBlock#onPlace} 与
 * {@code LiquidBlock#neighborChanged} 各调一次——也就是说每写一格流体就会当场判一次，等不到下一 tick。
 *
 * <p>转换粉落地时一次要换掉一整球方块，中间必然会写出「新流体挨着旧流体」的瞬间。
 * 那一瞬间不该算数，否则球内两种液体互相接触就整片反应掉了。所以写方块这一段把闸拉下来
 * （mixin：{@code mixin/FluidReactionSuppressMixin}），写完再推上去，并且只对球壳上那几格显式补一次判定，
 * 让范围外的液体照常与边界反应。闸开在 {@link ThreadLocal} 上：服务端只有一条世界刻线程，
 * 客户端压根不判定
 */
public final class FluidReactionGate {
    private static final ThreadLocal<Boolean> SUPPRESSED = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private FluidReactionGate() {}

    /** 现在是不是闸门关着（反应一律判否） */
    public static boolean suppressed() {
        return SUPPRESSED.get();
    }

    /** 在 {@code action} 执行期间把闸门拉下来；可重入，退出时恢复成进来时的样子 */
    public static void suppress(Runnable action) {
        boolean previous = SUPPRESSED.get();
        SUPPRESSED.set(Boolean.TRUE);
        try {
            action.run();
        } finally {
            SUPPRESSED.set(previous);
        }
    }
}
