package ua.zentix.airstrike.guidance;

import net.minecraft.world.phys.Vec3;
import ua.zentix.airstrike.entity.FlightPhase;
import ua.zentix.airstrike.strike.WeaponSpec;

/**
 * Закон полёта шахеда после старта: набор высоты на винте, крейсер над рельефом и не ниже цели+30, пикирование по
 * дуге, когда на последнем участке цель уходит на 18° под горизонт и прямая до неё свободна от блоков (иначе шахед
 * идёт дальше и пикирует круче, пока нос ещё успевает довернуть к цели), с разгоном до скорости пике. Старт с пусковой,
 * цель и шаг полёта — у сущности; здесь — только команды органам управления ({@link Craft}).
 */
public final class DroneAutopilot {
    /** Над целью крейсер не ниже стольких блоков. */
    private static final double ABOVE_TARGET = 30;
    /** Над рельефом впереди — не ниже стольких блоков. */
    private static final double ABOVE_RELIEF = 18;
    /** Пикирование — когда цель на столько градусов под горизонтом. */
    private static final double DIVE_PITCH = 18;
    /** Пике: предельная угловая скорость тангажа, °/тик, и её предельное ускорение, °/тик². */
    private static final double DIVE_RATE = 4.0, DIVE_ACCEL = 0.25;
    /**
     * Запас пути на доворот носа к цели: пике с закрытой прямой (цель под крышей, за высоткой у самой цели) начинается,
     * когда до цели осталось столько путей доворота ({@link #turnDistance}), — позже нос не успел бы, и шахед пролетал бы
     * цель. Пока путь длиннее, пике ждёт свободной прямой: над городом шахед врезался в высотку на линии пике за десятки
     * блоков до цели (проверка на городе хоста 01.10.2026).
     */
    private static final double DIVE_MARGIN = 1.5;
    /**
     * Закрытую прямую до цели проверять снова через столько тиков: луч — до ~200 блоков, в городе залп ждёт прямой
     * десятки тиков. За паузу шахед пролетает ~8 блоков, это покрывает {@link #DIVE_MARGIN}.
     */
    private static final int LINE_RECHECK = 4;
    /**
     * Пока прямая до цели закрыта, свой путь вперёд на высоте полёта проверяется на столько блоков: шахед ждёт прямой
     * над самой высокой частью города, а рельеф впереди ({@link Craft#reliefAhead}, до 45 блоков, три точки) видит
     * мачты и башни поздно — набор (до 15°) не успевал, и шахеды залпа били в них (город хоста 01.10.2026: 5 и 10 из 30).
     * На 90 блоках набор успевает подняться на ~20 блоков.
     */
    private static final double PATH_AHEAD = 90;

    private final WeaponSpec.Airframe air;
    private final Autopilot.Turn climbTurn;
    private final Autopilot.Turn cruiseTurn;
    /** Высота крейсера: не спускаемся ниже, даже если рельеф понижается. */
    private double cruiseAlt;
    /** Сколько тиков ещё не проверять закрытую прямую до цели (не сохраняется: после загрузки — проверить сразу). */
    private int lineWait;
    /**
     * Верх препятствия на своём пути впереди, найденного, пока прямая до цели закрыта: крейсер держится над ним
     * на {@link #ABOVE_RELIEF}; {@code -Infinity} — препятствия нет. Сбрасывается вне окна пике (не сохраняется:
     * после загрузки — проверить сразу).
     */
    private double obstacleTop = Double.NEGATIVE_INFINITY;

    public DroneAutopilot(WeaponSpec.Airframe air) {
        this.air = air;
        // на старте разворот мягче: скорость ещё мала
        this.climbTurn = new Autopilot.Turn(0.08, air.climbTurnRate(), 0.12, 0.12);
        this.cruiseTurn = new Autopilot.Turn(0.15, air.turnRate(), 0.3, 0.3);
    }

    public double cruiseAlt() {
        return cruiseAlt;
    }

    public void setCruiseAlt(double cruiseAlt) {
        this.cruiseAlt = cruiseAlt;
    }

    /**
     * Уже в воздухе (заход издалека, тесты): высота полёта = max(старт, цель+30, рельеф+20), она же — крейсер.
     *
     * @param surface высота рельефа под стартом
     */
    public double airborneAltitude(Vec3 pos, Vec3 target, double surface) {
        double y = Math.max(pos.y, target.y + ABOVE_TARGET);
        y = Math.max(y, surface + 20);
        cruiseAlt = y;
        return y;
    }

    /** С пусковой: крейсер на высоте из паспорта над стартом или целью, что выше. */
    public void fromLauncher(Vec3 rail, Vec3 target) {
        cruiseAlt = Math.max(rail.y, target.y) + air.cruiseHeight();
    }

