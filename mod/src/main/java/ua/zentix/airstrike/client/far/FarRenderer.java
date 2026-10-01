package ua.zentix.airstrike.client.far;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.Mth;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import ua.zentix.airstrike.client.render.FarDraw;

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
    /** Время кадра в потоке отрисовки (сбор и отправка вершин) за секунду: сумма, самый долгий, кадров; прошлая секунда, мс. */
    private static long frameNanos, frameMax;
    private static int frames, ticks;
    private static double lastMean, lastMax;

    private FarRenderer() {}

    /** Раз в тик клиента (не на паузе), после {@code FlightTracks.tick}. */
    public static void tick() {
        FarBlasts.tick();
        FarFlightView.tick();
        if (++ticks >= 20) {
            lastMean = frames > 0 ? frameNanos / 1e6 / frames : 0;
            lastMax = frameMax / 1e6;
            frameNanos = frameMax = 0;
            frames = ticks = 0;
        }
    }

    public static void render(RenderLevelStageEvent e) {
        if (e.getStage() != RenderLevelStageEvent.Stage.AFTER_LEVEL || FarBlasts.isEmpty() && FarFlightView.isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) return;
        long start = System.nanoTime();
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
                + String.format(Locale.ROOT, ", %.3f мс (самый долгий за секунду %.3f)", lastMean, lastMax);
    }

    /** Выход из мира или смена измерения. */
    public static void reset() {
        FarBlasts.reset();
        FarFlightView.reset();
        Arrays.fill(LAST_FRAME, 0);
        frameNanos = frameMax = 0;
        frames = ticks = 0;
        lastMean = lastMax = 0;
    }
}
