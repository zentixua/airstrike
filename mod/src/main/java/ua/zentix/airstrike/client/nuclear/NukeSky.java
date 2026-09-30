package ua.zentix.airstrike.client.nuclear;

import com.mojang.blaze3d.shaders.FogShape;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ViewportEvent;
import ua.zentix.airstrike.nuclear.Detonation;

/**
 * Небо и туман: в миг вспышки — белые, потом янтарные, пока светятся шар и гриб (десятки секунд; небо красит
 * {@link NukeRenderer}), у эпицентра оранжево-бурые от пыли и дыма, в чёрном дожде — серо-бурые и близкие.
 * Ванильная погода не трогается: чёрный дождь местный.
 */
public final class NukeSky {
    /** Сила чёрного дождя у камеры 0..1 (плавно нарастает и спадает). */
    private static float rain;
    private static boolean rainTarget;

    private NukeSky() {}

    public static float blackRain() {
        return rain;
    }

    public static boolean inBlackRain() {
        return rainTarget;
    }

    static void tick(ClientLevel level) {
        Vec3 cam = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        boolean in = false;
        if (Detonation.underOpenSky(level, cam)) {
            for (ClientNuclear.Active a : ClientNuclear.detonations()) {
                if (a.d.blackRain(cam.x, cam.z, level.getGameTime() - a.d.gameTime())) {
                    in = true;
                    break;
                }
            }
        }
        rainTarget = in;
        rain = Mth.clamp(rain + (in ? 0.02f : -0.03f), 0, 1);
    }

    static void reset() {
        rain = 0;
        rainTarget = false;
    }

    /**
     * Янтарное небо от подрыва у камеры 0..1: проступает к концу светового импульса, держится, пока светятся шар
     * и гриб, гаснет на 8–60 с у 15 кт (у 1 кт — вдвое быстрее, {@link CloudPuffs#glowScale}); засвеченное небо видно, как и вспышку, за десятки километров.
     */
    static float amber(ClientNuclear.Active a, Vec3 cam, float partial) {
        Detonation d = a.d;
        double t = a.seconds(partial);
        double pulse = NukeFlash.pulseSeconds(d.yieldKt()), k = CloudPuffs.glowScale(d.yieldKt());
        double env = CloudPuffs.smooth(0.5 * pulse, 2 * pulse, t) * (1 - CloudPuffs.smooth(8 * k, 60 * k, t));
        if (env <= 0) return 0;
        return (float) (env * Math.exp(-d.metres(cam.distanceTo(d.burst())) / d.visibility()));
    }

    public static void fogColor(ViewportEvent.ComputeFogColor e) {
        if (ClientNuclear.isEmpty() && rain <= 0) return;
        float partial = (float) e.getPartialTick();
        float r = e.getRed(), g = e.getGreen(), b = e.getBlue();
        // бурая мгла у эпицентра: минута после подрыва, тем гуще, чем ближе
        Vec3 cam = e.getCamera().getPosition();
        float dust = 0, amber = 0;
        for (ClientNuclear.Active a : ClientNuclear.detonations()) {
            Detonation d = a.d;
            double t = a.seconds(partial);
            amber = Math.max(amber, amber(a, cam, partial));
            if (t < 0.5) continue;
            double near = 1 - cam.distanceTo(d.burst()) / (d.radiusMax() * 2);
            if (near <= 0) continue;
            dust = Math.max(dust, (float) (0.45 * near * Math.exp(-t / 45) * CloudPuffs.smooth(0.5, 4, t)));
        }
        // янтарная засветка, пока светятся шар и гриб
        r = Mth.lerp(amber * 0.5f, r, 1f);
        g = Mth.lerp(amber * 0.5f, g, 0.6f);
        b = Mth.lerp(amber * 0.5f, b, 0.3f);
        r = Mth.lerp(dust, r, 0.62f);
        g = Mth.lerp(dust, g, 0.38f);
        b = Mth.lerp(dust, b, 0.2f);
        r = Mth.lerp(rain * 0.75f, r, 0.2f);
        g = Mth.lerp(rain * 0.75f, g, 0.19f);
        b = Mth.lerp(rain * 0.75f, b, 0.17f);
        float white = NukeFlash.whiteness(partial);
        r = Mth.lerp(white, r, 1f);
        g = Mth.lerp(white, g, 0.97f);
        b = Mth.lerp(white, b, 0.9f);
        e.setRed(r);
        e.setGreen(g);
        e.setBlue(b);
    }

    /** В чёрном дожде видно на 40–60 блоков. */
    public static void fog(ViewportEvent.RenderFog e) {
        if (rain <= 0) return;
        float far = Mth.lerp(rain, e.getFarPlaneDistance(), Math.min(e.getFarPlaneDistance(), 48));
        e.setFarPlaneDistance(far);
        e.setNearPlaneDistance(Math.min(e.getNearPlaneDistance(), far * 0.1f));
        e.setFogShape(FogShape.SPHERE);
        e.setCanceled(true);
    }
}
