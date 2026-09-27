package ua.zentix.airstrike.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import ua.zentix.airstrike.AirstrikeConfig;
import ua.zentix.airstrike.entity.StrikeProjectile;
import ua.zentix.airstrike.net.S2C;
import ua.zentix.airstrike.registry.ModItems;
import ua.zentix.airstrike.strike.Loadout;
import ua.zentix.airstrike.strike.SalvoData;
import ua.zentix.airstrike.strike.ServerActions;
import ua.zentix.airstrike.strike.StrikeService;
import ua.zentix.airstrike.strike.TargetMode;
import ua.zentix.airstrike.strike.WeaponType;
import ua.zentix.airstrike.target.Target;

import java.util.Collection;

/**
 * /airstrike — то же, что пульт, но для операторов и автоматизации (командные блоки, функции):
 * <pre>
 *   /airstrike drone|missile|bunker                   куда смотрю
 *   /airstrike missile ENOTzRPG                       по игроку или мобу (снаряд идёт за ним)
 *   /airstrike bunker at ~ ~-20 ~                     по точке
 *   /airstrike salvo drone 6 25 [me|look|ник|at x y z] залп: сколько, разброс
 *   /airstrike menu | clear | give [игроки] | help
 * </pre>
 * Ник подсказывает Tab — регистр букв больше не важен. «shahed» — синоним drone, как в датапаке.
 */
public final class AirstrikeCommand {
    private AirstrikeCommand() {}

    public static void register(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> d = event.getDispatcher();
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("airstrike")
                .requires(s -> s.hasPermission(2) || s.getEntity() instanceof Player p && ServerActions.mayUse(p));

        root.then(Commands.literal("help").executes(AirstrikeCommand::help));
        root.then(Commands.literal("menu").executes(ctx -> {
            PacketDistributor.sendToPlayer(ctx.getSource().getPlayerOrException(), new S2C.OpenRemote());
            return 1;
        }));
        root.then(Commands.literal("clear").executes(ctx -> {
            int n = ServerActions.clearAll(ctx.getSource().getServer());
            ctx.getSource().sendSuccess(() -> Component.translatable("airstrike.cleared", n), true);
            return n;
        }));
        root.then(Commands.literal("give").requires(s -> s.hasPermission(2))
                .executes(ctx -> give(ctx, java.util.List.of(ctx.getSource().getPlayerOrException())))
                .then(Commands.argument("players", EntityArgument.players())
                        .executes(ctx -> give(ctx, EntityArgument.getPlayers(ctx, "players")))));

        for (WeaponType w : WeaponType.values()) {
            for (String name : names(w)) {
                root.then(weapon(name, w));
                root.then(Commands.literal("salvo").then(salvo(name, w)));
            }
        }
        d.register(root);
    }

    private static String[] names(WeaponType w) {
        return switch (w) {
            case DRONE -> new String[]{"drone", "shahed"};
            case MISSILE -> new String[]{"missile"};
            case BUNKER -> new String[]{"bunker"};
        };
    }

