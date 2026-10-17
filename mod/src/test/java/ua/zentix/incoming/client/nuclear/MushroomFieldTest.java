package ua.zentix.incoming.client.nuclear;

import org.junit.jupiter.api.Test;
import ua.zentix.incoming.nuclear.model.FireballModel;
import ua.zentix.incoming.nuclear.model.MushroomModel;

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
}
