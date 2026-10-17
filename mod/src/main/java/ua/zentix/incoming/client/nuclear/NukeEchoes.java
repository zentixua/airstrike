package ua.zentix.incoming.client.nuclear;

import it.unimi.dsi.fastutil.floats.FloatArrayList;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.JOrbisAudioStream;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.client.far.FarTerrain;
import ua.zentix.incoming.client.far.Sightline;
import ua.zentix.incoming.client.sound.EchoMix;
import ua.zentix.incoming.client.sound.OwnSources;
import ua.zentix.incoming.client.sound.TerrainEcho;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.nuclear.model.BlastModel;
import ua.zentix.incoming.registry.ModSounds;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/**
 * Эхо подрыва от рельефа у этого слушателя (DESIGN-nuke §5): пока фронт идёт, лучи из уха по карте высот
 * ({@link TerrainEcho}, по {@link #RAYS_PER_TICK} за тик) находят склоны, которые вернут удар; запись прямого удара
 * читается в фоне. Когда фронт дошёл и лучи готовы — в фоне же сводятся дорожки по сторонам ({@link EchoMix}), и они
 * встают в очередь своих источников OpenAL ({@link OwnSources}) от настоящего начала прямого удара. Только вживую.
 */
final class NukeEchoes {
    /** Лучей за тик, пока фронт идёт (все {@link TerrainEcho#RAYS} — за 6 тиков). */
    static final int RAYS_PER_TICK = 12;
    /** Слушатель ушёл дальше этого от места, откуда пущены лучи, — лучи заново. */
    private static final double MOVED = 64;
    /** Зерно — столько секунд начала записи прямого удара. */
    private static final double GRAIN_SECONDS = 2.8;
    /** Посыл в хвост реверберации. */
    private static final float SEND = 0.25f;

    private final ClientNuclear.Active a;
    private final Detonation d;
    /** Запись прямого удара: её вариант выбирает зерно подрыва, как у {@link NukeSounds.Schedule#arrive}. */
    final long seed;
    private final CompletableFuture<float[]> grain;
    private final List<TerrainEcho.Reflection> found = new ArrayList<>();
    @Nullable
    private Vec3 from;
    private int nextRay;
    @Nullable
    private CompletableFuture<EchoMix.Mix> mix;
    @Nullable
    private EchoMix.Mix done;
    @Nullable
    private OwnSources.Anchor anchor;
    private float volume;
    private int queued;

    NukeEchoes(ClientNuclear.Active a) {
        this.a = a;
        this.d = a.d;
        this.seed = d.seed() ^ 0xEC40L;
        this.grain = CompletableFuture.supplyAsync(() -> read(seed), Util.backgroundExecutor())
                .exceptionally(e -> {
                    Incoming.LOG.warn("Terrain echo: can't read the blast sound: {}", e.toString());
                    return new float[0];
                });
    }

    /** Фронт ещё идёт: часть лучей. */
    void approach(Vec3 ear) {
        trace(ear, RAYS_PER_TICK);
    }

    /**
     * Фронт у слушателя: недосчитанные лучи — сразу, дальше сведение в фоне.
     *
     * @param tick   тик подрыва, когда зазвучал прямой удар
     * @param direct прямой удар, отданный движку (эхо — от его настоящего начала)
     * @param volume громкость прямого удара (эхо — доля её)
     * @param pitch  высота тона прямого удара (эхо — того же звука)
     */
    void arrive(Vec3 ear, long tick, @Nullable SoundInstance direct, float volume, float pitch) {
        trace(ear, TerrainEcho.RAYS);
        this.anchor = OwnSources.anchor(this, direct, a::ticks, tick);
        this.volume = volume;
        List<TerrainEcho.Reflection> list = List.copyOf(found);
        mix = grain.thenApplyAsync(g -> EchoMix.render(EchoMix.pitch(g, pitch), list), Util.backgroundExecutor());
    }

    /** Каждый тик после удара: готовые дорожки — в очередь своих источников. */
    void tick() {
        if (mix == null || anchor == null || done != null || !mix.isDone()) return;
        done = mix.getNow(EchoMix.Mix.NONE);
        if (done.tracks().isEmpty()) return;
        OwnSources.reverb(done.t60(), SEND);
        for (EchoMix.Track t : done.tracks()) {
            if (OwnSources.play(this, anchor, t.start(), t.azimuth(), Math.min(16, volume * t.gain()), t.pcm(), EchoMix.RATE)) queued++;
        }
    }

    /** Подрыв убран или стал прошлым. */
    void stop() {
        OwnSources.stop(this);
    }