    private static LiteralArgumentBuilder<CommandSourceStack> weapon(String name, WeaponType w) {
        return Commands.literal(name)
                .executes(ctx -> look(ctx, w, 1, 0))
                .then(Commands.literal("at").then(Commands.argument("pos", Vec3Argument.vec3())
                        .executes(ctx -> at(ctx, w, 1, 0, Vec3Argument.getVec3(ctx, "pos")))))
                .then(Commands.argument("target", EntityArgument.entity())
                        .executes(ctx -> entity(ctx, w, 1, 0, EntityArgument.getEntity(ctx, "target"))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> salvo(String name, WeaponType w) {
        return Commands.literal(name).then(Commands.argument("count", IntegerArgumentType.integer(1, 100))
                .then(Commands.argument("spread", IntegerArgumentType.integer(0, 500))
                        .executes(ctx -> me(ctx, w, count(ctx), spread(ctx)))
                        .then(Commands.literal("me").executes(ctx -> me(ctx, w, count(ctx), spread(ctx))))
                        .then(Commands.literal("look").executes(ctx -> look(ctx, w, count(ctx), spread(ctx))))
                        .then(Commands.literal("at").then(Commands.argument("pos", Vec3Argument.vec3())
                                .executes(ctx -> at(ctx, w, count(ctx), spread(ctx), Vec3Argument.getVec3(ctx, "pos")))))
                        .then(Commands.argument("target", EntityArgument.entity())
                                .executes(ctx -> entity(ctx, w, count(ctx), spread(ctx), EntityArgument.getEntity(ctx, "target"))))));
    }

    private static int count(CommandContext<CommandSourceStack> ctx) {
        return Math.min(IntegerArgumentType.getInteger(ctx, "count"), AirstrikeConfig.SERVER.maxSalvo.get());
    }

    private static int spread(CommandContext<CommandSourceStack> ctx) {
        return Math.min(IntegerArgumentType.getInteger(ctx, "spread"), AirstrikeConfig.SERVER.maxSpread.get());
    }

    private static int look(CommandContext<CommandSourceStack> ctx, WeaponType w, int count, int spread) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        ServerActions.Aim aim = ServerActions.fromMode(player, new Loadout(w, count, spread, TargetMode.LOOK, ""), null);
        return aim != null && ServerActions.strike(player, w, count, spread, aim) ? 1 : 0;
    }

    private static int me(CommandContext<CommandSourceStack> ctx, WeaponType w, int count, int spread) {
        CommandSourceStack s = ctx.getSource();
        return fire(s, w, count, spread, new ServerActions.Aim(new Target.Point(s.getPosition()), s.getPosition(), null));
    }

    private static int at(CommandContext<CommandSourceStack> ctx, WeaponType w, int count, int spread, Vec3 pos) {
        return fire(ctx.getSource(), w, count, spread, new ServerActions.Aim(new Target.Point(pos), pos, null));
    }

    private static int entity(CommandContext<CommandSourceStack> ctx, WeaponType w, int count, int spread, Entity target) {
        CommandSourceStack s = ctx.getSource();
        if (target.level() != s.getLevel()) {
            s.sendFailure(Component.translatable("airstrike.player_other_world", target.getDisplayName()));
            return 0;
        }
        return fire(s, w, count, spread, ServerActions.atPlayer(target));
    }

    /** Пуск от имени игрока (заход из-за его спины) или от консоли/командного блока. */
    private static int fire(CommandSourceStack s, WeaponType w, int count, int spread, ServerActions.Aim aim) {
        if (s.getEntity() instanceof ServerPlayer player) {
            return ServerActions.strike(player, w, count, spread, aim) ? 1 : 0;
        }
        ServerLevel level = s.getLevel();
        float yaw = s.getRotation().y;
        if (count <= 1 && spread <= 0) {
            StrikeProjectile e = StrikeService.launch(level, w, aim.target(), aim.point(), yaw, null, true);
            return e != null ? 1 : 0;
        }
        SalvoData.start(level, w, count, spread, aim.target(), aim.point(), yaw, null);
        return 1;
    }

    private static int give(CommandContext<CommandSourceStack> ctx, Collection<ServerPlayer> players) {
        for (ServerPlayer p : players) {
            if (!p.getInventory().add(ModItems.DESIGNATOR.get().getDefaultInstance())) p.drop(ModItems.DESIGNATOR.get().getDefaultInstance(), false);
        }
        ctx.getSource().sendSuccess(() -> Component.translatable("airstrike.given", players.size()), true);
        return players.size();
    }

    private static int help(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack s = ctx.getSource();
        s.sendSystemMessage(Component.translatable("airstrike.help.title").withStyle(ChatFormatting.DARK_RED, ChatFormatting.BOLD));
        for (int i = 1; i <= 9; i++) {
            s.sendSystemMessage(Component.translatable("airstrike.help." + i).withStyle(ChatFormatting.GRAY));
        }
        return 1;
    }
}
