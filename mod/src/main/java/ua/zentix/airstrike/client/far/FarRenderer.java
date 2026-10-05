package ua.zentix.airstrike.client.far;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import ua.zentix.airstrike.client.fx.layer.FxLayer;
import ua.zentix.airstrike.client.fx.layer.FxLights;
import ua.zentix.airstrike.client.fx.layer.FxQuads;
import ua.zentix.airstrike.client.fx.layer.SourceProbes;
import ua.zentix.airstrike.client.render.FarDraw;

import java.util.Arrays;

/**
 * Снаряды и взрывы вдали: дальше прорисовки частиц и сущностей нет, а под шейдерами Iris виден только этап после мира.
 * Кадр: что видно из {@link FarBlasts} и {@link FarFlightView} собирается в {@link FarSprites} — квадратами в общий
 * проход слоя эффектов ({@link FxLayer}), вместе с ближними частицами; модели снарядов рисуются в свой атлас
 * ({@code FarModels}), их плитки — квадратами того же прохода.
 */
public final class FarRenderer {
    private static final FarSprites SPRITES = new FarSprites();
    /** Сколько клубов, точек, света, лент и моделей было в последнем кадре (для строки сценария). */
    private static final int[] LAST_FRAME = new int[5];

    private FarRenderer() {}

    /** Тик клиента (не на паузе). */
    public static void tick() {
        FarBlasts.tick();
        FarFlightView.tick();
    }

    /**
     * Дальнее в кадр слоя эффектов; огненные шары — ещё и светом частиц кадра ({@code lights}), яркие источники —
     * пробами видимости для своего блика и вуали ({@code probes}).
     */
    public static void collect(FarView view, FxQuads out, FxLights lights, SourceProbes probes) {
        SPRITES.begin(view, out, probes);
        if (!FarBlasts.isEmpty() || !FarFlightView.isEmpty()) {
            FarBlasts.collect(view, SPRITES, lights);
            FarFlightView.collect(view, SPRITES);
        }
        SPRITES.counts(LAST_FRAME);
    }

    /**
     * Модели кадра — в атлас; после {@link #collect}, до отрисовки квадратов. Текстура атласа (0 — атласа ещё нет).
     */
    public static int renderModels() {
        SPRITES.models.render();
        return SPRITES.models.texture();
    }

    /**
     * Первый кадр в мире (и после перезагрузки ресурсов): атлас моделей, их сетки в видеопамяти и по одной отрисовке
     * каждой — до первого снаряда вдали ({@code FarModels#warmup}).
     */
    public static void warmup() {
        SPRITES.models.warmup();
    }

    /** Что нужно на кадр всем дальним картинкам и слою эффектов. */
    public static FarView view(RenderLevelStageEvent e, Minecraft mc, ClientLevel level) {
        Camera camera = e.getCamera();
        float partial = e.getPartialTick().getGameTimeDeltaPartialTick(false);
        // проекция: m11 = 1 / tan(fovY / 2) — угол пикселя по вертикали
        double pixel = 2 / (e.getProjectionMatrix().m11() * Math.max(1, mc.getWindow().getHeight()));
        double edge = Math.atan(0.5 * pixel * Math.hypot(mc.getWindow().getWidth(), mc.getWindow().getHeight()));
        float ambient = FarView.ambient(level, partial);
        return new FarView(FarDraw.eye(e), camera.getLookVector(), camera.getLeftVector(), camera.getUpVector(), partial,
                mc.gameRenderer.getDepthFar() * 0.97, pixel, edge, ambient, Sight.range(level.getRainLevel(partial), level.getThunderLevel(partial)),
                mc.options.getEffectiveRenderDistance() * 16.0, level.effects().getCloudHeight(), level.getRainLevel(partial));
    }

    /**
     * Что сейчас вдали — одной строкой (сценарии клиента пишут её в лог): взрывы и их звук, снаряды, спрайты последнего
     * нарисованного кадра и время кадра всего слоя эффектов. Пусто — вдали ничего нет.
     */
    public static String describe() {
        if (FarBlasts.isEmpty() && FarFlightView.isEmpty()) return "";
        return "взрывы: " + FarBlasts.describe() + "; снаряды: " + FarFlightView.describe() + "; кадр: клубов " + LAST_FRAME[0] + ", точек "
                + LAST_FRAME[1] + ", света " + LAST_FRAME[2] + ", лент " + LAST_FRAME[3] + ", моделей " + LAST_FRAME[4] + "; слой: "
                + FxLayer.describeTiming();
    }

    /** Выход из мира или смена измерения. */
    public static void reset() {
        FarBlasts.reset();
        FarFlightView.reset();
        Arrays.fill(LAST_FRAME, 0);
    }
}
