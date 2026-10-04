package ua.zentix.airstrike.client.sound;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;
import ua.zentix.airstrike.defense.InterceptorSpec;
import ua.zentix.airstrike.entity.InterceptorEntity;
import ua.zentix.airstrike.registry.ModSounds;
import ua.zentix.airstrike.warhead.Warheads;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Мотор зенитной ракеты: петля твердотопливного двигателя ({@code booster.engine}, та же, что у ускорителей) за ракетой,
 * пока её вид есть у клиента. Громкость — линейно до {@link #RANGE}, на разгоне громче; тон — с Доплером по скорости
 * сближения с ухом; воздух и преграды — каждый тик ({@link SoundFilters}), как у сирены. Ракета пропала (разрыв, ушла
 * из тикающих чанков) — звук стихает за полсекунды, а не обрывается.
 */
public final class InterceptorSound extends AbstractTickableSoundInstance implements SoundFilters.Muffled {
    /** Дальше стольких блоков мотор не слышно. */
    static final double RANGE = 400;
    /** Доля громкости, которую звук теряет за тик, когда ракеты не стало. */
    private static final float FADE = 0.1f;
    private static final Set<InterceptorEntity> PLAYING = Collections.newSetFromMap(new WeakHashMap<>());

    private final InterceptorEntity rocket;
    private final Occlusion occlusion = new Occlusion();
    private float filterGain = 1, filterHighs = 1;
    private float fade = 1;
    private double lastDistance = -1;

    private InterceptorSound(InterceptorEntity rocket) {
        super(ModSounds.BOOSTER_ENGINE.get(), SoundSource.AMBIENT, RandomSource.create());
        this.rocket = rocket;
        this.looping = true;
        this.delay = 0;
        this.attenuation = SoundInstance.Attenuation.NONE;
        this.relative = false;
        this.volume = 0;
        this.pitch = 1.2f;
        this.x = rocket.getX();
        this.y = rocket.getY();
        this.z = rocket.getZ();
    }

    /** Раз в тик клиента для каждой ракеты: мотор новой ракеты зазвучит. */
    public static void tick(InterceptorEntity e) {
        if (PLAYING.add(e)) Minecraft.getInstance().getSoundManager().play(new InterceptorSound(e));
    }

    /** Звучит с нуля: громкость набирается в первом тике. */
    @Override
    public boolean canStartSilent() {
        return true;
    }

    @Override
    public float lowpassGain() {
        return filterGain;
    }

    @Override
    public float lowpassHighs() {
        return filterHighs;
    }

    @Override
    public void tick() {
        if (isStopped()) return;
        if (rocket.isRemoved()) {
            fade -= FADE;
            if (fade <= 0) {
                stop();
                return;
            }
        } else {
            x = rocket.getX();
            y = rocket.getY();
            z = rocket.getZ();
        }
        Vec3 at = new Vec3(x, y, z);
        Vec3 ear = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        double d = ear.distanceTo(at);
        boolean boost = rocket.age() <= InterceptorSpec.SAM.boostTicks();
        volume = fade * (float) Math.max(0, 1 - d / RANGE) * (boost ? 1f : 0.7f);
        // Доплер: скорость сближения с ухом за тик против скорости звука
        double closing = lastDistance < 0 || rocket.isRemoved() ? 0 : lastDistance - d;
        lastDistance = d;
        pitch = Mth.clamp((float) (1.2 * Warheads.FRONT_SPEED / Math.max(1, Warheads.FRONT_SPEED - closing)), 0.5f, 2f);
        if (volume > VoiceBudget.AUDIBLE) {
            float open = occlusion.open(ClientSounds.now(), ear, at);
            filterGain = SoundFilters.blockedGain(open);
            filterHighs = SoundFilters.air(d) * SoundFilters.blockedHighs(open);
            SoundFilters.update(this, filterGain, filterHighs);
        }
    }
}
