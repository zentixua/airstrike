package ua.zentix.airstrike.client.sound;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import ua.zentix.airstrike.entity.BomberEntity;
import ua.zentix.airstrike.entity.StrikeProjectile;
import ua.zentix.airstrike.net.S2C;
import ua.zentix.airstrike.strike.WeaponType;

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
 * Путь снаряда — по сущности, а пока её у клиента нет (далеко или летит вне мира) — по пакетам сервера
 * {@link S2C.Heard}; один и тот же звук по UUID переходит с одного на другое.
 * <p>
 * Каждый тик здесь считается, что слышно от каждого снаряда ({@link Emission}) и как громко каждый его слой;
 * звучат (держат канал OpenAL) только слышимые слои и не больше {@link VoiceBudget#CAP} сразу.
 */
public final class ClientSounds {
    private static final Map<UUID, Tracked> TRACKS = new HashMap<>();
    private static final RandomSource RANDOM = RandomSource.create();
    /**
     * Без новых данных столько тиков (сущность пропала, пакетов о ней нет) — полёт кончился. Молчит звук раньше, как
     * только слушателю нечего слышать ({@link SourceTrack#covers}); этот срок — чтобы сервер, вставший на секунду,
     * не обрывал далёкий звук.
     */
    private static final int STALE = 40;
    /** Звук идёт до уха не дольше: дальше всех слышно свист крылатой ракеты, 3080 блоков, ~180 тиков. */
    private static final int RINGOUT = 200;
    /** Движок не дал слою канал (все заняты): через столько тиков попробовать снова. */
    private static final int RETRY = 10;
    private static long tick;

    private ClientSounds() {}

    /** Звук одного снаряда: путь, слои и фаза полёта, которую слушатель уже «услышал» (−1 — ещё никакую). */
    private static final class Tracked {
        final SourceTrack track;
        final List<Voice> voices;
        /** Есть слой ускорителя: смену фазы отмечают разовые звуки старта ({@link EngineSound#launchEvents}). */
        final boolean launchEvents;
        int heardPhase = -1;

        Tracked(SourceTrack track, List<EngineSound.Layer> layers) {
            this.track = track;
            this.voices = layers.stream().map(l -> new Voice(track, l)).toList();
            this.launchEvents = layers.contains(EngineSound.Layer.BOOSTER);
        }

        void kill() {
            voices.forEach(Voice::kill);
        }
    }

    /** Слой звука снаряда: что с ним в этот тик и звук, пока слой звучит. */
    private static final class Voice {
        final SourceTrack track;
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

        Voice(SourceTrack track, EngineSound.Layer layer) {
            this.track = track;
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
            EngineSound s = new EngineSound(track, layer);
            s.aim(heard, tone);
            // громкость, тон, место и фильтр — до запуска: так ваниль тикает отложенный звук перед play
            s.tick();
            manager.play(s);
            if (manager.isActive(s)) sound = s;
            else retryAt = tick + RETRY;
        }

        void kill() {
            if (sound != null) sound.kill();
            sound = null;
        }
    }

    /** Текущее время звука, тики клиента. */
    static double now() {
        return tick;
    }

    public static void tick() {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) {
            reset();
            return;
        }
        tick++;
        for (Entity e : level.entitiesForRendering()) {
            if (!(e instanceof StrikeProjectile p) || !p.isActive()) continue;
            track(p.getUUID(), p.weapon(), p instanceof BomberEntity).record(tick, p);
        }
        Iterator<Tracked> it = TRACKS.values().iterator();
        while (it.hasNext()) {
            Tracked t = it.next();
            if (!t.track.isDead() && tick - t.track.lastTick() > STALE) t.track.die(tick);
            if (t.track.isDead() && tick - t.track.deathTick() > RINGOUT) {
                t.kill();
                it.remove();
            }
        }
        budget(hear(mc));
    }

    /** Что слышно от каждого снаряда в этот тик: громкость каждого слоя и разовые звуки старта. */
    private static List<Voice> hear(Minecraft mc) {
        Vec3 ear = mc.gameRenderer.getMainCamera().getPosition();
        Entity camera = mc.getCameraEntity();
        List<Voice> voices = new ArrayList<>();
        for (Tracked t : TRACKS.values()) {
            SourceTrack track = t.track;
            double te = Acoustics.emissionTime(track, tick, ear.x, ear.y, ear.z);
            if (track.isDead() && te >= track.deathTick()) {
                // фронт взрыва дошёл: дальше слушатель слышит уже сам взрыв
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
            } else if (keep[i] && tick >= v.retryAt) {
                v.start();
            }
        }
    }

    /** Снаряды, которых у клиента нет, но которые уже слышно: путь по данным сервера. */
    public static void heard(S2C.Heard packet) {
        if (Minecraft.getInstance().level == null) return;
        for (S2C.HeardFlight f : packet.flights()) {
            track(f.id(), WeaponType.byId(f.weapon()), f.bomber()).record(tick, f);
        }
    }

    /** Звук снаряда: уже идущий или новый (прошлый полёт с тем же UUID уже отзвучал). */
    private static SourceTrack track(UUID id, WeaponType weapon, boolean bomber) {
        Tracked t = TRACKS.get(id);
        if (t == null || t.track.isDead()) {
            if (t != null) t.kill();
            SourceTrack track = new SourceTrack(id, weapon, bomber);
            t = new Tracked(track, layers(track));
            TRACKS.put(id, t);
        }
        return t.track;
    }

    private static List<EngineSound.Layer> layers(SourceTrack track) {
        return switch (track.weapon) {
            case DRONE -> List.of(EngineSound.Layer.DRONE_NEAR, EngineSound.Layer.DRONE_FAR, EngineSound.Layer.BOOSTER);
            case MISSILE -> List.of(EngineSound.Layer.MISSILE_FRONT, EngineSound.Layer.MISSILE_REAR, EngineSound.Layer.MISSILE_DIVE,
                    EngineSound.Layer.MISSILE_FAR, EngineSound.Layer.MISSILE_WHISTLE, EngineSound.Layer.BOOSTER);
            case BUNKER -> track.bomber
                    ? List.of(EngineSound.Layer.BOMBER_NEAR, EngineSound.Layer.BOMBER_FAR)
                    : List.of(EngineSound.Layer.BOMB_NEAR, EngineSound.Layer.BOMB_FAR, EngineSound.Layer.BOMB_DRILL);
            case NUKE -> List.of(EngineSound.Layer.BOOSTER);
            // РСЗО: рёв двигателя, пока горит; дальше снаряд летит по инерции и воет рассекаемым воздухом
            case ROCKET -> List.of(EngineSound.Layer.BOOSTER, EngineSound.Layer.ROCKET_AIR);
            // барражирующий: тот же винт, но маленький электромотор — выше и тише (см. EngineSound)
            case LOITER -> List.of(EngineSound.Layer.LOITER_NEAR, EngineSound.Layer.LOITER_FAR, EngineSound.Layer.LOITER_DIVE,
                    EngineSound.Layer.BOOSTER);
        };
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
        Vec3 ear = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        Vec3 dir = source.subtract(ear);
        Vec3 at = dir.lengthSqr() < 9 ? source : ear.add(dir.normalize().scale(3));
        float open = SoundFilters.open(ear, source.add(0, 1.5, 0));
        // дальние записи уже глухие — воздух добавляет не больше −12 дБ
        float highs = Math.max(0.25f, SoundFilters.air(dir.length())) * SoundFilters.blockedHighs(open);
        play(new Shot(event.getLocation(), category, volume, pitch, SoundInstance.Attenuation.NONE, at, SoundFilters.blockedGain(open), highs));
    }

    /** Обычный позиционный звук с ванильным затуханием (громкость > 1 — дальше слышно: 16 блоков на единицу). */
    public static void at(SoundEvent event, Vec3 pos, float volume, float pitch) {
        Vec3 ear = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        float open = SoundFilters.open(ear, pos.add(0, 1, 0));
        float highs = SoundFilters.air(ear.distanceTo(pos)) * SoundFilters.blockedHighs(open);
        play(new Shot(event.getLocation(), SoundSource.AMBIENT, volume, pitch, SoundInstance.Attenuation.LINEAR, pos, SoundFilters.blockedGain(open), highs));
    }

    private static void play(SoundInstance sound) {
        Minecraft.getInstance().getSoundManager().play(sound);
    }

    /** Разовый звук с фильтром пути (воздух, преграды). */
    private static final class Shot extends SimpleSoundInstance implements SoundFilters.Muffled {
        private final float gain, highs;

        Shot(ResourceLocation id, SoundSource category, float volume, float pitch, Attenuation attenuation, Vec3 at, float gain, float highs) {
            super(id, category, volume, pitch, RANDOM, false, 0, attenuation, at.x, at.y, at.z, false);
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
