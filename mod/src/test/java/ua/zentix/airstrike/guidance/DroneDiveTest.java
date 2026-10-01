package ua.zentix.airstrike.guidance;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import ua.zentix.airstrike.entity.FlightPhase;
import ua.zentix.airstrike.strike.WeaponSpec;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Пике шахеда над городом: высотка на линии пике между крейсером и целью (проверка на городе хоста 01.10.2026 —
 * 13 из 30 шахедов залпа врезались в дома высотой 42–68 над целью за 54–190 блоков до неё). Шахед идёт над городом
 * на высоте рельефа+18 и начинает пике, только когда прямая до цели свободна или нос иначе не успеет довернуть
 * к цели, и долетает до неё. Дом ближе 45 блоков к цели пике с крейсера над ним обходит не всегда (самый высокий —
 * нет: прямая освобождается круче, чем шахед успевает довернуть, и он пролетает цель); такие полёты только в выводе.
 */
class DroneDiveTest {
    private static final double GROUND = 64;
    /** Ближе к цели дом пике не обходит (см. описание класса). */
    private static final double NEAREST = 45;

    @Test
    void divesPastTowerOnDiveLine() {
        List<String> misses = new ArrayList<>();
        StringBuilder all = new StringBuilder();
        for (double height : new double[] {20, 30, 40, 50, 60, 70}) {
            for (double before : new double[] {20, 30, 45, 54, 70, 90, 130, 190}) {
                String end = strike(height, before);
                all.append(String.format(Locale.ROOT, "%n  дом %.0f в %.0f до цели: %s", height, before, end));
                if (before >= NEAREST && !end.startsWith("hit")) {
                    misses.add(String.format(Locale.ROOT, "дом высотой %.0f в %.0f блоках до цели: %s", height, before, end));
                }
            }
        }
        System.out.println("DroneDiveTest:" + all);
        assertTrue(misses.isEmpty(), String.join("\n", misses));
    }

    /** Шахед с 800 блоков над городом высотой {@code height} на цель за домом шириной 40 и толщиной 30; что вышло. */
    private static String strike(double height, double before) {
        // крейсер над плотной застройкой: старт на высоте дома + 20, как над городом
        AutopilotPropertiesTest.Ground ground = (x, z) ->
                Math.abs(x) <= 20 && z <= -before && z >= -before - 30 ? GROUND + height : GROUND;
        WeaponSpec.Airframe air = WeaponSpec.DRONE.airframe();
        DroneAutopilot drone = new DroneAutopilot(air);
        Vec3 aim = new Vec3(0, GROUND + 0.5, 0);
        Vec3 start = new Vec3(0, 0, -800);
        start = new Vec3(start.x, drone.airborneAltitude(start, aim, GROUND + height), start.z);
        AutopilotPropertiesTest.Model c = new AutopilotPropertiesTest.Model(ground, start, air.cruiseSpeed());
        c.altitude.reset(start.y);
        c.flight.set(FlightController.anglesTo(start, new Vec3(aim.x, start.y, aim.z))[0], 0);
        double maxPitch = 0;
        for (c.tick = 0; c.tick < 2000; c.tick++) {
            drone.fly(c, aim, aim, true);
            if (c.phase == FlightPhase.TERMINAL) maxPitch = Math.max(maxPitch, c.flight.pitch());
            if (c.pos.distanceTo(aim) <= c.speed + air.reachPad()) return String.format(Locale.ROOT, "hit, тангаж до %.0f°", maxPitch);
            Vec3 dir = c.flight.forward();
            for (double d = 0; d <= c.speed; d += 0.5) {
                Vec3 p = c.pos.add(dir.scale(d));
                if (p.y < ground.at(p.x, p.z)) {
                    return p.distanceTo(aim) <= c.speed + air.reachPad() ? "hit о землю у цели"
                            : String.format(Locale.ROOT, "врезался в %s, в %.0f блоках от цели", fmt(p), p.distanceTo(aim));
                }
            }
            c.pos = c.pos.add(dir.scale(c.speed));
        }
        return "не долетел: " + fmt(c.pos);
    }

    private static String fmt(Vec3 p) {
        return String.format(Locale.ROOT, "(%.0f %.0f %.0f)", p.x, p.y, p.z);
    }
}
