package ua.zentix.airstrike.client;

import org.jetbrains.annotations.Nullable;
import ua.zentix.airstrike.client.render.WeaponModels;
import ua.zentix.airstrike.client.sound.EngineSound;
import ua.zentix.airstrike.strike.WeaponType;

import java.util.List;
import java.util.function.Supplier;

/**
 * Клиентская часть паспорта оружия ({@link ua.zentix.airstrike.strike.WeaponSpec}): звуковые слои, голос ускорителя
 * и разовые звуки старта, сопла и факелы, сброшенный ускоритель. Отдельно от общего паспорта: сервер клиентские
 * классы не грузит.
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
     * Сопла и звук одной сущности полёта.
     *
     * @param layers        слои звука мотора
     * @param engines       сопла маршевого двигателя (у B-2 — четыре инверсионных следа, у бомбы — срыв потока с хвоста)
     * @param boosterNozzle срез сопла ускорителя; null — ускорителя нет
     * @param boosterSmoke  размер дыма ускорителя (шахед меньше ракеты)
     * @param boosterPlume  начало факела ускорителя (у шахеда он под хвостом, чуть ближе среза)
     * @param enginePlume   начало факела маршевого двигателя (или ступени МБР, двигателя РСЗО); null — факела нет
     */
    public record ClientAirframe(List<EngineSound.Layer> layers, List<At> engines, @Nullable At boosterNozzle, float boosterSmoke,
                                 @Nullable At boosterPlume, @Nullable At enginePlume) {
        /** Сопло маршевого двигателя. */
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

    public static final ClientWeaponSpec DRONE = new ClientWeaponSpec(
            new ClientAirframe(List.of(EngineSound.Layer.DRONE_NEAR, EngineSound.Layer.DRONE_FAR, EngineSound.Layer.BOOSTER),
                    List.of(new At(0, 0.03, -1.95)), new At(0, -0.29, -1.95), 0.55f, new At(0, -0.29, -1.93), null),
            null, BoosterVoice.SOLID, LaunchCue.BOOSTER, true,
            new SpentBooster(() -> WeaponModels.Mesh.DRONE_BOOSTER, 0.1f, 0.29f, 1.22f));

    public static final ClientWeaponSpec MISSILE = new ClientWeaponSpec(
            new ClientAirframe(List.of(EngineSound.Layer.MISSILE_FRONT, EngineSound.Layer.MISSILE_REAR, EngineSound.Layer.MISSILE_DIVE,
                    EngineSound.Layer.MISSILE_FAR, EngineSound.Layer.MISSILE_WHISTLE, EngineSound.Layer.BOOSTER),
                    List.of(new At(0, 0, -3.2)), new At(0, 0, -3.45), 0.8f, new At(0, 0, -3.44), new At(0, 0, -2.88)),
            null, BoosterVoice.SOLID_LOW, LaunchCue.BOOSTER_LOW, true,
            new SpentBooster(() -> WeaponModels.Mesh.MISSILE_BOOSTER, 0.24f, 0, 3.15f));

    public static final ClientWeaponSpec BUNKER = new ClientWeaponSpec(
            new ClientAirframe(List.of(EngineSound.Layer.BOMBER_NEAR, EngineSound.Layer.BOMBER_FAR),
                    List.of(new At(3.2, 0.3, -9.5), new At(1.9, 0.3, -9.3), new At(-1.9, 0.3, -9.3), new At(-3.2, 0.3, -9.5)),
                    null, 0, null, null),
            new ClientAirframe(List.of(EngineSound.Layer.BOMB_NEAR, EngineSound.Layer.BOMB_FAR, EngineSound.Layer.BOMB_DRILL),
                    List.of(new At(0, 0, -5.2)), null, 0, null, null),
            BoosterVoice.SOLID, LaunchCue.BOOSTER, true, null);

    public static final ClientWeaponSpec NUKE = new ClientWeaponSpec(
            new ClientAirframe(List.of(EngineSound.Layer.BOOSTER), List.of(new At(0, 0, -10)), null, 0, null, new At(0, 0, -9.7)),
            null, BoosterVoice.ICBM, LaunchCue.NONE, false, null);

    /** РСЗО: рёв двигателя, пока горит; дальше снаряд летит по инерции и воет рассекаемым воздухом. */
    public static final ClientWeaponSpec ROCKET = new ClientWeaponSpec(
            new ClientAirframe(List.of(EngineSound.Layer.BOOSTER, EngineSound.Layer.ROCKET_AIR), List.of(new At(0, 0, -1.5)),
                    null, 0, null, new At(0, 0, -1.5)),
            null, BoosterVoice.ROCKET, LaunchCue.ROCKET_TUBE, true, null);

    /** Барражирующий: тот же винт, но маленький электромотор — выше и тише (см. {@code EngineSound}). */
    public static final ClientWeaponSpec LOITER = new ClientWeaponSpec(
            new ClientAirframe(List.of(EngineSound.Layer.LOITER_NEAR, EngineSound.Layer.LOITER_FAR, EngineSound.Layer.LOITER_DIVE,
                    EngineSound.Layer.BOOSTER), List.of(), null, 0, null, null),
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
