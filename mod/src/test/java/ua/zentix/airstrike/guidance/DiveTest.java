package ua.zentix.airstrike.guidance;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiveTest {
    /** Точка встречи: снаряд и цель приходят в неё одновременно. */
    @Test
    void interceptIsReachedTogether() {
        Vec3 pos = new Vec3(0, 45, 0), target = new Vec3(40, 0, 0), v = new Vec3(0, 0, 0.5);
        Vec3 meet = Dive.intercept(pos, 4, target, v);
        double t = meet.subtract(target).length() / v.length();
        assertEquals(t * 4, meet.distanceTo(pos), 1e-6);
        assertEquals(target, Dive.intercept(pos, 4, target, Vec3.ZERO));
    }

    /**
     * Пике с круга барражирующего (40 блоков в стороне, 45 выше) на цель, которая идёт или кружит (как игрок в полёте
     * или бродящий моб) до 0.6 блока/тик: снаряд проходит в пределах поражения (скорость + 3 блока) с первого захода,
     * с какой бы стороны круга ни начал.
     */
    @Test
    void hitsMovingTargetFromCircle() {
        for (double v : new double[]{0, 0.3, 0.6}) {
            for (boolean circling : new boolean[]{false, true}) {
                for (int dir = 0; dir < 12; dir++) {
                    double a = Math.toRadians(dir * 30);
                    Vec3 target = Vec3.ZERO;
                    Vec3 pos = new Vec3(Math.sin(a) * 40, 45, Math.cos(a) * 40);
                    // на круге курс — по касательной
                    FlightController flight = new FlightController(FlightController.anglesTo(pos, target)[0] - 90f, 0);
                    double speed = 1.6, closest = Double.MAX_VALUE;
                    Vec3 vel = Vec3.ZERO;
                    for (int t = 0; t < 120; t++) {
                        double heading = circling ? t * v / 16 : 1.1;
                        vel = new Vec3(Math.cos(heading) * v, 0, Math.sin(heading) * v);
                        target = target.add(vel);
                        Dive.steer(flight, pos, speed, target, vel);
                        speed = Math.min(4, speed + 0.1);
                        pos = pos.add(flight.forward().scale(speed));
                        closest = Math.min(closest, pos.distanceTo(target));
                    }
                    assertTrue(closest <= 4 + 3, "мимо на " + closest + ": v=" + v + (circling ? " по кругу" : " прямо") + " с " + dir * 30 + "°");
                }
            }
        }
    }
}
