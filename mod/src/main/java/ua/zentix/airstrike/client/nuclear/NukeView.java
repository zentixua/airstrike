package ua.zentix.airstrike.client.nuclear;

import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Vector4f;

/** Матрицы кадра (снимаются после неба): проекция точки мира на экран — для послеобраза вспышки. */
public final class NukeView {
    private static final Matrix4f VIEW_PROJ = new Matrix4f();
    private static Vec3 camera = Vec3.ZERO;
    private static float projScale = 1;
    private static boolean valid;

    private NukeView() {}

    static void capture(RenderLevelStageEvent e) {
        VIEW_PROJ.set(e.getProjectionMatrix()).mul(e.getModelViewMatrix());
        projScale = e.getProjectionMatrix().m11();
        camera = e.getCamera().getPosition();
        valid = true;
    }

    /**
     * Точка на экране в долях (0..1 слева направо и сверху вниз) и угловой масштаб, или null, если точка за спиной.
     *
     * @return {x, y, w}: w — «глубина» для пересчёта размера (размер на экране ≈ радиус / w)
     */
    @Nullable
    public static float[] project(Vec3 world) {
        if (!valid) return null;
        Vector4f v = new Vector4f((float) (world.x - camera.x), (float) (world.y - camera.y), (float) (world.z - camera.z), 1);
        VIEW_PROJ.transform(v);
        if (v.w <= 0.05f) return null;
        return new float[]{(v.x / v.w + 1) * 0.5f, (1 - v.y / v.w) * 0.5f, v.w};
    }

    /** Вертикальный масштаб проекции: доля высоты экрана на единицу (радиус / глубина). */
    public static float verticalScale() {
        return projScale * 0.5f;
    }
}
