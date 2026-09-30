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
import ua.zentix.airstrike.util.Terrain;
import ua.zentix.airstrike.strike.WeaponType;
import ua.zentix.airstrike.target.Target;

import java.util.UUID;

/**
 * Крылатая ракета: 80 м/с (4 блока/тик, ≈290 км/ч). Старт из наклонного контейнера: стартовый ускоритель выносит её
 * вверх (~2 с), отделяется, запускается турбореактивный двигатель — разгон до маршевой, снижение на бреющий
 * полёт в 12 блоках над рельефом и маршрут в обход. На последнем участке в 160 блоках от цели — горка и
 * пикирование по дуге с разгоном до 5 блоков/тик. С ядерной БЧ при воздушном подрыве срабатывает над целью.
 *
 * <p>Скорость медленнее настоящей (у Х-101 и «Калибра» ~250 м/с) ради того, чтобы подлёт было видно: настоящую ракету
 * очевидец видит за километры, а в игре она появляется в пределах прорисовки (12 чанков — 192 блока). На 11.5 блока/тик
 * она пролетала их меньше чем за секунду, а при 12 TPS сервера — рывками по 11 блоков; на 4 блоках/тик подлёт от края
 * прорисовки с горкой и пикированием длится ~2.5 с (при 12 TPS ~4 с), пролёт поперёк поля зрения — ~5 с. Время
 * полёта до удара задаёт настройка {@code missile_flight_time}: путь маршрута — скорость × время. Последние
 * {@link #VISIBLE_LEG} блоков ракета летит в мире (полоса подлёта), когда у цели есть игрок, а не вне его: иначе она
 * появлялась бы только в пределах дистанции симуляции сервера у игрока.
 */
public class CruiseMissileEntity extends StrikeProjectile {
    public static final double CRUISE_SPEED = 4.0;
    /**
     * Последние столько блоков до цели ракета летит в мире ({@link ua.zentix.airstrike.strike.FlightTickets#approach}):
     * больше дальности прорисовки 12 чанков, чтобы подлёт с её края был виден, даже когда дистанция симуляции сервера
     * меньше (8 чанков — сущности тикают лишь в ~128 блоках от игрока). Только когда у цели есть кому смотреть, районы
     * полос общие для залпа и их число в мире ограничено ({@link ua.zentix.airstrike.strike.FlightTickets#holdApproach}).
     */
    public static final double VISIBLE_LEG = 256;
    /** Маршевая скорость в 2.3.0 и раньше: ракеты, сохранённые в полёте без ключа {@code cruise_speed}, летели так. */
    private static final double LEGACY_CRUISE_SPEED = 11.5;
    /** Горка перед пикированием начинается в стольких блоках от цели. */
    public static final double TERMINAL_RANGE = 160;
    /** Предельная скорость в пикировании. */
    private static final double DIVE_SPEED = 5.0;
    /** Горка только при заходе хотя бы с такого расстояния: ближе ракете не хватит места набрать высоту. */
    private static final double POP_UP_MIN_RANGE = 185;
    /** Предельная скорость разворота по курсу, °/тик: на маршруте и атаке, на наборе высоты. */
    private static final double TURN_RATE = 3.0, CLIMB_TURN_RATE = 2.0;
    /** Ближе этого (по горизонтали) атаку из-за круга разворота не отменяем. */
    private static final double REATTACK_MIN = 64;

    /** Ускоритель выносит ракету до ~3.4 блока/тик — ниже маршевой: дальше разгоняет турбина, без рывка вниз. */
    private static final LaunchProfile LAUNCH = new LaunchProfile(6, 40, 0.09, 8, -14);

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
    protected double visibleLeg() {
        return VISIBLE_LEG;
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
        // цель внутри круга разворота (сместилась вбок на атаке, перенацеливание, игрок телепортировался): атака
        // отменяется, ракета уходит прямо, пока цель не выйдет из круга, и заходит снова — как ракета на промахе
        // у самой цели не отменяем: небольшой промах добирает неконтактный взрыватель, а пролетев, ракета зайдёт снова
        boolean outOfTurn = n.horizontal() > REATTACK_MIN && insideTurn(nav, ph == FlightPhase.CLIMB ? CLIMB_TURN_RATE : TURN_RATE);
        if (outOfTurn && (flightPhase() == FlightPhase.TERMINAL || flightPhase() == FlightPhase.POP_UP)) setPhase(FlightPhase.CRUISE);
        if (flightPhase() == FlightPhase.CRUISE && onFinalLeg() && b.horizontal() <= TERMINAL_RANGE && !outOfTurn) {
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
        if (outOfTurn) {
            flight.settleYaw(ph == FlightPhase.CLIMB ? 0.15 : 0.3);
        } else if (n.horizontal() > 8) {
            if (ph == FlightPhase.CLIMB) flight.steerYaw(n.yaw(), 0.10, CLIMB_TURN_RATE, 0.15);
            else flight.steerYaw(n.yaw(), 0.15, TURN_RATE, 0.3);
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
        // срок жизни посчитан по плану полёта на той скорости, с которой ракету сохранили: на другой маршевой остаток
        // пути занимает другое время — остаток срока растягивается так же, иначе ракета пропала бы посреди полёта
        double saved = tag.contains("cruise_speed") ? tag.getDouble("cruise_speed") : LEGACY_CRUISE_SPEED;
        if (saved > CRUISE_SPEED) {
            if (lifetime > 0) lifetime = age + (int) Math.ceil(Math.max(0, lifetime - age) * saved / CRUISE_SPEED);
            // и летит уже с новой: на маршруте скорость сама падает до маршевой, а на горке осталась бы старой
            speed = Math.min(speed, CRUISE_SPEED);
        }
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putBoolean("pop_up", popUp);
        tag.putDouble("cruise_speed", CRUISE_SPEED);
    }
}
