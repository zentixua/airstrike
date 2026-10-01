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
import net.neoforged.neoforge.common.NeoForge;
import org.jetbrains.annotations.Nullable;
import ua.zentix.airstrike.Airstrike;
import ua.zentix.airstrike.AirstrikeConfig;
import ua.zentix.airstrike.registry.ModItems;

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
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
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
 * подробные плитки вокруг игрока строятся заранее, пока карта закрыта ({@link #tick}): из DH — в фоне, из чанков
 * клиента — в тике по частям под сроком {@link #PREFETCH_TICK_NS}, — карта открывается на игроке сразу; готовая
 * плитка DH перестраивается по его событию изменения чанка, а не по часам, и перестроенная мелкая плитка сразу
 * копируется в свою четверть крупных. Плитки живут, пока открыт мир, самые давние по показу вытесняются.
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
    /**
     * Сколько тик клиента строит заранее плитки слоя, который читается только в потоке игры (чанки клиента), — по
     * часам, плитка читается частями между тиками ({@link Partial}): срок проверяется перед каждой колонкой.
     */
    static final long PREFETCH_TICK_NS = 2_000_000L;
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

        /** Клетка {@code i} — как клетка {@code j} плитки {@code from}; без данных там — и здесь без данных. */
        void copy(int i, Columns from, int j) {
            if (has(i)) filled--;
            height[i] = from.height[j];
            color[i] = from.color[j];
            depth[i] = from.depth[j];
            if (has(i)) filled++;
        }

        /**
         * Четверть плитки ({@code q}: 0 — северо-запад, 1 — северо-восток, 2 — юго-запад, 3 — юго-восток) — из плитки
         * уровнем ниже на её месте: пиксель — клетка ребёнка у середины своей клетки.
         */
        void putQuarter(int q, Columns kid) {
            int half = SIZE / 2, x0 = (q & 1) * half, z0 = (q >> 1) * half;
            for (int z = 0; z < half; z++) {
                for (int x = 0; x < half; x++) copy((z0 + z) * SIZE + x0 + x, kid, (2 * z + 1) * SIZE + 2 * x + 1);
            }
        }

        boolean empty() {
            return filled == 0;
        }

        boolean complete() {
            return filled == SIZE * SIZE;
        }
    }

    static final class Tile {
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
         * Источник сообщил, что рельеф под плиткой изменился, — перестроить раньше обычного. Снимается, когда постройка
         * начата: изменение во время постройки её снова ставит. Перестроенная плитка уровнем ниже её не ставит — она
         * сразу копируется в свою четверть ({@link #propagate}).
         */
        boolean stale;

        Tile(Key key) {
            this.key = key;
        }
    }

    /**
     * Готовая плитка: колонки (null — не построена, её спросят снова, как пустую), сколько строилась. Пиксели рисует
     * поток игры: первому ряду нужна плитка к северу.
     */
    record Built(Key key, int generation, @Nullable Columns columns, long nanos) {
        static Built failed(Key key, int generation) {
            return new Built(key, generation, null, 0);
        }
    }

    /** Дети крупной плитки: все свежие — собрать из них; кто-то строится или пора обновить — подождать; кого-то нет — читать источник. */
    enum Kids { READY, REFRESHING, ABSENT }

    /** Плитки одного источника рельефа. */
    static final class Layer {
        final String name;
        final TerrainSource source;
        /** Порядок доступа: первая — давно не показанная. */
        final Map<Key, Tile> tiles = new LinkedHashMap<>(256, 0.75f, true);
        final Queue<Built> done = new ConcurrentLinkedQueue<>();
        /** Плитки, которые строятся заранее вокруг игрока: не вытесняются. */
        Set<Key> prefetch = Set.of();
        /** Они же, ближние первыми. */
        List<Key> prefetchOrder = List.of();
        /** Плитка, которую слой в потоке игры строит заранее по частям; null — сейчас никакая. */
        @Nullable
        Partial partial;
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

    /**
     * Сколько плиток нужного масштаба видно и сколько из них уже построено — для лога времени открытия карты;
     * {@code layers} — то же по слоям («dh 32/32, chunks 0/32»): видно, какой слой отстаёт.
     */
    public record Progress(int visible, int ready, String layers) {
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
    /**
     * Меняется при смене мира: плитки, начатые для старого, выбрасываются, а фоновая постройка останавливается между
     * колонками (поток её читает — {@code volatile}).
     */
    private static volatile int generation;
    /** DH стоит, и его API совместимо ({@link #init}). */
    private static boolean distantHorizons;
    /** Счётчик кадров карты (для вытеснения только невидимых плиток). */
    private static long frame;
    private static int ticks;
    private static Progress progress = new Progress(0, 0, "");
    /** Рельеф вокруг игрока строится заранее ({@link #prefetchWanted}); проверяется раз в {@link #PREFETCH_PERIOD}. */
    private static boolean prefetching;
    /** Карту в этом мире уже открывали: рельеф вокруг игрока строится заранее и без пульта в руках. */
    private static boolean opened;

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
        StringBuilder byLayer = new StringBuilder();
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
            int built = 0;
            for (Key k : keys) {
                Tile t = layer.tiles.get(k);
                if (t != null && t.builtAt != 0) built++;
            }
            visible += keys.size();
            ready += built;
            if (!byLayer.isEmpty()) byLayer.append(", ");
            byLayer.append(layer.name).append(' ').append(built).append('/').append(keys.size());
        }
        g.disableScissor();
        progress = new Progress(visible, ready, byLayer.toString());
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
            if (layer.source.arrivals()) layer.source.changes(current, (chunkX, chunkZ) -> arrived(layer, chunkX, chunkZ));
            else layer.source.changes(current, (chunkX, chunkZ) -> changed(layer, chunkX, chunkZ));
        }
        if (++ticks % PREFETCH_PERIOD == 0) {
            prefetching = prefetchWanted(AirstrikeConfig.CLIENT.mapPrefetch.get(), opened,
                    () -> player.getInventory().contains(s -> s.is(ModItems.DESIGNATOR.get())));
            for (Layer layer : layers) {
                if (layer.broken) continue;
                if (!prefetching) {
                    layer.prefetch = Set.of();
                    layer.prefetchOrder = List.of();
                    dropPartial(layer);
                    continue;
                }
                List<Key> keys = around(player.getX(), player.getZ());
                // слой чанков — только там, где у клиента есть чанки
                if (!layer.source.offThread()) keys.removeIf(k -> !nearPlayer(k));
                layer.prefetch = new HashSet<>(keys);
                layer.prefetchOrder = keys;
                for (Key k : keys) layer.tiles.computeIfAbsent(k, Tile::new);
                evict(layer);
                if (layer.source.offThread()) request(current, layer, keys, true);
            }
        }
        // слой чанков читается только в потоке игры: каждый тик, частями, под сроком по часам; без пульта и карты — ничего
        if (!prefetching) return;
        long deadline = System.nanoTime() + PREFETCH_TICK_NS;
        for (Layer layer : layers) {
            if (layer.source.offThread() || layer.broken) continue;
            prefetchInGameThread(layer, true, () -> layer.source.open(current), System::nanoTime, deadline, b -> apply(layer, b, true));
        }
    }

    /**
     * Рельеф заранее — только тем, кому карта нужна: включено в настройках клиента, и пульт в инвентаре или карту
     * в этом мире уже открывали. Иначе DH читал бы свою базу у каждого игрока со сборкой всю игру, а тик клиента
     * строил бы плитки чанков. Инвентарь смотрится, только если первые два условия не решили.
     */
    static boolean prefetchWanted(boolean enabled, boolean opened, BooleanSupplier hasDesignator) {
        return enabled && (opened || hasDesignator.getAsBoolean());
    }

    /**
     * Плитка слоя, который читается только в потоке игры, построенная заранее не целиком: колонки до {@code next}
     * (по рядам) прочитаны. Дочитывается в следующих тиках; пока она начата, у плитки стоит {@code buildingSince},
     * и карта в кадре её не строит второй раз.
     */
    static final class Partial {
        final Key key;
        final long started;
        final Columns columns = new Columns();
        int next;
        long nanos;

        Partial(Key key, long started) {
            this.key = key;
            this.started = started;
        }
    }

    /**
     * Заранее, в потоке игры, — плитки слоя из {@link Layer#prefetchOrder} (ближние первыми), которых нет, которые
     * устарели или неполны по их часам, по одной колонке, пока часы {@code clock} не дошли до {@code deadline}: начатая
     * плитка продолжается со следующего тика, готовая уходит в {@code sink}; начатая плитка, которая больше не нужна
     * заранее (игрок ушёл, телепорт), бросается не дочитанной. Не нужно ({@code wanted} false) — начатая бросается,
     * а источник и часы не трогаются.
     */
    static void prefetchInGameThread(Layer layer, boolean wanted, Supplier<TerrainSource.Reader> open, LongSupplier clock, long deadline,
                                     Consumer<Built> sink) {
        if (!wanted) {
            dropPartial(layer);
            return;
        }
        if (layer.partial != null && !layer.prefetch.contains(layer.partial.key)) dropPartial(layer);
        if (layer.partial == null && (layer.partial = nextPartial(layer, clock.getAsLong())) == null) return;
        TerrainSource.Reader reader = open.get();
        if (reader == null) return;
        try (reader) {
            while (layer.partial != null) {
                Partial p = layer.partial;
                long start = clock.getAsLong();
                // подробная плитка: колонка — каждый блок
                int x0 = p.key.tx * SIZE, z0 = p.key.tz * SIZE;
                boolean late = false;
                while (p.next < SIZE * SIZE) {
                    if (clock.getAsLong() >= deadline) {
                        late = true;
                        break;
                    }
                    int x = p.next % SIZE, z = p.next / SIZE;
                    TerrainSource.Column col = reader.column(x0 + x, z0 + z);
                    if (col != null) p.columns.set(p.next, col);
                    p.next++;
                }
                long now = clock.getAsLong();
                p.nanos += now - start;
                if (late) return;
                Tile t = layer.tiles.get(p.key);
                dropPartial(layer);
                // за это время плитку построили иначе (или её вытеснили) — эта уже не новее
                if (t != null && t.builtAt <= p.started) sink.accept(new Built(p.key, generation, p.columns, p.nanos));
                if (now >= deadline || (layer.partial = nextPartial(layer, now)) == null) return;
            }
        }
    }

    /** Следующая плитка, которую слой строит заранее: как {@link #request} с {@code prefetch}; отмечена «строится». */
    @Nullable
    private static Partial nextPartial(Layer layer, long now) {
        for (Key key : layer.prefetchOrder) {
            Tile t = layer.tiles.get(key);
            if (t == null || t.buildingSince != 0) continue;
            int u = urgency(false, t.builtAt, t.stale, t.columns, layer.source.refreshNanos(), now);
            if (u == 0 || u == 1 && (t.columns == null || !t.columns.complete())) {
                t.stale = false;
                t.buildingSince = now;
                return new Partial(key, now);
            }
        }
        return null;
    }

    /** Начатая заранее плитка больше не строится: снять с неё отметку «строится». */
    private static void dropPartial(Layer layer) {
        Partial p = layer.partial;
        if (p == null) return;
        layer.partial = null;
        Tile t = layer.tiles.get(p.key);
        if (t != null && t.buildingSince == p.started) t.buildingSince = 0;
    }

    /** Карта наведения открыта: дальше рельеф вокруг игрока строится заранее до выхода из мира. */
    public static void opened() {
        opened = true;
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
     * потоки не прерываются: прерывание посреди чтения DH он пишет в лог ошибкой ({@code InterruptedException} в
     * {@code getTerrainDataColumnArray}, выход из игры 30.09.2026). Их постройка видит новое поколение и
     * останавливается перед следующей колонкой, задачи из очереди кончаются сразу; потоки — демоны.
     */
    public static void reset() {
        for (Layer layer : layers) {
            for (Tile t : layer.tiles.values()) release(t);
        }
        layers = List.of();
        level = null;
        generation++;
        progress = new Progress(0, 0, "");
        opened = false;
        prefetching = false;
        // очередь событий DH держала бы обёртки мира, из которого вышли
        if (distantHorizons) DistantHorizonsTerrain.clearChanges();
        LoadedChunksTerrain.clearArrivals();
        if (executor != null) {
            executor.shutdown();
            executor = null;
        }
    }

    /**
     * При запуске клиента: подписка на приход чанков клиенту (слой чанков), есть ли DH с нашей версией API, и подписка
     * на его события. Класс DH-источника трогаем, только если DH стоит: без него ссылки на API не разрешатся.
     */
    public static void init() {
        NeoForge.EVENT_BUS.addListener(LoadedChunksTerrain::onChunkLoad);
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
     * из четырёх свежих уровнем ниже ({@link #kids}); если из них какие-то строятся или устарели — сперва они, а она
     * ждёт; если каких-то нет — из источника: в фоне ({@link #MAX_JOBS} сразу) или в кадре под {@link #FRAME_BUDGET_NS}.
     * {@code prefetch} — заранее, пока карту не смотрят: недостающие, устаревшие и неполные по их часам (полные
     * перечитываются по событию источника или когда их смотрят).
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
            else if (u == 1 && (!prefetch || t.columns == null || !t.columns.complete())) later.add(t);
        }
        todo.addAll(later);
        Set<Key> queued = new HashSet<>();
        for (Tile t : todo) queued.add(t.key);
        TerrainSource.Reader inFrame = null;
        try {
            // список растёт: детям крупной плитки, которых пора обновить, — место в конце
            for (int n = 0; n < todo.size(); n++) {
                Tile t = todo.get(n);
                if (System.nanoTime() > deadline) return;
                if (t.buildingSince != 0) continue;
                Tile[] kids = kidsOf(layer, t.key);
                switch (kids(kids, src.refreshNanos(), now)) {
                    case READY -> {
                        Columns c = new Columns();
                        for (int q = 0; q < 4; q++) c.putQuarter(q, kids[q].columns);
                        t.stale = false;
                        layer.composed++;
                        apply(layer, new Built(t.key, generation, c, 0), false);
                        continue;
                    }
                    case REFRESHING -> {
                        for (Tile kid : kids) {
                            if (kid.buildingSince == 0 && urgency(false, kid.builtAt, kid.stale, kid.columns, src.refreshNanos(), now) >= 0
                                    && queued.add(kid.key)) todo.add(kid);
                        }
                        continue;
                    }
                    case ABSENT -> {}
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
                    executor().execute(() -> buildOffThread(layer, key, gen, reader, () -> gen != generation));
                } else {
                    if (inFrame == null) inFrame = src.open(current);
                    if (inFrame == null) return;
                    t.stale = false;
                    apply(layer, build(t.key, generation, inFrame, () -> false), true);
                }
            }
        } finally {
            if (inFrame != null) inFrame.close();
        }
    }

    /** Четыре плитки уровнем ниже (северо-запад, северо-восток, юго-запад, юго-восток); у подробной — нет. */
    @Nullable
    private static Tile[] kidsOf(Layer layer, Key key) {
        if (key.level == 0) return null;
        Tile[] kids = new Tile[4];
        for (int q = 0; q < 4; q++) kids[q] = layer.tiles.get(new Key(key.level - 1, key.tx * 2 + (q & 1), key.tz * 2 + (q >> 1)));
        return kids;
    }

    /**
     * Можно ли собрать крупную плитку из детей. Рельеф, прочитанный из источника раз, служит всем масштабам, а плитка
     * из источника стоит столько же участков DH, сколько её четыре ребёнка вместе, — поэтому собирается из детей, когда
     * они все свежи: не устарели по событию и не пора их обновить по часам (неполного — {@link #PARTIAL_REFRESH_NS},
     * пустого — {@link #EMPTY_REFRESH_NS}: только что переспрошенный неполный ребёнок так же свеж, как источник, и
     * прочитанная заново крупная плитка была бы с теми же дырами). Строится или устарел кто-то из них — ждать его
     * (крупная плитка из источника прочла бы те же участки второй раз). Кого-то нет (или его постройка не удалась) —
     * {@link Kids#ABSENT}: читать источник.
     */
    static Kids kids(@Nullable Tile[] kids, long refreshNanos, long now) {
        if (kids == null) return Kids.ABSENT;
        boolean waiting = false;
        for (Tile kid : kids) {
            if (kid == null) return Kids.ABSENT;
            if (kid.buildingSince != 0) {
                waiting = true;
                continue;
            }
            if (kid.columns == null) return Kids.ABSENT;
            if (urgency(false, kid.builtAt, kid.stale, kid.columns, refreshNanos, now) >= 0) waiting = true;
        }
        return waiting ? Kids.REFRESHING : Kids.READY;
    }

    /** Сборка из детей в порядке: северо-запад, северо-восток, юго-запад, юго-восток. */
    static Columns compose(Columns[] kids) {
        Columns c = new Columns();
        for (int q = 0; q < 4; q++) c.putQuarter(q, kids[q]);
        return c;
    }

    /**
     * Постройка плитки в фоновом потоке. Плитка возвращается в слой при любом исходе, и при {@link Error} (оно летит
     * дальше): иначе задача слоя и отметка «строится» у плитки остались бы навсегда, а с ними — и слой.
     *
     * @param cancelled плитка больше не нужна (сменился мир): постройка кончается перед следующей колонкой
     */
    static void buildOffThread(Layer layer, Key key, int gen, TerrainSource.Reader reader, BooleanSupplier cancelled) {
        Built result = Built.failed(key, gen);
        try (reader) {
            result = build(key, gen, reader, cancelled);
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
    static void changed(Layer layer, int chunkX, int chunkZ) {
        for (int l = 0; l <= MAX_LEVEL; l++) {
            int span = SIZE << l;
            Tile t = layer.tiles.get(new Key(l, Math.floorDiv(chunkX * 16, span), Math.floorDiv(chunkZ * 16, span)));
            if (t != null) t.stale = true;
        }
    }

    /**
     * Источник прислал данные чанка, которых не было (чанк пришёл клиенту): перестроятся только плитки над ним с дырами
     * — у полной этот чанк уже был.
     */
    static void arrived(Layer layer, int chunkX, int chunkZ) {
        for (int l = 0; l <= MAX_LEVEL; l++) {
            int span = SIZE << l;
            Tile t = layer.tiles.get(new Key(l, Math.floorDiv(chunkX * 16, span), Math.floorDiv(chunkZ * 16, span)));
            if (t != null && t.builtAt != 0 && (t.columns == null || !t.columns.complete())) t.stale = true;
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
        repaint(layer, t);
        propagate(layer, t);
    }

    /**
     * Перестроенная плитка — в свою четверть плиток крупнее, что уже есть (до самой крупной): они не перечитывают
     * источник ради изменения, которое уже прочитано. Плитка крупнее, что строится из источника, получит своё.
     */
    private static void propagate(Layer layer, Tile t) {
        for (Tile kid = t; kid.key.level < MAX_LEVEL; ) {
            Tile parent = layer.tiles.get(kid.key.parent());
            if (parent == null || parent.columns == null || parent.buildingSince != 0 || kid.columns == null) return;
            parent.columns.putQuarter((Math.floorMod(kid.key.tz, 2) << 1) | Math.floorMod(kid.key.tx, 2), kid.columns);
            repaint(layer, parent);
            kid = parent;
        }
    }

    /** Пиксели плитки и её южной соседки: у той первый ряд тенится по последнему ряду этой. */
    private static void repaint(Layer layer, Tile t) {
        upload(layer, t);
        Tile south = layer.tiles.get(new Key(t.key.level, t.key.tx, t.key.tz + 1));
        if (south != null && south.columns != null) upload(layer, south);
    }

    private static void upload(Layer layer, Tile t) {
        if (t.columns == null) return;
        Tile north = layer.tiles.get(new Key(t.key.level, t.key.tx, t.key.tz - 1));
        int[] pixels = paint(t.columns, t.key.level, north == null ? null : north.columns);
        if (t.texture == null) {
            t.texture = new DynamicTexture(SIZE, SIZE, false);
            t.id = Airstrike.id("map/" + layer.name + "/" + t.key.level + "/" + t.key.tx + "/" + t.key.tz);
            Minecraft.getInstance().getTextureManager().register(t.id, t.texture);
        }
        NativeImage image = t.texture.getPixels();
        if (image == null) return;
        for (int z = 0; z < SIZE; z++) {
            for (int x = 0; x < SIZE; x++) image.setPixelRGBA(x, z, pixels[z * SIZE + x]);
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

    /** Плитка из источника: колонка в середине каждой клетки; отменённая — без колонок. */
    private static Built build(Key key, int gen, TerrainSource.Reader reader, BooleanSupplier cancelled) {
        long start = System.nanoTime();
        int step = 1 << key.level, span = key.span(), off = step / 2;
        int x0 = key.tx * span + off, z0 = key.tz * span + off;
        Columns c = new Columns();
        for (int z = 0; z < SIZE; z++) {
            for (int x = 0; x < SIZE; x++) {
                if (cancelled.getAsBoolean()) return Built.failed(key, gen);
                TerrainSource.Column col = reader.column(x0 + x * step, z0 + z * step);
                if (col != null) c.set(z * SIZE + x, col);
            }
        }
        return new Built(key, gen, c, System.nanoTime() - start);
    }

    /**
     * Пиксели плитки (ABGR, как у {@link NativeImage}); без данных — прозрачный. Тень как у ванильной карты
     * ({@code MapItem.update}) — по перепаду с северной соседкой; у первого ряда соседка — последний ряд плитки
     * {@code north} к северу, если она в памяти (придёт позже — эту перерисуют), иначе её высота оценивается по склону
     * к южной (читать соседнюю плитку ради одного ряда стоило бы ещё участка источника на каждую плитку).
     */
    static int[] paint(Columns c, int level, @Nullable Columns north) {
        int step = 1 << level;
        int[] pixels = new int[SIZE * SIZE];
        for (int z = 0; z < SIZE; z++) {
            for (int x = 0; x < SIZE; x++) {
                int i = z * SIZE + x;
                if (!c.has(i)) continue;
                int h = c.height[i], northH;
                if (z > 0) northH = c.has(i - SIZE) ? c.height[i - SIZE] : NO_HEIGHT;
                else if (north != null && north.has(i + (SIZE - 1) * SIZE)) northH = north.height[i + (SIZE - 1) * SIZE];
                else northH = c.has(i + SIZE) ? 2 * h - c.height[i + SIZE] : NO_HEIGHT;
                pixels[i] = MapColor.byId(c.color[i] & 0x3F).calculateRGBColor(shade(h, c.depth[i], northH, step, x + z));
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
