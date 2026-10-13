package ua.zentix.replayscan.film;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Что снимает план: снаряд (UUID и путь по тикам из разбора — {@code tracks} и дальние строки {@code far}), события
 * (удары: тик, место, поперечник огненного шара) и место (хаос — середина ударов и разброс). Куда смотрит кадр в тик
 * {@code t}: в первые {@link #KEY_HOLD} тиков события — на него (шар поднимается), до события — на снаряд, до появления
 * снаряда — на место пуска, без пути — на место.
 */
final class Subject {
    /** Удар: тик повтора, место, поперечник огненного шара (блоков). */
    record Key(int t, Vec3 pos, double size) {}

    /** Сколько тиков после события кадр смотрит на него. */
    static final int KEY_HOLD = 60;

    @Nullable
    final UUID uuid;
    /** Поперечник снаряда, блоков. */
    final double size;
    private final double[][] path;
    final List<Key> keys;
    @Nullable
    final Vec3 area;
    final double areaSize;

    Subject(@Nullable String uuid, double size, List<double[]> path, List<Key> keys, @Nullable Vec3 area, double areaSize) {
        this.uuid = uuid == null ? null : UUID.fromString(uuid);
        this.size = size;
        this.path = path.stream().sorted(Comparator.comparingDouble(p -> p[0])).toArray(double[][]::new);
        this.keys = keys.stream().sorted(Comparator.comparingInt(Key::t)).toList();
        this.area = area;
        this.areaSize = areaSize;
    }

    /** Тик первого события ({@code −1} — событий нет). */
    int firstKey() {
        return keys.isEmpty() ? -1 : keys.get(0).t();
    }

    /** Событие, на которое кадр смотрит в тик t (последнее начавшееся не раньше {@link #KEY_HOLD} тиков назад). */
    @Nullable
    Key keyAt(double t) {
        Key at = null;
        for (Key k : keys) {
            if (k.t() <= t && t < k.t() + KEY_HOLD) at = k;
        }
        return at;
    }

    /** Место снаряда в тик t по пути (между точками — по прямой, за краями — край); без пути — null. */
    @Nullable
    Vec3 pathAt(double t) {
        if (path.length == 0) return null;
        if (t <= path[0][0]) return at(0);
        if (t >= path[path.length - 1][0]) return at(path.length - 1);
        int lo = 0, hi = path.length - 1;
        while (hi - lo > 1) {
            int mid = (lo + hi) >>> 1;
            if (path[mid][0] <= t) lo = mid;
            else hi = mid;
        }
        double k = (t - path[lo][0]) / Math.max(1e-9, path[hi][0] - path[lo][0]);
        return at(lo).lerp(at(hi), k);
    }

    private Vec3 at(int i) {
        return new Vec3(path[i][1], path[i][2], path[i][3]);
    }

    double pathFirst() {
        return path.length == 0 ? Double.NaN : path[0][0];
    }

    double pathLast() {
        return path.length == 0 ? Double.NaN : path[path.length - 1][0];
    }

    /** Куда смотрит кадр в тик t; null — смотреть не на что. */
    @Nullable
    Vec3 aim(double t) {
        Key k = keyAt(t);
        if (k != null) return k.pos().add(0, Math.min(1, (t - k.t()) / 40.0) * k.size() * 0.4, 0);
        Vec3 p = pathAt(t);
        if (p != null) {
            // после пути (снаряд ушёл из записи) — его последнее событие
            if (t > pathLast() && !keys.isEmpty() && keys.get(keys.size() - 1).t() <= t) return keys.get(keys.size() - 1).pos();
            return p;
        }
        if (area != null) return area;
        return keys.isEmpty() ? null : keys.get(0).pos();
    }

    /** Поперечник того, на что смотрит кадр в тик t. */
    double aimSize(double t) {
        Key k = keyAt(t);
        if (k != null) return k.size();
        if (path.length > 0) return size;
        if (area != null) return Math.max(areaSize, size);
        return keys.isEmpty() ? size : keys.get(0).size();
    }

    /** Кадр в тик t смотрит на сам снаряд (не на событие и не на место). */
    boolean onProjectile(double t) {
        return keyAt(t) == null && path.length > 0 && t >= pathFirst() - 1 && t <= pathLast() + 1;
    }

    /** Курс подлёта к первому событию по горизонтали (по пути за 40 тиков до него); без пути — на север. */
    Vec3 heading() {
        if (path.length < 2) return new Vec3(0, 0, -1);
        double t1 = firstKey() >= 0 ? Math.min(firstKey() - 1, pathLast()) : pathLast(), t0 = Math.max(pathFirst(), t1 - 40);
        Vec3 d = pathAt(t1).subtract(pathAt(t0)).multiply(1, 0, 1);
        return d.lengthSqr() < 1e-6 ? new Vec3(0, 0, -1) : d.normalize();
    }

    /** Главное место плана: первое событие, иначе середина места, иначе середина пути. */
    @Nullable
    Vec3 anchor(int a, int b) {
        if (!keys.isEmpty()) return keys.get(0).pos();
        if (area != null) return area;
        return pathAt(Mth.clamp((a + b) / 2.0, pathFirst(), pathLast()));
    }
}
