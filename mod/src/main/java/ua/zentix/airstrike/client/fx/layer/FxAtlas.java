package ua.zentix.airstrike.client.fx.layer;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.platform.TextureUtil;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import ua.zentix.airstrike.Airstrike;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Все текстуры слоя эффектов на одном листе 1024×256 с уменьшенными копиями (mip-уровнями): клубы дыма и пламени,
 * искра, вспышка, кольцо, дальние круг, ореол и клубы. Один лист — одна текстура на весь отсортированный проход слоя
 * (частицы и дальнее вперемешку); уменьшенные копии — чтобы клуб в десяток пикселей вдали не рябил. Цвет на листе
 * умножен на непрозрачность, как и смешивание слоя.
 * <p>
 * Каждая картинка — в своём размере (клубы 64×64): частицы вблизи рисуются ближайшим пикселем, как из атласа Minecraft
 * ({@link FxQuads#nearestTexels}), и пиксель листа должен быть пикселем картинки. Копии — средние четырёх пикселей
 * с умноженной альфой: край тает без тёмной каймы. Не атлас Minecraft и не его {@code MipmapGenerator}: тот обнуляет
 * в копиях прозрачность ниже 96 из 255 (для листвы), а у клуба дыма она почти вся такая — в main дым за стеной
 * в сотне блоков пропадал целиком. Места на листе постоянные и кратны 16 — копии соседних мест не смешиваются;
 * картинку другого размера (пакет ресурсов) лист подгоняет под место.
 */
public final class FxAtlas extends SimplePreparableReloadListener<NativeImage[]> {
    public static final FxAtlas INSTANCE = new FxAtlas();

    private static final int CELL = 64, COLS = 16, ROWS = 4, WIDTH = COLS * CELL, HEIGHT = ROWS * CELL;
    /** Уменьшенных копий: клуб 64 → 4 пикселя (все места кратны 16 — копии соседних мест не смешивают). */
    private static final int MIPS = 4;

    /** Место текстуры на листе (доли листа). */
    public record Sprite(float u0, float v0, float u1, float v1) {
        /** Середина по вертикали (лента — средняя строка ореола). */
        public float vMid() {
            return (v0 + v1) * 0.5f;
        }
    }

    private record Slot(ResourceLocation texture, int x, int y, int w, int h) {}

    private static final List<Slot> SLOTS = new ArrayList<>();
    private static final Sprite[] SMOKE = new Sprite[16], FIRE = new Sprite[8], PUFFS = new Sprite[8];
    private static final Sprite RING, FLASH, GLOW, DISC, SPARK;

    static {
        for (int i = 0; i < SMOKE.length; i++) SMOKE[i] = slot(String.format(Locale.ROOT, "fx/particle/smoke_%02d", i), i * CELL, 0, CELL, CELL);
        for (int i = 0; i < FIRE.length; i++) FIRE[i] = slot(String.format(Locale.ROOT, "fx/particle/fire_%02d", i), i * CELL, CELL, CELL, CELL);
        int x = FIRE.length * CELL;
        FLASH = slot("fx/particle/flash", x, CELL, CELL, CELL);
        GLOW = slot("far/glow", x + CELL, CELL, CELL, CELL);
        DISC = slot("far/disc", x + 2 * CELL, CELL, 32, 32);
        SPARK = slot("fx/particle/spark", x + 2 * CELL + 32, CELL, 16, 16);
        // клубы дальнего дыма: 4×2 кадра на одной картинке
        Sprite puffs = slot("nuke/puffs", 0, 2 * CELL, 4 * CELL, 2 * CELL);
        float du = (puffs.u1 - puffs.u0) / 4, dv = (puffs.v1 - puffs.v0) / 2;
        for (int i = 0; i < PUFFS.length; i++) {
            float u = puffs.u0 + i % 4 * du, v = puffs.v0 + i / 4 * dv;
            PUFFS[i] = new Sprite(u, v, u + du, v + dv);
        }
        RING = slot("fx/particle/ring", 4 * CELL, 2 * CELL, 2 * CELL, 2 * CELL);
    }

    private int id = -1;

    private FxAtlas() {}

    private static Sprite slot(String path, int x, int y, int w, int h) {
        SLOTS.add(new Slot(Airstrike.id("textures/" + path + ".png"), x, y, w, h));
        return new Sprite((float) x / WIDTH, (float) y / HEIGHT, (float) (x + w) / WIDTH, (float) (y + h) / HEIGHT);
    }

    /** Клуб дыма: 4 клуба × 4 стадии рассеивания. */
    public static Sprite smoke(int i) {
        return SMOKE[i];
    }

    public static Sprite fire(int i) {
        return FIRE[i];
    }

    public static Sprite spark() {
        return SPARK;
    }

    public static Sprite flash() {
        return FLASH;
    }

    public static Sprite ring() {
        return RING;
    }

    /** Кадр дальнего клуба (4×2, {@code nuke/puffs}). */
    public static Sprite puff(int i) {
        return PUFFS[i];
    }

    /** Сплошной круг с мягким краем (корпус, огненный шар, ядро вспышки вдали). */
    public static Sprite disc() {
        return DISC;
    }

    /** Гауссов ореол без ядра (блик, вуаль, зарево; лентой — шлейф). */
    public static Sprite glow() {
        return GLOW;
    }

    /** Текстура OpenGL листа; −1 — ещё не загружен. */
    public int id() {
        return id;
    }

    /** Фоновый поток: картинки на места со своими уменьшенными копиями, умноженная альфа. */
    @Override
    protected NativeImage[] prepare(ResourceManager resources, ProfilerFiller profiler) {
        int[][] sheets = new int[MIPS + 1][];
        for (int l = 0; l <= MIPS; l++) sheets[l] = new int[(WIDTH >> l) * (HEIGHT >> l)];
        for (Slot s : SLOTS) place(resources, s, sheets);
        NativeImage[] levels = new NativeImage[MIPS + 1];
        for (int l = 0; l <= MIPS; l++) levels[l] = image(sheets[l], WIDTH >> l, HEIGHT >> l);
        return levels;
    }

    /** Поток отрисовки: в видеопамять (та же текстура при перезагрузке ресурсов). */
    @Override
    protected void apply(NativeImage[] levels, ResourceManager resources, ProfilerFiller profiler) {
        if (id < 0) id = TextureUtil.generateTextureId();
        TextureUtil.prepareImage(id, MIPS, WIDTH, HEIGHT);
        for (int l = 0; l < levels.length; l++) {
            NativeImage img = levels[l];
            img.upload(l, 0, 0, 0, 0, img.getWidth(), img.getHeight(), true, true, true, true);
        }
    }

    /** Картинка и её копии на свои места листа (цвет, умноженный на непрозрачность); другой размер — подгоняется. */
    private static void place(ResourceManager resources, Slot s, int[][] sheets) {
        Optional<Resource> res = resources.getResource(s.texture);
        if (res.isEmpty()) {
            Airstrike.LOG.warn("Текстура эффектов {} не найдена: на её месте пусто", s.texture);
            return;
        }
        int[] px;
        try (InputStream in = res.get().open(); NativeImage img = NativeImage.read(in)) {
            px = fit(img, s.w, s.h);
        } catch (IOException | RuntimeException e) {
            Airstrike.LOG.warn("Текстура эффектов {} не прочиталась: на её месте пусто", s.texture, e);
            return;
        }
        int w = s.w, h = s.h;
        for (int l = 0; ; l++) {
            put(sheets[l], WIDTH >> l, s, l, px);
            if (l == MIPS) break;
            px = half(px, w, h);
            w >>= 1;
            h >>= 1;
        }
    }

    /** Пиксели картинки (ABGR, цвет умножен на непрозрачность) в размере места: другой размер — билинейно. */
    private static int[] fit(NativeImage img, int w, int h) {
        int sw = img.getWidth(), sh = img.getHeight();
        int[] src = new int[sw * sh];
        for (int y = 0; y < sh; y++) {
            for (int x = 0; x < sw; x++) src[y * sw + x] = premultiply(img.getPixelRGBA(x, y));
        }
        if (sw == w && sh == h) return src;
        int[] out = new int[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) out[y * w + x] = sample(src, sw, sh, (x + 0.5f) * sw / w - 0.5f, (y + 0.5f) * sh / h - 0.5f);
        }
        return out;
    }

    /** Копия уровня l места s на лист этого уровня (ширина листа — sheetW). */
    private static void put(int[] sheet, int sheetW, Slot s, int l, int[] px) {
        int w = Math.max(1, s.w >> l), h = Math.max(1, s.h >> l), x0 = s.x >> l, y0 = s.y >> l;
        for (int y = 0; y < h; y++) System.arraycopy(px, y * w, sheet, (y0 + y) * sheetW + x0, w);
    }

    /** ABGR → ABGR с цветом, умноженным на непрозрачность. */
    private static int premultiply(int abgr) {
        int a = abgr >>> 24;
        int r = (abgr & 0xFF) * a / 255, g = (abgr >> 8 & 0xFF) * a / 255, b = (abgr >> 16 & 0xFF) * a / 255;
        return a << 24 | b << 16 | g << 8 | r;
    }

    /** Значение между пикселями (билинейно, по каналам с умноженной альфой), за краем — край. */
    private static int sample(int[] src, int w, int h, float fx, float fy) {
        int x0 = (int) Math.floor(fx), y0 = (int) Math.floor(fy);
        float tx = fx - x0, ty = fy - y0;
        int c00 = at(src, w, h, x0, y0), c10 = at(src, w, h, x0 + 1, y0), c01 = at(src, w, h, x0, y0 + 1), c11 = at(src, w, h, x0 + 1, y0 + 1);
        int out = 0;
        for (int shift = 0; shift < 32; shift += 8) {
            float v0 = (c00 >>> shift & 0xFF) * (1 - tx) + (c10 >>> shift & 0xFF) * tx;
            float v1 = (c01 >>> shift & 0xFF) * (1 - tx) + (c11 >>> shift & 0xFF) * tx;
            out |= Math.round(v0 * (1 - ty) + v1 * ty) << shift;
        }
        return out;
    }

    private static int at(int[] src, int w, int h, int x, int y) {
        return src[Math.clamp(y, 0, h - 1) * w + Math.clamp(x, 0, w - 1)];
    }

    /** Копия вдвое меньше: среднее четырёх пикселей по каждому каналу. */
    private static int[] half(int[] src, int w, int h) {
        int hw = w >> 1, hh = h >> 1;
        int[] out = new int[hw * hh];
        for (int y = 0; y < hh; y++) {
            for (int x = 0; x < hw; x++) {
                int i = 2 * y * w + 2 * x;
                int a = src[i], b = src[i + 1], c = src[i + w], d = src[i + w + 1], v = 0;
                for (int shift = 0; shift < 32; shift += 8) {
                    int sum = (a >>> shift & 0xFF) + (b >>> shift & 0xFF) + (c >>> shift & 0xFF) + (d >>> shift & 0xFF);
                    v |= (sum + 2) / 4 << shift;
                }
                out[y * hw + x] = v;
            }
        }
        return out;
    }

    private static NativeImage image(int[] px, int w, int h) {
        NativeImage img = new NativeImage(w, h, false);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) img.setPixelRGBA(x, y, px[y * w + x]);
        }
        return img;
    }
}
