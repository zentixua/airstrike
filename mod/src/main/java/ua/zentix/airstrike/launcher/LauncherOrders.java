package ua.zentix.airstrike.launcher;

import com.mojang.authlib.GameProfile;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.GameProfileCache;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import ua.zentix.airstrike.Airstrike;
import ua.zentix.airstrike.AirstrikeConfig;
import ua.zentix.airstrike.compat.SubLevels;
import ua.zentix.airstrike.strike.LaunchOrigin;
import ua.zentix.airstrike.strike.Loadout;
import ua.zentix.airstrike.strike.Munitions;
import ua.zentix.airstrike.strike.SalvoData;
import ua.zentix.airstrike.strike.ServerActions;
import ua.zentix.airstrike.strike.StrikeWorld;
import ua.zentix.airstrike.strike.Waypoints;
import ua.zentix.airstrike.util.Terrain;

import java.util.List;
import java.util.UUID;

/**
 * Приказы стационарным пусковым ({@link FixedLauncherBlockEntity}): задача с привязанного пульта ({@link #assign}) и
 * пуск по сигналу редстоуна ({@link #fire}). Пуск — тем же путём, что приказ игрока ({@link ServerActions#fromLauncher}):
 * один снаряд или залп ({@link SalvoData}) с огневой позиции — этой пусковой ({@link LaunchOrigin.Fixed}); снаряды —
 * от имени хозяина пусковой, поэтому свои и чужие по {@code /team}, его «Отбой» и предел снарядов в работе — как у его
 * приказов с пульта. Платит запас пусковой: на весь приказ он должен быть сразу (приказ не урезается), а снимается по
 * снаряду, когда тот встаёт на направляющую, — отменённый залп и неудачный пуск в запасе и остаются.
 * <p>
 * Пусковая хозяина — по его правилам ({@code rules} у {@link ServerActions#strike}): ему можно пульт, цель не дальше
 * {@code map_range} от самой пусковой, предел {@code max_active_per_player}. Ничья (поставлена командой ведущим) —
 * без них, как консоль.
 */
public final class LauncherOrders {
    private LauncherOrders() {}

    /** Сигнал дошёл до пусковой {@code be}: приказ по задаче. Null — приказ отдан; иначе — почему нет. */
    @Nullable
    public static Component fire(ServerLevel level, FixedLauncherBlockEntity be) {
        Mission m = be.mission();
        if (m == null) return Component.translatable("airstrike.fixed_launcher.no_mission.short");
        BlockPos pos = be.getBlockPos();
        // в сетке плотов Sable блок стоит не там, где его видят: пуск оттуда ушёл бы из далёкого плота
        if (SubLevels.inPlotGrid(level, new ChunkPos(pos))) return Component.translatable("airstrike.fixed_launcher.on_craft");
        UUID owner = be.owner();
        if (owner != null) {
            MutableComponent rule = rules(level.getServer(), owner, be.position(), m.point());
            if (rule != null) return rule;
        }
        LaunchOrigin.Fixed origin = new LaunchOrigin.Fixed(pos);
        // прошлый приказ ещё идёт: залп не кончился или снаряды на направляющих
        if (SalvoData.busy(level, origin) || !be.queue().silent(level.getGameTime())) return Component.translatable("airstrike.fixed_launcher.busy");
        Loadout l = ServerActions.clamp(new Loadout(m.weapon(), m.count(), m.spread(), Loadout.DEFAULT.mode(), "", Loadout.Nuke.DEFAULT));
        MutableComponent route = ServerActions.routeProblem(level, null, false, m.weapon(), origin, m.via(), m.point());
        if (route != null) return route;
        MutableComponent missing = Munitions.shortage(be.store().items(), Munitions.Bill.of(l));
        if (missing != null) return missing;
        String who = "пусковая " + pos.toShortString();
        Component before = be.lastReport();
        boolean ok = ServerActions.fromLauncher(level, who, owner, l, new ServerActions.Aim(m.target(), m.point(), null), m.via(), origin);
        if (ok) return null;
        // пуск сам сказал, что не так (сектор закрыт, запас), — эта причина точнее общей
        return be.lastReport() != before ? be.lastReport() : Component.translatable("airstrike.fixed_launcher.failed");
    }

    /**
     * Правила хозяина {@code owner} для приказа с пусковой в {@code at} по точке {@code aim}; null — можно. Хозяин может
     * быть не в сети: права — по его профилю.
     */
    @Nullable
    private static MutableComponent rules(MinecraftServer server, UUID owner, Vec3 at, Vec3 aim) {
        int permission = permission(server, owner);
        if (!AirstrikeConfig.SERVER.designatorForEveryone.get() && permission < 2) return Component.translatable("airstrike.fixed_launcher.no_rights");
        int limit = AirstrikeConfig.SERVER.maxActivePerPlayer.get();
        if (limit > 0 && permission < 2) {
            int active = StrikeWorld.active(server, owner);
            if (active >= limit) return Component.translatable("airstrike.too_many_active", active, limit);
        }
        return inRange(at, aim) ? null : Component.translatable("airstrike.fixed_launcher.out_of_range", AirstrikeConfig.SERVER.mapRange.get());
    }

