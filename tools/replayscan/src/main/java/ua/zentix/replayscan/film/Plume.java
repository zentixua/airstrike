package ua.zentix.replayscan.film;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * Дым удара по земле во времени — по коду картинки Incoming (2.10 и 3.x одинаково), а не на глаз: частицы взрыва
 * ({@code Explosions.burst} и {@code burn} у {@code BlastEffects}) — облако остывшего шара, вал пыли по земле, дымок
 * догорания в воронке; столб дыма и пыли ({@code FarBlasts.column}) — стоит минуты, поднимается на сотню и больше блоков,
 * расплывается и уходит по ветру. Частица движется, как у мода ({@code FxParticle.tick}: всплытие и ветер, шаг,
 * сопротивление), полуразмер растёт и тает так же; случайное взято по краю разброса, у столба — середина и запас на
 * разброс клубов. Модель — фигуры с непрозрачностью ({@link Shape}): облако шара и клубы столба — шары, дымок воронки —
 * цепочка шаров, вал пыли — цилиндр; граница фигуры — плотная середина клубов (0,7 полуразмера, как у отрисовки). На
 * проходах 13.10 (9 полётов, кадры против модели) она отделяет кадры, закрытые дымом, от чистых: камера в фигуре или
 * взгляд вперёд в неё — 85 из 87 закрытых кадров и 1 из 193 чистых. Прежняя модель (столб радиусом 8 и 25 с жизни) не
 * знала столба и вдвое занижала клубы — пилот вёл дрон в дым сразу после удара. По модели пилот ({@link Pilot}) и
 * оценка камер обходят дым; вывод меряет сам дым мода ({@link Smoke}).
 *
 * @param t    тик удара
 * @param pos  место удара
 * @param size поперечник огненного шара, блоков (для расстояния камеры до удара)
 * @param look вид взрыва у мода: от него частицы и столб
 */
record Plume(int t, Vec3 pos, double size, Look look) {
    /** Поперечник шара, когда план его не знает (удары прежних минут — только место). */
    static final double SIZE = 16;
    /** Ветер слоя эффектов, блоков за тик² ({@code Fx.WIND_X}, {@code WIND_Z}); столб сносит в 20 раз сильнее (сопротивление 0,95). */
    static final double WIND_X = 0.0022, WIND_Z = 0.0011, DRIFT = 1 / (1 - 0.95);
    /** Плотная середина клуба — доля полуразмера ({@code FxParticle.DENSE}). */
    static final double CORE = 0.7;
    /** Фигура в счёт, если непрозрачнее этого. */
    static final double SEEN = 0.3;

    /**
     * Вид взрыва ({@code S2C.Blast}) и его дым: радиус шара частиц вблизи ({@code BlastEffects}: шахед и РСЗО — 5, ракета
     * 8,5, бомба 11), тиков догорания в воронке, столб ({@code FarBlasts.Look}: шар светит тиков, высота, за сколько тиков
     * поднимается, радиус клуба у основания и в шапке, клубов, жизнь). Бомба — прорыв наружу (больше и выше, чем без него).
     */
    enum Look {
        ROCKET(5, 220, 8, 105, 2400, 7, 26, 10, 3000),
        DRONE(5, 220, 13, 150, 2400, 10, 40, 12, 3600),
        MISSILE(8.5, 320, 30, 270, 2400, 18, 70, 14, 4800),
        BUNKER(11, 0, 20, 210, 2400, 18, 55, 14, 4200);

        final double r, column, base, top;
        final int fire, ball, riseTicks, puffs, life;

        Look(double r, int fire, int ball, double column, int riseTicks, double base, double top, int puffs, int life) {
            this.r = r;
            this.fire = fire;
            this.ball = ball;
            this.column = column;
            this.riseTicks = riseTicks;
            this.base = base;
            this.top = top;
            this.puffs = puffs;
            this.life = life;
        }

