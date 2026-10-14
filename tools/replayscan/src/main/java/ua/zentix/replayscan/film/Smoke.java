package ua.zentix.replayscan.film;

import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collection;

/**
 * Дым и огонь слоя эффектов Incoming на линии взгляда: сколько света проходит от глаза до точки (1 — чисто, 0 —
 * закрыто). Слой эффектов — частицы Incoming вне частиц игры и столбы дыма взрывов, луч по блокам их не видит, поэтому —
 * их поля отражением (мод от Incoming не зависит; поля одни и те же с 2.10: частицы — {@code FxPool.INSTANCE},
 * {@code parts}, {@code count}, у частицы {@code x}, {@code y}, {@code z}, {@code quadSize} (полуразмер квадрата),
 * {@code aCol}, {@code kind.additive}; столбы — {@code FarBlasts.EVENTS} и {@code clock}, у взрыва {@code x}, {@code y},
 * {@code z}, {@code born}, {@code look} и клубы {@code f}, {@code ox}, {@code oz}, {@code size}). Пакет — по версии:
 * {@code ua.zentix.incoming} с 3.0, {@code ua.zentix.airstrike} до неё. Нет Incoming или полей — проверки дыма нет
 * ({@link #available()}), это пишется в отчёт; нет полей столбов — только частицы ({@link #columns()}).
 */
final class Smoke {
    /** Плотная середина клуба — доля его полуразмера, как у отрисовки ({@code FxParticle.DENSE}). */
    private static final double DENSE = 0.7;
    /** Столб: непрозрачность клуба, разброс клубов от оси (радиусов), снос ветром ({@code FarBlasts}). */
    private static final double OPACITY = 0.85, SPREAD = 0.4;
    /** Кадр в дыму: лучи сеткой по кадру до этой дальности, закрыт луч — света меньше этой доли. */
    static final double SMOTHER_REACH = 40, SMOTHER_LIGHT = 0.4;

    @Nullable
    private final Object pool;
    private Field parts, count, x, y, z, quad, alpha, kind, additive;
    /** Столбы: очередь взрывов, часы, поля взрыва и вида. */
    @Nullable
    private Collection<?> events;
    private Field clock, evX, evY, evZ, born, look, pf, pox, poz, psize;
    private Method column, riseTicks, base, top, life, ballTicks;
    private double windX, windZ;

    Smoke() {
        Object found = null;
        for (String pkg : new String[]{"ua.zentix.incoming", "ua.zentix.airstrike"}) {
            try {
                Class<?> poolClass = Class.forName(pkg + ".client.fx.particle.FxPool");
                Class<?> part = Class.forName(pkg + ".client.fx.particle.FxParticle");
                Class<?> kindClass = Class.forName(pkg + ".client.fx.particle.Fx$Kind");
                parts = open(poolClass, "parts");
                count = open(poolClass, "count");
                x = open(part, "x");
                y = open(part, "y");
                z = open(part, "z");
                quad = open(part, "quadSize");
                alpha = open(part, "aCol");
                kind = open(part, "kind");
                additive = open(kindClass, "additive");
                found = poolClass.getField("INSTANCE").get(null);
                columns(pkg);
                break;
            } catch (ReflectiveOperationException | RuntimeException e) {
                found = null;
            }
        }
        pool = found;
    }

    /** Поля столбов {@code FarBlasts}; нет — столбы не в счёт. */
    private void columns(String pkg) {
        try {
            Class<?> far = Class.forName(pkg + ".client.far.FarBlasts");
            Class<?> event = Class.forName(pkg + ".client.far.FarBlasts$Event");
            Class<?> lookClass = Class.forName(pkg + ".client.far.FarBlasts$Look");
            Class<?> fx = Class.forName(pkg + ".client.fx.particle.Fx");
            Collection<?> queue = (Collection<?>) open(far, "EVENTS").get(null);
            clock = open(far, "clock");
            evX = open(event, "x");
            evY = open(event, "y");
            evZ = open(event, "z");
            born = open(event, "born");
            look = open(event, "look");
            pf = open(event, "f");
            pox = open(event, "ox");
            poz = open(event, "oz");
            psize = open(event, "size");
            column = lookClass.getMethod("column");
            riseTicks = lookClass.getMethod("riseTicks");
            base = lookClass.getMethod("base");
            top = lookClass.getMethod("top");
            life = lookClass.getMethod("life");
            ballTicks = lookClass.getMethod("ballTicks");
            windX = open(fx, "WIND_X").getDouble(null);
            windZ = open(fx, "WIND_Z").getDouble(null);
            events = queue;
        } catch (ReflectiveOperationException | RuntimeException e) {
            events = null;
        }
    }

