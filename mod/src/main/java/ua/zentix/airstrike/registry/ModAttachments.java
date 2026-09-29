package ua.zentix.airstrike.registry;

import com.mojang.serialization.Codec;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;
import ua.zentix.airstrike.Airstrike;
import ua.zentix.airstrike.grid.BlackoutWorld;
import ua.zentix.airstrike.nuclear.radiation.RadiationDose;
import ua.zentix.airstrike.nuclear.world.NuclearWorld;
import ua.zentix.airstrike.nuclear.world.WorkClock;
import ua.zentix.airstrike.strike.PickHints;
import ua.zentix.airstrike.strike.StrikeWorld;

import java.util.function.Supplier;

public final class ModAttachments {
    public static final DeferredRegister<AttachmentType<?>> REGISTER = DeferredRegister.create(NeoForgeRegistries.Keys.ATTACHMENT_TYPES, Airstrike.MOD_ID);

    /** Доза и заражение игрока; смерть сбрасывает (не копируется). */
    public static final Supplier<AttachmentType<RadiationDose>> RADIATION = REGISTER.register("radiation",
            () -> AttachmentType.builder(() -> RadiationDose.NONE).serialize(RadiationDose.CODEC).build());

    /** Чанк: номер последнего ядерного подрыва, чьи повреждения к нему уже применены. */
    public static final Supplier<AttachmentType<Integer>> CHUNK_SCAR = REGISTER.register("chunk_scar",
            () -> AttachmentType.builder(() -> 0).serialize(Codec.INT).build());

    /**
     * Чанк: в нём стоят погашенные блэкаутом лампы (отметка, по которой загрузка чанка возвращает в него свет, когда
     * отключение кончилось); нет отметки — нет и погашенных ламп.
     */
    public static final Supplier<AttachmentType<Boolean>> GRID_DARK = REGISTER.register("grid_dark",
            () -> AttachmentType.builder(() -> false).build());

    /** Мир: блэкаут — каскады и очередь чанков ({@link BlackoutWorld}); не сохраняется. */
    public static final Supplier<AttachmentType<BlackoutWorld>> BLACKOUT_WORLD = REGISTER.register("blackout_world",
            () -> AttachmentType.builder(BlackoutWorld::new).build());

    /** Верхний мир: часы бюджета блэкаута — один на тик сервера ({@link ua.zentix.airstrike.grid.Blackouts#clock}); не сохраняется. */
    public static final Supplier<AttachmentType<WorkClock>> GRID_CLOCK = REGISTER.register("grid_clock",
            () -> AttachmentType.builder(ua.zentix.airstrike.grid.Blackouts::newClock).build());

    /** Мир: таймлайны взрывов ({@link StrikeWorld}); не сохраняется. */
    public static final Supplier<AttachmentType<StrikeWorld>> STRIKE_WORLD = REGISTER.register("strike_world",
            () -> AttachmentType.builder(StrikeWorld::new).build());

    /** Мир: ядерные очереди — фронт, свет, воронки, чанки, осадки у мобов ({@link NuclearWorld}); не сохраняется. */
    public static final Supplier<AttachmentType<NuclearWorld>> NUCLEAR_WORLD = REGISTER.register("nuclear_world",
            () -> AttachmentType.builder(NuclearWorld::new).build());

    /** Верхний мир: часы бюджета ядерной работы — один на тик сервера ({@link NuclearWorld#clock}); не сохраняется. */
    public static final Supplier<AttachmentType<WorkClock>> NUCLEAR_CLOCK = REGISTER.register("nuclear_clock",
            () -> AttachmentType.builder(WorkClock::new).build());

    /** Игрок: игровое время последнего пуска или отбоя с пульта (защита от дребезга); не сохраняется. */
    public static final Supplier<AttachmentType<Long>> LAST_FIRE = REGISTER.register("last_fire",
            () -> AttachmentType.builder(() -> 0L).build());

    /** Игрок: игровое время последнего перенацеливания из камеры снаряда; не сохраняется. */
    public static final Supplier<AttachmentType<Long>> LAST_RETARGET = REGISTER.register("last_retarget",
            () -> AttachmentType.builder(() -> 0L).build());

    /** Игрок: ему показан непустой список снарядов в полёте — погасить HUD, когда всё долетит; не сохраняется. */
    public static final Supplier<AttachmentType<Boolean>> FLIGHTS_SHOWN = REGISTER.register("flights_shown",
            () -> AttachmentType.builder(() -> false).build());

    /** Игрок: район, который грузится по клику на карте наведения ({@link PickHints}); не сохраняется. */
    public static final Supplier<AttachmentType<PickHints.Slot>> PICK_HINT = REGISTER.register("pick_hint",
            () -> AttachmentType.builder(PickHints.Slot::new).build());

    private ModAttachments() {}
}
