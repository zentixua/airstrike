package ua.zentix.airstrike.nuclear;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;
import ua.zentix.airstrike.AirstrikeConfig;
import ua.zentix.airstrike.entity.IcbmEntity;
import ua.zentix.airstrike.net.S2C;
import ua.zentix.airstrike.nuclear.radiation.RadiationTicker;
import ua.zentix.airstrike.nuclear.world.NuclearWorld;
import ua.zentix.airstrike.registry.ModEntities;
import ua.zentix.airstrike.util.Local;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Пуск МБР и запланированные удары (DESIGN-nuke §7): ракета стартует у запустившего (виден разгон), уходит за
 * потолок мира, дальше полёт — таймер в {@link NuclearEvents}; тревога у тех, кто рядом с целью, отсчёт у всех.
 * Здесь же подписки на события мира, которые нужны ядерной части.
 */
public final class NuclearStrikes {
    private NuclearStrikes() {}

    /**
     * Пуск. Если есть кто запускает — ракета видна: старт в 30 блоках позади него; иначе (консоль, командный блок)
     * пуск «издалека» — сразу таймер.
     */
    public static boolean launch(ServerLevel level, Vec3 target, double yieldKt, boolean airBurst, @Nullable ServerPlayer owner) {
        return owner == null ? launchFrom(level, target, yieldKt, airBurst, null, 0, null)
                : launchFrom(level, target, yieldKt, airBurst, owner.position(), owner.getYRot(), owner.getUUID());
    }

    /**
     * @param launcher где стоит запустивший (ракета стартует в 30 блоках позади него по курсу {@code yaw}),
     *                 null — пуск «издалека», сразу таймер
     */
    public static boolean launchFrom(ServerLevel level, Vec3 target, double yieldKt, boolean airBurst, @Nullable Vec3 launcher, float yaw,
                                     @Nullable UUID owner) {
        if (!AirstrikeConfig.SERVER.nukeEnabled.get()) return false;
        yieldKt = Math.min(yieldKt, AirstrikeConfig.SERVER.nukeMaxYield.get());
        if (launcher == null) {
            schedule(level, target, yieldKt, airBurst, target, owner);
            return true;
        }
        Vec3 back = Local.horizontal(yaw).scale(-30);
        int x = Mth.floor(launcher.x + back.x), z = Mth.floor(launcher.z + back.z);
        Vec3 pad = new Vec3(x + 0.5, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z + 0.5);
        IcbmEntity icbm = ModEntities.ICBM.get().create(level);
        if (icbm == null) return false;
        icbm.prepare(pad, target, owner);
        if (!level.addFreshEntity(icbm)) return false;
        schedule(level, target, yieldKt, airBurst, pad, owner);
        return true;
    }

    /** Записать удар: момент подрыва = сейчас + время полёта; всем в измерении — тревога и отсчёт. */
    private static void schedule(ServerLevel level, Vec3 target, double yieldKt, boolean airBurst, Vec3 launchPos, @Nullable UUID owner) {
        NuclearEvents events = NuclearEvents.get(level);
        long now = level.getGameTime();
        NuclearEvents.ScheduledStrike s = new NuclearEvents.ScheduledStrike(events.nextId(), target, yieldKt, airBurst, now,
                now + AirstrikeConfig.SERVER.nukeFlightTime.get(), launchPos, Optional.ofNullable(owner));
        events.schedule(s);
        for (ServerPlayer p : level.players()) PacketDistributor.sendToPlayer(p, warning(s, p));
    }

    /** Предупреждение для конкретного игрока: тревога — если он в радиусе {@code warning_radius} от цели. */
    public static S2C.NukeWarning warning(NuclearEvents.ScheduledStrike s, ServerPlayer p) {
        double r = AirstrikeConfig.SERVER.nukeWarningRadius.get();
        boolean alarm = p.position().distanceToSqr(s.target()) <= r * r;
        boolean mine = s.owner().map(p.getUUID()::equals).orElse(false);
        return new S2C.NukeWarning(s.id(), s.target(), s.launchPos(), s.launchTime(), s.detonateTime(), s.yieldKt(), s.airBurst(), alarm, mine,
                AirstrikeConfig.SERVER.nukeEffectsScale.get().floatValue());
    }

    /** Подрыв без полёта (команда «nuke now», GameTest). */
    public static Detonation detonateNow(ServerLevel level, Vec3 target, double yieldKt, boolean airBurst, @Nullable UUID owner) {
        return NuclearWarhead.detonate(level, target, Math.min(yieldKt, AirstrikeConfig.SERVER.nukeMaxYield.get()), airBurst, owner);
    }

    /** Отбой: запланированные удары отменены, следы осадков убраны, очереди остановлены. */
    public static int clear(ServerLevel level) {
        int n = NuclearEvents.get(level).clear();
        NuclearWorld.get(level).clear(level);
        sync(level);
        return n;
    }

    // ---------------------------------------------------------------- события мира

    public static void onLevelTick(LevelTickEvent.Post e) {
        if (!(e.getLevel() instanceof ServerLevel level)) return;
        NuclearEvents events = NuclearEvents.get(level);
        long now = level.getGameTime();
        List<NuclearEvents.ScheduledStrike> due = new ArrayList<>();
        for (NuclearEvents.ScheduledStrike s : events.scheduled()) {
            if (now >= s.detonateTime()) due.add(s);
        }
        for (NuclearEvents.ScheduledStrike s : due) {
            events.unschedule(s);
            NuclearWarhead.detonate(level, s.target(), s.yieldKt(), s.airBurst(), s.owner().orElse(null));
        }
        if (now % 1200 == 0) events.prune(now);
        NuclearWorld.get(level).tick(level);
        RadiationTicker.tick(level);
    }

    public static void onChunkLoad(ChunkEvent.Load e) {
        if (e.getLevel() instanceof ServerLevel level && e.getChunk() instanceof net.minecraft.world.level.chunk.LevelChunk chunk) {
            NuclearWorld.get(level).onChunkLoad(level, chunk);
        }
    }

    public static void onChunkUnload(ChunkEvent.Unload e) {
        if (e.getLevel() instanceof ServerLevel level && e.getChunk() instanceof net.minecraft.world.level.chunk.LevelChunk chunk) {
            NuclearWorld.get(level).onChunkUnload(chunk);
        }
    }

    public static void onLevelUnload(LevelEvent.Unload e) {
        if (e.getLevel() instanceof ServerLevel level) NuclearWorld.forget(level);
    }

    /** Вход и смена измерения: действующие подрывы и летящие ракеты этого измерения. */
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent e) {
        if (e.getEntity() instanceof ServerPlayer p) sync(p);
    }

    public static void onChangeDimension(PlayerEvent.PlayerChangedDimensionEvent e) {
        if (e.getEntity() instanceof ServerPlayer p) sync(p);
    }

    private static void sync(ServerPlayer p) {
        NuclearEvents events = NuclearEvents.get(p.serverLevel());
        PacketDistributor.sendToPlayer(p, new S2C.NukeSync(List.copyOf(events.detonations()),
                events.scheduled().stream().map(s -> warning(s, p)).toList()));
    }

    private static void sync(ServerLevel level) {
        for (ServerPlayer p : level.players()) sync(p);
    }

    /** Точка на земле под целью (для пуска по игроку или сущности — по их позиции). */
    public static Vec3 ground(ServerLevel level, Vec3 at) {
        BlockPos p = BlockPos.containing(at);
        if (!level.isLoaded(p)) return at;
        return new Vec3(at.x, Math.min(at.y, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, p.getX(), p.getZ())), at.z);
    }
}
