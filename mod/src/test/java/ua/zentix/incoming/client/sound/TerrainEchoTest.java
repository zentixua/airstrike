package ua.zentix.incoming.client.sound;

import org.junit.jupiter.api.Test;
import ua.zentix.incoming.client.far.Sightline;
import ua.zentix.incoming.nuclear.model.FrontProfile;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Эхо от рельефа: откуда, когда и насколько тише прямого удара. */
class TerrainEchoTest {
    private static final double C = FrontProfile.SOUND_SPEED;
    /** Взрыв, фронт которого идёт со скоростью звука, давление — как 1/r. */
    private static final TerrainEcho.Blast SOUND = new TerrainEcho.Blast() {
        @Override
        public double arrivalTicks(double blocks) {
            return blocks / C;
        }

        @Override
        public double pressure(double blocks) {
            return 1 / Math.max(1, blocks);
        }
    };

    /** Равнина на 64 и обрыв на востоке: подъём на 100 блоков между x0 и x0 + 200. */
    private static Sightline.Heights cliff(double x0) {
        return (x, z) -> x < x0 ? 64 : x < x0 + 200 ? 64 + (x - x0) / 2 : 164;
    }

    private static List<TerrainEcho.Reflection> east(Sightline.Heights h, double bx, double by) {
        List<TerrainEcho.Reflection> out = new ArrayList<>();
        TerrainEcho.ray(h, 0, TerrainEcho.RAYS, TerrainEcho.RANGE, 0, 66, 0, bx, by, 0, SOUND, out);
        return out;
    }

    private static double total(List<TerrainEcho.Reflection> list) {
        return list.stream().mapToDouble(TerrainEcho.Reflection::energy).sum();
    }

    @Test
    void cliffBehindTheListenerEchoesWithTheGeometricDelay() {
        // взрыв в 3 км на западе, обрыв в 400–600 блоках на востоке: эхо приходит с востока
        List<TerrainEcho.Reflection> all = TerrainEcho.trace(cliff(400), 0, 66, 0, -3000, 65, 0, SOUND);
        TerrainEcho.Reflection top = all.stream().max(Comparator.comparingDouble(TerrainEcho.Reflection::energy)).orElseThrow();
        assertEquals(0, EchoMix.wrap(top.azimuth() + 0.3) - 0.3, 0.1, "сильнейшее эхо — с востока");
        assertTrue(top.x() >= 400 && top.x() <= 600, "со склона: " + top.x());
        double r1 = Math.sqrt(Math.pow(top.x() + 3000, 2) + Math.pow(top.y() - 65, 2) + top.z() * top.z());
        double r2 = Math.sqrt(top.x() * top.x() + Math.pow(top.y() - 66, 2) + top.z() * top.z());
        double re = Math.sqrt(3000 * 3000 + 1);
        assertEquals((r1 + r2 - re) / C, top.delay(), 1e-6, "задержка — лишний путь со скоростью звука");
        assertEquals(r1 + r2 - re, top.extraPath(), 1e-6);
        assertEquals(2 * top.x(), top.extraPath(), 20, "за ухом лишний путь — туда и обратно");
        assertTrue(top.energy() > 1e-3 && top.energy() <= Math.pow(10, TerrainEcho.CAP_DB / 10) + 1e-9, "энергия " + top.energy());
    }

    @Test
    void delayFollowsTheGameFrontNotJustTheSoundSpeed() {
        // фронт игры у эпицентра медленнее звука: у склона он на 30 тиков позже, чем у уха по звуку
        TerrainEcho.Blast late = new TerrainEcho.Blast() {
            @Override
            public double arrivalTicks(double blocks) {
                return blocks / C + (blocks > 3200 ? 30 : 0);
            }

            @Override
            public double pressure(double blocks) {
                return 1 / blocks;
            }
        };
        List<TerrainEcho.Reflection> out = new ArrayList<>();
        TerrainEcho.ray(cliff(400), 0, TerrainEcho.RAYS, TerrainEcho.RANGE, 0, 66, 0, -3000, 65, 0, late, out);
        List<TerrainEcho.Reflection> plain = east(cliff(400), -3000, 65);
        assertFalse(out.isEmpty());
        for (TerrainEcho.Reflection r : out) {
            TerrainEcho.Reflection same = plain.stream().filter(p -> p.x() == r.x()).findFirst().orElseThrow();
            assertEquals(same.delay() + 30, r.delay(), 1e-6);
        }
    }

    @Test
    void flatGroundBarelyEchoesASurfaceBurst() {
        // ровная земля: фронт скользит вдоль неё и почти не отражается назад
        List<TerrainEcho.Reflection> all = TerrainEcho.trace((x, z) -> 64, 0, 66, 0, -3000, 64, 0, SOUND);
        assertTrue(total(all) < 1e-3, "сумма " + total(all));
        List<TerrainEcho.Reflection> wall = TerrainEcho.trace(cliff(400), 0, 66, 0, -3000, 64, 0, SOUND);
        assertTrue(total(wall) > 100 * Math.max(total(all), 1e-9), "обрыв громче ровной земли");
    }

    @Test
    void fartherCliffIsWeakerAndLater() {
        double near = total(east(cliff(400), -3000, 65)), far = total(east(cliff(1600), -3000, 65));
        assertTrue(near > 0 && far > 0);
        assertTrue(far < near, "дальний обрыв тише: " + far + " против " + near);
        double nearDelay = east(cliff(400), -3000, 65).get(0).delay(), farDelay = east(cliff(1600), -3000, 65).get(0).delay();
        assertTrue(farDelay > nearDelay + 100, "и позже: " + farDelay + " против " + nearDelay);
    }

    @Test
    void cliffHiddenFromTheBurstIsWeaker() {
        // гребень между взрывом и обрывом: склон получает фронт обходом
        Sightline.Heights open = cliff(400);
        Sightline.Heights ridge = (x, z) -> x > -1700 && x < -1300 ? 400 : open.at(x, z);
        double lit = total(east(open, -3000, 65)), shadowed = total(east(ridge, -3000, 65));
        assertTrue(shadowed > 0 && shadowed < 0.5 * lit, "в тени " + shadowed + " против " + lit);
    }

    @Test
    void slopeFacingAwayDoesNotEcho() {
        // спуск к востоку (склон от взрыва и от уха вниз) не возвращает удар
        Sightline.Heights down = (x, z) -> x < 400 ? 164 : x < 600 ? 164 - (x - 400) / 2.0 : 64;
        List<TerrainEcho.Reflection> out = new ArrayList<>();
        TerrainEcho.ray(down, 0, TerrainEcho.RAYS, TerrainEcho.RANGE, 0, 166, 0, -3000, 165, 0, SOUND, out);
        assertTrue(total(out) < 1e-4, "сумма " + total(out));
    }

    @Test
    void stepGrowsWithDistanceWithinBounds() {
        assertEquals(TerrainEcho.STEP_MIN, TerrainEcho.step(30), 1e-9);
        assertEquals(TerrainEcho.STEP_MAX, TerrainEcho.step(4000), 1e-9);
        assertTrue(TerrainEcho.step(1000) > TerrainEcho.step(500));
    }

    @Test
    void unknownTerrainGivesNoEcho() {
        assertTrue(TerrainEcho.trace((x, z) -> Double.NaN, 0, 66, 0, -3000, 65, 0, SOUND).isEmpty());
    }
}
