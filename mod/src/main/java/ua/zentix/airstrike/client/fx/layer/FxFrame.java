package ua.zentix.airstrike.client.fx.layer;

import com.mojang.blaze3d.shaders.FogShape;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.FrustumIntersection;
import ua.zentix.airstrike.client.far.FarView;
import ua.zentix.airstrike.client.far.Sight;

/**
 * Что нужно частицам на кадр: камера, пирамида видимости (координаты — от камеры), дымка воздуха, край прорисовки и туман
 * Minecraft.
 */
public final class FxFrame {
    public final Vec3 cam;
    public final FrustumIntersection frustum;
    private final FarView view;
    private final double range;
    private final float fogStart, fogEnd;
    private final boolean cylinder;

    FxFrame(FarView view, FrustumIntersection frustum, Fog fog) {
        this.cam = view.camera();
        this.frustum = frustum;
        this.view = view;
        this.range = view.range();
        this.fogStart = fog.start;
        this.fogEnd = fog.end;
        this.cylinder = fog.shape == FogShape.CYLINDER;
    }

    /** Сколько света частицы доходит через воздух ({@link Sight#transmittance}): та же дымка, что у дальней картинки. */
    public float haze(double d) {
        return (float) Sight.transmittance(d, range);
    }

    /**
     * Доля частицы на расстоянии d: дальше 0,8 прорисовки её сменяет дальняя картинка ({@link FarView#farShare}), к краю
     * частицы нет. Не туманом Minecraft: с Distant Horizons его нет, и частицы взрыва в 400 блоках рисовались вместе
     * с дальней картинкой того же взрыва — дым частиц закрывал её шар.
     */
    public float near(double d) {
        return (float) (1 - view.farShare(d));
    }

    /** Туман Minecraft в точке (от камеры), 0..1 — как у ванильных шейдеров (вода, лава, Незер, слепота, пепел ядерки). */
    public float fog(double x, double y, double z) {
        if (fogEnd <= fogStart) return 0;
        double d = cylinder ? Math.max(Math.sqrt(x * x + z * z), Math.abs(y)) : Math.sqrt(x * x + y * y + z * z);
        if (d <= fogStart) return 0;
        if (d >= fogEnd) return 1;
        float t = (float) ((d - fogStart) / (fogEnd - fogStart));
        return Mth.clamp(t * t * (3 - 2 * t), 0, 1);
    }

    /** Туман мира, снятый до того, как Minecraft сбросил его к концу кадра. */
    static final class Fog {
        float start, end;
        FogShape shape = FogShape.SPHERE;
        final float[] color = new float[4];
    }
}
