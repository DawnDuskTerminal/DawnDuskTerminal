package com.dawnduskterminal.command;

import com.dawnduskterminal.config.DdtConfig;
import com.dawnduskterminal.entity.YsmPackUser;
import com.dawnduskterminal.portal.PortalTeleporter;
import com.dawnduskterminal.registry.ModDimensions;
import com.dawnduskterminal.registry.ModStructures;
import com.dawnduskterminal.structure.GridPlacer;
import com.dawnduskterminal.structure.GridStructure;
import com.dawnduskterminal.world.ChronoLine;
import com.dawnduskterminal.world.ChronoLineState;
import com.dawnduskterminal.world.ChronoSkyLight;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import javax.annotation.Nullable;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.phys.Vec3;

public final class DdtCommands {
    private DdtCommands() {}

    /**
     * `/ddt pieces` 的公共收尾：摆完把消息发回去
     *
     * <p>{@code GridPlacer} 用「消息是否以 {@code catalogue:} 开头」区分成功与失败，
     * 失败的几种（结构不存在、不是 grid 类型、目录名不认识）都带一句为什么
     */
    private static int catalogue(
            CommandContext<CommandSourceStack> ctx,
            ServerLevel level,
            BlockPos origin,
            @Nullable ResourceLocation structure,
            @Nullable String dir) {
        String result = GridPlacer.placeCatalogue(level, origin, structure, dir);
        if (!result.startsWith("catalogue:")) {
            ctx.getSource().sendFailure(Component.literal(result));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.literal(result), false);
        return 1;
    }

