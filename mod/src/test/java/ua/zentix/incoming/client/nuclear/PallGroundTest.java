package ua.zentix.incoming.client.nuclear;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import ua.zentix.incoming.client.far.FarTerrain;
import ua.zentix.incoming.client.far.Sightline;
import ua.zentix.incoming.client.fx.layer.PallPass;
import ua.zentix.incoming.nuclear.Detonation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Рельеф под пеленой ({@link PallGround}): где кончается её луч, за которым нет мира. */
class PallGroundTest {
    /** 15 кт у земли на высоте 109, масштаб 1. */
    private static final Detonation GROUND = new Detonation(1, new Vec3(0, 109, 0), 109, 15, true, 0, 0.5f, 8, 20_000, 7, 1f, false, 0.42f);
    private static final Sightline.Heights UNKNOWN = (x, z) -> Double.NaN;

    private static PallGround ground() {
        return new PallGround(GROUND, new PallField(GROUND, 6000));
    }

    /** Весь круг замеров по {@code heights} и заполнение. */
    private static void surveyAll(PallGround g, Sightline.Heights heights) {
        surveyAll(g, heights, null);
    }

    /** Весь круг замеров по {@code heights} и {@code water} и заполнение. */
    private static void surveyAll(PallGround g, Sightline.Heights heights, FarTerrain.Water water) {
        for (int i = 0; i < PallGround.SIDE * PallGround.SIDE / PallGround.PER_TICK; i++) g.survey(0, heights, water);
        g.fill();
    }

    @Test
    void knowingNothingKeepsTheGroundUnderTheBurst() {
        PallGround g = ground();
        surveyAll(g, UNKNOWN);
        for (double x = -g.half(); x <= g.half(); x += g.half() / 8) {
            for (double z = -g.half(); z <= g.half(); z += g.half() / 8) assertEquals(0, g.at(x, z), 1e-6, x + " " + z);
        }
        PallPass.Ground out = new PallPass.Ground();
        g.frame(out);
        assertEquals(0, out.params.y(), 1e-6);
        assertEquals(0, out.params.z(), 1e-6);
        assertTrue(out.params.x() >= PallGround.MIN_CELL * PallGround.SIDE / 2);
        // и воды: подрыв на суше — суша всюду
        assertFalse(g.hasWater());
        assertTrue(g.hasLand());
        assertEquals(0, g.water(1500, -800), 1e-6);
    }

    /**
     * Ничего не знаем о воде под пеленой — вся она как под эпицентром ({@code NukeWater.under}): подрыв в море — над
     * морем, суши нет (пыли и мглы в таблице не будет). Вода под эпицентром решилась — карта заполняется сразу, не дожидаясь
     * срока: столб и волна уже встали, пелена не должна секунду оставаться пылью.
     */
    @Test
    void unknownWaterIsWhatIsUnderTheBurst() {
        PallField pall = new PallField(GROUND, 6000);
        PallGround g = new PallGround(GROUND, pall);
        surveyAll(g, UNKNOWN);
        int version = g.version();
        pall.water(1, 1);
        g.survey(0, UNKNOWN, null);
        assertEquals(version + 1, g.version(), "вода под эпицентром — сразу");
        assertTrue(g.hasWater());
        assertFalse(g.hasLand());
        for (double x = -g.half(); x <= g.half(); x += g.half() / 4) {
            for (double z = -g.half(); z <= g.half(); z += g.half() / 4) assertEquals(1, g.water(x, z), 1e-6, x + " " + z);
        }
        assertEquals(1, g.waterShare(), 1e-6);
    }

