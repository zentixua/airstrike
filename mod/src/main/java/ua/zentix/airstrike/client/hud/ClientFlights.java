package ua.zentix.airstrike.client.hud;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import ua.zentix.airstrike.entity.FlightPhase;
import ua.zentix.airstrike.entity.StrikeProjectile;
import ua.zentix.airstrike.net.S2C;
import ua.zentix.airstrike.strike.WeaponType;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Свои снаряды в полёте, как их прислал сервер ({@link S2C.Flights}, раз в 5 тиков): время до удара между пакетами
 * убывает по тикам клиента, позиция — у сущности, если она видна клиенту, иначе по присланным: между двумя последними
 * пакетами, с отставанием на один пакет (интерполяция снимков, без угадывания вперёд). Номер снаряда («№3»)
 * постоянный, пока снаряд летит.
 */
public final class ClientFlights {
    /** Сколько точек пути помнить на снаряд (пакет раз в 5 тиков: ~2 минуты полёта). */
    private static final int TRAIL = 480;
    private static final List<Tracked> FLIGHTS = new ArrayList<>();
    private static long tick;
    private static int nextNumber = 1;

    private ClientFlights() {}

    /** Снаряд: данные от сервера и постоянный номер. */
    public static final class Tracked {
        public final UUID id;
        public final int number;
        S2C.Flight data;
        /** Позиция из предыдущего пакета и тики клиента, когда пришли предыдущий и последний. */
        private Vec3 prevPos;
        private long prevAt, at;
        /** Пройденный путь по пакетам сервера (для карты), не больше {@link #TRAIL} точек. */
        private final ArrayDeque<Vec3> trail = new ArrayDeque<>();

        Tracked(S2C.Flight data, int number) {
            this.id = data.id();
            this.data = data;
            this.number = number;
            this.prevPos = data.pos();
            this.at = tick;
            this.prevAt = tick - 1;
            trail.add(data.pos());
        }

        void accept(S2C.Flight next) {
            prevPos = data.pos();
            prevAt = at;
            at = tick;
            data = next;
            if (trail.getLast().distanceToSqr(next.pos()) > 1) trail.addLast(next.pos());
            while (trail.size() > TRAIL) trail.removeFirst();
        }

        public WeaponType weapon() {
            WeaponType[] w = WeaponType.values();
            return w[Math.max(0, Math.min(w.length - 1, data.weapon()))];
        }

        public FlightPhase phase() {
            return FlightPhase.byId(data.phase());
        }

        public boolean nuclear() {
            return data.nuclear();
        }

        /** Секунды до удара с учётом прошедших с пакета тиков. */
        public double etaSeconds(float partialTick) {
            return Math.max(0, data.eta() - (tick - at) - partialTick) / 20.0;
        }

        public Vec3 target() {
            return data.target();
        }

        /** Цель пропала: снаряд идёт в последнюю известную точку. */
        public boolean targetLost() {
            return data.targetLost();
        }

        /** Чья цель: ник, тип сущности, аппарат; для точки — null. */
        @Nullable
        public Component targetLabel() {
            String n = data.targetName();
            return switch (data.targetKind()) {
                case S2C.Flight.TARGET_NAMED -> Component.literal(n);
                case S2C.Flight.TARGET_TYPE -> Component.translatable(n);
                case S2C.Flight.TARGET_AIRCRAFT -> n.isBlank()
                        ? Component.translatable("airstrike.target.aircraft")
                        : Component.translatable("airstrike.target.aircraft.named", n);
                default -> null;
            };
        }

        /** Сущность снаряда, если клиент её видит. */
        @Nullable
        public StrikeProjectile entity() {
            Minecraft mc = Minecraft.getInstance();
            if (mc.level == null) return null;
            for (Entity e : mc.level.entitiesForRendering()) {
                if (e instanceof StrikeProjectile p && p.getUUID().equals(id) && !p.isRemoved()) return p;
            }
            return null;
        }

        /** Где снаряд сейчас (для метки на экране). */
        public Vec3 position(float partialTick) {
            StrikeProjectile p = entity();
            if (p != null) return p.getPosition(partialTick);
            double k = (tick - at + partialTick) / Math.max(1, at - prevAt);
            return prevPos.lerp(data.pos(), Math.min(1, k));
        }

        /** Скорость, блоков за тик: у сущности — за последний тик, иначе между двумя последними пакетами. */
        public Vec3 velocity() {
            StrikeProjectile p = entity();
            if (p != null) return p.position().subtract(p.xo, p.yo, p.zo);
            return data.pos().subtract(prevPos).scale(1.0 / Math.max(1, at - prevAt));
        }

        /** Пройденный путь по пакетам сервера, от старых точек к новым. */
        public Collection<Vec3> trail() {
            return Collections.unmodifiableCollection(trail);
        }
    }

    public static void update(S2C.Flights packet) {
        List<Tracked> next = new ArrayList<>();
        for (S2C.Flight f : packet.flights()) {
            Tracked t = find(f.id());
            if (t == null) t = new Tracked(f, nextNumber++);
            else t.accept(f);
            next.add(t);
        }
        FLIGHTS.clear();
        FLIGHTS.addAll(next);
        FLIGHTS.sort(Comparator.comparingInt(t -> t.data.eta()));
        if (FLIGHTS.isEmpty()) nextNumber = 1;
    }

    public static void tick() {
        tick++;
    }

    public static void reset() {
        FLIGHTS.clear();
        nextNumber = 1;
    }

    /** По времени до удара, ближайший первым. */
    public static List<Tracked> all() {
        return FLIGHTS;
    }

    @Nullable
    public static Tracked find(UUID id) {
        for (Tracked t : FLIGHTS) {
            if (t.id.equals(id)) return t;
        }
        return null;
    }
}
