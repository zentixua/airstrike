package ua.zentix.airstrike.client.sound;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;

/**
 * Сирена гражданской обороны на месте в мире — петля, пока идёт тревога. Тон сирены — скорость её ротора: включённая,
 * она раскручивается (тон и громкость растут), выключенная ({@link #off}) — сбегает и смолкает сама. Громкость
 * по расстоянию — линейно до {@link #RANGE}, как ванильное затухание LINEAR, но своя: ваниль берёт дальность LINEAR
 * из громкости один раз при запуске, а у раскручивающейся сирены громкость растёт с нуля. Воздух и преграды между
 * сиреной и ухом — каждый тик ({@link SoundFilters}), как у мотора снаряда: тревога длится весь полёт МБР, и слушатель
 * за это время выходит из дома и заходит обратно.
 */
public final class SirenSound extends AbstractTickableSoundInstance implements SoundFilters.Muffled {
    /** Дальше стольких блоков сирену не слышно. */
    static final double RANGE = 320;
    /** Доля пути скорости ротора к полной за тик: раскрутка — ~3 с. */
    static final float SPIN_UP = 0.05f;
    /** Доля скорости, которую ротор теряет за тик на выбеге: смолкает за ~4 с. */
    static final float SPIN_DOWN = 0.035f;

    private final Vec3 at;
    /** Тон на полной скорости: у соседних сирен он чуть разный, и вместе они «плывут», как на улице. */
    private final float tone;
    private final Occlusion occlusion = new Occlusion();
    /** Скорость ротора, доля полной. */
    private float speed;
    private boolean on = true;
    private float filterGain = 1, filterHighs = 1;

    public SirenSound(SoundEvent event, Vec3 at, float tone) {
        super(event, SoundSource.AMBIENT, RandomSource.create());
        this.at = at;
        this.tone = tone;
        this.looping = true;
        this.delay = 0;
        this.attenuation = SoundInstance.Attenuation.NONE;
        this.relative = false;
        this.x = at.x;
        this.y = at.y;
        this.z = at.z;
        this.volume = 0;
        this.pitch = 0.5f * tone;
    }

    /** Тревога кончилась: ротор сбегает, звук смолкает сам. */
    public void off() {
        on = false;
    }

    /** Замолчать сразу (выход из мира, движок снял канал). */
    public void kill() {
        stop();
    }

    /** Звучит с нуля: раскрутка начинается с тишины. */
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

    /**
     * Движок держит канал сирены. Не {@code SoundManager.isActive}: ползунок категории в 0 снимает канал
     * ({@code SoundEngine.tickNonPaused}), а {@code isActive} по записи о запуске и дальше отвечает «звучит».
     */
    public boolean playing() {
        return Minecraft.getInstance().getSoundManager().soundEngine.instanceToChannel.containsKey(this);
    }

    @Override
    public void tick() {
        // движок тикает и остановленный звук, пока не снимет его, а тот, чей канал снял ползунок категории, — до остановки
        // всех звуков (выход из мира, смена измерения)
        if (isStopped()) return;
        speed = on ? speed + (1 - speed) * SPIN_UP : speed * (1 - SPIN_DOWN);
        float power = speed * speed;
        if (!on && power <= VoiceBudget.AUDIBLE) {
            stop();
            return;
        }
        Vec3 ear = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        double d = ear.distanceTo(at);
        this.volume = (float) (power * Math.max(0, 1 - d / RANGE));
        // ваниль не играет ниже половины тона: на нём ротор и смолкает
        this.pitch = tone * (0.5f + 0.5f * speed);
        if (volume > VoiceBudget.AUDIBLE) {
            float open = occlusion.open(ClientSounds.now(), ear, at);
            filterGain = SoundFilters.blockedGain(open);
            filterHighs = SoundFilters.air(d) * SoundFilters.blockedHighs(open);
            SoundFilters.update(this, filterGain, filterHighs);
        }
    }
}