    private static Field open(Class<?> c, String name) throws NoSuchFieldException {
        Field f = c.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }

    boolean available() {
        return pool != null;
    }

    /** Столбы дыма взрывов в счёт. */
    boolean columns() {
        return events != null;
    }

    /**
     * Доля света от глаза до точки сквозь клубы: клуб на отрезке закрывает его на свою непрозрачность там, где отрезок
     * проходит сквозь плотную часть, к краю квадрата — на нет; светящиеся частицы и клубы ближе {@code spare} к точке не
     * в счёт (свой шлейф снаряда, его же огненный шар). Без слоя эффектов — 1.
     */
    double transmittance(double ex, double ey, double ez, double tx, double ty, double tz, double spare) {
        return puffs(ex, ey, ez) ? light(ex, ey, ez, tx, ty, tz, spare) : 1;
    }

    /**
     * Доля лучей кадра (сетка 3 × 5), которые дым закрывает ближе {@link #SMOTHER_REACH} блоков: кадр почти весь — дым
     * у самой камеры (так выглядели проходы, влетевшие в дым).
     */
    double smothered(Cameras.View v) {
        Vec3 e = v.eye();
        if (!puffs(e.x, e.y, e.z)) return 0;
        int n = 0, closed = 0;
        for (double sx : new double[]{-0.6, 0, 0.6}) {
            for (double sy : new double[]{-0.7, -0.35, 0, 0.35, 0.7}) {
                Vec3 d = v.fwd().add(v.right().scale(sx * v.tanX())).add(v.up().scale(sy * v.tanY())).normalize().scale(SMOTHER_REACH);
                n++;
                if (light(e.x, e.y, e.z, e.x + d.x, e.y + d.y, e.z + d.z, 0) < SMOTHER_LIGHT) closed++;
            }
        }
        return (double) closed / n;
    }

    /** Клубы дыма кадра подряд — x, y, z, полуразмер, непрозрачность; один буфер на все вызовы (поток отрисовки). */
    private double[] buf = new double[5 * 1024];
    private int held;

