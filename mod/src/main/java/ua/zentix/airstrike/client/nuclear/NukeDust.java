package ua.zentix.airstrike.client.nuclear;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import ua.zentix.airstrike.nuclear.Detonation;

/**
 * Пыльная стена ударной волны у самого игрока: в миг прихода фронта — бурая пелена на весь экран (гаснет за
 * несколько секунд), а пока стена проходит мимо — клубы пыли и дыма, которые ветер несёт от эпицентра.
 */
public final class NukeDust {
    private static final RandomSource RANDOM = RandomSource.create();
    private static final DustParticleOptions DUST = new DustParticleOptions(new Vector3f(0.47f, 0.39f, 0.3f), 4.0f);
    private static float veil;
    private static float veilDecay = 0.97f;

    private NukeDust() {}

    /** Фронт пришёл: пелена по давлению (1 psi — лёгкая дымка, 5+ psi — почти ничего не видно). */
    static void arrive(double psi) {
        veil = Math.max(veil, (float) Mth.clamp(0.2 + psi / 7, 0.2, 0.85));
        veilDecay = psi >= 3 ? 0.975f : 0.96f;
    }

    /** Один тик прохождения стены: клубы вокруг игрока летят от эпицентра. */
    static void gust(Detonation d, Vec3 ear, double psi, int age) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) return;
        Vec3 away = new Vec3(ear.x - d.burst().x, 0, ear.z - d.burst().z);
        away = away.lengthSqr() < 1e-4 ? new Vec3(1, 0, 0) : away.normalize();
        double speed = Mth.clamp(0.4 + psi * 0.25, 0.4, 2.5) * (1 - age / 80.0);
        int n = (int) (Mth.clamp(psi * 12, 12, 90) * (1 - age / 60.0));
        for (int i = 0; i < n; i++) {
            // появляются с наветренной стороны и по бокам, пролетают мимо
            double along = -14 + RANDOM.nextDouble() * 20, side = (RANDOM.nextDouble() - 0.5) * 30, up = -2 + RANDOM.nextDouble() * 8;
            Vec3 p = ear.add(away.scale(along)).add(-away.z * side, up, away.x * side);
            double v = speed * (0.7 + RANDOM.nextDouble() * 0.6);
            // бурая пыль и серый дым; мелкие белые «пуфы» ванили в стене выглядят снегом — их нет
            if (i % 4 == 0) {
                level.addParticle(ParticleTypes.CAMPFIRE_COSY_SMOKE, p.x, p.y, p.z, away.x * v, 0.01 + RANDOM.nextDouble() * 0.03, away.z * v);
            } else if (i % 4 == 1) {
                level.addParticle(ParticleTypes.LARGE_SMOKE, p.x, p.y, p.z, away.x * v, 0.02, away.z * v);
            } else {
                level.addParticle(DUST, p.x, p.y, p.z, away.x * v, 0.02, away.z * v);
            }
        }
    }

    static void tick() {
        veil *= veilDecay;
        if (veil < 0.01f) veil = 0;
    }

    static void reset() {
        veil = 0;
    }

    /** Бурая пелена поверх мира (под вспышкой). */
    static void render(GuiGraphics g) {
        if (veil <= 0) return;
        int a = (int) (veil * 255);
        g.fill(0, 0, g.guiWidth(), g.guiHeight(), (a << 24) | 0x6E5A44);
    }
}
