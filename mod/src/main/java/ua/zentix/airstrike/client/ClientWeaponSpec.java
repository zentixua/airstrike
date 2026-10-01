package ua.zentix.airstrike.client;

import org.jetbrains.annotations.Nullable;
import ua.zentix.airstrike.client.render.WeaponModels;
import ua.zentix.airstrike.client.sound.EngineSound;
import ua.zentix.airstrike.entity.FlightPhase;
import ua.zentix.airstrike.strike.WeaponType;

import java.util.List;
import java.util.function.Supplier;

/**
 * Клиентская часть паспорта оружия ({@link ua.zentix.airstrike.strike.WeaponSpec}): звуковые слои, голос ускорителя
 * и разовые звуки старта, сопла и факелы, сброшенный ускоритель, вид вдали ({@link FarLook}). Отдельно от общего
 * паспорта: сервер клиентские классы не грузит.
 * <p>
 * Сопла и срезы факелов — в осях снаряда (нос по +Z, центр — центр модели) и следуют за размерами моделей
 * ({@code tools/gen_models.py}): меняя модель, менять их здесь вместе с шарнирами в {@code WeaponModels} и
 * {@code noseLength} в общем паспорте.
 *
 * @param airframe   сущность, которой летит оружие
 * @param payload    вторая сущность (бомба B-2); null — её нет
 * @param booster    голос стартового ускорителя, пока он горит (слой {@code BOOSTER})
 * @param launch     разовый звук поджига у пусковой
 * @param separates  слышно отделение ускорителя (хлопок пиропатронов и лязг замков)
 * @param spent      сброшенный ускоритель; null — ускорителя нет
 */
