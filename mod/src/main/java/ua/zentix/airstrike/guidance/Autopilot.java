package ua.zentix.airstrike.guidance;

import net.minecraft.world.phys.Vec3;
import ua.zentix.airstrike.strike.WeaponSpec;
import java.util.Arrays;

/**
 * Общие поведения управляемого полёта — один раз для всех законов ({@link DroneAutopilot}, {@link MissileAutopilot},
 * B-2): точка внутри круга разворота и уход на повторный заход, курс на точку маршрута. Чистый код без мира Minecraft.
 */
public final class Autopilot {
    private Autopilot() {}

    /**
     * Разворот по курсу на участке полёта.
     *
     * @param gain   усиление поворота к курсу на точку
     * @param rate   предельная угловая скорость, °/тик (паспорт)
     * @param accel  предельное угловое ускорение, °/тик²
     * @param settle угловое ускорение, с которым гасится поворот при уходе на повторный заход
     */
    public record Turn(double gain, double rate, double accel, double settle) {}

    /** Ближе этого по горизонтали курс на точку не трогаем: над самой точкой он скачет. */
    private static final double HOLD_COURSE = 8;

    /**
     * Точка внутри круга разворота: с предельной угловой скоростью {@code maxRateDeg} °/тик снаряд до неё не довернёт
     * и кружил бы вокруг неё, пока не кончится запас хода (крылатая ракета на 4 блоках/тик и 3°/тик разворачивается
     * по кругу радиусом ~80 блоков: цель, сместившаяся вбок на атаке, оставалась внутри). Такой снаряд сначала уходит
     * прямо, пока точка не выйдет из круга, и заходит снова. С запасом на разгон угловой скорости — {@link WeaponSpec#TURN_MARGIN}.
     */
    public static boolean insideTurn(Vec3 pos, Vec3 forward, double speed, Vec3 point, double maxRateDeg) {
        double fl = Math.sqrt(forward.x * forward.x + forward.z * forward.z);
        if (fl < 1e-6 || speed <= 0) return false;
        double fx = forward.x / fl, fz = forward.z / fl;
        double dx = point.x - pos.x, dz = point.z - pos.z;
        double r = WeaponSpec.turnRadius(speed * fl, maxRateDeg) * WeaponSpec.TURN_MARGIN;
        // центр разворота — сбоку, в сторону точки
        double nx = -fz, nz = fx;
        if (nx * dx + nz * dz < 0) {
            nx = -nx;
            nz = -nz;
        }
        double cx = dx - nx * r, cz = dz - nz * r;
        return cx * cx + cz * cz < r * r;
    }

    /**
     * Точка маршрута {@code nav} внутри круга разворота (промах в пике, цель ушла вбок, точка в воздухе, где цель
     * пропала): до неё не довернуть — атака отменяется, снаряд уходит прямо и заходит снова. Ближе {@code reattackMin}
     * по горизонтали — нет: небольшой промах добирает неконтактный взрыватель, а пролетев, снаряд зайдёт снова.
     */
    public static boolean outOfTurn(Craft c, Vec3 nav, Bearing toNav, double reattackMin, double rate) {
        return outOfTurn(c.position(), c.flight().forward(), c.speed(), nav, toNav, reattackMin, rate);
    }

    /** То же для места {@code pos}, носа {@code forward} и скорости {@code speed} (проигрыш пути вперёд, {@link #track}). */
    static boolean outOfTurn(Vec3 pos, Vec3 forward, double speed, Vec3 nav, Bearing toNav, double reattackMin, double rate) {
        return toNav.horizontal() > reattackMin && insideTurn(pos, forward, speed, nav, rate);
    }

    /** Курс: на повторном заходе — прямо, гася поворот; иначе — на точку маршрута (над самой точкой курс не трогаем). */
    public static void steer(Craft c, boolean outOfTurn, Bearing toNav, Turn turn) {
        steer(c.flight(), outOfTurn, toNav, turn);
    }

    static void steer(FlightController flight, boolean outOfTurn, Bearing toNav, Turn turn) {
        if (outOfTurn) {
            flight.settleYaw(turn.settle());
        } else if (toNav.horizontal() > HOLD_COURSE) {
            flight.steerYaw(toNav.yaw(), turn.gain(), turn.rate(), turn.accel());
        }
    }

    // ---------------------------------------------------------------- рельеф впереди

    /**
     * Полуширина полосы рельефа над путём, блоков: корпус (размах шахеда ~2,5 блока) и снос на развороте; тонкая
     * колонка (забор, мачта) в полосе не пропускается ({@link Corridor}).
     */
    static final double CORRIDOR_HALF_WIDTH = 1.5;
    /** Путь вперёд проигрывается поворотами не дальше стольких тиков, дальше — прямо по последнему курсу. */
    static final int TRACK_TICKS = 64;
    /** Точка пути — раз в столько тиков проигрыша: хорда дуги разворота (3°/тик) отходит от неё меньше чем на полблока. */
    private static final int TRACK_STEP = 4;
    /** Доля наибольшего угла набора ({@link AltitudeHold#MAX_CLIMB}) в {@link #CLIMB_GRADIENT}. */
    static final double CLIMB_SHARE = 0.5;
    /**
     * Наклон набора перед препятствием впереди: тангенс доли {@link #CLIMB_SHARE} наибольшего угла набора; остальное —
     * запас на то, что снаряд держит высоту фильтром и П-регулятором тангажа, а не по прямой.
     */
    static final double CLIMB_GRADIENT = Math.tan(Math.toRadians(AltitudeHold.MAX_CLIMB * CLIMB_SHARE));
    /**
     * Сколько тиков пути снаряд только выходит на набор: фильтр заданной высоты ({@link AltitudeHold}, 1/5 за тик)
     * и тангаж с ограничением угловой скорости и ускорения. На этом пути набора нет — рельеф на нём учитывается целиком,
     * не ближе, чем смотрел прежний датчик (у ракеты — 120 блоков против 90, у шахеда — 63 против 45). С меньшим запасом ракета, перенацеленная
     * внутри круга разворота над холмами, шла на атаку слишком низко и задевала склон (свойства полёта,
     * {@code AutopilotPropertiesTest}).
     */
    static final int REACTION_TICKS = 30;

