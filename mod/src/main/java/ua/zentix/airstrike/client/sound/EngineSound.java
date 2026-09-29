package ua.zentix.airstrike.client.sound;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;
import ua.zentix.airstrike.entity.FlightPhase;
import ua.zentix.airstrike.registry.ModSounds;
import ua.zentix.airstrike.strike.Hearing;
import ua.zentix.airstrike.strike.WeaponType;

import java.util.Locale;
import java.util.function.Supplier;

/**
 * Непрерывный звук снаряда: движок каждый тик получает от нас положение, громкость и тон.
 * Положение и скорость берутся в «запаздывающий» момент (звук идёт к уху со скоростью 343 м/с, {@link Emission}),
 * отсюда Доплер и задержка; громкость ~1/d с полутоном дальности (ближний и дальний слой звука плавно сменяют друг
 * друга) — {@link Layer#tone}. Воздух по дороге съедает верха, холм или дом между снарядом и ухом глушит
 * ({@link SoundFilters}).
 * <p>
 * Слой звучит (держит канал OpenAL), только пока его слышно: запускает и отпускает его {@link ClientSounds}
 * по {@link VoiceBudget}; отпущенный слой стихает и останавливается сам. Возвращаясь, слой — новый звук,
 * он нарастает с нуля тем же сглаживанием, без щелчка.
 */
final class EngineSound extends AbstractTickableSoundInstance implements SoundFilters.Muffled {
    /** Слои звука: какой файл и как его громкость зависит от расстояния, ракурса и фазы полёта. */
    enum Layer {
        DRONE_NEAR(() -> ModSounds.DRONE_ENGINE.get()),
        DRONE_FAR(() -> ModSounds.DRONE_ENGINE_FAR.get()),
        MISSILE_FRONT(() -> ModSounds.MISSILE_ENGINE.get()),
        MISSILE_REAR(() -> ModSounds.MISSILE_ENGINE_REAR.get()),
        MISSILE_DIVE(() -> ModSounds.MISSILE_DIVE.get()),
        MISSILE_FAR(() -> ModSounds.MISSILE_ENGINE_FAR.get()),
        MISSILE_WHISTLE(() -> ModSounds.MISSILE_WHISTLE.get()),
        BOMBER_NEAR(() -> ModSounds.BOMBER_ENGINE.get()),
        BOMBER_FAR(() -> ModSounds.BOMBER_ENGINE_FAR.get()),
        BOMB_NEAR(() -> ModSounds.BOMB_FALL.get()),
        BOMB_FAR(() -> ModSounds.BOMB_FALL_FAR.get()),
        BOMB_DRILL(() -> ModSounds.BOMB_DRILL.get()),
        /** Стартовый ускоритель шахеда и ракеты, двигатель МБР; этот же слой отмечает поджиг и отделение ускорителя. */
        BOOSTER(() -> ModSounds.BOOSTER_ENGINE.get()),
        /** Снаряд РСЗО после того, как догорел двигатель: вой рассекаемого воздуха — над головой и на подлёте. */
        ROCKET_AIR(() -> ModSounds.ROCKET_INCOMING.get()),
        /** Барражирующий боеприпас: электромотор с винтом вблизи и вдали, вой винта и ветер в пике. */
        LOITER_NEAR(() -> ModSounds.LOITER_ENGINE.get()),
        LOITER_FAR(() -> ModSounds.LOITER_ENGINE_FAR.get()),
        LOITER_DIVE(() -> ModSounds.LOITER_DIVE.get());

        /** Звук из реестра берётся, только когда слой запускают: громкость и тон считаются и без реестров (юнит-тесты). */
        final Supplier<SoundEvent> event;

        Layer(Supplier<SoundEvent> event) {
            this.event = event;
        }

