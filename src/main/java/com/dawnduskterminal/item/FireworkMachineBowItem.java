package com.dawnduskterminal.item;

import com.dawnduskterminal.client.FireworkMachineBowBob;
import com.dawnduskterminal.registry.ModSounds;
import com.dawnduskterminal.world.MachinebowFireworks;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundStopSoundPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.stats.Stats;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.UseAnim;
import net.minecraft.world.item.component.CustomModelData;
import net.minecraft.world.item.component.FireworkExplosion;
import net.minecraft.world.item.component.Fireworks;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.Level;

/**
 * 火箭机弩
 *
 * <p>按住右键期间每 {@value #BASE_INTERVAL_TICKS} tick（0.5 秒）以弩的方式打出一枚烟花火箭：
 * 弹速 {@value #ROCKET_SPEED}、从眼睛下方 0.15 格出膛、带 {@code shot_at_angle}，与弩打火箭弹完全同一条路。
 * 火箭就是这门武器的弹药：每发消耗身上任意一枚烟花火箭（优先副手，其次背包），身上没有就打不出去；
 * 打出去的内容不看消耗掉的那一枚是什么，每一发都现摇一颗：形状、颜色、拖尾、闪烁、
 * 飞行时长（{@value #MIN_FLIGHT_DURATION}~{@value #MAX_FLIGHT_DURATION}）与烟火之心数量
 * （{@value #MIN_ROCKET_STARS}~{@value #MAX_ROCKET_STARS}，只进伤害换算、不进外观演出）全都是随机的
 *
 * <p>耐久 {@value #DURABILITY_USE} 点一发，与原版弩打火箭弹的 {@code getDurabilityUse} 同一个数；
 * 耐久耗尽时这一发照打，弩随后碎掉
 *
 * <p>不做任何「使用中」姿势：本类不继承 {@code CrossbowItem}，{@code getUseAnimation} 也返回
 * {@link UseAnim#NONE}，按住期间物品就摆在普通位置。每一发出弹的那一拍，客户端调一次
 * {@code ItemInHandRenderer#itemUsed}：这就是原版每次用物品时「物品往下沉一下再回弹」的那一下，
 * 项目里那些按一下就走完的物品走的是同一条，跟挥手（{@code Player#swing}）不是一回事
 *
 * <p>每一发都同时放三声：火箭发射（{@code firework_machinebow_launch}，原版烟花的 launch1）、
 * 发射（{@code firework_machinebow_shoot}）与拉弦（{@code firework_machinebow_draw}）。
 * 发射与拉弦是跟着手的两声，打下一发时由 {@link ClientboundStopSoundPacket} 掐掉残响；
 * 火箭发射那一声不掐——火箭已经射出去了，声音跟着它走完。
 * 三个事件是模组自己的，掐的是事件 id，别的弩与别人的烟花不受影响
 *
 * <p>外观：常态是初始贴图，占 {@link DataComponents#CUSTOM_MODEL_DATA} 的两档 1 与 2 之间来回切，
 * 停手（不再使用、切手、收起）时清掉这一项，回到初始贴图，见 {@link #inventoryTick}
 *
 * <p>接受的附魔：力量（每级 +25% 伤害）、冲击（减少命中和爆炸的击退，每级减半、II 级起 0 击退）、
 * 火矢（点着被爆炸波及的目标）、
 * 多重射击（三发，左右各偏 {@value #MULTISHOT_SPREAD_DEGREES} 度再加一份
 * {@value #MULTISHOT_SPREAD_JITTER_DEGREES} 度以内的随机量）、快速装填（每级把间隔减
 * {@value #INTERVAL_PER_QUICK_CHARGE} tick，最快 {@value #MIN_INTERVAL_TICKS} tick，也就是 0.2 秒）。
 * 相关数值在打出的那一刻读出来、写进火箭的 {@code NeoForgeData}，伤害落地前由
 * {@link MachinebowFireworks} 在 {@code LivingIncomingDamageEvent} 里取用
 */
