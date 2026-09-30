package ua.zentix.airstrike.client.map;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.fml.ModList;
import org.jetbrains.annotations.Nullable;
import ua.zentix.airstrike.Airstrike;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Queue;
import java.util.Set;
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
 * стоит, DH грузит свои LOD): ждут только наши фоновые потоки, их не больше {@link #MAX_JOBS}, а слой чанков от них
 * не зависит. Готовые плитки загружаются в текстуры в потоке игры.
 * <p>
 * Каждое чтение DH — участок его базы 64×64 блока (распаковка, в очереди его потоков файлов), поэтому рельеф из DH
 * читается по возможности один раз: плитка крупнее собирается из четырёх готовых плиток уровнем ниже ({@link #compose}),
 * подробные плитки вокруг игрока строятся заранее, пока карта закрыта ({@link #tick}), — карта открывается на игроке
 * сразу; готовая плитка DH перестраивается по его событию изменения чанка, а не по часам. Плитки живут, пока открыт
 * мир, самые давние по показу вытесняются.
 */
public final class TerrainTiles {
    static final int SIZE = 64;
    /** Самая крупная плитка — 1024 блока (16 блоков на пиксель). */
    public static final int MAX_LEVEL = 4;
    /** Плиток в слое: экран подробных с подложкой крупных и запас вокруг игрока ({@link #PREFETCH_RADIUS}). */
    private static final int MAX_TILES = 768;
    /** Сколько плиток строится в фоне одновременно: DH читает свою базу, не забираем у игры больше. */
    private static final int MAX_JOBS = 2;
    /** Плитку с рельефом не везде (DH ещё досчитывает даль, чанк не пришёл) спрашиваем раз в столько. */
    private static final long PARTIAL_REFRESH_NS = 30_000_000_000L;
    /** Плитку без данных — чаще. */
    private static final long EMPTY_REFRESH_NS = 5_000_000_000L;
    /** Дольше этого чтение DH — в лог (один раз за мир): рельеф вдали не появится, пока DH не ответит. */
    private static final long STALL_NS = 30_000_000_000L;
    /** Сколько кадр строит плитки сам: из чанков клиента и из плиток уровнем ниже. */
    private static final long FRAME_BUDGET_NS = 3_000_000L;
    /** Заранее строятся подробные плитки фонового слоя в квадрате ±7 плиток вокруг игрока (≈ 900 блоков). */
    static final int PREFETCH_RADIUS = 7;
    /** Раз в столько тиков — какие плитки нужны заранее (игрок сместился). */
    private static final int PREFETCH_PERIOD = 10;
    /** Нет данных о высоте (северная соседка вне плитки и без оценки). */
    private static final int NO_HEIGHT = Integer.MIN_VALUE;

    record Key(int level, int tx, int tz) {
        int span() {
            return SIZE << level;
        }

        Key parent() {
            return new Key(level + 1, Math.floorDiv(tx, 2), Math.floorDiv(tz, 2));
        }
    }

    /**
     * Верх колонок плитки: высота, цвет ванильной карты (номер {@link MapColor#id}) и глубина воды — то, из чего
     * рисуются пиксели ({@link #paint}) и собираются крупные плитки. 16 КБ на плитку.
     */
    static final class Columns {
        static final short NONE = Short.MIN_VALUE;
        final short[] height = new short[SIZE * SIZE];
        final byte[] color = new byte[SIZE * SIZE];
        final byte[] depth = new byte[SIZE * SIZE];
        /** Колонок с данными. */
        int filled;

        Columns() {
            Arrays.fill(height, NONE);
        }

        boolean has(int i) {
            return height[i] != NONE;
        }

        void set(int i, TerrainSource.Column c) {
            height[i] = (short) Mth.clamp(c.height(), Short.MIN_VALUE + 1, Short.MAX_VALUE);
            color[i] = (byte) c.color().id;
            depth[i] = (byte) Math.min(c.waterDepth(), Byte.MAX_VALUE);
            filled++;
        }

        void copy(int i, Columns from, int j) {
            if (!from.has(j)) return;
            height[i] = from.height[j];
            color[i] = from.color[j];
            depth[i] = from.depth[j];
            filled++;
        }

        boolean empty() {
            return filled == 0;
        }

        boolean complete() {
            return filled == SIZE * SIZE;
        }
    }

    private static final class Tile {
        final Key key;
        @Nullable
        DynamicTexture texture;
        @Nullable
        ResourceLocation id;
        /** Данные последней постройки; null — ещё не было (или постройка не удалась). */
        @Nullable
        Columns columns;
        long builtAt;
        /** Кадр, в котором плитку последний раз рисовали: видимые сейчас не вытесняются. */
        long drawnFrame;
        /** Когда начата фоновая постройка; 0 — не строится. */
        long buildingSince;
        /**
         * Рельеф под плиткой изменился (событие источника, перестроена плитка уровнем ниже) — перестроить раньше
         * обычного. Снимается, когда постройка начата: изменение во время постройки её снова ставит.
         */
        boolean stale;

        Tile(Key key) {
            this.key = key;
        }
    }

    /** Готовая плитка: колонки (null — не построена, её спросят снова, как пустую), пиксели ABGR, сколько строилась. */
    record Built(Key key, int generation, @Nullable Columns columns, int[] pixels, long nanos) {
        static Built failed(Key key, int generation) {
            return new Built(key, generation, null, new int[0], 0);
        }
    }

    /** Плитки одного источника рельефа. */
    static final class Layer {
        final String name;
        final TerrainSource source;
        /** Порядок доступа: первая — давно не показанная. */
        final Map<Key, Tile> tiles = new LinkedHashMap<>(256, 0.75f, true);
        final Queue<Built> done = new ConcurrentLinkedQueue<>();
        /** Плитки, которые строятся заранее вокруг игрока: не вытесняются. */
        Set<Key> prefetch = Set.of();
        int jobs;
        boolean stallLogged, firstLogged;
        /** Источник сломался (другая версия API DH): слой больше не строится до смены мира. Пишет фоновый поток. */
        volatile boolean broken;
        /** Для лога: построено из источника, из них с рельефом, время их постройки; собрано из плиток уровнем ниже. */
        int built, withData, composed;
        long buildNanos;

        Layer(String name, TerrainSource source) {
            this.name = name;
            this.source = source;
        }
    }

    /** Сколько плиток нужного масштаба видно и сколько из них уже построено — для лога времени открытия карты. */
    public record Progress(int visible, int ready) {
        public boolean done() {
            return ready >= visible;
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
    private static int ticks;
    private static Progress progress = new Progress(0, 0);

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
        int visible = 0, ready = 0;
        g.enableScissor(left, top, right, bottom);
        for (Layer layer : layers) {
            collect(layer);
            if (!build) {
                // клеток кадра на такой карте — сотни тысяч, а нового ничего не заводится: рисуются готовые плитки
                // самого крупного уровня, перебором плиток слоя (их не больше MAX_TILES)
                draw(g, map, layer, ready(layer, map, left, top, right, bottom), false);
                continue;
            }
            // сначала крупные плитки, что уже есть, — подложка, пока строятся нужные (при приближении карта не пустеет)
            for (int l = MAX_LEVEL; l > want; l--) draw(g, map, layer, visible(map, l, left, top, right, bottom), false);
            List<Key> keys = visible(map, want, left, top, right, bottom);
            if (!layer.source.offThread()) keys.removeIf(k -> !nearPlayer(k));
            draw(g, map, layer, keys, true);
            evict(layer);
            if (layer.broken) continue;
            keys.sort(Comparator.comparingDouble(k -> Mth.square((k.tx + 0.5) * k.span() - cx) + Mth.square((k.tz + 0.5) * k.span() - cz)));
            request(current, layer, keys, false);
            visible += keys.size();
            for (Key k : keys) {
                Tile t = layer.tiles.get(k);
                if (t != null && t.builtAt != 0) ready++;
            }
        }
        g.disableScissor();
        progress = new Progress(visible, ready);
    }

    /** Раз в тик клиента: плитки из фона — в текстуры, изменения рельефа от источников, заранее — плитки вокруг игрока. */
    public static void tick() {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel current = mc.level;
        LocalPlayer player = mc.player;
        if (current == null || player == null) return;
        if (current != level) switchTo(current);
        for (Layer layer : layers) {
            collect(layer);
            layer.source.changes(current, (chunkX, chunkZ) -> changed(layer, chunkX, chunkZ));
        }
        if (++ticks % PREFETCH_PERIOD != 0) return;
        for (Layer layer : layers) {
            // заранее — только из фона: слой чанков строится в кадре и быстро, а чанки у игрока и так под рукой
            if (!layer.source.offThread() || layer.broken) continue;
            List<Key> keys = around(player.getX(), player.getZ());
            layer.prefetch = new HashSet<>(keys);
            for (Key k : keys) layer.tiles.computeIfAbsent(k, Tile::new);
            evict(layer);
            request(current, layer, keys, true);
        }
    }

    /** Что построено из плиток, которые рисовались в последнем кадре карты. */
    public static Progress progress() {
        return progress;
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
                if (t == null || t.columns == null) continue;
                int px = Math.floorMod(x, span) >> l, pz = Math.floorMod(z, span) >> l;
                int j = pz * SIZE + px;
                if (t.columns.has(j)) return OptionalInt.of(t.columns.height[j]);
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
            b.append(String.format(Locale.ROOT, "%s: плиток %d, с рельефом %d, в среднем %.0f мс, из мелких %d, в памяти %d, строится %d (дольше всех %.1f с)",
                    layer.name, layer.built, layer.withData, layer.built == 0 ? 0.0 : layer.buildNanos / 1e6 / layer.built,
                    layer.composed, layer.tiles.size(), layer.jobs, oldest / 1e9));
            String src = layer.source.describe();
            if (!src.isEmpty()) b.append(", ").append(src);
        }
        return b.toString();
    }

    /**
     * Выход из мира или смена измерения: текстуры освобождены, фоновые плитки для него больше не нужны. Потоки —
     * новые: чтение DH для старого мира может ждать сколько угодно, и новый мир стоял бы за ним в очереди. Старые
     * потоки прерываются и кончаются сами (демоны), их плитки — старого поколения, выбрасываются.
     */
    public static void reset() {
        for (Layer layer : layers) {
            for (Tile t : layer.tiles.values()) release(t);
        }
        layers = List.of();
        level = null;
        generation++;
        progress = new Progress(0, 0);
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    /**
     * При запуске клиента: есть ли DH с нашей версией API, и подписка на его события. Класс DH-источника трогаем,
     * только если DH стоит: без него ссылки на API не разрешатся.
     */
    public static void init() {
        if (!ModList.get().isLoaded("distanthorizons")) return;
        try {
            distantHorizons = DistantHorizonsTerrain.supported();
            if (distantHorizons) DistantHorizonsTerrain.subscribe();
        } catch (LinkageError e) {
            // API DH без нужных классов или методов (DH новее, чем мод знает): игра грузится, рельеф — из чанков
            Airstrike.LOG.warn("Distant Horizons: API не то, под которое собран мод, — дальний рельеф на карте выключен", e);
            distantHorizons = false;
        }
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

    /** Подробные плитки в квадрате {@link #PREFETCH_RADIUS} вокруг точки, ближние первыми. */
    static List<Key> around(double x, double z) {
        int cx = Math.floorDiv(Mth.floor(x), SIZE), cz = Math.floorDiv(Mth.floor(z), SIZE), r = PREFETCH_RADIUS;
        List<Key> keys = new ArrayList<>((2 * r + 1) * (2 * r + 1));
        for (int tz = cz - r; tz <= cz + r; tz++) {
            for (int tx = cx - r; tx <= cx + r; tx++) keys.add(new Key(0, tx, tz));
        }
        keys.sort(Comparator.comparingInt(k -> Mth.square(k.tx - cx) + Mth.square(k.tz - cz)));
        return keys;
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

    /** Готовые плитки самого крупного уровня, задевающие прямоугольник экрана. */
    private static List<Key> ready(Layer layer, MapProjection map, int left, int top, int right, int bottom) {
        double x0 = map.worldX(left), x1 = map.worldX(right), z0 = map.worldZ(top), z1 = map.worldZ(bottom);
        List<Key> keys = new ArrayList<>();
        for (Key key : layer.tiles.keySet()) {
            double span = key.span();
            if (key.level == MAX_LEVEL && key.tx * span < x1 && (key.tx + 1) * span > x0 && key.tz * span < z1 && (key.tz + 1) * span > z0) {
                keys.add(key);
            }
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

    /**
     * Нужна ли плитке постройка: 0 — сейчас (ещё не строилась, рельеф под ней изменился), 1 — пора обновить
     * (пустую — через {@link #EMPTY_REFRESH_NS}, неполную — через {@link #PARTIAL_REFRESH_NS}, полную — как велит
     * источник), −1 — не нужна или уже строится.
     */
    static int urgency(boolean building, long builtAt, boolean stale, @Nullable Columns columns, long refreshNanos, long now) {
        if (building) return -1;
        if (builtAt == 0 || stale) return 0;
        long period = columns == null || columns.empty() ? EMPTY_REFRESH_NS : columns.complete() ? refreshNanos : PARTIAL_REFRESH_NS;
        return now - builtAt >= period ? 1 : -1;
    }

    /**
     * Построить нужные из {@code keys} (ближние первыми): сначала те, которых нет, потом устаревшие. Крупную плитку —
     * из четырёх готовых уровнем ниже, если они есть; иначе из источника: в фоне ({@link #MAX_JOBS} сразу) или в кадре
     * под {@link #FRAME_BUDGET_NS}. {@code prefetch} — заранее, пока карту не смотрят: только недостающие и устаревшие.
     */
    private static void request(ClientLevel current, Layer layer, List<Key> keys, boolean prefetch) {
        TerrainSource src = layer.source;
        long now = System.nanoTime();
        long deadline = now + FRAME_BUDGET_NS;
        List<Tile> todo = new ArrayList<>();
        List<Tile> later = new ArrayList<>();
        for (Key key : keys) {
            Tile t = layer.tiles.get(key);
            if (t == null) continue;
            if (t.buildingSince != 0) {
                stalled(layer, t, now);
                continue;
            }
            int u = urgency(false, t.builtAt, t.stale, t.columns, src.refreshNanos(), now);
            if (u == 0) todo.add(t);
            else if (u == 1 && !prefetch) later.add(t);
        }
        todo.addAll(later);
        TerrainSource.Reader inFrame = null;
        try {
            for (Tile t : todo) {
                if (System.nanoTime() > deadline) return;
                Columns fromChildren = compose(layer, t.key);
                if (fromChildren != null) {
                    t.stale = false;
                    layer.composed++;
                    apply(layer, new Built(t.key, generation, fromChildren, paint(fromChildren, t.key.level), 0), false);
                    continue;
                }
                if (src.offThread()) {
                    if (layer.jobs >= MAX_JOBS) continue;
                    TerrainSource.Reader reader = src.open(current);
                    if (reader == null) return;
                    t.buildingSince = now;
                    t.stale = false;
                    layer.jobs++;
                    int gen = generation;
                    Key key = t.key;
                    executor().execute(() -> buildOffThread(layer, key, gen, reader));
                } else {
                    if (inFrame == null) inFrame = src.open(current);
                    if (inFrame == null) return;
                    t.stale = false;
                    apply(layer, build(t.key, generation, inFrame), true);
                }
            }
        } finally {
            if (inFrame != null) inFrame.close();
        }
    }

    /**
     * Крупная плитка из четырёх плиток уровнем ниже, если все они уже строились: пиксель — клетка ребёнка у середины
     * своей клетки. Рельеф, прочитанный из источника раз, служит всем масштабам. Null — детей нет, читать источник.
     */
    @Nullable
    private static Columns compose(Layer layer, Key key) {
        if (key.level == 0) return null;
        Columns[] kids = new Columns[4];
        for (int i = 0; i < 4; i++) {
            Tile kid = layer.tiles.get(new Key(key.level - 1, key.tx * 2 + (i & 1), key.tz * 2 + (i >> 1)));
            if (kid == null || kid.columns == null) return null;
            kids[i] = kid.columns;
        }
        return compose(kids);
    }

    /** Сборка из детей в порядке: северо-запад, северо-восток, юго-запад, юго-восток. */
    static Columns compose(Columns[] kids) {
        Columns c = new Columns();
        for (int z = 0; z < SIZE; z++) {
            int kz = 2 * z + 1;
            for (int x = 0; x < SIZE; x++) {
                int kx = 2 * x + 1;
                Columns kid = kids[(kz / SIZE) * 2 + kx / SIZE];
                c.copy(z * SIZE + x, kid, (kz % SIZE) * SIZE + kx % SIZE);
            }
        }
        return c;
    }

    /**
     * Постройка плитки в фоновом потоке. Плитка возвращается в слой при любом исходе, и при {@link Error} (оно летит
     * дальше): иначе задача слоя и отметка «строится» у плитки остались бы навсегда, а с ними — и слой.
     */
    static void buildOffThread(Layer layer, Key key, int gen, TerrainSource.Reader reader) {
        Built result = Built.failed(key, gen);
        try (reader) {
            result = build(key, gen, reader);
        } catch (RuntimeException e) {
            Airstrike.LOG.warn("Карта: плитка {} ({}) не построена", key, layer.name, e);
        } catch (LinkageError e) {
            // другая версия API DH, чем при сборке: источник выключается до смены мира
            Airstrike.LOG.error("Карта: источник {} несовместим, рельеф из него выключен", layer.name, e);
            layer.broken = true;
        } finally {
            layer.done.add(result);
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
            apply(layer, b, true);
        }
    }

    /** Источник сообщил об изменении чанка: плитки над ним на всех уровнях перестроятся, когда понадобятся. */
    private static void changed(Layer layer, int chunkX, int chunkZ) {
        for (int l = 0; l <= MAX_LEVEL; l++) {
            int span = SIZE << l;
            Tile t = layer.tiles.get(new Key(l, Math.floorDiv(chunkX * 16, span), Math.floorDiv(chunkZ * 16, span)));
            if (t != null) t.stale = true;
        }
    }

    /** @param fromSource плитка прочитана из источника (для лога; собранные из мелких считаются отдельно) */
    private static void apply(Layer layer, Built b, boolean fromSource) {
        Tile t = layer.tiles.get(b.key);
        if (t == null) return;
        t.buildingSince = 0;
        t.builtAt = System.nanoTime();
        Columns c = b.columns;
        if (fromSource) {
            layer.built++;
            layer.buildNanos += b.nanos;
            if (c != null && !c.empty()) layer.withData++;
            if (c != null && !c.empty() && !layer.firstLogged) {
                layer.firstLogged = true;
                Airstrike.LOG.info("Карта: первая плитка с рельефом из {} — {} за {} мс; {}", layer.name, b.key, b.nanos / 1_000_000, stats());
            }
        }
        if (c == null) return;
        t.columns = c;
        // плитка уровнем выше собрана из этой (или прочитана раньше): пусть соберётся заново
        if (b.key.level < MAX_LEVEL) {
            Tile parent = layer.tiles.get(b.key.parent());
            if (parent != null) parent.stale = true;
        }
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

    /** Сверх предела — самые давние по показу, кроме видимых в этом кадре, нужных заранее и строящихся. */
    private static void evict(Layer layer) {
        Iterator<Tile> it = layer.tiles.values().iterator();
        while (layer.tiles.size() > MAX_TILES && it.hasNext()) {
            Tile t = it.next();
            if (t.buildingSince != 0 || t.drawnFrame == frame || layer.prefetch.contains(t.key)) continue;
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

    /** Плитка из источника: колонка в середине каждой клетки. */
    private static Built build(Key key, int gen, TerrainSource.Reader reader) {
        long start = System.nanoTime();
        int step = 1 << key.level, span = key.span(), off = step / 2;
        int x0 = key.tx * span + off, z0 = key.tz * span + off;
        Columns c = new Columns();
        for (int z = 0; z < SIZE; z++) {
            for (int x = 0; x < SIZE; x++) {
                TerrainSource.Column col = reader.column(x0 + x * step, z0 + z * step);
                if (col != null) c.set(z * SIZE + x, col);
            }
        }
        return new Built(key, gen, c, paint(c, key.level), System.nanoTime() - start);
    }

    /**
     * Пиксели плитки (ABGR, как у {@link NativeImage}); без данных — прозрачный. Тень как у ванильной карты
     * ({@code MapItem.update}) — по перепаду с северной соседкой; у первого ряда соседка за краем плитки, и её высота
     * оценивается по склону к южной (чтение соседней плитки стоило бы ещё участка источника на каждую плитку).
     */
    static int[] paint(Columns c, int level) {
        int step = 1 << level;
        int[] pixels = new int[SIZE * SIZE];
        for (int z = 0; z < SIZE; z++) {
            for (int x = 0; x < SIZE; x++) {
                int i = z * SIZE + x;
                if (!c.has(i)) continue;
                int h = c.height[i], north;
                if (z > 0) north = c.has(i - SIZE) ? c.height[i - SIZE] : NO_HEIGHT;
                else north = c.has(i + SIZE) ? 2 * h - c.height[i + SIZE] : NO_HEIGHT;
                pixels[i] = MapColor.byId(c.color[i] & 0x3F).calculateRGBColor(shade(h, c.depth[i], north, step, x + z));
            }
        }
        return pixels;
    }

    /**
     * Яркость клетки: у воды — по глубине, у суши — по перепаду высоты с северной соседкой (на крупной плитке
     * перепад делится на размер клетки). {@code parity} — шахматный узор ванильной карты, чтобы ровное не сливалось.
     */
    static MapColor.Brightness shade(int height, int waterDepth, int northHeight, int step, int parity) {
        double dither = (parity & 1);
        if (waterDepth > 0) {
            double f = waterDepth * 0.1 + dither * 0.2;
            return f < 0.5 ? MapColor.Brightness.HIGH : f > 0.9 ? MapColor.Brightness.LOW : MapColor.Brightness.NORMAL;
        }
        if (northHeight == NO_HEIGHT) return MapColor.Brightness.NORMAL;
        double d = (height - northHeight) * 4.0 / (step + 4) + (dither - 0.5) * 0.4;
        return d > 0.6 ? MapColor.Brightness.HIGH : d < -0.6 ? MapColor.Brightness.LOW : MapColor.Brightness.NORMAL;
    }
}