    /**
     * Тик полёта после старта.
     *
     * @param aim       точка цели
     * @param nav       куда держать курс: точка маршрута или цель
     * @param finalLeg  маршрут пройден: последний участок — на цель
     */
    public void fly(Craft c, Vec3 aim, Vec3 nav, boolean finalLeg) {
        Bearing b = Bearing.of(c.position(), aim);
        Bearing n = Bearing.of(c.position(), nav);
        FlightPhase ph = c.phase();

        if (ph == FlightPhase.CLIMB) {
            // винт на полных оборотах, скорость после ускорителя спадает к крейсерской
            c.setSpeed(c.speed() + (air.cruiseSpeed() - c.speed()) * 0.04);
            double terrain = c.reliefAhead(15, 30, 45);
            c.holdAltitude(Math.max(cruiseAlt, terrain + ABOVE_RELIEF), 0.10, 1.0, 0.12);
            if (c.phaseAge() > 60 && Math.abs(cruiseAlt - c.position().y) < 6) c.setPhase(FlightPhase.CRUISE);
        }
        Autopilot.Turn turn = ph == FlightPhase.CLIMB ? climbTurn : cruiseTurn;
        // цель внутри круга разворота: пике отменяется, шахед уходит прямо, набирая высоту, и заходит снова
        boolean outOfTurn = Autopilot.outOfTurn(c, nav, n, air.attack().reattackMin(), turn.rate());
        if (outOfTurn && c.phase() == FlightPhase.TERMINAL) c.setPhase(FlightPhase.CRUISE);
        // пикирование — как только цель под нужным углом, даже если высота ещё набирается (цель рядом, перенацеливание)
        // прямая до цели — последней: луч по блокам дороже остальных условий
        boolean diveWindow = (c.phase() == FlightPhase.CRUISE || c.phase() == FlightPhase.CLIMB) && finalLeg && b.pitch() >= DIVE_PITCH && !outOfTurn;
        if (!diveWindow) obstacleTop = Double.NEGATIVE_INFINITY;
        if (diveWindow && (b.distance() <= turnDistance(c, b) * DIVE_MARGIN || lineClear(c, aim))) {
            c.setPhase(FlightPhase.TERMINAL);
        }

        if (c.phase() == FlightPhase.CRUISE) {
            c.setSpeed(c.speed() + (air.cruiseSpeed() - c.speed()) * 0.05);
            double terrain = c.reliefAhead(15, 30, 45);
            double desired = Math.max(Math.max(Math.max(terrain, obstacleTop) + ABOVE_RELIEF, cruiseAlt), aim.y + ABOVE_TARGET);
            c.holdAltitude(desired, 0.12, 1.2, 0.15);
        } else if (c.phase() == FlightPhase.TERMINAL) {
            c.flight().arcPitch(b.pitch(), c.speed(), b.distance(), DIVE_RATE, DIVE_ACCEL);
            c.setSpeed(Math.min(air.diveSpeed(), c.speed() + 0.04));
        }
        Autopilot.steer(c, outOfTurn, n, turn);
    }

    /**
     * Прямая до цели свободна; закрытая проверяется снова через {@link #LINE_RECHECK} тиков, и тогда же — свой путь
     * вперёд на высоте полёта ({@link #PATH_AHEAD}): верх первого препятствия на нём — в {@link #obstacleTop}
     * (наибольший за ожидание).
     */
    private boolean lineClear(Craft c, Vec3 aim) {
        if (lineWait > 0) {
            lineWait--;
            return false;
        }
        if (c.clearAlong(aim, air.reachPad()) == Double.POSITIVE_INFINITY) return true;
        lineWait = LINE_RECHECK - 1;
        Vec3 f = c.flight().forward();
        Vec3 level = new Vec3(f.x, 0, f.z).normalize();
        double free = c.clearAlong(c.position().add(level.scale(PATH_AHEAD)), 0);
        // наибольший верх за ожидание: над препятствием путь снова свободен, и крейсер опускался бы на него
        if (free != Double.POSITIVE_INFINITY) obstacleTop = Math.max(obstacleTop, c.reliefAhead(free));
        return false;
    }

    /**
     * Путь, за который нос доворачивает от нынешнего тангажа до линии на цель {@code b} в пределах пике
     * ({@link #DIVE_RATE}, {@link #DIVE_ACCEL}): разгон угловой скорости и торможение, при большом угле — с полкой на пределе.
     */
    private static double turnDistance(Craft c, Bearing b) {
        double turn = Math.max(0, b.pitch() - c.flight().pitch());
        double ticks = turn <= DIVE_RATE * DIVE_RATE / DIVE_ACCEL
                ? 2 * Math.sqrt(turn / DIVE_ACCEL)
                : turn / DIVE_RATE + DIVE_RATE / DIVE_ACCEL;
        return c.speed() * ticks;
    }
}
