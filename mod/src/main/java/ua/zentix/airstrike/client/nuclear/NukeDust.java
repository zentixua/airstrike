package ua.zentix.airstrike.client.nuclear;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.block.Blocks;
import ua.zentix.airstrike.client.fx.particle.Fx;
import ua.zentix.airstrike.client.fx.particle.FxBudget;
import ua.zentix.airstrike.nuclear.Detonation;

/**
 * Пыльная стена ударной волны у самого игрока: в миг прихода фронта — бурая пелена на весь экран (гаснет за
 * несколько секунд), а пока стена проходит мимо — клубы пыли и дыма, которые ветер несёт от эпицентра.
 */
public final class NukeDust {
    private static final RandomSource RANDOM = RandomSource.create();
    private static final BlockParticleOption DIRT = new BlockParticleOption(ParticleTypes.BLOCK, Blocks.DIRT.defaultBlockState());
    private static final BlockParticleOption LEAVES = new BlockParticleOption(ParticleTypes.BLOCK, Blocks.OAK_LEAVES.defaultBlockState());
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
            if (i % 5 == 0) {
                // сорванные листья, земля, щепа — летят и кувыркаются
                level.addParticle(i % 10 == 0 ? LEAVES : DIRT, p.x, p.y, p.z, away.x * v * 1.3, 0.1 + RANDOM.nextDouble() * 0.2, away.z * v * 1.3);
            } else {
                // бурая пыль и серый дым клубами — стена, которая проходит сквозь игрока
                boolean smoke = i % 5 == 1;
                Fx.smoke().vel(away.x * v, 0.01 + RANDOM.nextDouble() * 0.04, away.z * v).size(1.2f, 5 + RANDOM.nextFloat() * 3).growFast()
                        .life(70 + RANDOM.nextInt(90)).color(smoke ? 0x3E3A36 : 0x7A6852, smoke ? 0x6E6A66 : 0xA08E78).alpha(0.7f).drag(0.97f)
                        .collide().wind(0).fadeIn(2).fadeFrom(0.35f).budget(FxBudget.GROUND).spawn(level, p);
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