        /** Номер в строке дыма плана ({@code plan.py}): 1 — РСЗО, 2 — шахед, 3 — ракета, 4 — бомба; 0 и прочее — нет. */
        static Look of(int code) {
            return switch (code) {
                case 1 -> ROCKET;
                case 2 -> DRONE;
                case 3 -> MISSILE;
                case 4 -> BUNKER;
                default -> null;
            };
        }

        /** По поперечнику шара, когда вида нет (прежние планы): РСЗО 8, шахед 12 и неизвестный 16, ракета 18, бомба 24. */
        static Look bySize(double size) {
            return size < 10 ? ROCKET : size < 17 ? DRONE : size < 21 ? MISSILE : BUNKER;
        }

        /** С какого тика дым шара проступает ({@code FarBlasts.smokeFrom}). */
        int smokeFrom() {
            return (int) Math.ceil(0.55 * ball);
        }
    }

    /**
     * Фигура дыма: шар (середина, радиус) или вертикальный цилиндр ({@code cylinder}: ось x, z, от {@code y} до
     * {@code top}); непрозрачность 0..1.
     */
    record Shape(double x, double y, double z, double r, double top, boolean cylinder, double alpha) {
        /** Расстояние до фигуры (меньше 0 — внутри). */
        double distance(double px, double py, double pz) {
            if (!cylinder) {
                double dx = px - x, dy = py - y, dz = pz - z;
                return Math.sqrt(dx * dx + dy * dy + dz * dz) - r;
            }
            double h = Math.sqrt((px - x) * (px - x) + (pz - z) * (pz - z)) - r, v = Math.max(y - py, py - top);
            if (h <= 0 && v <= 0) return Math.max(h, v);
            return Math.sqrt(Math.max(0, h) * Math.max(0, h) + Math.max(0, v) * Math.max(0, v));
        }

        /** Отрезок проходит сквозь фигуру. */
        boolean crosses(Vec3 from, Vec3 to) {
            double dx = to.x - from.x, dy = to.y - from.y, dz = to.z - from.z;
            if (!cylinder) {
                double len2 = dx * dx + dy * dy + dz * dz;
                double u = len2 < 1e-9 ? 0 : Mth.clamp(((x - from.x) * dx + (y - from.y) * dy + (z - from.z) * dz) / len2, 0, 1);
                double cx = from.x + dx * u - x, cy = from.y + dy * u - y, cz = from.z + dz * u - z;
                return cx * cx + cy * cy + cz * cz < r * r;
            }
            // часть отрезка в высотах цилиндра, на ней — ближайшая к оси точка по горизонтали
            double u0 = 0, u1 = 1;
            if (Math.abs(dy) < 1e-9) {
                if (from.y < y || from.y > top) return false;
            } else {
                double a = (y - from.y) / dy, b = (top - from.y) / dy;
                u0 = Math.max(0, Math.min(a, b));
                u1 = Math.min(1, Math.max(a, b));
                if (u0 > u1) return false;
            }
            double len2 = dx * dx + dz * dz;
            double u = len2 < 1e-9 ? u0 : Mth.clamp(((x - from.x) * dx + (z - from.z) * dz) / len2, u0, u1);
            double cx = from.x + dx * u - x, cz = from.z + dz * u - z;
            return cx * cx + cz * cz < r * r;
        }
    }

    static Plume of(double[] row) {
        double size = row.length > 4 ? row[4] : SIZE;
        Look look = row.length > 5 ? Look.of((int) row[5]) : Look.bySize(size);
        return new Plume((int) row[0], new Vec3(row[1], row[2], row[3]), size, look);
    }

    /** Дым есть: удар был и столб ещё не растаял (вид без дыма — ядерный, у него своя картинка). */
    boolean alive(double at) {
        double u = at - t;
        return look != null && u >= 0 && u <= look.life;
    }

    /** Фигуры дыма в тик {@code at} (непрозрачные меньше {@link #SEEN} — тоже). */
    List<Shape> shapes(double at) {
        List<Shape> out = new ArrayList<>();
        shapes(at, out);
        return out;
    }

