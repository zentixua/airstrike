package ua.zentix.incoming.client.nuclear;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import ua.zentix.incoming.client.fx.layer.WaterDecal;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.nuclear.model.BlastModel;
import ua.zentix.incoming.nuclear.model.WaterModel;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WaterPuffsTest {
    /** Наземный подрыв 15 кт на воде (гладь — 63), масштаб 1: блоки = метры. */
    private static final Detonation SEA = new Detonation(1, new Vec3(0.5, 63, 0.5), 63, 15, true, 0, 0, 0, 20_000, 7, 1f, false, 0.5f);

    private static List<double[]> sprites(WaterPuffs w, double t) {
        List<double[]> out = new ArrayList<>();
        w.sprites(t, 1f, SEA.groundY(), (x, y, z, size, rot, r, g, b, a, gr, gg, gb, ga, tex) -> out.add(new double[]{x, y, z, size, a}));
        return out;
    }

    /** Сухой подрыв (на суше или высоко) — ни столба, ни тумана, ни волны. */
    @Test
    void dryBurstHasNothing() {
        WaterPuffs w = new WaterPuffs(SEA, 450);
        assertArrayEquals(new int[3], w.visible(2));
        assertTrue(sprites(w, 2).isEmpty());
        assertEquals(0, WaterPuffs.count(new Detonation(2, new Vec3(0, 63 + 600, 0), 63, 15, false, 0, 0, 0, 20_000, 7, 1f, false, 0.5f)),
                "воздушный подрыв в 600 м — не водяной");
    }

    /**
     * По времени «Бейкера» (в масштабе 15 кт): в первую секунду — кольцо тумана и встающий столб; к 6 с туман сошёл,
     * столб стоит; базисная волна — когда вода столба падает, и держится минутами; упавший столб не виден.
     */
    @Test
    void phasesFollowTheModel() {
        WaterPuffs w = new WaterPuffs(SEA, 450);
        w.wet(1);
        assertArrayEquals(new int[3], w.visible(0.15), "в первые доли секунды — только шар");
        int[] early = w.visible(1.5);
        assertTrue(early[0] > 0 && early[1] > 0 && early[2] == 0, "1,5 с: столб и туман без волны");
        int[] six = w.visible(6);
        assertTrue(six[0] > 0 && six[1] == 0 && six[2] == 0, "6 с: только столб");
        int[] minute = w.visible(60);
        assertTrue(minute[0] == 0 && minute[2] > 0, "минута: столб упал, волна идёт");
        assertTrue(w.visible(400)[2] > 0, "волна держится минутами");
    }

    /** Места: столб — у эпицентра и не выше своего верха; волна — внутри своего радиуса и не выше своей высоты с клубом. */
    @Test
    void puffsStayInsideTheirShapes() {
        WaterPuffs w = new WaterPuffs(SEA, 450);
        w.wet(1);
        double col = WaterModel.columnRadius(15), top = WaterModel.columnTop(6, 15);
        for (double[] p : sprites(w, 6)) {
            assertTrue(Math.hypot(p[0] - 0.5, p[2] - 0.5) < 2.2 * col, "столб: " + Math.hypot(p[0], p[2]));
            assertTrue(p[1] - SEA.groundY() < top * 1.2, "столб выше верха: " + (p[1] - SEA.groundY()));
            assertTrue(p[1] > SEA.groundY() - 1, "столб под водой");
        }
        double surge = WaterModel.surgeRadius(120, 15), height = WaterModel.surgeHeight(120, 15);
        List<double[]> s = sprites(w, 120);
        assertTrue(s.size() > 50);
        for (double[] p : s) {
            assertTrue(Math.hypot(p[0] - 0.5, p[2] - 0.5) <= surge * 1.001, "волна дальше радиуса");
            assertTrue(p[1] - SEA.groundY() <= height + p[3], "волна выше высоты с клубом");
        }
    }

    /** Брызги — белые, а не бурые: зелёный и синий не меньше красного. */
    @Test
    void sprayIsWhite() {
        for (int rgb : new int[]{WaterPuffs.SPRAY, WaterPuffs.MIST, WaterPuffs.SURGE}) {
            int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
            assertTrue(g >= r && b >= r && r > 0xC0, Integer.toHexString(rgb));
        }
    }

    /**
     * Профиль тёмного кольца: у фронта — темнота ряби по ветру там, за фронтом — светлее; пена — только у эпицентра
     * (ветер сильнее 25 м/с) и не на самом фронте.
     */
    @Test
    void ringProfileIsDarkestAtTheFront() {
        float[] dark = new float[WaterDecal.SAMPLES], foam = new float[WaterDecal.SAMPLES];
        double ticks = 400, r = SEA.frontRadius(ticks);
        assertTrue(NukeWater.profile(SEA, ticks, r, 0, dark, foam));
        double wind = BlastModel.windSpeed(BlastModel.overpressureKpa(r, 15));
        assertEquals(WaterModel.darkness(wind, 0, NukeWater.BAND, NukeWater.CALM), dark[0], 0.02);
        assertTrue(dark[0] > 0.3, "у фронта в " + Math.round(r) + " м темно: " + dark[0]);
        assertTrue(dark[WaterDecal.SAMPLES / 2] < dark[0]);
        double late = 0;
        for (float f : foam) late = Math.max(late, f);
        assertTrue(late < 0.15, "в 3 км пена у эпицентра уже сошла: " + late);
        // через 5 с фронт в ~1,2 км, ветер за ним ~80 м/с: за фронтом пена
        assertTrue(NukeWater.profile(SEA, 100, SEA.frontRadius(100), 0, dark, foam));
        assertEquals(0, foam[0], 1e-6, "на самом фронте пены ещё нет");
        double max = 0;
        for (float f : foam) max = Math.max(max, f);
        assertTrue(max > 0.4, "за фронтом пена: " + max);
        // в 10 км ветер слабый: кольцо бледное, пены нет
        double far = SEA.arrivalTicks(10_000);
        assertTrue(NukeWater.profile(SEA, far, 10_000, 0, dark, foam));
        assertTrue(dark[0] < 0.25 && dark[0] > 0.02, "в 10 км: " + dark[0]);
        assertEquals(0, foam[0], 1e-6);
    }

    /** Выборки профиля гуще у фронта: первая — на нём, последняя — у центра. */
    @Test
    void profileSamplesAreDenseAtTheFront() {
        double length = 3000;
        assertEquals(0, WaterDecal.sampleAt(0, length));
        assertEquals(length, WaterDecal.sampleAt(WaterDecal.SAMPLES - 1, length), 1e-9);
        assertTrue(WaterDecal.sampleAt(1, length) < 1, "у фронта шаг меньше блока");
        double prev = -1;
        for (int i = 0; i < WaterDecal.SAMPLES; i++) {
            double x = WaterDecal.sampleAt(i, length);
            assertTrue(x > prev);
            prev = x;
        }
    }

    /** Догадка без данных: первый воздух над землёй ровно на уровне моря — море, выше — суша. */
    @Test
    void seaLevelGuess() {
        assertEquals(1, NukeWater.guess(63, 63));
        assertEquals(0, NukeWater.guess(64, 63));
        assertEquals(0, NukeWater.guess(70.5, 63));
    }
}
