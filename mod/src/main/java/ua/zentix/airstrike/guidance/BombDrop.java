package ua.zentix.airstrike.guidance;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Падение бетонобойной бомбы после сброса: нос по дуге на точку, разгон до {@link #MAX_SPEED}. Одни и те же шаги
 * у самой бомбы и у B-2, который перед сбросом проигрывает её падение ({@link #miss}): попадёт ли бомба, сброшенная
 * сейчас, с этой высоты и дальности.
 */
public final class BombDrop {
    /** Скорость и тангаж в момент сброса: 6 блоков/тик, нос на 10° вниз. */
    public static final double DROP_SPEED = 6, DROP_PITCH = 10;
    public static final double MAX_SPEED = 12.5, ACCEL = 0.3;
    /** Бомба ниже B-2 на столько (отделяется от брюха). */
    public static final double DROP_BELOW = 4;
    /** Запас дальности подрыва сверх шага: бомба ближе шага и этого запаса к точке — попала в неё. */
    public static final double REACH_PAD = 5.3;
    /** Нос вниз не меньше, чем при сбросе: с 170 блоков бомба на земле самое позднее через ~80 тиков. */
    public static final float MIN_DIVE = 10;
    /** Падение, когда цели под носом нет. */
    public static final float FALL_PITCH = 60;
    /** Столько тиков бомба падает в {@link #miss} самое большее: с высоты до ~1000 блоков она на земле раньше. */
    private static final int MAX_TICKS = 200;

    private BombDrop() {}

    /** Точка позади по курсу {@code yaw} (дальше 8 блоков по горизонтали): бомба к ней уже не повернёт. */
    public static boolean passed(Vec3 pos, Vec3 aim, float yaw) {
        double dx = aim.x - pos.x, dz = aim.z - pos.z;
        if (dx * dx + dz * dz <= 64) return false;
        return Math.abs(Mth.wrapDegrees(FlightController.anglesTo(pos, aim)[0] - yaw)) >= 90;
    }

    /** Шаг наведения падающей бомбы: повернуть нос; возвращает скорость на этом тике. */
    public static double steer(FlightController flight, Vec3 pos, double speed, Vec3 aim) {
        float[] a = FlightController.anglesTo(pos, aim);
        double dx = aim.x - pos.x, dz = aim.z - pos.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        // свободно падающая бомба не выравнивается и не набирает высоту: точка не ниже MIN_DIVE под носом
        // (цель на высоте бомбы или выше, уже пролетели) — просто падать круто вниз, на рули не надеясь
        boolean below = a[1] >= MIN_DIVE && !passed(pos, aim, flight.yaw());
        flight.arcPitch(below ? a[1] : FALL_PITCH, speed, pos.distanceTo(aim), 7, 0.8);
        if (flight.pitch() < MIN_DIVE) flight.set(flight.yaw(), MIN_DIVE);
        if (below && horizontal > 8) flight.steerYaw(a[0], 0.15, 3.0, 0.3);
        return Math.min(MAX_SPEED, speed + ACCEL);
    }

    /**
     * Сбросить бомбу сейчас: B-2 в {@code pos} с курсом {@code yaw}, за тик он проходит {@code step}. Бомба, сброшенная
     * отсюда, придёт в точку ({@link #miss}), и дальше ждать нечего: B-2 дошёл до дальности сброса для своей высоты над
     * точкой ({@code lineRatio} × высота — угол на точку тот же, что на эшелоне) или со следующего тика бомба уже
     * промахнётся (выше эшелона бомбе нужно больше места, чтобы опустить нос). Падение проигрывается теми же шагами, что
     * у самой бомбы, — на ровной земле она придёт туда же. С любой высоты, на любой дальности; промах отовсюду (точка
     * слишком близко впереди, сзади, выше B-2) — заход снова.
     */
    public static boolean releaseNow(Vec3 pos, Vec3 step, float yaw, Vec3 aim, double lineRatio) {
        if (miss(pos, yaw, aim) > 0) return false;
        double dx = aim.x - pos.x, dz = aim.z - pos.z;
        return Math.sqrt(dx * dx + dz * dz) <= (pos.y - aim.y) * lineRatio || miss(pos.add(step), yaw, aim) > 0;
    }

    /**
     * Промах бомбы, сброшенной сейчас из {@code release} (там, где B-2) с курсом {@code yaw}, по точке {@code aim} на
     * ровной земле на её высоте: 0 — придёт в точку (ближе шага и {@link #REACH_PAD}), иначе — сколько блоков
     * по горизонтали от точки она войдёт в землю.
     */
    public static double miss(Vec3 release, float yaw, Vec3 aim) {
        FlightController flight = new FlightController(yaw, (float) DROP_PITCH);
        Vec3 pos = release.add(0, -DROP_BELOW, 0);
        double speed = DROP_SPEED;
        for (int t = 0; t < MAX_TICKS; t++) {
            speed = steer(flight, pos, speed, aim);
            if (pos.distanceTo(aim) <= speed + REACH_PAD) return 0;
            Vec3 next = pos.add(flight.forward().scale(speed));
            if (next.y <= aim.y) {
                Vec3 at = pos.lerp(next, (pos.y - aim.y) / (pos.y - next.y));
                return Math.sqrt((at.x - aim.x) * (at.x - aim.x) + (at.z - aim.z) * (at.z - aim.z));
            }
            pos = next;
        }
        return Double.POSITIVE_INFINITY;
    }
}
