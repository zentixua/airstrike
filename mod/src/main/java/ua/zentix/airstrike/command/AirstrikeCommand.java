package ua.zentix.airstrike.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import ua.zentix.airstrike.AirstrikeConfig;
import ua.zentix.airstrike.net.S2C;
import ua.zentix.airstrike.nuclear.NuclearStrikes;
import ua.zentix.airstrike.nuclear.radiation.GeigerFormat;
import ua.zentix.airstrike.nuclear.radiation.RadiationDose;
import ua.zentix.airstrike.nuclear.radiation.RadiationTicker;
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
 *   /airstrike nuke [кт] [air|ground]                  МБР туда, куда смотрю (по умолчанию — воздушный подрыв)
 *   /airstrike nuke at x y z [кт] [air|ground]         МБР по точке
 *   /airstrike nuke now [at x y z] [кт] [air|ground]   подрыв сразу, без полёта (отладка)
 *   /airstrike radiation [игрок] | radiation clear [игроки]
 * </pre>
 * Ник подсказывает Tab — регистр букв больше не важен. «shahed» — синоним drone, как в датапаке.
 */
public final class AirstrikeCommand {
    private static final SimpleCommandExceptionType TARGET_NOT_FOUND = new SimpleCommandExceptionType(Component.translatable("airstrike.target_not_found"));

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
            CommandSourceStack s = ctx.getSource();
            boolean nuclear = s.hasPermission(2) || s.getEntity() instanceof ServerPlayer p && ServerActions.mayUseNuke(p);
            int n = ServerActions.clearAll(s.getServer(), nuclear);
            s.sendSuccess(() -> Component.translatable("airstrike.cleared", n), true);
            return n;
        }));
        root.then(Commands.literal("give").requires(s -> s.hasPermission(2))
                .executes(ctx -> give(ctx, java.util.List.of(ctx.getSource().getPlayerOrException())))
                .then(Commands.argument("players", EntityArgument.players())
                        .executes(ctx -> give(ctx, EntityArgument.getPlayers(ctx, "players")))));

        root.then(nuke());
        root.then(Commands.literal("radiation").requires(s -> s.hasPermission(2))
                .executes(ctx -> radiation(ctx, ctx.getSource().getPlayerOrException()))
                .then(Commands.literal("clear")
                        .executes(ctx -> radiationClear(ctx, java.util.List.of(ctx.getSource().getPlayerOrException())))
                        .then(Commands.argument("players", EntityArgument.players())
                                .executes(ctx -> radiationClear(ctx, EntityArgument.getPlayers(ctx, "players")))))
                .then(Commands.argument("player", EntityArgument.player())
                        .executes(ctx -> radiation(ctx, EntityArgument.getPlayer(ctx, "player")))));

        LiteralArgumentBuilder<CommandSourceStack> salvo = Commands.literal("salvo");
        for (WeaponType w : WeaponType.values()) {
            for (String name : names(w)) {
                root.then(weapon(name, w));
                salvo.then(salvo(name, w));
            }
        }
        d.register(root.then(salvo));
    }

    private static String[] names(WeaponType w) {
        return switch (w) {
            case DRONE -> new String[]{"drone", "shahed"};
            case MISSILE -> new String[]{"missile"};
            case BUNKER -> new String[]{"bunker"};
            case ROCKET -> new String[]{"rocket", "grad"};
            case LOITER -> new String[]{"loiter", "lancet"};
            case NUKE -> new String[0]; // своя ветка: мощность и подрыв вместо количества и разброса
        };
    }

    // ---------------------------------------------------------------- ядерный удар

    private interface NukeAction {
        int run(CommandContext<CommandSourceStack> ctx, Loadout.Nuke nuke) throws CommandSyntaxException;
    }

    /** «[кт] [air|ground]» после любой ядерной команды. */
    private static <T extends com.mojang.brigadier.builder.ArgumentBuilder<CommandSourceStack, T>> T yieldArgs(T node, NukeAction action) {
        return node.executes(ctx -> action.run(ctx, new Loadout.Nuke(AirstrikeConfig.SERVER.nukeDefaultYield.get(), true)))
                .then(Commands.argument("kt", IntegerArgumentType.integer(1, Loadout.Nuke.MAX_YIELD))
                        .executes(ctx -> action.run(ctx, new Loadout.Nuke(IntegerArgumentType.getInteger(ctx, "kt"), true)))
                        .then(Commands.literal("air").executes(ctx -> action.run(ctx, new Loadout.Nuke(IntegerArgumentType.getInteger(ctx, "kt"), true))))
                        .then(Commands.literal("ground").executes(ctx -> action.run(ctx, new Loadout.Nuke(IntegerArgumentType.getInteger(ctx, "kt"), false)))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> nuke() {
        LiteralArgumentBuilder<CommandSourceStack> nuke = Commands.literal("nuke").requires(AirstrikeCommand::mayNuke);
        yieldArgs(nuke, AirstrikeCommand::nukeLook);
        return nuke
                .then(Commands.literal("at").then(yieldArgs(Commands.argument("pos", Vec3Argument.vec3()),
                        (ctx, n) -> nukeAt(ctx.getSource(), Vec3Argument.getVec3(ctx, "pos"), n))))
                .then(yieldArgs(Commands.literal("now"), (ctx, n) -> nukeNow(ctx.getSource(), lookPoint(ctx), n))
                        .then(Commands.literal("at").then(yieldArgs(Commands.argument("pos", Vec3Argument.vec3()),
                                (ctx, n) -> nukeNow(ctx.getSource(), Vec3Argument.getVec3(ctx, "pos"), n)))));
    }

    private static boolean mayNuke(CommandSourceStack s) {
        if (!AirstrikeConfig.SERVER.nukeEnabled.get()) return false;
        return s.hasPermission(2) || !AirstrikeConfig.SERVER.nukeOpsOnly.get() && s.getEntity() instanceof Player p && ServerActions.mayUse(p);
    }

    private static int nukeLook(CommandContext<CommandSourceStack> ctx, Loadout.Nuke nuke) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        Loadout l = ServerActions.clamp(new Loadout(WeaponType.NUKE, 1, 0, TargetMode.LOOK, "", nuke));
        ServerActions.Aim aim = ServerActions.fromMode(player, l, null);
        return aim != null && ServerActions.strike(player, WeaponType.NUKE, 1, 0, aim, l.nuke()) ? 1 : 0;
    }

    private static int nukeAt(CommandSourceStack s, Vec3 pos, Loadout.Nuke nuke) {
        Loadout.Nuke n = ServerActions.clamp(new Loadout(WeaponType.NUKE, 1, 0, TargetMode.LOOK, "", nuke)).nuke();
        return fire(s, WeaponType.NUKE, 1, 0, new ServerActions.Aim(new Target.Point(pos), pos, null), n);
    }

    private static Vec3 lookPoint(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        ServerActions.Aim aim = ServerActions.fromMode(player, new Loadout(WeaponType.NUKE, 1, 0, TargetMode.LOOK, "", Loadout.Nuke.DEFAULT), null);
        if (aim == null) throw TARGET_NOT_FOUND.create();
        return aim.point();
    }

    private static int nukeNow(CommandSourceStack s, Vec3 pos, Loadout.Nuke nuke) {
        Entity e = s.getEntity();
        var d = NuclearStrikes.detonateNow(s.getLevel(), NuclearStrikes.ground(s.getLevel(), pos), nuke.yieldKt(), nuke.airBurst(), e == null ? null : e.getUUID());
        double kt = d != null ? d.yieldKt() : Math.min(nuke.yieldKt(), AirstrikeConfig.SERVER.nukeMaxYield.get());
        s.sendSuccess(() -> Component.translatable(d != null ? "airstrike.nuke.detonated" : "airstrike.nuke.detonating", Math.round(kt),
                Component.translatable(nuke.airBurst() ? "airstrike.nuke.burst.air" : "airstrike.nuke.burst.ground"),
                Mth.floor(pos.x), Mth.floor(pos.y), Mth.floor(pos.z)), true);
        return 1;
    }

    private static int radiation(CommandContext<CommandSourceStack> ctx, ServerPlayer p) {
        RadiationDose r = RadiationTicker.dose(p);
        ctx.getSource().sendSuccess(() -> Component.translatable("airstrike.radiation.report", p.getDisplayName(),
                String.format(java.util.Locale.ROOT, "%.2f", r.doseGy()), GeigerFormat.rate(r.rate()),
                String.format(java.util.Locale.ROOT, "%.2f", r.contamination())), false);
        return Math.round(r.doseGy() * 100);
    }

    private static int radiationClear(CommandContext<CommandSourceStack> ctx, Collection<ServerPlayer> players) {
        for (ServerPlayer p : players) RadiationTicker.clear(p);
        ctx.getSource().sendSuccess(() -> Component.translatable("airstrike.radiation.cleared", players.size()), true);
        return players.size();
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
        return Commands.literal(name).then(Commands.argument("count", IntegerArgumentType.integer(1, Loadout.MAX_COUNT))
                .then(Commands.argument("spread", IntegerArgumentType.integer(0, Loadout.MAX_SPREAD))
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
        ServerActions.Aim aim = ServerActions.fromMode(player, new Loadout(w, count, spread, TargetMode.LOOK, "", Loadout.Nuke.DEFAULT), null);
        return aim != null && ServerActions.strike(player, w, count, spread, aim, Loadout.Nuke.DEFAULT) ? 1 : 0;
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
        return fire(s, w, count, spread, aim, Loadout.Nuke.DEFAULT);
    }

    private static int fire(CommandSourceStack s, WeaponType w, int count, int spread, ServerActions.Aim aim, Loadout.Nuke nuke) {
        if (s.getEntity() instanceof ServerPlayer player) {
            return ServerActions.strike(player, w, count, spread, aim, nuke) ? 1 : 0;
        }
        ServerLevel level = s.getLevel();
        float yaw = s.getRotation().y;
        StrikeService.log(s.getTextName(), w, count, spread, aim.point());
        if (count <= 1 && spread <= 0) {
            return StrikeService.launch(level, w, aim.target(), aim.point(), yaw, null, true, nuke, false).ok() ? 1 : 0;
        }
        SalvoData.start(level, w, count, spread, aim.target(), aim.point(), yaw, null, nuke);
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
        for (int i = 1; i <= 11; i++) {
            s.sendSystemMessage(Component.translatable("airstrike.help." + i).withStyle(ChatFormatting.GRAY));
        }
        return 1;
    }
}
