package ua.zentix.airstrike.guidance;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class OrbitTest {
    private static final Vec3 CENTER = Vec3.ZERO;
    /** Барражирующий: 1.6 блока/тик. */
    private static final double SPEED = 1.6;

    /**
     * Все радиусы, которые выбирает барражирующий (32–48), оба направления облёта, заход с 12 сторон, с 60–600 блоков
     * и с курсом мимо центра до ±40°: выход на круг, и дальше аппарат держит его линию в пределах {@link Orbit#TOLERANCE}.
     */
    @Test
    void capturesAndHoldsCircleFromAnyApproach() {
        double worst = 0;
        String where = "";
        for (double radius = 32; radius <= 48; radius += 4) {
            for (int side : new int[]{1, -1}) {
                for (int dir = 0; dir < 12; dir++) {
                    for (double start : new double[]{60, 150, 600}) {
                        for (double miss : new double[]{-40, 0, 40}) {
                            double a = Math.toRadians(dir * 30);
                            Vec3 pos = new Vec3(Math.sin(a) * start, 0, Math.cos(a) * start);
                            Orbit orbit = new Orbit(radius, side);
                            FlightController flight = new FlightController(FlightController.anglesTo(pos, CENTER)[0] + (float) miss, 0);
                            int captured = -1;
                            for (int t = 0; t < 1500; t++) {
                                orbit.steer(flight, pos, CENTER, SPEED);
                                pos = pos.add(flight.forward().scale(SPEED));
                                if (captured < 0 && orbit.captured(pos, CENTER, flight.yaw())) captured = t;
                                if (captured >= 0 && Math.abs(orbit.offset(pos, CENTER)) > Math.abs(worst)) {
                                    worst = orbit.offset(pos, CENTER);
                                    where = "R=" + radius + " side=" + side + " dir=" + dir * 30 + " start=" + start + " miss=" + miss;
                                }
                            }
                            assertTrue(captured >= 0 && captured < (start + 300) / SPEED,
                                    "не вышел на круг: R=" + radius + " side=" + side + " dir=" + dir * 30 + " start=" + start + " miss=" + miss);
                        }
                    }
                }
            }
        }
        assertTrue(Math.abs(worst) <= Orbit.TOLERANCE, "ушёл с круга на " + worst + ": " + where);
    }

    /** Сторона облёта: +1 и −1 кружат в разные стороны (курсовая скорость разного знака). */
    @Test
    void sideSetsDirection() {
        for (int side : new int[]{1, -1}) {
            Orbit orbit = new Orbit(40, side);
            Vec3 pos = new Vec3(0, 0, -40);
            FlightController flight = new FlightController((float) (FlightController.anglesTo(pos, CENTER)[0] - side * 90.0), 0);
            double turned = 0;
            for (int t = 0; t < 100; t++) {
                float before = flight.yaw();
                orbit.steer(flight, pos, CENTER, SPEED);
                pos = pos.add(flight.forward().scale(SPEED));
                turned += net.minecraft.util.Mth.wrapDegrees(flight.yaw() - before);
            }
            assertTrue(turned * side > 100, "сторона " + side + ": повернул на " + turned);
        }
    }
}
