package ua.zentix.incoming.client.nuclear;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Свет в пелене ({@link PallLight}, в шейдере — {@code pallDiffuse}): два потока сохраняют энергию, толстый слой — своего
 * цвета, тонкий светится по толще и не белой стеной, густая пыль внизу — сумерки, тень пелены на земле от тонкой — 1;
 * линейный свет и экран.
 */
class PallLightTest {
    private static final double DUST = PallLight.truncated(PallLight.DUST_G), MIST = PallLight.truncated(PallLight.MIST_G);
    /** Полдень: солнце над головой и небо (свет кадра {@code NukePall.light}, зелёный канал: небо 0,64 на экране). */
    private static final double SKY = PallLight.SKY * PallLight.linear(0.64), SUN = 1 - SKY;

    /** Поток вверх у верха слоя, делённый на π: пучок силы 1 под косинусом mu и небо top. */
    private static double up(double w, double g, double tc, double ground, double mu, double beam, double top) {
        return PallLight.diffuse(w, g, 0, tc, ground, mu, beam, 1, 0, top, 0, 2.0 / 3);
    }

    /** Поток вниз у низа слоя (рассеянный), делённый на π. */
    private static double down(double w, double g, double tc, double ground, double mu, double beam, double top) {
        return PallLight.diffuse(w, g, tc, tc, ground, mu, beam, 1, 0, top, 0, -2.0 / 3);
    }

    /** Без поглощения (ω' = 1) и без земли весь свет пучка и неба выходит из слоя вверх или вниз. */
    @Test
    void conservativeLayerKeepsAllLight() {
        for (double g : new double[]{DUST, MIST}) {
            for (double tc : new double[]{0.01, 0.3, 1, 4, 16, 40}) {
                for (double mu : new double[]{0.12, 0.4, 0.8, 1}) {
                    double out = up(1, g, tc, 0, mu, 1, 0) + down(1, g, tc, 0, mu, 1, 0) + mu * Math.exp(-tc / mu);
                    assertEquals(mu, out, 1e-3 * mu, "пучок: g = " + g + ", τc = " + tc + ", μ = " + mu);
                }
                double out = up(1, g, tc, 0, 1, 0, 1) + down(1, g, tc, 0, 1, 0, 1);
                assertEquals(1, out, 1e-3, "небо: g = " + g + ", τc = " + tc);
            }
        }
    }

    /** Поглощающая пыль теряет свет, и тем больше, чем толще слой. */
    @Test
    void dustAbsorbsMoreTheThickerItIs() {
        double w = PallLight.albedo(PallLight.linear(0.53), DUST), last = 1;
        for (double tc : new double[]{0.1, 0.5, 2, 6, 20}) {
            double out = up(w, DUST, tc, 0, 1, 1, 0) + down(w, DUST, tc, 0, 1, 1, 0) + Math.exp(-tc);
            assertTrue(out < last, "τc = " + tc + ": " + out + " не меньше " + last);
            last = out;
        }
        assertTrue(last < 0.8, "толстая пыль поглощает мало: выходит " + last);
    }

    /** Толстый слой в рассеянном свете неба отражает свой цвет — цвет стены пыли, дыма и брызг (на экране — тот же). */
    @Test
    void thickLayerHasItsColour() {
        for (double r : new double[]{0.24, 0.3, 0.45, 0.53, 0.6, 0.9, 0.93}) {
            double g = r > 0.8 ? MIST : DUST, w = PallLight.albedo(PallLight.linear(r), g);
            double tc = 60 / PallLight.k(w, g);
            assertEquals(r, PallLight.display(up(w, g, tc, 0.2, 1, 0, 1)), 1e-3, "цвет " + r);
        }
    }

    /**
     * Отсвет, что стена пыли на экране прибавляет к свету неба ({@link PallLight#lift}): толстый слой в свете неба base и
     * этом отсвете на экране — ровно цвет × (base + x), как клуб стены ({@code CloudPuffs.smoke}).
     */
    @Test
    void liftedLightShowsAsOnTheWall() {
        for (double r : new double[]{0.45, 0.6, 0.918}) {
            for (double base : new double[]{0.12, 0.17, 0.5, 1}) {
                for (double x : new double[]{0, 0.05, 0.3, 0.8}) {
                    double lit = PallLight.linear(r) * (PallLight.linear(base) + PallLight.lift(base, x));
                    assertEquals(r * (base + x), PallLight.display(lit), 1e-9, "цвет " + r + ", небо " + base + ", отсвет " + x);
                }
            }
            assertEquals(r, PallLight.display(PallLight.linear(r)), 1e-12);
        }
    }

