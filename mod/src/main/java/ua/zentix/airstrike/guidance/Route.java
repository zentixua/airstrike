package ua.zentix.airstrike.guidance;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Маршрут полёта: точки поворота по горизонтали (высоту держит сам снаряд), после последней — цель.
 * Как у настоящих шахедов и крылатых ракет: не по прямой от пусковой, а петлёй в обход и заход на цель
 * с выбранного направления — отсюда полёт на десятки секунд и удар «из-за спины».
 */
public final class Route {
    private final List<Vec3> points;
    /** Откуда начат маршрут: начало первого участка (для проверки «проскочил» уже на нём). */
    @Nullable
    private final Vec3 origin;
    private int next;

    public Route(List<Vec3> points) {
        this(points, null);
    }

    public Route(List<Vec3> points, @Nullable Vec3 origin) {
        this.points = new ArrayList<>(points);
        this.origin = origin;
    }

    public static Route direct() {
        return new Route(List.of());
    }

    /** Следующая точка поворота или null — дальше только цель. */
    @Nullable
    public Vec3 current() {
        return next < points.size() ? points.get(next) : null;
    }

    public boolean finished() {
        return next >= points.size();
    }

    /** Бросить оставшиеся точки: прямо на цель (перенацеливание из камеры). */
    public void skip() {
        next = points.size();
    }

    /**
     * Точка пройдена, если до неё ближе {@code capture} по горизонтали или она уже позади по ходу участка
     * (при большом радиусе разворота снаряд не кружит вокруг точки, а идёт дальше). Для первой точки участок
     * начинается в точке пуска: точка входа позади старта (короткий полёт) иначе ловила шахед на вечный круг.
     */
    public void update(Vec3 pos, double capture) {
        while (next < points.size()) {
            Vec3 wp = points.get(next);
            Vec3 from = next == 0 ? origin : points.get(next - 1);
            double dx = wp.x - pos.x, dz = wp.z - pos.z;
            boolean reached = dx * dx + dz * dz <= capture * capture;
            if (!reached && from != null) {
                // перешли через перпендикуляр к участку в точке поворота
                double lx = wp.x - from.x, lz = wp.z - from.z;
                reached = lx * dx + lz * dz < 0 && dx * dx + dz * dz < 4 * capture * capture;
            }
            if (!reached) return;
            next++;
        }
    }

    /** Длина оставшегося пути по горизонтали: до следующей точки, по точкам и от последней до цели. */
    public double remaining(Vec3 pos, Vec3 target) {
        double len = 0;
        Vec3 at = pos;
        for (int i = next; i < points.size(); i++) {
            len += horizontal(at, points.get(i));
            at = points.get(i);
        }
        return len + horizontal(at, target);
    }

    public int size() {
        return points.size();
    }

    public int index() {
        return next;
    }

    public List<Vec3> points() {
        return List.copyOf(points);
    }

    static double horizontal(Vec3 a, Vec3 b) {
        double dx = b.x - a.x, dz = b.z - a.z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    /**
     * Маршрут длиной около {@code length}: пуск → (точка обхода сбоку) → точка входа → цель.
     * Точка входа — в {@code entry} блоках до цели по направлению захода; если прямой путь короче нужного,
     * добавляется точка обхода сбоку от середины участка «пуск — вход», вынесенная так, чтобы путь стал нужной длины.
     *
     * @param approach горизонтальное направление захода на цель (единичный вектор, «куда летит» на последнем участке)
     * @param side     +1 — обход слева, -1 — справа
     */
    public static Route plan(Vec3 launch, Vec3 target, Vec3 approach, double length, double entry, double side) {
        Vec3 dir = new Vec3(approach.x, 0, approach.z);
        dir = dir.lengthSqr() < 1.0e-6 ? new Vec3(0, 0, 1) : dir.normalize();
        Vec3 e = new Vec3(target.x - dir.x * entry, 0, target.z - dir.z * entry);
        Vec3 l = new Vec3(launch.x, 0, launch.z);
        double le = horizontal(l, e);
        List<Vec3> pts = new ArrayList<>();
        double need = length - entry;
        if (need > le + 1) {
            Vec3 m = l.add(e).scale(0.5);
            Vec3 along = le > 1 ? e.subtract(l).normalize() : dir;
            Vec3 perp = new Vec3(-along.z, 0, along.x).scale(side);
            double half = le / 2;
            double h = Math.sqrt(Math.max(0, need * need / 4 - half * half));
            pts.add(m.add(perp.scale(h)));
        }
        pts.add(e);
        return new Route(pts, l);
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        ListTag list = new ListTag();
        for (Vec3 p : points) {
            CompoundTag t = new CompoundTag();
            t.putDouble("x", p.x);
            t.putDouble("z", p.z);
            list.add(t);
        }
        tag.put("points", list);
        tag.putInt("next", next);
        if (origin != null) {
            tag.putDouble("origin_x", origin.x);
            tag.putDouble("origin_z", origin.z);
        }
        return tag;
    }

    public static Route load(CompoundTag tag) {
        List<Vec3> pts = new ArrayList<>();
        for (Tag t : tag.getList("points", Tag.TAG_COMPOUND)) {
            CompoundTag c = (CompoundTag) t;
            pts.add(new Vec3(c.getDouble("x"), 0, c.getDouble("z")));
        }
        Vec3 origin = tag.contains("origin_x") ? new Vec3(tag.getDouble("origin_x"), 0, tag.getDouble("origin_z")) : null;
        Route r = new Route(pts, origin);
        r.next = Math.min(tag.getInt("next"), pts.size());
        return r;
    }
}
