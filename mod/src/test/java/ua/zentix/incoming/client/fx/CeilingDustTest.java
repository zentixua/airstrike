package ua.zentix.incoming.client.fx;

import org.junit.jupiter.api.Test;
import ua.zentix.incoming.strike.WeaponSpec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CeilingDustTest {
    /** Сила пыли у свода от наземного взрыва оружия w в r блоках. */
    private static double surface(WeaponSpec w, double r) {
        double charge = w.shockCharge();
        return CeilingDust.intensity(CeilingDust.velocity(charge, CeilingDust.coupling(0, charge), r));
    }

    /**
     * Станция в 16 блоках под улицей: свод — в 12–20 блоках под точкой удара. Крылатая ракета над ней — сыплет в полную
     * силу, с несколькими струями; снаряд «Града» туда же — только редкие крупинки, без струй.
     */
    @Test
    void missileOverStationPoursGradTrickles() {
        for (double r = 12; r <= 20; r += 1) {
            double missile = surface(WeaponSpec.MISSILE, r);
            assertTrue(missile >= 0.9, "ракета в " + r + ": " + missile);
            assertTrue(CeilingDust.streams(missile) >= 4, "струй у ракеты в " + r);
            assertTrue(CeilingDust.grains(missile) >= 0.85 * CeilingDust.GRAINS, "крупинок у ракеты в " + r);
        }
        for (double r = 12; r <= 16; r += 1) {
            double grad = surface(WeaponSpec.ROCKET, r);
            assertTrue(grad > 0 && grad < 0.4, "«Град» в " + r + ": " + grad);
            assertEquals(0, CeilingDust.streams(grad), "струй у «Града» в " + r);
            assertTrue(CeilingDust.grains(grad) < CeilingDust.GRAINS / 4, "крупинок у «Града» в " + r);
        }
    }

    /** Дальше дальности заряда пыли нет; ближе — чем дальше, тем слабее; дальности оружия — как в описании класса. */
    @Test
    void rangeByWeaponPower() {
        double surface = CeilingDust.coupling(0, 1);
        assertEquals(79, CeilingDust.range(WeaponSpec.MISSILE.shockCharge(), surface), 2);
        assertEquals(38, CeilingDust.range(WeaponSpec.DRONE.shockCharge(), surface), 2);
        assertEquals(19, CeilingDust.range(WeaponSpec.ROCKET.shockCharge(), surface), 1);
        assertEquals(15, CeilingDust.range(WeaponSpec.LOITER.shockCharge(), surface), 1);
        double bomb = WeaponSpec.BUNKER.shockCharge();
        assertEquals(300, CeilingDust.range(bomb, CeilingDust.coupling(30, bomb)), 10);
        assertEquals(0, CeilingDust.range(WeaponSpec.NUKE.shockCharge(), surface));
        for (WeaponSpec w : new WeaponSpec[]{WeaponSpec.MISSILE, WeaponSpec.DRONE, WeaponSpec.ROCKET, WeaponSpec.LOITER}) {
            double range = CeilingDust.range(w.shockCharge(), surface);
            assertEquals(0, surface(w, range + 0.5), "за дальностью " + range);
            assertTrue(surface(w, range - 2) > 0, "до дальности " + range);
            double prev = 2;
            for (double r = 2; r < range; r += 2) {
                double s = surface(w, r);
                assertTrue(s <= prev, "дальше — не сильнее: " + r);
                prev = s;
            }
        }
        // вдали от ракеты — лишь крупинки
        double far = surface(WeaponSpec.MISSILE, 65);
        assertTrue(far > 0 && far < 0.2 && CeilingDust.streams(far) == 0, "ракета в 65: " + far);
        // заряд больше — пыли больше на том же месте
        assertTrue(surface(WeaponSpec.MISSILE, 30) > surface(WeaponSpec.DRONE, 30));
        assertTrue(surface(WeaponSpec.DRONE, 15) > surface(WeaponSpec.ROCKET, 15));
    }

    /** Доля энергии в грунте: наземный подрыв — 0,14, бомба в 2,4 т глубже 5,4 блока — вся. */
    @Test
    void couplingByDepth() {
        assertEquals(CeilingDust.SURFACE, CeilingDust.coupling(0, 450), 1e-9);
        assertEquals(1, CeilingDust.coupling(30, 2400), 1e-9);
        assertEquals(1, CeilingDust.coupling(5.4, 2400), 0.01);
        double half = CeilingDust.coupling(2.7, 2400);
        assertTrue(half > 0.5 && half < 0.6, "на полпути: " + half);
        assertEquals(0, CeilingDust.coupling(10, 0));
        // бомба в грунте у бункера в 80 блоках — ещё в полную силу
        assertEquals(1, CeilingDust.intensity(CeilingDust.velocity(2400, 1, 80)), 1e-9);
    }

    /** Свод — первая клетка колонки над глазом не выше предела; глаз в блоке или над ним пусто — свода нет. */
    @Test
    void roofFromColumn() {
        assertEquals(3, CeilingDust.roof(i -> i >= 3, CeilingDust.REACH));
        assertEquals(1, CeilingDust.roof(i -> i == 1, CeilingDust.REACH));
        assertEquals(-1, CeilingDust.roof(i -> false, CeilingDust.REACH), "небо");
        assertEquals(-1, CeilingDust.roof(i -> i == CeilingDust.REACH + 1, CeilingDust.REACH), "высокий зал");
        assertEquals(CeilingDust.REACH, CeilingDust.roof(i -> i == CeilingDust.REACH, CeilingDust.REACH));
        assertEquals(-1, CeilingDust.roof(i -> true, CeilingDust.REACH), "глаз в блоке");
    }

    /** Сверху и вровень — да, ниже глаза на этаж — нет. */
    @Test
    void blastAboveOrLevel() {
        assertTrue(CeilingDust.overhead(80, 64));
        assertTrue(CeilingDust.overhead(62.5, 64));
        assertTrue(CeilingDust.overhead(60, 64));
        assertTrue(!CeilingDust.overhead(59, 64));
    }

    /** На удар — не больше пределов при любой силе, слабый — горсть; за время пыли сыплется всё задуманное. */
    @Test
    void capsPerBlast() {
        for (double s = 0; s <= 2; s += 0.05) {
            assertTrue(CeilingDust.grains(s) <= CeilingDust.GRAINS);
            assertTrue(CeilingDust.streams(s) <= CeilingDust.STREAMS);
            assertTrue(CeilingDust.bits(s) <= CeilingDust.BITS);
            assertTrue(CeilingDust.hazePuffs(s) <= CeilingDust.HAZE_PUFFS);
        }
        assertEquals(CeilingDust.GRAINS, CeilingDust.grains(1));
        assertEquals(0, CeilingDust.grains(0));
        assertTrue(CeilingDust.grains(0.05) > 0 && CeilingDust.grains(0.05) < 20);
        // облачков у струй и по своду — в своей группе, и залп из трёх ракет над станцией там помещается
        int puffs = CeilingDust.STREAMS * CeilingDust.STREAM_PUFFS + CeilingDust.HAZE_PUFFS;
        assertTrue(3 * puffs <= ua.zentix.incoming.client.fx.particle.FxBudget.DUST.limit(), puffs + " на удар");
        for (double tau : new double[]{CeilingDust.STREAM_TAU, CeilingDust.HAZE_TAU}) {
            double sum = 0;
            for (int k = 0; k < CeilingDust.POUR_TICKS; k++) sum += CeilingDust.share(k, tau);
            assertEquals(1, sum, 1e-9);
            assertEquals(0, CeilingDust.share(CeilingDust.POUR_TICKS, tau));
        }
    }

    /** Живых крупинок у всех ударов — не больше предела, рождённые дольше жизни назад место освобождают. */
    @Test
    void capInTotal() {
        CeilingDust.Births b = new CeilingDust.Births(CeilingDust.GRAIN_LIFE, CeilingDust.GRAIN_CAP);
        int born = 0;
        // залп: четыре ракеты подряд по полной, каждая — за 20 тиков
        for (int t = 0; t < 80; t++) {
            born += b.take(CeilingDust.GRAINS / 20 * 4);
            assertTrue(b.live() <= CeilingDust.GRAIN_CAP);
            b.tick();
        }
        assertEquals(CeilingDust.GRAIN_CAP, born);
        assertEquals(0, b.take(10));
        for (int t = 0; t < CeilingDust.GRAIN_LIFE; t++) b.tick();
        assertEquals(0, b.live());
        assertEquals(10, b.take(10));
        b.reset();
        assertEquals(0, b.live());
    }
}