    /**
     * Свет огня ({@link PallLight#flame}): пока отсвет на экране не дошёл до цвета пламени — тот же, что {@link PallLight#lift},
     * дальше — излучение самого пламени, в каждом канале не ярче его.
     */
    @Test
    void fireLightIsNoBrighterThanTheFlames() {
        double[] fire = {1.0, 0.541, 0.18};
        for (double base : new double[]{0.12, 0.17, 0.5, 1}) {
            for (double x = 0; x <= 3; x += 0.01) {
                for (double c : fire) {
                    double light = PallLight.flame(base, x * c, c);
                    assertTrue(light <= PallLight.linear(c) + 1e-12, "небо " + base + ", отсвет " + x + ": ярче пламени " + c);
                    if (PallLight.lift(base, x * c) <= PallLight.linear(c)) assertEquals(PallLight.lift(base, x * c), light, 1e-12);
                }
            }
        }
    }

    /**
     * Густая пыль над пожарами ночью (пелена у земли через минуту после подрыва, как в сценарии {@code pall-low}): свет снизу
     * — пламя под всей пылью (отсвет стены над зоной пожаров на экране — уже цвет огня). Пыль у пола на экране не выше
     * 0,85 (плечо fx_pall: shoulder там ещё оставляет больше половины перепада), и толща клуба между ней и огнём (одна
     * единица) темнит её заметно: клубы видны. Свет, обращённый через цвет пыли (lift без предела), был бы втрое ярче огня: у пола пыль ярче 1 и в
     * тени клуба — тоже, плечо сводило всё к ровному оранжевому.
     */
    @Test
    void dustOverFiresKeepsItsShading() {
        double[] dust = {0.6, 0.53, 0.45}, fire = {1.0, 0.541, 0.18};
        double base = 0.17, glow = CloudPuffs.glowOnSmoke((float) base), sky = PallLight.linear(NukePall.nightSky((float) base, 1)), tc = 6;
        for (int c = 0; c < 3; c++) {
            double w = PallLight.albedo(PallLight.linear(dust[c]), DUST), x = fire[c] * 0.95 * glow / dust[c];
            double bounded = PallLight.flame(base, x, fire[c]), lifted = PallLight.lift(base, x);
            double floor = PallLight.display(w * PallLight.diffuse(w, DUST, tc, tc, PallLight.LAND, 1, 0, 1, 0, sky, bounded, 0));
            double shaded = PallLight.display(w * PallLight.diffuse(w, DUST, tc - 1, tc, PallLight.LAND, 1, 0, 1, 0, sky, bounded, 0));
            assertTrue(floor < 0.85, "канал " + c + ": пыль у пола на экране " + floor);
            assertTrue(shaded < 0.85 * floor, "канал " + c + ": тень клуба " + shaded + " против " + floor);
            if (c == 0) {
                double old = PallLight.display(w * PallLight.diffuse(w, DUST, tc - 1, tc, PallLight.LAND, 1, 0, 1, 0, sky, lifted, 0));
                assertTrue(old > 1, "без предела пыль в тени клуба на экране " + old);
            }
        }
    }

