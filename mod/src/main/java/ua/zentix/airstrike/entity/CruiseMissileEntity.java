package ua.zentix.airstrike.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import ua.zentix.airstrike.nuclear.NuclearStrikes;
import ua.zentix.airstrike.nuclear.NuclearWarhead;
import ua.zentix.airstrike.nuclear.world.Terrain;
import ua.zentix.airstrike.strike.WeaponType;
import ua.zentix.airstrike.target.Target;

import java.util.UUID;

/**
 * Крылатая ракета: 230 м/с (11.5 блока/тик). Старт из наклонного контейнера: стартовый ускоритель выносит её
 * вверх (~2 с), отделяется, запускается турбореактивный двигатель — разгон до маршевой, снижение на бреющий
 * полёт в 12 блоках над рельефом и маршрут в обход. На последнем участке в 160 блоках от цели — горка и
 * пикирование по дуге с разгоном до 12.5 блока/тик. С ядерной БЧ при воздушном подрыве срабатывает над целью.
 */
public class CruiseMissileEntity extends StrikeProjectile {
    public static final double CRUISE_SPEED = 11.5;
    /** Горка перед пикированием начинается в стольких блоках от цели. */
    public static final double TERMINAL_RANGE = 160;
    /** Предельная скорость в пикировании. */
    private static final double DIVE_SPEED = 12.5;
    /** Горка только при заходе хотя бы с такого расстояния: ближе ракете не хватит места набрать высоту. */
    private static final double POP_UP_MIN_RANGE = 185;

    private static final LaunchProfile LAUNCH = new LaunchProfile(6, 40, 0.15, 8, -14);

    /** Горка перед пикированием — только при длинном заходе. */
    private boolean popUp = true;

    public CruiseMissileEntity(EntityType<? extends CruiseMissileEntity> type, Level level) {
        super(type, level);
    }

    @Override
    public WeaponType weapon() {
        return WeaponType.MISSILE;
    }

    @Override
    protected double noseLength() {
        return 2.96;
    }

    @Override
    public double cruiseSpeed() {
        return CRUISE_SPEED;
    }

    @Override
    protected int defaultLifetime() {
        return 700;
    }

    @Override
    protected float maxHealth() {
        return 8;
    }

    @Override
    @Nullable
    protected LaunchProfile launchProfile() {
        return LAUNCH;
    }

    /** Уже в воздухе (заход издалека, тесты): бреющий полёт, 12 блоков над рельефом и не ниже цели+12. */
    @Override
    public void launch(Vec3 pos, Target target, Vec3 targetPoint, @Nullable UUID owner) {
        double y = Math.max(targetPoint.y + 12, surfaceY(level(), pos.x, pos.z) + 12);
        Vec3 start = new Vec3(pos.x, y, pos.z);
        super.launch(start, target, targetPoint, owner);
        speed = CRUISE_SPEED;
        altFilter = y;
        double dx = targetPoint.x - start.x, dz = targetPoint.z - start.z;
        popUp = dx * dx + dz * dz >= POP_UP_MIN_RANGE * POP_UP_MIN_RANGE;
        setPhase(FlightPhase.CRUISE);
    }

    @Override
    protected void onRetarget() {
        Bearing b = bearingTo(tracker.point());
        popUp = b.horizontal() >= POP_UP_MIN_RANGE;
        if (flightPhase() == FlightPhase.TERMINAL || flightPhase() == FlightPhase.POP_UP) setPhase(FlightPhase.CRUISE);
    }

    @Override
    protected void serverTick(ServerLevel level) {
        Vec3 aim = updateTarget(level);
        if (launchTick(level)) return;

        Vec3 nav = navPoint(aim, 120);
        Bearing b = bearingTo(aim);
        Bearing n = bearingTo(nav);

        // ядерная БЧ, воздушный подрыв: над целью, не долетая до земли (высоту подрыва задаёт сама БЧ)
        if (nuclear != null && nuclear.airBurst() && onFinalLeg() && armed() && b.horizontal() <= speed * 2 + 12
                && (!isVirtual() || Terrain.ready(level, BlockPos.containing(aim)))) {
            impact(level, aim, null);
            return;
        }

        FlightPhase ph = flightPhase();
        if (ph == FlightPhase.CLIMB) {
            // турбина набирает тягу; ракета переходит с подъёма на снижение к бреющему полёту
            speed = Math.min(CRUISE_SPEED, speed + 0.09);
            double terrain = terrainAhead(level, 30, 60, 90);
            holdAltitude(Math.max(terrain + 25, aim.y + 12), 0.25, 4, 0.6);
            if (speed >= CRUISE_SPEED - 0.01 && phaseAge() > 40) setPhase(FlightPhase.CRUISE);
        }
        if (flightPhase() == FlightPhase.CRUISE && onFinalLeg() && b.horizontal() <= TERMINAL_RANGE) {
            setPhase(popUp ? FlightPhase.POP_UP : FlightPhase.TERMINAL);
        }
        if (flightPhase() == FlightPhase.POP_UP && (b.pitch() >= 24 || getY() >= aim.y + 32)) setPhase(FlightPhase.TERMINAL);

        switch (flightPhase()) {
            case CRUISE -> {
                speed = Math.min(CRUISE_SPEED, speed + 0.09);
                double terrain = terrainAhead(level, 30, 60, 90);
                holdAltitude(Math.max(terrain + 12, aim.y + 12), 0.30, 8, 1.8);
            }
            case POP_UP -> flight.holdPitch(-20, 0.30, 8, 1.8);
            case TERMINAL -> {
                flight.arcPitch(b.pitch(), speed, b.distance(), 16, 3.5);
                speed = Math.min(DIVE_SPEED, speed + 0.1);
            }
            default -> {}
        }
        if (n.horizontal() > 8) {
            if (ph == FlightPhase.CLIMB) flight.steerYaw(n.yaw(), 0.10, 2.0, 0.15);
            else flight.steerYaw(n.yaw(), 0.15, 3.0, 0.3);
        }

        advance(level, aim, 6.5);
    }

    @Override
    protected void impact(ServerLevel level, Vec3 point, @Nullable Entity hitEntity) {
        if (nuclear == null) {
            super.impact(level, point, hitEntity);
            return;
        }
        discard();
        NuclearWarhead.detonate(level, NuclearStrikes.ground(level, point), nuclear.yieldKt(), nuclear.airBurst(), ownerId());
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        popUp = !tag.contains("pop_up") || tag.getBoolean("pop_up");
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putBoolean("pop_up", popUp);
    }
}
