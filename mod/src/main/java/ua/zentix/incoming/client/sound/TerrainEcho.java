package ua.zentix.incoming.client.sound;

import ua.zentix.incoming.client.far.Sightline;
import ua.zentix.incoming.nuclear.model.FrontProfile;

import java.util.ArrayList;
import java.util.List;

/**
 * Эхо далёкого взрыва от рельефа (без Minecraft, проверяется юнит-тестами): какие склоны вернут удар к уху, когда
 * и насколько тише прямого звука. Удар фронта у слушателя — только начало: тот же фронт доходит до холмов, лесов
 * и городов вокруг, и каждый склон, обращённый и к взрыву, и к уху, отражает его — раскат катится секундами,
 * а с гор возвращается отдельным ударом.
 * <p>
 * Лучи из уха по карте высот ({@link Sightline.Heights}: чанки клиента, дальше — плитки рельефа карты) по кругу,
 * {@link #RAYS} штук; шаг растёт с расстоянием ({@link #step}). Отражает то, что ухо видит: точка, которая поднимается
 * над горизонтом луча (угол места выше всего, что ближе), — открытая уху грань; её телесный угол — прибавка угла места
 * на ширину луча. Отражение по Ламберту (рассеяние, а не зеркало: склон в карте высот — не ровная стена):
 * доля энергии прямого звука у уха
 * <pre>E = ρ · cos θ₁ · Ω / π · (p(r₁) / p(rₑ))²</pre>
 * где ρ — отражение земли на низах ({@link #ALBEDO}), θ₁ — угол падения фронта на склон (нормаль — по разностям карты
 * высот), Ω — телесный угол грани у уха, p — давление фронта на расстоянии r₁ склона и rₑ уха от взрыва (у эпицентра
 * волна нелинейна и теряет силу быстрее, чем расходится: назад к уху от неё идёт не больше, чем от звука, который
 * расходится сферой, — отношение не больше rₑ/r₁). Ровная земля отражает низы почти зеркально — вперёд, а не назад
 * к уху: рассеивает её доля {@link #FLAT}, склон от {@link #STEEP} — целиком (кроны леса и стены домов — тоже склоны
 * в карте высот). Склон,
 * которого взрыв не видит (за гребнем), получает фронт обходом — {@link #SHADOW} энергии. Удар приходит к склону
 * тогда же, когда к нему приходит фронт игры ({@code Detonation.arrivalTicks}: стена пыли, дальше — скорость звука),
 * а от склона к уху — со скоростью звука; задержка — против прихода прямого удара.
 * <p>
 * Диффузная модель недооценивает низы больших склонов (на 50–100 Гц длина волны 3–7 м, склон в сотни метров отражает
 * почти зеркально) и не знает, что прямой звук у земли теряет больше, чем отражённый сверху, — поэтому эхо поднято на
 * {@link #LIFT_DB}, но ни одно отражение не громче {@link #CAP_DB} прямого звука.
 */
public final class TerrainEcho {
    /** Лучей по кругу (через 5°). */
    public static final int RAYS = 72;
    /** До скольких блоков от уха ищутся склоны. */
    public static final double RANGE = 4096;
    /** Ближе — не отражатель: такое эхо сливается с прямым ударом. */
    static final double NEAR = 24;
    /** Шаг луча: доля расстояния, не мельче и не крупнее, блоков. */
    static final double STEP_SHARE = 0.03, STEP_MIN = 8, STEP_MAX = 96;
    /** Отражение земли на низах (63–250 Гц): трава, лес, скалы — 0,7–0,9. */
    static final double ALBEDO = 0.8;
    /** Доля рассеяния у ровной земли; склон круче {@link #STEEP} (sin² уклона) рассеивает весь. */
    static final double FLAT = 0.1, STEEP = 0.25;
    /** Склон за гребнем от взрыва получает фронт обходом — эта доля энергии. */
    static final double SHADOW = 0.25;
    /** Подъём эха над диффузной моделью и наибольшее отражение против прямого звука, дБ. */
    static final double LIFT_DB = 12, CAP_DB = -3;
    /** Тише этого (против прямого, дБ) — отражения нет. */
    static final double FLOOR_DB = -45;
    /** Эхо раньше стольких тиков после прямого удара сливается с ним. */
    static final double MIN_DELAY = 2;

    private TerrainEcho() {}

    /** Взрыв: когда фронт приходит на расстояние от точки подрыва и какое там давление. */
    public interface Blast {
        /** Через сколько тиков после подрыва фронт дойдёт до расстояния {@code blocks}. */
        double arrivalTicks(double blocks);

        /** Давление фронта на этом расстоянии (единицы любые: важно отношение). */
        double pressure(double blocks);
    }

    /**
     * Одно отражение.
     *
     * @param x,y,z     где склон
     * @param azimuth   направление от уха, рад ({@code atan2(dz, dx)})
     * @param delay     через сколько тиков после прямого удара приходит
     * @param energy    энергия против прямого удара (с подъёмом {@link #LIFT_DB}, не больше {@link #CAP_DB})
     * @param extraPath на сколько блоков путь через склон длиннее прямого — воздух съедает верха
     * @param distance  от уха до склона, блоков
     */
    public record Reflection(double x, double y, double z, double azimuth, double delay, double energy, double extraPath, double distance) {
        /** Громкость (амплитуда) против прямого удара. */
        public double gain() {
            return Math.sqrt(energy);
        }
    }

