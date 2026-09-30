package ua.zentix.airstrike.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import ua.zentix.airstrike.guidance.Ballistics;
import ua.zentix.airstrike.guidance.FlightController;
import ua.zentix.airstrike.guidance.Mission;
import ua.zentix.airstrike.strike.WeaponType;
import ua.zentix.airstrike.target.Target;
import ua.zentix.airstrike.util.Nbt;
import ua.zentix.airstrike.util.Terrain;

import java.util.UUID;

/**
 * Неуправляемый реактивный снаряд РСЗО (122 мм, как у «Града»). Стоит в трубе пакета, поджиг, сход — и дальше
 * только баллистика ({@link Ballistics}): траектория рассчитана при сходе по углу возвышения пакета и настоящему g,
 * так что на 500 блоков снаряд летит ~10 с и поднимается на сотню блоков. Двигатель горит первые
 * {@link #BURN_TICKS} (факел и плотный дым), дальше полёт по инерции; на нисходящей ветви — фаза атаки.
 * <p>
 * В точку падения снаряд приходит ровно на тике {@code N}. Если его сдвинули (возврат из полёта вне мира поднимает над рельефом), траектория
 * пересчитывается от текущего места на оставшееся время. Цель не отслеживается: куда навели при пуске, туда и упадёт
 * (у места с карты высота уточняется один раз — {@link #settleImpact}).
 * <p>
 * Район точки падения грузится с постановки в трубу, снаряд сходит сразу. Если он вне мира подходит к цели, а район
 * ещё не готов, конец траектории растягивается во времени ({@link #stretch}), а не замирает в воздухе у цели.
 */
public class RocketEntity extends StrikeProjectile {
    /** Двигатель горит столько тиков после схода. */
    public static final int BURN_TICKS = 40;
    /** Поджиг в трубе до схода. */
    public static final int IGNITION_TICKS = 3;
    /** Ближе этого времени полёта не стреляет: круче задираем трубы (миномётная траектория). */
    private static final int MIN_FLIGHT = 50;
    /** Вне мира, пока район цели не готов, замедляются последние столько тиков траектории (5 с, дольше загрузки района). */
    public static final double STRETCH_TICKS = 100;
    /** Темп полёта вне мира меняется не быстрее этого за тик. */
    private static final double RATE_SLEW = 0.05;
    /**
     * Ближе этого к неготовому району цели растянутый полёт не подходит: там снаряд вне мира ждал бы района стоя
     * (StrikeProjectile.advanceVirtual ждёт в 48 блоках плюс шаг).
     */
    private static final double HOLD_OFF = 64;
    /** Запас хода сверх дуги траектории, тиков на маршевой скорости: снаряд, не попавший в точку, летит дальше вниз. */
    private static final int OVERSHOOT_TICKS = 200;

    /** Точка падения — куда навели при пуске. */
    @Nullable
    private Vec3 impactAt;
    /** Начало текущей траектории, скорость в нём и сколько тиков лететь от него. */
    @Nullable
    private Vec3 start;
    private Vec3 v0 = Vec3.ZERO;
    private int flightTicks;
    /** Время от начала текущей траектории, тиков (дробное: полёт вне мира растягивается). */
    private double t;
    /** Темп времени траектории: 1 — как у мира; меньше — полёт вне мира растянут до загрузки района цели. */
    private double rate = 1;
    /** Сколько времени мира набежало сверх траектории (растяжение); целые тики уходят в ожидание района. */
    private double stretched;
    /** Время траектории, после которого снаряд ближе {@link #HOLD_OFF} к цели: растяжение к нему только подходит. */
    private double holdAt;
    /** Угол возвышения пакета, с которым снаряд стоял на пусковой. */
    private float elevation = LauncherEntity.elevation(WeaponType.ROCKET);

    public RocketEntity(EntityType<? extends RocketEntity> type, Level level) {
        super(type, level);
    }

    @Override
    public WeaponType weapon() {
        return WeaponType.ROCKET;
    }

    /** Средняя скорость по траектории — для оценок; время подлёта считается по самой траектории. */

    @Override
    protected boolean climbs() {
        return false;
    }

    @Override
    protected boolean acceptsRetarget() {
        return false;
    }

