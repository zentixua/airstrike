package ua.zentix.incoming.client.nuclear;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.nuclear.model.MushroomModel;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CloudPuffsTest {
    /** Ночное небо: столько света неба остаётся на дыме (нижний предел в рендерере). */
    private static final float NIGHT = 0.12f;

    private static double luminance(float[] c) {
        return 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2];
    }

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
     * в километре от неё — меньше тысячной дня ({@link MushroomModel#light}); погасший шар пыль сверху не освещает.
     */
    @Test
    void onlyTheFireballLightsTheDustFromAbove() {
        MushroomModel.Shape cap = MushroomModel.shape(20, 15, 0, true, 3_000);
        assertTrue(MushroomModel.light(cap, 15, true, 1_000) < 1e-3, "шапка через 20 с: " + MushroomModel.light(cap, 15, true, 1_000));
        float[] light = new float[8];
        assertTrue(new CloudPuffs(GROUND, 750, false).pallLight(20, light));
        assertEquals(0, light[0], 0, "15 кт через 20 с: свет сверху");
    }

    /** 15 кт у земли на высоте 64, без ветра, в масштабе 1 блок = 4 м. */
    private static final Detonation GROUND = new Detonation(1, new Vec3(0, 64, 0), 64, 15, true, 0, 0, 0, 20_000, 7, 0.25f, false, Detonation.OLD_WAVE);

    /** Клуб кадра: центр и размер, блоки. */
    private record Seen(double x, double y, double z, double size) {}

    private static List<Seen> frame(CloudPuffs puffs, double t) {
        List<Seen> out = new ArrayList<>();
        puffs.sprites(t, 1, 1, (x, y, z, size, rot, r, g, b, a, gr, gg, gb, ga, tex) -> out.add(new Seen(x, y, z, size)));
        return out;
    }

    /**
     * Пыль у земли стоит на рельефе под собой: на плато в 136 блоках над эпицентром стена и шлейф поднимаются ровно
     * на него, облако Вильсона остаётся над эпицентром; передний край стены — на голове ({@link CloudPuffs#wall}), не дальше.
     */
    @Test
    void groundDustStandsOnTerrain() {
        for (double t : new double[]{2, 6, 20}) {
            CloudPuffs onPlateau = new CloudPuffs(GROUND, 750, false), flat = new CloudPuffs(GROUND, 750, false);
            onPlateau.track(t, (x, z) -> 200, 0, 0, 1e6);
            List<Seen> raised = frame(onPlateau, t), level = frame(flat, t);
            assertEquals(level.size(), raised.size(), t + " с");
            double head = onPlateau.wall(t).head(), edge = 0;
            int lifted = 0;
            for (int i = 0; i < raised.size(); i++) {
                Seen a = raised.get(i), b = level.get(i);
                assertEquals(b.x, a.x, 1e-9);
                assertEquals(b.z, a.z, 1e-9);
                double dy = a.y - b.y;
                assertTrue(Math.abs(dy) < 1e-6 || Math.abs(dy - 136) < 1e-6, t + " с: клуб сдвинут на " + dy);
                if (dy > 1) {
                    lifted++;
                    // клуб на текстуре — ~60% квадрата
                    edge = Math.max(edge, Math.hypot(a.x, a.z) + 0.3 * a.size);
                }
            }
            assertTrue(lifted > 300, t + " с: на рельеф встало " + lifted + " клубов");
            assertTrue(edge <= head * 1.02 + 1, t + " с: пыль впереди головы стены: " + edge + " > " + head);
            assertTrue(edge >= head * 0.9, t + " с: стена не дошла до головы: " + edge + " < " + head);
        }
    }

    /**
     * Пыль у камеры берёт свет стены ({@link CloudPuffs#wallColor}): у головы стены в 1,5 км её цвет на любой высоте —
     * в пределах цветов клубов стены там же (дым со свечением поверх), днём и ночью; ночью он не тёмный — стена там
     * в отсвете пожаров, а по ночной карте освещения пыль вышла бы почти чёрной.
     */
    @Test
    void nearDustTakesWallLight() {
        CloudPuffs puffs = new CloudPuffs(GROUND, 750, false);
        double t = 0.5;
        while (GROUND.metres(puffs.wall(t).head()) < 1500) t += 0.05;
        double head = puffs.wall(t).head(), height = puffs.wall(t).height();
        for (float ambient : new float[]{NIGHT, 1f}) {
            double[] range = {Double.MAX_VALUE, -Double.MAX_VALUE};
            puffs.sprites(t, 1, ambient, (x, y, z, size, rot, r, g, b, a, gr, gg, gb, ga, tex) -> {
                // клубы головы стены: у фронта и не выше стены (эпицентр — 0, 64, 0)
                if (Math.abs(Math.hypot(x, z) - head) > 0.5 * height || y - 64 > height || a < 0.05) return;
                double k = ga / a;
                double l = luminance(new float[]{(float) (r + gr * k), (float) (g + gg * k), (float) (b + gb * k)});
                range[0] = Math.min(range[0], l);
                range[1] = Math.max(range[1], l);
            });
            assertTrue(range[1] > range[0], "у головы стены нет клубов");
            for (double up = 0.3; up <= 1; up += 0.1) {
                float[] c = new float[3];
                assertTrue(puffs.wallColor(t, head - 5, up, 1, ambient, c));
                double l = luminance(c);
                assertTrue(l >= range[0] * 0.95 && l <= range[1] * 1.05,
                        "свет " + ambient + ", высота " + up + ": яркость " + l + " вне клубов стены " + range[0] + ".." + range[1]);
                if (ambient == NIGHT) assertTrue(l > 0.2, "ночью у пожаров пыль тёмная: " + l);
            }
        }
    }

    /** Вал головы стены крутится в одну сторону и не быстрее {@link CloudPuffs#ROLL_MAX}. */
    @Test
    void wallHeadRollsSteadily() {
        CloudPuffs puffs = new CloudPuffs(GROUND, 750, false);
        assertEquals(0, puffs.rollPhase(0));
        double prev = 0;
        for (double t = 0.05; t <= 600; t += 0.05) {
            double phase = puffs.rollPhase(t);
            assertTrue(phase >= prev - 1e-9, t + " с: вал крутится назад");
            assertTrue(phase - prev <= CloudPuffs.ROLL_MAX * 0.05 + 1e-6, t + " с: быстрее предела " + (phase - prev) / 0.05);
            prev = phase;
        }
        assertTrue(puffs.rollPhase(10) > Math.PI, "за 10 с вал не обернулся и наполовину: " + puffs.rollPhase(10));
    }
}
