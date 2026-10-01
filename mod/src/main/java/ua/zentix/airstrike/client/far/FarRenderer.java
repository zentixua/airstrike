package ua.zentix.airstrike.client.far;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.Mth;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import ua.zentix.airstrike.Airstrike;
import ua.zentix.airstrike.client.render.FarDraw;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.Arrays;
import java.util.Locale;

/**
 * Снаряды и взрывы вдали — после мира ({@code AFTER_LEVEL}, как ядерный удар): дальше прорисовки частиц и сущностей
 * нет, а под шейдерами Iris виден только этот этап. Кадр: что видно из {@link FarBlasts} и {@link FarFlightView}
 * собирается в {@link FarSprites} и рисуется четырьмя вызовами.
 */
public final class FarRenderer {
    private static final FarSprites SPRITES = new FarSprites();
    /** Сколько клубов, точек, света и лент было в последнем кадре (для строки сценария). */
    private static final int[] LAST_FRAME = new int[4];
    /**
     * Время кадра в потоке отрисовки (сбор и отправка вершин) за секунду: сумма, самый долгий; самый долгий из кадров,
     * в которые сборка мусора не останавливала JVM, и время процессора потока в нём; кадров со сборкой; прошлая секунда, мс.
     * Сборка мусора останавливает все потоки, и кадр, попавший на неё, длинен не своей работой (облако и ноутбук:
     * кадр вдали ничего не выделяет, а паузы G1 — 10–35 мс), поэтому она — отдельно.
     */
    private static long frameNanos, frameMax, cleanMax, cleanCpu;
    private static int frames, ticks, collected;
    private static double lastMean, lastMax, lastClean, lastCleanCpu;
    private static int lastCollected;
    private static final ThreadMXBean THREADS = ManagementFactory.getThreadMXBean();
    /** Сборщики, которые останавливают JVM (у ZGC и Shenandoah — «… Pauses»; «… Cycles» идут вместе с программой). */
    private static final GarbageCollectorMXBean[] PAUSES = ManagementFactory.getGarbageCollectorMXBeans().stream()
            .filter(b -> !b.getName().contains("Cycles")).toArray(GarbageCollectorMXBean[]::new);

    private FarRenderer() {}

    /** Раз в тик клиента (не на паузе), после {@code FlightTracks.tick}. */
    public static void tick() {
        FarBlasts.tick();
        FarFlightView.tick();
        if (++ticks >= 20) {
            lastMean = frames > 0 ? frameNanos / 1e6 / frames : 0;
            lastMax = frameMax / 1e6;
            lastClean = cleanMax / 1e6;
            lastCleanCpu = cleanCpu / 1e6;
            lastCollected = collected;
            frameNanos = frameMax = cleanMax = cleanCpu = 0;
            frames = ticks = collected = 0;
        }
    }

    public static void render(RenderLevelStageEvent e) {
        if (e.getStage() != RenderLevelStageEvent.Stage.AFTER_LEVEL) return;
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) return;
        if (FarSprites.cold()) warmup(e, view(e, mc, level));
        if (FarBlasts.isEmpty() && FarFlightView.isEmpty()) return;
        long start = System.nanoTime(), cpu = cpuNanos(), pauses = pauses();
        FarView view = view(e, mc, level);
        SPRITES.begin(view);
        FarBlasts.collect(view, SPRITES);
        FarFlightView.collect(view, SPRITES);
        SPRITES.counts(LAST_FRAME);
        if (!SPRITES.isEmpty()) {
            FarDraw.begin(e);
            try {
                SPRITES.draw(view);
            } finally {
                FarDraw.end();
            }
        }
        long spent = System.nanoTime() - start;
        frameNanos += spent;
        frameMax = Math.max(frameMax, spent);
        frames++;
        if (pauses() != pauses) {
            collected++;
        } else if (spent > cleanMax) {
            cleanMax = spent;
            cleanCpu = cpuNanos() - cpu;
        }
    }

    /**
     * Первый кадр в мире (и после перезагрузки ресурсов): невидимый кадр всех четырёх видов, чтобы текстуры, шейдер
     * и буферы были готовы до первого снаряда и взрыва вдали ({@link FarSprites#warmup}).
     */
    private static void warmup(RenderLevelStageEvent e, FarView view) {
        long start = System.nanoTime();
        SPRITES.begin(view);
        SPRITES.warmup();
        FarDraw.begin(e);
        try {
            SPRITES.draw(view);
        } finally {
            FarDraw.end();
        }
        Airstrike.LOG.info("Дальняя картинка: прогрев {} мс", String.format(Locale.ROOT, "%.1f", (System.nanoTime() - start) / 1e6));
    }

    /** Время процессора потока отрисовки, нс (−1 — JVM не умеет: тогда в строке 0). */
    private static long cpuNanos() {
        return THREADS.isCurrentThreadCpuTimeSupported() ? THREADS.getCurrentThreadCpuTime() : -1;
    }

    /** Сколько раз сборка мусора останавливала JVM с запуска. */
    private static long pauses() {
        long n = 0;
        for (GarbageCollectorMXBean b : PAUSES) n += Math.max(0, b.getCollectionCount());
        return n;
    }

    private static FarView view(RenderLevelStageEvent e, Minecraft mc, ClientLevel level) {
        Camera camera = e.getCamera();
        float partial = e.getPartialTick().getGameTimeDeltaPartialTick(false);
        // проекция: m11 = 1 / tan(fovY / 2) — угол пикселя по вертикали
        double pixel = 2 / (e.getProjectionMatrix().m11() * Math.max(1, mc.getWindow().getHeight()));
        float ambient = Mth.clamp(level.getSkyDarken(partial) * 1.1f - 0.05f, 0.12f, 1f);
        return new FarView(camera.getPosition(), camera.getLeftVector(), camera.getUpVector(), partial, mc.gameRenderer.getDepthFar() * 0.97,
                pixel, ambient, Sight.range(level.getRainLevel(partial), level.getThunderLevel(partial)),
                mc.options.getEffectiveRenderDistance() * 16.0, level.effects().getCloudHeight(), level.getRainLevel(partial));
    }

    /**
     * Что сейчас вдали — одной строкой (сценарии клиента пишут её в лог): взрывы и их звук, снаряды, спрайты последнего
     * нарисованного кадра. Пусто — вдали ничего нет.
     */
    public static String describe() {
        if (FarBlasts.isEmpty() && FarFlightView.isEmpty()) return "";
        return "взрывы: " + FarBlasts.describe() + "; снаряды: " + FarFlightView.describe() + "; кадр: клубов " + LAST_FRAME[0] + ", точек "
                + LAST_FRAME[1] + ", света " + LAST_FRAME[2] + ", лент " + LAST_FRAME[3]
                + String.format(Locale.ROOT, ", %.3f мс (самый долгий за секунду %.3f; без сборки мусора %.3f, процессор %.3f; кадров со сборкой %d)",
                lastMean, lastMax, lastClean, Math.max(0, lastCleanCpu), lastCollected);
    }

    /** Выход из мира или смена измерения. */
    public static void reset() {
        FarBlasts.reset();
        FarFlightView.reset();
        Arrays.fill(LAST_FRAME, 0);
        frameNanos = frameMax = cleanMax = cleanCpu = 0;
        frames = ticks = collected = lastCollected = 0;
        lastMean = lastMax = lastClean = lastCleanCpu = 0;
    }
}
