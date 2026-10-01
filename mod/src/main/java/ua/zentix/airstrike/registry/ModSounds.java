package ua.zentix.airstrike.registry;

import net.minecraft.core.registries.Registries;
import net.minecraft.sounds.SoundEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import ua.zentix.airstrike.Airstrike;

/** Звуки мода. Файлы и sounds.json собирает tools/build_sounds.py (записи — SOUND-CREDITS.md, остальное — синтез). */
public final class ModSounds {
    public static final DeferredRegister<SoundEvent> REGISTER = DeferredRegister.create(Registries.SOUND_EVENT, Airstrike.MOD_ID);

    // зацикленные: громкость, тон и положение каждый тик выставляет клиент (Доплер, задержка звука)
    public static final DeferredHolder<SoundEvent, SoundEvent> DRONE_ENGINE = register("drone.engine");
    public static final DeferredHolder<SoundEvent, SoundEvent> DRONE_ENGINE_FAR = register("drone.engine.far");
    public static final DeferredHolder<SoundEvent, SoundEvent> MISSILE_ENGINE = register("missile.engine");
    public static final DeferredHolder<SoundEvent, SoundEvent> MISSILE_ENGINE_REAR = register("missile.engine.rear");
    public static final DeferredHolder<SoundEvent, SoundEvent> MISSILE_ENGINE_FAR = register("missile.engine.far");
    public static final DeferredHolder<SoundEvent, SoundEvent> MISSILE_DIVE = register("missile.dive");
    public static final DeferredHolder<SoundEvent, SoundEvent> MISSILE_WHISTLE = register("missile.whistle");
    public static final DeferredHolder<SoundEvent, SoundEvent> BOMBER_ENGINE = register("bomber.engine");
    public static final DeferredHolder<SoundEvent, SoundEvent> BOMBER_ENGINE_FAR = register("bomber.engine.far");
    public static final DeferredHolder<SoundEvent, SoundEvent> BOMB_FALL = register("bomb.fall");
    public static final DeferredHolder<SoundEvent, SoundEvent> BOMB_FALL_FAR = register("bomb.fall.far");
    public static final DeferredHolder<SoundEvent, SoundEvent> BOMB_DRILL = register("bomb.drill");
    public static final DeferredHolder<SoundEvent, SoundEvent> BOOSTER_ENGINE = register("booster.engine");
    public static final DeferredHolder<SoundEvent, SoundEvent> SIREN = register("siren");

