package ua.zentix.incoming.client.nuclear;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import ua.zentix.incoming.client.fx.layer.WaterDecal;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.nuclear.model.BlastModel;
import ua.zentix.incoming.nuclear.model.WaterModel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NukeWaterTest {
    /** Наземный подрыв 15 кт на воде (гладь — 63), масштаб 1: блоки = метры. */
    private static final Detonation SEA = new Detonation(1, new Vec3(0.5, 63, 0.5), 63, 15, true, 0, 0, 0, 20_000, 7, 1f, false, 0.5f);

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

    /** Самый дальний передний край клубов гриба и стены в кадре, блоков от эпицентра по горизонтали. */
    private static double farthestEdge(CloudPuffs puffs, double t) {
        double[] most = {0};
        puffs.sprites(t, 1, 1, (x, y, z, size, rot, r, g, b, a, gr, gg, gb, ga, tex) -> {
            if (a > 0.01) most[0] = Math.max(most[0], Math.hypot(x - SEA.burst().x, z - SEA.burst().z) + 0.3 * size);
        });
        return most[0];
    }

    /**
     * Стена над водой — брызги позади тёмного кольца: её передний край отстаёт от головы стены на путь фронта за
     * последние {@link WaterModel#SPRAY_LAG} (капли встают не сразу), а стена пыли над сушей идёт с головой.
     */
    @Test
    void sprayWallTrailsTheDarkRing() {
        for (double t : new double[]{3, 6}) {
            CloudPuffs dry = new CloudPuffs(SEA, 750, false), wet = new CloudPuffs(SEA, 750, false);
            wet.burst(1, 0);
            double head = dry.wall(t).head(), lag = head - dry.wall(t - WaterModel.SPRAY_LAG).head();
            double dryEdge = farthestEdge(dry, t), wetEdge = farthestEdge(wet, t);
            assertTrue(lag > 30, t + " с: путь фронта за время подъёма брызг " + lag);
            assertTrue(dryEdge > head - 0.25 * lag, t + " с: пыль с головой: край " + dryEdge + ", голова " + head);
            assertTrue(wetEdge < head - 0.7 * lag, t + " с: брызги позади кольца: край " + wetEdge + ", голова " + head + ", отставание " + lag);
        }
    }

    /**
     * Догадка без данных: первый воздух над землёй ровно на уровне моря — море, выше — суша; гладь моря с прицела
     * (луч с жидкостью: верх воды на 8/9 блока, 62,889) — тоже море.
     */
    @Test
    void seaLevelGuess() {
        assertEquals(1, NukeWater.guess(63, 63));
        assertEquals(1, NukeWater.guess(62 + 8.0 / 9.0, 63), "гладь моря с прицела");
        assertEquals(0, NukeWater.guess(64, 63));
        assertEquals(0, NukeWater.guess(70.5, 63));
        assertEquals(0, NukeWater.guess(61, 63), "дно ниже моря — не догадка «море»");
    }

    /**
     * Блок поверхности под эпицентром: у целой высоты (первый воздух над землёй) — под ним, у глади с прицела (62,889)
     * — сам блок воды, а не блок под ним (иначе мелкая вода — суша, без столба, тумана и волны).
     */
    @Test
    void surfaceBlockUnderTheBurst() {
        assertEquals(62, NukeWater.surfaceBlock(63));
        assertEquals(62, NukeWater.surfaceBlock(62 + 8.0 / 9.0));
        assertEquals(63, NukeWater.surfaceBlock(64));
        assertEquals(70, NukeWater.surfaceBlock(70.5), "верх полублока — сам полублок");
        assertEquals(-1, NukeWater.surfaceBlock(-0.11));
    }

    /**
     * Догадка о воде под эпицентром держится, пока подрыв на клиенте молод по своим тикам от прихода пакета: у
     * вошедшего посреди подрыва (или в повторе) ей дольше ждать чанков, а известное наверняка решено сразу.
     */
    @Test
    void guessSettlesByOwnAge() {
        var guess = new NukeWater.Under(1, NukeWater.Source.SEA_LEVEL);
        assertTrue(NukeWater.settles(new NukeWater.Under(0, NukeWater.Source.CHUNK), 0, false), "чанк — сразу");
        assertTrue(NukeWater.settles(new NukeWater.Under(1, NukeWater.Source.TILES), 0, true), "плитка — сразу");
        assertTrue(!NukeWater.settles(guess, NukeWater.GUESS_TICKS, true));
        assertTrue(NukeWater.settles(guess, NukeWater.GUESS_TICKS + 1, true));
        assertTrue(!NukeWater.settles(guess, NukeWater.GUESS_TICKS + 1, false), "пришедший прошлым ждёт дольше");
        assertTrue(NukeWater.settles(guess, NukeWater.GUESS_TICKS_PAST + 1, false));
    }
}
