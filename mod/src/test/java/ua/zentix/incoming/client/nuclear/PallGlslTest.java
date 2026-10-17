package ua.zentix.incoming.client.nuclear;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import ua.zentix.incoming.client.far.Sight;
import ua.zentix.incoming.client.fx.layer.VolumeNoise;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.nuclear.model.MushroomModel;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Законы пелены и огненного шторма в шейдере ({@code shaders/include/pall.glsl}) — те же, что в Java ({@link PallField},
 * {@link FireStorm}, {@link VolumeNoise#shape}): функции шейдера читаются из файла и считаются маленьким разбором
 * выражений GLSL, числа сравниваются с Java по всей области. Правка закона только в одном месте ломает этот тест.
 */
class PallGlslTest {
    private static final String GLSL = read("include/pall.glsl");
    /** 15 кт у земли, масштаб 1, стена пыли вдали — 0,42 скорости звука. */
    private static final Detonation GROUND = new Detonation(1, new Vec3(0, 64, 0), 64, 15, true, 0, 0.5f, 8, 20_000, 7, 1f, false, 0.42f);

    private static String read(String path) {
        try (InputStream in = PallGlslTest.class.getResourceAsStream("/assets/incoming/shaders/" + path)) {
            assertNotNull(in, path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).replaceAll("//[^\n]*", "");
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    @Test
    void sceneDistanceKeepsOnlyTheWorldInFront() {
        // расстояние до мира за пикселем (SceneDepth): негодное — NaN, бесконечность, за глазом (чужая глубина LOD) —
        // как небо, 1e9: иначе луч пелены шёл бы сквозь NaN, и пиксель кадра выходил чёрным
        GlslFunction f = GlslFunction.parse(GLSL, "pallScene");
        for (double d : new double[] {0, 0.5, 64, 3000, 9.9e7}) assertEquals(d, f.call(Map.of("d", d)), 0, "d = " + d);
        for (double d : new double[] {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -0.01, -5000, 1e8, 1e9, 3e38}) {
            assertEquals(1e9, f.call(Map.of("d", d)), 0, "d = " + d);
        }
    }

    /** Голова стены пыли без шума — та же, что в Java ({@link BlastWall#inside}, {@link BlastWall#fade}, {@link BlastWall#mean}). */
    @Test
    void wallHeadMatchesJava() {
        GlslFunction inside = GlslFunction.parse(GLSL, "pallWallInside"), fade = GlslFunction.parse(GLSL, "pallWallFade"), mean = GlslFunction.parse(GLSL, "pallWallMean");
        assertEquals(BlastWall.BITE, scalar(GLSL, "WALL_BITE"), 0);
        for (double a : new double[]{3, 25, 80}) {
            for (double top = 10; top <= 300; top += 58) {
                for (double s = -10; s <= 8 * a; s += a / 7) {
                    for (double z = -0.6 * top; z <= 1.2 * top; z += top / 23) {
                        Map<String, Double> args = Map.of("s", s, "z", z, "a", a, "top", top, "WALL_BITE", BlastWall.BITE);
                        String at = a + ", " + top + ": " + s + ", " + z;
                        assertEquals(BlastWall.inside(s, z, a, top), inside.call(args), 1e-9, at);
                        assertEquals(BlastWall.fade(s, z, a, top), fade.call(args), 1e-9, at);
                        assertEquals(BlastWall.mean(s, z, a, top), mean.call(args), 1e-9, at);
                    }
                }
            }
        }
    }

    @Test
    void profileMatchesJava() {
        GlslFunction f = GlslFunction.parse(GLSL, "pallProfile");
        for (double x = -1.5; x <= 1.5; x += 0.005) assertEquals(PallField.profile(x), f.call(Map.of("x", x)), 1e-9, "x = " + x);
    }

    @Test
    void columnMatchesJava() {
        GlslFunction f = GlslFunction.parse(GLSL, "pallColumn");
        for (double x = -1.5; x <= 1.5; x += 0.005) assertEquals(PallField.column(x), f.call(Map.of("x", x)), 1e-9, "x = " + x);
    }

    @Test
    void edgeMatchesJava() {
        GlslFunction f = GlslFunction.parse(GLSL, "pallEdge");
        for (double u = 0; u <= 1.2; u += 0.002) assertEquals(PallField.edge(u), f.call(Map.of("u", u)), 1e-9, "u = " + u);
    }

    @Test
    void surgeMatchesJava() {
        GlslFunction f = GlslFunction.parse(GLSL, "pallSurge"), top = GlslFunction.parse(GLSL, "pallSurgeTop");
        for (double u = 0; u <= 1.3; u += 0.002) {
            assertEquals(PallField.surgeProfile(u), f.call(Map.of("u", u)), 1e-9, "u = " + u);
            assertEquals(PallField.surgeTop(u), top.call(Map.of("u", u)), 1e-9, "u = " + u);
        }
    }

    /**
     * Водяная пыль пелены ({@code MIST} в {@code fx_pall.fsh}) — того же белого, что стена брызг над водой
     * ({@link CloudPuffs#SPRAY}): пелена за фронтом над водой продолжает её цвет, как бурая пыль — стену над сушей.
     */
    @Test
    void mistIsTheSprayWallColour() {
        double[] mist = vec3(read("core/fx_pall.fsh"), "MIST");
        for (int c = 0; c < 3; c++) assertEquals((CloudPuffs.SPRAY >> (16 - 8 * c) & 0xFF) / 255.0, mist[c], 0.5 / 255, "канал " + c);
    }

    @Test
    void shapeMatchesJava() {
        GlslFunction f = GlslFunction.parse(GLSL, "pallShape");
        for (double r = 0; r <= 1; r += 0.05) {
            for (double g = 0; g <= 1; g += 0.05) {
                for (double fill = 0; fill <= 1; fill += 0.1) {
                    assertEquals(VolumeNoise.shape(r, g, fill), f.call(Map.of("n.x", r, "n.y", g, "fill", fill)), 1e-9,
                            "r = " + r + ", g = " + g + ", fill = " + fill);
                }
            }
        }
    }

    /** Свет шара на пыли в {@code fx_pall} ({@code pallBallShare}) — закон {@link CloudPuffs#ball}: L·R²/d², у поверхности — L. */
    @Test
    void ballLightMatchesJava() {
        GlslFunction f = GlslFunction.parse(read("core/fx_pall.fsh"), "pallBallShare");
        for (double radius : new double[]{0, 40, 150, 600}) {
            for (double d = 0; d <= 4000; d += 50) {
                assertEquals(CloudPuffs.ball(1, radius, d), f.call(Map.of("radius", radius, "d", d)), 1e-12,
                        "radius = " + radius + ", d = " + d);
            }
        }
    }

    @Test
    void burnMatchesJava() {
        GlslFunction f = GlslFunction.parse(GLSL, "pallBurn");
        for (double r = 0; r <= 4000; r += 25) {
            for (double up = 0; up <= 1; up += 0.25) {
                for (double fireR : new double[]{0, 1900}) {
                    assertEquals(CloudPuffs.burn(r, up, 0.8, fireR), f.call(Map.of("r", r, "up", up, "glow", 0.8, "fireR", fireR)), 1e-9,
                            "r = " + r + ", up = " + up + ", fireR = " + fireR);
                }
            }
        }
    }

    /** Свет и экран шейдера ({@code pallLinear}, {@code pallDisplay}, {@code pallLift}) — те же, что в {@link PallLight}. */
    @Test
    void displayMatchesJava() {
        GlslFunction linear = GlslFunction.parse(GLSL, "pallLinear"), display = GlslFunction.parse(GLSL, "pallDisplay"), lift = GlslFunction.parse(GLSL, "pallLift");
        for (double v = 0; v <= 1.5; v += 0.01) {
            assertEquals(PallLight.linear(v), linear.call(Map.of("v", v)), 1e-12, "v = " + v);
            assertEquals(PallLight.display(v), display.call(Map.of("v", v)), 1e-6 * (1 + v), "v = " + v);
            for (double base : new double[]{0.12, 0.17, 0.6, 1}) {
                assertEquals(PallLight.lift(base, v), lift.call(Map.of("base", base, "x", v)), 1e-12, "небо " + base + ", отсвет " + v);
            }
        }
    }

    /** Мелкие клубы шейдера — те же, что у {@link VolumeNoise#detail}. */
    @Test
    void detailMatchesJava() {
        GlslFunction f = GlslFunction.parse(GLSL, "pallDetail");
        for (double r = 0; r <= 1; r += 0.002) assertEquals(VolumeNoise.detail(r), f.call(Map.of("r", r)), 1e-9, "r = " + r);
    }

    /** Затухание рассеянного света и сам свет в слое ({@code pallK}, {@code pallDiffuse}) — те же, что в {@link PallLight}. */
    @Test
    void lightMatchesJava() {
        GlslFunction k = GlslFunction.parse(GLSL, "pallK"), f = GlslFunction.parse(GLSL, "pallDiffuse");
        // у тёмного дыма (0,55, 0,48) k > 1: пучок гаснет как рассеянный свет при μ = 1/k
        double[] albedos = {1, 0.9989, 0.97, 0.9, 0.76, 0.55, 0.48};
        double[] phases = {0.3333, 0.375, 0.4595};
        for (double w : albedos) {
            for (double g : phases) {
                assertEquals(PallLight.k(w, g), k.call(Map.of("w", w, "g", g)), 1e-12, "w = " + w + ", g = " + g);
                for (double tc : new double[]{0.01, 0.7, 3, 12}) {
                    for (double share : new double[]{0, 0.4, 1}) {
                        // 0,95 и 0,975 — в полосе kμ ≈ 1 у тёмного дыма (сдвиг пучка, pallBeamMu)
                        for (double mu1 : new double[]{0.12, 0.6, 0.95, 0.975, 1}) {
                            for (double f2 : new double[]{0, 0.5}) {
                                for (double top : new double[]{0, 0.2}) {
                                    for (double bottom : new double[]{0, 0.7}) {
                                        for (double c : new double[]{-2.0 / 3, 0.2, 2.0 / 3}) {
                                            double tau = share * tc, ground = bottom > 0 ? 0.2 : 0.06;
                                            double java = PallLight.diffuse(w, g, tau, tc, ground, mu1, 0.8, 0.35, f2, top, bottom, c);
                                            Map<String, Double> args = new HashMap<>(Map.of("w", w, "g", g, "tau", tau, "tc", tc, "ground", ground,
                                                    "mu1", mu1, "f1", 0.8, "mu2", 0.35, "f2", f2, "top", top));
                                            args.put("bottom", bottom);
                                            args.put("c", c);
                                            // 2/3 в шейдере — 0,6666667: при почти непоглощающем веществе (матрица почти вырождена) это 10⁻⁵
                                            assertEquals(java, f.call(args), 1e-4 * (1 + Math.abs(java)), "w = " + w + ", g = " + g + ", τc = " + tc
                                                    + ", τ = " + tau + ", μ1 = " + mu1 + ", f2 = " + f2 + ", верх " + top + ", низ " + bottom + ", c = " + c);
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Вещество шейдера ({@code PALL_*_W}, {@code PALL_*_G} в {@code pall.glsl}) — из цветов толстого слоя {@code fx_pall.fsh}
     * в линейном ({@link PallLight#albedo}, асимметрия {@link PallLight#truncated}): толстая пелена на экране того же цвета,
     * что стена пыли и брызг (R∞ альбедо — её цвет). Сажа и пламя смерча в линейном ({@code *_LINEAR}) — те же цвета.
     */
    @Test
    void materialsHaveTheirColours() {
        String fx = read("core/fx_pall.fsh");
        String[][] materials = {{"DUST", "DUST"}, {"SMOKE", "DUST"}, {"HAZE", "DUST"}, {"MIST", "MIST"}, {"SOOT", "SOOT"}};
        Map<String, Double> full = Map.of("DUST", PallLight.DUST_G, "MIST", PallLight.MIST_G, "SOOT", PallLight.SOOT_G);
        for (String[] m : materials) {
            double g = scalar(GLSL, "PALL_" + m[1] + "_G");
            assertEquals(PallLight.truncated(full.get(m[1])), g, 1e-7, m[0]);
            double[] colour = vec3(fx, m[0]), w = vec3(GLSL, "PALL_" + m[0] + "_W");
            for (int c = 0; c < 3; c++) {
                assertEquals(PallLight.albedo(PallLight.linear(colour[c]), g), w[c], 1e-7, m[0] + ", канал " + c);
                assertEquals(colour[c], PallLight.display(PallLight.reflectance(w[c], g)), 1e-4, m[0] + ", канал " + c);
            }
        }
        for (String m : new String[]{"SOOT", "WHIRL_FIRE"}) {
            double[] colour = vec3(fx, m), linear = vec3(fx, m + "_LINEAR");
            for (int c = 0; c < 3; c++) assertEquals(PallLight.linear(colour[c]), linear[c], 1e-7, m + ", канал " + c);
        }
        assertEquals(PallLight.LAND, scalar(GLSL, "PALL_LAND"), 0);
        assertEquals(PallLight.SEA, scalar(GLSL, "PALL_SEA"), 0);
        // пыль у камеры (NukePall.dustLight) — тех же веществ и того же пламени
        double[] dust = vec3(fx, "DUST"), smoke = vec3(fx, "SMOKE"), fire = vec3(fx, "FIRE");
        for (int c = 0; c < 3; c++) {
            assertEquals(dust[c], NukePall.DUST[c], 0, "канал " + c);
            assertEquals(smoke[c], NukePall.SMOKE[c], 0, "дым, канал " + c);
            assertEquals(fire[c], NukePall.FIRE[c], 0, "пламя, канал " + c);
        }
    }

    /**
     * Пелена за фронтом ночью — в том же свете, что голова стены перед ней: толстая пыль ({@code PALL_DUST_W}) в свете шара
     * и пожаров по законам шейдера (pallDiffuse; шар — чёрное тело, как у тела гриба, pallBallShare × привыкание глаза;
     * пожары — сила {@link CloudPuffs#glowOnSmoke}, делённая на цвет пыли {@code DUST}, на экране поверх света неба — в
     * линейном разностью, pallLift; свет неба — {@link NukePall#nightSky} в линейном; итог — на экране, pallDisplay).
     * Сверху, под шаром и шапкой в первые секунды (пучок, pallBallLight), — того же цвета, что верх
     * головы стены там же ({@link NukePall#dustLight} — свет головы, как в кадре), с точностью до трети: голова — не
     * плоский слой. И не тёмная у эпицентра. Своё зарево пожаров пелены ({@code PallFireLight}) разгорается позже, с минуту,
     * — его здесь нет.
     * <p>
     * Свет пожаров — не ярче пламени ({@code pallFlame}, {@link PallLight#flame}). Ночью над зоной пожаров отсвет на экране
     * уже цвета огня ({@code FIRE}), а такой цвет у пыли свет дать не может — обращение через цвет пыли дало бы свет втрое
     * ярче огня. Там пелена у пола — пыль в свете самого пламени: сбоку не ярче пламени и того же оттенка (красный, за ним
     * зелёный, синий), в красном — не меньше 0,6 его: не тёмный занавес над пожарами.
     */
    @Test
    void pallBehindTheFrontTakesTheWallLight() {
        GlslFunction burn = GlslFunction.parse(GLSL, "pallBurn"), light = GlslFunction.parse(GLSL, "pallDiffuse"),
                lift = GlslFunction.parse(GLSL, "pallLift"), display = GlslFunction.parse(GLSL, "pallDisplay"), linear = GlslFunction.parse(GLSL, "pallLinear");
        assertTrue(GLSL.replaceAll("\\s+", " ").contains("vec3 pallFlame(float base, vec3 x, vec3 flame) { return min(pallLift3(base, x), "
                + "vec3(pallLinear(flame.r), pallLinear(flame.g), pallLinear(flame.b))); }"), "pallFlame — не PallLight.flame");
        String fx = read("core/fx_pall.fsh");
        GlslFunction ball = GlslFunction.parse(fx, "pallBallShare");
        double[] dust = vec3(fx, "DUST"), fire = vec3(fx, "FIRE"), w = vec3(GLSL, "PALL_DUST_W");
        assertEquals(PallLight.CROSS, scalar(fx, "CROSS"), 0, "к белому — как PallLight.screen");
        double g = scalar(GLSL, "PALL_DUST_G");
        CloudPuffs puffs = new CloudPuffs(GROUND);
        BlastWall blast = new BlastWall(GROUND, false);
        float ambient = 0.17f;
        double k = CloudPuffs.glowOnSmoke(ambient), sky = linear.call(Map.of("v", (double) NukePall.nightSky(ambient, 1))), tc = 8;
        float[] wall = new float[8], high = new float[3], slot = new float[BlastWall.SLOT];
        NukePall.Sky night = new NukePall.Sky();
        night.sy = -1;
        java.util.Arrays.fill(night.sky, (float) sky);
        night.base = ambient;
        night.exposure = (float) Sight.adapted(1, ambient);
        night.glowOnSmoke = (float) k;
        int checked = 0, burning = 0;
        for (double t : new double[]{2, 6, 10, 20, 40}) {
            assertTrue(puffs.pallLight(MushroomModel.shape(t, 15, 0, true, GROUND.metres(blast.wall(t).head())), wall));
            assertTrue(blast.slot(t, slot));
            double head = blast.wall(t).head();
            for (double share : new double[]{0.01, 0.03, 0.05, 0.2, 0.5, 0.8, 0.97}) {
                double r = head * share;
                double toBall = Math.hypot(wall[7], r);
                double a = ball.call(Map.of("radius", (double) wall[0], "d", toBall)) * night.exposure;
                double b = burn.call(Map.of("r", r, "up", 0.0, "glow", (double) wall[1], "fireR", (double) wall[3]));
                double mu = wall[7] / toBall;
                // верх головы стены, будь она здесь (точка — посреди её толщи), — до неё пелена здесь и была ею
                float[] here = slot.clone();
                here[0] = (float) (r + 1.5 * here[1]);
                NukePall.dustLight(here, wall, night, 0, r, 0.9 * here[2], 0, 0.9 * here[2], high);
                double sum = 0;
                double[] side = new double[3], top = new double[3];
                // отсвет пожаров на пыли — уже цвет огня: свет пелены — пламя (pallFlame)
                boolean flames = false;
                for (int c = 0; c < 3; c++) {
                    flames |= lift.call(Map.of("base", (double) ambient, "x", fire[c] * b * k / dust[c])) > linear.call(Map.of("v", fire[c]));
                }
                for (int c = 0; c < 3; c++) {
                    double capF = wall[4 + c] * a;
                    double burnF = Math.min(lift.call(Map.of("base", (double) ambient, "x", fire[c] * b * k / dust[c])),
                            linear.call(Map.of("v", fire[c])));
                    Map<String, Double> args = new HashMap<>(Map.of("w", w[c], "g", g, "tc", tc, "ground", PallLight.LAND, "mu1", 1.0,
                            "f1", 0.0, "mu2", Math.max(mu, 0.12), "f2", capF, "top", sky, "bottom", burnF));
                    args.put("tau", tc);
                    args.put("c", -2.0 / 3);
                    // пелена срезает плечом (fx_pall: shoulder): ярче 1 — как 1
                    double below = Math.min(1, display.call(Map.of("v", light.call(args))));
                    args.put("c", 0.0);
                    side[c] = display.call(Map.of("v", w[c] * light.call(args)));
                    args.put("tau", 0.0);
                    args.put("c", 2.0 / 3);
                    top[c] = light.call(args);
                    sum += Math.max(below, Math.min(1, display.call(Map.of("v", top[c]))));
                }
                if (mu > 0.9 && t <= 10) {
                    // на экран — как у пелены (к белому, плечо): под шаром в первые секунды пересвечено
                    float[] shown = new float[3];
                    PallLight.screen(top, shown);
                    for (int c = 0; c < 3; c++) {
                        assertEquals(high[c], shown[c], 0.03 + 0.3 * high[c], t + " с, " + r + " блоков, сверху, канал " + c);
                        checked++;
                    }
                }
                // пожары светят ярче шара (fx_pall: главный свет — снизу)
                if (flames && b * k > (0.2126 * wall[4] + 0.7152 * wall[5] + 0.0722 * wall[6]) * a) {
                    String at = t + " с, " + r + " блоков, у пола сбоку: ";
                    for (int c = 0; c < 3; c++) assertTrue(side[c] <= fire[c] + 0.03, at + "ярче пламени в канале " + c + ", " + side[c]);
                    assertTrue(side[0] > side[1] && side[1] > side[2], at + "не цвета огня");
                    assertTrue(side[0] >= 0.6 * fire[0], at + "тёмная против пламени, " + side[0]);
                    burning++;
                }
                if (r < 1000) assertTrue(sum / 3 > 0.25, t + " с, " + r + " блоков: пелена у эпицентра тёмная, " + sum / 3);
            }
        }
        assertTrue(checked > 15, "сравнений с верхом стены " + checked);
        assertTrue(burning > 10, "сравнений у пола над пожарами " + burning);
    }

    /**
     * Крупный ореол слой эффектов рисует по формуле ({@code glowAlpha} в {@code fx.fsh}), мелкий — из картинки
     * {@code far/glow} ({@code tools/gen_particles.py}): это один и тот же ореол — середины пикселей картинки стоят на r от
     * −1 до 1 через 63 шага, у каждого пикселя формула даёт его значение (с точностью 8 бит).
     */
    @Test
    void glowFormulaIsThePicture() throws IOException {
        GlslFunction f = GlslFunction.parse(read("core/fx.fsh"), "glowAlpha");
        BufferedImage img;
        try (InputStream in = PallGlslTest.class.getResourceAsStream("/assets/incoming/textures/far/glow.png")) {
            assertNotNull(in, "far/glow.png");
            img = ImageIO.read(in);
        }
        int n = img.getWidth();
        assertEquals(64, n, "fx.fsh считает середины крайних пикселей по картинке 64×64");
        for (int j = 0; j < n; j++) {
            for (int i = 0; i < n; i++) {
                double x = -1 + 2.0 * i / (n - 1), y = -1 + 2.0 * j / (n - 1);
                double a = (img.getRGB(i, j) >>> 24) / 255.0;
                assertEquals(a, f.call(Map.of("r", Math.hypot(x, y))), 0.5 / 255 + 1e-4, "пиксель " + i + ", " + j);
            }
        }
    }

    /**
     * Как {@code fx_pall} складывает свет, и как он и {@code fx_pall_composite} переводят его в экран, — по тексту
     * шейдеров: части проверены по отдельности, а собирает их {@code main}, который тест не считает. Огонь в точке — все
     * огни вместе под одним пределом пламени (каждый в своём пределе, а сумма была ярче огня); пожары под пылью — тоже в
     * пределе; толща над точкой — весь столб слоя; свет пелены в экран — один раз на канал, ярче белого — в долю пикселя
     * при настоящем пропускании; тень на мире — один раз.
     */
    @Test
    void pallPutsItsLightTogetherOnce() {
        String march = read("core/fx_pall.fsh"), composite = read("core/fx_pall_composite.fsh");
        assertTrue(march.contains("vec3 fire = min(below + under * (glow + s.fire / max(s.albedo, vec3(1e-7))) + around, gFlame);"),
                "огонь в точке — одной суммой под пределом пламени");
        assertTrue(march.contains("vec3 emit = s.albedo * (field + beams + fire);"), "свет точки — рассеянный, пучки и огонь");
        assertTrue(march.contains("+ min(below * exp(-gSootK * self) + (0.5 + 0.75 * PALL_SOOT_G * mv) * glow + around, gFlame);"),
                "огонь на дыме шторма — одной суммой под пределом пламени");
        assertTrue(march.contains("s.burn += pallFlame("), "пожары под пылью — не ярче пламени");
        assertTrue(march.contains("s.tau += f.column.x + f.column.y + f.column.z;"), "толща над точкой — весь столб слоя");
        assertEquals(3, occurrences(march, "pallDisplay("), "свет пелены в экран — один раз на канал");
        assertTrue(march.contains("shoulder(vec3(pallDisplay(medium.r), pallDisplay(medium.g), pallDisplay(medium.b))) * shownCover"),
                "на выходе — экран с плечом");
        assertTrue(march.contains("shownCover = min(cover * lum, 1.0);"), "пересвет — в долю пикселя, не больше всего пикселя");
        assertTrue(march.contains("vec4 res = vec4(shown, min(transmit + lost, 1.0));"),
                "пропускание кадра — настоящее и при пересвете");
        assertEquals(1, occurrences(composite, "pallDisplay("), "тень на мире в экран — один раз");
        assertTrue(composite.contains("pall.a *= pallDisplay(shade("), "тень на мире — в экран");
    }

    private static int occurrences(String text, String what) {
        int n = 0;
        for (int i = text.indexOf(what); i >= 0; i = text.indexOf(what, i + 1)) n++;
        return n;
    }

    /** Константа {@code const float name = a;} шейдера. */
    private static double scalar(String source, String name) {
        Matcher m = Pattern.compile("const\\s+float\\s+" + name + "\\s*=\\s*([^;]+);").matcher(source);
        if (!m.find()) throw new AssertionError("нет const float " + name);
        return Double.parseDouble(m.group(1).strip());
    }

    /** Константа {@code const vec3 name = vec3(a, b, c);} шейдера. */
    private static double[] vec3(String source, String name) {
        Matcher m = Pattern.compile("const\\s+vec3\\s+" + name + "\\s*=\\s*vec3\\(([^)]*)\\)").matcher(source);
        if (!m.find()) throw new AssertionError("нет const vec3 " + name);
        String[] p = m.group(1).split(",");
        return new double[]{Double.parseDouble(p[0].strip()), Double.parseDouble(p[1].strip()), Double.parseDouble(p[2].strip())};
    }

    @Test
    void fireLawsMatchJava() {
        GlslFunction bend = GlslFunction.parse(GLSL, "fireBend"), width = GlslFunction.parse(GLSL, "fireWidth"), lod = GlslFunction.parse(GLSL, "fireLod"),
                glow = GlslFunction.parse(GLSL, "fireGlowLod"), column = GlslFunction.parse(GLSL, "fireColumn");
        for (double h = -60; h <= 400; h += 0.5) {
            assertEquals(FireStorm.bend(h, FireStorm.BEND_HEIGHT), bend.call(Map.of("h", h, "hb", FireStorm.BEND_HEIGHT)), 1e-9, "h = " + h);
            for (double cell : new double[]{4, 32}) {
                assertEquals(FireStorm.width(h, cell), width.call(Map.of("h", h, "cell", cell)), 1e-9, "h = " + h);
                assertEquals(FireStorm.glowLod(h, cell), glow.call(Map.of("h", h, "cell", cell)), 1e-9, "h = " + h);
                double w = FireStorm.width(h, cell);
                assertEquals(FireStorm.lod(w, cell), lod.call(Map.of("w", w, "cell", cell)), 1e-9, "w = " + w);
            }
            assertEquals(FireStorm.column(h, 260), column.call(Map.of("h", h, "top", 260.0)), 1e-9, "h = " + h);
        }
    }
}
