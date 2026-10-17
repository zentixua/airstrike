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
}
