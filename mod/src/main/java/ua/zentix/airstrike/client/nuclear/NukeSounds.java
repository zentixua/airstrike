package ua.zentix.airstrike.client.nuclear;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import ua.zentix.airstrike.AirstrikeConfig;
import ua.zentix.airstrike.client.fx.CameraShake;
import ua.zentix.airstrike.client.sound.ClientSounds;
import ua.zentix.airstrike.client.sound.SirenSound;
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
    /** Сирены по разные стороны от места, где слушателя застала тревога, — как на улицах города, и их тон на полной скорости. */
    private static final Vec3[] SIREN_AT = {new Vec3(140, 25, -90), new Vec3(-120, 25, 130)};
    private static final float[] SIREN_TONE = {1.0f, 0.98f};
    /** Движок не дал сирене канал (звука нет, каналы заняты): через столько тиков попробовать снова. */
    private static final int SIREN_RETRY = 20;

    @Nullable
    private static RainLoop rainLoop;
    /** Сирены, пока у слушателя тревога; {@code null} — эта не звучит (выключенные сбегают сами). */
    private static final SirenSound[] SIRENS = new SirenSound[SIREN_AT.length];
    /** Где слушателя застала тревога, в каком мире; {@code null} — тревоги нет. */
    @Nullable
    private static Vec3 sirenOrigin;
    @Nullable
    private static ClientLevel sirenLevel;
    private static int sirenWait;

    private NukeSounds() {}

    /** Пуск МБР: у запустившего — рёв двигателя со стола. Сирена гражданской обороны — {@link #sirens}. */
    static void warning(S2C.NukeWarning w) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        long now = mc.level.getGameTime();
        if (w.mine() && now - w.launchTime() < 40) ClientSounds.at(ModSounds.NUKE_LAUNCH.get(), w.launchPos(), 24, 1);
    }

    /** Петля чёрного дождя — после {@link NukeSky#tick}, который решает, идёт ли он у камеры; сирены. */
    static void tick() {
        if (NukeSky.inBlackRain() && (rainLoop == null || rainLoop.isStopped())) {
            rainLoop = new RainLoop();
            Minecraft.getInstance().getSoundManager().play(rainLoop);
        }
        sirens();
    }

    /**
     * Сирены гражданской обороны воют, пока летит хоть одна МБР, о которой слушателю объявлена тревога: до подрыва
     * (пакет подрыва снимает предупреждение), отбоя (сервер присылает предупреждения заново) или конца ожидания места
     * удара; потом сбегают. Сирену, которую движок не держит (перезапуск звука, смена измерения, ползунок «Окружение»
     * был в нуле), — запустить снова на её месте; пока ползунок в нуле — не запускать: снятый им звук движок не убирает.
     */
    private static void sirens() {
        Minecraft mc = Minecraft.getInstance();
        if (sirenWait > 0) sirenWait--;
        boolean alarm = false;
        for (S2C.NukeWarning w : ClientNuclear.warnings()) alarm |= w.alarm();
        if (!alarm || mc.level != sirenLevel) {
            for (int i = 0; i < SIRENS.length; i++) {
                if (SIRENS[i] != null) SIRENS[i].off();
                SIRENS[i] = null;
            }
            sirenOrigin = null;
            sirenLevel = null;
            if (!alarm) return;
        }
        if (sirenWait > 0 || mc.player == null || mc.options.getSoundSourceVolume(SoundSource.AMBIENT) <= 0) return;
        if (sirenOrigin == null) {
            sirenOrigin = mc.player.position();
            sirenLevel = mc.level;
        }
        for (int i = 0; i < SIRENS.length; i++) {
            if (SIRENS[i] != null && SIRENS[i].playing()) continue;
            if (SIRENS[i] != null) SIRENS[i].kill();
            SIRENS[i] = new SirenSound(ModSounds.NUKE_ALARM.get(), sirenOrigin.add(SIREN_AT[i]), SIREN_TONE[i]);
            mc.getSoundManager().play(SIRENS[i]);
            sirenWait = SIREN_RETRY;
        }
    }

    static void reset() {
        if (rainLoop != null) rainLoop.end();
        rainLoop = null;
        for (int i = 0; i < SIRENS.length; i++) {
            if (SIRENS[i] != null) SIRENS[i].kill();
            SIRENS[i] = null;
        }
        sirenOrigin = null;
        sirenLevel = null;
        sirenWait = 0;
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
                ClientSounds.atEarLoud(ModSounds.NUKE_ROAR.get(), d.burst(), (float) Mth.clamp(psi / 3, 0.35, 1), 1);
                ClientSounds.atEarLoud(ModSounds.NUKE_ROAR.get(), d.burst().add(0, -200, 0), (float) Mth.clamp(psi / 4, 0.3, 1), 0.8f);
            }
            // стена пыли проходит мимо игрока секунды три
            if (now - arrivalTick < 60 && psi >= 0.5) NukeDust.gust(d, ear, psi, (int) (now - arrivalTick));
            if (nextRumble < rumbles.length && after >= rumbles[nextRumble]) {
                float v = (float) (Mth.clamp(0.3 + psi / 8, 0.2, 0.9) * (1 - 0.15 * nextRumble));
                // отражение от рельефа приходит с разных сторон
                double ang = random.nextDouble() * Math.PI * 2;
                ClientSounds.atEar(ModSounds.NUKE_RUMBLE.get(), ear.add(Math.cos(ang) * 50, 0, Math.sin(ang) * 50), v, 0.9f + random.nextFloat() * 0.2f);
                CameraShake.quake(20);
                nextRumble++;
            }
        }

        /**
         * Фронт у слушателя: оглушающий удар (N-волна; несколько источников вместе — одному OpenAL не даёт громче
         * единицы), тряска, пыльная стена, ветер, стёкла, оглушение. Всё по «Общей громкости».
         */
        private void arrive(Vec3 ear) {
            Vec3 b = d.burst();
            Vec3 side = new Vec3(b.z - ear.z, 0, ear.x - b.x).normalize().scale(40);
            if (psi >= 1) {
                ClientSounds.atEarLoud(ModSounds.NUKE_CRACK.get(), b, 1, 1);
                ClientSounds.atEarLoud(ModSounds.NUKE_CRACK.get(), b.add(side), 1, 0.92f);
                ClientSounds.atEarLoud(ModSounds.NUKE_CRACK.get(), b.subtract(side), 1, 0.85f);
                ClientSounds.atEarLoud(ModSounds.NUKE_BOOM_FAR.get(), b, 1, 0.75f);
                ClientSounds.atEarLoud(ModSounds.BLAST_NEAR.get(), b, 1, 0.55f);
            } else {
                float v = (float) Mth.clamp(0.35 + psi, 0.35, 1);
                ClientSounds.atEarLoud(ModSounds.NUKE_BOOM_FAR.get(), b, v, 1);
                ClientSounds.atEarLoud(ModSounds.NUKE_BOOM_FAR.get(), b.add(side), v * 0.8f, 0.85f);
                ClientSounds.atEarLoud(ModSounds.BLAST_FAR.get(), b, v, 0.6f);
            }
            CameraShake.nuke(psi);
            CameraShake.quake((int) Mth.clamp(20 + psi * 8, 20, 120));
            NukeDust.arrive(psi);
            if (psi >= 0.5) {
                ClientSounds.atEarLoud(ModSounds.NUKE_WIND.get(), d.burst(), (float) Mth.clamp(psi / 4, 0.4, 1), 1);
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
