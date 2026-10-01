package ua.zentix.airstrike.client.far;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

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

    @Test
    void ribbonFactorMatchesMiddleRow() throws IOException {
        // лента берёт строку v = 0,5: при сглаживании — среднее двух средних строк
        BufferedImage glow = image("textures/far/glow.png");
        int mid = glow.getHeight() / 2;
        assertEquals(FarSprites.RIBBON, 1 / coverage(glow, mid - 1, mid), 0.03);
    }

    @Test
    void farTexturesAreSmoothAndClamped() throws IOException {
        for (String name : new String[]{"disc", "glow"}) {
            try (InputStream in = FarSpritesTest.class.getResourceAsStream("/assets/airstrike/textures/far/" + name + ".png.mcmeta")) {
                assertNotNull(in, name);
                String meta = new String(in.readAllBytes(), StandardCharsets.UTF_8).replaceAll("\\s", "");
                assertTrue(meta.contains("\"blur\":true") && meta.contains("\"clamp\":true"), name + ": " + meta);
            }
        }
    }
}