    /** Цель не дальше {@code map_range} от пусковой по горизонтали: докуда бьёт пульт, оттуда же бьёт и она. */
    private static boolean inRange(Vec3 at, Vec3 aim) {
        double range = AirstrikeConfig.SERVER.mapRange.get(), dx = aim.x - at.x, dz = aim.z - at.z;
        return dx * dx + dz * dz <= range * range;
    }

    /** Уровень прав игрока {@code id}: в сети — его, иначе по профилю из кэша сервера (операторы, хост одиночной игры). */
    private static int permission(MinecraftServer server, UUID id) {
        ServerPlayer online = server.getPlayerList().getPlayer(id);
        GameProfileCache cache = server.getProfileCache();
        GameProfile profile = online != null ? online.getGameProfile() : cache == null ? null : cache.get(id).orElse(null);
        return profile == null ? 0 : server.getProfilePermissions(profile);
    }

    /**
     * «Огонь» пульта с привязанными пусковыми {@code links}: цель приказа {@code aim} — по правилам игрока, как его
     * собственный пуск ({@link ServerActions#ruled}), — становится задачей ({@link Mission}) каждой привязанной, до
     * которой можно дотянуться: в этом мире, в готовом чанке, своей или своей команды, цель в её дальности и маршрут
     * {@code via} — в дальности оружия от неё. Игроку — строка, скольким передано и почему не остальным.
     */
    public static void assign(ServerPlayer player, List<GlobalPos> links, Loadout loadout, ServerActions.Aim aim, Waypoints via) {
        Loadout l = ServerActions.clamp(loadout);
        if (!Mission.accepts(l.weapon())) {
            player.displayClientMessage(Component.translatable("airstrike.fixed_launcher.unsupported", l.weapon().displayName())
                    .withStyle(ChatFormatting.RED), true);
            return;
        }
        if (l.nuclear()) {
            player.displayClientMessage(Component.translatable("airstrike.fixed_launcher.no_nuke").withStyle(ChatFormatting.RED), true);
            return;
        }
        ServerActions.Aim ruled = ServerActions.ruled(player, aim);
        if (ruled == null) return;
        Mission mission = new Mission(l.weapon(), l.count(), l.spread(), ruled.target(), ruled.point(), via);
        int given = 0;
        MutableComponent why = null;
        for (GlobalPos at : links) {
            MutableComponent problem = assign(player, at, mission);
            if (problem == null) given++;
            else if (why == null) why = problem;
        }
        Airstrike.LOG.info("Задача пусковым: {} ×{} по {} {} {} — {} из {}, {}", l.weapon().getSerializedName(), l.count(), Math.round(ruled.point().x),
                Math.round(ruled.point().y), Math.round(ruled.point().z), given, links.size(), player.getGameProfile().getName());
        MutableComponent line = given > 0 ? Component.translatable("airstrike.fixed_launcher.assigned", given, links.size()).withStyle(ChatFormatting.GOLD)
                : Component.translatable("airstrike.fixed_launcher.assigned.none").withStyle(ChatFormatting.RED);
        if (why != null && given < links.size()) line.append(Component.literal(" — ").append(why).withStyle(ChatFormatting.RED));
        player.displayClientMessage(line, true);
    }

    /** Задача {@code mission} пусковой в {@code at}; null — поставлена, иначе — почему нет. */
    @Nullable
    private static MutableComponent assign(ServerPlayer player, GlobalPos at, Mission mission) {
        ServerLevel level = player.serverLevel();
        if (!at.dimension().equals(level.dimension())) return Component.translatable("airstrike.fixed_launcher.elsewhere");
        // чанк пусковой не грузим ради задачи: далеко от игроков её нет в памяти
        if (!Terrain.ready(level, at.pos())) return Component.translatable("airstrike.fixed_launcher.not_loaded");
        if (!(level.getBlockEntity(at.pos()) instanceof FixedLauncherBlockEntity be)) return Component.translatable("airstrike.fixed_launcher.gone");
        if (!LauncherLinks.mayCommand(player, be)) return Component.translatable("airstrike.fixed_launcher.not_yours");
        if (!inRange(be.position(), mission.point())) {
            return Component.translatable("airstrike.fixed_launcher.out_of_range", AirstrikeConfig.SERVER.mapRange.get());
        }
        // точки маршрута — на карте игрока, а дальность оружия — от пусковой
        MutableComponent route = ServerActions.routeProblem(level, player, true, mission.weapon(), new LaunchOrigin.Fixed(at.pos()), mission.via(),
                mission.point());
        if (route != null) return route;
        be.setMission(mission);
        return null;
    }
}
