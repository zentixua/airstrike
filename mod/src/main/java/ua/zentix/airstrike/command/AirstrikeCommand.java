package ua.zentix.airstrike.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
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
import ua.zentix.airstrike.strike.NuclearKeys;
import ua.zentix.airstrike.strike.ServerActions;
import ua.zentix.airstrike.strike.TargetMode;
import ua.zentix.airstrike.strike.Waypoints;
import ua.zentix.airstrike.strike.WeaponType;
import ua.zentix.airstrike.target.Target;

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
 *   /airstrike menu | clear | give [игроки] | help
 *   /airstrike nuke [кт] [air|ground]                  МБР туда, куда смотрю (по умолчанию — воздушный подрыв)
 *   /airstrike nuke at x y z [кт] [air|ground]         МБР по точке
 *   /airstrike nuke now [at x y z] [кт] [air|ground]   подрыв сразу, без полёта (только оператор)
 *   /airstrike radiation [игрок] | radiation clear [игроки]
 *   /airstrike grid …                                  сеть и блэкаут ({@link GridCommand})
 * </pre>
 * Ник подсказывает Tab — регистр букв больше не важен. «shahed» — синоним drone, как в датапаке. Команда игрока без
 * прав оператора бьёт по правилам пульта ({@link #rules}) и тратит боеприпасы из инвентаря ({@code strike.Munitions});
 * оператор, консоль и командный блок не платят.
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
            // оператор (консоль, командный блок) и хост снимают всё, и ядерные удары; игрок — только свои, как с пульта
            boolean trusted = s.hasPermission(2) || s.getEntity() instanceof ServerPlayer p && NuclearKeys.trusted(p);
            if (!trusted && s.getEntity() instanceof ServerPlayer p) {
                // отбой — до строки: без sendCommandFeedback sendSuccess не зовёт поставщик строки
                Component done = ServerActions.recall(p);
                s.sendSuccess(() -> done, false);
                return 1;
            }
            int n = ServerActions.clearAll(s.getServer(), trusted, s.getTextName());
            s.sendSuccess(() -> ServerActions.clearedMessage(n, trusted), true);
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
                // подрыв без полёта — инструмент хоста: не платит, без тревоги; не оператору его нет и при ops_only = false
                .then(yieldArgs(Commands.literal("now").requires(s -> s.hasPermission(2)), (ctx, n) -> nukeNow(ctx.getSource(), lookPoint(ctx), n))
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
        return aim != null && ServerActions.strike(player, WeaponType.NUKE, 1, 0, aim, nuke, Waypoints.NONE, rules(ctx.getSource())) ? 1 : 0;
    }

    private static int nukeAt(CommandSourceStack s, Vec3 pos, Loadout.Nuke nuke) {
        return fire(s, WeaponType.NUKE, 1, 0, new ServerActions.Aim(new Target.Point(pos), pos, null), nuke);
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

    /** Количество и разброс залпа зажимает пуск ({@link ServerActions#clamp}). */
    private static int count(CommandContext<CommandSourceStack> ctx) {
        return IntegerArgumentType.getInteger(ctx, "count");
    }

    private static int spread(CommandContext<CommandSourceStack> ctx) {
        return IntegerArgumentType.getInteger(ctx, "spread");
    }

    private static int look(CommandContext<CommandSourceStack> ctx, WeaponType w, int count, int spread) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        ServerActions.Aim aim = ServerActions.fromMode(player, new Loadout(w, count, spread, TargetMode.LOOK, "", Loadout.Nuke.DEFAULT), null);
        return aim != null && ServerActions.strike(player, w, count, spread, aim, Loadout.Nuke.DEFAULT, Waypoints.NONE, rules(ctx.getSource())) ? 1 : 0;
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
        boolean ok = s.getEntity() instanceof ServerPlayer player
                ? ServerActions.strike(player, w, count, spread, aim, nuke, Waypoints.NONE, rules(s))
                : ServerActions.dispatch(s.getLevel(), s.getTextName(), s.getRotation().y, w, count, spread, aim, nuke);
        return ok ? 1 : 0;
    }

    /**
     * Приказ командой — по правилам пульта ({@link ServerActions#sighted}) и с оплатой боеприпасами, кроме операторов
     * сервера (и {@code /execute as} из консоли или командного блока — права у них): хост и ведущий устраивают события
     * без правил, как консоль и командный блок.
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
        for (int i = 1; i <= 12; i++) {
            s.sendSystemMessage(Component.translatable("airstrike.help." + i).withStyle(ChatFormatting.GRAY));
        }
        return 1;
    }
}
