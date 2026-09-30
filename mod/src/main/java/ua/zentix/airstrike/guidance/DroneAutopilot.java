package ua.zentix.airstrike.guidance;

import net.minecraft.world.phys.Vec3;
import ua.zentix.airstrike.entity.FlightPhase;
import ua.zentix.airstrike.strike.WeaponSpec;

/**
 * Закон полёта шахеда после старта: набор высоты на винте, крейсер над рельефом и не ниже цели+30, пикирование по
 * дуге, когда на последнем участке цель уходит на 18° под горизонт, с разгоном до скорости пике. Старт с пусковой,
 * цель и шаг полёта — у сущности; здесь — только команды органам управления ({@link Craft}).
 */
public final class DroneAutopilot {
    /** Над целью крейсер не ниже стольких блоков. */
    private static final double ABOVE_TARGET = 30;
    /** Над рельефом впереди — не ниже стольких блоков. */
    private static final double ABOVE_RELIEF = 18;
    /** Пикирование — когда цель на столько градусов под горизонтом. */
    private static final double DIVE_PITCH = 18;

    private final WeaponSpec.Airframe air;
    private final Autopilot.Turn climbTurn;
    private final Autopilot.Turn cruiseTurn;
    /** Высота крейсера: не спускаемся ниже, даже если рельеф понижается. */
    private double cruiseAlt;

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
        if ((c.phase() == FlightPhase.CRUISE || c.phase() == FlightPhase.CLIMB) && finalLeg && b.pitch() >= DIVE_PITCH && !outOfTurn) {
            c.setPhase(FlightPhase.TERMINAL);
        }

        if (c.phase() == FlightPhase.CRUISE) {
            c.setSpeed(c.speed() + (air.cruiseSpeed() - c.speed()) * 0.05);
            double terrain = c.reliefAhead(15, 30, 45);
            double desired = Math.max(Math.max(terrain + ABOVE_RELIEF, cruiseAlt), aim.y + ABOVE_TARGET);
            c.holdAltitude(desired, 0.12, 1.2, 0.15);
        } else if (c.phase() == FlightPhase.TERMINAL) {
            c.flight().arcPitch(b.pitch(), c.speed(), b.distance(), 4.0, 0.25);
            c.setSpeed(Math.min(air.diveSpeed(), c.speed() + 0.04));
        }
        Autopilot.steer(c, outOfTurn, n, turn);
    }
}
