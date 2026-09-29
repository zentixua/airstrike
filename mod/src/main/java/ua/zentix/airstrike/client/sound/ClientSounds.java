package ua.zentix.airstrike.client.sound;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
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
    /** Звук идёт до уха не дольше: самый дальний слышимый снаряд — ступень МБР, 1580 блоков, ~92 тика. */
    private static final int RINGOUT = 100;
    private static long tick;

    private ClientSounds() {}

    private record Tracked(SourceTrack track, List<EngineSound> sounds) {}

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
                t.sounds.forEach(EngineSound::kill);
                it.remove();
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
            if (t != null) t.sounds.forEach(EngineSound::kill);
            t = start(new SourceTrack(id, weapon, bomber));
            TRACKS.put(id, t);
        }
        return t.track;
    }

    private static Tracked start(SourceTrack track) {
        List<EngineSound> sounds = new ArrayList<>();
        List<EngineSound.Layer> layers = switch (track.weapon) {
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
        for (EngineSound.Layer l : layers) {
            EngineSound s = new EngineSound(track, l);
            sounds.add(s);
            Minecraft.getInstance().getSoundManager().play(s);
        }
        return new Tracked(track, sounds);
    }

    /**
     * Слышимые сейчас слои: «оружие (откуда путь: e — сущность, s — пакеты сервера; до уха, блоков) слой громкость×тон»
     * — для отладки звука по логу.
     */
    public static String describe() {
        StringBuilder sb = new StringBuilder();
        Vec3 ear = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        double[] p = new double[3];
        for (Tracked t : TRACKS.values()) {
            StringBuilder layers = new StringBuilder();
            for (EngineSound s : t.sounds) {
                // getVolume читает звук, который движок подставляет при запуске: без звука (нет устройства) его нет
                if (!s.isStopped() && s.getSound() != null && s.getVolume() > 0.01f) layers.append(String.format(Locale.ROOT, " %s %.2f×%.2f", s.describe(), s.getVolume(), s.getPitch()));
            }
            if (layers.isEmpty()) continue;
            t.track.at(t.track.lastTick(), p);
            sb.append(String.format(Locale.ROOT, " %s(%s %.0f)", t.track.weapon.getSerializedName(), t.track.fromServer() ? "s" : "e",
                    Math.sqrt((p[0] - ear.x) * (p[0] - ear.x) + (p[1] - ear.y) * (p[1] - ear.y) + (p[2] - ear.z) * (p[2] - ear.z)))).append(layers);
        }
        return sb.toString();
    }

    /** Отбой или выход из мира: заглушить всё сразу. */
    public static void reset() {
        TRACKS.values().forEach(t -> t.sounds.forEach(EngineSound::kill));
        TRACKS.clear();
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
