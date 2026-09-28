package ua.zentix.airstrike.client.nuclear;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import ua.zentix.airstrike.Airstrike;
import ua.zentix.airstrike.AirstrikeConfig;
import ua.zentix.airstrike.nuclear.Detonation;
import ua.zentix.airstrike.nuclear.NuclearWarhead;
import ua.zentix.airstrike.nuclear.model.FireballModel;
import ua.zentix.airstrike.nuclear.model.ThermalModel;

/**
 * Вспышка ядерного взрыва на экране (DESIGN-nuke §6): белый экран по двойному импульсу яркости шара, медленно
 * отпускающий глаз, затем — тёмно-оранжевое «пятно» послеобраза там, где был шар, если игрок на него смотрел.
 * Сила — по световому импульсу у игрока: прямая видимость и шар в поле зрения — полная, иначе треть (засветка неба).
 */
public final class NukeFlash {
    private static final ResourceLocation FLARE = Airstrike.id("textures/nuke/flare.png");

    @Nullable
    private static ClientNuclear.Active current;
    /** Сила вспышки 0..1 для этого игрока. */
    private static float strength;
    /** Послеобраз: сколько тиков ещё и сколько было всего. */
    private static int afterLeft, afterTotal;
    private static long ticks, startTick;

    private NukeFlash() {}

    static void detonation(ClientNuclear.Active a) {
        Detonation d = a.d;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null) return;
        Vec3 eye = p.getEyePosition();
        boolean sees = NuclearWarhead.sees(mc.level, d, p);
        double q = d.fluence(eye);
        double distM = d.metres(eye.distanceTo(d.burst()));
        // кроме настоящего светового импульса — ослеплённое небо: у 15 кт видно за сотни километров
        double s = Math.min(1, q / 2 + 0.6 * Math.exp(-distM / d.visibility()));
        Vec3 look = p.getViewVector(1);
        double angle = Math.toDegrees(Math.acos(Mth.clamp(look.dot(d.burst().subtract(eye).normalize()), -1, 1)));
        boolean inView = sees && angle < 60;
        s *= inView ? 1 : sees ? 0.45 : 0.3;
        s *= AirstrikeConfig.CLIENT.flash.get();
        if (s <= strength && current != null) return;
        current = a;
        strength = (float) s;
        startTick = ticks;
        if (inView && q >= 0.05) {
            // 5–30 с по световому импульсу (кал/см²)
            afterTotal = afterLeft = (int) (20 * Mth.clamp(5 + q * 2.5, 5, 30));
        }
        if (sees && q >= ThermalModel.BURN_1) ua.zentix.airstrike.client.fx.CameraShake.blast(6);
    }

    static void tick() {
        ticks++;
        if (afterLeft > 0) afterLeft--;
        if (current != null && ticks - startTick > 200 && afterLeft <= 0) {
            current = null;
            strength = 0;
        }
    }

    static void reset() {
        current = null;
        strength = 0;
        afterLeft = 0;
    }

    /** Насколько экран сейчас белый (0..1): для неба и тумана тоже. */
    public static float whiteness(float partialTick) {
        ClientNuclear.Active a = current;
        if (a == null || strength <= 0) return 0;
        double t = Math.max(a.seconds(partialTick), 1e-4);
        // яркость шара (двойной импульс) и «ослеплённый» глаз, который отпускает за ~1.5 с
        double eye = Math.exp(-(ticks - startTick + partialTick) / 18.0);
        return (float) (strength * Math.min(1, Math.max(FireballModel.brightness(t, a.d.yieldKt()) * 1.5, eye)));
    }

    public static void render(GuiGraphics g, DeltaTracker delta) {
        float partial = delta.getGameTimeDeltaPartialTick(false);
        int w = g.guiWidth(), h = g.guiHeight();
        NukeDust.render(g);
        if (afterLeft > 0 && current != null) afterimage(g, current.d, partial, w, h);
        float white = whiteness(partial);
        if (white > 0.004f) {
            int a = Math.min(255, (int) (white * 255));
            // сначала бело-голубой, потом тёплый
            double t = current.seconds(partial);
            int rgb = t < FireballModel.firstMinimumSeconds(current.d.yieldKt()) * 4 ? 0xEEF4FF : 0xFFF8E8;
            g.fill(0, 0, w, h, (a << 24) | rgb);
        }
    }

    /** Тёмно-оранжевое пятно на месте шара, медленно гаснет; двигается вместе со взглядом, как настоящий послеобраз. */
    private static void afterimage(GuiGraphics g, Detonation d, float partial, int w, int h) {
        float[] s = NukeView.project(d.burst());
        if (s == null) return;
        double r = Math.max(d.fireballRadius(), 30);
        // пятно больше видимого шара: глаз «размазывает» засветку
        float px = Mth.clamp((float) (r * NukeView.verticalScale() / s[2] * h * 2.2), 14, 400);
        float life = (afterLeft - partial) / afterTotal;
        float a = Mth.clamp(life * 1.3f, 0, 0.85f) * Math.min(1, strength * 1.5f);
        if (a <= 0.01f) return;
        int x = (int) (s[0] * w), y = (int) (s[1] * h), size = (int) px;
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        g.setColor(0.55f, 0.22f, 0.05f, a);
        g.blit(FLARE, x - size, y - size, 2 * size, 2 * size, 0, 0, 64, 64, 64, 64);
        g.setColor(0.15f, 0.05f, 0.02f, a * 0.7f);
        g.blit(FLARE, x - size / 3, y - size / 3, 2 * (size / 3), 2 * (size / 3), 0, 0, 64, 64, 64, 64);
        g.setColor(1, 1, 1, 1);
        RenderSystem.disableBlend();
    }
}
