package ua.zentix.airstrike.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import ua.zentix.airstrike.guidance.MissileAutopilot;
import ua.zentix.airstrike.nuclear.NuclearStrikes;
import ua.zentix.airstrike.nuclear.NuclearWarhead;
import ua.zentix.airstrike.util.Terrain;
import ua.zentix.airstrike.strike.WeaponSpec;
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
 * {@link WeaponSpec.Airframe#visibleLeg} блоков ракета летит в мире (полоса подлёта), когда у цели есть игрок, а не вне его: иначе она
 * появлялась бы только в пределах дистанции симуляции сервера у игрока. Закон полёта после старта — {@link MissileAutopilot}.
 */
public class CruiseMissileEntity extends StrikeProjectile {
    /** Паспорт: маршевая и в пике скорости, пределы поворота, высота бреющего полёта, старт, полоса подлёта. */
    private static final WeaponSpec.Airframe AIR = WeaponSpec.MISSILE.airframe();
    /** Маршевая скорость в 2.3.0 и раньше: ракеты, сохранённые в полёте без ключа {@code cruise_speed}, летели так. */
    private static final double LEGACY_CRUISE_SPEED = 11.5;

    private final MissileAutopilot autopilot = new MissileAutopilot(AIR);

    public CruiseMissileEntity(EntityType<? extends CruiseMissileEntity> type, Level level) {
        super(type, level);
    }

    @Override
    public WeaponType weapon() {
        return WeaponType.MISSILE;
    }

    /** Уже в воздухе (заход издалека, тесты): бреющий полёт, 12 блоков над рельефом и не ниже цели+12. */
    @Override
    public void launch(Vec3 pos, Target target, Vec3 targetPoint, @Nullable UUID owner) {
        double y = autopilot.airborneAltitude(targetPoint, surfaceY(level(), pos.x, pos.z));
        Vec3 start = new Vec3(pos.x, y, pos.z);
        super.launch(start, target, targetPoint, owner);
        speed = AIR.cruiseSpeed();
        altitude.reset(y);
        autopilot.approach(Math.sqrt(targetPoint.subtract(start).horizontalDistanceSqr()));
        setPhase(FlightPhase.CRUISE);
    }

    @Override
    protected void onRetarget() {
        autopilot.retarget(craft, tracker.point());
    }

    /** Маршрут пройден: горка — только если на последнем участке до цели на неё хватает места (у точек оператора он бывает коротким). */
    @Override
    protected void onRouteDone(Vec3 aim) {
        autopilot.approach(bearingTo(aim).horizontal());
    }

    @Override
    protected void serverTick(ServerLevel level) {
        Vec3 aim = updateTarget(level);
        if (launchTick(level)) return;
        Vec3 nav = navPoint(aim);
        // ядерная БЧ, воздушный подрыв: над целью, не долетая до земли (высоту подрыва задаёт сама БЧ)
        if (nuclear != null && nuclear.airBurst() && onFinalLeg() && armed() && bearingTo(aim).horizontal() <= speed * 2 + 12
                && (!isVirtual() || Terrain.ready(level, BlockPos.containing(aim)))) {
            impact(level, aim, null);
            return;
        }
        autopilot.fly(craft, aim, nav, onFinalLeg());
        advance(level, aim, AIR.reachPad());
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
        autopilot.setPopUp(!tag.contains("pop_up") || tag.getBoolean("pop_up"));
        // сохранена на большей маршевой (2.3.0 и раньше): летит уже с новой — на маршруте скорость сама падает
        // до маршевой, а на горке осталась бы старой. Остаток пути в блоках от скорости не зависит (запас хода)
        if (savedCruiseSpeed(tag) > AIR.cruiseSpeed()) speed = Math.min(speed, AIR.cruiseSpeed());
    }

    /** Маршевая скорость сохранения; без ключа {@code cruise_speed} — 2.3.0 и раньше. */
    @Override
    protected double savedCruiseSpeed(CompoundTag tag) {
        return tag.contains("cruise_speed") ? tag.getDouble("cruise_speed") : LEGACY_CRUISE_SPEED;
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putBoolean("pop_up", autopilot.popUp());
        tag.putDouble("cruise_speed", AIR.cruiseSpeed());
    }
}