public class FireworkMachineBowItem extends Item {
    /** 一直按着也不主动结束，与弓弩那一套的 72000 一致 */
    public static final int USE_DURATION = 72000;

    /** 没有快速装填时的发射间隔，10 tick = 0.5 秒 */
    public static final int BASE_INTERVAL_TICKS = 10;

    /** 快速装填每级把间隔缩短多少 tick，III 级正好落到 4 tick = 0.2 秒 */
    public static final int INTERVAL_PER_QUICK_CHARGE = 2;

    /** 间隔下限，也就是快速装填 III：0.2 秒 */
    public static final int MIN_INTERVAL_TICKS = 4;

    /** 弹速，与弩打烟花火箭的 {@code FIREWORK_POWER} 相同 */
    public static final float ROCKET_SPEED = 1.6F;

    /** 多重射击时多出来的两发的基础偏角 */
    public static final float MULTISHOT_SPREAD_DEGREES = 7.0F;

    /** 多出来的两发各自再叠的随机偏角上限（0 到本值之间） */
    public static final float MULTISHOT_SPREAD_JITTER_DEGREES = 3.0F;

    /** 烟花的飞行时长范围，含两端 */
    public static final int MIN_FLIGHT_DURATION = 1;

    public static final int MAX_FLIGHT_DURATION = 3;

    /** 烟火之心（只参与伤害换算）数量的范围，含两端；原版工作台一颗火箭最多塞 7 颗 */
    public static final int MIN_ROCKET_STARS = 1;

    public static final int MAX_ROCKET_STARS = 7;

    /** 掐旧声音送给多远以内的玩家：原版世界音效只走 16 格，留足余量，0.2 秒内飞不出去 */
    public static final double SOUND_STOP_RADIUS = 64.0D;

    /** 力量每级提升的伤害比例，与弓的 Power 同一套 25% */
    public static final float POWER_DAMAGE_PER_LEVEL = 0.25F;

    /** 耐久上限：与原版弩的 465 一致 */
    public static final int DURABILITY = 465;

    /** 每发扣的耐久：与原版弩打烟花火箭的 {@code getDurabilityUse} 相同 */
    public static final int DURABILITY_USE = 3;

