package ua.zentix.airstrike.entity;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import ua.zentix.airstrike.guidance.Ballistics;
import ua.zentix.airstrike.guidance.FlightController;
import ua.zentix.airstrike.strike.WeaponType;
import ua.zentix.airstrike.warhead.Warheads;

/**
 * Неуправляемый реактивный снаряд РСЗО (122 мм, как у «Града»). Стоит в трубе пакета, поджиг, сход — и дальше
 * только баллистика ({@link Ballistics}): траектория рассчитана при сходе по углу возвышения пакета и настоящему g,
 * так что на 500 блоков снаряд летит ~10 с и поднимается на сотню блоков. Двигатель горит первые
 * {@link #BURN_TICKS} (факел и плотный дым), дальше полёт по инерции; на нисходящей ветви — фаза атаки.
 * <p>
 * В точку падения снаряд приходит ровно на тике {@code N}. Если его сдвинули (возврат из полёта вне мира поднимает над рельефом), траектория
 * пересчитывается от текущего места на оставшееся время. Цель не отслеживается: куда навели при пуске, туда и упадёт.
 */
public class RocketEntity extends StrikeProjectile {
    /** Двигатель горит столько тиков после схода. */
    public static final int BURN_TICKS = 40;
    /** Поджиг в трубе до схода. */
    public static final int IGNITION_TICKS = 3;
    /** Ближе этого времени полёта не стреляет: круче задираем трубы (миномётная траектория). */
    private static final int MIN_FLIGHT = 50;

    /** Точка падения — куда навели при пуске. */
    @Nullable
    private Vec3 impactAt;
    /** Начало текущей траектории, скорость в нём и сколько тиков лететь от него. */
    @Nullable
    private Vec3 start;
    private Vec3 v0 = Vec3.ZERO;
    private int flightTicks;
    /** Тиков от начала текущей траектории. */
    private int n;
    /** Угол возвышения пакета, с которым снаряд стоял на пусковой. */
    private float elevation = 50;

    public RocketEntity(EntityType<? extends RocketEntity> type, Level level) {
        super(type, level);
    }

    @Override
    public WeaponType weapon() {
        return WeaponType.ROCKET;
    }

    @Override
    protected double noseLength() {
        return 1.45;
    }

    /** Средняя скорость по траектории — для оценок; время подлёта считается по самой траектории. */
    @Override
    public double cruiseSpeed() {
        return 4;
    }

    @Override
    protected int defaultLifetime() {
        return 2400;
    }

    @Override
    protected float maxHealth() {
        return 2;
    }

    @Override
    protected double clearance() {
        return 4;
    }

    @Override
    protected boolean acceptsRetarget() {
        return false;
    }

    /** Поставить в трубу: как у шахеда, плюс угол пакета — по нему строится траектория. */
    public void placeInTube(Vec3 rail, float yaw, float elevation, int readyTicks, int hiddenTicks,
                            ua.zentix.airstrike.target.Target target, Vec3 impact, @Nullable java.util.UUID owner) {
        placeOnLauncher(rail, yaw, elevation, readyTicks, hiddenTicks, target, impact, owner);
        this.elevation = elevation;
        this.impactAt = impact;
    }

    /**
     * Пуск без пусковой (консоль, нет места): снаряд уже на траектории, стартовавшей в {@code from}.
     * Взрыватель взведён — такой снаряд приходит издалека.
     */
    public void launchFrom(Vec3 from, ua.zentix.airstrike.target.Target target, Vec3 impact, @Nullable java.util.UUID owner) {
        launch(from, target, impact, owner);
        this.impactAt = impact;
        this.elevation = 50;
        solve(from, impact);
        setPhase(FlightPhase.CRUISE);
        face(v0);
    }

    @Override
    protected int launchTicksLeft() {
        return switch (flightPhase()) {
            case READY -> Math.max(0, readyTicks() - phaseAge()) + IGNITION_TICKS;
            case IGNITION -> Math.max(0, IGNITION_TICKS - phaseAge());
            default -> 0;
        };
    }

