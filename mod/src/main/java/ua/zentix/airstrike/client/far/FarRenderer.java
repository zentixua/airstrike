package ua.zentix.airstrike.client.far;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.Mth;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import ua.zentix.airstrike.client.render.FarDraw;

/**
 * Снаряды и взрывы вдали — после мира ({@code AFTER_LEVEL}, как ядерный удар): дальше прорисовки частиц и сущностей
 * нет, а под шейдерами Iris виден только этот этап. Кадр: что видно из {@link FarBlasts} и {@link FarFlightView}
 * собирается в {@link FarSprites} и рисуется четырьмя вызовами.
 */
public final class FarRenderer {
    private static final FarSprites SPRITES = new FarSprites();

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

    /** Выход из мира или смена измерения. */
    public static void reset() {
        FarBlasts.reset();
        FarFlightView.reset();
    }
}
