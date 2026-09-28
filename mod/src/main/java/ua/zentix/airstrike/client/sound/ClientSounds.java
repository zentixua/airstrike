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
import ua.zentix.airstrike.entity.StrikeProjectile;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Звук снарядов на клиенте. У каждого снаряда свой независимый набор зацикленных звуков (никаких «4 ячеек»
 * и перебивающих друг друга свистов, как в датапаке); задержку и Доплер считает {@link EngineSound}.
 */
public final class ClientSounds {
    private static final Map<Integer, Tracked> TRACKS = new HashMap<>();
    private static final RandomSource RANDOM = RandomSource.create();
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
            Tracked t = TRACKS.get(p.getId());
            if (t == null || t.track.isDead()) {
                t = start(p);
                TRACKS.put(p.getId(), t);
            }
            t.track.record(tick, p);
        }
        Iterator<Tracked> it = TRACKS.values().iterator();
        while (it.hasNext()) {
            Tracked t = it.next();
            if (!t.track.isDead() && t.track.lastTick() < tick) t.track.die(tick);
            // через 3 секунды после взрыва звук уже дошёл до любого слушателя (60 блоков · 17/тик…)
            if (t.track.isDead() && tick - t.track.deathTick() > 60) {
                t.sounds.forEach(EngineSound::kill);
                it.remove();
            }
        }
    }

    private static Tracked start(StrikeProjectile p) {
        SourceTrack track = new SourceTrack(p);
        List<EngineSound> sounds = new ArrayList<>();
        List<EngineSound.Layer> layers = switch (p.weapon()) {
            case DRONE -> List.of(EngineSound.Layer.DRONE_NEAR, EngineSound.Layer.DRONE_FAR, EngineSound.Layer.BOOSTER);
            case MISSILE -> List.of(EngineSound.Layer.MISSILE_FRONT, EngineSound.Layer.MISSILE_REAR, EngineSound.Layer.MISSILE_DIVE,
                    EngineSound.Layer.MISSILE_FAR, EngineSound.Layer.MISSILE_WHISTLE, EngineSound.Layer.BOOSTER);
            case BUNKER -> p instanceof ua.zentix.airstrike.entity.BomberEntity
                    ? List.of(EngineSound.Layer.BOMBER_NEAR, EngineSound.Layer.BOMBER_FAR)
                    : List.of(EngineSound.Layer.BOMB_NEAR, EngineSound.Layer.BOMB_FAR, EngineSound.Layer.BOMB_DRILL);
            case NUKE -> List.of(EngineSound.Layer.BOOSTER);
        };
        for (EngineSound.Layer l : layers) {
            EngineSound s = new EngineSound(track, l);
            sounds.add(s);
            Minecraft.getInstance().getSoundManager().play(s);
        }
        return new Tracked(track, sounds);
    }

    /** Слышимые сейчас слои: «оружие/слой громкость×тон» — для отладки звука по логу. */
    public static String describe() {
        StringBuilder sb = new StringBuilder();
        for (Tracked t : TRACKS.values()) {
            for (EngineSound s : t.sounds) {
                if (!s.isStopped() && s.getVolume() > 0.01f) sb.append(String.format(" %s %.2f×%.2f", s.describe(), s.getVolume(), s.getPitch()));
            }
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
