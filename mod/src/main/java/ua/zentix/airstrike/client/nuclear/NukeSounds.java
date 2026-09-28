package ua.zentix.airstrike.client.nuclear;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import ua.zentix.airstrike.AirstrikeConfig;
import ua.zentix.airstrike.client.fx.CameraShake;
import ua.zentix.airstrike.client.sound.ClientSounds;
import ua.zentix.airstrike.net.S2C;
import ua.zentix.airstrike.nuclear.Detonation;
import ua.zentix.airstrike.registry.ModSounds;


/**
 * Звук ядерного удара (DESIGN-nuke §5). Свет приходит сразу, звук — с фронтом ударной волны: каждый тик клиент
 * сравнивает радиус фронта ({@link Detonation#frontRadius}) со своим расстоянием до эпицентра, и когда фронт
 * дошёл — удар, рёв, ветер, звон стёкол, раскаты. Громкость и тембр — от давления у слушателя, а не от
 * ванильного затухания; направление честное (точка в 3 блоках от уха в сторону эпицентра).
 */
public final class NukeSounds {
    /** Вход боеголовки виден последние 3 с — беззвучно: на 7 км/с её звук придёт уже после взрыва. */
    static final int REENTRY_TICKS = 60;

    @Nullable
    private static RainLoop rainLoop;

    private NukeSounds() {}

    /** Пуск МБР: у запустившего — рёв двигателя со стола, у тех, кого касается, — сирена гражданской обороны. */
    static void warning(S2C.NukeWarning w) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        long now = mc.level.getGameTime();
        if (w.mine() && now - w.launchTime() < 40) ClientSounds.at(ModSounds.NUKE_LAUNCH.get(), w.launchPos(), 24, 1);
        if (w.alarm() && now < w.detonateTime()) {
            // две сирены по разные стороны от игрока — как на улицах города
            Vec3 c = mc.player.position();
            ClientSounds.at(ModSounds.NUKE_ALARM.get(), c.add(140, 25, -90), 20, 1.0f);
            ClientSounds.at(ModSounds.NUKE_ALARM.get(), c.add(-120, 25, 130), 20, 0.98f);
        }
    }

    static void tick(ClientLevel level, long now) {
        NukeSky.tick(level);
        if (NukeSky.inBlackRain() && (rainLoop == null || rainLoop.isStopped())) {
            rainLoop = new RainLoop();
            Minecraft.getInstance().getSoundManager().play(rainLoop);
        }
    }

    static void reset() {
        if (rainLoop != null) rainLoop.end();
        rainLoop = null;
        NukeSky.reset();
    }

    /** Звуки одного подрыва у этого слушателя. */
    static final class Schedule {
        private final ClientNuclear.Active a;
        private final Detonation d;
        private final boolean live;
        private final RandomSource random;
        private boolean arrived;
        private long arrivalTick;
        private double psi;
        private final double[] rumbles;
        private int nextRumble;

        Schedule(ClientNuclear.Active a) {
            this.a = a;
            this.d = a.d;
            this.live = a.live;
            this.random = RandomSource.create(d.seed() ^ 0x5EED);
            rumbles = new double[3 + random.nextInt(3)];
            for (int i = 0; i < rumbles.length; i++) rumbles[i] = 5 + random.nextDouble() * 55;
            java.util.Arrays.sort(rumbles);
        }

        void tick() {
            if (!live) return;
            Vec3 ear = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
            long now = (long) a.ticks(0);
            double since = now;
            if (!arrived) {
                double dist = ear.distanceTo(d.burst());
                if (d.frontRadius(since) < dist) return;
                arrived = true;
                arrivalTick = now;
                psi = d.psi(ear);
                arrive(ear);
                return;
            }
            double after = (now - arrivalTick) / 20.0;
            if (now - arrivalTick == 2 && psi >= 0.3) {
                ClientSounds.atEar(ModSounds.NUKE_ROAR.get(), d.burst(), (float) Mth.clamp(psi / 3, 0.25, 1), 1);
            }
            if (nextRumble < rumbles.length && after >= rumbles[nextRumble]) {
                float v = (float) (Mth.clamp(0.3 + psi / 8, 0.2, 0.9) * (1 - 0.15 * nextRumble));
                // отражение от рельефа приходит с разных сторон
                double ang = random.nextDouble() * Math.PI * 2;
                ClientSounds.atEar(ModSounds.NUKE_RUMBLE.get(), ear.add(Math.cos(ang) * 50, 0, Math.sin(ang) * 50), v, 0.9f + random.nextFloat() * 0.2f);
                CameraShake.quake(20);
                nextRumble++;
            }
        }

        /** Фронт у слушателя: удар (N-волна или далёкий гул), тряска, ветер, стёкла, оглушение. */
        private void arrive(Vec3 ear) {
            if (psi >= 1) {
                ClientSounds.atEar(ModSounds.NUKE_CRACK.get(), d.burst(), 1, 1);
                ClientSounds.atEar(ModSounds.NUKE_BOOM_FAR.get(), d.burst(), (float) Mth.clamp(psi / 4, 0.4, 1), 0.8f);
            } else {
                ClientSounds.atEar(ModSounds.NUKE_BOOM_FAR.get(), d.burst(), (float) Mth.clamp(0.25 + psi, 0.25, 1), 1);
            }
            CameraShake.blast((int) Mth.clamp(12 + psi * 5, 12, 60));
            CameraShake.quake((int) Mth.clamp(20 + psi * 8, 20, 120));
            if (psi >= 0.5) {
                ClientSounds.atEar(ModSounds.NUKE_WIND.get(), d.burst(), (float) Mth.clamp(psi / 5, 0.3, 1), 1);
                if (psi < 12) ClientSounds.atEar(ModSounds.NUKE_GLASS.get(), ear.add(0, 2, 0), (float) Mth.clamp(psi / 3, 0.3, 0.9), 1);
            }
            if (psi >= 2 && AirstrikeConfig.CLIENT.nukeTinnitus.get()) {
                Deafness.start((int) (20 * Mth.clamp(8 + psi, 10, 20)), psi);
            }
        }
    }

    /** Шум чёрного дождя: пока игрок под ним, гаснет плавно. */
    private static final class RainLoop extends AbstractTickableSoundInstance {
        RainLoop() {
            super(ModSounds.NUKE_RAIN.get(), SoundSource.WEATHER, SoundInstance.createUnseededRandom());
            looping = true;
            delay = 0;
            volume = 0.01f;
            relative = true;
            attenuation = Attenuation.NONE;
        }

        @Override
        public void tick() {
            volume = Math.max(0, NukeSky.blackRain() * 0.8f);
            if (!NukeSky.inBlackRain() && NukeSky.blackRain() <= 0) stop();
        }

        void end() {
            stop();
        }

        @Override
        public boolean canStartSilent() {
            return true;
        }
    }
}