    // разовые (у взрывов по несколько вариантов в sounds.json — игра выбирает случайно)
    public static final DeferredHolder<SoundEvent, SoundEvent> LAUNCH_BOOSTER = register("launch.booster");
    public static final DeferredHolder<SoundEvent, SoundEvent> BOOSTER_SEPARATE = register("booster.separate");
    public static final DeferredHolder<SoundEvent, SoundEvent> ROCKET_LAUNCH = register("rocket.launch");
    public static final DeferredHolder<SoundEvent, SoundEvent> ROCKET_INCOMING = register("rocket.incoming");
    public static final DeferredHolder<SoundEvent, SoundEvent> ROCKET_BLAST = register("rocket.blast");
    public static final DeferredHolder<SoundEvent, SoundEvent> LOITER_ENGINE = register("loiter.engine");
    public static final DeferredHolder<SoundEvent, SoundEvent> LOITER_ENGINE_FAR = register("loiter.engine.far");
    public static final DeferredHolder<SoundEvent, SoundEvent> LOITER_DIVE = register("loiter.dive");
    public static final DeferredHolder<SoundEvent, SoundEvent> LOITER_LAUNCH = register("loiter.launch");
    public static final DeferredHolder<SoundEvent, SoundEvent> BLAST_NEAR = register("blast.near");
    public static final DeferredHolder<SoundEvent, SoundEvent> BLAST_SUB = register("blast.sub");
    public static final DeferredHolder<SoundEvent, SoundEvent> BLAST_MID = register("blast.mid");
    public static final DeferredHolder<SoundEvent, SoundEvent> BLAST_FAR = register("blast.far");
    public static final DeferredHolder<SoundEvent, SoundEvent> BLAST_TAIL = register("blast.tail");
    public static final DeferredHolder<SoundEvent, SoundEvent> DEBRIS_FALL = register("debris.fall");
    public static final DeferredHolder<SoundEvent, SoundEvent> BLAST_FIRE = register("blast.fire");
    public static final DeferredHolder<SoundEvent, SoundEvent> BOMB_CRACK = register("bomb.crack");
    public static final DeferredHolder<SoundEvent, SoundEvent> BOMB_IMPACT = register("bomb.impact");
    public static final DeferredHolder<SoundEvent, SoundEvent> BOMB_QUAKE = register("bomb.quake");
    public static final DeferredHolder<SoundEvent, SoundEvent> BOMB_DEEP = register("bomb.deep");
    public static final DeferredHolder<SoundEvent, SoundEvent> BOMB_VENT = register("bomb.vent");
    public static final DeferredHolder<SoundEvent, SoundEvent> BOMB_CAVE = register("bomb.cave");
    public static final DeferredHolder<SoundEvent, SoundEvent> DESIGNATOR_LOCK = register("designator.lock");
    /** Пустое событие: ванильный взрыв не должен звучать — свой звук с задержкой по расстоянию играет клиент. */
    public static final DeferredHolder<SoundEvent, SoundEvent> SILENT = register("silent");

    // ядерный удар: громкость и тембр по давлению у слушателя ставит клиент, по времени прихода фронта
    public static final DeferredHolder<SoundEvent, SoundEvent> NUKE_ALARM = register("nuke.alarm");
    public static final DeferredHolder<SoundEvent, SoundEvent> NUKE_LAUNCH = register("nuke.launch");
    public static final DeferredHolder<SoundEvent, SoundEvent> NUKE_CRACK = register("nuke.crack");
    public static final DeferredHolder<SoundEvent, SoundEvent> NUKE_BOOM_FAR = register("nuke.boom_far");
    public static final DeferredHolder<SoundEvent, SoundEvent> NUKE_ROAR = register("nuke.roar");
    public static final DeferredHolder<SoundEvent, SoundEvent> NUKE_WIND = register("nuke.wind");
    public static final DeferredHolder<SoundEvent, SoundEvent> NUKE_RUMBLE = register("nuke.rumble");
    public static final DeferredHolder<SoundEvent, SoundEvent> NUKE_GLASS = register("nuke.glass");
    public static final DeferredHolder<SoundEvent, SoundEvent> NUKE_TINNITUS = register("nuke.tinnitus");
    public static final DeferredHolder<SoundEvent, SoundEvent> NUKE_RAIN = register("nuke.rain");
    public static final DeferredHolder<SoundEvent, SoundEvent> GEIGER_CLICK = register("geiger.click");

    // блэкаут: выход подстанции (хлопок, дуга, затихающий гул), квартал гаснет и загорается, гул и искры подстанции
    public static final DeferredHolder<SoundEvent, SoundEvent> GRID_FAIL = register("grid.fail");
    public static final DeferredHolder<SoundEvent, SoundEvent> GRID_POWER_DOWN = register("grid.power_down");
    public static final DeferredHolder<SoundEvent, SoundEvent> GRID_POWER_UP = register("grid.power_up");
    public static final DeferredHolder<SoundEvent, SoundEvent> GRID_HUM = register("grid.hum");
    public static final DeferredHolder<SoundEvent, SoundEvent> GRID_SPARK = register("grid.spark");

    private ModSounds() {}

    private static DeferredHolder<SoundEvent, SoundEvent> register(String name) {
        return REGISTER.register(name, () -> SoundEvent.createVariableRangeEvent(Airstrike.id(name)));
    }
}