    /** Шаг луча на расстоянии r: дальше — крупнее (дальний склон и так виден под малым углом). */
    static double step(double r) {
        return Math.max(STEP_MIN, Math.min(STEP_MAX, r * STEP_SHARE));
    }

    /** Все лучи разом (для проверок; клиент считает лучи по частям — {@link #ray}). */
    public static List<Reflection> trace(Sightline.Heights h, double ex, double ey, double ez, double bx, double by, double bz, Blast blast) {
        List<Reflection> out = new ArrayList<>();
        for (int i = 0; i < RAYS; i++) ray(h, i, RAYS, RANGE, ex, ey, ez, bx, by, bz, blast, out);
        return out;
    }

    /**
     * Один луч из уха: открытые уху грани, которые поднимаются над горизонтом луча, — в {@code out}.
     *
     * @param index номер луча из {@code rays} по кругу
     */
    public static void ray(Sightline.Heights h, int index, int rays, double range, double ex, double ey, double ez, double bx, double by, double bz,
                           Blast blast, List<Reflection> out) {
        double phi = 2 * Math.PI * index / rays, width = 2 * Math.PI / rays;
        double cx = Math.cos(phi), cz = Math.sin(phi);
        double re = Math.sqrt((ex - bx) * (ex - bx) + (ey - by) * (ey - by) + (ez - bz) * (ez - bz));
        double direct = blast.arrivalTicks(re), pe = blast.pressure(re);
        if (!(pe > 0)) return;
        double horizon = Double.NaN, lastTop = Double.NaN, lastR = 0;
        for (double r = NEAR; r <= range; r += step(r)) {
            double x = ex + cx * r, z = ez + cz * r;
            double top = h.at((int) Math.floor(x), (int) Math.floor(z));
            if (Double.isNaN(top)) continue;
            double t = (top - ey) / r;
            double prevTop = lastTop, prevR = lastR;
            lastTop = top;
            lastR = r;
            // первая известная точка — горизонт: всё, что ниже его, ухо не видит
            if (Double.isNaN(horizon)) {
                horizon = t;
                continue;
            }
            if (t <= horizon) continue;
            double rise = Math.atan(t) - Math.atan(horizon);
            horizon = t;
            // телесный угол грани у уха: прибавка угла места × ширина луча × cos угла места
            double omega = rise * width * Math.cos(Math.atan(t));
            double[] n = normal(h, x, z, step(r), cx, cz, top, prevTop, r - prevR);
            double px = x - bx, py = top - by, pz = z - bz;
            double r1 = Math.sqrt(px * px + py * py + pz * pz);
            if (r1 < 1) continue;
            // фронт падает на склон: нормаль навстречу направлению от взрыва
            double cos1 = -(px * n[0] + py * n[1] + pz * n[2]) / r1;
            if (cos1 <= 0) continue;
            double ux = ex - x, uy = ey - top, uz = ez - z;
            double r2 = Math.sqrt(ux * ux + uy * uy + uz * uz);
            // к уху склон тоже должен быть обращён лицом (видна верхушка, а не обратный скат)
            if (ux * n[0] + uy * n[1] + uz * n[2] <= 0) continue;
            double ratio = Math.min(blast.pressure(r1) / pe, re / r1);
            double tilt = 1 - n[1] * n[1];
            double e = ALBEDO * (FLAT + (1 - FLAT) * Math.min(1, tilt / STEEP)) * cos1 * omega / Math.PI * ratio * ratio;
            if (!(e > 0)) continue;
            double delay = blast.arrivalTicks(r1) + r2 / FrontProfile.SOUND_SPEED - direct;
            if (delay < MIN_DELAY) continue;
            if (Sightline.trace(h, bx, by, bz, x, top + 1, z).pathDifference() > 0) e *= SHADOW;
            double db = Math.min(CAP_DB, 10 * Math.log10(e) + LIFT_DB);
            if (db < FLOOR_DB) continue;
            out.add(new Reflection(x, top, z, phi, delay, Math.pow(10, db / 10), r1 + r2 - re, r2));
        }
    }

    /**
     * Нормаль склона в точке: по разностям карты высот с четырёх сторон; где их нет (край известной карты) — по подъёму
     * вдоль луча от прошлой точки.
     */
    static double[] normal(Sightline.Heights h, double x, double z, double step, double cx, double cz, double top, double prevTop, double along) {
        double g = Math.max(2, step / 2);
        double hx1 = h.at((int) Math.floor(x + g), (int) Math.floor(z)), hx0 = h.at((int) Math.floor(x - g), (int) Math.floor(z));
        double hz1 = h.at((int) Math.floor(x), (int) Math.floor(z + g)), hz0 = h.at((int) Math.floor(x), (int) Math.floor(z - g));
        double gx, gz;
        if (Double.isNaN(hx1) || Double.isNaN(hx0) || Double.isNaN(hz1) || Double.isNaN(hz0)) {
            double s = Double.isNaN(prevTop) || along <= 0 ? 0 : (top - prevTop) / along;
            gx = cx * s;
            gz = cz * s;
        } else {
            gx = (hx1 - hx0) / (2 * g);
            gz = (hz1 - hz0) / (2 * g);
        }
        double len = Math.sqrt(gx * gx + 1 + gz * gz);
        return new double[]{-gx / len, 1 / len, -gz / len};
    }
}