    public static void register(net.neoforged.neoforge.event.RegisterCommandsEvent event) {
        event.getDispatcher()
                .register(Commands.literal("ddt")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.literal("line").executes(ctx -> {
                            ServerPlayer player = ctx.getSource().getPlayerOrException();
                            ChronoLine line = ChronoLineState.serverLine();
                            double distance = line.distance(player.getX(), player.getZ());
                            double param = line.param(player.getX(), player.getZ());
                            int light = ChronoSkyLight.maxSkyLight(
                                    line, (int) Math.floor(player.getX()), (int) Math.floor(player.getZ()));
                            ctx.getSource()
                                    .sendSuccess(
                                            () -> Component.literal(String.format(
                                                    "origin=(%.0f, %.0f) normal=(%.4f, %.4f) distance=%.1f param=%.3f skyLight=%d dimension=%s",
                                                    line.originX(),
                                                    line.originZ(),
                                                    line.normalX(),
                                                    line.normalZ(),
                                                    distance,
                                                    param,
                                                    light,
                                                    player.level().dimension().location())),
                                            false);
                            return 1;
                        }))
                        .then(Commands.literal("action")
                                .then(Commands.argument("targets", EntityArgument.entities())
                                        .then(Commands.argument("key", StringArgumentType.word())
                                                .executes(ctx -> {
                                                    String key = StringArgumentType.getString(ctx, "key");
                                                    int count = 0;
                                                    for (Entity entity : EntityArgument.getEntities(ctx, "targets")) {
                                                        if (entity instanceof YsmPackUser user) {
                                                            user.playAction(key);
                                                            count++;
                                                        }
                                                    }
                                                    if (count == 0) {
                                                        ctx.getSource()
                                                                .sendFailure(Component.literal(
                                                                        "no model-pack entity in targets"));
                                                        return 0;
                                                    }
                                                    int played = count;
                                                    ctx.getSource()
                                                            .sendSuccess(
                                                                    () -> Component.literal("action " + key + " -> "
                                                                            + played + " entity(s)"),
                                                                    false);
                                                    return played;
                                                }))))
                        .then(Commands.literal("roaming")
                                .then(Commands.argument("targets", EntityArgument.entities())
                                        .then(Commands.argument("key", StringArgumentType.word())
                                                .then(Commands.argument("value", DoubleArgumentType.doubleArg())
                                                        .executes(ctx -> {
                                                            String key = StringArgumentType.getString(ctx, "key");
                                                            double value = DoubleArgumentType.getDouble(ctx, "value");
                                                            int count = 0;
                                                            for (Entity entity :
                                                                    EntityArgument.getEntities(ctx, "targets")) {
                                                                if (entity instanceof YsmPackUser user) {
                                                                    user.setRoaming(key, value);
                                                                    count++;
                                                                }
                                                            }
                                                            if (count == 0) {
                                                                ctx.getSource()
                                                                        .sendFailure(Component.literal(
                                                                                "no model-pack entity in targets"));
                                                                return 0;
                                                            }
                                                            int applied = count;
                                                            ctx.getSource()
                                                                    .sendSuccess(
                                                                            () -> Component.literal(key + "=" + value
                                                                                    + " -> " + applied + " entity(s)"),
                                                                            false);
                                                            return applied;
                                                        })))))
                        .then(Commands.literal("pack")
                                .then(Commands.argument("targets", EntityArgument.entities())
                                        .then(Commands.argument("id", StringArgumentType.string())
                                                .executes(ctx -> {
                                                    String id = StringArgumentType.getString(ctx, "id");
                                                    int count = 0;
                                                    for (Entity entity : EntityArgument.getEntities(ctx, "targets")) {
                                                        if (entity instanceof YsmPackUser user) {
                                                            user.setYsmPack(id);
                                                            count++;
                                                        }
                                                    }
                                                    if (count == 0) {
                                                        ctx.getSource()
                                                                .sendFailure(Component.literal(
                                                                        "no model-pack entity in targets"));
                                                        return 0;
                                                    }
                                                    int applied = count;
                                                    ctx.getSource()
                                                            .sendSuccess(
                                                                    () -> Component.literal(
                                                                            "pack " + (id.isEmpty() ? "(default)" : id)
                                                                                    + " -> " + applied + " entity(s)"),
                                                                    false);
                                                    return applied;
                                                }))))
                        .then(Commands.literal("griddump").executes(ctx -> {
                            ServerLevel level = ctx.getSource().getLevel();
                            BlockPos pos = BlockPos.containing(ctx.getSource().getPosition());
                            String dump = GridPlacer.dump(level, pos);
                            for (String line : dump.split("\n")) {
                                ctx.getSource().sendSuccess(() -> Component.literal(line), false);
                            }
                            return 1;
                        }))
                        .then(Commands.literal("pieces")
                                .executes(ctx -> {
                                    ServerLevel level = ctx.getSource().getLevel();
                                    BlockPos origin =
                                            BlockPos.containing(ctx.getSource().getPosition());
                                    return catalogue(ctx, level, origin, null, null);
                                })
                                .then(Commands.argument("structure", ResourceLocationArgument.id())
                                        .suggests((ctx, builder) -> {
                                            Registry<Structure> registry = ctx.getSource()
                                                    .getLevel()
                                                    .registryAccess()
                                                    .registryOrThrow(Registries.STRUCTURE);
                                            registry.entrySet().stream()
                                                    .filter(entry ->
                                                            entry.getValue().type() == ModStructures.GRID.get())
                                                    .forEach(entry -> builder.suggest(entry.getKey()
                                                            .location()
                                                            .toString()));
                                            return builder.buildFuture();
                                        })
                                        .executes(ctx -> {
                                            ServerLevel level = ctx.getSource().getLevel();
                                            BlockPos origin = BlockPos.containing(
                                                    ctx.getSource().getPosition());
                                            ResourceLocation structure =
                                                    ResourceLocationArgument.getId(ctx, "structure");
                                            return catalogue(ctx, level, origin, structure, null);
                                        })
                                        .then(Commands.argument("dir", StringArgumentType.word())
                                                .suggests((ctx, builder) -> {
                                                    GridStructure.PIECE_DIRS.forEach(builder::suggest);
                                                    return builder.buildFuture();
                                                })
                                                .executes(ctx -> {
                                                    ServerLevel level =
                                                            ctx.getSource().getLevel();
                                                    BlockPos origin = BlockPos.containing(
                                                            ctx.getSource().getPosition());
                                                    ResourceLocation structure =
                                                            ResourceLocationArgument.getId(ctx, "structure");
                                                    String dir = StringArgumentType.getString(ctx, "dir");
                                                    return catalogue(ctx, level, origin, structure, dir);
                                                }))))
                        .then(Commands.literal("tp")
                                .then(Commands.argument("param", DoubleArgumentType.doubleArg(-1.0D, 1.0D))
                                        .executes(ctx -> {
                                            ServerPlayer player =
                                                    ctx.getSource().getPlayerOrException();
                                            ServerLevel chrono = player.server.getLevel(ModDimensions.CHRONO);
                                            if (chrono == null) {
                                                ctx.getSource()
                                                        .sendFailure(
                                                                Component.literal("chrono dimension is not loaded"));
                                                return 0;
                                            }
                                            double target = DoubleArgumentType.getDouble(ctx, "param");
                                            ChronoLine line = ChronoLineState.serverLine();
                                            double distance = target * DdtConfig.maxDistance();
                                            double x = line.originX() + line.normalX() * distance;
                                            double z = line.originZ() + line.normalZ() * distance;
                                            Vec3 landing = PortalTeleporter.findSafeLanding(chrono, x, z, 160);
                                            player.teleportTo(
                                                    chrono,
                                                    landing.x,
                                                    landing.y,
                                                    landing.z,
                                                    player.getYRot(),
                                                    player.getXRot());
                                            int light = ChronoSkyLight.maxSkyLight(
                                                    line, (int) Math.floor(landing.x), (int) Math.floor(landing.z));
                                            ctx.getSource()
                                                    .sendSuccess(
                                                            () -> Component.literal(String.format(
                                                                    "param=%.2f landed at %.1f %.1f %.1f skyLight=%d",
                                                                    target, landing.x, landing.y, landing.z, light)),
                                                            false);
                                            return 1;
                                        }))));
    }
}
