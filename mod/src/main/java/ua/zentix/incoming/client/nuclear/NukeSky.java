package ua.zentix.incoming.client.nuclear;

import com.mojang.blaze3d.shaders.FogShape;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ViewportEvent;
import ua.zentix.incoming.client.far.FarView;
import ua.zentix.incoming.client.far.Sight;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.nuclear.model.MushroomModel;

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

    /** Середина шапки подрыва в мире ({@link MushroomField#centre}). */
    private static final double[] CENTRE = new double[3];

    /**
     * Янтарное небо от подрыва у камеры 0..1 — свет шара и шапки у глаза ({@link MushroomModel#light}: чёрное тело их
     * видимой температуры, как (R/d)²) против света неба, к которому привык глаз ({@link Sight#adapted}), сквозь дымку:
     * днём — секунды, пока шар ярче неба, ночью — дольше, пока светится шапка; засвеченное небо видно, как и вспышку,
     * за десятки километров.
     */
    static float amber(ClientNuclear.Active a, Vec3 cam, float partial) {
        MushroomModel.Shape s = MushroomField.shape(a, partial);
        if (s == null) return 0;
        Detonation d = a.d;
        MushroomField.centre(a, s, CENTRE);
        double dist = d.metres(Math.sqrt(Mth.square(CENTRE[0] - cam.x) + Mth.square(CENTRE[1] - cam.y) + Mth.square(CENTRE[2] - cam.z)));
        ClientLevel level = Minecraft.getInstance().level;
        float ambient = level == null ? 1 : FarView.ambient(level, partial);
        // свет шара у глаза против света неба, к которому привык глаз, сквозь дымку: наравне с небом — небо наполовину янтарное
        double lit = Sight.adapted(MushroomModel.light(s, d.yieldKt(), d.surface(), dist), ambient) * Math.exp(-dist / d.visibility());
        return (float) (lit / (1 + lit));
    }

    public static void fogColor(ViewportEvent.ComputeFogColor e) {
        if (ClientNuclear.isEmpty() && rain <= 0) return;
        float partial = (float) e.getPartialTick();
        float r = e.getRed(), g = e.getGreen(), b = e.getBlue();
        // бурая мгла у эпицентра: минута после прихода стены пыли, тем гуще, чем ближе
        Vec3 cam = e.getCamera().getPosition();
        float dust = 0, amber = 0;
        for (ClientNuclear.Active a : ClientNuclear.detonations()) {
            Detonation d = a.d;
            amber = Math.max(amber, amber(a, cam, partial));
            double dist = cam.distanceTo(d.burst());
            double near = 1 - dist / (d.radiusMax() * 2);
            if (near <= 0) continue;
            // мгла — пыль, которую принесла стена: от её прихода к камере
            double t = (a.ticks(partial) - d.arrivalTicks(dist)) / (20.0 * d.scale());
            if (t < 0.5) continue;
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
