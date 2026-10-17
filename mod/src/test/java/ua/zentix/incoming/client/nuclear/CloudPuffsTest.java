package ua.zentix.incoming.client.nuclear;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.nuclear.model.BlackBody;
import ua.zentix.incoming.nuclear.model.MushroomModel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CloudPuffsTest {
    /** 15 кт у земли на высоте 64, без ветра, в масштабе 1 блок = 4 м. */
    private static final Detonation GROUND = new Detonation(1, new Vec3(0, 64, 0), 64, 15, true, 0, 0, 0, 20_000, 7, 0.25f, false, Detonation.OLD_WAVE);

    /** Отсвет пожаров под пылью стены у 15 кт — больше минуты: к 70 с — почти в полную силу, к 90 с — больше половины. */
    @Test
    void wallFireGlowLastsMoreThanAMinute() {
        assertTrue(CloudPuffs.glow(30, 15) > 0.99);
        assertTrue(CloudPuffs.glow(70, 15) > 0.75, "70 с: " + CloudPuffs.glow(70, 15));
        assertTrue(CloudPuffs.glow(90, 15) > 0.5, "90 с: " + CloudPuffs.glow(90, 15));
        assertTrue(CloudPuffs.glow(200, 15) < 0.01);
        assertTrue(CloudPuffs.glow(50, 1) < CloudPuffs.glow(50, 15) - 0.3, "у 1 кт заметно короче");
    }

    /**
     * Свет сверху на пыль — шар: свернувшись в кольцо, шапка снаружи смешана с воздухом, и через 20 с у 15 кт её свет
     * в километре от неё — меньше тысячной дня ({@link MushroomModel#light}); погасший шар пыль сверху не освещает — и по
     * числам слота пелены.
     */
    @Test
    void onlyTheFireballLightsTheDustFromAbove() {
        MushroomModel.Shape cap = MushroomModel.shape(20, 15, 0, true, 3_000);
        assertTrue(MushroomModel.light(cap, 15, true, 1_000) < 1e-3, "шапка через 20 с: " + MushroomModel.light(cap, 15, true, 1_000));
        float[] light = new float[8];
        assertTrue(new CloudPuffs(GROUND).pallLight(cap, light));
        double lit = CloudPuffs.ball(luminance(light), light[0], GROUND.blocks(1_000));
        assertTrue(lit < 1e-3, "15 кт через 20 с: свет сверху в километре " + lit);
    }

    /**
     * Свет шара на пыли — тот же источник, что у тела гриба и частиц ({@link MushroomModel#light}): по числам слота
     * ({@link CloudPuffs#ball}) — та же сила на любом расстоянии, цвет — чёрное тело видимой температуры шапки, середина —
     * на её высоте; пожары — до края зоны поджига. Без гриба (до подрыва и после) — нули: отсвета нет.
     */
    @Test
    void pallLightIsTheMushroomLight() {
        CloudPuffs puffs = new CloudPuffs(GROUND);
        float[] light = new float[8];
        double[] bb = new double[4];
        for (double t : new double[]{0.5, 2, 5, 9}) {
            MushroomModel.Shape s = MushroomModel.shape(t, 15, 0, true, 3_000);
            assertTrue(puffs.pallLight(s, light));
            BlackBody.light(MushroomModel.edgeTemperature(t, 15, true), bb);
            for (int c = 0; c < 3; c++) assertEquals(bb[c], light[4 + c], 1e-6 * bb[c] + 1e-30, t + " с, канал " + c);
            assertTrue(light[4] >= light[5] && light[5] >= light[6], t + " с: шар тёплый: " + light[4] + " " + light[5] + " " + light[6]);
            for (double m : new double[]{50, 400, 2_000}) {
                double body = MushroomModel.light(s, 15, true, m), dust = CloudPuffs.ball(bb[3], light[0], GROUND.blocks(m));
                assertEquals(body, dust, 1e-5 * body, t + " с, " + m + " м");
            }
            assertEquals(GROUND.blocks(s.capY()), light[7], 1e-3, t + " с: середина шапки");
            assertEquals(CloudPuffs.glow(t, 15), light[1], 1e-6);
            assertTrue(light[3] > light[0], "край пожаров " + light[3] + " дальше радиуса шапки " + light[0]);
        }
        assertFalse(puffs.pallLight(null, light));
        for (float v : light) assertEquals(0, v);
    }

    private static double luminance(float[] light) {
        return 0.2126 * light[4] + 0.7152 * light[5] + 0.0722 * light[6];
    }
}
