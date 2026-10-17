package ua.zentix.incoming.client.nuclear;

import org.junit.jupiter.api.Test;
import ua.zentix.incoming.nuclear.model.FireballModel;
import ua.zentix.incoming.nuclear.model.MushroomModel;
import ua.zentix.incoming.nuclear.model.WaterModel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MushroomFieldTest {
    /**
     * Рамка гриба (верх и радиус) — числа на всю жизнь гриба: у воздушного, пока фронт не дошёл до земли, юбки нет (её
     * поднимать нечему), и ноль её сечения не делит рамку (NaN выбрасывал гриб из кадра пирамидой видимости).
     */
    @Test
    void boundsAreFiniteBeforeTheFrontReachesTheGround() {
        double high = 4 * FireballModel.maxRadius(15, false);
        for (double t = 0.1; t < MushroomModel.lifeSeconds(15); t += 1.7) {
            for (double front : new double[] {0, 300, 3_000}) {
                MushroomModel.Shape s = MushroomModel.shape(t, 15, high, false, front);
                if (s == null) continue;
                double top = MushroomField.top(s), reach = MushroomField.reach(s);
                assertTrue(Double.isFinite(top) && top >= 1, t + " с, фронт " + front + ": верх " + top);
                assertTrue(Double.isFinite(reach) && reach >= 1, t + " с, фронт " + front + ": радиус " + reach);
                if (front == 0) assertEquals(0, s.skirtTau(), 0, t + " с: юбка до прихода фронта");
            }
        }
    }

    /**
     * Рамка луча — наклонный цилиндр (mushSpan): каждое место тела (ось сдвинута сносом на долю высоты, mushShare, вокруг
     * неё — до reach) лежит в ней на любой высоте от 0,05 верха под землёй до верха, и ниже и выше конца подъёма.
     */
    @Test
    void boundHoldsTheDriftedBody() {
        double reach = 900, drift = 1700;
        for (double[] te : new double[][] {{4000, 3000}, {2500, 3000}, {500, 3000}}) {
            double top = te[0], end = te[1], k = drift / end;
            double r = MushroomField.boundRadius(reach, drift, top, end);
            for (double h = -0.05 * top; h <= top; h += top / 200) {
                double axis = drift * Math.clamp(h / end, 0, 1), tilted = k * h;
                // дальше всего от наклонной оси — место тела на краю reach в сторону расхождения осей
                double worst = Math.abs(axis - tilted) + reach;
                assertTrue(worst <= r + 1e-9, "верх " + top + ", конец " + end + ", высота " + h + ": " + worst + " > " + r);
            }
        }
    }

    /**
     * Рамка со столбом воды держит его стенку и корону с клубами (до {@link MushroomField#REACH_COLUMN} толщины стенки
     * за их край) всю жизнь столба, пока гриб у подрыва 15 кт на воде ещё молод и уже его, и не раздувается: и у низкого
     * столба (первые доли секунды, конец падения) — не шире трёх его радиусов (луч гриба шёл бы по пустому, а толща к
     * свету — мимо шапки).
     */
    @Test
    void boundHoldsTheWaterColumn() {
        for (double t = 0.001; t < 40; t += t < 1 ? 0.01 : 0.37) {
            MushroomModel.Shape s = MushroomModel.shape(t, 15, 0, true, 3_000);
            WaterModel.Column c = WaterModel.column(t, 15);
            if (c == null) continue;
            double r = MushroomField.REACH_COLUMN * MushroomField.columnUnit(c);
            double reach = MushroomField.reach(s, c), top = MushroomField.top(s, c);
            assertTrue(reach >= c.mid() + c.half() + r - 1e-9 && reach >= c.crownR() + r - 1e-9, t + " с: радиус " + reach);
            assertTrue(top >= c.top() + r - 1e-9, t + " с: верх " + top);
            assertTrue(reach >= MushroomField.reach(s) && top >= MushroomField.top(s), t + " с: рамка гриба");
            // и не шире нужного: рамка со столбом — не шире трёх его радиусов и не выше его радиуса над ним
            assertTrue(reach <= Math.max(MushroomField.reach(s), 3 * c.outer()), t + " с: радиус " + reach + " у столба " + c.outer());
            assertTrue(top <= Math.max(MushroomField.top(s), c.top() + c.outer()), t + " с: верх " + top + " у столба " + c.top());
        }
        MushroomModel.Shape s = MushroomModel.shape(5, 15, 0, true, 3_000);
        assertEquals(MushroomField.reach(s), MushroomField.reach(s, null), 0, "без столба — рамка гриба");
    }

    /** Направление тени — единичное, не ниже {@link MushroomField#SHADOW_LOW}; оси плоскости поперёк него — ортонормированы. */
    @Test
    void shadowAxesAreOrthonormal() {
        double[] s = new double[3], a = new double[3], b = new double[3];
        for (double angle = 0; angle < 2 * Math.PI; angle += 0.01) {
            for (double z : new double[] {0, 0.3, -0.95}) {
                MushroomField.shadowSun(-Math.sin(angle), Math.cos(angle), z, s);
                MushroomField.shadowAxes(s, a, b);
                String at = "угол " + angle + ", z " + z;
                assertEquals(1, dot(s, s), 1e-9, at);
                assertTrue(s[1] >= MushroomField.SHADOW_LOW - 1e-12, at);
                assertEquals(1, dot(a, a), 1e-9, at);
                assertEquals(1, dot(b, b), 1e-9, at);
                assertEquals(0, dot(a, s), 1e-9, at);
                assertEquals(0, dot(b, s), 1e-9, at);
                assertEquals(0, dot(a, b), 1e-9, at);
            }
        }
    }

    /**
     * Плитка карты тени держит проекцию рамки гриба поперёк солнца (наклонный цилиндр mushLine: круги радиуса boundR вокруг
     * оси, сдвинутой сносом на долю высоты, от −0,05 верха до верха) с запасом {@link MushroomField#SHADOW_PAD} текселей
     * с каждой стороны: прямые у края плитки проходят мимо гриба, и тень не обрезана краем.
     */
    @Test
    void shadowTileHoldsTheBound() {
        double[] s = new double[3], a = new double[3], b = new double[3], rect = new double[4];
        for (double angle = -1.55; angle <= 1.55; angle += 0.05) {
            MushroomField.shadowSun(-Math.sin(angle), Math.cos(angle), 0, s);
            MushroomField.shadowAxes(s, a, b);
            for (double[] g : new double[][] {{1200, 2500, 3000, 1700, -400}, {600, 400, 3000, 0, 0}, {80, 300, 1, 40, 25}}) {
                double r = g[0], top = g[1], end = g[2], dx = g[3], dz = g[4];
                MushroomField.shadowRect(a, b, r, dx, dz, top, end, rect);
                double kx = dx / Math.max(end, 1), kz = dz / Math.max(end, 1);
                for (int i = 0; i < 2; i++) {
                    double[] e = i == 0 ? a : b;
                    double pad = MushroomField.SHADOW_PAD * rect[2 + i] / MushroomField.SHADOW, min = Double.MAX_VALUE, max = -Double.MAX_VALUE;
                    for (double h = -0.05 * top; h <= top + 1e-9; h += top / 100) {
                        for (double phi = 0; phi < 2 * Math.PI; phi += 0.05) {
                            double u = e[0] * (kx * h + r * Math.cos(phi)) + e[1] * h + e[2] * (kz * h + r * Math.sin(phi));
                            min = Math.min(min, u);
                            max = Math.max(max, u);
                        }
                    }
                    String at = "угол " + angle + ", рамка " + r + "/" + top + ", ось " + i;
                    assertTrue(min >= rect[i] + pad - 1e-6, at + ": " + min + " < " + (rect[i] + pad));
                    assertTrue(max <= rect[i] + rect[2 + i] - pad + 1e-6, at + ": " + max + " > " + (rect[i] + rect[2 + i] - pad));
                }
            }
        }
    }

    private static double dot(double[] x, double[] y) {
        return x[0] * y[0] + x[1] * y[1] + x[2] * y[2];
    }
}