    /** Поставить в трубу: как у шахеда, плюс угол пакета — по нему строится траектория. */
    public void placeInTube(Vec3 rail, float yaw, float elevation, int readyTicks, int hiddenTicks,
                            Target target, Vec3 impact, @Nullable UUID owner) {
        placeOnLauncher(rail, yaw, elevation, readyTicks, hiddenTicks, target, impact, owner);
        this.elevation = elevation;
        this.impactAt = impact;
    }

    /**
     * Пуск без пусковой (консоль, нет места): снаряд уже на траектории, стартовавшей в {@code from}.
     * Взрыватель взведён — такой снаряд приходит издалека.
     */
    public void launchFrom(Vec3 from, Target target, Vec3 impact, @Nullable UUID owner) {
        launch(from, target, impact, owner);
        this.impactAt = impact;
        this.elevation = LauncherEntity.elevation(WeaponType.ROCKET);
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
        return (int) Math.ceil(Math.max(0, flightTicks - t));
    }

    @Override
    protected void serverTick(ServerLevel level) {
        if (impactAt == null) impactAt = tracker.point();
        settleImpact(level);
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
        // вне мира путь кончился на поверхности: ни траектории, ни растяжения — ждать там загрузки и попасть
        if (isGrounded()) {
            advance(level, impactAt, airframe().reachPad());
            return;
        }
        if (start == null) solve(position(), impactAt);

        // сдвинули с траектории (вернулся в мир выше рельефа) — пересчитать от текущего места на оставшееся время
        if (position().distanceToSqr(at(t)) > 0.25) {
            restart(position(), impactAt, ticksLeft());
        }

        if (ph == FlightPhase.BOOST && phaseAge() >= BURN_TICKS) setPhase(FlightPhase.CRUISE);
        // в мире снаряд виден — время только как у мира
        double step = isVirtual() ? stretch(level) : (rate = 1);
        if (isRemoved()) return;
        Vec3 v = at(t + step).subtract(at(t));
        if (flightPhase() == FlightPhase.CRUISE && v.y < 0) setPhase(FlightPhase.TERMINAL);

        // вне мира растянутый почти до остановки у цели: стоять, пока темп не вернётся
        if (v.lengthSqr() < 1.0e-12) return;
        face(v);
        speed = v.length();
        Vec3 before = position();
        if (!advance(level, impactAt, airframe().reachPad())) return;
        // вне мира у самой цели снаряд ждёт загрузки района — тогда он не сдвинулся и время траектории стоит
        if (position().distanceToSqr(before) > 1.0e-6) t += step;
    }

    /**
     * Место с карты ({@link Target.Ground}): высота точки падения у пуска — оценка, пока чанк там не готов (оценка
     * генератора у города из сохранения — улица под крышей). Район точки падения грузится с постановки в трубу; как
     * только её чанк готов, точка встаёт на его поверхность (разброс остаётся свой) — один раз, как высота цели у расчёта
     * огня: цель становится этой точкой и дальше, как у всей РСЗО, не отслеживается. Снаряд уже летит — траектория
     * пересчитывается от текущего места на оставшееся время.
     */
    private void settleImpact(ServerLevel level) {
        if (!(tracker.target() instanceof Target.Ground) || !Terrain.ready(level, BlockPos.containing(impactAt))) return;
        Vec3 surface = new Target.Ground(impactAt).surface(level);
        boolean moved = !surface.equals(impactAt);
        impactAt = surface;
        // цель — сама точка падения с разбросом, как у пуска: у неё метка, сирена, камера и район цели
        settleAim(impactAt);
        if (moved && start != null) restart(position(), impactAt, ticksLeft());
    }

    /** Темп времени траектории: 1 — как у мира, меньше — полёт вне мира растянут (для стенда). */
    public double timeRate() {
        return rate;
    }