        /** Громкость и тон слоя для того, что слушатель слышит сейчас (до сглаживания). */
        Tone tone(SourceTrack track, Emission e) {
            double d = e.distance(), dop = e.doppler(), age = e.phaseAge();
            int phase = e.phase();
            Vec3 v = e.velocity();
            boolean approaching = e.approaching();
            double gain;
            double pitch = dop;
            switch (this) {
                case DRONE_NEAR, DRONE_FAR -> {
                    double w = near(d, 60, 160);
                    gain = Acoustics.gain(d, 60, 0.12, Hearing.ENGINE) * share(this == DRONE_NEAR ? w : 1 - w) * spool(phase, age, true);
                    pitch *= spoolPitch(phase, age, true);
                    if (phase == FlightPhase.TERMINAL.ordinal()) pitch *= 1.12;
                }
                case LOITER_NEAR, LOITER_FAR -> {
                    // маленький электромотор: тонкий вой, слышно ближе шахеда; в пике винт на полном газу — выше тоном,
                    // а громче всего воздух (слой LOITER_DIVE)
                    double w = near(d, 35, 110);
                    boolean dive = phase == FlightPhase.TERMINAL.ordinal();
                    gain = Acoustics.gain(d, 35, 0.12, Hearing.LOITER) * share(this == LOITER_NEAR ? w : 1 - w) * spool(phase, age, true)
                            * (dive ? 0.6 : 1);
                    pitch *= spoolPitch(phase, age, true) * (dive ? 1.15 : 1);
                }
                case LOITER_DIVE -> {
                    boolean dive = phase == FlightPhase.TERMINAL.ordinal();
                    gain = dive ? Acoustics.gain(d, 45, 0.1, Hearing.LOITER_DIVE) * Math.min(1, age / 10) : 0;
                }
                case MISSILE_FRONT, MISSILE_REAR, MISSILE_DIVE -> {
                    // спереди — свист вентилятора, сзади — рёв струи, в пике — пронзительный свист
                    Vec3 f = track.forward(e.time());
                    double front = (1 + f.x * e.nx() + f.y * e.ny() + f.z * e.nz()) / 2;
                    boolean dive = phase == FlightPhase.TERMINAL.ordinal();
                    double w = switch (this) {
                        case MISSILE_FRONT -> dive ? 0 : front;
                        case MISSILE_DIVE -> dive ? front : 0;
                        default -> 1 - front;
                    };
                    gain = Acoustics.gain(d, 90, 0.12, Hearing.ENGINE) * share(near(d, 60, 170)) * share(w) * spool(phase, age, false);
                    pitch *= spoolPitch(phase, age, false);
                }
                case MISSILE_FAR -> {
                    // вдали не тише «пола» до среза ближнего гула, дальше ~1/d до километра с лишним
                    gain = Math.max(Acoustics.gain(d, 90, 0.12, Hearing.ENGINE), Acoustics.gain(d, 90, 0, Hearing.JET))
                            * share(1 - near(d, 60, 170)) * spool(phase, age, false);
                    pitch *= spoolPitch(phase, age, false);
                }
                case MISSILE_WHISTLE -> {
                    // подлёт: всё время, пока ракета идёт на цель (заход по прямой, а не обход сбоку) и на слушателя, —
                    // свист слышно издалека, как дальний гул (верха по дороге съедает воздух), тон падает на последних
                    // WHISTLE_DROP блоках до цели; кроме того рвёт воздух над тем, мимо кого она проходит: слышно, пока
                    // идёт на слушателя, и тон падает при пролёте
                    double wd = e.aimDistance();
                    double attack = launched(phase) && e.towardAim() && approaching ? Acoustics.gain(d, 110, 0, Hearing.WHISTLE) : 0;
                    double flyby = launched(phase) ? Acoustics.airflow(d, v.length(), e.radial(), 40, 4, Hearing.AIRFLOW) * 0.6 : 0;
                    gain = Math.max(attack, flyby);
                    double attackPitch = (0.6 + 1.4 * Math.min(1, wd / WHISTLE_DROP)) * Math.sqrt(dop);
                    pitch = gain > 0 ? (attack * attackPitch + flyby * 0.8 * Math.sqrt(dop)) / (attack + flyby) : attackPitch;
                }
                case BOMBER_NEAR -> gain = Acoustics.gain(d, 120, 0.12, Hearing.BOMBER) * share(near(d, 120, 260));
                case BOMBER_FAR -> gain = Math.max(Acoustics.gain(d, 120, 0.12, Hearing.BOMBER), Acoustics.gain(d, 120, 0, Hearing.JET))
                        * share(1 - near(d, 120, 260));
                case BOMB_NEAR -> gain = track.drilling ? 0 : Acoustics.gain(d, 40, 0.12, Hearing.ENGINE) * share(near(d, 45, 140));
                case BOMB_FAR -> gain = track.drilling ? 0 : Acoustics.gain(d, 40, 0.12, Hearing.ENGINE) * share(1 - near(d, 45, 140));
                case BOMB_DRILL -> {
                    gain = track.drilling ? Math.max(0, 1 - d / 96) : 0;
                    pitch = 0.85;
                }
                case BOOSTER -> {
                    FlightPhase ph = FlightPhase.byId(phase);
                    if (track.weapon == WeaponType.LOITER) {
                        // катапульта — без огня: только разовый удар и свист (launchEvents)
                        gain = 0;
                    } else if (track.weapon == WeaponType.NUKE) {
                        // «Минитмен»: низкий рёв твердотопливной ступени, слышно за километр
                        gain = ph.boosterLit() ? Acoustics.gain(d, 400, 0.15, Hearing.ICBM) : 0;
                        pitch *= 0.7;
                    } else if (track.weapon == WeaponType.ROCKET) {
                        // маленький двигатель реактивного снаряда: резкое шипение, выше тоном; разовый «фш-ш» — в launchEvents
                        gain = ph.boosterLit() ? Acoustics.gain(d, 60, 0.05, Hearing.ROCKET_BOOSTER) * 0.6 : 0;
                        pitch *= 1.3;
                    } else {
                        // ускоритель — поверх разового рёва старта у пусковой: этот слой уходит вместе со снарядом
                        double ramp = ph == FlightPhase.IGNITION ? Math.min(1, age / 6) : 1;
                        gain = ph.boosterLit() ? Acoustics.gain(d, 150, 0.1, Hearing.BOOSTER) * 0.6 * ramp : 0;
                        if (track.weapon == WeaponType.MISSILE) pitch *= 0.9;
                    }
                }
                case ROCKET_AIR -> {
                    // по инерции: вой воздуха по всей дуге, от выгорания двигателя до падения, и слышно его так же
                    // далеко, как двигатель; громче, пока снаряд идёт на слушателя. Громкость по скорости — только
                    // у медленного снаряда: скорости в игре — десятая доля настоящих (у «Града» до 700 м/с), и на
                    // вершине дуги (2–3 блока/тик) он рвёт воздух так же. У точки падения, пока он идёт на слушателя,
                    // — ещё и громче к земле
                    FlightPhase ph = FlightPhase.byId(phase);
                    boolean coasting = ph == FlightPhase.CRUISE || ph == FlightPhase.TERMINAL;
                    double wd = e.aimDistance();
                    double incoming = ph == FlightPhase.TERMINAL && wd <= 220 && approaching
                            ? Acoustics.gain(d, 70, 0.1, Hearing.ROCKET_AIR) * (0.35 + 0.65 * (1 - wd / 220)) : 0;
                    gain = coasting ? Math.max(incoming, Acoustics.airflow(d, v.length(), e.radial(), 50, 2, Hearing.ROCKET_AIR)) : 0;
                    pitch = Math.sqrt(dop);
                }
                default -> gain = 0;
            }
            // камера снаряда: слушатель сидит на нём самом — мотор в упор, но не оглушающе
            if (e.onboard()) gain *= 0.5;
            return new Tone(gain, Math.max(0.5, Math.min(2.0, pitch)));
        }
    }

