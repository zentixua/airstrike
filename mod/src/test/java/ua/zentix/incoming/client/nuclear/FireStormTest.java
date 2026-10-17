package ua.zentix.incoming.client.nuclear;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.nuclear.model.ThermalModel;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Огненный шторм ({@link FireStorm}): источники по сетке — по закону поджига руин и звука, с горючестью районов; поджиг —
 * не раньше волны; пламя и дым разгораются, стихают и гаснут к утру; карта; смерчи — редкие, короткие, у сильных очагов.
 */
class FireStormTest {
    private static final Detonation AIR = new Detonation(1, new Vec3(0, 64 + 500, 0), 64, 15, false, 0, 0.5f, 6, 20_000, 7, 1, false, 1);
    private static final Detonation GROUND = new Detonation(2, new Vec3(0, 64, 0), 64, 15, true, 0, 0.5f, 6, 20_000, 9, 1, false, 1);
    private static final Detonation SMALL = new Detonation(3, new Vec3(0, 64, 0), 64, 1, true, 0, 0.5f, 6, 20_000, 11, 1, false, 1);
    private static final long NIGHT = 18000;

    private static FireStorm.Grid grid(Detonation d) {
        return FireStorm.build(d, new PallField(d, NIGHT).fireReach());
    }

    /** Середина клетки {@code c} от эпицентра, блоки. */
    private static double[] centre(FireStorm.Grid g, int c) {
        double half = g.side() * g.cell() / 2;
        return new double[]{(c % g.side() + 0.5) * g.cell() - half, (c / g.side() + 0.5) * g.cell() - half};
    }

    @Test
    void sourcesFollowTheIgnitionLawInsideTheZone() {
        for (Detonation d : List.of(AIR, GROUND)) {
            double reach = new PallField(d, NIGHT).fireReach();
            FireStorm.Grid g = grid(d);
            assertEquals(FireStorm.CELL, g.cell(), 1e-9, "у 15 кт — клетка 32 блока");
            assertTrue(g.side() <= FireStorm.MAX_SIDE && g.side() * g.cell() / 2 >= reach, "карта накрывает зону: " + g.side());
            assertTrue(g.index().length > 5000, "клеток " + g.index().length);
            double inner = d.surface() ? 0.8 * d.fireballRadius() : 0;
            // там, где поджигает всё (q ≥ 20 у любого района), средняя сила — доля «есть чему гореть» за вычетом
            // сбитого волной: районы и клетки в среднем ×1
            double sum = 0, expected = 0;
            int n = 0;
            for (int k = 0; k < g.index().length; k++) {
                double[] xz = centre(g, g.index()[k]);
                double r = Math.hypot(xz[0], xz[1]);
                assertTrue(r <= reach * 1.05 + 1e-6 && r >= inner - 1e-6, "клетка вне зоны: " + r);
                assertTrue(g.strength()[k] >= 0.02f, "слабые клетки отброшены");
                assertEquals(Math.pow(g.strength()[k], FireStorm.COLUMN_POWER), g.column()[k], 1e-5, "столб — сила в степени");
                Vec3 p = new Vec3(d.burst().x + xz[0], d.groundY() + 1, d.burst().z + xz[1]);
                if (d.fluence(p) * Math.exp(-3 * FireStorm.SIGMA_EDGE) >= FireStorm.FULL_Q) {
                    sum += g.strength()[k];
                    expected += FireField.SHARE * (1 - 0.5 * FireStorm.smooth(1.5, 2.5, d.psi(p)));
                    n++;
                }
            }
            assertTrue(n > 200, "клеток, где поджигает всё: " + n);
            assertTrue(sum / expected > 0.8 && sum / expected < 1.25, "средняя сила против закона: " + sum / expected);
            // край зоны рваный: у границы поджига горят не все клетки
            double edgeIn = 0, edgeAll = 0;
            for (int j = 0; j < g.side(); j++) {
                for (int i = 0; i < g.side(); i++) {
                    double[] xz = centre(g, j * g.side() + i);
                    double r = Math.hypot(xz[0], xz[1]);
                    if (r < 0.9 * reach || r > reach) continue;
                    edgeAll++;
                    if (Arrays.binarySearch(g.index(), j * g.side() + i) >= 0) edgeIn++;
                }
            }
            assertTrue(edgeIn > 0.05 * edgeAll && edgeIn < 0.95 * edgeAll, "край: горит " + edgeIn + " из " + edgeAll);
        }
    }

