package ua.zentix.incoming.client.nuclear;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import ua.zentix.incoming.client.fx.layer.VolumeNoise;
import ua.zentix.incoming.nuclear.Detonation;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
        Function f = Function.parse(GLSL, "pallScene");
        for (double d : new double[] {0, 0.5, 64, 3000, 9.9e7}) assertEquals(d, f.call(Map.of("d", d)), 0, "d = " + d);
        for (double d : new double[] {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -0.01, -5000, 1e8, 1e9, 3e38}) {
            assertEquals(1e9, f.call(Map.of("d", d)), 0, "d = " + d);
        }
    }

    @Test
    void profileMatchesJava() {
        Function f = Function.parse(GLSL, "pallProfile");
        for (double x = -1.5; x <= 1.5; x += 0.005) assertEquals(PallField.profile(x), f.call(Map.of("x", x)), 1e-9, "x = " + x);
    }

    @Test
    void columnMatchesJava() {
        Function f = Function.parse(GLSL, "pallColumn");
        for (double x = -1.5; x <= 1.5; x += 0.005) assertEquals(PallField.column(x), f.call(Map.of("x", x)), 1e-9, "x = " + x);
    }

    @Test
    void edgeMatchesJava() {
        Function f = Function.parse(GLSL, "pallEdge");
        for (double u = 0; u <= 1.2; u += 0.002) assertEquals(PallField.edge(u), f.call(Map.of("u", u)), 1e-9, "u = " + u);
    }

    @Test
    void surgeMatchesJava() {
        Function f = Function.parse(GLSL, "pallSurge"), top = Function.parse(GLSL, "pallSurgeTop");
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
        Function f = Function.parse(GLSL, "pallShape");
        for (double r = 0; r <= 1; r += 0.05) {
            for (double g = 0; g <= 1; g += 0.05) {
                for (double fill = 0; fill <= 1; fill += 0.1) {
                    assertEquals(VolumeNoise.shape(r, g, fill), f.call(Map.of("n.x", r, "n.y", g, "fill", fill)), 1e-9,
                            "r = " + r + ", g = " + g + ", fill = " + fill);
                }
            }
        }
    }

    @Test
    void litMatchesJava() {
        Function f = Function.parse(GLSL, "pallLit");
        for (double r = 0; r <= 4000; r += 50) {
            for (double up = 0; up <= 1; up += 0.25) {
                for (double overhead : new double[]{0, 0.5, 1}) {
                    assertEquals(CloudPuffs.lit(r, up, overhead, 1560), f.call(Map.of("r", r, "up", up, "overhead", overhead, "lightR", 1560.0)), 1e-9,
                            "r = " + r + ", up = " + up + ", overhead = " + overhead);
                }
            }
        }
    }

    @Test
    void burnMatchesJava() {
        Function f = Function.parse(GLSL, "pallBurn");
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
        Function linear = Function.parse(GLSL, "pallLinear"), display = Function.parse(GLSL, "pallDisplay"), lift = Function.parse(GLSL, "pallLift");
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
        Function f = Function.parse(GLSL, "pallDetail");
        for (double r = 0; r <= 1; r += 0.002) assertEquals(VolumeNoise.detail(r), f.call(Map.of("r", r)), 1e-9, "r = " + r);
    }

    /** Затухание рассеянного света и сам свет в слое ({@code pallK}, {@code pallDiffuse}) — те же, что в {@link PallLight}. */
    @Test
    void lightMatchesJava() {
        Function k = Function.parse(GLSL, "pallK"), f = Function.parse(GLSL, "pallDiffuse");
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
        double[] dust = vec3(fx, "DUST");
        for (int c = 0; c < 3; c++) assertEquals(dust[c], NukePall.DUST[c], 0, "канал " + c);
    }

    /**
     * Пелена за фронтом продолжает стену пыли ночью: толстая пыль ({@code PALL_DUST_W}) в свете стены по законам шейдера
     * (pallDiffuse; сила {@link CloudPuffs#glowOnSmoke}, делённая на цвет пыли {@code DUST}, на экране поверх света неба —
     * в линейном разностью, pallLift; свет неба — {@link NukePall#nightSky} в линейном; итог — на экране, pallDisplay)
     * — снизу, в зоне пожаров (свет снизу, у земли — pallBurn у низа стены), и сверху, под шаром и шапкой в первые секунды
     * (пучок, pallLit у верха стены), — того же цвета, что клуб стены там же ({@link CloudPuffs#wallColor}), с точностью до
     * трети: клуб стены освещён только прямо, а пелена — слой, и свет в нём ходит между ним и землёй (у низа пожар светит
     * и отражённым от земли), а в толще гаснет (верх густой пелены над пожарами темнее верхних клубов стены — их стена
     * освещает пожаром по всей высоте). И не тёмная у эпицентра. Своё зарево пожаров пелены ({@code PallFireLight})
     * разгорается позже, с минуту, — его здесь нет.
     * <p>
     * Свет пожаров — не ярче пламени ({@code pallFlame}, {@link PallLight#flame}). Ночью над зоной пожаров клуб стены на
     * экране уже цвета огня ({@code FIRE}: свечение клуба поверх отсвета), а такой цвет у пыли свет дать не может — обращение
     * через цвет пыли дало бы свет втрое ярче огня. Там пелена у пола — пыль в свете самого пламени: сбоку (как клуб стены
     * видит глаз) не ярче клуба и того же оттенка (красный, за ним зелёный, синий), в красном — не меньше 0,6 его: не тёмный
     * занавес перед освещённой стеной. Где свет стены меньше пламени — как раньше, тот же цвет с точностью до трети.
     */
    @Test
    void pallBehindTheFrontTakesTheWallLight() {
        Function lit = Function.parse(GLSL, "pallLit"), burn = Function.parse(GLSL, "pallBurn"), light = Function.parse(GLSL, "pallDiffuse"),
                lift = Function.parse(GLSL, "pallLift"), display = Function.parse(GLSL, "pallDisplay"), linear = Function.parse(GLSL, "pallLinear");
        assertTrue(GLSL.replaceAll("\\s+", " ").contains("vec3 pallFlame(float base, vec3 x, vec3 flame) { return min(pallLift3(base, x), "
                + "vec3(pallLinear(flame.r), pallLinear(flame.g), pallLinear(flame.b))); }"), "pallFlame — не PallLight.flame");
        String fx = read("core/fx_pall.fsh");
        double[] dust = vec3(fx, "DUST"), fire = vec3(fx, "FIRE"), w = vec3(GLSL, "PALL_DUST_W");
        double g = scalar(GLSL, "PALL_DUST_G");
        CloudPuffs puffs = new CloudPuffs(GROUND, 600, false);
        float ambient = 0.17f;
        double k = CloudPuffs.glowOnSmoke(ambient), sky = linear.call(Map.of("v", (double) NukePall.nightSky(ambient, 1))), tc = 8;
        float[] wall = new float[8], low = new float[3], high = new float[3];
        int checked = 0, burning = 0;
        for (double t : new double[]{2, 6, 10, 20, 40}) {
            assertTrue(puffs.pallLight(t, wall));
            double head = puffs.wall(t).head();
            for (double share : new double[]{0.05, 0.2, 0.5, 0.8, 0.97}) {
                double r = head * share;
                double a = lit.call(Map.of("r", r, "up", 1.0, "overhead", (double) wall[0], "lightR", (double) wall[2]));
                double b = burn.call(Map.of("r", r, "up", 0.0, "glow", (double) wall[1], "fireR", (double) wall[3]));
                double mu = wall[7] / Math.hypot(wall[7], r);
                assertTrue(puffs.wallColor(t, r, 0.05, 1, ambient, low));
                assertTrue(puffs.wallColor(t, r, 0.9, 1, ambient, high));
                double sum = 0;
                double[] side = new double[3];
                // отсвет пожаров на клубе стены — уже цвет огня: свет пелены — пламя (pallFlame)
                boolean flames = false;
                for (int c = 0; c < 3; c++) {
                    flames |= lift.call(Map.of("base", (double) ambient, "x", fire[c] * b * k / dust[c])) > linear.call(Map.of("v", fire[c]));
                }
                for (int c = 0; c < 3; c++) {
                    double capF = lift.call(Map.of("base", (double) ambient, "x", wall[4 + c] * a * k / dust[c]));
                    double burnF = Math.min(lift.call(Map.of("base", (double) ambient, "x", fire[c] * b * k / dust[c])),
                            linear.call(Map.of("v", fire[c])));
                    Map<String, Double> args = new HashMap<>(Map.of("w", w[c], "g", g, "tc", tc, "ground", PallLight.LAND, "mu1", 1.0,
                            "f1", 0.0, "mu2", Math.max(mu, 0.12), "f2", capF, "top", sky, "bottom", burnF));
                    args.put("tau", tc);
                    args.put("c", -2.0 / 3);
                    // стена срезает каждый канал на 1 (CloudPuffs.smoke), пелена — плечом (fx_pall: shoulder): ярче 1 — как 1
                    double below = Math.min(1, display.call(Map.of("v", light.call(args))));
                    args.put("c", 0.0);
                    side[c] = display.call(Map.of("v", w[c] * light.call(args)));
                    args.put("tau", 0.0);
                    args.put("c", 2.0 / 3);
                    double above = Math.min(1, display.call(Map.of("v", light.call(args))));
                    sum += Math.max(below, above);
                    if (b > lit.call(Map.of("r", r, "up", 0.05, "overhead", (double) wall[0], "lightR", (double) wall[2])) && !flames) {
                        assertEquals(low[c], below, 0.03 + 0.3 * low[c], t + " с, " + r + " блоков, снизу, канал " + c);
                        checked++;
                    }
                    if (mu > 0.9 && t <= 10) {
                        assertEquals(high[c], above, 0.03 + 0.3 * high[c], t + " с, " + r + " блоков, сверху, канал " + c);
                        checked++;
                    }
                }
                if (flames && b > lit.call(Map.of("r", r, "up", 0.05, "overhead", (double) wall[0], "lightR", (double) wall[2]))) {
                    String at = t + " с, " + r + " блоков, у пола сбоку: ";
                    for (int c = 0; c < 3; c++) assertTrue(side[c] <= low[c] + 0.03, at + "ярче клуба стены в канале " + c + ", " + side[c]);
                    assertTrue(side[0] > side[1] && side[1] > side[2], at + "не цвета огня");
                    assertTrue(side[0] >= 0.6 * low[0], at + "тёмная против стены, " + side[0] + " и " + low[0]);
                    burning++;
                }
                if (r < 1000) assertTrue(sum / 3 > 0.25, t + " с, " + r + " блоков: пелена у эпицентра тёмная, " + sum / 3);
            }
        }
        assertTrue(checked > 15, "сравнений со стеной " + checked);
        assertTrue(burning > 10, "сравнений у пола над пожарами " + burning);
    }

    /**
     * Крупный ореол слой эффектов рисует по формуле ({@code glowAlpha} в {@code fx.fsh}), мелкий — из картинки
     * {@code far/glow} ({@code tools/gen_particles.py}): это один и тот же ореол — середины пикселей картинки стоят на r от
     * −1 до 1 через 63 шага, у каждого пикселя формула даёт его значение (с точностью 8 бит).
     */
    @Test
    void glowFormulaIsThePicture() throws IOException {
        Function f = Function.parse(read("core/fx.fsh"), "glowAlpha");
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
     * пределе; толща над точкой — весь столб слоя; свет пелены в экран — один раз на канал, тень на мире — один раз.
     */
    @Test
    void pallPutsItsLightTogetherOnce() {
        String march = read("core/fx_pall.fsh"), composite = read("core/fx_pall_composite.fsh");
        assertTrue(march.contains("vec3 fire = min(below + under * (glow + s.fire / max(s.albedo, vec3(1e-7))) + around, flame);"),
                "огонь в точке — одной суммой под пределом пламени");
        assertTrue(march.contains("vec3 emit = s.albedo * (field + beams + fire);"), "свет точки — рассеянный, пучки и огонь");
        assertTrue(march.contains("+ min(below * exp(-sootK * self) + (0.5 + 0.75 * PALL_SOOT_G * mv) * glow + around, flame);"),
                "огонь на дыме шторма — одной суммой под пределом пламени");
        assertTrue(march.contains("s.burn += pallFlame("), "пожары под пылью — не ярче пламени");
        assertTrue(march.contains("s.tau += f.column.x + f.column.y + f.column.z;"), "толща над точкой — весь столб слоя");
        assertEquals(3, occurrences(march, "pallDisplay("), "свет пелены в экран — один раз на канал");
        assertTrue(march.contains("shoulder(vec3(pallDisplay(medium.r), pallDisplay(medium.g), pallDisplay(medium.b))) * cover"),
                "на выходе — экран с плечом");
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
        Function bend = Function.parse(GLSL, "fireBend"), width = Function.parse(GLSL, "fireWidth"), lod = Function.parse(GLSL, "fireLod"),
                glow = Function.parse(GLSL, "fireGlowLod"), column = Function.parse(GLSL, "fireColumn");
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

    /**
     * Функция GLSL из чисел в число: объявления {@code float a = …, b = …;}, {@code if (условие) return …;},
     * {@code return …;}; в выражениях — числа, переменные (у vec2 — {@code n.x}), + − * /, сравнения, встроенные
     * {@code smoothstep, clamp, min, max, exp, log2, pow, abs, sqrt, sign} и такие же функции того же файла.
     */
    private record Function(List<String> tokens, List<String> params, String source) {
        private static final Pattern TOKEN = Pattern.compile("\\s*(\\d+\\.?\\d*(?:[eE][-+]?\\d+)?|\\.\\d+|[A-Za-z_]\\w*(?:\\.[xyzw]+)?|<=|>=|==|[-+*/(),;{}<>=])");

        static Function parse(String source, String name) {
            Matcher m = Pattern.compile("float\\s+" + name + "\\s*\\(([^)]*)\\)\\s*\\{").matcher(source);
            if (!m.find()) throw new AssertionError("в pall.glsl нет float " + name);
            List<String> params = new ArrayList<>();
            for (String p : m.group(1).split(",")) {
                String[] w = p.strip().split("\\s+");
                if (!w[0].isEmpty()) params.add(w[w.length - 1]);
            }
            int depth = 1, i = m.end();
            for (; depth > 0; i++) {
                if (source.charAt(i) == '{') depth++;
                if (source.charAt(i) == '}') depth--;
            }
            String body = source.substring(m.end(), i - 1);
            List<String> tokens = new ArrayList<>();
            Matcher t = TOKEN.matcher(body);
            int at = 0;
            while (at < body.length() && t.find(at) && t.start() == at) {
                tokens.add(t.group(1));
                at = t.end();
            }
            if (!body.substring(at).isBlank()) throw new AssertionError(name + ": не разобрать «" + body.substring(at).trim() + "»");
            return new Function(tokens, params, source);
        }

        double call(Map<String, Double> args) {
            return new Run(tokens, new HashMap<>(args), source).body();
        }
    }

    private static final class Run {
        private static final Set<String> BUILTIN = Set.of("smoothstep", "clamp", "min", "max", "exp", "log2", "pow", "abs", "sqrt", "sign");
        private final List<String> t;
        private final Map<String, Double> vars;
        private final String source;
        private int i;

        Run(List<String> tokens, Map<String, Double> vars, String source) {
            this.t = tokens;
            this.vars = vars;
            this.source = source;
        }

        double body() {
            while (i < t.size()) {
                String w = next();
                switch (w) {
                    case "float" -> {
                        do {
                            String name = next();
                            expect("=");
                            vars.put(name, expr());
                        } while (take(","));
                        expect(";");
                    }
                    case "if" -> {
                        expect("(");
                        boolean c = condition();
                        expect(")");
                        expect("return");
                        double v = expr();
                        expect(";");
                        if (c) return v;
                    }
                    case "return" -> {
                        double v = expr();
                        expect(";");
                        return v;
                    }
                    default -> throw new AssertionError("не знаю инструкцию " + w);
                }
            }
            throw new AssertionError("нет return");
        }

        private boolean condition() {
            double a = expr();
            String op = next();
            double b = expr();
            return switch (op) {
                case "<" -> a < b;
                case "<=" -> a <= b;
                case ">" -> a > b;
                case ">=" -> a >= b;
                case "==" -> a == b;
                default -> throw new AssertionError("не знаю сравнение " + op);
            };
        }

        private double expr() {
            double v = term();
            while (i < t.size() && (t.get(i).equals("+") || t.get(i).equals("-"))) v = next().equals("+") ? v + term() : v - term();
            return v;
        }

        private double term() {
            double v = unary();
            while (i < t.size() && (t.get(i).equals("*") || t.get(i).equals("/"))) v = next().equals("*") ? v * unary() : v / unary();
            return v;
        }

        private double unary() {
            if (take("-")) return -unary();
            if (take("(")) {
                double v = expr();
                expect(")");
                return v;
            }
            String w = next();
            if (Character.isDigit(w.charAt(0)) || w.charAt(0) == '.') return Double.parseDouble(w);
            if (take("(")) {
                List<Double> a = new ArrayList<>();
                if (!take(")")) {
                    do a.add(expr());
                    while (take(","));
                    expect(")");
                }
                return function(w, a);
            }
            Double v = vars.get(w);
            if (v == null) throw new AssertionError("нет переменной " + w);
            return v;
        }

        /** Встроенная функция или функция того же файла (её параметры — по порядку). */
        private double function(String name, List<Double> a) {
            if (BUILTIN.contains(name)) return builtin(name, a);
            Function f = Function.parse(source, name);
            if (f.params.size() != a.size()) throw new AssertionError(name + ": параметров " + f.params.size() + ", а дано " + a.size());
            Map<String, Double> args = new HashMap<>();
            for (int k = 0; k < a.size(); k++) args.put(f.params.get(k), a.get(k));
            return f.call(args);
        }

        private static double builtin(String name, List<Double> a) {
            return switch (name) {
                case "smoothstep" -> {
                    double f = Math.max(0, Math.min(1, (a.get(2) - a.get(0)) / (a.get(1) - a.get(0))));
                    yield f * f * (3 - 2 * f);
                }
                case "clamp" -> Math.max(a.get(1), Math.min(a.get(2), a.get(0)));
                case "min" -> Math.min(a.get(0), a.get(1));
                case "max" -> Math.max(a.get(0), a.get(1));
                case "exp" -> Math.exp(a.get(0));
                case "log2" -> Math.log(a.get(0)) / Math.log(2);
                case "pow" -> Math.pow(a.get(0), a.get(1));
                case "abs" -> Math.abs(a.get(0));
                case "sqrt" -> Math.sqrt(a.get(0));
                case "sign" -> Math.signum(a.get(0));
                default -> throw new AssertionError("не знаю функцию " + name);
            };
        }

        private String next() {
            if (i >= t.size()) throw new AssertionError("конец функции раньше времени");
            return t.get(i++);
        }

        private boolean take(String w) {
            if (i < t.size() && t.get(i).equals(w)) {
                i++;
                return true;
            }
            return false;
        }

        private void expect(String w) {
            String got = next();
            if (!got.equals(w)) throw new AssertionError("ждал «" + w + "», а там «" + got + "»");
        }
    }
}
