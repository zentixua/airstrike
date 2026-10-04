package ua.zentix.airstrike.defense;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;
import ua.zentix.airstrike.Airstrike;
import ua.zentix.airstrike.AirstrikeConfig;
import ua.zentix.airstrike.entity.InterceptorEntity;
import ua.zentix.airstrike.entity.StrikeProjectile;
import ua.zentix.airstrike.net.S2C;
import ua.zentix.airstrike.registry.ModAttachments;
import ua.zentix.airstrike.registry.ModSounds;
import ua.zentix.airstrike.strike.StrikeWorld;
import ua.zentix.airstrike.strike.VirtualFlights;
import ua.zentix.airstrike.util.Terrain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * ПВО мира: зенитные ракеты в полёте ({@link Interceptor}), по каким целям они идут, о каких целях уже объявлена
 * тревога ({@link AirAlert}) и снимок снарядов мира на тик для радаров. Своё у каждого мира, не сохраняется
 * ({@link ModAttachments#DEFENSE_WORLD}): полёт ракеты — секунды, после перезапуска сервера его нет.
 * <p>
 * Ракеты летят здесь, в конце тика мира, — в мире и вне его одним кодом; в тикающих чанках у ракеты есть вид
 * ({@link InterceptorEntity}), который ставится на место полёта каждый тик, вне их вида нет.
 */
public final class DefenseWorld {
    private final List<Interceptor> flights = new ArrayList<>();
    /** Цель → ракета, которая за ней идёт. */
    private final Map<UUID, Interceptor> engaged = new HashMap<>();
    /** Сторона → цели, о которых ей объявлена тревога, и когда радар видел их последний раз. */
    private final Map<String, Map<UUID, Long>> alerted = new HashMap<>();
    /** Сторона → когда ей последний раз звучала тревога. */
    private final Map<String, Long> alertSound = new HashMap<>();
    private long snapshotTick = Long.MIN_VALUE;
    private List<StrikeProjectile> snapshot = List.of();

    /** Для {@link ModAttachments#DEFENSE_WORLD}. */
    public DefenseWorld() {}

    public static DefenseWorld get(ServerLevel level) {
        return level.getData(ModAttachments.DEFENSE_WORLD);
    }

    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !level.hasData(ModAttachments.DEFENSE_WORLD)) return;
        DefenseWorld world = get(level);
        // радары этого тика уже осмотрели небо (блок-сущности тикают раньше): снимок снарядов дальше не держать
        world.snapshot = List.of();
        world.snapshotTick = Long.MIN_VALUE;
        if (level.getGameTime() % AirAlert.FORGET == 0) world.forget(level.getGameTime());
        // «/tick freeze» останавливает сущности — ракеты стоят вместе со снарядами
        if (!level.tickRateManager().runsNormally()) return;
        world.tick(level);
    }

    /** Тревоги о целях, которых радары стороны давно не видят (ЗРК стороны мог и пропасть), — забыть. */
    private void forget(long now) {
        for (Map<UUID, Long> seen : alerted.values()) seen.values().removeIf(t -> now - t > AirAlert.FORGET);
        alerted.values().removeIf(Map::isEmpty);
        alertSound.values().removeIf(t -> now - t >= AirAlert.SOUND_GAP);
    }

    /** Снаряды мира на этот тик (в мире и вне его): один список на все радары тика. */
    List<StrikeProjectile> projectiles(ServerLevel level) {
        long now = level.getGameTime();
        if (now != snapshotTick) {
            snapshot = StrikeWorld.projectiles(level);
            snapshotTick = now;
        }
        return snapshot;
    }

    /** Снаряд мода по UUID: в мире или вне его; null — нет (взорвался, убран, ушёл из этого мира). */
    @Nullable
    StrikeProjectile find(ServerLevel level, UUID id) {
        if (level.getEntity(id) instanceof StrikeProjectile p && !p.isRemoved()) return p;
        for (StrikeProjectile p : VirtualFlights.get(level).flights()) {
            if (p.getUUID().equals(id) && !p.isRemoved()) return p;
        }
        return null;
    }

    /** За целью уже идёт ракета. */
    public boolean engaged(UUID target) {
        return engaged.containsKey(target);
    }

    /** Ракеты в полёте. */
    public List<Interceptor> flights() {
        return Collections.unmodifiableList(flights);
    }

    Map<UUID, Long> alerted(String side) {
        return alerted.computeIfAbsent(side, k -> new HashMap<>());
    }

    /** Тревога стороне звучит не чаще раза в {@code gap} тиков: true — пора, время отмечено. */
    boolean alertSound(String side, long now, int gap) {
        Long last = alertSound.get(side);
        if (last != null && now - last < gap) return false;
        alertSound.put(side, now);
        return true;
    }

    /** Пуск: ракета сходит с направляющей вверх под углом паспорта в сторону цели. */
    Interceptor launch(ServerLevel level, Vec3 rail, Radar.Track track, @Nullable UUID owner, String launcher, InterceptorSpec spec) {
        Interceptor f = new Interceptor(spec, track.id(), owner, launcher, rail, Interceptor.railDirection(rail, track.position(), spec));
        flights.add(f);
        engaged.put(track.id(), f);
        level.playSound(null, rail.x, rail.y, rail.z, ModSounds.ROCKET_LAUNCH.get(), SoundSource.AMBIENT, 6f, 1.15f);
        Airstrike.LOG.debug("ЗРК {} пустил ракету по {} {} в {} блоках", launcher, track.projectile().getType().getDescriptionId(), track.id(),
                (int) track.distance());
        show(level, f);
        return f;
    }

    private void tick(ServerLevel level) {
        if (flights.isEmpty()) return;
        for (Interceptor f : new ArrayList<>(flights)) {
            try {
                f.tick(level, this);
            } catch (RuntimeException e) {
                Airstrike.LOG.error("Зенитная ракета у {} упала с ошибкой и убрана", BlockPos.containing(f.position()), e);
                end(f, Interceptor.End.LOST);
            }
            if (!f.done()) show(level, f);
        }
    }

    /**
     * Ракета разорвалась в {@code at}: у цели ({@code target}) или сама (самоликвидация, земля). Сбила — цель
     * разваливается ({@link StrikeProjectile#intercepted}). Клиентам — разрыв в воздухе ({@link S2C.Intercept}).
     */
    void burst(ServerLevel level, Interceptor f, Vec3 at, @Nullable StrikeProjectile target, Interceptor.End end) {
        boolean kill = end == Interceptor.End.KILL && target != null;
        if (kill) target.intercepted(level, "ЗРК " + f.launcher());
        Airstrike.LOG.debug("Зенитная ракета ЗРК {} по {}: {} у {}", f.launcher(), f.target(), end, BlockPos.containing(at));
        PacketDistributor.sendToPlayersNear(level, null, at.x, at.y, at.z, AirstrikeConfig.SERVER.farRange.get(),
                new S2C.Intercept(at, kill, kill ? Optional.of(target.getUUID()) : Optional.empty(), level.random.nextLong()));
        end(f, end);
    }

    private void end(Interceptor f, Interceptor.End end) {
        f.finish(end);
        flights.remove(f);
        engaged.remove(f.target(), f);
        InterceptorEntity v = f.view();
        if (v != null) v.discard();
        f.setView(null);
    }

    /**
     * Вид ракеты: есть, только пока она над тикающим готовым чанком (там сущность сама не грузит ничего) — ставится на
     * место полёта; ушла из них — вид убран, полёт идёт дальше без него.
     */
    private static void show(ServerLevel level, Interceptor f) {
        BlockPos at = BlockPos.containing(f.position());
        boolean live = Terrain.ready(level, at) && level.isPositionEntityTicking(at);
        InterceptorEntity v = f.view();
        if (v != null && (v.isRemoved() || !live)) {
            v.discard();
            f.setView(null);
            v = null;
        }
        if (!live) return;
        if (v != null) {
            v.follow(f);
            return;
        }
        v = InterceptorEntity.of(level, f);
        if (level.addFreshEntity(v)) f.setView(v);
    }

    /** Убрать все ракеты без разрыва (проверки: остатки не достаются следующим). */
    public void clear() {
        for (Interceptor f : new ArrayList<>(flights)) end(f, Interceptor.End.LOST);
        alerted.clear();
        alertSound.clear();
    }
}