    void shapes(double at, List<Shape> out) {
        if (!alive(at)) return;
        double u = at - t, r = look.r;
        int from = look.smokeFrom();
        if (u < BALL_LIFE) {
            // облако шара: клубы из 0,3 R над местом разлетаются до 0,11 R за тик (сопротивление 0,9), всплывают, сносятся
            double cy = pos.y + 0.35 * r + 0.3 * r * 0.55 + (0.11 + 0.55 * 0.08 * r) * BALL.move(u) + BALL.rise(u);
            double reach = 0.3 * r + 0.11 * r * BALL.move(u), half = r * (0.55 + 1.4 * grow4(u / BALL_MEAN));
            double in = u < from ? 0 : Math.min(1, (u - from) / Math.max(1, look.ball - from));
            out.add(new Shape(pos.x + WIND_X * BALL.wind(u), cy, pos.z + WIND_Z * BALL.wind(u), reach + CORE * half, 0, false,
                    0.92 * in * fade(u / BALL_MEAN, 0.45)));
        }
        if (u < DUST_LIFE) {
            // вал пыли: клубы от 0,5 R расходятся по земле со скоростью до 0,35 R за тик (сопротивление 0,88)
            double half = r * (0.35 + 0.8 * grow4(u / DUST_MEAN));
            out.add(new Shape(pos.x + WIND_X * DUST.wind(u), pos.y, pos.z + WIND_Z * DUST.wind(u), 0.5 * r + 0.35 * r * DUST.move(u) + CORE * half,
                    pos.y + 0.6 + 0.07 * DUST.move(u) + DUST.rise(u) + CORE * half, true, 0.8 * fade(u / DUST_MEAN, 0.4)));
        }
        if (look.fire > 0 && u < look.fire + BURN_LIFE) {
            // дымок воронки: клуб за тик, пока горит; от самого молодого клуба до самого старого — наклонный столбик
            double lo = Math.max(0, u - look.fire), hi = Math.min(u, BURN_LIFE);
            double half = r * (0.12 + 0.48 * grow3(hi / BURN_MEAN)), y0 = pos.y + 0.3 + 0.15 * r;
            double alpha = 0.6 * Math.min(1, 1.5 * (1 - lo / look.fire));
            for (int k = 0; k < 4; k++) {
                double v = lo + (hi - lo) * k / 3;
                out.add(new Shape(pos.x + WIND_X * BURN.wind(v), y0 + 0.1 * BURN.move(v) + BURN.rise(v), pos.z + WIND_Z * BURN.wind(v),
                        0.8 * r + CORE * half, 0, false, alpha * fade(v / BURN_MEAN, 0.4)));
            }
        }
        // столб FarBlasts: клубы по доле высоты f, проступает, пока тает шар, тает снизу вверх
        double rise = 1 - Math.exp(-3 * u / look.riseTicks), emerge = smoothstep(from, Math.max(from + 8, look.ball), u);
        double spread = 1 + 0.5 * u / look.life, grow = 0.35 + 0.65 * rise;
        for (int i = 0; i < look.puffs; i++) {
            double f = Math.pow((i + 0.5) / look.puffs, 1.3);
            double alpha = 0.85 * emerge * (1 - smoothstep(look.life * (0.35 + 0.45 * f), look.life, u));
            double pr = (look.base + (look.top - look.base) * f) * grow * spread, h = 0.6 * pr + f * look.column * rise;
            double shear = 0.3 + 0.7 * f * rise;
            // размер клуба до 1,15 номинала, середина клуба от оси — до 0,38 его радиуса у 9 из 10 клубов
            out.add(new Shape(pos.x + WIND_X * DRIFT * u * shear, pos.y + h, pos.z + WIND_Z * DRIFT * u * shear, pr * (1.15 * CORE + 0.38), 0, false, alpha));
        }
    }