    /**
     * Какую высоту рельефа надо иметь в виду сейчас: наибольшее по полосе ({@link #CORRIDOR_HALF_WIDTH}) над путём на
     * {@link WeaponSpec.Airframe#reliefLookahead} блоков вперёд ({@link #track}) за вычетом высоты, которую снаряд
     * успеет набрать до колонки ({@link #climb}). Без вычета длинная полоса держала бы ракету над самым высоким на
     * сотни блоков вперёд, и бреющего полёта не было бы. Прежние три точки по курсу пропускали мачту из забора и узкий
     * дом между ними, а фильтр высоты сглаживал короткий пик: ракета на бреющем и шахеды залпа бились в них.
     */
    public static double reliefAhead(Craft c, Vec3 nav, Turn turn, WeaponSpec.Airframe air) {
        double[] track = track(c, nav, turn, air.attack().reattackMin(), air.reliefLookahead());
        return Corridor.highest(track, CORRIDOR_HALF_WIDTH, climb(c.speed()), c::relief);
    }

    /** Набор высоты снаряда на скорости {@code speed}: {@link #REACTION_TICKS} тиков пути без набора, потом {@link #CLIMB_GRADIENT}. */
    static Corridor.Climb climb(double speed) {
        return new Corridor.Climb(REACTION_TICKS * speed, CLIMB_GRADIENT);
    }

    /** Полоса рельефа впереди считается заново раз в столько тиков ({@link ReliefSensor}). */
    static final int REFRESH_TICKS = 4;

    /**
     * Датчик рельефа впереди одного снаряда ({@link #reliefAhead}) с замером раз в {@link #REFRESH_TICKS} тиков: полоса —
     * сотни колонок, а за паузу меняется мало. Между замерами ответ растёт на {@link #CLIMB_GRADIENT} × путь с замера:
     * колонки впереди стали ближе, и набрать до них снаряд успеет меньше (оценка сверху — у ближних колонок, где набора
     * нет, это лишний запас не больше наклона × пути за паузу). Не сохраняется: после загрузки — замер сразу.
     */
    public static final class ReliefSensor {
        private double value;
        private Vec3 at;
        private int age;

        public double reliefAhead(Craft c, Vec3 nav, Turn turn, WeaponSpec.Airframe air) {
            Vec3 pos = c.position();
            if (at == null || age >= REFRESH_TICKS) {
                value = Autopilot.reliefAhead(c, nav, turn, air);
                at = pos;
                age = 0;
            }
            age++;
            return value + CLIMB_GRADIENT * Math.hypot(pos.x - at.x, pos.z - at.z);
        }
    }

    /** Наибольшая высота рельефа под полосой над путём {@code track} — без наклона (возврат в мир, «Ланцет»). */
    public static double reliefAlong(double[] track, Corridor.Relief relief) {
        return Corridor.highest(track, CORRIDOR_HALF_WIDTH, Corridor.Climb.NONE, relief);
    }

    /**
     * Путь по земле на {@code lookahead} блоков вперёд (x0, z0, x1, z1, …): повороты к {@code nav} проигрываются копией
     * органов управления ({@link FlightController#copy}) с тем же законом курса ({@link #steer}, повторный заход —
     * {@link #outOfTurn}) не дальше {@link #TRACK_TICKS} тиков, остаток — прямо. Скорость — нынешняя, по горизонтали:
     * с запасом на наборе и снижении.
     */
    static double[] track(Craft c, Vec3 nav, Turn turn, double reattackMin, double lookahead) {
        Vec3 pos = c.position();
        double[] out = new double[2 * (TRACK_TICKS / TRACK_STEP + 3)];
        int n = 0;
        out[n++] = pos.x;
        out[n++] = pos.z;
        if (lookahead <= 0) return Arrays.copyOf(out, n);
        FlightController f = c.flight().copy();
        double step = Math.max(0.5, c.speed()), x = pos.x, z = pos.z, travelled = 0;
        for (int t = 1; t <= TRACK_TICKS && travelled < lookahead; t++) {
            Vec3 here = new Vec3(x, pos.y, z);
            Bearing toNav = Bearing.of(here, nav);
            steer(f, outOfTurn(here, f.forward(), step, nav, toNav, reattackMin, turn.rate()), toNav, turn);
            double yaw = Math.toRadians(f.yaw()), d = Math.min(step, lookahead - travelled);
            x -= Math.sin(yaw) * d;
            z += Math.cos(yaw) * d;
            travelled += d;
            if (t % TRACK_STEP == 0 || travelled >= lookahead) {
                out[n++] = x;
                out[n++] = z;
            }
        }
        if (travelled < lookahead) {
            double yaw = Math.toRadians(f.yaw()), d = lookahead - travelled;
            out[n++] = x - Math.sin(yaw) * d;
            out[n++] = z + Math.cos(yaw) * d;
        }
        return Arrays.copyOf(out, n);
    }
}
