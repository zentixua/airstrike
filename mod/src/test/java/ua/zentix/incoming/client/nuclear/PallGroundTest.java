package ua.zentix.incoming.client.nuclear;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import ua.zentix.incoming.client.far.Sightline;
import ua.zentix.incoming.client.fx.layer.PallPass;
import ua.zentix.incoming.nuclear.Detonation;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        for (int i = 0; i < PallGround.SIDE * PallGround.SIDE / PallGround.PER_TICK; i++) g.survey(0, heights);
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
        int version = g.version();
        surveyAll(g, UNKNOWN);
        assertEquals(a, g.at(500, -300), 1e-6);
        assertEquals(b, g.at(-1500, 2000), 1e-6);
        assertTrue(g.version() > version, "заполнение по запросу — новая версия");
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
        g.survey(late, UNKNOWN);
        assertTrue(g.half() >= pall.extent(late), "карта накрывает пелену");
        assertEquals(a, g.at(600, 400), 6, "известное осталось (на клетках крупнее)");
        assertEquals(80 + 30 * Math.sin(600 / 700.0) * Math.cos(400 / 900.0) - 109, g.at(600, 400), 6);
    }
}
