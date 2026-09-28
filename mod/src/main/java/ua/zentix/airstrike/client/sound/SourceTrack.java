package ua.zentix.airstrike.client.sound;

import net.minecraft.world.phys.Vec3;
import ua.zentix.airstrike.entity.BunkerBusterEntity;
import ua.zentix.airstrike.entity.StrikeProjectile;
import ua.zentix.airstrike.strike.WeaponType;

/**
 * История полёта снаряда на клиенте (по тикам): из неё звук берёт «запаздывающее» положение и скорость.
 * Живёт дольше сущности: после взрыва дальние слушатели ещё какое-то время слышат мотор.
 */
final class SourceTrack implements Acoustics.Path {
    private static final int CAPACITY = 64;

    final int entityId;
    final WeaponType weapon;
    private final double[] xs = new double[CAPACITY], ys = new double[CAPACITY], zs = new double[CAPACITY];
    private final int[] phases = new int[CAPACITY], phaseAges = new int[CAPACITY];
    private final float[] yaws = new float[CAPACITY], pitches = new float[CAPACITY];
    private long first = -1, last = -1;
    private long death = Long.MAX_VALUE;
    /** Открыт ли путь звука к уху (1) или за холмом/домом (0), сглажено; считается раз в тик на все слои. */
    private float open = 1;
    private long openTick = -1;
    /** Бомба бурит (для звука бурения), ракета — сколько ей до цели (для свиста). */
    boolean drilling;
    double distanceToAim = Double.MAX_VALUE;

    SourceTrack(StrikeProjectile p) {
        this.entityId = p.getId();
        this.weapon = p.weapon();
    }

    void record(long tick, StrikeProjectile p) {
        if (first < 0) first = tick;
        last = tick;
        int i = (int) (tick % CAPACITY);
        xs[i] = p.getX();
        ys[i] = p.getY();
        zs[i] = p.getZ();
        yaws[i] = p.getYRot();
        pitches[i] = p.getXRot();
        phases[i] = p.flightPhase().ordinal();
        phaseAges[i] = p.phaseAge();
        drilling = p instanceof BunkerBusterEntity b && b.isDrilling();
        distanceToAim = p.position().distanceTo(p.aimPoint());
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

    /** Скорость в момент t, блоков/тик. */
    Vec3 velocity(double t) {
        double[] a = new double[3], b = new double[3];
        at(t - 0.5, a);
        at(t + 0.5, b);
        return new Vec3(b[0] - a[0], b[1] - a[1], b[2] - a[2]);
    }

    int phase(double t) {
        long a = (long) Math.max(start(), Math.min(last, Math.floor(t)));
        return phases[(int) (a % CAPACITY)];
    }

    /** Сколько тиков шла фаза полёта к моменту t (дробно — для плавной раскрутки мотора). */
    double phaseAge(double t) {
        double s = Math.max(start(), Math.min(last, t));
        long a = (long) Math.floor(s);
        return phaseAges[(int) (a % CAPACITY)] + (s - a);
    }

    /** Путь от «запаздывающего» положения p до уха открыт (1) или закрыт (0); плавно, без щелчков. */
    float open(double now, Vec3 ear, double[] p) {
        long tick = (long) now;
        if (tick != openTick) {
            openTick = tick;
            open += (SoundFilters.open(ear, new Vec3(p[0], p[1], p[2])) - open) * 0.3f;
        }
        return open;
    }

    /** Направление носа в момент t (для «спереди свист, сзади рёв»). */
    Vec3 forward(double t) {
        long a = (long) Math.max(start(), Math.min(last, Math.floor(t)));
        int i = (int) (a % CAPACITY);
        return Vec3.directionFromRotation(pitches[i], yaws[i]);
    }
}
