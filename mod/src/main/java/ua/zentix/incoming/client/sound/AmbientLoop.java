package ua.zentix.incoming.client.sound;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;

/**
 * Петля окружения, которую ведёт хозяин (гул горящего города, ветер в огонь): каждый тик он ставит, откуда она
 * слышна, громкость и расстояние до источника (воздух съедает верха, {@link SoundFilters}); громкость меняется
 * плавно. Хозяин перестал её вести (подрыв убран, повтор перемотан) — петля сама стихает и уходит.
 */
public final class AmbientLoop extends AbstractTickableSoundInstance implements SoundFilters.Muffled {
    /** На столько громкость подходит к цели за тик (за секунду — из тишины в полную). */
    private static final float SLEW = 0.05f;
    /** Хозяин молчит столько тиков — петля уходит. */
    private static final int ORPHAN = 10;

    private float target, highs = 1;
    private int quiet;
    private boolean ending;

    public AmbientLoop(SoundEvent event, SoundSource source, float pitch, long seed) {
        super(event, source, RandomSource.create(seed));
        looping = true;
        delay = 0;
        volume = 0.001f;
        this.pitch = pitch;
        relative = false;
        attenuation = Attenuation.NONE;
    }

    /**
     * Хозяин: звучать с направления {@code toward} от уха (в 3 блоках от него), с громкостью {@code volume}, источник
     * за {@code distance} блоков.
     */
    public void set(Vec3 ear, Vec3 toward, float volume, double distance) {
        Vec3 dir = toward.subtract(ear);
        Vec3 at = dir.lengthSqr() < 9 ? toward : ear.add(dir.normalize().scale(3));
        x = at.x;
        y = at.y;
        z = at.z;
        target = Math.max(0, Math.min(1, volume));
        highs = SoundFilters.air(distance);
        quiet = 0;
    }

    /** Стихнуть и уйти. */
    public void end() {
        ending = true;
        target = 0;
    }

    /**
     * Звучит ли: движок мог её не взять (нет каналов) или снять (перезапуск звука, ползунок категории в 0). Не
     * {@code SoundManager.isActive}: снятый ползунком канал ({@code SoundEngine.tickNonPaused}) он по записи о запуске
     * и дальше считает звучащим (как у {@link SirenSound#playing}).
     */
    public boolean playing() {
        return !isStopped() && Minecraft.getInstance().getSoundManager().soundEngine.instanceToChannel.containsKey(this);
    }

    @Override
    public void tick() {
        if (++quiet > ORPHAN) end();
        volume += Math.max(-SLEW, Math.min(SLEW, target - volume));
        if (ending && volume <= 0.002f) {
            stop();
            return;
        }
        SoundFilters.update(this, 1, highs);
    }

    @Override
    public boolean canStartSilent() {
        return true;
    }

    @Override
    public float lowpassGain() {
        return 1;
    }

    @Override
    public float lowpassHighs() {
        return highs;
    }
}