    /**
     * Свет, что слой водяной пыли над морем в полдень шлёт вверх (сверху на него смотрит глаз): по ∫ J·e^(−τ) dτ
     * рассеянного к глазу — растёт с толщей слоя, тонкий светится едва (по толще), толстый — не ярче своего цвета: туман
     * не становится белой стеной.
     */
    @Test
    void thinMistGlowsByItsDepthThickHasItsColour() {
        double w = PallLight.albedo(PallLight.linear(0.918), MIST), last = 0;
        for (double tc : new double[]{0.02, 0.1, 0.5, 2, 8, 40}) {
            double seen = 0;
            int n = 4000;
            for (int i = 0; i < n; i++) {
                double tau = (i + 0.5) / n * tc, j = w * (PallLight.diffuse(w, MIST, tau, tc, PallLight.SEA, 1, SUN, 1, 0, SKY, 0, MIST)
                        + 0.25 * SUN * hg(MIST, -1) * Math.exp(-tau));
                seen += j * Math.exp(-tau) * tc / n;
            }
            assertTrue(seen > last, "τc = " + tc + ": " + seen);
            if (tc <= 0.1) assertTrue(seen < 0.6 * tc, "тонкая водяная пыль светит " + seen + " при толще " + tc);
            assertTrue(seen < 0.95, "τc = " + tc + ": белая стена, " + seen);
            last = seen;
        }
    }

    /**
     * Густая пыль в полдень (видно на 40 блоков, 150 блоков высотой): внизу — бурые сумерки (как «тьма среди дня» после
     * Хиросимы), на экране в разы темнее верха, но не чернота; свет гаснет к земле медленнее прямого луча.
     */
    @Test
    void denseDustIsDuskBelow() {
        double w = PallLight.albedo(PallLight.linear(0.53), DUST), tc = 0.098 * 150 * 0.548;
        double top = w * (PallLight.diffuse(w, DUST, 0, tc, PallLight.LAND, 1, SUN, 1, 0, SKY, 0, 0) + 0.25 * SUN * hg(DUST, 0));
        double bottom = w * (PallLight.diffuse(w, DUST, tc, tc, PallLight.LAND, 1, SUN, 1, 0, SKY, 0, 0) + 0.25 * SUN * hg(DUST, 0) * Math.exp(-tc));
        assertTrue(PallLight.display(bottom) < PallLight.display(top) / 3, "внизу " + bottom + ", вверху " + top);
        assertTrue(PallLight.display(bottom) > 0.06, "внизу чернота: " + PallLight.display(bottom));
        assertTrue(bottom / top > 10 * Math.exp(-tc), "рассеянный свет гаснет как прямой: " + bottom / top);
    }

    /**
     * Тень пелены на земле ({@code shade} в {@code fx_pall_composite}): свет на земле под слоем против неба без него — от
     * тонкого слоя 1, с толщей меньше; водяная пыль, которая не поглощает, затеняет меньше пыли той же толщи.
     */
    @Test
    void groundShadeFadesWithDepth() {
        double wd = PallLight.albedo(PallLight.linear(0.53), DUST), wm = PallLight.albedo(PallLight.linear(0.918), MIST), last = 1.0001;
        for (double tc : new double[]{1e-4, 0.3, 1, 3, 8}) {
            double dust = ground(wd, DUST, tc, PallLight.LAND), mist = ground(wm, MIST, tc, PallLight.SEA);
            if (tc < 1e-3) assertEquals(1, dust, 1e-3);
            assertTrue(dust < last && dust > 0, "τc = " + tc + ": " + dust);
            if (tc > 0.1) assertTrue(mist > dust, "τc = " + tc + ": водяная пыль " + mist + ", пыль " + dust);
            last = dust;
        }
    }

    private static double ground(double w, double g, double tc, double albedo) {
        double mu = 1;
        return (mu * SUN * Math.exp(-tc / mu) + PallLight.diffuse(w, g, tc, tc, albedo, mu, SUN, 1, 0, SKY, 0, -2.0 / 3)) / (mu * SUN + SKY);
    }

    /** Свет тонкой пыли для квадратов слоя — предел слоя без толщи: рассеянное к глазу, в среднем по направлениям. */
    @Test
    void thinLightIsTheThinLimit() {
        double w = PallLight.albedo(PallLight.linear(0.53), DUST), mu = 0.8;
        double i0 = PallLight.diffuse(w, DUST, 0, 1e-6, PallLight.LAND, mu, SUN, 1, 0, SKY, 0, 0);
        assertEquals(PallLight.thin(w, SUN, SKY, mu, PallLight.LAND), w * (i0 + 0.25 * SUN), 2e-3);
    }

    private static double hg(double g, double c) {
        double k = 1 + g * g - 2 * g * c;
        return (1 - g * g) / (k * Math.sqrt(k));
    }
}
