package ua.zentix.airstrike.client.far;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.Mth;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import ua.zentix.airstrike.client.render.FarDraw;

import java.util.Arrays;

/**
 * Снаряды и взрывы вдали — после мира ({@code AFTER_LEVEL}, как ядерный удар): дальше прорисовки частиц и сущностей
 * нет, а под шейдерами Iris виден только этот этап. Кадр: что видно из {@link FarBlasts} и {@link FarFlightView}
 * собирается в {@link FarSprites} и рисуется четырьмя вызовами.
 */
public final class FarRenderer {
    private static final FarSprites SPRITES = new FarSprites();
    /** Сколько клубов, точек, света и лент было в последнем кадре (для строки сценария). */
    private static final int[] LAST_FRAME = new int[4];

    private FarRenderer() {}

    /** Раз в тик клиента (не на паузе), после {@code FlightTracks.tick}. */
    public static void tick() {
        FarBlasts.tick();
        FarFlightView.tick();
    }

    public static void render(RenderLevelStageEvent e) {
        if (e.getStage() != RenderLevelStageEvent.Stage.AFTER_LEVEL || FarBlasts.isEmpty() && FarFlightView.isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) return;
        FarView view = view(e, mc, level);
        SPRITES.begin(view);
        FarBlasts.collect(view, SPRITES);
        FarFlightView.collect(view, SPRITES);
        SPRITES.counts(LAST_FRAME);
        if (SPRITES.isEmpty()) return;
        FarDraw.begin(e);
        try {
            SPRITES.draw(view);
        } finally {
            FarDraw.end();
        }
    }

    private static FarView view(RenderLevelStageEvent e, Minecraft mc, ClientLevel level) {
        Camera camera = e.getCamera();
        float partial = e.getPartialTick().getGameTimeDeltaPartialTick(false);
        // проекция: m11 = 1 / tan(fovY / 2) — угол пикселя по вертикали
        double pixel = 2 / (e.getProjectionMatrix().m11() * Math.max(1, mc.getWindow().getHeight()));
        float[] fog = RenderSystem.getShaderFogColor();
        float ambient = Mth.clamp(level.getSkyDarken(partial) * 1.1f - 0.05f, 0.12f, 1f);
        return new FarView(camera.getPosition(), camera.getLeftVector(), camera.getUpVector(), partial, mc.gameRenderer.getDepthFar() * 0.97,
                pixel, new float[]{fog[0], fog[1], fog[2]}, ambient, Sight.range(level.getRainLevel(partial), level.getThunderLevel(partial)),
                mc.options.getEffectiveRenderDistance() * 16.0);
    }

    /**
     * Что сейчас вдали — одной строкой (сценарии клиента пишут её в лог): взрывы и их звук, снаряды, спрайты последнего
     * нарисованного кадра. Пусто — вдали ничего нет.
     */
    public static String describe() {
        if (FarBlasts.isEmpty() && FarFlightView.isEmpty()) return "";
        return "взрывы: " + FarBlasts.describe() + "; снаряды: " + FarFlightView.describe() + "; кадр: клубов " + LAST_FRAME[0] + ", точек "
                + LAST_FRAME[1] + ", света " + LAST_FRAME[2] + ", лент " + LAST_FRAME[3];
    }

    /** Выход из мира или смена измерения. */
    public static void reset() {
        FarBlasts.reset();
        FarFlightView.reset();
        Arrays.fill(LAST_FRAME, 0);
    }
}
