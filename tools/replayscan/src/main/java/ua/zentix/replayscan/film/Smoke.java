package ua.zentix.replayscan.film;

import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Field;

/**
 * Дым и огонь слоя эффектов Incoming на линии взгляда: сколько света проходит от глаза до точки (1 — чисто, 0 —
 * закрыто). Слой эффектов — частицы Incoming вне частиц игры, луч по блокам их не видит, поэтому — их поля отражением
 * (мод от Incoming не зависит; поля одни и те же с 2.5.0: {@code FxPool.INSTANCE}, {@code parts}, {@code count},
 * у частицы {@code x}, {@code y}, {@code z}, {@code quadSize}, {@code aCol}, {@code kind.additive}). Пакет — по версии:
 * {@code ua.zentix.incoming} с 3.0, {@code ua.zentix.airstrike} до неё. Нет Incoming или полей — проверки дыма нет
 * ({@link #available()}), это пишется в отчёт.
 */
final class Smoke {
    /** Плотная середина клуба — доля его полуширины, как у отрисовки ({@code FxParticle.DENSE}). */
    private static final double DENSE = 0.7;

    @Nullable
    private final Object pool;
    private Field parts, count, x, y, z, quad, alpha, kind, additive;

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
                break;
            } catch (ReflectiveOperationException | RuntimeException e) {
                found = null;
            }
        }
        pool = found;
    }

    private static Field open(Class<?> c, String name) throws NoSuchFieldException {
        Field f = c.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }

    boolean available() {
        return pool != null;
    }

    /**
     * Доля света от глаза до точки сквозь клубы: клуб на отрезке закрывает его на свою непрозрачность там, где отрезок
     * проходит сквозь плотную часть, к краю квадрата — на нет; светящиеся частицы и клубы ближе {@code spare} к точке не
     * в счёт (свой шлейф снаряда, его же огненный шар). Без слоя эффектов — 1.
     */
    double transmittance(double ex, double ey, double ez, double tx, double ty, double tz, double spare) {
        if (pool == null) return 1;
        try {
            Object[] all = (Object[]) parts.get(pool);
            int n = count.getInt(pool);
            double dx = tx - ex, dy = ty - ey, dz = tz - ez, len2 = dx * dx + dy * dy + dz * dz;
            double light = 1;
            for (int i = 0; i < n && i < all.length; i++) {
                Object p = all[i];
                if (p == null) continue;
                float a = alpha.getFloat(p);
                if (a <= 0.004f || additive.getBoolean(kind.get(p))) continue;
                double px = x.getDouble(p), py = y.getDouble(p), pz = z.getDouble(p);
                double ox = px - tx, oy = py - ty, oz = pz - tz;
                if (ox * ox + oy * oy + oz * oz < spare * spare) continue;
                double u = len2 < 1e-9 ? 0 : Math.max(0, Math.min(1, ((px - ex) * dx + (py - ey) * dy + (pz - ez) * dz) / len2));
                double cx = ex + dx * u - px, cy = ey + dy * u - py, cz = ez + dz * u - pz;
                double miss = Math.sqrt(cx * cx + cy * cy + cz * cz), r = quad.getFloat(p) / 2, dense = r * DENSE;
                if (miss >= r) continue;
                light *= 1 - a * (miss <= dense ? 1 : 1 - (miss - dense) / (r - dense));
            }
            return light;
        } catch (IllegalAccessException e) {
            return 1;
        }
    }
}
