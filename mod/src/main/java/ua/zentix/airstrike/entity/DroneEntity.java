package ua.zentix.airstrike.entity;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import ua.zentix.airstrike.guidance.DroneAutopilot;
import ua.zentix.airstrike.strike.WeaponSpec;
import ua.zentix.airstrike.strike.WeaponType;
import ua.zentix.airstrike.target.Target;

import java.util.UUID;

/**
 * Дрон-камикадзе в духе Shahed-136: ≈150 км/ч (2.1 блока/тик). Старт с пусковой: твердотопливный ускоритель
 * под хвостом горит ~2 с и сбрасывается, дальше тянет толкающий винт — набор высоты, доворот на маршрут.
 * Крейсер над рельефом и не ниже цели+30; на последнем участке — пикирование по дуге, когда цель уходит на 18°
 * под горизонт и прямая до неё свободна от блоков; в пике разгон до 3 блоков/тик. Закон полёта после старта — {@link DroneAutopilot}.
 */
public class DroneEntity extends StrikeProjectile {
    /** Паспорт: скорости, пределы поворота на маршруте и наборе, высота крейсера над стартом и целью, старт. */
    private static final WeaponSpec.Airframe AIR = WeaponSpec.DRONE.airframe();

    private final DroneAutopilot autopilot = new DroneAutopilot(AIR);

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
        double y = autopilot.airborneAltitude(pos, targetPoint, surfaceY(level(), pos.x, pos.z));
        Vec3 start = new Vec3(pos.x, y, pos.z);
        super.launch(start, target, targetPoint, owner);
        speed = AIR.cruiseSpeed();
        altitude.reset(y);
        setPhase(FlightPhase.CRUISE);
    }

    @Override
    public void placeOnLauncher(Vec3 rail, float yaw, float elevation, int readyTicks, int hiddenTicks, Target target, Vec3 targetPoint,
                                @Nullable UUID owner) {
        super.placeOnLauncher(rail, yaw, elevation, readyTicks, hiddenTicks, target, targetPoint, owner);
        autopilot.fromLauncher(rail, targetPoint);
    }

    @Override
    protected void serverTick(ServerLevel level) {
        Vec3 aim = updateTarget(level);
        if (launchTick(level)) return;
        Vec3 nav = navPoint(aim, 40);
        autopilot.fly(craft, aim, nav, onFinalLeg());
        advance(level, aim, AIR.reachPad());
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        autopilot.setCruiseAlt(tag.getDouble("cruise_alt"));
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putDouble("cruise_alt", autopilot.cruiseAlt());
    }
}