    private void trace(Vec3 ear, int rays) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null || level.dimensionType().hasCeiling()) return;
        if (from == null || from.distanceTo(ear) > MOVED) {
            from = ear;
            nextRay = 0;
            found.clear();
        }
        if (nextRay >= TerrainEcho.RAYS) return;
        Sightline.Heights heights = ground(level);
        Vec3 b = d.burst();
        TerrainEcho.Blast blast = blast(d);
        for (int i = 0; i < rays && nextRay < TerrainEcho.RAYS; i++, nextRay++) {
            TerrainEcho.ray(heights, nextRay, TerrainEcho.RAYS, TerrainEcho.RANGE, from.x, from.y, from.z, b.x, b.y, b.z, blast, found);
        }
    }

    /** Карта высот без пустоты под миром (у неё верх — дно мира). */
    private static Sightline.Heights ground(ClientLevel level) {
        Sightline.Heights h = FarTerrain.of(level);
        int floor = level.getMinBuildHeight() + 1;
        return (x, z) -> {
            double v = h.at(x, z);
            return v <= floor ? Double.NaN : v;
        };
    }

    /** Фронт подрыва для эха: приход — как у фронта игры, давление — модель Кинни — Грэма. */
    static TerrainEcho.Blast blast(Detonation d) {
        return new TerrainEcho.Blast() {
            @Override
            public double arrivalTicks(double blocks) {
                return d.arrivalTicks(blocks);
            }

            @Override
            public double pressure(double blocks) {
                return BlastModel.overpressureKpa(Math.max(1, d.metres(blocks)), d.yieldKt());
            }
        };
    }

    /**
     * Начало записи далёкого удара (тот вариант, что выберет зерно) — в зерно {@link EchoMix#RATE}. В фоновом потоке:
     * ресурсы читаются так же, как их читает загрузка звуков.
     */
    private static float[] read(long seed) {
        Minecraft mc = Minecraft.getInstance();
        WeighedSoundEvents event = mc.getSoundManager().getSoundEvent(ModSounds.NUKE_BOOM_FAR.get().getLocation());
        if (event == null) return new float[0];
        Sound sound = event.getSound(RandomSource.create(seed));
        try (InputStream in = mc.getResourceManager().open(sound.getPath()); JOrbisAudioStream ogg = new JOrbisAudioStream(in)) {
            int rate = (int) ogg.getFormat().getSampleRate(), channels = ogg.getFormat().getChannels();
            int limit = (int) (GRAIN_SECONDS * rate * channels) + 8192;
            FloatArrayList samples = new FloatArrayList(limit);
            while (samples.size() < limit && ogg.readChunk(samples::add)) {
                // читаем до нужной длины
            }
            return EchoMix.grain(samples.toFloatArray(), channels, rate, GRAIN_SECONDS);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Откуда слышен раскат через {@code seconds} после удара: сильнейшее отражение вблизи этого времени (±3 с); NaN —
     * эха нет (сторону выберет случай).
     */
    double rumbleAzimuth(double seconds) {
        EchoMix.Mix m = done;
        if (m == null || m.tracks().isEmpty()) return Double.NaN;
        TerrainEcho.Reflection best = null;
        for (TerrainEcho.Reflection r : found) {
            if (Math.abs(r.delay() / 20 - seconds) > 3) continue;
            if (best == null || r.energy() > best.energy()) best = r;
        }
        return best == null ? Double.NaN : best.azimuth();
    }

    /** Строка {@code SCENARIO}: лучи, отражения и сильнейшие из них (задержка, сторона, уровень). */
    String describe() {
        StringBuilder sb = new StringBuilder(String.format(Locale.ROOT, "echo rays %d/%d taps %d", nextRay, TerrainEcho.RAYS, found.size()));
        EchoMix.Mix m = done;
        if (m != null) {
            Vec3 e = from, bu = d.burst();
            if (e != null) sb.append(String.format(Locale.ROOT, " ear %.0f,%.0f,%.0f burst %.0f,%.0f,%.0f", e.x, e.y, e.z, bu.x, bu.y, bu.z));
            double sum = found.stream().mapToDouble(TerrainEcho.Reflection::energy).sum();
            sb.append(String.format(Locale.ROOT, " sum %.1fdB tracks %d queued %d t60 %.1f vol %.2f start +%.2fs", 10 * Math.log10(Math.max(1e-9, sum)),
                    m.tracks().size(), queued, m.t60(), volume, anchor == null ? Double.NaN : anchor.lateSeconds()));
            for (EchoMix.Track t : m.tracks()) {
                sb.append(String.format(Locale.ROOT, " [az %.0f start %.2fs len %.1fs x%.2f]", Math.toDegrees(t.azimuth()), t.start() / 20, t.seconds(), t.gain()));
            }
            found.stream().sorted(Comparator.comparingDouble(TerrainEcho.Reflection::energy).reversed()).limit(6).forEach(r ->
                    sb.append(String.format(Locale.ROOT, " {d %.2fs az %.0f %.1fdB r %.0f at %.0f,%.0f,%.0f}", r.delay() / 20, Math.toDegrees(r.azimuth()),
                            10 * Math.log10(r.energy()), r.distance(), r.x(), r.y(), r.z())));
        }
        return sb.toString();
    }
}