    /** Клубы дыма сейчас в {@link #buf} (у столба — для глаза в {@code e…}); без слоя эффектов — false. */
    private boolean puffs(double ex, double ey, double ez) {
        if (pool == null) return false;
        held = 0;
        try {
            Object[] all = (Object[]) parts.get(pool);
            int n = Math.min(count.getInt(pool), all.length);
            for (int i = 0; i < n; i++) {
                Object p = all[i];
                if (p == null) continue;
                float a = alpha.getFloat(p);
                if (a <= 0.004f || additive.getBoolean(kind.get(p))) continue;
                put(x.getDouble(p), y.getDouble(p), z.getDouble(p), quad.getFloat(p), a);
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
        if (events != null) {
            int particles = held;
            try {
                columns(ex, ey, ez);
            } catch (ReflectiveOperationException | RuntimeException e) {
                // столбы не читаются — дальше только частицы (в итоге — smoke_columns false)
                events = null;
                held = particles;
            }
        }
        return true;
    }

    private void put(double px, double py, double pz, double half, double a) {
        if (5 * held + 5 > buf.length) buf = Arrays.copyOf(buf, 2 * buf.length);
        int i = 5 * held++;
        buf[i] = px;
        buf[i + 1] = py;
        buf[i + 2] = pz;
        buf[i + 3] = half;
        buf[i + 4] = a;
    }

    /** Свет по отрезку от глаза до точки сквозь клубы {@link #buf}. */
    private double light(double ex, double ey, double ez, double tx, double ty, double tz, double spare) {
        double dx = tx - ex, dy = ty - ey, dz = tz - ez, len2 = dx * dx + dy * dy + dz * dz;
        double light = 1;
        for (int i = 0; i < 5 * held; i += 5) {
            light *= 1 - buf[i + 4] * cover(ex, ey, ez, dx, dy, dz, len2, buf[i], buf[i + 1], buf[i + 2], buf[i + 3], tx, ty, tz, spare);
        }
        return light;
    }

    /**
     * Клубы столбов {@code FarBlasts.column} — по тем же числам, что у отрисовки (подъём, расплывание, снос, таяние
     * снизу, проступает, пока тает шар); глаз внутри клуба — клуб тает, как там.
     */
    private void columns(double ex, double ey, double ez) throws ReflectiveOperationException {
        double now = clock.getLong(null);
        for (Object e : events.toArray()) {
            double age = Math.max(0, now - born.getLong(e));
            Object k = look.get(e);
            double lifeT = ((Number) life.invoke(k)).doubleValue();
            if (age > lifeT) continue;
            double rise = 1 - Math.exp(-3 * age / ((Number) riseTicks.invoke(k)).doubleValue());
            double ball = ((Number) ballTicks.invoke(k)).doubleValue(), from = Math.ceil(0.55 * ball);
            double emerge = Plume.smoothstep(from, Math.max(from + 8, ball), age);
            if (emerge <= 0) continue;
            double col = ((Number) column.invoke(k)).doubleValue(), b0 = ((Number) base.invoke(k)).doubleValue(), b1 = ((Number) top.invoke(k)).doubleValue();
            double bx = evX.getDouble(e), by = evY.getDouble(e), bz = evZ.getDouble(e);
            double windDx = windX * Plume.DRIFT * age, windDz = windZ * Plume.DRIFT * age;
            double spread = 1 + 0.5 * age / lifeT, grow = 0.35 + 0.65 * rise;
            float[] f = (float[]) pf.get(e), ox = (float[]) pox.get(e), oz = (float[]) poz.get(e), size = (float[]) psize.get(e);
            for (int i = 0; i < f.length; i++) {
                double a = OPACITY * emerge * (1 - Plume.smoothstep(lifeT * (0.35 + 0.45 * f[i]), lifeT, age));
                if (a < 0.004) continue;
                double r = (b0 + (b1 - b0) * f[i]) * size[i] * grow * spread, h = 0.6 * r + f[i] * col * rise, shear = 0.3 + 0.7 * f[i] * rise;
                double px = bx + ox[i] * r * SPREAD + windDx * shear, py = by + h, pz = bz + oz[i] * r * SPREAD + windDz * shear;
                double d = Math.sqrt((px - ex) * (px - ex) + (py - ey) * (py - ey) + (pz - ez) * (pz - ez));
                a *= Plume.smoothstep(0.5 * r, r, d);
                if (a >= 0.004) put(px, py, pz, r, a);
            }
        }
    }

    /**
     * Доля, на которую клуб полуразмера {@code half} в {@code p} закрывает отрезок от глаза (0 — мимо, 1 — сквозь
     * середину). Клуб — квадрат лицом к камере через свою середину ({@code FxQuads.billboard}): середина за глазом —
     * квадрат за камерой, его не видно.
     */
    static double cover(double ex, double ey, double ez, double dx, double dy, double dz, double len2,
                                double px, double py, double pz, double half, double tx, double ty, double tz, double spare) {
        double ox = px - tx, oy = py - ty, oz = pz - tz;
        if (ox * ox + oy * oy + oz * oz < spare * spare) return 0;
        double along = (px - ex) * dx + (py - ey) * dy + (pz - ez) * dz;
        if (along <= 0) return 0;
        double u = len2 < 1e-9 ? 0 : Math.min(1, along / len2);
        double cx = ex + dx * u - px, cy = ey + dy * u - py, cz = ez + dz * u - pz;
        double miss = Math.sqrt(cx * cx + cy * cy + cz * cz), dense = half * DENSE;
        if (miss >= half) return 0;
        return miss <= dense ? 1 : 1 - (miss - dense) / (half - dense);
    }
}
