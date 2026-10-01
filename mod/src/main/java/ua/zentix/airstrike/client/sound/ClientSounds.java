package ua.zentix.airstrike.client.sound;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import ua.zentix.airstrike.client.ClientWeaponSpec;
import ua.zentix.airstrike.client.flight.FlightTrack;
import ua.zentix.airstrike.client.flight.FlightTracks;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Звук снарядов на клиенте. У каждого снаряда свой независимый набор зацикленных звуков (никаких «4 ячеек»
 * и перебивающих друг друга свистов, как в датапаке); задержку и Доплер считает {@link EngineSound}.
 * Путь снаряда — {@link FlightTracks}: по сущности, а пока её у клиента нет (далеко или летит вне мира) — по пакетам
 * сервера; один и тот же звук по UUID переходит с одного на другое. Дальше среза слышимости ({@code Hearing})
 * громкость слоёв — ноль: путь, который сервер шлёт только ради картинки вдали, канала не занимает.
 * <p>
 * Каждый тик здесь считается, что слышно от каждого снаряда ({@link Emission}) и как громко каждый его слой;
 * звучат (держат канал OpenAL) только слышимые слои и не больше {@link VoiceBudget#CAP} сразу.
 */
public final class ClientSounds {
    private static final Map<UUID, Tracked> TRACKS = new HashMap<>();
    private static final RandomSource RANDOM = RandomSource.create();
    /** Моторы уходят вниз под громкий взрыв. */
    private static final Ducking DUCK = new Ducking();
    /** Движок не дал слою канал (все заняты): через столько тиков попробовать снова. */
    private static final int RETRY = 10;
    /**
     * Мотор снаряда стихает столько тиков после того, как до уха дошёл конец полёта (слой без данных — вдвое тише
     * за тик), и только потом глушится: оборванный на полной громкости, он щёлкал бы, а оборванный на тик раньше
     * фронта взрыва — оставлял бы перед ним паузу тишины.
     */
    private static final int FADE = 4;

    private ClientSounds() {}

    /** Звук одного снаряда: путь, слои и фаза полёта, которую слушатель уже «услышал» (−1 — ещё никакую). */
    private static final class Tracked {
        final FlightTrack track;
        final Occlusion occlusion = new Occlusion();
        final List<Voice> voices;
        /** Есть слой ускорителя: смену фазы отмечают разовые звуки старта ({@link EngineSound#launchEvents}). */
        final boolean launchEvents;
        int heardPhase = -1;

        Tracked(FlightTrack track, List<EngineSound.Layer> layers) {
            this.track = track;
            this.voices = layers.stream().map(l -> new Voice(track, occlusion, l)).toList();
            this.launchEvents = layers.contains(EngineSound.Layer.BOOSTER);
        }

        void kill() {
            voices.forEach(Voice::kill);
        }
    }

    /** Слой звука снаряда: что с ним в этот тик и звук, пока слой звучит. */
    private static final class Voice {
        final FlightTrack track;
        final Occlusion occlusion;
        final EngineSound.Layer layer;
        /** Что слышно от снаряда в этот тик ({@code null} — данных нет) и громкость с тоном слоя. */
        Emission heard;
        EngineSound.Tone tone;
        /** Сколько тиков подряд слой не слышно. */
        int quiet;
        /** Раньше этого тика не запускать: движок не дал канал. */
        long retryAt;
        /** Звучит сейчас; {@code null} — канала не держит. */
        EngineSound sound;

        Voice(FlightTrack track, Occlusion occlusion, EngineSound.Layer layer) {
            this.track = track;
            this.occlusion = occlusion;
            this.layer = layer;
        }

        void hear(Emission e) {
            heard = e;
            tone = e == null ? null : layer.tone(track, e);
            quiet = gain() >= VoiceBudget.AUDIBLE ? 0 : quiet + 1;
            if (sound != null) sound.aim(heard, tone);
        }

        float gain() {
            return tone == null ? 0 : (float) tone.gain();
        }

        /** Слой звучит: звук не смолк сам и его ещё ведёт движок (перезапускаясь, движок бросает все звуки). */
        boolean live() {
            if (sound != null && (sound.isStopped() || !Minecraft.getInstance().getSoundManager().isActive(sound))) kill();
            return sound != null;
        }

        /** Запустить слой; движок не дал канал — попробовать позже. */
        void start() {
            SoundManager manager = Minecraft.getInstance().getSoundManager();
            EngineSound s = new EngineSound(track, occlusion, layer);
            s.aim(heard, tone);
            // громкость, тон, место и фильтр — до запуска: так ваниль тикает отложенный звук перед play
            s.tick();
            manager.play(s);
            if (manager.isActive(s)) sound = s;
            else retryAt = FlightTracks.now() + RETRY;
        }

        void kill() {
            if (sound != null) sound.kill();
            sound = null;
        }
    }

    /** Текущее время звука, тики клиента. */
    static double now() {
        return FlightTracks.now();
    }

    /** Раз в тик после {@link FlightTracks#tick}: слои звука у каждого пути, громкости, каналы. */
    public static void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            reset();
            return;
        }
        // путь забыт или по тому же UUID начался новый полёт — прежний звук глушится
        Iterator<Tracked> it = TRACKS.values().iterator();
        while (it.hasNext()) {
            Tracked t = it.next();
            if (FlightTracks.get(t.track.id) != t.track) {
                t.kill();
                it.remove();
            }
        }
        for (FlightTrack track : FlightTracks.all()) {
            if (!TRACKS.containsKey(track.id)) TRACKS.put(track.id, new Tracked(track, layers(track)));
        }
        DUCK.tick();
        budget(hear(mc));
    }

    /** Громкость моторов под взрывами ({@link Ducking}). */
    static float duck() {
        return DUCK.factor();
    }

    /** До уха дошёл взрыв с громкостью прямого звука lufs: моторы уходят вниз ({@link Ducking}). */
    static void ducked(double lufs) {
        DUCK.blast(lufs);
    }

    /** Что слышно от каждого снаряда в этот тик: громкость каждого слоя и разовые звуки старта. */
    private static List<Voice> hear(Minecraft mc) {
        Vec3 ear = mc.gameRenderer.getMainCamera().getPosition();
        Entity camera = mc.getCameraEntity();
        List<Voice> voices = new ArrayList<>();
        double tick = now();
        for (Tracked t : TRACKS.values()) {
            FlightTrack track = t.track;
            double te = Acoustics.emissionTime(track, tick, ear.x, ear.y, ear.z);
            if (track.isDead() && te >= track.deathTick() + FADE) {
                // фронт взрыва дошёл и мотор стих: дальше слушатель слышит уже сам взрыв
                t.kill();
                continue;
            }
            // данных о снаряде на этот момент нет (взорвался или ушёл из слуха) — слои стихают, не дёргая источник
            Emission e = track.covers(te) ? Emission.at(track, te, ear, camera != null && camera.getUUID().equals(track.id)) : null;
            if (e != null && t.launchEvents && e.phase() != t.heardPhase) {
                EngineSound.launchEvents(track, e, t.heardPhase);
                t.heardPhase = e.phase();
            }
            for (Voice v : t.voices) {
                v.hear(e);
                voices.add(v);
            }
        }
        return voices;
    }

    /** Звучат слои, которые выбрал {@link VoiceBudget}: лишние отпускают канал, недостающие запускаются. */
    private static void budget(List<Voice> voices) {
        int n = voices.size();
        float[] gain = new float[n];
        boolean[] live = new boolean[n];
        int[] quiet = new int[n];
        for (int i = 0; i < n; i++) {
            Voice v = voices.get(i);
            gain[i] = v.gain();
            live[i] = v.live();
            quiet[i] = v.quiet;
        }
        boolean[] keep = VoiceBudget.select(gain, live, quiet, VoiceBudget.CAP);
        for (int i = 0; i < n; i++) {
            Voice v = voices.get(i);
            if (live[i]) {
                if (keep[i]) v.sound.keep();
                else v.sound.release();
            } else if (keep[i] && now() >= v.retryAt) {
                v.start();
            }
        }
    }

    /** Слои звука снаряда — из клиентского паспорта его сущности (у B-2 свои, у его бомбы свои). */
    private static List<EngineSound.Layer> layers(FlightTrack track) {
        return ClientWeaponSpec.of(track.weapon).airframe(!track.bomber).layers();
    }

    /**
     * Слышимые сейчас слои: «оружие (откуда путь: e — сущность, s — пакеты сервера; до уха, блоков) слой громкость×тон»,
     * в конце — сколько слоёв держат канал; для отладки звука по логу.
     */
    public static String describe() {
        StringBuilder sb = new StringBuilder();
        Vec3 ear = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        double[] p = new double[3];
        int live = 0;
        for (Tracked t : TRACKS.values()) {
            StringBuilder layers = new StringBuilder();
            for (Voice v : t.voices) {
                EngineSound s = v.sound;
                if (s == null) continue;
                live++;
                // getVolume читает звук, который движок подставляет при запуске: без звука (нет устройства) его нет
                if (s.getSound() != null && s.getVolume() > 0.01f) layers.append(String.format(Locale.ROOT, " %s %.2f×%.2f", s.describe(), s.getVolume(), s.getPitch()));
            }
            if (layers.isEmpty()) continue;
            t.track.at(t.track.lastTick(), p);
            sb.append(String.format(Locale.ROOT, " %s(%s %.0f)", t.track.weapon.getSerializedName(), t.track.fromServer() ? "s" : "e",
                    Math.sqrt((p[0] - ear.x) * (p[0] - ear.x) + (p[1] - ear.y) * (p[1] - ear.y) + (p[2] - ear.z) * (p[2] - ear.z)))).append(layers);
        }
        return sb.append(" live=").append(live).toString();
    }

    /** Отбой или выход из мира: заглушить всё сразу. */
    public static void reset() {
        TRACKS.values().forEach(Tracked::kill);
        TRACKS.clear();
        DUCK.reset();
    }

    /** Обычный отбой: заглушить отменённые снаряды; оставшиеся (ядерные) звучат дальше. */
    public static void cancelled(Collection<UUID> projectiles) {
        for (UUID id : projectiles) {
            Tracked t = TRACKS.remove(id);
            if (t != null) t.kill();
        }
    }

    // ---------------------------------------------------------------- разовые звуки

    /**
     * Звук, который пришёл к уху «отовсюду сразу» — удар взрывной волны: громкость не зависит от расстояния
     * (её задаёт вызывающий), но направление честное — точка в 3 блоках от уха в сторону источника.
     * По дороге воздух съедает верха, холм или дом между — глушит ({@link SoundFilters}).
     */
    public static void atEar(SoundEvent event, Vec3 source, float volume, float pitch) {
        atEar(event, source, volume, pitch, SoundSource.AMBIENT);
    }

    /**
     * Ядерный удар по ушам: как {@link #atEar}, но по ползунку «Общая громкость» — его не должно быть тише
     * от того, что «Окружение» у игрока убавлено.
     */
    public static void atEarLoud(SoundEvent event, Vec3 source, float volume, float pitch) {
        atEar(event, source, volume, pitch, SoundSource.MASTER);
    }

    private static void atEar(SoundEvent event, Vec3 source, float volume, float pitch, SoundSource category) {
        atEar(event, source, volume, pitch, category, RANDOM);
    }

    /**
     * Как {@link #atEar(SoundEvent, Vec3, float, float)}, но запись из вариантов события выбирает зерно: ракурсы одного
     * взрыва с одним зерном — одна и та же запись (у событий ракурсов поровну вариантов).
     */
    public static void atEar(SoundEvent event, Vec3 source, float volume, float pitch, long seed) {
        atEar(event, source, volume, pitch, SoundSource.AMBIENT, RandomSource.create(seed));
    }

    private static void atEar(SoundEvent event, Vec3 source, float volume, float pitch, SoundSource category, RandomSource random) {
        Vec3 ear = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        float open = SoundFilters.open(ear, source.add(0, 1.5, 0));
        float highs = farAir(ear.distanceTo(source)) * SoundFilters.blockedHighs(open);
        play(new Shot(event.getLocation(), category, volume, pitch, random, SoundInstance.Attenuation.NONE, toward(ear, source), false,
                SoundFilters.blockedGain(open), highs));
    }

    /**
     * Как {@link #atEar(SoundEvent, Vec3, float, float, long)}, но фильтр пути задаёт вызывающий (дальний взрыв: что между — холм, земля,
     * погода — уже посчитал {@link Outdoor}): без луча по блокам и без своего воздуха.
     */
    public static void atEar(SoundEvent event, Vec3 source, float volume, float pitch, long seed, float gain, float highs) {
        Vec3 ear = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        play(new Shot(event.getLocation(), SoundSource.AMBIENT, volume, pitch, RandomSource.create(seed), SoundInstance.Attenuation.NONE,
                toward(ear, source), false, gain, highs));
    }

    /**
     * Звук без места в мире — отовсюду сразу (эхо взрыва: отражения от склонов, домов и леса приходят со всех сторон;
     * стерео играется как есть). Вариант выбирает зерно, верха — {@code highs}.
     *
     * @return звук, чтобы следить, звучит ли он ещё
     */
    static SoundInstance around(SoundEvent event, float volume, float pitch, long seed, float highs) {
        Shot s = new Shot(event.getLocation(), SoundSource.AMBIENT, volume, pitch, RandomSource.create(seed), SoundInstance.Attenuation.NONE,
                Vec3.ZERO, true, 1, highs);
        play(s);
        return s;
    }

    /** Верха записи раската, дошедшие по воздуху: дальние записи уже глухие — воздух добавляет не больше −12 дБ. */
    static float farAir(double d) {
        return Math.max(0.25f, SoundFilters.air(d));
    }

    /** Точка в 3 блоках от уха в сторону источника (ближе — сам источник). */
    private static Vec3 toward(Vec3 ear, Vec3 source) {
        Vec3 dir = source.subtract(ear);
        return dir.lengthSqr() < 9 ? source : ear.add(dir.normalize().scale(3));
    }

    /** Обычный позиционный звук с ванильным затуханием (громкость > 1 — дальше слышно: 16 блоков на единицу). */
    public static void at(SoundEvent event, Vec3 pos, float volume, float pitch) {
        Vec3 ear = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        float open = SoundFilters.open(ear, pos.add(0, 1, 0));
        float highs = SoundFilters.air(ear.distanceTo(pos)) * SoundFilters.blockedHighs(open);
        play(new Shot(event.getLocation(), SoundSource.AMBIENT, volume, pitch, RANDOM, SoundInstance.Attenuation.LINEAR, pos, false,
                SoundFilters.blockedGain(open), highs));
    }

    private static void play(SoundInstance sound) {
        Minecraft.getInstance().getSoundManager().play(sound);
    }

    /** Разовый звук с фильтром пути (воздух, преграды). */
    private static final class Shot extends SimpleSoundInstance implements SoundFilters.Muffled {
        private final float gain, highs;

        Shot(ResourceLocation id, SoundSource category, float volume, float pitch, RandomSource random, Attenuation attenuation, Vec3 at,
             boolean relative, float gain, float highs) {
            super(id, category, volume, pitch, random, false, 0, attenuation, at.x, at.y, at.z, relative);
            this.gain = gain;
            this.highs = highs;
        }

        @Override
        public float lowpassGain() {
            return gain;
        }

        @Override
        public float lowpassHighs() {
            return highs;
        }
    }
}