    /**
     * Кадр {@code nuke-water}: подрыв на море, зритель над водой у берега знает только чанки вокруг себя — берег поперёк,
     * суша за спиной. Неизвестное — как ближайшее известное: море между зрителем и подрывом и за подрывом — море (средним
     * всего увиденного оно вышло бы полусушей, и над ним висела бы пыль), берег продолжается в обе стороны (чем дальше, тем
     * шире полоса, где он может быть), суша за спиной — суша.
     */
    @Test
    void coastContinuesAndOpenSeaStaysSea() {
        PallField pall = new PallField(GROUND, 6000);
        pall.water(1, 1);
        PallGround g = new PallGround(GROUND, pall);
        // зритель в (−1500, 0), берег — x = −1540, море к +x
        FarTerrain.Water shore = (x, z) -> Math.hypot(x + 1500, z) < 250 ? (x > -1540 ? 1 : 0) : Double.NaN;
        surveyAll(g, UNKNOWN, shore);
        assertTrue(g.hasWater());
        assertTrue(g.hasLand());
        assertEquals(1, g.water(-1300, 0), 0.02, "известное море");
        assertEquals(0, g.water(-1700, 0), 0.02, "известная суша");
        for (double[] p : new double[][]{{-800, 0}, {0, 0}, {1500, 300}, {-900, 1600}, {-900, -1600}, {2000, -2500}}) {
            assertEquals(1, g.water(p[0], p[1]), 0.02, "море " + p[0] + " " + p[1]);
        }
        for (double[] p : new double[][]{{-3000, 0}, {-2500, 800}, {-2500, -800}, {-5000, 300}}) {
            assertEquals(0, g.water(p[0], p[1]), 0.02, "суша " + p[0] + " " + p[1]);
        }
        // вдоль берега вдали — где он, неизвестно: клетка берега наполовину вода, и её доля расходится клином
        for (double[] p : new double[][]{{-2300, 1600}, {-2300, -1600}}) {
            double w = g.water(p[0], p[1]);
            assertTrue(w > 0.2 && w < 0.8, "берег вдали " + p[0] + " " + p[1] + ": " + w);
        }
    }

    /**
     * Кадр {@code nuke-lake}: подрыв на суше, перед зрителем озеро. Неизвестная суша вокруг остаётся сушей: средним
     * увиденного вода озера разошлась бы по всей карте, и над сушей вместо пыли были бы бледные брызги.
     */
    @Test
    void aLakeByTheViewerDoesNotFloodTheLand() {
        PallField pall = new PallField(GROUND, 6000);
        PallGround g = new PallGround(GROUND, pall);
        // зритель в (0, −1500), озеро 300 × 260 блоков перед ним (к подрыву)
        FarTerrain.Water lake = (x, z) -> Math.hypot(x, z + 1500) >= 400 ? Double.NaN
                : Math.abs(x) < 150 && z > -1480 && z < -1220 ? 1 : 0;
        surveyAll(g, UNKNOWN, lake);
        assertTrue(g.hasWater());
        assertTrue(g.hasLand());
        assertTrue(g.water(0, -1350) > 0.9, "озеро " + g.water(0, -1350));
        for (double[] p : new double[][]{{0, -700}, {0, 0}, {2000, 0}, {0, 2500}, {-2500, -1500}, {0, -2500}}) {
            assertEquals(0, g.water(p[0], p[1]), 0.02, "суша " + p[0] + " " + p[1]);
        }
        assertTrue(g.waterShare() < 0.01, "воды на карте " + g.waterShare());
    }

    /** Под эпицентром карта знает воду сама (чанк у подрыва): известное там — как есть, вокруг — по нему. */
    @Test
    void knownCellsKeepTheirWater() {
        PallField pall = new PallField(GROUND, 6000);
        pall.water(1, 1);
        PallGround g = new PallGround(GROUND, pall);
        // подрыв у самого берега: суша к −x, море к +x, известно в 600 блоках
        FarTerrain.Water coast = (x, z) -> Math.hypot(x, z) < 600 ? (x > 0 ? 1 : 0) : Double.NaN;
        surveyAll(g, UNKNOWN, coast);
        assertEquals(0, g.water(-400, 0), 0.02);
        assertEquals(1, g.water(400, 0), 0.02);
        assertEquals(0, g.water(-3000, 200), 0.02, "берег продолжается");
        assertEquals(1, g.water(3000, 200), 0.02);
        double share = g.waterShare();
        assertTrue(share > 0.4 && share < 0.6, "половина карты — море: " + share);
    }

