package ua.zentix.airstrike.entity;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import ua.zentix.airstrike.strike.WeaponSpec;
import ua.zentix.airstrike.strike.WeaponType;
import ua.zentix.airstrike.target.Target;

import java.util.UUID;

/**
 * Дрон-камикадзе в духе Shahed-136: ≈150 км/ч (2.1 блока/тик). Старт с пусковой: твердотопливный ускоритель
 * под хвостом горит ~2 с и сбрасывается, дальше тянет толкающий винт — набор высоты, доворот на маршрут.
 * Крейсер над рельефом и не ниже цели+30; на последнем участке — пикирование по дуге, когда цель уходит на 18°
 * под горизонт; в пике разгон до 3 блоков/тик.
 */
public class DroneEntity extends StrikeProjectile {
    /** Паспорт: скорости, пределы поворота на маршруте и наборе, высота крейсера над стартом и целью, старт. */
    private static final WeaponSpec.Airframe AIR = WeaponSpec.DRONE.airframe();
    /** Ближе этого (по горизонтали) пике из-за круга разворота не отменяем: малый промах добирает взрыватель. */
    private static final double REATTACK_MIN = 16;

    /** Высота крейсера: не спускаемся ниже, даже если рельеф понижается. */
    private double cruiseAlt;

    public DroneEntity(EntityType<? extends DroneEntity> type, Level level) {
        super(type, level);
    }

    @Override
    public WeaponType weapon() {
        return WeaponType.DRONE;
    }

    /** Уже в воздухе (заход издалека, тесты): высота полёта = max(старт, цель+30, рельеф+20). */
    @Override
    public void launch(Vec3 pos, Target target, Vec3 targetPoint, @Nullable UUID owner) {
        double y = Math.max(pos.y, targetPoint.y + 30);
        y = Math.max(y, surfaceY(level(), pos.x, pos.z) + 20);
        Vec3 start = new Vec3(pos.x, y, pos.z);
        super.launch(start, target, targetPoint, owner);
        speed = AIR.cruiseSpeed();
        cruiseAlt = y;
        altFilter = y;
        setPhase(FlightPhase.CRUISE);
    }

    @Override
    public void placeOnLauncher(Vec3 rail, float yaw, float elevation, int readyTicks, int hiddenTicks, Target target, Vec3 targetPoint,
                                @Nullable UUID owner) {
        super.placeOnLauncher(rail, yaw, elevation, readyTicks, hiddenTicks, target, targetPoint, owner);
        cruiseAlt = Math.max(rail.y, targetPoint.y) + AIR.cruiseHeight();
    }

    @Override
    protected void serverTick(ServerLevel level) {
        Vec3 aim = updateTarget(level);
        if (launchTick(level)) return;

        Vec3 nav = navPoint(aim, 40);
        Bearing b = bearingTo(aim);
        Bearing n = bearingTo(nav);
        FlightPhase ph = flightPhase();

        if (ph == FlightPhase.CLIMB) {
            // винт на полных оборотах, скорость после ускорителя спадает к крейсерской
            speed += (AIR.cruiseSpeed() - speed) * 0.04;
            double terrain = terrainAhead(level, 15, 30, 45);
            holdAltitude(Math.max(cruiseAlt, terrain + 18), 0.10, 1.0, 0.12);
            if (phaseAge() > 60 && Math.abs(cruiseAlt - getY()) < 6) setPhase(FlightPhase.CRUISE);
        }
        // цель внутри круга разворота (промах в пике, цель ушла вбок, точка в воздухе, где цель пропала): до неё не
        // довернуть, и шахед кружил бы вокруг неё до конца срока жизни. Пике отменяется: шахед уходит прямо, набирая
        // высоту, пока цель не выйдет из круга, и заходит снова — как крылатая ракета
        boolean outOfTurn = n.horizontal() > REATTACK_MIN && insideTurn(nav, ph == FlightPhase.CLIMB ? AIR.climbTurnRate() : AIR.turnRate());
        if (outOfTurn && flightPhase() == FlightPhase.TERMINAL) setPhase(FlightPhase.CRUISE);
        // пикирование — как только цель под нужным углом, даже если высота ещё набирается (цель рядом, перенацеливание)
        if ((flightPhase() == FlightPhase.CRUISE || flightPhase() == FlightPhase.CLIMB) && onFinalLeg() && b.pitch() >= 18 && !outOfTurn) {
            setPhase(FlightPhase.TERMINAL);
        }

        if (flightPhase() == FlightPhase.CRUISE) {
            speed += (AIR.cruiseSpeed() - speed) * 0.05;
            double terrain = terrainAhead(level, 15, 30, 45);
            double desired = Math.max(Math.max(terrain + 18, cruiseAlt), aim.y + 30);
            holdAltitude(desired, 0.12, 1.2, 0.15);
        } else if (flightPhase() == FlightPhase.TERMINAL) {
            flight.arcPitch(b.pitch(), speed, b.distance(), 4.0, 0.25);
            speed = Math.min(AIR.diveSpeed(), speed + 0.04);
        }
        // над самой целью курс не трогаем; на старте разворот мягче (скорость ещё мала)
        if (outOfTurn) {
            flight.settleYaw(ph == FlightPhase.CLIMB ? 0.12 : 0.3);
        } else if (n.horizontal() > 8) {
            if (ph == FlightPhase.CLIMB) flight.steerYaw(n.yaw(), 0.08, AIR.climbTurnRate(), 0.12);
            else flight.steerYaw(n.yaw(), 0.15, AIR.turnRate(), 0.3);
        }

        advance(level, aim, AIR.reachPad());
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        cruiseAlt = tag.getDouble("cruise_alt");
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putDouble("cruise_alt", cruiseAlt);
    }
}
