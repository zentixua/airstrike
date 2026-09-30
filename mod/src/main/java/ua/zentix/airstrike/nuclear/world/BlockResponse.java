package ua.zentix.airstrike.nuclear.world;

import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.event.TagsUpdatedEvent;
import ua.zentix.airstrike.registry.ModTags;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Как блок переносит ударную волну: порог избыточного давления (psi) и что с ним происходит.
 * Пороги из DESIGN-nuke §3.3: теги {@code airstrike:nuke_*} (их можно переопределить датапаком), остальное —
 * по прочности блока. Ответ кэшируется на состояние блока.
 */
public record BlockResponse(float thresholdPsi, Kind kind) {
    public static final float NEVER = Float.MAX_VALUE;

    public enum Kind {
        /** Не поражается волной (воздух, жидкость, неразрушимое). */
        NONE,
        /** Природный грунт: волна не трогает; сверху меняется только от света и в воронке. */
        GROUND,
        /** Ломается в воздух. */
        BREAK,
        /** Листва: ломается, а в сильном световом импульсе загорается. */
        LEAVES,
        /** Бревно: ствол дерева на земле валится от эпицентра. */
        LOG
    }

    private static final BlockResponse NONE = new BlockResponse(NEVER, Kind.NONE);
    private static final BlockResponse GROUND = new BlockResponse(NEVER, Kind.GROUND);
    private static final Map<BlockState, BlockResponse> CACHE = new IdentityHashMap<>();

    public static synchronized BlockResponse of(BlockState s) {
        return CACHE.computeIfAbsent(s, BlockResponse::classify);
    }

    /** Теги могли измениться: /reload или другой мир в одиночной игре (свои датапаки). */
    public static synchronized void clearCache() {
        CACHE.clear();
    }

    public static void onTagsUpdated(TagsUpdatedEvent e) {
        if (e.getUpdateCause() == TagsUpdatedEvent.UpdateCause.SERVER_DATA_LOAD) clearCache();
    }

    /** Блок грунта в постройке над землёй (терракота, кальцит, камень; {@code RuinPlanner.ground}): как кладка. */
    public static final BlockResponse BUILT = new BlockResponse(12f, Kind.BREAK);

    public boolean breaksAt(double psi, int jitterSeed) {
        if (thresholdPsi == NEVER) return false;
        // ±15%: соседние одинаковые блоки ломаются не по линейке
        float j = 0.85f + 0.3f * ((jitterSeed * 0x9E3779B9 >>> 8) & 0xFFFF) / 65535f;
        return psi >= thresholdPsi * j;
    }

    private static BlockResponse classify(BlockState s) {
        if (s.isAir() || s.getBlock() instanceof net.minecraft.world.level.block.LiquidBlock) return NONE;
        if (s.is(ModTags.NUKE_GROUND)) return GROUND;
        if (s.is(ModTags.NUKE_FRAGILE)) return new BlockResponse(0.8f, Kind.BREAK);
        if (s.is(BlockTags.LEAVES)) return new BlockResponse(2f, Kind.LEAVES);
        if (s.is(BlockTags.LOGS)) return new BlockResponse(5f, Kind.LOG);
        if (s.is(ModTags.NUKE_LIGHT)) return new BlockResponse(4f, Kind.BREAK);
        if (s.is(ModTags.NUKE_MASONRY)) return new BlockResponse(12f, Kind.BREAK);
        Block b = s.getBlock();
        float hardness = b.defaultDestroyTime();
        if (hardness < 0) return NONE;
        if (hardness <= 0.5f) return new BlockResponse(1f, Kind.BREAK);
        if (hardness <= 3f) return new BlockResponse(5f, Kind.BREAK);
        if (hardness <= 6f) return new BlockResponse(12f, Kind.BREAK);
        if (hardness <= 9f) return new BlockResponse(25f, Kind.BREAK);
        return NONE;
    }
}
