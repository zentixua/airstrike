package ua.zentix.airstrike.client.sound;

import net.minecraft.world.phys.Vec3;
import ua.zentix.airstrike.entity.BunkerBusterEntity;
import ua.zentix.airstrike.entity.StrikeProjectile;
import ua.zentix.airstrike.net.S2C;
import ua.zentix.airstrike.strike.WeaponType;

import java.util.UUID;

/**
 * История полёта снаряда на клиенте (по тикам): из неё звук берёт «запаздывающее» положение и скорость. Скорость —
 * та, что сообщил сервер ({@link StrikeProjectile#velocity()}), а не разность положений по тикам клиента: пакеты
 * одного тика сервера доходят то в один тик клиента, то в соседний, и такая разность то падала до нуля, то удваивалась.
 * Пишется по сущности, пока она есть у клиента, а без неё — по пакетам сервера ({@link S2C.Heard}, раз в
 * {@link S2C.Heard#PERIOD} тика; промежуточные тики — по прямой между ними). Живёт дольше сущности: после взрыва
 * дальние слушатели ещё какое-то время слышат мотор.
 */
final class SourceTrack implements Acoustics.Path {
    /** Тиков истории: звук с самого дальнего слышимого снаряда (ступень МБР, 1580 блоков) идёт до уха ~92 тика. */
    private static final int CAPACITY = 128;
    /** Разрыв в данных длиннее — это уже другой полёт в поле слуха: история начинается заново. */
    private static final int MAX_GAP = 8;

    final UUID id;
    final WeaponType weapon;
    /** B-2 (у его бомбы то же оружие, звук другой). */
    final boolean bomber;
    private final double[] xs = new double[CAPACITY], ys = new double[CAPACITY], zs = new double[CAPACITY];
    private final double[] vxs = new double[CAPACITY], vys = new double[CAPACITY], vzs = new double[CAPACITY];
    private final int[] phases = new int[CAPACITY], phaseAges = new int[CAPACITY];
    private final double[] axs = new double[CAPACITY], ays = new double[CAPACITY], azs = new double[CAPACITY];
    private final float[] yaws = new float[CAPACITY], pitches = new float[CAPACITY];
    private long first = -1, last = -1;
    /** Точка пути перед последней (от неё строится прямая до последней). */
    private long previous = -1;
    private long death = Long.MAX_VALUE;
    /**
     * На сколько тиков после последней записи звук ещё можно тянуть с неё: по сущности запись каждый тик, так что 1;
     * по пакетам — до следующего пакета (и тик на неровную доставку). Дальше данных нет (взорвался или ушёл из слуха) —
     * слой молчит.
     */
    private int slack = 1;
    /** Последняя запись — по сущности. */
    private boolean fromEntity;
    /** Открыт ли путь звука к уху (1) или за холмом/домом (0), сглажено; считается раз в тик на все слои. */
    private float open = 1;
    private long openTick = -1;
    /** Бомба бурит (для звука бурения). */
    boolean drilling;

    SourceTrack(UUID id, WeaponType weapon, boolean bomber) {
        this.id = id;
        this.weapon = weapon;
        this.bomber = bomber;
    }

    /** Запись по сущности у клиента. */
    void record(long tick, StrikeProjectile p) {
        append(tick, new Sample(p.position(), p.velocity(), p.getYRot(), p.getXRot(), p.flightPhase().ordinal(), p.phaseAge(), p.aimPoint()));
        fromEntity = true;
        slack = 1;
        drilling = p instanceof BunkerBusterEntity b && b.isDrilling();
    }

    /**
     * Запись по пакету сервера. Два пакета в один тик (сеть прислала пачкой) — верен последний; на тик, записанный
     * по сущности, пакет не нужен.
     */
    void record(long tick, S2C.HeardFlight f) {
        if (tick < last || tick == last && fromEntity) return;
        append(tick, new Sample(f.pos(), f.velocity(), f.yaw(), f.pitch(), f.phase(), f.phaseAge(), f.aim()));
        fromEntity = false;
        slack = S2C.Heard.PERIOD + 2;
        drilling = f.drilling();
    }

    /**
     * Новая точка пути; пропущенные тики до неё — по прямой, долгий разрыв — история заново. Точка того же тика
     * поправляет последнюю: прямая строится заново от предыдущей.
     */
    private void append(long tick, Sample s) {
        if (tick < last) return;
        if (tick > last) previous = last;
        long from = previous;
        if (from >= 0 && tick - from > MAX_GAP) first = tick;
        else if (from >= 0 && tick - from > 1) {
            int i0 = (int) (from % CAPACITY);
            Vec3 p0 = new Vec3(xs[i0], ys[i0], zs[i0]), v0 = new Vec3(vxs[i0], vys[i0], vzs[i0]);
            for (long t = from + 1; t < tick; t++) {
                double k = (double) (t - from) / (tick - from);
                put(t, new Sample(p0.lerp(s.pos, k), v0.lerp(s.vel, k), s.yaw, s.pitch, s.phase, s.phaseAge - (int) (tick - t), s.aim));
            }
        }
        put(tick, s);
    }

