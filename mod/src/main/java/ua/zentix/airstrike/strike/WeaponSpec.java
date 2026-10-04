package ua.zentix.airstrike.strike;

import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntityType;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.jetbrains.annotations.Nullable;
import ua.zentix.airstrike.AirstrikeConfig;
import ua.zentix.airstrike.entity.FlightPhase;
import ua.zentix.airstrike.entity.LauncherRack;
import ua.zentix.airstrike.entity.StrikeProjectile;
import ua.zentix.airstrike.guidance.BombDrop;
import ua.zentix.airstrike.guidance.FlightController;
import ua.zentix.airstrike.registry.ModEntities;

import java.util.function.Supplier;
import java.util.function.ToDoubleFunction;

/**
 * Паспорт оружия: всё, чем один вид оружия отличается от другого, — в одном описании на вид ({@link WeaponType#spec()}).
 * Пуск, взрыв, сирена, слышимость, пусковая и сами снаряды читают числа отсюда, а не держат свои копии и
 * {@code switch} по виду оружия. Клиентская часть (звуковые слои, модели) — {@code client.ClientWeaponSpec}: сервер
 * клиентские классы не грузит.
 * <p>
 * Законы наведения ({@code guidance/}) и логика фаз полёта в сущностях — не здесь: паспорт даёт им числа (скорости,
 * пределы поворота, высоты), а как лететь, решают они.
 *
 * @param siren      какая тревога у цели
 * @param sirenSeconds за сколько секунд до удара цель «видит» снаряд и включается тревога (у МБР — свой отсчёт, не этот)
 * @param salvo      залп: паузы между пусками и что ставит пульт, когда выбирают это оружие
 * @param warhead    ядерная боевая часть: всегда, по выбору или никогда
 * @param power      сила взрыва боевой части — настройка мира
 * @param launch     как пускается ({@link StrikeService#launch})
 * @param route      путь до цели: время полёта, последний прямой участок, вынос пусковой
 * @param tracking   идёт ли за движущейся целью, которую заметил игрок ({@code target.Target.Sighted})
 * @param rack       пакет пусковой у стреляющего; null — оружие с пусковой у игрока не стартует
 * @param blast      картинка и вторичные подрывы наземного взрыва ({@code Warheads})
 * @param charge     заряд боевой части в тротиловом эквиваленте, кг: по давлению его волны наземный взрыв выбивает
 *                   стёкла ({@code Warheads}); 0 — наземного взрыва нет ({@link Blast#NONE})
 * @param penetrates бьёт на глубине точки (бомба ищет пещеру под целью), а не по поверхности
 * @param confirmPitch тон щелчка пульта, когда пуск принят
 * @param airframe   сущность, которой летит оружие (у B-2 — сам бомбардировщик)
 * @param payload    вторая сущность полёта: бомба, которую сбрасывает носитель; null — её нет
 */
