package ua.zentix.airstrike.entity;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import ua.zentix.airstrike.guidance.FlightController;
import ua.zentix.airstrike.strike.WeaponType;
import ua.zentix.airstrike.target.Target;

import java.util.UUID;

/**
 * Межконтинентальная баллистическая ракета — только участок разгона (DESIGN-nuke §7): вертикальный старт
 * из земли у запустившего, разгон 0.5 → 25 блоков/тик, доворот на курс цели и уход за потолок мира.
 * Дальше полёт — таймер запланированного удара; сама ракета ничего не взрывает.
 */
public class IcbmEntity extends StrikeProjectile {
    private static final double MAX_SPEED = 25;

    public IcbmEntity(EntityType<? extends IcbmEntity> type, Level level) {
        super(type, level);
    }

    @Override
    public WeaponType weapon() {
        return WeaponType.NUKE;
    }

    @Override
    protected double noseLength() {
        return 9;
    }

    @Override
    public double cruiseSpeed() {
        return MAX_SPEED;
    }

    @Override
    protected int defaultLifetime() {
        return 600;
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
        speed = 0.5;
        setPhase(FlightPhase.IGNITION);
    }

    @Override
    protected void serverTick(ServerLevel level) {
        // разгон: сначала медленно отрывается от стола, потом всё быстрее
        speed = Math.min(MAX_SPEED, speed + (age < 40 ? 0.08 : 0.35));
        if (age >= 40) setPhase(FlightPhase.BOOST);
        if (age > 60) {
            // доворот на курс цели: к пологим 45° над горизонтом
            flight.holdPitch(-45, 0.02, 0.6, 0.05);
        }
        Vec3 dir = flight.forward();
        Vec3 next = position().add(dir.scale(speed));
        // за пределами неба, по сроку или на краю тикающих чанков МБР больше не нужна: дальше летит «удар» (NuclearStrikes)
        if (next.y > level.getMaxBuildHeight() + 256 || age >= maxAge()) {
            discard();
            return;
        }
        if (leavesTickingChunks(level, next)) return;
        moveAlong(level, next, dir);
    }

    @Override
    protected void impact(ServerLevel level, Vec3 point, @Nullable Entity hitEntity) {
        discard();
    }

}
