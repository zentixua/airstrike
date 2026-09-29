package ua.zentix.airstrike.client.map;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.fml.ModList;
import org.jetbrains.annotations.Nullable;
import ua.zentix.airstrike.Airstrike;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Подложка карты: рельеф плитками 64×64 пикселя, как у веб-карт. Плитка уровня {@code L} покрывает
 * {@code 64·2^L} блоков, пиксель — колонку в середине клетки {@code 2^L} блоков; уровень выбирается по масштабу,
 * чтобы на пиксель экрана приходилось не больше пикселя плитки. Цвета — ванильной карты ({@link MapColor}) с тенью
 * склонов с севера, как у карты в руке.
 * <p>
 * Два слоя: снизу — Distant Horizons, если он стоит (километры вокруг, строится в своих фоновых потоках), сверху —
 * чанки клиента (строятся в кадре, понемногу). Клетка без данных прозрачна, поэтому вблизи видны точные и свежие
 * чанки (воронки), дальше — рельеф DH. Чтение DH может ждать его очередей сколько угодно (данных ещё нет, генерация
 * стоит): ждут только наши фоновые потоки, их не больше {@link #MAX_JOBS}, а слой чанков от них не зависит.
 * Готовые плитки загружаются в текстуры в потоке игры. Плитки живут, пока открыт мир, самые давние по показу
 * вытесняются; видимая плитка перестраивается, когда устарела (взрывы меняют рельеф, DH досчитывает даль).
 */
public final class TerrainTiles {
    static final int SIZE = 64;
    /** Самая крупная плитка — 1024 блока (16 блоков на пиксель). */
    public static final int MAX_LEVEL = 4;
    private static final int MAX_TILES = 512;
    /** Сколько плиток строится в фоне одновременно: DH читает свою базу, не забираем у игры больше. */
    private static final int MAX_JOBS = 2;
    private static final long REFRESH_NS = 30_000_000_000L;
    /** Плитку без данных (DH ещё не досчитал, чанк не пришёл) спрашиваем чаще. */
    private static final long EMPTY_REFRESH_NS = 5_000_000_000L;
    /** Дольше этого чтение DH — в лог (один раз за мир): рельеф вдали не появится, пока DH не ответит. */
    private static final long STALL_NS = 30_000_000_000L;
    /** Сколько кадр строит плитки из чанков клиента. */
    private static final long FRAME_BUDGET_NS = 3_000_000L;
    /** Нет данных о высоте. */
    private static final int NO_HEIGHT = Integer.MIN_VALUE;

    private record Key(int level, int tx, int tz) {
        int span() {
            return SIZE << level;
        }
    }

    private static final class Tile {
        final Key key;
        @Nullable
        DynamicTexture texture;
        @Nullable
        ResourceLocation id;
        @Nullable
        int[] heights;
        long builtAt;
        /** Кадр, в котором плитку последний раз рисовали: видимые сейчас не вытесняются. */
        long drawnFrame;
        /** Когда начата фоновая постройка; 0 — не строится. */
        long buildingSince;
        boolean empty = true;

        Tile(Key key) {
            this.key = key;
        }
    }

    /** Готовая плитка из фона: пиксели ABGR (как у {@link NativeImage}) и высоты; сколько строилась. */
    private record Built(Key key, int generation, int[] pixels, int[] heights, boolean empty, long nanos) {}

    /** Плитки одного источника рельефа. */
    private static final class Layer {
        final String name;
        final TerrainSource source;
        /** Порядок доступа: первая — давно не показанная. */
        final Map<Key, Tile> tiles = new LinkedHashMap<>(256, 0.75f, true);
        final Queue<Built> done = new ConcurrentLinkedQueue<>();
        int jobs;
        boolean stallLogged, firstLogged;
        /** Источник сломался (другая версия API DH): слой больше не строится до смены мира. */
        boolean broken;
        /** Для лога: построено плиток, из них с рельефом, время постройки всех. */
        int built, withData;
        long buildNanos;

        Layer(String name, TerrainSource source) {
            this.name = name;
            this.source = source;
        }
    }

    @Nullable
    private static ExecutorService executor;
    @Nullable
    private static ClientLevel level;
    /** Снизу вверх: DH (если стоит), потом чанки клиента. */
    private static List<Layer> layers = List.of();
    /** Меняется при смене мира: плитки, начатые для старого, выбрасываются. */
    private static int generation;
    /** DH стоит, и его API совместимо ({@link #init}). */
    private static boolean distantHorizons;
    /** Счётчик кадров карты (для вытеснения только невидимых плиток). */
    private static long frame;

    private TerrainTiles() {}

    /** Уровень плиток для масштаба {@code k} пикселей на блок. */
    static int levelFor(double k) {
        return Mth.clamp(Mth.floor(Math.log(1 / k) / Math.log(2)), 0, MAX_LEVEL);
    }

    /** Нарисовать рельеф в прямоугольнике экрана и заказать недостающие плитки — ближние к центру первыми. */
    public static void render(GuiGraphics g, MapProjection map, int left, int top, int right, int bottom) {
        ClientLevel current = Minecraft.getInstance().level;
        if (current == null) return;
        if (current != level) switchTo(current);
        frame++;
        int want = levelFor(map.k());
        // мельче самых крупных плиток не строим (и не заводим): на такую карту ушли бы тысячи участков DH
        boolean build = map.k() * (2 << MAX_LEVEL) >= 1;
        double cx = map.worldX((left + right) / 2.0), cz = map.worldZ((top + bottom) / 2.0);
        g.enableScissor(left, top, right, bottom);
        for (Layer layer : layers) {
            collect(layer);
            // сначала крупные плитки, что уже есть, — подложка, пока строятся нужные (при приближении карта не пустеет)
            for (int l = MAX_LEVEL; l > want; l--) draw(g, map, layer, visible(map, l, left, top, right, bottom), false);
            List<Key> keys = visible(map, want, left, top, right, bottom);
            if (!layer.source.offThread()) keys.removeIf(k -> !nearPlayer(k));
            draw(g, map, layer, keys, build);
            evict(layer);
            if (!build || layer.broken) continue;
            keys.sort(Comparator.comparingDouble(k -> Mth.square((k.tx + 0.5) * k.span() - cx) + Mth.square((k.tz + 0.5) * k.span() - cz)));
            request(current, layer, keys);
        }
        g.disableScissor();
    }

    /** Рельеф вдали есть (Distant Horizons), а не только в загруженных чанках. */
    public static boolean farTerrain() {
        return layers.size() > 1;
    }

    /** Distant Horizons стоит, но ещё не отдал ни одной плитки с рельефом, а спрошен (занят своей загрузкой). */
    public static boolean farPending() {
        if (layers.size() < 2) return false;
        Layer far = layers.get(0);
        return far.withData == 0 && far.jobs > 0;
    }

    /** Высота земли в колонке: по самой подробной готовой плитке, чанки клиента первыми. */
    public static OptionalInt height(int x, int z) {
        for (int i = layers.size() - 1; i >= 0; i--) {
            Map<Key, Tile> tiles = layers.get(i).tiles;
            for (int l = 0; l <= MAX_LEVEL; l++) {
                int span = SIZE << l;
                Tile t = tiles.get(new Key(l, Math.floorDiv(x, span), Math.floorDiv(z, span)));
                if (t == null || t.heights == null) continue;
                int px = Math.floorMod(x, span) >> l, pz = Math.floorMod(z, span) >> l;
                int h = t.heights[pz * SIZE + px];
                if (h != NO_HEIGHT) return OptionalInt.of(h);
            }
        }
        return OptionalInt.empty();
    }

    /** Для лога и сценария: по слоям — сколько плиток построено и с рельефом, сколько строится, что отдал источник. */
    public static String stats() {
        StringBuilder b = new StringBuilder();
        long now = System.nanoTime();
        for (Layer layer : layers) {
            long oldest = 0;
            for (Tile t : layer.tiles.values()) {
                if (t.buildingSince != 0) oldest = Math.max(oldest, now - t.buildingSince);
            }
            if (!b.isEmpty()) b.append("; ");
            b.append(String.format(Locale.ROOT, "%s: плиток %d, с рельефом %d, в среднем %.0f мс, строится %d (дольше всех %.1f с)",
                    layer.name, layer.built, layer.withData, layer.built == 0 ? 0.0 : layer.buildNanos / 1e6 / layer.built,
                    layer.jobs, oldest / 1e9));
            String src = layer.source.describe();
            if (!src.isEmpty()) b.append(", ").append(src);
        }
        return b.toString();
    }

    /** Выход из мира: текстуры освобождены, фоновые плитки для него больше не нужны. */
    public static void reset() {
        for (Layer layer : layers) {
            for (Tile t : layer.tiles.values()) release(t);
        }
        layers = List.of();
        level = null;
        generation++;
    }

    /**
     * При запуске клиента: есть ли DH с нашей версией API, и подписка на его события. Класс DH-источника трогаем,
     * только если DH стоит: без него ссылки на API не разрешатся.
     */
    public static void init() {
        distantHorizons = ModList.get().isLoaded("distanthorizons") && DistantHorizonsTerrain.supported();
        if (distantHorizons) DistantHorizonsTerrain.subscribe();
    }

    private static void switchTo(ClientLevel current) {
        reset();
        level = current;
        Layer chunks = new Layer("chunks", new LoadedChunksTerrain());
        layers = distantHorizons ? List.of(new Layer("dh", new DistantHorizonsTerrain()), chunks) : List.of(chunks);
    }

    /** Плитка задевает дальность прорисовки клиента: дальше чанков у него нет. */
    private static boolean nearPlayer(Key key) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return false;
        int r = (mc.options.getEffectiveRenderDistance() + 1) * 16, span = key.span();
        double px = mc.player.getX(), pz = mc.player.getZ();
        double x0 = key.tx * (double) span, z0 = key.tz * (double) span;
        return x0 < px + r && x0 + span > px - r && z0 < pz + r && z0 + span > pz - r;
    }

    private static List<Key> visible(MapProjection map, int l, int left, int top, int right, int bottom) {
        int span = SIZE << l;
        int x0 = Mth.floor(map.worldX(left) / span), x1 = Mth.floor(map.worldX(right) / span);
        int z0 = Mth.floor(map.worldZ(top) / span), z1 = Mth.floor(map.worldZ(bottom) / span);
        List<Key> keys = new ArrayList<>((x1 - x0 + 1) * (z1 - z0 + 1));
        for (int tz = z0; tz <= z1; tz++) {
            for (int tx = x0; tx <= x1; tx++) keys.add(new Key(l, tx, tz));
        }
        return keys;
    }

    /** @param create завести плитку, если её нет (иначе рисуются только готовые) */
    private static void draw(GuiGraphics g, MapProjection map, Layer layer, List<Key> keys, boolean create) {
        for (Key key : keys) {
            Tile t = create ? layer.tiles.computeIfAbsent(key, Tile::new) : layer.tiles.get(key);
            if (t == null) continue;
            t.drawnFrame = frame;
            if (t.id == null) continue;
            int span = key.span();
            float x = (float) (map.ox() + (key.tx * (double) span - map.cx()) * map.k());
            float y = (float) (map.oy() + (key.tz * (double) span - map.cz()) * map.k());
            float s = (float) (span * map.k() / SIZE);
            g.pose().pushPose();
            g.pose().translate(x, y, 0);
            g.pose().scale(s, s, 1);
            g.blit(t.id, 0, 0, 0, 0, SIZE, SIZE, SIZE, SIZE);
            g.pose().popPose();
        }
    }

    private static void request(ClientLevel current, Layer layer, List<Key> keys) {
        TerrainSource src = layer.source;
        long now = System.nanoTime();
        long deadline = now + FRAME_BUDGET_NS;
        TerrainSource.Reader inFrame = null;
        try {
            for (Key key : keys) {
                Tile t = layer.tiles.get(key);
                if (t == null) continue;
                if (t.buildingSince != 0) {
                    stalled(layer, t, now);
                    continue;
                }
                if (t.builtAt != 0 && now - t.builtAt < (t.empty ? EMPTY_REFRESH_NS : REFRESH_NS)) continue;
                if (src.offThread()) {
                    if (layer.jobs >= MAX_JOBS) return;
                    TerrainSource.Reader reader = src.open(current);
                    if (reader == null) return;
                    t.buildingSince = now;
                    layer.jobs++;
                    int gen = generation;
                    executor().execute(() -> {
                        try (reader) {
                            layer.done.add(build(key, gen, reader));
                        } catch (RuntimeException e) {
                            Airstrike.LOG.warn("Карта: плитка {} ({}) не построена", key, layer.name, e);
                            layer.done.add(new Built(key, gen, new int[0], new int[0], true, 0));
                        } catch (LinkageError e) {
                            // другая версия API DH, чем при сборке: источник выключается до смены мира
                            Airstrike.LOG.error("Карта: источник {} несовместим, рельеф из него выключен", layer.name, e);
                            layer.broken = true;
                            layer.done.add(new Built(key, gen, new int[0], new int[0], true, 0));
                        }
                    });
                } else {
                    if (System.nanoTime() > deadline) return;
                    if (inFrame == null) inFrame = src.open(current);
                    if (inFrame == null) return;
                    apply(layer, build(key, generation, inFrame));
                }
            }
        } finally {
            if (inFrame != null) inFrame.close();
        }
    }

    private static void stalled(Layer layer, Tile t, long now) {
        if (layer.stallLogged || now - t.buildingSince < STALL_NS) return;
        layer.stallLogged = true;
        Airstrike.LOG.warn("Карта: источник {} не отдаёт плитку {} дольше {} с — рельеф вдали появится, когда он ответит; {}",
                layer.name, t.key, STALL_NS / 1_000_000_000L, stats());
    }

    /** Забрать плитки, построенные в фоне, и загрузить их в текстуры. */
    private static void collect(Layer layer) {
        for (Built b; (b = layer.done.poll()) != null; ) {
            if (b.generation != generation) continue;
            layer.jobs--;
            apply(layer, b);
        }
    }

    private static void apply(Layer layer, Built b) {
        Tile t = layer.tiles.get(b.key);
        if (t == null) return;
        t.buildingSince = 0;
        t.builtAt = System.nanoTime();
        layer.built++;
        layer.buildNanos += b.nanos;
        if (!b.empty) layer.withData++;
        if (!b.empty && !layer.firstLogged) {
            layer.firstLogged = true;
            Airstrike.LOG.info("Карта: первая плитка с рельефом из {} — {} за {} мс; {}", layer.name, b.key, b.nanos / 1_000_000, stats());
        }
        if (b.pixels.length == 0) return;
        t.empty = b.empty;
        t.heights = b.heights;
        if (t.texture == null) {
            t.texture = new DynamicTexture(SIZE, SIZE, false);
            t.id = Airstrike.id("map/" + layer.name + "/" + b.key.level + "/" + b.key.tx + "/" + b.key.tz);
            Minecraft.getInstance().getTextureManager().register(t.id, t.texture);
        }
        NativeImage image = t.texture.getPixels();
        if (image == null) return;
        for (int z = 0; z < SIZE; z++) {
            for (int x = 0; x < SIZE; x++) image.setPixelRGBA(x, z, b.pixels[z * SIZE + x]);
        }
        t.texture.upload();
    }

    /** Сверх предела — самые давние по показу, кроме видимых в этом кадре и строящихся. */
    private static void evict(Layer layer) {
        Iterator<Tile> it = layer.tiles.values().iterator();
        while (layer.tiles.size() > MAX_TILES && it.hasNext()) {
            Tile t = it.next();
            if (t.buildingSince != 0 || t.drawnFrame == frame) continue;
            release(t);
            it.remove();
        }
    }

    private static void release(Tile t) {
        if (t.id != null) Minecraft.getInstance().getTextureManager().release(t.id);
        t.texture = null;
        t.id = null;
    }

    private static ExecutorService executor() {
        if (executor == null) {
            executor = Executors.newFixedThreadPool(MAX_JOBS, new ThreadFactoryBuilder().setNameFormat("Airstrike map %d").setDaemon(true)
                    .setPriority(Thread.MIN_PRIORITY).build());
        }
        return executor;
    }

    /**
     * Плитка: колонка в середине каждой клетки и ряд севернее плитки — для тени первого ряда. Тень как у ванильной
     * карты ({@code MapItem.update}): склон к северу вверх — светлее, вниз — темнее; вода темнее с глубиной.
     */
    private static Built build(Key key, int gen, TerrainSource.Reader reader) {
        long start = System.nanoTime();
        int step = 1 << key.level, span = key.span(), off = step / 2;
        int x0 = key.tx * span + off, z0 = key.tz * span + off;
        int[] pixels = new int[SIZE * SIZE];
        int[] heights = new int[SIZE * SIZE];
        int[] north = new int[SIZE];
        for (int x = 0; x < SIZE; x++) {
            TerrainSource.Column c = reader.column(x0 + x * step, z0 - step);
            north[x] = c == null ? NO_HEIGHT : c.height();
        }
        boolean empty = true;
        for (int z = 0; z < SIZE; z++) {
            for (int x = 0; x < SIZE; x++) {
                TerrainSource.Column c = reader.column(x0 + x * step, z0 + z * step);
                int i = z * SIZE + x;
                if (c == null) {
                    heights[i] = NO_HEIGHT;
                    continue;
                }
                empty = false;
                heights[i] = c.height();
                pixels[i] = c.color().calculateRGBColor(shade(c, north[x], step, x + z));
            }
            System.arraycopy(heights, z * SIZE, north, 0, SIZE);
        }
        return new Built(key, gen, pixels, heights, empty, System.nanoTime() - start);
    }

    /**
     * Яркость клетки: у воды — по глубине, у суши — по перепаду высоты с северной соседкой (на крупной плитке
     * перепад делится на размер клетки). {@code parity} — шахматный узор ванильной карты, чтобы ровное не сливалось.
     */
    static MapColor.Brightness shade(TerrainSource.Column c, int northHeight, int step, int parity) {
        double dither = (parity & 1);
        if (c.waterDepth() > 0) {
            double f = c.waterDepth() * 0.1 + dither * 0.2;
            return f < 0.5 ? MapColor.Brightness.HIGH : f > 0.9 ? MapColor.Brightness.LOW : MapColor.Brightness.NORMAL;
        }
        if (northHeight == NO_HEIGHT) return MapColor.Brightness.NORMAL;
        double d = (c.height() - northHeight) * 4.0 / (step + 4) + (dither - 0.5) * 0.4;
        return d > 0.6 ? MapColor.Brightness.HIGH : d < -0.6 ? MapColor.Brightness.LOW : MapColor.Brightness.NORMAL;
    }
}