    private void put(long tick, Sample s) {
        if (first < 0) first = tick;
        last = tick;
        int i = (int) (tick % CAPACITY);
        xs[i] = s.pos.x;
        ys[i] = s.pos.y;
        zs[i] = s.pos.z;
        vxs[i] = s.vel.x;
        vys[i] = s.vel.y;
        vzs[i] = s.vel.z;
        axs[i] = s.aim.x;
        ays[i] = s.aim.y;
        azs[i] = s.aim.z;
        yaws[i] = s.yaw;
        pitches[i] = s.pitch;
        phases[i] = s.phase;
        phaseAges[i] = Math.max(0, s.phaseAge);
    }

    /** Звук из момента t ещё есть чем вести (данные дошли не позже, чем на {@link #slack} тиков раньше). */
    boolean covers(double t) {
        return t <= last + slack;
    }

    /** Путь сейчас идёт по пакетам сервера (сущности у клиента нет). */
    boolean fromServer() {
        return !fromEntity;
    }

    void die(long tick) {
        if (death == Long.MAX_VALUE) death = Math.max(last, Math.min(tick, last + 1));
    }

    boolean isDead() {
        return death != Long.MAX_VALUE;
    }

    long deathTick() {
        return death;
    }

    long lastTick() {
        return last;
    }

    @Override
    public double start() {
        return Math.max(first, last - CAPACITY + 1);
    }

    @Override
    public void at(double t, double[] out) {
        double s = Math.max(start(), Math.min(last, t));
        long a = (long) Math.floor(s);
        long b = Math.min(last, a + 1);
        double f = s - a;
        int ia = (int) (a % CAPACITY), ib = (int) (b % CAPACITY);
        out[0] = xs[ia] + (xs[ib] - xs[ia]) * f;
        out[1] = ys[ia] + (ys[ib] - ys[ia]) * f;
        out[2] = zs[ia] + (zs[ib] - zs[ia]) * f;
    }

    /** Скорость в момент t, блоков/тик (между тиками — по прямой). */
    Vec3 velocity(double t) {
        double s = Math.max(start(), Math.min(last, t));
        long a = (long) Math.floor(s);
        long b = Math.min(last, a + 1);
        double f = s - a;
        int ia = (int) (a % CAPACITY), ib = (int) (b % CAPACITY);
        return new Vec3(vxs[ia] + (vxs[ib] - vxs[ia]) * f, vys[ia] + (vys[ib] - vys[ia]) * f, vzs[ia] + (vzs[ib] - vzs[ia]) * f);
    }

    int phase(double t) {
        long a = (long) Math.max(start(), Math.min(last, Math.floor(t)));
        return phases[(int) (a % CAPACITY)];
    }

    /** Куда снаряд летел в момент t (точка цели; перенацеливание — с того тика, как о нём стало известно). */
    Vec3 aim(double t) {
        int i = (int) ((long) Math.max(start(), Math.min(last, Math.floor(t))) % CAPACITY);
        return new Vec3(axs[i], ays[i], azs[i]);
    }

    /** Сколько тиков шла фаза полёта к моменту t (дробно — для плавной раскрутки мотора). */
    double phaseAge(double t) {
        double s = Math.max(start(), Math.min(last, t));
        long a = (long) Math.floor(s);
        return phaseAges[(int) (a % CAPACITY)] + (s - a);
    }

    /** Путь от «запаздывающего» положения до уха открыт (1) или закрыт (0); плавно, без щелчков. */
    float open(double now, Vec3 ear, Vec3 at) {
        long tick = (long) now;
        if (tick != openTick) {
            openTick = tick;
            open += (SoundFilters.open(ear, at) - open) * 0.3f;
        }
        return open;
    }

    /** Направление носа в момент t (для «спереди свист, сзади рёв»). */
    Vec3 forward(double t) {
        long a = (long) Math.max(start(), Math.min(last, Math.floor(t)));
        int i = (int) (a % CAPACITY);
        return Vec3.directionFromRotation(pitches[i], yaws[i]);
    }

    /** Одна запись пути: где снаряд, его сдвиг за тик, нос, фаза и сколько она идёт, куда он летит. */
    private record Sample(Vec3 pos, Vec3 vel, float yaw, float pitch, int phase, int phaseAge, Vec3 aim) {}
}
