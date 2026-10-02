package ua.zentix.airstrike.client.flight;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import ua.zentix.airstrike.entity.BomberEntity;
import ua.zentix.airstrike.entity.StrikeProjectile;
import ua.zentix.airstrike.net.S2C;
import ua.zentix.airstrike.strike.WeaponType;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Пути снарядов на клиенте по UUID ({@link FlightTrack}): по сущности, пока она есть у клиента, без неё — по пакетам
 * сервера {@link S2C.FarFlights}; один путь переходит с одного на другое без шва. Из них звук снарядов
 * ({@code ClientSounds}) и картинка вдали ({@code client.far.FarFlightRenderer}). Время — свои тики клиента
 * ({@link #now}), на паузе стоят.
 */
public final class FlightTracks {
    private static final Map<UUID, FlightTrack> TRACKS = new HashMap<>();
    /**
     * Без новых данных столько тиков (сущность пропала, пакетов о ней нет) — полёт кончился. Молчит звук и пропадает
     * снаряд вдали раньше, как только данных на нужный момент нет ({@link FlightTrack#covers}); этот срок — чтобы
     * сервер, вставший на секунду, не обрывал далёкий звук.
     */
    private static final int STALE = 40;
    /**
     * Кончившийся полёт помнится столько: звук идёт до уха не дольше (дальше всех слышно свист крылатой ракеты, 3080
     * блоков, ~180 тиков), шлейф вдали за это время тает.
     */
    public static final int RINGOUT = 200;
    private static long tick;

    private FlightTracks() {}

    /** Текущее время путей, тики клиента. */
    public static long now() {
        return tick;
    }

    /** Раз в тик клиента (не на паузе): запись по сущностям, конец полётов, о которых давно ничего нет. */
    public static void tick() {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            reset();
            return;
        }
        tick++;
        for (Entity e : level.entitiesForRendering()) {
            if (e instanceof StrikeProjectile p && p.isActive()) track(p.getUUID(), p.weapon(), p instanceof BomberEntity).record(tick, p);
        }
        TRACKS.values().removeIf(t -> {
            if (!t.isDead() && tick - t.lastTick() > STALE) t.die(tick);
            return t.isDead() && tick - t.deathTick() > RINGOUT;
        });
    }

    /** Снаряды, которых у клиента нет, но которые уже слышно или видно: путь по данным сервера. */
    public static void received(S2C.FarFlights packet) {
        if (Minecraft.getInstance().level == null) return;
        for (S2C.FarFlight f : packet.flights()) track(f.id(), WeaponType.byId(f.weapon()), f.bomber()).record(tick, f);
    }

    /** Путь снаряда: уже идущий или новый (прошлый полёт с тем же UUID уже кончился). */
    private static FlightTrack track(UUID id, WeaponType weapon, boolean bomber) {
        FlightTrack t = TRACKS.get(id);
        if (t == null || t.isDead()) {
            t = new FlightTrack(id, weapon, bomber);
            TRACKS.put(id, t);
        }
        return t;
    }

    /** Нынешний путь снаряда или {@code null}. */
    public static FlightTrack get(UUID id) {
        return TRACKS.get(id);
    }

    public static Collection<FlightTrack> all() {
        return Collections.unmodifiableCollection(TRACKS.values());
    }

    /** Снаряд взорвался (пакет взрыва): его полёт кончился в этот тик ({@link FlightTrack#impact}). */
    public static void impact(UUID id) {
        FlightTrack t = TRACKS.get(id);
        if (t != null) t.impact(tick);
    }

    /** Обычный отбой: эти полёты кончились сразу (без взрыва — и без шлейфа, который тает). */
    public static void cancelled(Collection<UUID> projectiles) {
        projectiles.forEach(TRACKS::remove);
    }

    /** Отбой с ядерными или выход из мира. */
    public static void reset() {
        TRACKS.clear();
    }
}
