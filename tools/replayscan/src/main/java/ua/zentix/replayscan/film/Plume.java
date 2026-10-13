package ua.zentix.replayscan.film;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Дым удара по земле во времени — модель по слою эффектов Incoming (2.x и 3.x одинаково, {@code BlastEffects}): клубы
 * огненного шара (до 1,3 его радиуса) почти не поднимаются и живут 12–19 с, пыль грунта поднимается на 10–15 блоков,
 * ветер ({@code Fx.WIND_X}, {@code WIND_Z}) сносит клубы на восток и юг. Модель — столб с запасом: от земли до верха
 * {@link #top}, радиусом {@link #radius}, ось снесена ветром ({@link #drift}). По ней камеры оцениваются до вывода и
 * пилот обходит дым ({@link Pilot}); вывод меряет сам дым слоя эффектов ({@link Smoke}).
 *
 * @param t    тик удара
 * @param pos  место удара
 * @param size поперечник огненного шара, блоков
 */
record Plume(int t, Vec3 pos, double size) {
    /** Тиков дыма в модели: самые долгие клубы — 380 тиков, пыль из воронки идёт ещё секунды. */
    static final int LIFE = 500;
    /** Поперечник шара, когда план его не знает (удары прежних минут — только место). */
    static final double SIZE = 14;
    /**
     * Снос ветром, блоков за тик: установившаяся скорость клуба (ветер слоя эффектов {@code 0.0022}, {@code 0.0011} за
     * тик² при сопротивлении 0,85), первые 20 тиков клубы ещё разлетаются.
     */
    static final double WIND_X = 0.015, WIND_Z = 0.0073;

    static Plume of(double[] row) {
        return new Plume((int) row[0], new Vec3(row[1], row[2], row[3]), row.length > 4 ? row[4] : SIZE);
    }

    boolean alive(double at) {
        double u = at - t;
        return u >= 0 && u <= LIFE;
    }

    /** Радиус столба: шар раздувается за 20 тиков до 0,75 своего поперечника, не меньше 8 блоков. */
    double radius(double at) {
        return Math.max(8, 0.75 * size) * Math.min(1, 0.45 + (at - t) / 36);
    }

    /** Верх столба над местом удара: шар — сразу, пыль за 5 с поднимается до 30 блоков. */
    double top(double at) {
        return Math.max(0.8 * size, 30 * Math.min(1, (at - t) / 100));
    }

    /** Ось столба у земли (место, снесённое ветром). */
    Vec3 base(double at) {
        double u = Math.max(0, at - t - 20);
        return pos.add(WIND_X * u, 0, WIND_Z * u);
    }

    /** Расстояние от точки до дыма (меньше 0 — в дыму); дыма в этот тик нет — бесконечность. */
    double clearance(Vec3 p, double at) {
        if (!alive(at)) return Double.POSITIVE_INFINITY;
        Vec3 b = base(at);
        double y = Mth.clamp(p.y, b.y - 1, b.y + top(at));
        double dx = p.x - b.x, dy = p.y - y, dz = p.z - b.z;
        return Math.sqrt(dx * dx + dy * dy + dz * dz) - radius(at);
    }

    /**
     * Линия взгляда от {@code from} к {@code to} проходит сквозь столб. Свой дым цели не в счёт: столб ближе {@link #OWN}
     * блоков от цели — её же удар (огненный шар и его дым и есть то, на что смотрят).
     */
    boolean blocks(Vec3 from, Vec3 to, double at) {
        if (!alive(at) || pos.distanceTo(to) <= OWN) return false;
        Vec3 b = base(at);
        double r = radius(at), lo = b.y - 1, hi = b.y + top(at);
        // ближайшая к оси точка отрезка по горизонтали; там же — высота отрезка
        double dx = to.x - from.x, dz = to.z - from.z, len2 = dx * dx + dz * dz;
        double u = len2 < 1e-9 ? 0 : Mth.clamp(((b.x - from.x) * dx + (b.z - from.z) * dz) / len2, 0, 1);
        double cx = from.x + dx * u - b.x, cz = from.z + dz * u - b.z;
        if (cx * cx + cz * cz >= r * r) return false;
        double y = from.y + (to.y - from.y) * u;
        return y >= lo - r && y <= hi + r;
    }

    /** Свой дым цели: столб удара ближе этого к ней. */
    static final double OWN = 12;
}