    /** Расстояние от точки до дыма (меньше 0 — в дыму); дыма в этот тик нет — бесконечность. */
    double clearance(Vec3 p, double at) {
        double best = Double.POSITIVE_INFINITY;
        if (!alive(at)) return best;
        for (Shape s : shapes(at)) {
            if (s.alpha() >= SEEN) best = Math.min(best, s.distance(p.x, p.y, p.z));
        }
        return best;
    }

    /**
     * Линия взгляда от {@code from} к {@code to} проходит сквозь дым. Свой дым цели не в счёт: удар ближе {@link #OWN}
     * блоков от цели — её же (огненный шар и его дым и есть то, на что смотрят).
     */
    boolean blocks(Vec3 from, Vec3 to, double at) {
        if (!alive(at) || pos.distanceTo(to) <= OWN) return false;
        for (Shape s : shapes(at)) {
            if (s.alpha() >= 0.5 && s.crosses(from, to)) return true;
        }
        return false;
    }

    /** Свой дым цели: удар ближе этого к ней. */
    static final double OWN = 12;

    // ---------------------------------------------------------------- частицы мода

    /** Жизнь клубов, тиков: облако шара 420–720, вал пыли 200–360, дымок воронки 140–220; середины — для роста и таяния. */
    static final int BALL_LIFE = 720, DUST_LIFE = 360, BURN_LIFE = 220;
    static final double BALL_MEAN = 570, DUST_MEAN = 280, BURN_MEAN = 180;
    /** Движение частиц трёх видов: сопротивление и всплытие ({@code Fx.Spec.drag}, {@code rise}). */
    static final Motion BALL = new Motion(0.9, 0.011, BALL_LIFE), DUST = new Motion(0.88, 0.002, DUST_LIFE), BURN = new Motion(0.96, 0.006, BURN_LIFE);

    /**
     * Путь частицы за {@code age} тиков по тику мода: скорость += всплытие (сильнее, пока горячая) и ветер, место +=
     * скорость, скорость *= сопротивление. Таблицы: сдвиг от начальной скорости 1, от ветра 1 за тик² и от всплытия.
     */
    static final class Motion {
        private final double[] move, wind, rise;

        Motion(double drag, double up, int life) {
            move = new double[life + 1];
            wind = new double[life + 1];
            rise = new double[life + 1];
            double v0 = 1, vw = 0, vr = 0;
            for (int age = 1; age <= life; age++) {
                vw += 1;
                vr += up * (0.15 + 0.85 * Math.exp(-age / 40.0));
                move[age] = move[age - 1] + v0;
                wind[age] = wind[age - 1] + vw;
                rise[age] = rise[age - 1] + vr;
                v0 *= drag;
                vw *= drag;
                vr *= drag;
            }
        }

        double move(double age) {
            return at(move, age);
        }

        double wind(double age) {
            return at(wind, age);
        }

        double rise(double age) {
            return at(rise, age);
        }

        private static double at(double[] v, double age) {
            double u = Mth.clamp(age, 0, v.length - 1);
            int i = Math.min(v.length - 2, (int) u);
            return v[i] + (v[i + 1] - v[i]) * (u - i);
        }
    }

    /** Рост полуразмера к концу жизни ({@code FxParticle.update}): быстрый — 1 − (1 − f)⁴, обычный — 1 − (1 − f)³. */
    static double grow4(double f) {
        double g = 1 - Mth.clamp(f, 0, 1);
        return 1 - g * g * g * g;
    }

    static double grow3(double f) {
        double g = 1 - Mth.clamp(f, 0, 1);
        return 1 - g * g * g;
    }

    /** Таяние с доли жизни {@code from} до конца. */
    static double fade(double f, double from) {
        return f < from ? 1 : Math.max(0, 1 - (f - from) / (1 - from));
    }

    static double smoothstep(double e0, double e1, double x) {
        if (e1 <= e0) return x >= e1 ? 1 : 0;
        double u = Mth.clamp((x - e0) / (e1 - e0), 0, 1);
        return u * u * (3 - 2 * u);
    }
}