    /** Тон свиста крылатой ракеты падает на стольких последних блоках до цели. */
    private static final double WHISTLE_DROP = 260;

    /** Громкость и тон слоя в этот тик. */
    record Tone(double gain, double pitch) {}

    private final SourceTrack track;
    private final Layer layer;
    private float smoothVolume;
    private float filterGain = 1, filterHighs = 1;
    /** Что слышно в этот тик ({@code null} — данных о снаряде на этот момент нет) и громкость с тоном слоя. */
    private Emission heard;
    private Tone tone;
    /** Отпущен ({@link VoiceBudget}): стихает и останавливается. */
    private boolean released;

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

    String describe() {
        return layer.name().toLowerCase(Locale.ROOT);
    }

    /** Что слушатель слышит в этот тик: {@code heard} — {@code null}, если данных о снаряде на этот момент нет. */
    void aim(Emission heard, Tone tone) {
        this.heard = heard;
        this.tone = tone;
    }

    /** Отпустить канал: слой стихает и останавливается сам. */
    void release() {
        released = true;
    }

    /** Снова нужен, пока ещё не смолк: не останавливать. */
    void keep() {
        released = false;
    }

    void kill() {
        stop();
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
        if (heard == null) {
            // данных о снаряде на этот момент нет (взорвался или ушёл из слуха): стихнуть, не дёргая источник
            smoothVolume *= 0.5f;
            this.volume = smoothVolume;
        } else {
            // сглаживание: смена слоёв и ракурса, запуск и отпускание слоя — без щелчков
            double gain = released ? 0 : tone.gain();
            smoothVolume += (float) ((gain - smoothVolume) * 0.35);
            this.volume = smoothVolume;
            this.pitch = (float) tone.pitch();
            this.x = heard.x();
            this.y = heard.y();
            this.z = heard.z();
            if (smoothVolume > VoiceBudget.AUDIBLE) {
                Vec3 ear = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
                float open = track.open(ClientSounds.now(), ear, heard.position());
                filterGain = SoundFilters.blockedGain(open);
                filterHighs = SoundFilters.air(heard.distance()) * SoundFilters.blockedHighs(open);
                SoundFilters.update(this, filterGain, filterHighs);
            }
        }
        if (released && smoothVolume <= VoiceBudget.AUDIBLE) stop();
    }