    @Test
    void sourcesAreDeterministicPerDetonation() {
        FireStorm.Grid a = grid(AIR), b = grid(AIR), c = grid(new Detonation(4, AIR.burst(), 64, 15, false, 0, 0.5f, 6, 20_000, 8, 1, false, 1));
        assertArrayEquals(a.index(), b.index());
        assertArrayEquals(a.strength(), b.strength());
        assertArrayEquals(a.start(), b.start());
        assertEquals(a.whirls(), b.whirls());
        assertFalse(Arrays.equals(a.strength(), c.strength()), "у другого зерна — другие районы");
    }

    @Test
    void cellsIgniteNotBeforeTheWave() {
        FireStorm.Grid g = grid(AIR);
        for (int k = 0; k < g.index().length; k++) {
            double[] xz = centre(g, g.index()[k]);
            double wave = AIR.arrivalTicks(Math.hypot(Math.hypot(xz[0], xz[1]), AIR.burst().y - AIR.groundY()));
            assertTrue(g.start()[k] >= wave - 1e-3 && g.start()[k] <= wave + 20 * FireStorm.IGNITE_SPREAD_S + 1, "поджиг " + g.start()[k] + ", волна " + wave);
            assertTrue(g.peak()[k] >= 0.7 * FireStorm.PEAK_S - 1e-3 && g.peak()[k] <= 1.3 * FireStorm.PEAK_S + 1e-3);
        }
    }

    @Test
    void smallScaleKeepsEnoughCells() {
        double reach = new PallField(SMALL, NIGHT).fireReach();
        FireStorm.Grid g = grid(SMALL);
        assertTrue(g.cell() <= 2 * reach / 24 + 1e-9 && g.cell() >= 2, "клетка " + g.cell());
        assertTrue(g.index().length > 100, "клеток " + g.index().length);
        assertTrue(g.whirls().size() >= 1, "и у 1 кт — смерч");
    }

    @Test
    void flameGrowsPeaksAndDiesDown() {
        double peak = FireStorm.PEAK_S;
        assertEquals(0, FireStorm.flame(0, peak), 1e-12);
        assertEquals(FireStorm.START + (1 - FireStorm.START) * (1 - Math.exp(-FireStorm.FLAME_RISE_S / FireStorm.GROW_S)),
                FireStorm.flame(FireStorm.FLAME_RISE_S, peak), 1e-9, "занялось");
        double last = 0;
        for (double t = 1; t <= peak; t += 1) {
            double f = FireStorm.flame(t, peak);
            assertTrue(f >= last - 1e-12, "до пика не стихает: " + t);
            last = f;
        }
        assertTrue(last > 0.95, "к пику — шторм: " + last);
        for (double t = peak + 1; t < peak + 3000; t += 10) {
            double f = FireStorm.flame(t, peak);
            assertTrue(f <= last + 1e-12 && f >= FireStorm.EMBERS * last - 1e-9, "стихает к тлению: " + t);
            last = f;
            assertTrue(FireStorm.smoke(t, peak) >= f, "дым — дольше пламени");
        }
        assertEquals(FireStorm.EMBERS + (1 - FireStorm.EMBERS) * Math.exp(-1), FireStorm.flame(peak + FireStorm.DECAY_S, peak)
                / (FireStorm.START + (1 - FireStorm.START) * (1 - Math.exp(-(peak + FireStorm.DECAY_S) / FireStorm.GROW_S))), 1e-9);
    }

    @Test
    void mapIsTheLawAndGoesOutByMorning() {
        FireStorm.Grid g = grid(AIR);
        double end = 20 * 60 * 15;
        float[] map = new float[g.side() * g.side() * FireStorm.CHANNELS];
        double ticks = 20 * 300;
        FireStorm.fill(g, ticks, end, null, map);
        boolean[] cell = new boolean[g.side() * g.side()];
        for (int k = 0; k < g.index().length; k++) {
            int c = g.index()[k];
            cell[c] = true;
            double age = (ticks - g.start()[k]) / 20;
            assertEquals(g.strength()[k] * FireStorm.flame(age, g.peak()[k]), map[4 * c], 1e-5);
            assertEquals(g.column()[k] * FireStorm.smoke(age, g.peak()[k]), map[4 * c + 1], 1e-5);
            assertTrue(map[4 * c + 2] <= map[4 * c + 1] + 1e-7, "подъём — доля подачи");
        }
        for (int c = 0; c < cell.length; c++) if (!cell[c]) assertEquals(0, map[4 * c] + map[4 * c + 1] + map[4 * c + 2], 0, "вне зоны пусто");
        // до волны — ничего, к утру — гаснет всё
        FireStorm.fill(g, 0, end, null, map);
        for (float v : map) assertEquals(0, v, 0);
        FireStorm.fill(g, end, end, null, map);
        for (float v : map) assertEquals(0, v, 0);
        assertEquals(1, FireStorm.morning(end - PallField.HAZE_FADE_TICKS - 1, end), 1e-12);
        assertEquals(0.5, FireStorm.morning(end - PallField.HAZE_FADE_TICKS / 2.0, end), 1e-12);
    }

