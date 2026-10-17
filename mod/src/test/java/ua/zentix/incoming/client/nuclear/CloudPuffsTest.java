package ua.zentix.incoming.client.nuclear;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.nuclear.model.BlackBody;
import ua.zentix.incoming.nuclear.model.FireballModel;
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
     * Свет сверху на пыль — только шар: когда он начинает гаснуть (70 t_max), видимый край шапки, смешанный с воздухом, в
     * сотни тысяч раз тусклее его (чёрное тело), у любой мощности; погасший шар пыль сверху не освещает.
     */
    @Test
    void onlyTheFireballLightsTheDustFromAbove() {
        for (double y : new double[]{0.1, 15, 1000}) {
            double t = 70 * FireballModel.secondMaximumSeconds(y);
            double edge = BlackBody.luminance(MushroomModel.edgeTemperature(t, y, true));
            assertTrue(edge < 1e-5 * BlackBody.luminance(MushroomModel.fireballTemperature(t, y)), y + " кт: край " + edge);
        }
        float[] light = new float[8];
        assertTrue(new CloudPuffs(GROUND).pallLight(20, light));
        assertEquals(0, light[0], 0, "15 кт через 20 с: свет сверху");
    }

    /**
     * Свет на пыли в слоте пелены: пока шар светит — его свет и цвет сверху с высоты шара над землёй, пожары до края зоны
     * поджига; до подрыва и после гриба — нули (отсвета нет).
     */
    @Test
    void pallLightFollowsTheFireball() {
        CloudPuffs puffs = new CloudPuffs(GROUND);
        float[] light = new float[8];
        assertTrue(puffs.pallLight(2, light));
        assertEquals(CloudPuffs.fireballGlow(2, 15), light[0], 1e-6);
        assertEquals(CloudPuffs.glow(2, 15), light[1], 1e-6);
        assertTrue(light[2] > 0 && light[3] > light[2], "шесть радиусов шара " + light[2] + ", край пожаров " + light[3]);
        assertTrue(light[4] >= light[5] && light[5] >= light[6], "шар тёплый: " + light[4] + " " + light[5] + " " + light[6]);
        assertTrue(light[7] > 0, "шар над землёй");
        assertFalse(puffs.pallLight(0, light));
        assertFalse(puffs.pallLight(CloudPuffs.lifeSeconds(GROUND) + 1, light));
        for (float v : light) assertEquals(0, v);
    }
}
