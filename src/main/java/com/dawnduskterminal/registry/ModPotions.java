package com.dawnduskterminal.registry;

import com.dawnduskterminal.DawnDuskTerminal;
import com.dawnduskterminal.crafting.ChickweedStabilityBrewing;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.Potion;
import net.minecraft.world.item.alchemy.PotionBrewing;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.ItemLike;
import net.minecraft.world.level.block.DispenserBlock;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.crafting.DataComponentIngredient;
import net.neoforged.neoforge.event.ModifyDefaultComponentsEvent;
import net.neoforged.neoforge.event.brewing.RegisterBrewingRecipesEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 药水。目前有两瓶：稳定与复方侵蚀
 *
 * <p>「稳定」见 {@code docs/设定.md}：喝下之后身体被稳定在这个世界，足以抵抗不稳定液体的传送，
 * 代价是每抵抗一次都受伤（判定在 {@code PortalBlock#entityInside}，效果本身是纯标记，见 {@code StabilityEffect}）
 *
 * <p>「复方侵蚀」是它的反面：稳定药水在酿造台上加一罐不稳定液体酿出来，效果每 2 秒造成 3 点
 * 传送门流体的接触伤害（见 {@code CompoundErosionEffect}）。两瓶效果的颜色都是 {@code 0x7F159E}，
 * 所以药水颜色一模一样，只看瓶子分不出来
 *
 * <p>档次照原版的效果药水家族来：稳定 3 分钟 / 8 分钟，复方侵蚀 45 秒 / 1 分 30 秒，各自都有喷溅与滞留型。
 * 全部配方都在 {@link #registerBrewingRecipes}：起手用繁缕菜、加罐用不稳定液体，
 * 红石延长、火药喷溅、龙息滞留走的是原版那三条规则
 *
 * <p>药水颜色不单独写：{@code PotionContents#getColor()} 按可见效果的 {@code MobEffect#getColor()} 加权平均，
 * 而两个效果都是 {@code 0x7F159E}（也是传送门流体的雾色），只有这一条效果时平均出来就是它，与传送门同色
 */
public final class ModPotions {
    public static final DeferredRegister<Potion> POTIONS =
            DeferredRegister.create(Registries.POTION, DawnDuskTerminal.MOD_ID);

    /** 本型：3 分钟，3600 tick，与原版的迅捷、夜视同档 */
    public static final int STABILITY_DURATION = 3 * 60 * 20;

    /** 延长型：8 分钟，9600 tick，原版「延长」那一档 */
    public static final int LONG_STABILITY_DURATION = 8 * 60 * 20;

    /** 名字要与 {@code PotionItem} 拼出来的翻译键对上：{@code item.dawnduskterminal.stability_potion.effect.stability} */
    public static final DeferredHolder<Potion, Potion> STABILITY = POTIONS.register(
            "stability",
            () -> new Potion("stability", new MobEffectInstance(ModEffects.STABILITY, STABILITY_DURATION)));

    /**
     * 延长型
     *
     * <p>名字字段仍写 {@code stability}：原版的 {@code long_swiftness} 也只写 {@code swiftness}，
     * 两种时长共用同一个翻译键，具体时长由提示框里的效果行显示
     */
    public static final DeferredHolder<Potion, Potion> LONG_STABILITY = POTIONS.register(
            "long_stability",
            () -> new Potion("stability", new MobEffectInstance(ModEffects.STABILITY, LONG_STABILITY_DURATION)));

    /** 复方侵蚀本型：45 秒，900 tick */
    public static final int COMPOUND_EROSION_DURATION = 45 * 20;

    /** 复方侵蚀延长型：1 分 30 秒，1800 tick */
    public static final int LONG_COMPOUND_EROSION_DURATION = 90 * 20;

    /** 名字要与 {@code PotionItem} 拼出来的翻译键对上：{@code item.dawnduskterminal.compound_erosion_potion.effect.compound_erosion} */
    public static final DeferredHolder<Potion, Potion> COMPOUND_EROSION = POTIONS.register(
            "compound_erosion",
            () -> new Potion(
                    "compound_erosion", new MobEffectInstance(ModEffects.COMPOUND_EROSION, COMPOUND_EROSION_DURATION)));

    /** 复方侵蚀的延长型，名字字段照旧共用 {@code compound_erosion} */
    public static final DeferredHolder<Potion, Potion> LONG_COMPOUND_EROSION = POTIONS.register(
            "long_compound_erosion",
            () -> new Potion(
                    "compound_erosion",
                    new MobEffectInstance(ModEffects.COMPOUND_EROSION, LONG_COMPOUND_EROSION_DURATION)));

    private ModPotions() {}

    public static void register(IEventBus bus) {
        POTIONS.register(bus);
    }

    /**
     * 稳定药水与复方侵蚀药水的炼药配方
     *
     * <p>必须在 {@link RegisterBrewingRecipesEvent} 里加，而不是数据包：1.21.1 的药水配方是硬编码在
     * {@code PotionBrewing#addVanillaMixes} 里的，没有数据驱动的 JSON 形式。事件在客户端与服务端各发一次，
     * 两边各建一份 {@code PotionBrewing}，所以这里是同一次注册跑两遍，互不影响
     *
     * <p>稳定那三条规则对着原版：繁缕菜当起手原料（粗制的药水 → 稳定药水，水瓶子那一档落到平凡药水，
     * 与糖、岩浆膏那批一致），红石把本型延长成 8 分钟，火药与龙息把普通瓶换成喷溅瓶与滞留瓶。
     * 复方侵蚀的本型与延长型由稳定药水加一罐不稳定液体酿出，本型进本型、延长型进延长型，
     * 红石与两种瓶型另有三条规则
     *
     * <p>换物品的两条配方走 NeoForge 的 {@code IBrewingRecipe} 家族而不是 {@code addMix}：
     * 原版的药水规则只按物品换内容，起手那一条会酿出 {@code minecraft:potion}、
     * 加罐那一条会酿出 {@code stability_potion} 装着复方侵蚀，都不是模组自己的那件物品。
     * 起手是自写的 {@link ChickweedStabilityBrewing}；加罐用 NeoForge 自带的 {@code BrewingRecipe}
     * （输入侧见 {@link #potionBottle}），好让 JEI 认得出来
     *
     * <p>空罐不用在这里处理：两桶流体都是 {@code item/CanocanBucketItem}，剩余物是苍穹罐，
     * NeoForge 补过的 {@code BrewingStandBlockEntity#doBrew} 会把原料的剩余物写回原料槽
     */
    public static void registerBrewingRecipes(RegisterBrewingRecipesEvent event) {
        PotionBrewing.Builder builder = event.getBuilder();
        Item veges = ModTerrainBlocks.STELLARIA_VEGES_ITEM.get();
        Item bucket = ModItems.PORTAL_BUCKET.get();

        // 每瓶药水的三种瓶型都要先登记成「容器」，否则酿造台下面三个槽连放都不让放（PotionBrewing#isInput）
        builder.addContainer(ModItems.STABILITY_POTION.get());
        builder.addContainer(ModItems.STABILITY_SPLASH_POTION.get());
        builder.addContainer(ModItems.STABILITY_LINGERING_POTION.get());
        builder.addContainer(ModItems.COMPOUND_EROSION_POTION.get());
        builder.addContainer(ModItems.COMPOUND_EROSION_SPLASH_POTION.get());
        builder.addContainer(ModItems.COMPOUND_EROSION_LINGERING_POTION.get());

        // 起手原料：繁缕菜。粗制的药水那一支由自定义配方接住，水瓶子那一支落到平凡药水（原版的失败分支）
        builder.addRecipe(new ChickweedStabilityBrewing());
        builder.addMix(Potions.WATER, veges, Potions.MUNDANE);

        // 复方侵蚀的本型：一罐不稳定液体倒进稳定药水（3 分钟、8 分钟两种都收）
        builder.addRecipe(
                potionBottle(ModItems.STABILITY_POTION.get(), STABILITY),
                Ingredient.of(bucket),
                PotionContents.createItemStack(ModItems.COMPOUND_EROSION_POTION.get(), COMPOUND_EROSION));
        builder.addRecipe(
                potionBottle(ModItems.STABILITY_POTION.get(), LONG_STABILITY),
                Ingredient.of(bucket),
                PotionContents.createItemStack(ModItems.COMPOUND_EROSION_POTION.get(), LONG_COMPOUND_EROSION));

        // 延长：红石把本型推到延长型（稳定 3 分钟 → 8 分钟，复方侵蚀 45 秒 → 1 分 30 秒）
        builder.addMix(STABILITY, Items.REDSTONE, LONG_STABILITY);
        builder.addMix(COMPOUND_EROSION, Items.REDSTONE, LONG_COMPOUND_EROSION);

        // 瓶型：火药换喷溅瓶，龙息把喷溅瓶换成滞留瓶，与原版同一套
        builder.addContainerRecipe(
                ModItems.STABILITY_POTION.get(), Items.GUNPOWDER, ModItems.STABILITY_SPLASH_POTION.get());
        builder.addContainerRecipe(
                ModItems.STABILITY_SPLASH_POTION.get(), Items.DRAGON_BREATH, ModItems.STABILITY_LINGERING_POTION.get());
        builder.addContainerRecipe(
                ModItems.COMPOUND_EROSION_POTION.get(), Items.GUNPOWDER, ModItems.COMPOUND_EROSION_SPLASH_POTION.get());
        builder.addContainerRecipe(
                ModItems.COMPOUND_EROSION_SPLASH_POTION.get(),
                Items.DRAGON_BREATH,
                ModItems.COMPOUND_EROSION_LINGERING_POTION.get());
    }

    /**
     * 一罐不稳定液体倒进稳定药水时，酿造台输入侧的那瓶药水
     *
     * <p>输入侧只认 {@link Ingredient}，而 {@code stability} 与 {@code long_stability} 是同一件
     * {@code stability_potion} 的两档，只有 {@code potion_contents} 这一项组件不同。
     * {@link DataComponentIngredient} 的非严格模式正好够用：物品与 {@code potion_contents} 相等即算命中，
     * 瓶子上的其它组件（改名之类）不参与比对
     *
     * <p>这一条写成 NeoForge 自带的 {@code BrewingRecipe} 而不是自写 {@code IBrewingRecipe}，是为了 JEI：
     * 它的酿造页只模拟 {@code potionMixes} 与 {@code containerMixes} 两张表，自写的 {@code IBrewingRecipe}
     * 不在其中，按 R 查不到产物、按 U 查不到原料；它给 NeoForge 自带的 {@code BrewingRecipe} 单独挂了一条扩展
     * （{@code BrewingRecipeCategoryExtension}），只有这一类进得了那一页
     *
     * <p>已知的差距：JEI 对没登记子类型的物品只算一个 UID，本型与延长型这两条配方算出来的 UID 相同，
     * 集合去重后界面上只剩本型那一条。酿造本身两档都在（{@code gametest/CompoundErosionTests} 钉着）
     */
    private static Ingredient potionBottle(Item item, Holder<Potion> potion) {
        return DataComponentIngredient.of(false, DataComponents.POTION_CONTENTS, new PotionContents(potion), item);
    }

    /**
     * 给药水物品补上默认的 {@code potion_contents}
     *
     * <p>不能在注册物品时写 {@code Item.Properties#component}：NeoForge 的注册顺序是原版注册表顺序，
     * 物品（第 7 个）先于药水（第 8 个），那时 {@link #STABILITY} 还没绑定，取 {@code get()} 会直接抛。
     * {@link ModifyDefaultComponentsEvent} 在全部注册表就绪之后才发，这时两头都拿得到；物品默认组件是
     * {@code Item#components()} 那张表，{@code new ItemStack(item)} 从它拷一份，所以创造栏、{@code /give}、
     * 各种掉出来的物品栈都会自带这瓶药
     *
     * <p>三种瓶型都写本型：酿造台上的产物走的是配方，与这里的默认值无关，默认值只决定
     * 「凭空出现的一瓶」长什么样，取本型与最基础的那条配方一致
     */
    public static void modifyDefaultComponents(ModifyDefaultComponentsEvent event) {
        setDefaultContents(event, ModItems.STABILITY_POTION, STABILITY);
        setDefaultContents(event, ModItems.STABILITY_SPLASH_POTION, STABILITY);
        setDefaultContents(event, ModItems.STABILITY_LINGERING_POTION, STABILITY);
        setDefaultContents(event, ModItems.COMPOUND_EROSION_POTION, COMPOUND_EROSION);
        setDefaultContents(event, ModItems.COMPOUND_EROSION_SPLASH_POTION, COMPOUND_EROSION);
        setDefaultContents(event, ModItems.COMPOUND_EROSION_LINGERING_POTION, COMPOUND_EROSION);
    }

    /**
     * 发射器：投掷瓶照原版那两瓶排进去
     *
     * <p>原版在 {@code DispenseItemBehavior} 的静态块里给 {@code minecraft:splash_potion} 与
     * {@code minecraft:lingering_potion} 登记了 {@code ProjectileDispenseBehavior}。
     * 模组这几瓶不登记的话，发射器只会按「扔出去一件物品」处理，落地不炸开
     *
     * <p>调在 {@code FMLCommonSetupEvent} 里：那时物品都已绑定，而 {@code DISPENSER_REGISTRY}
     * 是可写的一张表，原版自己也是在静态块里往里写
     */
    public static void registerDispenserBehaviors() {
        DispenserBlock.registerProjectileBehavior(ModItems.STABILITY_SPLASH_POTION.get());
        DispenserBlock.registerProjectileBehavior(ModItems.STABILITY_LINGERING_POTION.get());
        DispenserBlock.registerProjectileBehavior(ModItems.COMPOUND_EROSION_SPLASH_POTION.get());
        DispenserBlock.registerProjectileBehavior(ModItems.COMPOUND_EROSION_LINGERING_POTION.get());
    }

    private static void setDefaultContents(ModifyDefaultComponentsEvent event, ItemLike item, Holder<Potion> potion) {
        event.modify(item, builder -> builder.set(DataComponents.POTION_CONTENTS, new PotionContents(potion)));
    }
}