    /**
     * Темп полёта вне мира. Район точки падения не готов — последние {@link #STRETCH_TICKS} тиков траектории до
     * {@link #HOLD_OFF} блоков от цели замедляются плавно (темп = оставшееся до них время / STRETCH_TICKS, меняется
     * не быстрее {@link #RATE_SLEW} за тик): снаряд подходит к этой черте всё медленнее и не замирает; район готов —
     * темп так же плавно возвращается к 1. Вне мира
     * снаряда никто не видит, только слышит путь издалека. Растянутое время — ожидание района: снаряд идёт медленнее и
     * хода на него не тратит, предел ожидания общий с ожиданием у цели.
     */
    private double stretch(ServerLevel level) {
        double want = aimAreaReady(level, impactAt) ? 1 : Math.min(1, Math.max(0, holdAt - t) / STRETCH_TICKS);
        rate += Math.max(-RATE_SLEW, Math.min(RATE_SLEW, want - rate));
        stretched += 1 - rate;
        while (stretched >= 1 && !isRemoved()) {
            stretched -= 1;
            waitForAimArea(impactAt);
        }
        return rate;
    }

    /**
     * Район цели грузится с постановки в трубу: полёт по дуге короткий (500 блоков — ~10 с), и район, взятый на
     * подлёте, не успевал загрузиться. Снаряд сходит сразу: полёт длиннее загрузки района (замер: 0,7–4,6 с на
     * свежий район), а короткий полёт вне мира растягивается ({@link #stretch}).
     */
    @Override
    protected double preloadDistance() {
        return Double.MAX_VALUE;
    }

    /**
     * Путь кончается там, куда рассчитана баллистика ({@code impactAt}). Цель ракета не обновляет, и точка цели с ней
     * совпадает; переопределение — страховка на случай, если разойдутся.
     */
    @Override
    @Nullable
    protected Vec3 pathEnd() {
        return impactAt != null ? impactAt : super.pathEnd();
    }

    /** Нос по скорости. */
    private void face(Vec3 v) {
        float[] a = FlightController.anglesTo(Vec3.ZERO, v);
        flight.set(a[0], a[1]);
    }

    private Vec3 at(double k) {
        return Ballistics.at(start, v0, k);
    }

    /** Последний тик траектории, на котором снаряд ещё дальше {@link #HOLD_OFF} от точки падения (0 — уже ближе). */
    private double holdPoint(Vec3 impact) {
        for (int k = flightTicks; k > 0; k--) {
            if (at(k).distanceTo(impact) > HOLD_OFF) return k;
        }
        return 0;
    }

    /** Траектория из {@code from} в {@code to} с углом возвышения пакета; положе не выходит — круче, до 80°. */
    private void solve(Vec3 from, Vec3 to) {
        restart(from, to, Ballistics.ticksFor(from, to, elevation, MIN_FLIGHT));
    }

    /** Длина дуги текущей траектории от её начала до точки падения, блоков: столько снаряд пролетит по тикам. */
    private double arcLength() {
        double arc = 0;
        for (int k = 0; k < flightTicks; k++) arc += at(k + 1).subtract(at(k)).length();
        return arc;
    }

    /** Сколько тиков осталось лететь по текущей траектории — для пересчёта с текущего места (не меньше 8). */
    private int ticksLeft() {
        return Math.max(8, (int) Math.ceil(flightTicks - t));
    }

    private void restart(Vec3 from, Vec3 to, int ticks) {
        start = from;
        t = 0;
        flightTicks = Math.max(1, ticks);
        v0 = Ballistics.launchVelocity(from, to, flightTicks);
        setMission(Mission.of(arcLength() + OVERSHOOT_TICKS * cruiseSpeed()));
        holdAt = holdPoint(to);
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        elevation = tag.contains("elevation") ? tag.getFloat("elevation") : LauncherEntity.elevation(WeaponType.ROCKET);
        Vec3 impact = Nbt.getVec(tag, "impact");
        if (impact != null) impactAt = impact;
        if (tag.contains("start_x")) {
            start = Nbt.getVec(tag, "start");
            v0 = Nbt.getVec(tag, "v0");
            flightTicks = tag.getInt("flight_ticks");
            t = tag.contains("t") ? tag.getDouble("t") : tag.getInt("n");
            if (impactAt != null) holdAt = holdPoint(impactAt);
        }
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putFloat("elevation", elevation);
        if (impactAt != null) Nbt.putVec(tag, "impact", impactAt);
        if (start != null) {
            Nbt.putVec(tag, "start", start);
            Nbt.putVec(tag, "v0", v0);
            tag.putInt("flight_ticks", flightTicks);
            tag.putDouble("t", t);
        }
    }
}
