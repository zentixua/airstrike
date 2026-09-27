package ua.zentix.airstrike.registry;

import net.minecraft.core.registries.Registries;
import net.minecraft.sounds.SoundEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import ua.zentix.airstrike.Airstrike;

/** Звуки мода. Файлы — assets/airstrike/sounds, синтез — tools/synth_mod_sounds.py. */
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
    public static final DeferredHolder<SoundEvent, SoundEvent> SIREN = register("siren");

    // разовые
    public static final DeferredHolder<SoundEvent, SoundEvent> BLAST_NEAR = register("blast.near");
    public static final DeferredHolder<SoundEvent, SoundEvent> BLAST_SUB = register("blast.sub");
    public static final DeferredHolder<SoundEvent, SoundEvent> BLAST_FAR = register("blast.far");
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
    public static final DeferredHolder<SoundEvent, SoundEvent> NUKE_REENTRY = register("nuke.reentry");
    public static final DeferredHolder<SoundEvent, SoundEvent> NUKE_CRACK = register("nuke.crack");
    public static final DeferredHolder<SoundEvent, SoundEvent> NUKE_BOOM_FAR = register("nuke.boom_far");
    public static final DeferredHolder<SoundEvent, SoundEvent> NUKE_ROAR = register("nuke.roar");
    public static final DeferredHolder<SoundEvent, SoundEvent> NUKE_WIND = register("nuke.wind");
    public static final DeferredHolder<SoundEvent, SoundEvent> NUKE_RUMBLE = register("nuke.rumble");
    public static final DeferredHolder<SoundEvent, SoundEvent> NUKE_GLASS = register("nuke.glass");
    public static final DeferredHolder<SoundEvent, SoundEvent> NUKE_TINNITUS = register("nuke.tinnitus");
    public static final DeferredHolder<SoundEvent, SoundEvent> NUKE_RAIN = register("nuke.rain");
    public static final DeferredHolder<SoundEvent, SoundEvent> GEIGER_CLICK = register("geiger.click");

    private ModSounds() {}

    private static DeferredHolder<SoundEvent, SoundEvent> register(String name) {
        return REGISTER.register(name, () -> SoundEvent.createVariableRangeEvent(Airstrike.id(name)));
    }
}
