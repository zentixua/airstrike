package ua.zentix.airstrike.client.fx;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import ua.zentix.airstrike.client.fx.particle.Fx;
import ua.zentix.airstrike.client.sound.ClientSounds;
import ua.zentix.airstrike.net.S2C;
import ua.zentix.airstrike.registry.ModSounds;
import ua.zentix.airstrike.warhead.Warheads;

/**
 * Подстанция выходит из строя: несколько голубых вспышек дуги подряд (ночью их видно над городом издалека),
 * фонтан искр, потом густой дым горящего масла трансформатора. Звук пробоя приходит со скоростью звука.
 */
public final class GridEffects {
    /** Дальше вспышка дуги не освещает экран. */
    private static final double FLASH_RANGE = 240;
    /** Голубовато-белый свет дуги и его синий край. */
    private static final int ARC = 0xDCE8FF, ARC_EDGE = 0x5A7CFF;

    private static final RandomSource RANDOM = RandomSource.create();

    private GridEffects() {}

    public static void failure(S2C.GridFailure p) {
        Vec3 pos = p.pos();
        RandomSource rnd = RandomSource.create(p.seed());
        // вспышки дуги: первая сразу, ещё две-три — вразнобой в первые полторы секунды (дуга перебрасывается)
        int[] arcs = new int[3 + rnd.nextInt(2)];
        for (int i = 1; i < arcs.length; i++) arcs[i] = arcs[i - 1] + 3 + rnd.nextInt(10);
        Effects.add(new Effects.Effect() {
            private boolean heard;

            @Override
            public boolean tick(ClientLevel level, int t) {
                Vec3 ear = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
                double d = ear.distanceTo(pos);
                if (!heard && d <= Warheads.FRONT_SPEED * t) {
                    heard = true;
                    ClientSounds.atEar(ModSounds.GRID_FAIL.get(), pos, (float) Math.max(0.15, 1.6 * (1 - d / 320)), 0.95f + rnd.nextFloat() * 0.1f);
                }
                for (int a : arcs) {
                    if (a == t) arc(level, pos, rnd, d, a == 0 ? 1 : 0.6f);
                }
                if (t < 40 && t % 2 == 0) sparks(level, pos, rnd, t < 10 ? 6 : 2);
                if (t >= 4 && t < 200) {
                    float f = t / 200f;
                    Fx.smoke().vel(rnd.nextGaussian() * 0.03, 0.25 + 0.2 * rnd.nextDouble(), rnd.nextGaussian() * 0.03).size(0.5f, 3.5f)
                            .life(160 + rnd.nextInt(80)).color(0x1C1A1A, 0x55504C).alpha(0.8f * (1 - 0.5f * f)).glow(t < 20 ? 0.6f : 0, 6)
                            .drag(0.95f).rise(0.004f).fadeIn(3).fadeFrom(0.5f).spawn(level, pos.add(rnd.nextGaussian() * 0.3, 0.6, rnd.nextGaussian() * 0.3));
                }
                return t < 200 || !heard && t < 400;
            }
        });
    }

    private static void arc(ClientLevel level, Vec3 pos, RandomSource rnd, double d, float strength) {
        Vec3 at = pos.add(rnd.nextGaussian() * 0.4, 1 + rnd.nextDouble(), rnd.nextGaussian() * 0.4);
        Fx.flash().color(ARC, ARC_EDGE).size(10 * strength, 16 * strength).life(3).spawn(level, at);
        Fx.flash().color(0xFFFFFF, ARC).size(3, 5).life(2).spawn(level, at);
        Player player = Minecraft.getInstance().player;
        if (player == null || d > FLASH_RANGE) return;
        Vec3 eye = player.getEyePosition();
        boolean visible = level.clip(new ClipContext(eye, at, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, player)).getType() == HitResult.Type.MISS;
        // вспышка освещает небо и стены вокруг — её видно и из-за дома, только слабее
        Flash.trigger((float) (1 - d / FLASH_RANGE) * (visible ? 0.45f : 0.15f) * strength, 0.45f, ARC);
    }

    private static void sparks(ClientLevel level, Vec3 pos, RandomSource rnd, int n) {
        for (int i = 0; i < n; i++) {
            Fx.spark().color(0xFFFFFF, 0x80A0FF).vel(rnd.nextGaussian() * 0.35, 0.3 + rnd.nextDouble() * 0.6, rnd.nextGaussian() * 0.35)
                    .life(12 + rnd.nextInt(16)).gravity(0.05f).spawn(level, pos.add(0, 1, 0));
        }
    }

    /** Квартал погас или загорелся: щелчок и гул там, где его слышно. */
    public static void district(S2C.GridDistrict p) {
        ClientSounds.at(p.on() ? ModSounds.GRID_POWER_UP.get() : ModSounds.GRID_POWER_DOWN.get(), p.pos(), 3f, 0.9f + RANDOM.nextFloat() * 0.2f);
    }
}
