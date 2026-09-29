package ua.zentix.airstrike.strike;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jetbrains.annotations.Nullable;
import ua.zentix.airstrike.item.DesignatorItem;
import ua.zentix.airstrike.net.C2S;
import ua.zentix.airstrike.registry.ModAttachments;

import java.util.Comparator;
import java.util.UUID;

/**
 * Подсказка карты наведения: место выбрано кликом, приказа ещё нет — район начинает грузиться уже сейчас (в фоне,
 * по чанкам, {@link AreaLoader}, как район цели у снаряда), и к пуску он чаще всего готов. У игрока один такой
 * район: следующий клик заменяет его; новый район — не чаще раза в секунду (клик чаще запоминается и берётся, когда
 * можно); район живёт {@link #LIFESPAN} тиков и отпускается сам, даже если игрок вышел.
 */
public final class PickHints {
    /** Район подсказки отпускается сам через 30 с. */
    public static final int LIFESPAN = 600;
    /** Новый район — не чаще раза в секунду с игрока. */
    public static final int MIN_INTERVAL = 20;
    private static final TicketType<UUID> TYPE = TicketType.create("airstrike_pick", Comparator.<UUID>naturalOrder());

    private PickHints() {}

    /** Район подсказки игрока (несохраняемый attachment): взятый и отложенный клик. */
    public static final class Slot {
        @Nullable
        ResourceKey<Level> dimension;
        @Nullable
        ChunkPos held;
        long takenAt = Long.MIN_VALUE / 2;
        @Nullable
        ResourceKey<Level> pendingDimension;
        @Nullable
        ChunkPos pending;

        @Nullable
        public ChunkPos held() {
            return held;
        }
    }

    /**
     * Клик по карте. Те же проверки, что у приказа по карте ({@link ServerActions#groundInRange}), плюс пульт в руке
     * (карта открывается только с него) и не наблюдатель.
     */
    public static void pick(C2S.Pick p, IPayloadContext ctx) {
        if (!(ctx.player() instanceof ServerPlayer player) || player.isSpectator() || !ServerActions.mayUse(player)) return;
        if (!(player.getItemInHand(InteractionHand.MAIN_HAND).getItem() instanceof DesignatorItem)
                && !(player.getItemInHand(InteractionHand.OFF_HAND).getItem() instanceof DesignatorItem)) return;
        if (!Double.isFinite(p.x()) || !Double.isFinite(p.z()) || !ServerActions.groundInRange(player, p.x(), p.z())) return;
        pick(player.serverLevel(), player.getUUID(), player.getData(ModAttachments.PICK_HINT.get()), p.x(), p.z());
    }

    /**
     * Взять район вокруг (x, z) вместо прежнего. Тот же район (соседний чанк) — только продлить срок; новый раньше
     * {@link #MIN_INTERVAL} после прошлого — запомнить, его возьмёт {@link #onPlayerTick}.
     */
    public static void pick(ServerLevel level, UUID who, Slot slot, double x, double z) {
        ChunkPos pos = new ChunkPos(BlockPos.containing(x, 0, z));
        long now = level.getGameTime();
        boolean same = slot.held != null && level.dimension().equals(slot.dimension) && slot.held.getChessboardDistance(pos) < 2;
        if (!same && now - slot.takenAt < MIN_INTERVAL) {
            slot.pendingDimension = level.dimension();
            slot.pending = pos;
            return;
        }
        take(level, who, slot, same ? slot.held : pos);
    }

    private static void take(ServerLevel level, UUID who, Slot slot, ChunkPos pos) {
        release(level.getServer(), who, slot);
        StrikeWorld.get(level).areas().hold(level, area(pos, who), level.getGameTime() + LIFESPAN);
        slot.dimension = level.dimension();
        slot.held = pos;
        slot.takenAt = level.getGameTime();
        slot.pending = null;
        slot.pendingDimension = null;
    }

    private static void release(MinecraftServer server, UUID who, Slot slot) {
        if (slot.held == null || slot.dimension == null) return;
        ServerLevel level = server.getLevel(slot.dimension);
        if (level != null) StrikeWorld.get(level).areas().release(level, area(slot.held, who));
        slot.held = null;
        slot.dimension = null;
    }

    /** Отложенный клик — когда пройдёт {@link #MIN_INTERVAL}; и в тестах, где игрока нет. */
    public static void tick(ServerLevel level, UUID who, Slot slot) {
        if (slot.pending == null || level.getGameTime() - slot.takenAt < MIN_INTERVAL) return;
        ServerLevel at = level.getServer().getLevel(slot.pendingDimension);
        if (at != null) take(at, who, slot, slot.pending);
        else slot.pending = null;
    }

    public static void onPlayerTick(PlayerTickEvent.Post e) {
        if (!(e.getEntity() instanceof ServerPlayer player) || !player.hasData(ModAttachments.PICK_HINT.get())) return;
        tick(player.serverLevel(), player.getUUID(), player.getData(ModAttachments.PICK_HINT.get()));
    }

    private static AreaLoader.Area area(ChunkPos pos, UUID who) {
        return new AreaLoader.Area(TYPE, pos, FlightTickets.DISTANCE, who);
    }

    /** Сколько районов подсказки у игрока взято или догружается (проверки). */
    public static int areas(ServerLevel level, UUID who) {
        return StrikeWorld.get(level).areas().count(TYPE, who);
    }

    /** Тикет подсказки карты (тесты и стенд считают районы подсказок). */
    public static boolean isPickTicket(TicketType<?> type) {
        return type == TYPE;
    }
}
