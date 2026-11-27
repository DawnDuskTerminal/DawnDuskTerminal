package com.dawnduskterminal.item;

import com.dawnduskterminal.entity.FruitKind;
import com.dawnduskterminal.entity.FruitProjectileEntity;
import com.dawnduskterminal.registry.ModEntities;
import com.dawnduskterminal.registry.ModSounds;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.stats.Stats;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * 西瓜炮与南瓜炮
 *
 * <p>右键消耗身上的一整块西瓜或南瓜，射出一颗同种的整格炮弹。发射音是
 * {@code melongun_shoot}，音高每次随机；发射时朝瞄准方向喷出一簇初速各不相同的白色烟雾。
 * 使用者还会被后坐力推一下，水平沿视线向后、竖直抬一点，见 {@link #applyRecoil}。
 * 冷却 {@value #COOLDOWN_TICKS} tick（2 秒），耐久 {@value #DURABILITY}，每发扣 1 点。
 *
 * <p>弹药优先取副手，其次整份背包；创造模式不消耗。炮弹的物理、命中、偏转与爆裂都在
 * {@link FruitProjectileEntity}，这里只负责扣弹药、放发射音、喷发射烟雾、推使用者、
 * 上冷却与扣耐久。
 */
public class FruitGunItem extends Item {
    /** 耐久上限 */
    public static final int DURABILITY = 256;

    /** 冷却，40 tick = 2 秒 */
    public static final int COOLDOWN_TICKS = 40;

    /** 发射音的音高范围，含两端 */
    private static final float MIN_PITCH = 0.9F;

    private static final float MAX_PITCH = 1.1F;

    /** 发射时喷出的白色烟雾粒子数量 */
    private static final int LAUNCH_SMOKE_PARTICLES = 12;

    /** 烟雾从眼睛沿视线往前让出的距离 */
    private static final double LAUNCH_SMOKE_OFFSET = 0.9D;

    /** 烟雾生成点的横向散布 */
    private static final double LAUNCH_SMOKE_SPREAD = 0.15D;

    /** 每个粒子初速的范围，含两端 */
    private static final double MIN_LAUNCH_SMOKE_SPEED = 0.15D;

    private static final double MAX_LAUNCH_SMOKE_SPEED = 0.6D;

    /** 初速方向相对瞄准方向的抖动，归一化后约 20 度以内 */
    private static final double LAUNCH_SMOKE_DIRECTION_JITTER = 0.25D;

    /** 后坐力的水平分量，沿视线的水平投影向后 */
    public static final double RECOIL_HORIZONTAL = 0.25D;

    /** 后坐力的竖直分量，指向天空 */
    public static final double RECOIL_LIFT = 0.08D;

    /** 视线的水平分量短于这个值时只保留竖直分量，避免除以接近零的长度 */
    private static final double MIN_RECOIL_AIM = 1.0E-3D;

    private final FruitKind kind;

    public FruitGunItem(FruitKind kind, Properties properties) {
        super(properties);
        this.kind = kind;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (player.getCooldowns().isOnCooldown(this)) {
            return InteractionResultHolder.fail(stack);
        }
        if (!hasAmmo(player)) {
            return InteractionResultHolder.fail(stack);
        }
        if (level.isClientSide) {
            // 客户端先上冷却，服务端的冷却包晚一拍到也不影响手感
            player.getCooldowns().addCooldown(this, COOLDOWN_TICKS);
            return InteractionResultHolder.consume(stack);
        }
        if (!consumeAmmo(player)) {
            return InteractionResultHolder.fail(stack);
        }
        FruitProjectileEntity.launch(level, player, this.kind, this.entityType());
        applyRecoil(player);
        if (level instanceof ServerLevel server) {
            this.spawnLaunchSmoke(server, player);
        }
        level.playSound(
                null,
                player.getX(),
                player.getEyeY(),
                player.getZ(),
                ModSounds.MELONGUN_SHOOT.get(),
                SoundSource.PLAYERS,
                1.0F,
                Mth.nextFloat(level.random, MIN_PITCH, MAX_PITCH));
        player.getCooldowns().addCooldown(this, COOLDOWN_TICKS);
        player.awardStat(Stats.ITEM_USED.get(this));
        stack.hurtAndBreak(1, player, LivingEntity.getSlotForHand(hand));
        return InteractionResultHolder.consume(stack);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        super.appendHoverText(stack, context, tooltip, flag);
        tooltip.add(Component.translatable("item.dawnduskterminal." + this.kind.gunId() + ".desc")
                .withStyle(ChatFormatting.GRAY));
    }

    private EntityType<? extends FruitProjectileEntity> entityType() {
        return switch (this.kind) {
            case MELON -> ModEntities.MELON_PROJECTILE.get();
            case PUMPKIN -> ModEntities.PUMPKIN_PROJECTILE.get();
        };
    }

    /** 身上有没有这一种果实：创造模式永远算有 */
    private boolean hasAmmo(Player player) {
        if (player.hasInfiniteMaterials()) {
            return true;
        }
        if (player.getOffhandItem().is(this.kind.ammo())) {
            return true;
        }
        Inventory inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            if (inventory.getItem(slot).is(this.kind.ammo())) {
                return true;
            }
        }
        return false;
    }

    /** 消耗一枚这一种果实：优先副手，其次背包；创造模式不扣。返回这一发到底有没有拿到弹药 */
    private boolean consumeAmmo(Player player) {
        if (player.hasInfiniteMaterials()) {
            return true;
        }
        ItemStack offhand = player.getOffhandItem();
        if (offhand.is(this.kind.ammo())) {
            offhand.shrink(1);
            return true;
        }
        Inventory inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack candidate = inventory.getItem(slot);
            if (candidate.is(this.kind.ammo())) {
                candidate.shrink(1);
                return true;
            }
        }
        return false;
    }

    /**
     * 发射的后坐力：沿视线的水平投影向后推 {@value #RECOIL_HORIZONTAL}，竖直抬 {@value #RECOIL_LIFT}。
     * 抬到几乎垂直时水平分量按 {@value #MIN_RECOIL_AIM} 判断为没有方向，只保留竖直那一点推力，
     * 因此朝天上打不会随机往某一侧滑
     */
    private static void applyRecoil(Player player) {
        Vec3 look = player.getViewVector(1.0F);
        double horizontal = Math.sqrt(look.x * look.x + look.z * look.z);
        double x = 0.0D;
        double z = 0.0D;
        if (horizontal > MIN_RECOIL_AIM) {
            x = -look.x / horizontal * RECOIL_HORIZONTAL;
            z = -look.z / horizontal * RECOIL_HORIZONTAL;
        }
        player.push(x, RECOIL_LIFT, z);
        player.hurtMarked = true;
    }

    /**
     * 发射时的一簇白色烟雾：从炮口沿瞄准方向喷出，每个粒子的初速在
     * {@value #MIN_LAUNCH_SMOKE_SPEED}~{@value #MAX_LAUNCH_SMOKE_SPEED} 之间各摇一次，
     * 方向再叠一点抖动，于是整簇烟朝前散开而不是齐步走。
     *
     * <p>{@code sendParticles} 的 {@code count == 0} 那条路把三个偏移量直接当速度用，
     * 因此每个粒子单独发一次包，服务端附近的所有玩家都能看到
     */
    private void spawnLaunchSmoke(ServerLevel server, Player player) {
        Vec3 look = player.getViewVector(1.0F);
        Vec3 origin = player.getEyePosition().add(look.scale(LAUNCH_SMOKE_OFFSET));
        for (int index = 0; index < LAUNCH_SMOKE_PARTICLES; index++) {
            double speed = Mth.nextDouble(server.random, MIN_LAUNCH_SMOKE_SPEED, MAX_LAUNCH_SMOKE_SPEED);
            Vec3 direction = new Vec3(
                            look.x + (server.random.nextDouble() - 0.5D) * LAUNCH_SMOKE_DIRECTION_JITTER,
                            look.y + (server.random.nextDouble() - 0.5D) * LAUNCH_SMOKE_DIRECTION_JITTER,
                            look.z + (server.random.nextDouble() - 0.5D) * LAUNCH_SMOKE_DIRECTION_JITTER)
                    .normalize()
                    .scale(speed);
            server.sendParticles(
                    ParticleTypes.WHITE_SMOKE,
                    origin.x + (server.random.nextDouble() - 0.5D) * LAUNCH_SMOKE_SPREAD,
                    origin.y + (server.random.nextDouble() - 0.5D) * LAUNCH_SMOKE_SPREAD,
                    origin.z + (server.random.nextDouble() - 0.5D) * LAUNCH_SMOKE_SPREAD,
                    0,
                    direction.x,
                    direction.y,
                    direction.z,
                    1.0D);
        }
    }
}
