package ua.zentix.incoming.entity;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.guidance.FlightController;
import ua.zentix.incoming.strike.WeaponSpec;
import ua.zentix.incoming.strike.WeaponType;
import ua.zentix.incoming.target.Target;

import java.util.UUID;

/**
 * Межконтинентальная баллистическая ракета — только участок разгона (DESIGN-nuke §7): вертикальный старт
 * из земли у запустившего, разгон первой ступенью по тяге и массе из паспорта ({@link WeaponSpec.Ascent}: на отрыве
 * ~1,5 g, растёт по мере выгорания топлива), строго отвесно из шахты, потом лёгкий наклон к курсу цели и уход за потолок
 * мира — через ~8,5 с на y 64. Дальше полёт — таймер запланированного удара; сама ракета ничего не взрывает.
 */
public class IcbmEntity extends StrikeProjectile {
    /** Паспорт МБР: предельная скорость разгона, длина, запас хода. */
    private static final WeaponSpec.Airframe AIR = WeaponSpec.NUKE.airframe();
    /** Разгон первой ступени (паспорт). */
    private static final WeaponSpec.Ascent ASCENT = WeaponSpec.NUKE.ascent();
    private static final Vec3 UP = new Vec3(0, 1, 0);

    public IcbmEntity(EntityType<? extends IcbmEntity> type, Level level) {
        super(type, level);
    }

    @Override
    public WeaponType weapon() {
        return WeaponType.NUKE;
    }

    @Override
    protected boolean fliesVirtually() {
        return false;
    }

    @Override
    protected boolean acceptsRetarget() {
        return false;
    }

    /** МБР всегда ядерная, хотя подрывает не она, а запланированный удар ({@code NuclearStrikes}). */
    @Override
    public boolean isNuclear() {
        return true;
    }

    /** Поставить на стартовую площадку носом вверх. */
    public void prepare(Vec3 pad, Vec3 target, @Nullable UUID owner) {
        super.launch(pad.add(0, 9, 0), new Target.Point(target), target, owner);
        float[] a = FlightController.anglesTo(pad, target);
        flight.set(a[0], -89);
        setYRot(a[0]);
        setXRot(-89);
        // горячий старт из шахты: со стола — с нуля
        speed = 0;
        setPhase(FlightPhase.IGNITION);
    }

    @Override
    protected void serverTick(ServerLevel level) {
        speed = Math.min(AIR.cruiseSpeed(), ASCENT.step(speed, age));
        if (age >= 40) setPhase(FlightPhase.BOOST);
        // до наклона — строго по оси шахты (тангаж контроллера не круче 89°), потом нос плавно к курсу цели
        if (age > ASCENT.pitchOverTick()) flight.holdPitch(-ASCENT.climbPitch(), 0.02, 0.6, 0.05);
        Vec3 dir = age > ASCENT.pitchOverTick() ? flight.forward() : UP;
        Vec3 next = position().add(dir.scale(speed));
        // за пределами неба, по запасу хода или на краю тикающих чанков МБР больше не нужна: дальше летит «удар» (NuclearStrikes)
        if (next.y > level.getMaxBuildHeight() + ASCENT.ceiling() || exhausted()) {
            discard();
            return;
        }
        if (leavesTickingChunks(level, next)) return;
        // чанк впереди — по курсу над землёй: ракета идёт почти отвесно, и чанк «на 16 блоков вперёд по носу» был бы
        // в паре блоков по горизонтали — она входила бы в новый чанк раньше, чем у него начнут тикать сущности
        moveAlong(level, next, Vec3.directionFromRotation(0, flight.yaw()));
    }

    @Override
    protected void impact(ServerLevel level, Vec3 point, @Nullable Entity hitEntity) {
        discard();
    }

}