    @Test
    void waterIceAndSnowThatTheClientKnowsDoNotBurn() throws InterruptedException {
        FireStorm s = new FireStorm(AIR, new PallField(AIR, NIGHT));
        for (int i = 0; i < 600 && s.grid() == null; i++) Thread.sleep(10);
        FireStorm.Grid g = s.grid();
        assertNotNull(g);
        // к западу от эпицентра — море, у самого эпицентра — не знаем, к востоку — суша
        FireStorm.Surface surface = (x, z) -> Math.abs(x - AIR.burst().x) < 100 ? -1 : x < AIR.burst().x ? 1 : 0;
        for (int i = 0; i <= g.index().length / FireStorm.SURVEY_CELLS; i++) s.survey(surface, 0);
        assertEquals(0, s.dryAt(AIR.burst().x - 500, AIR.burst().z), 0);
        assertEquals(1, s.dryAt(AIR.burst().x + 500, AIR.burst().z), 0);
        assertEquals(1, s.dryAt(AIR.burst().x + 5, AIR.burst().z), 0, "не знаем, под эпицентром суша — горит, как в модели");
        // игрок ушёл, чанки выгрузились: увиденное море остаётся морем
        for (int i = 0; i <= g.index().length / FireStorm.SURVEY_CELLS; i++) s.survey((x, z) -> -1, 0);
        assertEquals(0, s.dryAt(AIR.burst().x - 500, AIR.burst().z), 0, "видели море — не горит и потом");
        // под эпицентром море: где не знаем ничего, не горит (подрыв над морем вдали)
        FireStorm sea = new FireStorm(AIR, new PallField(AIR, NIGHT));
        for (int i = 0; i < 600 && sea.grid() == null; i++) Thread.sleep(10);
        for (int i = 0; i <= g.index().length / FireStorm.SURVEY_CELLS; i++) sea.survey(surface, 1);
        assertEquals(0, sea.dryAt(AIR.burst().x + 5, AIR.burst().z), 0, "не знаем, под эпицентром море — не горит");
        assertEquals(1, sea.dryAt(AIR.burst().x + 500, AIR.burst().z), 0, "суша, которую знаем, горит");
        // ночь подрыва — утро через 5 минут: карта — на 3-й минуте
        assertTrue(s.refresh(20 * 180));
        float[] map = s.map();
        double half = g.side() * g.cell() / 2, west = 0, east = 0, middle = 0;
        for (int c = 0; c < g.side() * g.side(); c++) {
            double x = (c % g.side() + 0.5) * g.cell() - half;
            double v = map[4 * c] + map[4 * c + 1];
            if (x < -100 - g.cell()) west += v;
            else if (x > 100 + g.cell()) east += v;
            else if (Math.abs(x) < 100 - g.cell()) middle += v;
        }
        assertEquals(0, west, 0, "море не горит");
        assertTrue(east > 100 && middle > 0, "суша и неизвестное горят: " + east + ", " + middle);
    }

    @Test
    void noStormWhereTheServerStartsNoFires() {
        assertEquals(0, new PallField(AIR.withFires(false), NIGHT).fireReach(), 0, "ни мглы над пожарами, ни зарева");
        FireStorm off = new FireStorm(AIR.withFires(false), new PallField(AIR, NIGHT));
        assertEquals(0, off.gate(), 0, "с первого кадра");
        assertEquals(0, off.trust(), 0);
        FireStorm on = new FireStorm(AIR, new PallField(AIR, NIGHT));
        assertEquals(1, on.gate(), 0);
        assertEquals(1, on.trust(), 0);
        on.seen(0);
        assertEquals(0, on.trust(), 0, "в своих чанках модель ждала огня, а его нет");
        on.seen(0.15);
        assertEquals(0.5, on.trust(), 1e-12);
    }