    /**
     * Разовые звуки старта в момент, когда их фронт дошёл до уха: поджиг ускорителя (удар и рёв у пусковой)
     * и отделение (хлопок пиропатронов и лязг замков), у РСЗО — сход каждого снаряда с трубы, у барражирующего —
     * катапульта. Зовётся, когда слушатель услышал смену фазы: {@code prev} — прошлая услышанная, −1 — ещё никакой.
     * У МБР свой звук пуска ({@code NukeSounds}).
     */
    static void launchEvents(SourceTrack track, Emission e, int prev) {
        if (track.weapon == WeaponType.NUKE) return;
        FlightPhase ph = FlightPhase.byId(e.phase());
        double age = e.phaseAge(), d = e.distance();
        Vec3 at = e.position();
        // поджиг: при смене фазы или если снаряд попал в поле зрения уже на поджиге
        if (ph == FlightPhase.IGNITION && (prev >= 0 || age < 5) && track.weapon == WeaponType.ROCKET) {
            // сход реактивного снаряда: резкое «фш-ш» с треском; очередь по полсекунды сливается в рёв залпа
            float v = (float) Acoustics.gain(d, 100, 0.08, Hearing.ROCKET_LAUNCH);
            if (v > 0.01f) ClientSounds.atEar(ModSounds.ROCKET_LAUNCH.get(), at, v, 0.94f + (float) Math.random() * 0.12f);
        } else if (ph == FlightPhase.IGNITION && (prev >= 0 || age < 5) && track.weapon == WeaponType.LOITER) {
            // катапульта барражирующего: удар поршня и свист направляющей
            float v = (float) Acoustics.gain(d, 40, 0.05, Hearing.ENGINE);
            if (v > 0.01f) ClientSounds.atEar(ModSounds.LOITER_LAUNCH.get(), at, v, 0.95f + (float) Math.random() * 0.1f);
        } else if (ph == FlightPhase.IGNITION && (prev >= 0 || age < 5)) {
            float v = (float) Acoustics.gain(d, 150, 0.1, Hearing.BOOSTER);
            if (v > 0.01f) ClientSounds.atEar(ModSounds.LAUNCH_BOOSTER.get(), at, v, track.weapon == WeaponType.MISSILE ? 0.92f : 1.05f);
        } else if (ph == FlightPhase.CLIMB && prev >= 0 && FlightPhase.byId(prev).boosterLit() && track.weapon != WeaponType.LOITER) {
            float v = (float) Acoustics.gain(d, 40, 0, 250);
            if (v > 0.01f) ClientSounds.atEar(ModSounds.BOOSTER_SEPARATE.get(), at, v, 1);
        }
    }

    /**
     * Маршевый мотор на старте, доля громкости. Шахед молотит винтом ещё на пусковой (на малом газу, под рёвом
     * ускорителя), после отделения выходит на полный газ; турбина ракеты запускается только после отделения
     * и раскручивается за ~2,5 с.
     */
    private static double spool(int phase, double age, boolean drone) {
        FlightPhase ph = FlightPhase.byId(phase);
        if (ph.launching()) return drone ? (ph == FlightPhase.READY ? 0.3 : 0.45) : 0;
        if (ph != FlightPhase.CLIMB) return 1;
        return drone ? 0.45 + 0.55 * Math.min(1, age / 30) : Acoustics.smoothstep(0, 50, age);
    }

    /** Множитель тона маршевого мотора на старте (обороты растут). */
    private static double spoolPitch(int phase, double age, boolean drone) {
        FlightPhase ph = FlightPhase.byId(phase);
        if (ph.launching()) return drone ? (ph == FlightPhase.READY ? 0.72 : 0.8) : 0.55;
        if (ph != FlightPhase.CLIMB) return 1;
        return drone ? 0.8 + 0.2 * Math.min(1, age / 30) : 0.55 + 0.45 * Acoustics.smoothstep(0, 50, age);
    }

    /** Ракета уже сошла с пусковой и отстрелила ускоритель. */
    private static boolean launched(int phase) {
        FlightPhase ph = FlightPhase.byId(phase);
        return !ph.onLauncher() && !ph.boosterLit();
    }

    /** Вес ближнего слоя: 1 ближе a, 0 дальше b. */
    private static double near(double d, double a, double b) {
        return 1 - Acoustics.smoothstep(a, b, d);
    }

    /**
     * Доля слоя в переходе между разными записями (ближняя и дальняя, спереди и сзади): доли {@code w} и {@code 1 − w}
     * складываются по мощности (корень), а не по амплитуде. Записи разные и не коррелированы, поэтому при весах
     * 0,5 и 0,5 сумма по амплитуде тише на 3 дБ: ракета проседала на пролёте прямо над головой и на смене ближнего
     * гула дальним (60–170 блоков), становясь тише, хотя приближалась.
     */
    static double share(double w) {
        return Math.sqrt(Math.max(0, w));
    }
}
