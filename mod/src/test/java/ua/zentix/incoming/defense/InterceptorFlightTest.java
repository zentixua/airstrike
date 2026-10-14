package ua.zentix.incoming.defense;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import ua.zentix.incoming.guidance.Ballistics;
import ua.zentix.incoming.strike.WeaponSpec;
import ua.zentix.incoming.strike.WeaponType;

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
    /** Середина контейнера ракеты у ЗРК на блоке (0, 64, 0) — {@code SamBlockEntity.rail}. */
    private static final Vec3 RAIL = new Vec3(0.5 - 0.33, 64 + 3.25, 0.5 - 0.33);
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

    /** Где шахед через {@code tick} тиков: идёт на высоте {@code height} над ЗРК, в 6 блоках сбоку, с {@code z0}, с z 0 — пике в землю у z 100. */
    private static Vec3 overhead(double z0, int tick, double height, double cruise, double dive) {
        double z = z0 + cruise * tick;
        if (z <= 0) return RAIL.add(6, height, z);
        Vec3 start = RAIL.add(6, height, 0), ground = RAIL.add(6, -1, 100);
        double s = dive * (tick + z0 / cruise);
        return s >= start.distanceTo(ground) ? ground : start.add(ground.subtract(start).normalize().scale(s));
    }

    /**
     * Шахед проходит над самым ЗРК и за ним пикирует в цель в 100 блоках (GameTest {@code shootsDownHostileDrones}):
     * в какой бы тик осмотра неба ракета ни сошла — с цели за 50 блоков до ЗРК до цели над ним, — она сбивает его до
     * земли. С одной угловой скоростью поворота ракета, сошедшая навстречу, разворачивалась уже за ним и шла за ним
     * до земли ({@link InterceptorSpec#turnLimit}).
     */
    @Test
    void catchesTargetPassingOverhead() {
        WeaponSpec.Airframe a = WeaponType.DRONE.spec().airframe();
        for (double z0 = -50; z0 <= 0; z0 += 0.5) {
            Interceptor f = new Interceptor(SPEC, UUID.randomUUID(), null, "тест", RAIL, Interceptor.railDirection(RAIL, overhead(z0, 0, 45, a.cruiseSpeed(),
                    a.diveSpeed()), SPEC));
            int hit = -1;
            for (int tick = 1; tick <= SPEC.fuelTicks(); tick++) {
                Vec3 prev = overhead(z0, tick - 1, 45, a.cruiseSpeed(), a.diveSpeed()), t = overhead(z0, tick, 45, a.cruiseSpeed(), a.diveSpeed());
                if (t.y <= RAIL.y) break;
                Vec3 next = f.steer(t);
                if (f.fuze(next, prev, t, a.noseLength()) >= 0) {
                    hit = tick;
                    break;
                }
                f.moveTo(next);
            }
            assertTrue(hit > 0, "шахед в " + (int) -z0 + " блоках до ЗРК: ракета не сбила его до земли");
        }
    }

    /**
     * Из отвесного схода к цели далеко и низко у горизонта ракета ложится дугой: каждый тик поворот не круче бокового
     * ускорения паспорта на скорости этого тика, в первый тик после направляющей — не больше 20°, а 10° над горизонтом
     * курс проходит за 6–10 тиков (0,3–0,5 с) после неё.
     */
    @Test
    void pitchOverIsAnArc() {
        Vec3 target = RAIL.add(1500, 100, 0);
        Interceptor f = new Interceptor(SPEC, UUID.randomUUID(), null, "тест", RAIL, Interceptor.railDirection(RAIL, target, SPEC));
        for (int i = 1; i <= SPEC.railTicks(); i++) f.moveTo(f.steer(target));
        int level = -1;
        for (int i = 1; i <= 40 && level < 0; i++) {
            Vec3 dir = f.direction();
            double speed = f.speed();
            f.moveTo(f.steer(target));
            double turn = Math.toDegrees(Math.acos(Math.min(1, dir.dot(f.direction()))));
            double byG = Math.toDegrees(SPEC.lateralG() * Ballistics.GRAVITY / Math.max(speed, SPEC.launchSpeed()));
            assertTrue(turn <= SPEC.turnLimit(speed) + 1e-9, i + "-й тик после схода: поворот " + turn + "°");
            assertTrue(turn <= byG + 1e-9, i + "-й тик после схода: поворот " + turn + "° круче " + SPEC.lateralG() + " g");
            if (i == 1) assertTrue(turn <= 20, "первый тик после схода: поворот " + turn + "°");
            if (Math.toDegrees(Math.asin(f.direction().y)) <= 10) level = i;
        }
        assertTrue(level >= 6 && level <= 10, "10° над горизонтом — на " + level + "-м тике после направляющей");
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

    /**
     * Сход: ракета ЗРК — отвесно из контейнера, ракета с наклонной направляющей — вверх под её углом в сторону цели;
     * первые тики курс не меняется, на разгоне скорость растёт.
     */
    @Test
    void leavesTheRailUpwards() {
        Vec3 target = RAIL.add(-800, 50, 600);
        Vec3 dir = Interceptor.railDirection(RAIL, target, SPEC);
        assertEquals(0, dir.subtract(0, 1, 0).length(), 1e-9, "ЗРК пускает отвесно");
        InterceptorSpec inclined = new InterceptorSpec(SPEC.railTicks(), 65, SPEC.launchSpeed(), SPEC.boostTicks(), SPEC.boostAccel(),
                SPEC.maxSpeed(), SPEC.lateralG(), SPEC.fuelTicks(), SPEC.fuse(), SPEC.noseLength());
        Vec3 slant = Interceptor.railDirection(RAIL, target, inclined);
        assertEquals(65, Math.toDegrees(Math.asin(slant.y)), 1e-9);
        assertEquals(Math.atan2(600, -800), Math.atan2(slant.z, slant.x), 1e-9);
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
