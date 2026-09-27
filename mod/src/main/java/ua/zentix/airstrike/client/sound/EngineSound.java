package ua.zentix.airstrike.client.sound;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;
import ua.zentix.airstrike.entity.CruiseMissileEntity;
import ua.zentix.airstrike.entity.DroneEntity;
import ua.zentix.airstrike.registry.ModSounds;

import java.util.function.Supplier;

/**
 * Непрерывный звук снаряда: движок каждый тик получает от нас положение, громкость и тон.
 * Положение и скорость берутся в «запаздывающий» момент (звук идёт к уху со скоростью 343 м/с), отсюда Доплер
 * и задержка; громкость ~1/d с полутоном дальности (ближний и дальний слой звука плавно сменяют друг друга).
 */
final class EngineSound extends AbstractTickableSoundInstance {
    /** Слои звука: какой файл и как его громкость зависит от расстояния, ракурса и фазы полёта. */
    enum Layer {
        DRONE_NEAR(ModSounds.DRONE_ENGINE::get),
        DRONE_FAR(ModSounds.DRONE_ENGINE_FAR::get),
        MISSILE_FRONT(ModSounds.MISSILE_ENGINE::get),
        MISSILE_REAR(ModSounds.MISSILE_ENGINE_REAR::get),
        MISSILE_DIVE(ModSounds.MISSILE_DIVE::get),
        MISSILE_FAR(ModSounds.MISSILE_ENGINE_FAR::get),
        MISSILE_WHISTLE(ModSounds.MISSILE_WHISTLE::get),
        BOMBER_NEAR(ModSounds.BOMBER_ENGINE::get),
        BOMBER_FAR(ModSounds.BOMBER_ENGINE_FAR::get),
        BOMB_NEAR(ModSounds.BOMB_FALL::get),
        BOMB_FAR(ModSounds.BOMB_FALL_FAR::get),
        BOMB_DRILL(ModSounds.BOMB_DRILL::get);

        final Supplier<SoundEvent> event;

        Layer(Supplier<SoundEvent> event) {
            this.event = event;
        }
    }

    private final SourceTrack track;
    private final Layer layer;
    private final double[] p = new double[3];
    private float smoothVolume;

    EngineSound(SourceTrack track, Layer layer) {
        super(layer.event.get(), SoundSource.AMBIENT, RandomSource.create());
        this.track = track;
        this.layer = layer;
        this.looping = true;
        this.delay = 0;
        this.attenuation = SoundInstance.Attenuation.NONE;
        this.relative = false;
        this.volume = 0;
        this.pitch = 1;
    }

    @Override
    public boolean canStartSilent() {
        return true;
    }

    @Override
    public boolean canPlaySound() {
        return true;
    }

    String describe() {
        return layer.name().toLowerCase(java.util.Locale.ROOT);
    }

    void kill() {
        stop();
    }

    @Override
    public void tick() {
        Vec3 ear = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        double now = ClientSounds.now();
        double te = Acoustics.emissionTime(track, now, ear.x, ear.y, ear.z);
        if (track.isDead() && te >= track.deathTick()) {
            // фронт взрыва дошёл: дальше слушатель слышит уже сам взрыв
            stop();
            return;
        }
        track.at(te, p);
        double dx = ear.x - p[0], dy = ear.y - p[1], dz = ear.z - p[2];
        double d = Math.max(0.5, Math.sqrt(dx * dx + dy * dy + dz * dz));
        double nx = dx / d, ny = dy / d, nz = dz / d;
        Vec3 v = track.velocity(te);
        double dop = Acoustics.doppler(v.x, v.y, v.z, nx, ny, nz);
        boolean approaching = v.x * nx + v.y * ny + v.z * nz > 0;

        double gain;
        double pitch = dop;
        int phase = track.phase(te);
        switch (layer) {
            case DRONE_NEAR -> {
                gain = Acoustics.gain(d, 60, 0.12, 300) * near(d, 60, 160);
                if (phase == DroneEntity.PHASE_DIVE) pitch *= 1.12;
            }
            case DRONE_FAR -> {
                gain = Acoustics.gain(d, 60, 0.12, 300) * (1 - near(d, 60, 160));
                if (phase == DroneEntity.PHASE_DIVE) pitch *= 1.12;
            }
            case MISSILE_FRONT, MISSILE_REAR, MISSILE_DIVE -> {
                // спереди — свист вентилятора, сзади — рёв струи, в пике — пронзительный свист
                Vec3 f = track.forward(te);
                double front = (1 + f.x * nx + f.y * ny + f.z * nz) / 2;
                boolean dive = phase == CruiseMissileEntity.PHASE_DIVE;
                double w = switch (layer) {
                    case MISSILE_FRONT -> dive ? 0 : front;
                    case MISSILE_DIVE -> dive ? front : 0;
                    default -> 1 - front;
                };
                gain = Acoustics.gain(d, 90, 0.12, 300) * near(d, 60, 170) * w;
            }
            case MISSILE_FAR -> gain = Acoustics.gain(d, 90, 0.12, 300) * (1 - near(d, 60, 170));
            case MISSILE_WHISTLE -> {
                // свист на последних 260 блоках, пока ракета приближается; тон ниже к цели
                double wd = track.distanceToAim;
                gain = wd <= 260 && approaching ? Acoustics.gain(d, 110, 0.2, 300) : 0;
                pitch = (0.6 + 1.4 * Math.min(1, wd / 260)) * Math.sqrt(dop);
            }
            case BOMBER_NEAR -> gain = Acoustics.gain(d, 120, 0.12, 330) * near(d, 120, 260);
            case BOMBER_FAR -> gain = Acoustics.gain(d, 120, 0.12, 330) * (1 - near(d, 120, 260));
            case BOMB_NEAR -> gain = track.drilling ? 0 : Acoustics.gain(d, 40, 0.12, 300) * near(d, 45, 140);
            case BOMB_FAR -> gain = track.drilling ? 0 : Acoustics.gain(d, 40, 0.12, 300) * (1 - near(d, 45, 140));
            case BOMB_DRILL -> {
                gain = track.drilling ? Math.max(0, 1 - d / 96) : 0;
                pitch = 0.85;
            }
            default -> gain = 0;
        }
        // сглаживание: смена слоёв и ракурса без щелчков
        smoothVolume += (float) ((gain - smoothVolume) * 0.35);
        this.volume = smoothVolume;
        this.pitch = (float) Math.max(0.5, Math.min(2.0, pitch));
        this.x = p[0];
        this.y = p[1];
        this.z = p[2];
    }

    /** Вес ближнего слоя: 1 ближе a, 0 дальше b. */
    private static double near(double d, double a, double b) {
        return 1 - Acoustics.smoothstep(a, b, d);
    }
}