public record WeaponSpec(WeaponType.SirenKind siren, int sirenSeconds, Salvo salvo, Warhead warhead,
                         Supplier<ModConfigSpec.IntValue> power, Launch launch, Route route, Tracking tracking, @Nullable LauncherRack rack,
                         Blast blast, double charge, boolean penetrates, float confirmPitch, Airframe airframe, @Nullable Airframe payload) {

    /** Как оружие пускается. */
    public enum Launch {
        /** Шахед и крылатая ракета: с пусковой рядом со стреляющим по маршруту в обход, иначе издалека. */
        GUIDED,
        /** Реактивный снаряд РСЗО из трубы пакета, по баллистике. */
        ROCKET,
        /** Барражирующий боеприпас с катапульты: прямо к цели и круг над ней. */
        LOITER,
        /** B-2 заходит издалека и сбрасывает бомбу. */
        BOMBER,
        /** МБР: запланированный ядерный удар ({@code NuclearStrikes}). */
        ICBM
    }

    /**
     * Как оружие бьёт по движущейся цели (сущность, аппарат), которую игрок выбрал, видя её ({@code target.Target.Sighted}).
     * Приказ без правил (консоль, командный блок, ведущий, команда оператора) этого не касается: там снаряд идёт за целью.
     */
    public enum Tracking {
        /**
         * Идёт за целью, пока оператор держит её в кадре камеры этого снаряда ({@link CameraLink}); не держит — летит туда,
         * где её видели в последний раз. Камера у оператора одна, поэтому ведомый снаряд у него тоже один.
         */
        CAMERA,
        /** Бьёт туда, где цель была при приказе: за ней не идёт. */
        POINT
    }

    /**
     * Залп.
     *
     * @param gapMin пауза между пусками, тиков: от
     * @param gapMax до
     * @param count  сколько в залпе ставит пульт, когда выбирают это оружие вместо одиночного (1 — оставить как было)
     * @param spread и разброс не меньше, блоков
     */
    public record Salvo(int gapMin, int gapMax, int count, int spread) {
        static Salvo gaps(int gapMin, int gapMax) {
            return new Salvo(gapMin, gapMax, 1, 0);
        }
    }

    /**
     * Ядерная боевая часть.
     *
     * @param always     всегда ядерная (МБР)
     * @param optional   может нести ядерную вместо обычной ({@code Loadout.Nuke#onCarrier}: ракета, бомба B-2)
     * @param chooseBurst подрыв выбирается: воздушный или наземный (бомба рвётся под землёй — выбора нет)
     */
    public record Warhead(boolean always, boolean optional, boolean chooseBurst) {
        static final Warhead CONVENTIONAL = new Warhead(false, false, false);
    }

    /** Наземный взрыв боевой части: картинка у клиентов ({@code S2C.Blast}) и то, что взрыв делает сверх главного подрыва. */
    public enum Blast {
        /** Взрыв шахеда: главный подрыв и огненный шар. */
        DRONE,
        /** Взрыв крылатой ракеты: сильнее огненный шар, кольцо огня, вторичные подрывы, свои обломки. */
        MISSILE,
        /** Взрыв реактивного снаряда РСЗО. */
        ROCKET,
        /** Наземного взрыва боевой части нет: у бомбы — подземный ({@code BunkerBlast}), у МБР — ядерный подрыв. */
        NONE
    }

    /**
     * Путь до цели.
     *
     * @param flightSeconds время полёта от пуска до цели, секунд (настройка мира): путь маршрута = скорость × время;
     *                      null — время полёта следует из дальности, а не задаётся
     * @param finalLeg      последний прямой участок перед целью, блоков (у B-2 — дальность сброса на эшелоне)
     * @param standoff      без стреляющего рядом пусковая стоит за столько блоков от цели (по направлению захода)
     * @param dispersion    рассеивание неуправляемого снаряда: СКО точки падения по каждой оси — такая доля дальности
     *                      стрельбы, не меньше блока; 0 — снаряд управляемый, точка — сама цель
     * @param capture       точка маршрута взята, когда до неё ближе стольких блоков по горизонтали (или она уже позади,
     *                      {@code guidance.Route#update}): около радиуса разворота — ближе снаряд кружил бы вокруг точки
     * @param reach         дальность по маршруту оператора ({@link Waypoints}): путь от него через его точки до цели не
     *                      длиннее стольких блоков; 0 — по точкам оператора не летает
     */
    public record Route(@Nullable Supplier<ModConfigSpec.IntValue> flightSeconds, double finalLeg, double standoff, double dispersion,
                        double capture, double reach) {
        /** Время полёта из настроек, секунд. */
        public int seconds() {
            return flightSeconds == null ? 0 : flightSeconds.get().get();
        }

        /** Летит по точкам оператора ({@link Waypoints}). */
        public boolean waypoints() {
            return reach > 0;
        }

        /** СКО точки падения по каждой оси при стрельбе на {@code range} блоков (0 — без рассеивания). */
        public double sigma(double range) {
            return dispersion > 0 ? Math.max(1, range * dispersion) : 0;
        }
    }

    /**
     * Стартовый участок с пусковой: сколько гореть на направляющей, сколько работает ускоритель, как разгоняет
     * и куда к концу разгона опускает нос.
     *
     * @param ignitionTicks ускоритель горит, снаряд ещё стоит
     * @param boostTicks    работа ускорителя после схода
     * @param boostAccel    прирост скорости за тик, блоков/тик²
     * @param railTicks     первые тики разгона нос держит угол направляющей
     * @param boostEndPitch тангаж к концу разгона (° , < 0 — нос вверх)
     */
    public record LaunchProfile(int ignitionTicks, int boostTicks, double boostAccel, int railTicks, float boostEndPitch) {
        /**
         * Тик разгона {@code tick} (с 1 до {@code boostTicks}): тяга растёт не сразу — первые тики снаряд едва сползает
         * с направляющей; сойдя с неё, нос уходит к тангажу конца разгона. Один закон у снаряда в полёте и у проверки
         * сектора пуска ({@code LaunchSite.clearAhead}).
         *
         * @return скорость после тика
         */
        public double boost(FlightController flight, double speed, int tick) {
            if (tick > railTicks) flight.holdPitch(boostEndPitch, 0.06, 1.2, 0.12);
            return speed + boostAccel * Math.min(1, (tick + 1) / 6.0);
        }
    }

    /**
     * Летательный аппарат: числа, которыми сущность снаряда отличается от других.
     *
     * @param entity        тип сущности (поставщик: паспорт не трогает реестр, пока его не спросят)
     * @param cruiseSpeed   маршевая скорость, блоков/тик: по ней считается время подлёта
     * @param diveSpeed     предельная скорость в пикировании (у кого пике нет — маршевая)
     * @param turnRate      предельная скорость разворота по курсу на маршруте и в атаке, °/тик (0 — не управляется)
     * @param climbTurnRate то же на наборе высоты после старта
     * @param cruiseHeight  высота крейсера (над стартом и целью у шахеда, над целью у «Ланцета» — круг, над рельефом
     *                      у ракеты — бреющий полёт, над точкой сброса у B-2)
     * @param clearance     наименьший запас высоты над рельефом, с которым снаряд возвращается в мир из полёта вне мира
     * @param reliefLookahead на сколько блоков пути вперёд автопилот смотрит рельеф полосой ({@code guidance.Autopilot.reliefAhead}
     *                      у шахеда и ракеты — с наклоном набора, у «Ланцета» — прямо по курсу без наклона); 0 — не смотрит
     * @param noseLength    полудлина корпуса: от центра до носа, блоков
     * @param health        сколько урона выдержит, прежде чем его собьют; 0 — сбить нельзя
     * @param range         запас хода, блоков, если его не задаёт план полёта ({@code guidance.Mission}): столько, сколько
     *                      снаряд пролетает на маршевой скорости за прежний срок жизни
     * @param reachPad      запас дальности неконтактного взрывателя сверх шага за тик, блоков
     * @param launchProfile старт с пусковой; null — с пусковой не стартует (или у него свой старт, как у РСЗО)
     * @param visibleLeg    последние столько блоков до цели снаряд летит в мире, когда у цели есть игрок
     *                      ({@link FlightTickets#approach}); 0 — только район цели
     * @param attack        геометрия атаки управляемого снаряда
     * @param audible       докуда слышно снаряд в этой фазе, блоков, без затухания {@link Hearing#FADE} (0 — не слышно)
     */
    public record Airframe(Supplier<EntityType<? extends StrikeProjectile>> entity, double cruiseSpeed, double diveSpeed,
                           double turnRate, double climbTurnRate, double cruiseHeight, double clearance, double reliefLookahead, double noseLength,
                           float health, double range, double reachPad, @Nullable LaunchProfile launchProfile, double visibleLeg,
                           Attack attack, ToDoubleFunction<FlightPhase> audible) {
        /** Радиус разворота на скорости {@code speed} с пределом поворота на маршруте ({@link #turnRadius(double, double)}). */
        public double turnRadius(double speed) {
            return WeaponSpec.turnRadius(speed, turnRate);
        }
    }

    /**
     * Геометрия атаки управляемого снаряда. Эти числа не выводятся из скорости и поворота одной формулой: они
     * подобраны по картинке и сценариям полёта и держат запас на то, чего формула не видит (набор угловой скорости,
     * разгон в пике, рельеф). Их связь с выводимыми числами (радиус разворота, геометрия горки) проверяет
     * {@code WeaponSpecTest}: поменяв скорость или поворот, тест скажет, какое из них пересмотреть.
     *
     * @param reattackMin   ближе этого (по горизонтали) атака из-за круга разворота не отменяется: малый промах
     *                      добирает неконтактный взрыватель, а пролетев, снаряд зайдёт снова. Меньше радиуса разворота
     * @param terminalRange горка перед пикированием начинается в стольких блоках от цели (0 — горки нет): не ближе
     *                      геометрии горки (подъём с бреющего полёта до вершины и пике с неё под углом входа) с запасом
     *                      на переход по тангажу
     * @param popUpMinRange горка только при заходе хотя бы с такого расстояния: ближе ракете не хватит места набрать
     *                      высоту; больше {@code terminalRange} на путь, за который ракета выходит на курс атаки
     */
    public record Attack(double reattackMin, double terminalRange, double popUpMinRange) {
        static final Attack NONE = new Attack(0, 0, 0);
    }

    /** Запас радиуса разворота, внутри которого точку не достать ({@code guidance.Autopilot.insideTurn}): угловая скорость набирается не сразу. */
    public static final double TURN_MARGIN = 1.2;

    /**
     * Радиус разворота: скорость v блоков/тик при угловой скорости ω °/тик — r = v / ω (ω в радианах). Шахед на 2.1
     * и 3°/тик — ~40 блоков, ракета на 4 и 3°/тик — ~76, B-2 на 12 и 1°/тик — ~690.
     */
    public static double turnRadius(double speed, double rateDeg) {
        return speed / Math.toRadians(rateDeg);
    }

    /** За сколько тиков до удара включается тревога у цели. */
    public int sirenLead() {
        return sirenSeconds * 20;
    }

    /** Стартовый участок: на пусковой или горит ускоритель. */
    private static boolean launching(FlightPhase ph) {
        return ph.onLauncher() || ph.boosterLit();
    }

    /** Сила взрыва боевой части (настройка мира). */
    public float blastPower() {
        return power.get().get();
    }

    /** Пауза между пусками в залпе, тиков. */
    public int salvoGap(RandomSource random) {
        return salvo.gapMin() + random.nextInt(salvo.gapMax() - salvo.gapMin() + 1);
    }

    /**
     * Дрон-камикадзе в духе Shahed-136: ≈150 км/ч, крейсер над рельефом, пикирование на цель с разгоном до 3 блоков/тик.
     * Стартовый ускоритель горит ~2 с и сбрасывается. Боевая часть — 50 кг: стёкла выбивает до ~70 блоков. По точкам
     * оператора — до 30 км пути (~12 минут полёта): втрое дальше карты пульта по умолчанию.
     */
    public static final WeaponSpec DRONE = new WeaponSpec(WeaponType.SirenKind.AIR_RAID, 25, Salvo.gaps(20, 40), Warhead.CONVENTIONAL,
            () -> AirstrikeConfig.SERVER.dronePower, Launch.GUIDED,
            new Route(() -> AirstrikeConfig.SERVER.droneFlightTime, 300, 0, 0, 40, 30_000), Tracking.CAMERA, LauncherRack.DRONE, Blast.DRONE, 50, false, 0.6f,
            new Airframe(() -> ModEntities.DRONE.get(), 2.1, 3.0, 3.0, 1.6, 45, 20, 256, 1.83, 12, 900 * 2.1, 4.3,
                    new LaunchProfile(8, 38, 0.075, 8, -9), 0, new Attack(16, 0, 0),
                    ph -> launching(ph) ? Hearing.BOOSTER : Hearing.ENGINE),
            null);

    /**
     * Крылатая ракета: 80 м/с (4 блока/тик, ≈290 км/ч) — медленнее настоящей, чтобы подлёт было видно; бреющий полёт
     * в 12 блоках над рельефом, горка и пикирование до 5 блоков/тик. Ускоритель выносит её до ~3.4 блока/тик — ниже
     * маршевой: дальше разгоняет турбина, без рывка вниз. Последние 256 блоков — в мире: больше дальности прорисовки
     * 12 чанков, чтобы подлёт с её края был виден и при меньшей дистанции симуляции. Боевая часть — 450 кг, как у «Калибра»
     * и Х-101: стёкла выбивает до ~150 блоков. По точкам оператора — до 30 км пути (~6 минут полёта).
     */
    public static final WeaponSpec MISSILE = new WeaponSpec(WeaponType.SirenKind.MISSILE, 15, Salvo.gaps(15, 30), new Warhead(false, true, true),
            () -> AirstrikeConfig.SERVER.missilePower, Launch.GUIDED,
            new Route(() -> AirstrikeConfig.SERVER.missileFlightTime, 500, 0, 0, 120, 30_000), Tracking.POINT, LauncherRack.MISSILE, Blast.MISSILE, 450, false, 0.5f,
            new Airframe(() -> ModEntities.CRUISE_MISSILE.get(), 4.0, 5.0, 3.0, 2.0, 12, 12, 256, 2.96, 8, 700 * 4.0, 6.5,
                    new LaunchProfile(6, 40, 0.09, 8, -14), 256, new Attack(64, 160, 185),
                    ph -> launching(ph) ? Hearing.BOOSTER : Hearing.WHISTLE),
            null);

    /**
     * B-2 на эшелоне +170 над местом падения бомбы, 12 блоков/тик, разворот 1°/тик (радиус ~690 блоков), сброс за ~85
     * блоков до цели; бетонобойная бомба пробивает грунт и взрывается под землёй.
     */
    public static final WeaponSpec BUNKER = new WeaponSpec(WeaponType.SirenKind.AIR_RAID, 20, Salvo.gaps(60, 80), new Warhead(false, true, false),
            () -> AirstrikeConfig.SERVER.bunkerPower, Launch.BOMBER,
            new Route(() -> AirstrikeConfig.SERVER.bomberFlightTime, 85, 0, 0, 0, 0), Tracking.POINT, null, Blast.NONE, 0, true, 0.5f,
            new Airframe(() -> ModEntities.BOMBER.get(), 12, 12, 1.0, 1.0, 170, 30, 0, 8.5, 0, 120 * 12, 0, null, 0, Attack.NONE,
                    ph -> Hearing.JET),
            new Airframe(() -> ModEntities.BUNKER_BUSTER.get(), BombDrop.MAX_SPEED, BombDrop.MAX_SPEED, 0, 0, 0, 12, 0, BombDrop.NOSE, 0, 300 * BombDrop.MAX_SPEED,
                    BombDrop.REACH_PAD, null, 0, Attack.NONE,
                    ph -> Hearing.ENGINE));

    /**
     * МБР с ядерной боеголовкой: только участок разгона до 25 блоков/тик; удар — таймер {@code NuclearStrikes}. Сила
     * взрыва — {@code bunkerPower}: своей настройки у МБР нет, её подрыв ядерный (мощность — килотонны {@code Loadout.Nuke}),
     * а поле паспорта обязательно.
     */
    public static final WeaponSpec NUKE = new WeaponSpec(WeaponType.SirenKind.NUCLEAR, 0, Salvo.gaps(200, 300), new Warhead(true, false, true),
            () -> AirstrikeConfig.SERVER.bunkerPower, Launch.ICBM,
            new Route(null, 0, 0, 0, 0, 0), Tracking.POINT, null, Blast.NONE, 0, false, 0.5f,
            new Airframe(() -> ModEntities.ICBM.get(), 25, 25, 0, 0, 0, 12, 0, 9, 0, 600 * 25, 0, null, 0, Attack.NONE,
                    ph -> ph.boosterLit() ? Hearing.ICBM : 0),
            null);

    /**
     * РСЗО в духе БМ-21 «Град»: неуправляемые реактивные снаряды по баллистике с пакета из 40 труб, залп очередью
     * по полсекунды; одиночным не стреляет — пульт ставит очередь из 12 по площади 15 блоков; без стреляющего рядом
     * пакет стоит в 600 блоках от цели. Заряд снаряда 9М22У — 6,4 кг ВВ: стёкла выбивает до ~37 блоков.
     */
    public static final WeaponSpec ROCKET = new WeaponSpec(WeaponType.SirenKind.MISSILE, 8, new Salvo(8, 12, 12, 15), Warhead.CONVENTIONAL,
            () -> AirstrikeConfig.SERVER.rocketPower, Launch.ROCKET,
            new Route(null, 0, 600, 0.01, 0, 0), Tracking.POINT, LauncherRack.ROCKET, Blast.ROCKET, 6.4, false, 0.5f,
            new Airframe(() -> ModEntities.ROCKET.get(), 4, 4, 0, 0, 0, 4, 0, 1.45, 2, 2400 * 4, 1.5, null, 0, Attack.NONE,
                    ph -> launching(ph) ? Hearing.ROCKET_LAUNCH : Hearing.ROCKET_AIR),
            null);

    /**
     * Барражирующий боеприпас в духе «Ланцета»: ≈115 км/ч, с катапульты (толчок за полсекунды, без огня) к цели,
     * круг на 45 блоков выше неё, пикирование с разгоном до 4 блоков/тик; без стреляющего рядом — издалека, с 500 блоков.
     * Боевая часть — 3 кг, как у «Ланцета-3»: стёкла выбивает до ~29 блоков. По точкам оператора — до 15 км пути
     * (~8 минут полёта): ближнее оружие, вдвое короче шахеда.
     */
    public static final WeaponSpec LOITER = new WeaponSpec(WeaponType.SirenKind.AIR_RAID, 20, Salvo.gaps(40, 60), Warhead.CONVENTIONAL,
            () -> AirstrikeConfig.SERVER.loiterPower, Launch.LOITER,
            new Route(null, 0, 500, 0, 32, 15_000), Tracking.CAMERA, LauncherRack.LOITER, Blast.DRONE, 3, false, 0.5f,
            new Airframe(() -> ModEntities.LOITER.get(), 1.6, 4.0, 3.0, 2.0, 45, 25, 35, 1.3, 4, 2400 * 1.6, 3.0,
                    new LaunchProfile(4, 10, 0.16, 4, -8), 0, Attack.NONE,
                    ph -> ph.onLauncher() ? Hearing.ENGINE : Hearing.LOITER_DIVE),
            null);
}
