package ua.zentix.airstrike.client.far;

import org.junit.jupiter.api.Test;
import ua.zentix.airstrike.client.fx.layer.FxAtlas;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Множители квадрата к радиусу круга того же светового потока сходятся с тем, сколько квадрата закрывают сами
 * текстуры: без этого ореол вспышки и шара вдали нёс в 9 раз меньше света, чем задумано (свет рисовался квадратом
 * размера круга, а текстура с ядром закрывает 0,084 квадрата). Текстуры сглажены и без повтора — иначе края пикселей
 * и обрез по краю квадрата.
 */
class FarSpritesTest {
    private static BufferedImage image(String path) throws IOException {
        try (InputStream in = FarSpritesTest.class.getResourceAsStream("/assets/airstrike/" + path)) {
            assertNotNull(in, path);
            return ImageIO.read(in);
        }
    }

    /** Средняя непрозрачность строк from..to включительно. */
    private static double coverage(BufferedImage img, int from, int to) {
        double sum = 0;
        for (int y = from; y <= to; y++) {
            for (int x = 0; x < img.getWidth(); x++) sum += (img.getRGB(x, y) >>> 24) / 255.0;
        }
        return sum / (img.getWidth() * (to - from + 1));
    }

    private static double coverage(String path) throws IOException {
        BufferedImage img = image(path);
        return coverage(img, 0, img.getHeight() - 1);
    }

    /** Полуразмер квадрата на радиус круга, который текстура с этим покрытием заменяет при непрозрачности 1. */
    private static double factor(double coverage) {
        return Math.sqrt(Math.PI / (4 * coverage));
    }

    @Test
    void factorsMatchTextures() throws IOException {
        assertEquals(FarSprites.DISC, factor(coverage("textures/far/disc.png")), 0.02, "круг");
        assertEquals(FarSprites.GLOW, factor(coverage("textures/far/glow.png")), 0.05, "ореол");
    }

    /** Доля пикселей непрозрачнее половины. */
    private static double opaque(BufferedImage img) {
        int n = 0;
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) if ((img.getRGB(x, y) >>> 24) > 127) n++;
        }
        return (double) n / (img.getWidth() * img.getHeight());
    }

    /**
     * Огненный шар вдали и вблизи — квадрат {@link FarSprites#FIREBALL} радиусов: в кадрах светящегося шара круг той же
     * площади — его радиус в доле полного ({@link FarBlasts#growth}, от него свет вспышки и шара). Иначе шар вблизи
     * и вдали разного размера, а блик — не от того, что видно.
     */
    @Test
    void fireballFramesMatchItsGrowth() throws IOException {
        for (int i = 0; i < FxAtlas.FIREBALL_FRAMES / 2; i++) {
            BufferedImage img = image(String.format(Locale.ROOT, "textures/fx/particle/fireball_%02d.png", i));
            double radius = Math.sqrt(4 * opaque(img) / Math.PI) * FarSprites.FIREBALL;
            assertEquals(FarBlasts.growth((i + 0.5) / FxAtlas.FIREBALL_FRAMES), radius, 0.08, "кадр " + i);
        }
    }

    @Test
    void flameFactorMatchesTextures() throws IOException {
        double sum = 0;
        for (int i = 0; i < 8; i++) sum += coverage(String.format(Locale.ROOT, "textures/fx/particle/fire_%02d.png", i));
        assertEquals(FarSprites.FIRE, factor(sum / 8), 0.1, "клуб пламени");
    }

    @Test
    void ribbonFactorMatchesMiddleRow() throws IOException {
        // лента берёт строку v = 0,5: при сглаживании — среднее двух средних строк
        BufferedImage glow = image("textures/far/glow.png");
        int mid = glow.getHeight() / 2;
        assertEquals(FarSprites.RIBBON, 1 / coverage(glow, mid - 1, mid), 0.03);
    }

    @Test
    void ballSeenFromAboveStaysOutOfTheGround() {
        // наземный шар ракеты (радиус 11, середина на 0,45 радиуса над землёй) в 85 блоках, взгляд на 54° вниз; мягкий
        // край — FxQuads.SOFT полуразмера кадра. Точка кадра на 0,6 радиуса ниже середины — ближняя к глазу часть купола
        double r = 11, d = 85, pitch = Math.toRadians(54), soft = 0.5 * FarSprites.FIREBALL * r, s = 0.6 * r;
        double centre = d * Math.sin(pitch), ground = d * (centre + 0.45 * r) / (centre + s * Math.cos(pitch));
        double plane = (ground - d) / soft, front = (ground - d * FarSprites.front(d, FarSprites.BALL_FRONT * r)) / soft;
        assertTrue(plane < 0.3, "плоскостью через середину низ шара уходит в землю: " + plane);
        assertTrue(front > 0.8, "у передней половины купол виден: " + front);
        assertEquals(d - 5, d * FarSprites.front(d, 5), 1e-9, "та же угловая величина на 5 блоков ближе");
        assertEquals(1, FarSprites.front(1, 5), 1e-9, "у самого глаза — на месте");
        assertEquals(1, FarSprites.front(85, 0), 1e-9);
    }
}
