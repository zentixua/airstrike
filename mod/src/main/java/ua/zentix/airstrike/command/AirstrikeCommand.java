package ua.zentix.airstrike.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.coordinates.Coordinates;
import net.minecraft.commands.arguments.coordinates.Vec2Argument;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;
import ua.zentix.airstrike.AirstrikeConfig;
import ua.zentix.airstrike.net.S2C;
import ua.zentix.airstrike.nuclear.NuclearStrikes;
import ua.zentix.airstrike.nuclear.radiation.GeigerFormat;
import ua.zentix.airstrike.nuclear.radiation.RadiationDose;
import ua.zentix.airstrike.nuclear.radiation.RadiationTicker;
import ua.zentix.airstrike.registry.ModItems;
import ua.zentix.airstrike.strike.Loadout;
import ua.zentix.airstrike.strike.ServerActions;
import ua.zentix.airstrike.strike.TargetMode;
import ua.zentix.airstrike.strike.Waypoints;
import ua.zentix.airstrike.strike.WeaponSpec;
import ua.zentix.airstrike.strike.WeaponType;
import ua.zentix.airstrike.target.Target;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

/**
 * /airstrike — то же, что пульт, но для операторов и автоматизации (командные блоки, функции):
 * <pre>
 *   /airstrike drone|missile|bunker                   куда смотрю
 *   /airstrike missile ENOTzRPG                       по игроку или мобу (у оператора снаряд идёт за ним)
 *   /airstrike bunker at ~ ~-20 ~                     по точке
 *   /airstrike salvo drone 6 25 [me|look|ник|at x y z] залп: сколько, разброс
 *          … [from x z] [via x z [x z …]]            место пуска (оператор) и точки маршрута, как на карте пульта;
 *                                                     один снаряд — salvo drone 1 0 …
 *   /airstrike menu | clear | give [игроки] | help
 *   /airstrike nuke [кт] [air|ground]                  МБР туда, куда смотрю (по умолчанию — воздушный подрыв)
 *   /airstrike nuke at x y z [кт] [air|ground]         МБР по точке
 *   /airstrike nuke now [at x y z] [кт] [air|ground]   подрыв сразу, без полёта (отладка)
 *   /airstrike radiation [игрок] | radiation clear [игроки]
 *   /airstrike grid …                                  сеть и блэкаут ({@link GridCommand})
 * </pre>
 * Ник подсказывает Tab — регистр букв больше не важен. «shahed» — синоним drone, как в датапаке. Команда игрока без
 * прав оператора бьёт по правилам пульта ({@link #rules}).
 * <p>
 * Место пуска {@code from} — где встаёт пусковая (для постановочных боёв: видно, кто откуда стреляет); задаёт его только
 * оператор, консоль и командный блок. Точки {@code via} — те же, что игрок ставит на карте пульта ({@link Waypoints}),
 * и только у оружия, которое по ним летает.
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
            int n = ServerActions.clearAll(s.getServer(), nuclear, s.getTextName());
            s.sendSuccess(() -> ServerActions.clearedMessage(n, nuclear), true);
            return n;
        }));
        root.then(Commands.literal("give").requires(s -> s.hasPermission(2))
                .executes(ctx -> give(ctx, List.of(ctx.getSource().getPlayerOrException())))
                .then(Commands.argument("players", EntityArgument.players())
                        .executes(ctx -> give(ctx, EntityArgument.getPlayers(ctx, "players")))));

        root.then(nuke());
        root.then(GridCommand.build());
        root.then(Commands.literal("radiation").requires(s -> s.hasPermission(2))
                .executes(ctx -> radiation(ctx, ctx.getSource().getPlayerOrException()))
                .then(Commands.literal("clear")
                        .executes(ctx -> radiationClear(ctx, List.of(ctx.getSource().getPlayerOrException())))
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
    private static <T extends ArgumentBuilder<CommandSourceStack, T>> T yieldArgs(T node, NukeAction action) {
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
        ServerActions.Aim aim = ServerActions.fromMode(player, new Loadout(WeaponType.NUKE, 1, 0, TargetMode.LOOK, "", nuke), null);
        return aim != null && ServerActions.strike(player, WeaponType.NUKE, 1, 0, aim, nuke, Waypoints.NONE, null, rules(ctx.getSource())) ? 1 : 0;
    }

    private static int nukeAt(CommandSourceStack s, Vec3 pos, Loadout.Nuke nuke) {
        return fire(s, WeaponType.NUKE, 1, 0, new ServerActions.Aim(new Target.Point(pos), pos, null), nuke, Path.NONE);
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
                String.format(Locale.ROOT, "%.2f", r.doseGy()), GeigerFormat.rate(r.rate()),
                String.format(Locale.ROOT, "%.2f", r.contamination())), false);
        return Math.round(r.doseGy() * 100);
    }

    private static int radiationClear(CommandContext<CommandSourceStack> ctx, Collection<ServerPlayer> players) {
        for (ServerPlayer p : players) RadiationTicker.clear(p);
        ctx.getSource().sendSuccess(() -> Component.translatable("airstrike.radiation.cleared", players.size()), true);
        return players.size();
    }

    private static LiteralArgumentBuilder<CommandSourceStack> weapon(String name, WeaponType w) {
        return Commands.literal(name)
                .executes(ctx -> look(ctx, w, 1, 0, Path.NONE))
                .then(Commands.literal("at").then(Commands.argument("pos", Vec3Argument.vec3())
                        .executes(ctx -> at(ctx, w, 1, 0, Vec3Argument.getVec3(ctx, "pos"), Path.NONE))))
                .then(Commands.argument("target", EntityArgument.entity())
                        .executes(ctx -> entity(ctx, w, 1, 0, EntityArgument.getEntity(ctx, "target"), Path.NONE)));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> salvo(String name, WeaponType w) {
        return Commands.literal(name).then(Commands.argument("count", IntegerArgumentType.integer(1, Loadout.MAX_COUNT))
                .then(pathArgs(Commands.argument("spread", IntegerArgumentType.integer(0, Loadout.MAX_SPREAD)), w,
                        (ctx, path) -> me(ctx, w, count(ctx), spread(ctx), path))
                        .then(pathArgs(Commands.literal("me"), w, (ctx, path) -> me(ctx, w, count(ctx), spread(ctx), path)))
                        .then(pathArgs(Commands.literal("look"), w, (ctx, path) -> look(ctx, w, count(ctx), spread(ctx), path)))
                        .then(Commands.literal("at").then(pathArgs(Commands.argument("pos", Vec3Argument.vec3()), w,
                                (ctx, path) -> at(ctx, w, count(ctx), spread(ctx), Vec3Argument.getVec3(ctx, "pos"), path))))
                        .then(pathArgs(Commands.argument("target", EntityArgument.entity()), w,
                                (ctx, path) -> entity(ctx, w, count(ctx), spread(ctx), EntityArgument.getEntity(ctx, "target"), path)))));
    }

    // ---------------------------------------------------------------- откуда и через где

    /** Откуда и через где летит приказ: место пуска (по горизонтали; null — у стреляющего или издалека) и точки маршрута. */
    private record Path(@Nullable Vec3 from, Waypoints via) {
        static final Path NONE = new Path(null, Waypoints.NONE);
    }

    private interface Order {
        int run(CommandContext<CommandSourceStack> ctx, Path path) throws CommandSyntaxException;
    }

    /**
     * «[from x z] [via x z [x z …]]» после цели залпа: место пуска — там встаёт пусковая (только у оператора, консоли
     * и командного блока) — и точки маршрута, как на карте пульта (только у оружия, которое по ним летает: паспорт,
     * {@link WeaponSpec.Route#waypoints}).
     */
    private static <T extends ArgumentBuilder<CommandSourceStack, T>> T pathArgs(T node, WeaponType w, Order order) {
        RequiredArgumentBuilder<CommandSourceStack, Coordinates> from = Commands.argument("site", Vec2Argument.vec2())
                .executes(ctx -> order.run(ctx, path(ctx, true, 0)));
        if (w.spec().route().waypoints()) {
            node.then(via(order, false));
            from.then(via(order, true));
        }
        return node.executes(ctx -> order.run(ctx, Path.NONE))
                .then(Commands.literal("from").requires(s -> s.hasPermission(2)).then(from));
    }

    /** «via x z [x z …]»: до {@link Waypoints#MAX} точек по порядку пролёта; {@code from} — после места пуска. */
    private static LiteralArgumentBuilder<CommandSourceStack> via(Order order, boolean from) {
        ArgumentBuilder<CommandSourceStack, ?> next = null;
        for (int i = Waypoints.MAX; i >= 1; i--) {
            int n = i;
            RequiredArgumentBuilder<CommandSourceStack, Coordinates> point = Commands.argument("point" + i, Vec2Argument.vec2())
                    .executes(ctx -> order.run(ctx, path(ctx, from, n)));
            if (next != null) point.then(next);
            next = point;
        }
        return Commands.literal("via").then(next);
    }

    /** Место пуска (если {@code from}) и первые {@code points} точек маршрута из команды. */
    private static Path path(CommandContext<CommandSourceStack> ctx, boolean from, int points) {
        List<Vec3> via = new ArrayList<>(points);
        for (int i = 1; i <= points; i++) via.add(column(ctx, "point" + i));
        return new Path(from ? column(ctx, "site") : null, new Waypoints(via));
    }

    /** Место «x z» по горизонтали; «~» — от того, кто дал команду (в числах double: {@code Vec2Argument.getVec2} — float). */
    private static Vec3 column(CommandContext<CommandSourceStack> ctx, String name) {
        Vec3 p = ctx.getArgument(name, Coordinates.class).getPosition(ctx.getSource());
        return new Vec3(p.x, 0, p.z);
    }

    /** Количество и разброс залпа зажимает пуск ({@link ServerActions#clamp}). */
    private static int count(CommandContext<CommandSourceStack> ctx) {
        return IntegerArgumentType.getInteger(ctx, "count");
    }

    private static int spread(CommandContext<CommandSourceStack> ctx) {
        return IntegerArgumentType.getInteger(ctx, "spread");
    }

    private static int look(CommandContext<CommandSourceStack> ctx, WeaponType w, int count, int spread, Path path) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        ServerActions.Aim aim = ServerActions.fromMode(player, new Loadout(w, count, spread, TargetMode.LOOK, "", Loadout.Nuke.DEFAULT), null);
        return aim != null ? fire(ctx.getSource(), w, count, spread, aim, Loadout.Nuke.DEFAULT, path) : 0;
    }

    private static int me(CommandContext<CommandSourceStack> ctx, WeaponType w, int count, int spread, Path path) {
        CommandSourceStack s = ctx.getSource();
        return fire(s, w, count, spread, new ServerActions.Aim(new Target.Point(s.getPosition()), s.getPosition(), null), Loadout.Nuke.DEFAULT, path);
    }

    private static int at(CommandContext<CommandSourceStack> ctx, WeaponType w, int count, int spread, Vec3 pos, Path path) {
        return fire(ctx.getSource(), w, count, spread, new ServerActions.Aim(new Target.Point(pos), pos, null), Loadout.Nuke.DEFAULT, path);
    }

    private static int entity(CommandContext<CommandSourceStack> ctx, WeaponType w, int count, int spread, Entity target, Path path) {
        CommandSourceStack s = ctx.getSource();
        if (target.level() != s.getLevel()) {
            s.sendFailure(Component.translatable("airstrike.player_other_world", target.getDisplayName()));
            return 0;
        }
        return fire(s, w, count, spread, ServerActions.atPlayer(target), Loadout.Nuke.DEFAULT, path);
    }

    /**
     * Пуск от имени игрока (заход из-за его спины) или от консоли/командного блока; место пуска и точки маршрута
     * {@code path} сначала проверяются ({@link ServerActions#routeProblem}).
     */
    private static int fire(CommandSourceStack s, WeaponType w, int count, int spread, ServerActions.Aim aim, Loadout.Nuke nuke, Path path) {
        ServerPlayer player = s.getEntity() instanceof ServerPlayer p ? p : null;
        ServerLevel level = player != null ? player.serverLevel() : s.getLevel();
        MutableComponent problem = ServerActions.routeProblem(level, player, rules(s), w, path.from(), path.via(), aim.point());
        if (problem != null) {
            s.sendFailure(problem);
            return 0;
        }
        boolean ok = player != null
                ? ServerActions.strike(player, w, count, spread, aim, nuke, path.via(), path.from(), rules(s))
                : ServerActions.dispatch(level, s.getTextName(), s.getRotation().y, w, count, spread, aim, nuke, path.via(), path.from());
        return ok ? 1 : 0;
    }

    /**
     * Приказ командой — по правилам пульта ({@link ServerActions#sighted}), кроме операторов сервера: хост и ведущий
     * устраивают события без правил, как консоль и командный блок.
     */
    private static boolean rules(CommandSourceStack s) {
        return !s.hasPermission(2);
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
        for (int i = 1; i <= 13; i++) {
            s.sendSystemMessage(Component.translatable("airstrike.help." + i).withStyle(ChatFormatting.GRAY));
        }
        return 1;
    }
}