    @Override
    public int etaTicks() {
        if (start == null) {
            // ещё в трубе: время полёта от неё
            Vec3 aim = impactAt != null ? impactAt : aimPoint();
            return launchTicksLeft() + Ballistics.ticksFor(position(), aim, elevation, MIN_FLIGHT);
        }
        return Math.max(0, flightTicks - n);
    }

    @Override
    protected void serverTick(ServerLevel level) {
        if (impactAt == null) impactAt = tracker.point();
        FlightPhase ph = flightPhase();
        if (ph == FlightPhase.READY) {
            speed = 0;
            if (phaseAge() >= readyTicks()) setPhase(FlightPhase.IGNITION);
            return;
        }
        if (ph == FlightPhase.IGNITION) {
            speed = 0;
            if (phaseAge() >= IGNITION_TICKS) {
                solve(position(), impactAt);
                setPhase(FlightPhase.BOOST);
            }
            return;
        }
        if (start == null) solve(position(), impactAt);

        // сдвинули с траектории (вернулся в мир выше рельефа) — пересчитать от текущего места на оставшееся время
        if (position().distanceToSqr(at(n)) > 0.25) {
            int left = Math.max(8, flightTicks - n);
            restart(position(), impactAt, left);
        }

        if (ph == FlightPhase.BOOST && phaseAge() >= BURN_TICKS) setPhase(FlightPhase.CRUISE);
        Vec3 v = at(n + 1).subtract(at(n));
        if (flightPhase() == FlightPhase.CRUISE && v.y < 0) setPhase(FlightPhase.TERMINAL);

        face(v);
        speed = v.length();
        Vec3 before = position();
        if (!advance(level, impactAt, 1.5)) return;
        // вне мира у цели снаряд ждёт загрузки района — тогда он не сдвинулся и время траектории стоит
        if (position().distanceToSqr(before) > 1.0e-6) n++;
    }

    /** Нос по скорости. */
    private void face(Vec3 v) {
        float[] a = FlightController.anglesTo(Vec3.ZERO, v);
        flight.set(a[0], a[1]);
    }

    private Vec3 at(int k) {
        return Ballistics.at(start, v0, k);
    }

    /** Траектория из {@code from} в {@code to} с углом возвышения пакета; положе не выходит — круче, до 80°. */
    private void solve(Vec3 from, Vec3 to) {
        restart(from, to, Ballistics.ticksFor(from, to, elevation, MIN_FLIGHT));
    }

    private void restart(Vec3 from, Vec3 to, int ticks) {
        start = from;
        n = 0;
        flightTicks = Math.max(1, ticks);
        v0 = Ballistics.launchVelocity(from, to, flightTicks);
        lifetime = age + flightTicks + 200;
    }

    @Override
    protected void separate(ServerLevel level) {
        // ускорителя нет: двигатель — сам снаряд
    }

    @Override
    protected void impact(ServerLevel level, Vec3 point, @Nullable Entity hitEntity) {
        discard();
        Warheads.detonate(level, WeaponType.ROCKET, point, this, ownerId());
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        elevation = tag.contains("elevation") ? tag.getFloat("elevation") : 50;
        if (tag.contains("impact_x")) impactAt = new Vec3(tag.getDouble("impact_x"), tag.getDouble("impact_y"), tag.getDouble("impact_z"));
        if (tag.contains("start_x")) {
            start = new Vec3(tag.getDouble("start_x"), tag.getDouble("start_y"), tag.getDouble("start_z"));
            v0 = new Vec3(tag.getDouble("v0_x"), tag.getDouble("v0_y"), tag.getDouble("v0_z"));
            flightTicks = tag.getInt("flight_ticks");
            n = tag.getInt("n");
        }
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putFloat("elevation", elevation);
        if (impactAt != null) {
            tag.putDouble("impact_x", impactAt.x);
            tag.putDouble("impact_y", impactAt.y);
            tag.putDouble("impact_z", impactAt.z);
        }
        if (start != null) {
            tag.putDouble("start_x", start.x);
            tag.putDouble("start_y", start.y);
            tag.putDouble("start_z", start.z);
            tag.putDouble("v0_x", v0.x);
            tag.putDouble("v0_y", v0.y);
            tag.putDouble("v0_z", v0.z);
            tag.putInt("flight_ticks", flightTicks);
            tag.putInt("n", n);
        }
    }
}
