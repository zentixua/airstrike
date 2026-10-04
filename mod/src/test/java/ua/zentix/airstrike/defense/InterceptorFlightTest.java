package ua.zentix.airstrike.defense;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import ua.zentix.airstrike.strike.WeaponSpec;
import ua.zentix.airstrike.strike.WeaponType;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Полёт зенитной ракеты без мира ({@link Interceptor#steer}, {@link Interceptor#fuze}) по прямолинейной цели: каждое
 * оружие, которое ЗРК бьёт ({@code Signature.intercept}), ракета паспорта {@link InterceptorSpec#SAM} догоняет до
 * самоликвидации с края дальности, на которой его видно, — летящее на ЗРК, мимо и (кроме B-2) от него. Порядок тика —
 * как в мире: цель уже сдвинулась, ракета доворачивает на её нынешнее место, взрыватель смотрит отрезки обоих.
 */
class InterceptorFlightTest {
    private static final InterceptorSpec SPEC = InterceptorSpec.SAM;
    /** Настройки мира по умолчанию ({@code air_defense}). */
    private static final double RADAR = 3000, ENGAGE = 1500;
    private static final Vec3 RAIL = new Vec3(0.5, 65.2, 0.5);
    private static final double ALTITUDE = 100;

    /** Тик, на котором сработал взрыватель, или −1 — топливо кончилось раньше. */
    private static int fly(Vec3 start, Vec3 velocity, double halfLength) {
        Interceptor f = new Interceptor(SPEC, UUID.randomUUID(), null, "тест", RAIL, Interceptor.railDirection(RAIL, start, SPEC));
        Vec3 t = start;
        for (int tick = 1; tick <= SPEC.fuelTicks(); tick++) {
            Vec3 prev = t;
            t = t.add(velocity);
            Vec3 next = f.steer(t);
            if (f.fuze(next, prev, t, halfLength) >= 0) return tick;
            f.moveTo(next);
        }
        return -1;
    }

    private static List<WeaponType> interceptable() {
        List<WeaponType> out = new ArrayList<>();
        for (WeaponType w : WeaponType.values()) if (w.spec().airframe().radar().intercept()) out.add(w);
        return out;
    }

    /** С какой дальности по горизонтали ЗРК пускает по этому оружию: дальность огня, но не дальше, чем его видно. */
    private static double engageFrom(WeaponSpec.Airframe a) {
        return Math.min(ENGAGE, RADAR * a.radar().visibility());
    }

    @Test
    void catchesApproachingAndCrossingTargets() {
        List<WeaponType> types = interceptable();
        assertTrue(types.size() >= 4, "перехватываемых меньше четырёх: " + types);
        for (WeaponType w : types) {
            WeaponSpec.Airframe a = w.spec().airframe();
            double d = engageFrom(a);
            for (double speed : new double[]{a.cruiseSpeed(), a.diveSpeed()}) {
                for (int bearing = 0; bearing < 360; bearing += 45) {
                    double b = Math.toRadians(bearing);
                    Vec3 start = RAIL.add(Math.sin(b) * d, ALTITUDE, -Math.cos(b) * d);
                    Vec3 toward = RAIL.add(0, ALTITUDE, 0).subtract(start).normalize().scale(speed);
                    // на ЗРК; мимо — в 300 блоках сбоку
                    Vec3 cross = new Vec3(-toward.z, 0, toward.x);
                    Vec3 aside = start.add(cross.normalize().scale(300));
                    int head = fly(start, toward, a.noseLength());
                    int pass = fly(aside, toward, a.noseLength());
                    String what = w + " " + speed + " бл/тик с " + (int) d + " блоков, пеленг " + bearing;
                    assertTrue(head > 0, what + ": на ЗРК — не догнала");
                    assertTrue(pass > 0, what + ": мимо — не догнала");
                }
            }
        }
    }

    /** Уходящую цель (кроме B-2: ракета быстрее его на 2 блока/тик) ракета тоже догоняет с края дальности огня. */
    @Test
    void catchesRecedingSlowTargets() {
        for (WeaponType w : interceptable()) {
            WeaponSpec.Airframe a = w.spec().airframe();
            if (a.cruiseSpeed() > SPEC.maxSpeed() / 2) continue;
            double d = engageFrom(a);
            Vec3 start = RAIL.add(d, ALTITUDE, 0);
            int tick = fly(start, new Vec3(a.cruiseSpeed(), 0, 0), a.noseLength());
            assertTrue(tick > 0, w + " уходит с " + (int) d + " блоков — не догнала");
        }
    }

    /** На направляющей взрыватель не взведён: цель рядом с пусковой не рвёт ракету на старте. */
    @Test
    void fuzeIsSafeOnTheRail() {
        Interceptor f = new Interceptor(SPEC, UUID.randomUUID(), null, "тест", RAIL, new Vec3(0, 1, 0));
        Vec3 t = RAIL.add(1, 1, 0);
        for (int i = 1; i <= SPEC.railTicks(); i++) {
            Vec3 next = f.steer(t);
            assertEquals(-1, f.fuze(next, t, t, 2), 0, "взрыватель сработал на " + i + "-м тике схода");
            f.moveTo(next);
        }
        Vec3 next = f.steer(f.position().add(1, 0, 0));
        assertTrue(f.fuze(next, f.position().add(1, 0, 0), f.position().add(1, 0, 0), 2) >= 0, "после схода взрыватель не взвёлся");
    }

    /** Сход: вверх под углом паспорта в сторону цели; первые тики курс не меняется, на разгоне скорость растёт. */
    @Test
    void leavesTheRailUpwardsTowardsTarget() {
        Vec3 target = RAIL.add(-800, 50, 600);
        Vec3 dir = Interceptor.railDirection(RAIL, target, SPEC);
        assertEquals(SPEC.elevation(), Math.toDegrees(Math.asin(dir.y)), 1e-9);
        assertEquals(Math.atan2(600, -800), Math.atan2(dir.z, dir.x), 1e-9);
        Interceptor f = new Interceptor(SPEC, UUID.randomUUID(), null, "тест", RAIL, dir);
        Vec3 rail = f.direction();
        double speed = f.speed();
        for (int i = 1; i <= SPEC.railTicks(); i++) {
            f.moveTo(f.steer(target));
            assertEquals(rail, f.direction(), "курс на направляющей");
            assertTrue(f.speed() > speed);
            speed = f.speed();
        }
        for (int i = SPEC.railTicks() + 1; i <= SPEC.fuelTicks(); i++) f.moveTo(f.steer(target));
        assertEquals(SPEC.maxSpeed(), f.speed(), 1e-9, "после разгона — наибольшая скорость");
    }
}