public record ClientWeaponSpec(ClientAirframe airframe, @Nullable ClientAirframe payload, BoosterVoice booster,
                               LaunchCue launch, boolean separates, @Nullable SpentBooster spent) {

    /** Точка в осях снаряда: вправо-влево, вверх, вдоль (нос по +Z), блоков. */
    public record At(double x, double y, double z) {}

    /**
     * Модель, сопла и звук одной сущности полёта.
     *
     * @param model         модель ({@link WeaponModels}): вблизи её рисует рендерер сущности, вдали — {@code FarModels}
     * @param layers        слои звука мотора
     * @param engines       сопла маршевого двигателя (у B-2 — четыре инверсионных следа, у бомбы — срыв потока с хвоста)
     * @param boosterNozzle срез сопла ускорителя; null — ускорителя нет
     * @param boosterSmoke  размер дыма ускорителя (шахед меньше ракеты)
     * @param boosterPlume  начало факела ускорителя (у шахеда он под хвостом, чуть ближе среза)
     * @param enginePlume   начало факела маршевого двигателя (или ступени МБР, двигателя РСЗО); null — факела нет
     * @param far           как сущность выглядит вдали, где её у клиента нет
     */
    public record ClientAirframe(WeaponModels.Look model, List<EngineSound.Layer> layers, List<At> engines, @Nullable At boosterNozzle, float boosterSmoke,
                                 @Nullable At boosterPlume, @Nullable At enginePlume, FarLook far) {
        /** Сопло маршевого двигателя — только у оружия с соплами ({@link #engines} не пуст; у «Ланцета» их нет). */
        public At engine() {
            return engines.getFirst();
        }
    }

    /** Как звучит стартовый ускоритель (или двигатель), пока горит. */
    public enum BoosterVoice {
        /** Катапульта — без огня: только разовый удар и свист ({@link LaunchCue#CATAPULT}). */
        NONE,
        /** Ускоритель шахеда: поверх разового рёва старта у пусковой, уходит вместе со снарядом. */
        SOLID,
        /** Ускоритель крылатой ракеты: тот же, ниже тоном. */
        SOLID_LOW,
        /** Маленький двигатель реактивного снаряда: резкое шипение, выше тоном. */
        ROCKET,
        /** «Минитмен»: низкий рёв твердотопливной ступени, слышно за километр. */
        ICBM
    }

    /** Разовый звук поджига, когда его фронт дошёл до уха. */
    public enum LaunchCue {
        /** Нет (у МБР свой звук пуска, {@code NukeSounds}). */
        NONE,
        /** Рёв ускорителя шахеда у пусковой. */
        BOOSTER,
        /** Рёв ускорителя ракеты — ниже тоном. */
        BOOSTER_LOW,
        /** Сход реактивного снаряда с трубы: резкое «фш-ш» с треском. */
        ROCKET_TUBE,
        /** Катапульта барражирующего: удар поршня и свист направляющей. */
        CATAPULT
    }

    /**
     * Сброшенный ускоритель: сетка (поставщик: модели грузятся только в игре, паспорт читают и юнит-тесты звука) и её сдвиг.
     *
     * @param lift подъём над землёй (сетка лежит на боку)
     * @param dy   середина сетки в системе снаряда: вверх
     * @param dz   и вдоль
     */
    public record SpentBooster(Supplier<WeaponModels.Mesh> mesh, float lift, float dy, float dz) {}

    /**
     * Сущность полёта вдали, где её у клиента нет ({@code client.far.FarFlightView}): корпус — мягкая тёмная точка,
     * факел — свет, шлейф — лента по точкам пути. Вблизи то же рисуют модель, {@code PlumeRenderer} и частицы
     * {@code Exhaust}: числа сняты с них, и в каких фазах огонь и дым — тоже как там.
     *
     * @param size     наибольший размер корпуса (размах или длина модели), блоков: крупнее пикселя точка не меньше его
     * @param area     средняя площадь силуэта, м² (по Коши — четверть поверхности: корпус πDL/4, крыло — половина
     *                 площади в плане): сколько неба корпус закрывает, когда он мельче пикселя
     * @param color    цвет корпуса на свету (вдали — смешан с дымкой)
     * @param boost    пока горит стартовый ускоритель ({@link FlightPhase#boosterLit}; у МБР и РСЗО — сам двигатель)
     * @param cruise   остальной полёт
     * @param terminal в пике ({@link FlightPhase#TERMINAL}); null — как {@code cruise}
     */
    public record FarLook(double size, double area, int color, Stage boost, Stage cruise, @Nullable Stage terminal) {
        /** Огонь и дым в фазе: на пусковой до поджига и под землёй (бомба бурит) — ничего. */
        public Stage stage(FlightPhase phase) {
            if (phase == FlightPhase.READY || phase == FlightPhase.DRILL) return Stage.NONE;
            if (phase.boosterLit()) return boost;
            return phase == FlightPhase.TERMINAL && terminal != null ? terminal : cruise;
        }
    }

    /** Что видно вдали в одной фазе: свет факела и шлейф; null — нет. */
    public record Stage(@Nullable Flame flame, @Nullable FarTrail trail) {
        public static final Stage NONE = new Stage(null, null);
    }

    /**
     * Факел вдали — свет с сохранением потока ({@code client.far.Sight#light}): мельче пикселя он не пропадает, а
     * бледнеет; ярче белого — расплывается бликом. Ночью глаз привыкает к темноте, и тот же факел против неё ярче.
     *
     * @param radius     радиус круга той же площади, что факел сбоку (длина × поперечник струи вблизи), блоков
     * @param back       середина факела позади центра корпуса (срез сопла плюс половина струи), блоков
     * @param brightness яркость против белого экрана днём
     * @param color      цвет света
     */
    public record Flame(double radius, double back, double brightness, int color) {}

    /**
     * Шлейф вдали — лента через точки пути: каждая со своим возрастом расплывается, сносится ветром и тает, как клубы
     * {@code Exhaust} вблизи (размер растёт как 1 − (1 − f)³, цвет — по f^0,6, непрозрачность ровная от проявления до
     * {@code fadeFrom}, дальше линейно в ноль; f — доля жизни). Ширина — видимая ширина следа: клуб вблизи
     * {@code size(a, b)} — квадрат в 2a…2b блоков, плотная часть мягкой текстуры — около 0,7 его.
     *
     * @param step     точка пути — раз в столько тиков
     * @param life     тиков жизни точки (сколько в среднем живут клубы вблизи)
     * @param width0   видимая ширина у сопла, блоков
     * @param width1   к концу жизни
     * @param color0   цвет у сопла
     * @param color1   к концу жизни
     * @param alpha    непрозрачность ленты (клубы вблизи перекрываются — лента плотнее одного клуба)
     * @param fadeIn   тиков проявления
     * @param fadeFrom с какой доли жизни тает
     * @param wind     парусность, как {@code Fx.Spec#wind}
     */
    public record FarTrail(int step, int life, double width0, double width1, int color0, int color1, float alpha, int fadeIn,
                           float fadeFrom, float wind) {
        /** Сопротивление клубов шлейфа вблизи ({@code Fx.Spec#drag}). */
        static final double DRAG = 0.9;
        /** Скорость сноса клуба — ветер × это (установившаяся при сопротивлении {@link #DRAG}); столько же тиков он разгоняется. */
        static final double TERMINAL = DRAG / (1 - DRAG);

        /** Доля жизни на возрасте age, 0..1. */
        public double fraction(double age) {
            return Math.max(0, Math.min(1, age / life));
        }

        /** Точка уже растаяла. */
        public boolean expired(double age) {
            return age >= life;
        }

        /** Видимая ширина на возрасте age, блоков. */
        public double width(double age) {
            double k = 1 - fraction(age);
            return width0 + (width1 - width0) * (1 - k * k * k);
        }

        /** Непрозрачность на возрасте age. */
        public float opacity(double age) {
            if (age < 0 || expired(age)) return 0;
            double f = fraction(age);
            double in = fadeIn <= 0 ? 1 : Math.min(1, age / fadeIn);
            double out = f < fadeFrom ? 1 : 1 - (f - fadeFrom) / (1 - fadeFrom);
            return (float) (alpha * in * Math.max(0, out));
        }

        /** Доля пути от цвета у сопла к цвету в конце, 0..1. */
        public float shade(double age) {
            return (float) Math.pow(fraction(age), 0.6);
        }

        /** Сколько ветров ({@code Fx.WIND_X}, {@code Fx.WIND_Z}) снесло точку за age тиков (с отставанием на разгон). */
        public double drift(double age) {
            return wind * TERMINAL * Math.max(0, age - TERMINAL);
        }
    }

    /** Свет факела: середина между белым ядром (0xFFF4E0) и оранжевым краем (0xFF8A30) струи вблизи. */
    private static final int FLAME_LIGHT = 0xFFBF88;
    /** Ускоритель шахеда: струя 2,2 × 0,26 блока от среза в 1,9 позади центра. */
    private static final Flame DRONE_BOOSTER_FLAME = new Flame(0.3, 3, 15, FLAME_LIGHT);
    /** Ускоритель ракеты: 4 × 0,5 от среза в 3,4. */
    private static final Flame MISSILE_BOOSTER_FLAME = new Flame(0.56, 5.4, 20, FLAME_LIGHT);
    /**
     * Маршевый ТРД ракеты — тусклое свечение 1 × 0,28 от среза в 2,9 (ядро 0xFFD8A0, край 0xFF5A18); в пике — 2 × 0,36,
     * ярче. Вдали почти не светит: выхлоп ТРД — сотни градусов, а не пламя (ночью над городом ракету слышно, а не видно);
     * ночью глаз, привыкший к темноте, видит искру.
     */
    private static final Flame MISSILE_SUSTAINER_FLAME = new Flame(0.21, 3.4, 0.05, 0xFF995C),
            MISSILE_DIVE_FLAME = new Flame(0.34, 3.9, 0.15, 0xFFA970);
    /** Двигатель реактивного снаряда: 3 × 0,3 от среза в 1,5. */
    private static final Flame ROCKET_FLAME = new Flame(0.38, 3, 20, FLAME_LIGHT);
    /** Ступень «Минитмена»: 18 × 2,2 с алмазами от среза в 9,7. */
    private static final Flame ICBM_FLAME = new Flame(2.5, 18.7, 25, FLAME_LIGHT);

    /** Ускоритель шахеда: густой белый дым ({@code size(0,5·k, 3,5·k)}, k = 0,55), висит 13–19 с. */
    private static final FarTrail DRONE_BOOSTER_TRAIL = new FarTrail(2, 320, 0.4, 2.7, 0xF2EFEA, 0xC4C0BA, 0.85f, 2, 0.5f, 1);
    /** Ускоритель ракеты: тот же дым, k = 0,8. */
    private static final FarTrail MISSILE_BOOSTER_TRAIL = new FarTrail(2, 320, 0.55, 3.9, 0xF2EFEA, 0xC4C0BA, 0.85f, 2, 0.5f, 1);
    /** Поршневой мотор шахеда: тонкий сизый выхлоп на 2 с; в пике гуще и дольше. */
    private static final FarTrail DRONE_EXHAUST = new FarTrail(4, 40, 0.4, 1.7, 0x6A6C72, 0xB6B8BE, 0.18f, 2, 0.2f, 1),
            DRONE_DIVE_EXHAUST = new FarTrail(4, 60, 0.4, 2.2, 0x6A6C72, 0xB6B8BE, 0.3f, 2, 0.2f, 1);
    /** Горячий след ТРД ракеты: едва заметная дымка на 3 с. */
    private static final FarTrail MISSILE_HAZE = new FarTrail(4, 60, 0.3, 2, 0xB8B4AE, 0xDADAD8, 0.16f, 2, 0.25f, 1);
    /** Реактивный снаряд, пока горит двигатель: плотный серо-белый след дугой, висит 21–31 с. */
    private static final FarTrail ROCKET_TRAIL = new FarTrail(2, 520, 0.6, 3.6, 0xEDEAE4, 0xB2AEA8, 0.85f, 2, 0.55f, 1);
    /** «Минитмен»: густой белый столб ({@code size(1,8, 8)}), висит 45–60 с и сносится ветром. */
    private static final FarTrail ICBM_TRAIL = new FarTrail(4, 1050, 2.5, 11, 0xF2EFEA, 0xBDBAB6, 0.9f, 2, 0.55f, 1);
    /**
     * Инверсионные следы четырёх двигателей B-2 (сопла в ±1,9 и ±3,2 блока от оси, клубы {@code size(0,35, 2,8)}): вдали
     * они сливаются в одну полосу — от крайних сопел до расплывшихся следов, реже одного следа.
     */
    private static final FarTrail CONTRAILS = new FarTrail(4, 500, 4, 11, 0xFFFFFF, 0xE6EAF0, 0.5f, 8, 0.4f, 0.6f);
    /** Срыв потока с хвоста падающей бомбы: на секунду. */
    private static final FarTrail BOMB_WAKE = new FarTrail(2, 24, 0.3, 1.3, 0xDADCE0, 0xF2F2F2, 0.3f, 1, 0.2f, 1);

    /** Шахед: 3,6 м, размах 2,6; дельта-крыло ~4,7 м² и корпус Ø 0,45. */
    private static final FarLook DRONE_FAR = new FarLook(3.6, 3.6, 0x3C3E42, new Stage(DRONE_BOOSTER_FLAME, DRONE_BOOSTER_TRAIL),
            new Stage(null, DRONE_EXHAUST), new Stage(null, DRONE_DIVE_EXHAUST));
    /** «Томагавк»: 5,9 м, с ускорителем 6,2, Ø 0,52, размах 2,7. */
    private static final FarLook MISSILE_FAR = new FarLook(6.2, 3.1, 0x6E7276, new Stage(MISSILE_BOOSTER_FLAME, MISSILE_BOOSTER_TRAIL),
            new Stage(MISSILE_SUSTAINER_FLAME, MISSILE_HAZE), new Stage(MISSILE_DIVE_FLAME, MISSILE_HAZE));
    /** B-2: размах 52 м, крыло 478 м² в плане. */
    private static final FarLook BOMBER_FAR = new FarLook(52, 250, 0x2E3034, Stage.NONE, new Stage(null, CONTRAILS), null);
    /** GBU-57: 6,2 м, Ø 0,8. */
    private static final FarLook BOMB_FAR = new FarLook(6.2, 3.9, 0x55595E, Stage.NONE, new Stage(null, BOMB_WAKE), null);
    /** Ступень «Минитмена» горит весь полёт. */
    private static final Stage ICBM_STAGE = new Stage(ICBM_FLAME, ICBM_TRAIL);
    /** «Минитмен III»: 18,3 м, Ø 1,7. */
    private static final FarLook ICBM_FAR = new FarLook(18.3, 24, 0xD8D6D0, ICBM_STAGE, ICBM_STAGE, null);
    /** Снаряд «Града»: 2,9 м, Ø 0,12; после выгорания — без огня и следа. */
    private static final FarLook ROCKET_FAR = new FarLook(2.9, 0.28, 0x4E5446, new Stage(ROCKET_FLAME, ROCKET_TRAIL), Stage.NONE, null);
    /** «Ланцет» (модель ×1,5): 2,5 м, Ø 0,45, два креста крыльев; катапульта и электромотор — без огня и дыма. */
    private static final FarLook LOITER_FAR = new FarLook(2.5, 1.4, 0x6A7066, Stage.NONE, Stage.NONE, null);

    public static final ClientWeaponSpec DRONE = new ClientWeaponSpec(
            new ClientAirframe(WeaponModels::drone, List.of(EngineSound.Layer.DRONE_NEAR, EngineSound.Layer.DRONE_FAR, EngineSound.Layer.BOOSTER),
                    List.of(new At(0, 0.03, -1.95)), new At(0, -0.29, -1.95), 0.55f, new At(0, -0.29, -1.93), null, DRONE_FAR),
            null, BoosterVoice.SOLID, LaunchCue.BOOSTER, true,
            new SpentBooster(() -> WeaponModels.Mesh.DRONE_BOOSTER, 0.1f, 0.29f, 1.22f));

    public static final ClientWeaponSpec MISSILE = new ClientWeaponSpec(
            new ClientAirframe(WeaponModels::missile, List.of(EngineSound.Layer.MISSILE_FRONT, EngineSound.Layer.MISSILE_REAR, EngineSound.Layer.MISSILE_DIVE,
                    EngineSound.Layer.MISSILE_FAR, EngineSound.Layer.MISSILE_WHISTLE, EngineSound.Layer.BOOSTER),
                    List.of(new At(0, 0, -3.2)), new At(0, 0, -3.45), 0.8f, new At(0, 0, -3.44), new At(0, 0, -2.88), MISSILE_FAR),
            null, BoosterVoice.SOLID_LOW, LaunchCue.BOOSTER_LOW, true,
            new SpentBooster(() -> WeaponModels.Mesh.MISSILE_BOOSTER, 0.24f, 0, 3.15f));

    public static final ClientWeaponSpec BUNKER = new ClientWeaponSpec(
            new ClientAirframe(WeaponModels::bomber, List.of(EngineSound.Layer.BOMBER_NEAR, EngineSound.Layer.BOMBER_FAR),
                    List.of(new At(3.2, 0.3, -9.5), new At(1.9, 0.3, -9.3), new At(-1.9, 0.3, -9.3), new At(-3.2, 0.3, -9.5)),
                    null, 0, null, null, BOMBER_FAR),
            new ClientAirframe(WeaponModels::bomb, List.of(EngineSound.Layer.BOMB_NEAR, EngineSound.Layer.BOMB_FAR, EngineSound.Layer.BOMB_DRILL),
                    List.of(new At(0, 0, -5.2)), null, 0, null, null, BOMB_FAR),
            BoosterVoice.SOLID, LaunchCue.BOOSTER, true, null);

    public static final ClientWeaponSpec NUKE = new ClientWeaponSpec(
            new ClientAirframe(WeaponModels::icbm, List.of(EngineSound.Layer.BOOSTER), List.of(new At(0, 0, -10)), null, 0, null, new At(0, 0, -9.7), ICBM_FAR),
            null, BoosterVoice.ICBM, LaunchCue.NONE, false, null);

    /** РСЗО: рёв двигателя, пока горит; дальше снаряд летит по инерции и воет рассекаемым воздухом. */
    public static final ClientWeaponSpec ROCKET = new ClientWeaponSpec(
            new ClientAirframe(WeaponModels::rocket, List.of(EngineSound.Layer.BOOSTER, EngineSound.Layer.ROCKET_AIR), List.of(new At(0, 0, -1.5)),
                    null, 0, null, new At(0, 0, -1.5), ROCKET_FAR),
            null, BoosterVoice.ROCKET, LaunchCue.ROCKET_TUBE, true, null);

    /** Барражирующий: тот же винт, но маленький электромотор — выше и тише (см. {@code EngineSound}). */
    public static final ClientWeaponSpec LOITER = new ClientWeaponSpec(
            new ClientAirframe(WeaponModels::loiter, List.of(EngineSound.Layer.LOITER_NEAR, EngineSound.Layer.LOITER_FAR, EngineSound.Layer.LOITER_DIVE,
                    EngineSound.Layer.BOOSTER), List.of(), null, 0, null, null, LOITER_FAR),
            null, BoosterVoice.NONE, LaunchCue.CATAPULT, false, null);

    /** Клиентский паспорт оружия. */
    public static ClientWeaponSpec of(WeaponType weapon) {
        return switch (weapon) {
            case DRONE -> DRONE;
            case MISSILE -> MISSILE;
            case BUNKER -> BUNKER;
            case NUKE -> NUKE;
            case ROCKET -> ROCKET;
            case LOITER -> LOITER;
        };
    }

    /** Сущность полёта: носитель или вторая (бомба). */
    public ClientAirframe airframe(boolean payload) {
        return payload && this.payload != null ? this.payload : airframe;
    }
}