    @Test
    void whirlsAreRareShortAndAtTheStrongestFires() {
        for (Detonation d : List.of(AIR, GROUND)) {
            double reach = new PallField(d, NIGHT).fireReach();
            FireStorm.Grid g = grid(d);
            List<FireStorm.Whirl> ws = g.whirls();
            assertTrue(ws.size() >= 2 && ws.size() <= FireStorm.MAX_WHIRLS, "смерчей " + ws.size());
            float[] sorted = g.strength().clone();
            Arrays.sort(sorted);
            float strong = sorted[(int) (sorted.length * 0.97)];
            double busy = 0, last = -1;
            for (int i = 0; i < ws.size(); i++) {
                FireStorm.Whirl w = ws.get(i);
                assertTrue(w.startS() >= last, "по времени");
                last = w.startS();
                double x = w.x() - d.burst().x, z = w.z() - d.burst().z;
                assertTrue(Math.hypot(x, z) <= 0.8 * reach + 1e-6, "в глубине пожара");
                int at = Arrays.binarySearch(g.index(), cellAt(g, x, z));
                assertTrue(at >= 0 && g.strength()[at] >= strong, "у сильного очага: " + (at >= 0 ? g.strength()[at] : -1));
                assertTrue(w.startS() >= g.start()[at] / 20.0 + FireStorm.WHIRL_FIRST_S, "когда шторм в силе");
                assertTrue(w.lengthS() >= 45 && w.lengthS() <= 90, "короткий: " + w.lengthS());
                assertTrue(w.radius() > 3 && w.radius() <= 14 && w.height() >= 90 && w.height() <= 130);
                assertEquals(0, w.strength(w.startS()), 0);
                assertEquals(0, w.strength(w.startS() + w.lengthS()), 0);
                assertEquals(1, w.strength(w.startS() + w.lengthS() / 2), 1e-9);
                for (int j = 0; j < i; j++) {
                    assertTrue(Math.hypot(w.x() - ws.get(j).x(), w.z() - ws.get(j).z()) >= FireStorm.WHIRL_GAP * Math.min(1, reach / 1000) - 1e-6, "порознь");
                }
                busy += w.lengthS();
            }
            // событие, а не обои: вместе — несколько минут на ночь пожара
            assertTrue(busy <= FireStorm.MAX_WHIRLS * 90, "смерчи идут " + busy + " с");
        }
    }

    private static int cellAt(FireStorm.Grid g, double x, double z) {
        double half = g.side() * g.cell() / 2;
        return (int) Math.floor((z + half) / g.cell()) * g.side() + (int) Math.floor((x + half) / g.cell());
    }

    @Test
    void lawsShapeTheColumns() {
        double cell = FireStorm.CELL, hb = FireStorm.BEND_HEIGHT;
        assertEquals(0, FireStorm.bend(-10, hb), 0);
        assertEquals(2 * hb, FireStorm.bend(hb, hb), 1e-9, "на высоте hb снос вдвое больше прямого");
        assertEquals(FireStorm.PILLAR * cell, FireStorm.width(0, cell), 1e-9);
        assertTrue(FireStorm.width(0, cell) < 0.35 * cell, "у земли столбы клеток стоят порознь");
        assertTrue(FireStorm.width(80, cell) > 0.6 * cell, "к 80 блокам — сливаются");
        assertEquals(0, FireStorm.lod(FireStorm.width(0, cell), cell), 1e-9, "у земли — клетка");
        assertEquals(1, FireStorm.lod(cell, cell), 1e-9);
        assertTrue(FireStorm.glowLod(200, cell) > FireStorm.glowLod(50, cell));
        assertEquals(0, FireStorm.column(-40, 260), 1e-12);
        assertEquals(1, FireStorm.column(0.3 * 260, 260), 1e-12);
        assertEquals(0, FireStorm.column(260, 260), 1e-12);
    }

    @Test
    void mapRefreshesAndFollowsRewind() throws InterruptedException {
        PallField pall = new PallField(AIR, NIGHT);
        FireStorm s = new FireStorm(AIR, pall);
        long until = System.nanoTime() + 30_000_000_000L;
        while (s.grid() == null && System.nanoTime() < until) Thread.sleep(20);
        assertNotNull(s.grid(), "сетка в фоне");
        assertTrue(s.refresh(20 * 30));
        int v = s.version();
        assertFalse(s.refresh(20 * 30 + 1), "не каждый кадр");
        assertTrue(s.refresh(20 * 30 + FireStorm.FILL_FAST), "пока огонь молод — часто");
        assertTrue(s.refresh(20 * 10), "перемотка назад — сразу");
        assertEquals(v + 2, s.version());
        assertTrue(s.burning() > 0);
        double late = 20 * 900;
        assertTrue(s.refresh(late));
        assertFalse(s.refresh(late + FireStorm.FILL_FAST), "огонь уже не молод — реже");
        assertTrue(s.refresh(late + FireStorm.FILL_SLOW));
    }
}