    public FireworkMachineBowItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        // 与原版弩一样，身上没有弹药就举不起来
        if (!hasRocket(player)) {
            return InteractionResultHolder.fail(stack);
        }
        // 客户端也要走这一句：右键那一刻本地玩家就已经在「使用中」，弩的抬起姿势才会出来
        player.startUsingItem(hand);
        return InteractionResultHolder.consume(stack);
    }

    @Override
    public int getUseDuration(ItemStack stack, LivingEntity entity) {
        return USE_DURATION;
    }

    /**
     * 不作任何「使用中」姿势
     *
     * <p>{@link UseAnim#CROSSBOW} 的那套「抬起拉弦」姿势来自 {@code ItemInHandRenderer} 里对
     * {@link net.minecraft.world.item.CrossbowItem} 的 instanceof 分支；本类不继承它，返回
     * {@link UseAnim#NONE}，按住期间物品就摆在普通位置
     */
    @Override
    public UseAnim getUseAnimation(ItemStack stack) {
        return UseAnim.NONE;
    }

    /** 附魔值 1，与弩相同；{@code Item} 的默认值是 0，附魔台会直接跳过这一件 */
    @Override
    public int getEnchantmentValue(ItemStack stack) {
        return 1;
    }

    /**
     * 换手动画：贴图 1/2 的来回切只是改了 {@code custom_model_data}，不能当成换了物品
     *
     * <p>默认判据是 {@code !oldStack.equals(newStack)}，每发换一次贴图就会触发一次「收起再抬起」；
     * 快速装填间隔短的时候动画来不及回弹，一路叠着往下沉，最后把物品压出屏幕。这里只看物品本体与槽位
     */
    @Override
    public boolean shouldCauseReequipAnimation(ItemStack oldStack, ItemStack newStack, boolean slotChanged) {
        return slotChanged || oldStack.getItem() != newStack.getItem();
    }

    @Override
    public void onUseTick(Level level, LivingEntity entity, ItemStack stack, int remaining) {
        if (!(entity instanceof Player player)) {
            return;
        }
        int elapsed = USE_DURATION - remaining;
        if (elapsed <= 0) {
            return;
        }
        // 火箭是弹药：身上打空了就收手，别在空中一直扣着扳机。
        // 客户端也判一次，等背包同步到位之后那一侧才会一起松手
        if (!hasRocket(player)) {
            player.stopUsingItem();
            return;
        }
        boolean shotTick = elapsed % shotIntervalTicks(level, stack) == 0;
        // 客户端与出手同一拍播一下「物品被使用」的下沉回弹：就是原版每次用物品时
        // ItemInHandRenderer#itemUsed 那一下，项目里那些按一下就走完的物品都是这条
        if (shotTick && level.isClientSide) {
            FireworkMachineBowBob.play(player.getUsedItemHand());
        }
        // 两端都会走到这里，真正出弹的只有服务端
        if (level.isClientSide || !shotTick) {
            return;
        }
        if (!consumeRocket(player)) {
            player.stopUsingItem();
            return;
        }
        fire(level, player, stack);
    }

    /**
     * 停手（松开右键）时把贴图拨回初始那一张
     *
     * <p>切手、收起这类不走 {@code releaseUsing} 的路径由 {@link #inventoryTick} 兜住
     */
    @Override
    public void releaseUsing(ItemStack stack, Level level, LivingEntity entity, int remaining) {
        stack.remove(DataComponents.CUSTOM_MODEL_DATA);
    }

    /**
     * 不在使用中就把贴图状态清掉
     *
     * <p>每一 tick 都会对背包里每一栈调一次，所以先看有没有那一项再说：常态下这里什么都不做
     */
    @Override
    public void inventoryTick(ItemStack stack, Level level, Entity entity, int slotId, boolean isSelected) {
        if (level.isClientSide || !stack.has(DataComponents.CUSTOM_MODEL_DATA)) {
            return;
        }
        if (entity instanceof LivingEntity living && living.isUsingItem() && living.getUseItem() == stack) {
            return;
        }
        stack.remove(DataComponents.CUSTOM_MODEL_DATA);
    }

    @Override
    public void appendHoverText(
            ItemStack stack, Item.TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        super.appendHoverText(stack, context, tooltip, flag);
        tooltip.add(Component.translatable("item.dawnduskterminal.firework_machinebow.desc")
                .withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("item.dawnduskterminal.firework_machinebow.desc2")
                .withStyle(ChatFormatting.GRAY));
    }

    /** 发射间隔：0.5 秒起，快速装填每级减 2 tick，最快 0.2 秒 */
    public static int shotIntervalTicks(Level level, ItemStack stack) {
        int quickCharge = enchantmentLevel(level, stack, Enchantments.QUICK_CHARGE);
        return Math.max(MIN_INTERVAL_TICKS, BASE_INTERVAL_TICKS - INTERVAL_PER_QUICK_CHARGE * quickCharge);
    }

    /** 打一发：读附魔、换贴图、掐旧声音放新声音、出弹 */
    private static void fire(Level level, Player player, ItemStack stack) {
        if (!(level instanceof ServerLevel server)) {
            return;
        }
        int power = enchantmentLevel(level, stack, Enchantments.POWER);
        int punch = enchantmentLevel(level, stack, Enchantments.PUNCH);
        int flame = enchantmentLevel(level, stack, Enchantments.FLAME);
        boolean multishot = enchantmentLevel(level, stack, Enchantments.MULTISHOT) > 0;

        toggleModelState(stack);
        playShotSounds(server, player);

        if (multishot) {
            // 左右两发各自再叠一份随机偏角，两发互不相同
            RandomSource random = level.getRandom();
            float left = -MULTISHOT_SPREAD_DEGREES - random.nextFloat() * MULTISHOT_SPREAD_JITTER_DEGREES;
            float right = MULTISHOT_SPREAD_DEGREES + random.nextFloat() * MULTISHOT_SPREAD_JITTER_DEGREES;
            fireRocket(server, player, left, power, punch, flame);
            fireRocket(server, player, 0.0F, power, punch, flame);
            fireRocket(server, player, right, power, punch, flame);
        } else {
            fireRocket(server, player, 0.0F, power, punch, flame);
        }
        // 与原版弩一样：先出弹再扣耐久，最后一发照打，弩随后碎掉
        player.awardStat(Stats.ITEM_USED.get(stack.getItem()));
        stack.hurtAndBreak(DURABILITY_USE, player, LivingEntity.getSlotForHand(player.getUsedItemHand()));
        if (stack.isEmpty()) {
            player.stopUsingItem();
        }
    }

    /**
     * 身上有没有烟花火箭：创造模式永远算有，与原版取弹药的判据一致
     *
     * <p>优先副手，其次整份背包（含快捷栏与背包）；这句话对应设定里「消耗身上的任意火箭」
     */
    private static boolean hasRocket(Player player) {
        if (player.hasInfiniteMaterials()) {
            return true;
        }
        if (player.getOffhandItem().is(Items.FIREWORK_ROCKET)) {
            return true;
        }
        Inventory inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            if (inventory.getItem(slot).is(Items.FIREWORK_ROCKET)) {
                return true;
            }
        }
        return false;
    }

    /** 消耗一枚身上的任意烟花火箭；创造模式不扣。返回的是这一发到底有没有拿到弹药 */
    private static boolean consumeRocket(Player player) {
        if (player.hasInfiniteMaterials()) {
            return true;
        }
        ItemStack offhand = player.getOffhandItem();
        if (offhand.is(Items.FIREWORK_ROCKET)) {
            offhand.shrink(1);
            return true;
        }
        Inventory inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack candidate = inventory.getItem(slot);
            if (candidate.is(Items.FIREWORK_ROCKET)) {
                candidate.shrink(1);
                return true;
            }
        }
        return false;
    }

    /** 两档贴图来回切：没有那一项时从 1 开始，之后 1 → 2 → 1 */
    private static void toggleModelState(ItemStack stack) {
        int current = stack.getOrDefault(DataComponents.CUSTOM_MODEL_DATA, CustomModelData.DEFAULT)
                .value();
        stack.set(DataComponents.CUSTOM_MODEL_DATA, new CustomModelData(current == 1 ? 2 : 1));
    }

    /**
     * 先掐掉上一发剩下的发射与拉弦两声，再放齐三声：火箭发射、发射、拉弦
     *
     * <p>「火箭发射」那一声<b>不掐</b>：火箭已经射出去了，声音该跟着它走完；
     * 掐的只是跟着手的那两声（发射、拉弦），下一发不叠上一发的残响。
     * 掐是按事件 id 掐的，只影响本模组这两条自定义事件；半径取 {@value #SOUND_STOP_RADIUS} 格
     *
     * <p>火箭本体是静音的：原版那声 {@code FIREWORK_ROCKET_LAUNCH} 由弹体自己放，
     * 这里改用本模组自己的 launch 事件（素材同样是原版烟花的 launch1）
     */
    private static void playShotSounds(ServerLevel level, Player player) {
        SoundEvent launch = ModSounds.FIREWORK_MACHINEBOW_LAUNCH.get();
        SoundEvent shoot = ModSounds.FIREWORK_MACHINEBOW_SHOOT.get();
        SoundEvent draw = ModSounds.FIREWORK_MACHINEBOW_DRAW.get();
        ClientboundStopSoundPacket stopShoot = new ClientboundStopSoundPacket(shoot.getLocation(), SoundSource.PLAYERS);
        ClientboundStopSoundPacket stopDraw = new ClientboundStopSoundPacket(draw.getLocation(), SoundSource.PLAYERS);
        for (ServerPlayer listener : level.players()) {
            if (listener.distanceToSqr(player) > SOUND_STOP_RADIUS * SOUND_STOP_RADIUS) {
                continue;
            }
            listener.connection.send(stopShoot);
            listener.connection.send(stopDraw);
        }
        level.playSound(null, player.getX(), player.getY(), player.getZ(), launch, SoundSource.PLAYERS, 1.5F, 1.0F);
        level.playSound(null, player.getX(), player.getY(), player.getZ(), shoot, SoundSource.PLAYERS, 1.0F, 1.0F);
        level.playSound(null, player.getX(), player.getY(), player.getZ(), draw, SoundSource.PLAYERS, 0.7F, 1.0F);
    }

    /**
     * 出一枚火箭：内容现摇，附魔等级与烟火之心数量写进 {@code NeoForgeData} 供伤害结算取用，弹体静音
     *
     * <p>静音是因为发射声由 {@link #playShotSounds} 自己放
     */
    private static void fireRocket(ServerLevel level, Player player, float yawOffset, int power, int punch, int flame) {
        RandomSource random = level.getRandom();
        int stars = MIN_ROCKET_STARS + random.nextInt(MAX_ROCKET_STARS - MIN_ROCKET_STARS + 1);
        FireworkRocketEntity rocket = new FireworkRocketEntity(
                level, randomRocket(random), player, player.getX(), player.getEyeY() - 0.15D, player.getZ(), true);
        rocket.setSilent(true);
        CompoundTag data = rocket.getPersistentData();
        data.putBoolean(MachinebowFireworks.TAG, true);
        data.putInt(MachinebowFireworks.STARS, stars);
        data.putInt(MachinebowFireworks.POWER, power);
        data.putInt(MachinebowFireworks.PUNCH, punch);
        data.putInt(MachinebowFireworks.FLAME, flame);
        rocket.shootFromRotation(player, player.getXRot(), player.getYRot() + yawOffset, 0.0F, ROCKET_SPEED, 1.0F);
        level.addFreshEntity(rocket);
    }

    /**
     * 一颗随机烟花火箭：形状五选一、一到两种颜色、一半概率带淡出色、拖尾与闪烁各掷一次硬币，
     * 飞行时长 {@value #MIN_FLIGHT_DURATION}~{@value #MAX_FLIGHT_DURATION}。
     * 外观只演一颗爆炸；烟火之心数量另存在弹体的数据里，只参与伤害换算，见 {@link MachinebowFireworks}
     */
    private static ItemStack randomRocket(RandomSource random) {
        FireworkExplosion.Shape[] shapes = FireworkExplosion.Shape.values();
        FireworkExplosion.Shape shape = shapes[random.nextInt(shapes.length)];
        IntList colors = new IntArrayList();
        int colorCount = 1 + random.nextInt(2);
        for (int i = 0; i < colorCount; i++) {
            colors.add(randomColor(random));
        }
        IntList fadeColors = random.nextBoolean() ? IntList.of(randomColor(random)) : IntList.of();
        FireworkExplosion explosion =
                new FireworkExplosion(shape, colors, fadeColors, random.nextBoolean(), random.nextBoolean());
        ItemStack stack = new ItemStack(Items.FIREWORK_ROCKET);
        stack.set(
                DataComponents.FIREWORKS,
                new Fireworks(
                        MIN_FLIGHT_DURATION + random.nextInt(MAX_FLIGHT_DURATION - MIN_FLIGHT_DURATION + 1),
                        List.of(explosion)));
        return stack;
    }

    private static int randomColor(RandomSource random) {
        return DyeColor.byId(random.nextInt(DyeColor.values().length)).getFireworkColor();
    }

    /**
     * 附魔等级：走数据包里的附魔注册表，拿不到就是 0
     *
     * <p>查询必须带注册表：1.21 的附魔全在数据包里，{@code Enchantments} 那几个只有键
     */
    private static int enchantmentLevel(Level level, ItemStack stack, ResourceKey<Enchantment> key) {
        Registry<Enchantment> registry = level.registryAccess().registryOrThrow(Registries.ENCHANTMENT);
        return stack.getEnchantmentLevel(registry.getHolderOrThrow(key));
    }
}