    /**
     * Камера в долине в 400 блоках от эпицентра на столбе (кадр pall-low): клиент знает только чанки вокруг себя (земля
     * на 46 ниже, чем под эпицентром). Неизвестное заполняется известным вокруг — вдали луч кончается на земле долины,
     * а не на плоскости под эпицентром; у эпицентра (его землю знает сервер) карта поднимается к ней плавно.
     */
    @Test
    void unknownTakesTheKnownAround() {
        PallGround g = ground();
        Sightline.Heights valley = (x, z) -> Math.hypot(x + 400, z) < 192 ? 63 : Double.NaN;
        surveyAll(g, valley);
        assertEquals(-46, g.at(-400, 0), 1);
        for (double[] p : new double[][]{{-2000, 0}, {-1200, 1500}, {-400, -2500}}) assertEquals(-46, g.at(p[0], p[1]), 8, p[0] + " " + p[1]);
        assertTrue(g.at(0, 0) > -40, "у эпицентра — к его земле: " + g.at(0, 0));
        // без ступенек: соседние клетки рядом
        double cell = 2 * g.half() / PallGround.SIDE, worst = 0;
        for (double x = -g.half(); x < g.half() - cell; x += cell) worst = Math.max(worst, Math.abs(g.at(x + cell, 0) - g.at(x, 0)));
        assertTrue(worst < 15, "ступенька " + worst);
    }

    /** Ушёл игрок — чанки выгрузились, клиент больше не знает: карта остаётся, какой её видели. */
    @Test
    void seenCellsStaySeen() {
        PallGround g = ground();
        Sightline.Heights hills = (x, z) -> 80 + 30 * Math.sin(x / 700.0) * Math.cos(z / 900.0);
        surveyAll(g, hills);
        double a = g.at(500, -300), b = g.at(-1500, 2000);
        surveyAll(g, UNKNOWN);
        assertEquals(a, g.at(500, -300), 1e-6);
        assertEquals(b, g.at(-1500, 2000), 1e-6);
    }

    /** Карта заполняется заново, только когда узнали новое, и не чаще раза в {@link PallGround#FILL_TICKS} тиков. */
    @Test
    void refillsOnlyOnNewsAtMostOnceASecond() {
        PallGround g = ground();
        Sightline.Heights hills = (x, z) -> 80 + 30 * Math.sin(x / 700.0) * Math.cos(z / 900.0);
        surveyAll(g, hills);
        int version = g.version();
        for (int i = 0; i < 3 * PallGround.FILL_TICKS; i++) g.survey(0, hills, null);
        assertEquals(version, g.version(), "то же самое — без заполнения");
        g.fill();
        version = g.version();
        Sightline.Heights raised = (x, z) -> hills.at(x, z) + 10;
        for (int i = 1; i < PallGround.FILL_TICKS; i++) g.survey(0, raised, null);
        assertEquals(version, g.version(), "новое раньше срока — ждёт");
        g.survey(0, raised, null);
        assertEquals(version + 1, g.version(), "в срок — заполнена");
    }

    /** Рамка пелены выросла (снос, шлейф) — карта крупнее, известное в ней остаётся. */
    @Test
    void growingKeepsWhatIsKnown() {
        PallGround g = ground();
        Sightline.Heights hills = (x, z) -> 80 + 30 * Math.sin(x / 700.0) * Math.cos(z / 900.0);
        surveyAll(g, hills);
        double half = g.half(), a = g.at(600, 400);
        PallField pall = new PallField(GROUND, 6000);
        double late = 20 * 600;
        assertTrue(pall.extent(late) > half, "через 10 минут пелена шире карты");
        g.survey(late, UNKNOWN, null);
        assertTrue(g.half() >= pall.extent(late), "карта накрывает пелену");
        assertEquals(a, g.at(600, 400), 6, "известное осталось (на клетках крупнее)");
        assertEquals(80 + 30 * Math.sin(600 / 700.0) * Math.cos(400 / 900.0) - 109, g.at(600, 400), 6);
    }
}
