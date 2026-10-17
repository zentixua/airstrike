package ua.zentix.incoming.client.nuclear;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.client.far.FarTerrain;
import ua.zentix.incoming.client.fx.BlastEffects;
import ua.zentix.incoming.client.sound.AmbientLoop;
import ua.zentix.incoming.client.sound.ClientSounds;
import ua.zentix.incoming.net.S2C;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.registry.ModSounds;
import ua.zentix.incoming.warhead.GroundMaterial;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Город горит (DESIGN-nuke §5): гул пожаров со всех горящих мест, ветер, который тянет в огонь, треск и поздние
 * обрушения в руинах рядом, вторичные взрывы в горящих кварталах. Всё — от возраста подрыва, а не от разовых событий:
 * подрыв, застанный при входе в мир или после перемотки повтора, звучит в своём возрасте; вторичные взрывы — по
 * расписанию из зерна подрыва ({@link FireField#secondaries}), из прошлого не повторяются.
 * <p>
 * Гул — {@link #QUADRANTS} петли по сторонам света вокруг уха: каждый пожар (кольцо модели {@link FireField} в чанках,
 * которых у клиента нет, или настоящий огонь {@link FireWatch} в своих чанках) даёт мощность s/(4π·d²) своим двум
 * сторонам (cos²/sin²); громкость — как у взрывов, −3 дБ на удвоение расстояния ({@code v ∝ I^¼}), от {@link #V_REF}
 * при {@link #I_REF}, не громче {@link #ROAR_MAX}. Модель вдали — в меру того, что горит в своих чанках против модели
 * там же ({@link #trust}), и без того, что уже горит в своих чанках (счётчик пожаров у подрыва один).
 */
final class NukeAfter {
    static final int QUADRANTS = 4;
    /** Громкость гула при мощности 2000 пожаров в 100 блоках; громче {@link #ROAR_MAX} петля не бывает (огонь со всех сторон). */
    static final double V_REF = 0.4, I_REF = 2000 / (4 * Math.PI * 100 * 100), ROAR_MAX = 0.5;
    /** Ближе — всё равно что в стольких блоках (пожар — не точка). */
    private static final double NEAR = 6;
    /** Ветер в огонь: мощность стока ∑ s / d от пожаров не дальше {@link #WIND_RANGE}; полная — у 5000 пожаров в 100 блоках. */
    private static final double WIND_RANGE = 1500, WIND_REF = 5000 / 100.0, WIND_VOLUME = 0.3;
    /** Точек модели на кольцо — по окружности через столько блоков. */
    private static final double ARC = 64;
    /** Пересчёт мощности — раз в столько тиков (громкость петель между ними плавная). */
    private static final int EVERY = 5;
    /** Обрушения: места руин рядом (не дальше), сколько помнить, частота у начала и спад. */
    private static final double COLLAPSE_RANGE = 128, COLLAPSE_TAU = 2 * 60 * 20;
    private static final int RUINS_KEPT = 512;
    private static final double COLLAPSE_RATE = 1 / 50.0;
    /** Пока своих чанков с моделью мало (ожидается меньше стольких пожаров), модели верим как есть. */
    private static final double TRUST_MIN = 40;

    private final ClientNuclear.Active a;
    private final Detonation d;
    private final RandomSource random;
    @Nullable
    private FireField field;
    private List<FireField.Secondary> secondaries = List.of();
    private int nextSecondary = -1;
    private final AmbientLoop[] roar = new AmbientLoop[QUADRANTS];
    @Nullable
    private AmbientLoop wind;
    private final float[] roarVolume = new float[QUADRANTS];
    private final double[] roarDistance = new double[QUADRANTS];
    private float windVolume;
    private Vec3 windToward = Vec3.ZERO;
    private double windDistance;
    private int retry;
    /** Доверие модели: сколько горит в своих чанках против модели там же (1 — как в модели, 0 — пожаров нет). */
    private double trust = 1;
    private double modelLoaded, seenLoaded, modelRest = 1;
    /** Места руин рядом с подрывом (из обломков, что прислал сервер), выборка не больше {@link #RUINS_KEPT}. */
    private final List<BlockPos> ruins = new ArrayList<>();
    private int ruinsSeen;
    private double firstRuin = Double.NaN;
    private int fired;

    NukeAfter(ClientNuclear.Active a) {
        this.a = a;
        this.d = a.d;
        this.random = RandomSource.create(d.seed() ^ 0xAF7E5L);
    }

    /** Модель огня — при первой нужде (радиусы подрыва считаются по таблицам). */
    private FireField field() {
        if (field == null) {
            field = new FireField(d);
            secondaries = field.secondaries();
        }
        return field;
    }

    /** Горит ли ещё что-то от этого подрыва (тогда {@link FireWatch} смотрит чанки). */
    boolean burning() {
        double t = a.ticks(0);
        return t > 0 && t < field().endTicks() && field().total > 0;
    }

    void tick() {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) return;
        FireField f = field();
        double t = a.ticks(0);
        if (nextSecondary < 0) {
            // подрыв, застанный не с начала: прошлые взрывы не повторяются
            nextSecondary = 0;
            while (nextSecondary < secondaries.size() && secondaries.get(nextSecondary).ticks() <= t) nextSecondary++;
        }
        Vec3 ear = mc.gameRenderer.getMainCamera().getPosition();
        if (f.total <= 0 || t >= f.endTicks()) {
            end();
            return;
        }
        if ((long) t % EVERY == 0) measure(level, f, t, ear);
        sound(ear);
        secondaries(level, t);
        collapses(t, ear);
    }

    /**
     * Мощность пожаров у уха по сторонам, ветер в огонь, доверие модели. Огонь в своих чанках — настоящий; модель вдали —
     * только то, чего там не видно: сервер ставит пожары и туда, где игроки (их чанки — первыми), а счётчик у подрыва
     * один, поэтому модель вдали — без той доли, что горит в своих чанках.
     */
    private void measure(ClientLevel level, FireField f, double t, Vec3 ear) {
        double[] power = new double[QUADRANTS], dist = new double[QUADRANTS];
        double[] wind = new double[4];
        double modelHere = 0;
        Vec3 b = d.burst();
        double reach = d.ruinRadius() + 64;
        double[] seen = {0};
        FireWatch.forEach((x, y, z, count) -> {
            if (Math.hypot(x - b.x, z - b.z) > reach) return;
            seen[0] += count;
            add(power, dist, wind, ear, x, y, z, count);
        });
        double all = f.burning(t);
        double rest = all <= 0 ? 0 : Math.max(0, 1 - seen[0] / all);
        for (int i = 0; i < f.fires.length; i++) {
            double burning = f.burning(i, t);
            if (burning <= 0) continue;
            double r = FireField.radius(i);
            int n = (int) Math.max(8, Math.min(96, Math.ceil(2 * Math.PI * r / ARC)));
            double each = burning / n, offset = i * 0.618;
            for (int j = 0; j < n; j++) {
                double ang = 2 * Math.PI * (j + 0.5) / n + offset;
                double x = b.x + Math.cos(ang) * r, z = b.z + Math.sin(ang) * r;
                // в своих чанках огонь видно на деле: модель там — только для сверки
                if (level.getChunkSource().getChunk((int) Math.floor(x) >> 4, (int) Math.floor(z) >> 4, false) != null) {
                    modelHere += each;
                    continue;
                }
                add(power, dist, wind, ear, x, d.groundY() + 1, z, each * trust * rest);
            }
        }
        modelLoaded = modelHere;
        modelRest = rest;
        seenLoaded = seen[0];
        // сверка: модель против огня в своих чанках, плавно (огонь встаёт с волной и гаснет не сразу)
        if (modelHere >= TRUST_MIN) trust += 0.2 * (Math.min(4, seen[0] / modelHere) - trust);
        for (int q = 0; q < QUADRANTS; q++) {
            roarVolume[q] = power[q] <= 0 ? 0 : (float) Math.min(ROAR_MAX, V_REF * Math.pow(power[q] / I_REF, 0.25));
            if (roarVolume[q] < 0.02f) roarVolume[q] = 0;
            roarDistance[q] = power[q] <= 0 ? 0 : dist[q] / power[q];
        }
        double sink = wind[3];
        windVolume = sink <= 0 ? 0 : (float) (WIND_VOLUME * Math.min(1, Math.sqrt(sink / WIND_REF)));
        if (windVolume < 0.03f) windVolume = 0;
        windToward = sink <= 0 ? Vec3.ZERO : new Vec3(wind[0] / sink, ear.y, wind[1] / sink);
        windDistance = sink <= 0 ? 0 : wind[2] / sink;
    }

    /** Пожар {@code s} в точке: его мощность — двум сторонам света у уха, сток ветра — к нему. */
    private static void add(double[] power, double[] dist, double[] wind, Vec3 ear, double x, double y, double z, double s) {
        if (s <= 0) return;
        double dx = x - ear.x, dy = y - ear.y, dz = z - ear.z;
        double flat = Math.sqrt(dx * dx + dz * dz), d3 = Math.sqrt(flat * flat + dy * dy);
        double p = s / (4 * Math.PI * Math.pow(Math.max(NEAR, d3), 2));
        double az = Math.atan2(dz, dx), sector = Math.PI * 2 / QUADRANTS;
        double u = (az < 0 ? az + 2 * Math.PI : az) / sector;
        int q0 = (int) Math.floor(u) % QUADRANTS, q1 = (q0 + 1) % QUADRANTS;
        double frac = u - Math.floor(u);
        double c = Math.cos(frac * Math.PI / 2), sn = Math.sin(frac * Math.PI / 2);
        power[q0] += p * c * c;
        power[q1] += p * sn * sn;
        dist[q0] += p * c * c * d3;
        dist[q1] += p * sn * sn * d3;
        if (flat < WIND_RANGE) {
            double w = s / Math.max(30, flat);
            wind[0] += w * x;
            wind[1] += w * z;
            wind[2] += w * flat;
            wind[3] += w;
        }
    }

    /** Петли гула и ветра: ведутся каждый тик; не звучит (движок не взял, перезапуск) — заново. */
    private void sound(Vec3 ear) {
        Minecraft mc = Minecraft.getInstance();
        if (retry > 0) retry--;
        for (int q = 0; q < QUADRANTS; q++) {
            double az = 2 * Math.PI * q / QUADRANTS;
            Vec3 toward = ear.add(Math.cos(az) * 10, 0, Math.sin(az) * 10);
            if (roar[q] != null && !roar[q].isStopped()) roar[q].set(ear, toward, roarVolume[q], roarDistance[q]);
            else if (roarVolume[q] > 0 && retry == 0) {
                roar[q] = new AmbientLoop(ModSounds.NUKE_FIRE.get(), SoundSource.AMBIENT, 0.9f + 0.07f * q, d.seed() + q);
                roar[q].set(ear, toward, roarVolume[q], roarDistance[q]);
                mc.getSoundManager().play(roar[q]);
                retry = 20;
            }
        }
        if (wind != null && !wind.isStopped()) wind.set(ear, windToward, windVolume, windDistance);
        else if (windVolume > 0 && retry == 0) {
            wind = new AmbientLoop(ModSounds.NUKE_FIRE_WIND.get(), SoundSource.AMBIENT, 0.75f, d.seed() ^ 0x3D);
            wind.set(ear, windToward, windVolume, windDistance);
            mc.getSoundManager().play(wind);
            retry = 20;
        }
    }

    /** Вторичные взрывы, чей час настал: наш взрыв — картинка и звук с фронтом, без разрушений. */
    private void secondaries(ClientLevel level, double t) {
        while (nextSecondary < secondaries.size() && secondaries.get(nextSecondary).ticks() <= t) {
            FireField.Secondary s = secondaries.get(nextSecondary++);
            // пожары погашены или выключены у сервера — и взрываться нечему
            if (trust < 0.2) continue;
            double y = FarTerrain.of(level).at((int) Math.floor(s.x()), (int) Math.floor(s.z()));
            if (Double.isNaN(y) || y <= level.getMinBuildHeight() + 1) y = d.groundY() + 1;
            BlastEffects.blast(new S2C.Blast(s.big() ? S2C.Blast.MISSILE : S2C.Blast.DRONE, new Vec3(s.x(), y + 0.5, s.z()),
                    GroundMaterial.BRICK.ordinal(), (float) y, s.seed(), Optional.empty()));
            fired++;
        }
    }

    /** Места снесённого рядом (обломки от сервера) — для обрушений. */
    void ruins(List<S2C.NukeDebris.Piece> pieces) {
        if (Double.isNaN(firstRuin)) firstRuin = a.ticks(0);
        for (S2C.NukeDebris.Piece p : pieces) {
            ruinsSeen++;
            if (ruins.size() < RUINS_KEPT) ruins.add(p.pos());
            else {
                int k = random.nextInt(ruinsSeen);
                if (k < RUINS_KEPT) ruins.set(k, p.pos());
            }
        }
    }

    /** Треск и поздние обрушения в руинах рядом, всё реже. */
    private void collapses(double t, Vec3 ear) {
        if (ruins.isEmpty() || Double.isNaN(firstRuin) || t < firstRuin + 40) return;
        double rate = COLLAPSE_RATE * Math.exp(-(t - firstRuin) / COLLAPSE_TAU);
        if (random.nextDouble() >= rate) return;
        BlockPos p = ruins.get(random.nextInt(ruins.size()));
        Vec3 at = Vec3.atCenterOf(p);
        if (at.distanceTo(ear) > COLLAPSE_RANGE) return;
        boolean small = random.nextFloat() < 0.35f;
        ClientSounds.at(small ? ModSounds.DEBRIS_FALL.get() : ModSounds.NUKE_COLLAPSE.get(), at, small ? 3 : 6, 0.8f + random.nextFloat() * 0.3f);
    }

    /** Петли стихают (подрыв убран, огонь кончился). */
    void end() {
        for (AmbientLoop l : roar) if (l != null) l.end();
        if (wind != null) wind.end();
    }

    String describe() {
        FireField f = field();
        double t = a.ticks(0);
        return String.format(Locale.ROOT, "fire model %.0f burning %.0f trust %.2f rest %.2f (model here %.0f seen %.0f) roar %.2f/%.2f/%.2f/%.2f wind %.2f secondary %d/%d ruins %d",
                f.total, f.burning(t), trust, modelRest, modelLoaded, seenLoaded, roarVolume[0], roarVolume[1], roarVolume[2], roarVolume[3], windVolume,
                fired, secondaries.size(), ruins.size());
    }
}
