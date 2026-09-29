package ua.zentix.airstrike.grid;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.CopperBulbBlock;
import net.minecraft.world.level.block.RedstoneLampBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;

import java.util.ArrayList;
import java.util.List;

/**
 * Перевод ламп чанка к состоянию сети: погасить (лампы → двойники) или зажечь (двойники → лампы). Секции без
 * нужных блоков пропускаются по палитре, не глядя в блоки.
 */
public final class ChunkLights {
    /**
     * Без обновлений соседей и их форм: у двойника та же форма, что у лампы, и соседям знать не о чем (лампа из
     * красного камня от обновления зажглась бы снова). Клиентам — да, свет пересчитывает сам мир.
     */
    static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;

    private ChunkLights() {}

    @FunctionalInterface
    interface Change {
        void accept(int x, int y, int z, BlockState to);
    }

    /** Есть ли в секции, что переводить (по палитре). */
    static boolean needs(LevelChunkSection section, boolean dark) {
        return !section.hasOnlyAir() && section.maybeHas(s -> GridLights.needs(s, dark));
    }

    /** Есть ли в чанке, что переводить (по палитрам секций). */
    static boolean needs(LevelChunk chunk, boolean dark) {
        for (LevelChunkSection section : chunk.getSections()) {
            if (needs(section, dark)) return true;
        }
        return false;
    }

    static void scan(LevelChunkSection section, boolean dark, Change change) {
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    BlockState to = GridLights.toward(section.getBlockState(x, y, z), dark);
                    if (to != null) change.accept(x, y, z, to);
                }
            }
        }
    }

    /**
     * Чанк мира: блоки меняются через мир (свет, клиенты, Sable). Соседние чанки должны быть загружены — это
     * проверяет вызывающий. Возвращает, сколько ламп переведено.
     * <p>
     * Лампы от сигнала (из красного камня, медные) после возврата света сверяются с сигналом, как от обновления
     * соседа: сигнал, пропавший или появившийся в темноте, лампа замечает, как только снова есть ток.
     */
    public static int apply(ServerLevel level, LevelChunk chunk, boolean dark) {
        return apply(level, chunk, dark, Integer.MAX_VALUE);
    }

    /** То же, не больше {@code limit} ламп (единица работы под бюджетом): остальные — следующим вызовом. */
    public static int apply(ServerLevel level, LevelChunk chunk, boolean dark, int limit) {
        LevelChunkSection[] sections = chunk.getSections();
        int x0 = chunk.getPos().getMinBlockX(), z0 = chunk.getPos().getMinBlockZ();
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        List<BlockPos> signalled = new ArrayList<>();
        int changed = 0;
        sections:
        for (int i = 0; i < sections.length; i++) {
            LevelChunkSection section = sections[i];
            if (!needs(section, dark)) continue;
            int y0 = SectionPos.sectionToBlockCoord(chunk.getSectionYFromSectionIndex(i));
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        BlockState to = GridLights.toward(section.getBlockState(x, y, z), dark);
                        if (to == null || !level.setBlock(p.set(x0 + x, y0 + y, z0 + z), to, FLAGS)) continue;
                        if (!dark && (to.getBlock() instanceof RedstoneLampBlock || to.getBlock() instanceof CopperBulbBlock)) signalled.add(p.immutable());
                        if (++changed >= limit) break sections;
                    }
                }
            }
        }
        for (BlockPos at : signalled) {
            BlockState s = level.getBlockState(at);
            s.handleNeighborChanged(level, at, s.getBlock(), at, false);
        }
        return changed;
    }

    /** В секциях есть погашенные лампы (по палитрам). */
    static boolean anyUnlit(LevelChunkSection[] sections) {
        for (LevelChunkSection section : sections) {
            if (section != null && !section.hasOnlyAir() && section.maybeHas(GridLights::isUnlit)) return true;
        }
        return false;
    }

    /** Блоки секции вне чанка (копия для сохранения): меняются прямо в палитре. Возвращает, сколько ламп переведено. */
    static int apply(PalettedContainer<BlockState> states, boolean dark) {
        if (!states.maybeHas(s -> GridLights.needs(s, dark))) return 0;
        int changed = 0;
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    BlockState to = GridLights.toward(states.get(x, y, z), dark);
                    if (to != null) {
                        states.set(x, y, z, to);
                        changed++;
                    }
                }
            }
        }
        return changed;
    }

    /** В секциях есть лампы сети — горящие или погашенные (по палитрам). */
    static boolean any(LevelChunkSection[] sections) {
        for (LevelChunkSection section : sections) {
            if (section != null && !section.hasOnlyAir() && section.maybeHas(s -> GridLights.isLit(s) || GridLights.isUnlit(s))) return true;
        }
        return false;
    }

    /** Секции копии чанка (не из мира): меняются прямо в палитре. Возвращает, сколько ламп переведено. */
    public static int apply(LevelChunkSection[] sections, boolean dark) {
        int[] changed = {0};
        for (LevelChunkSection section : sections) {
            if (section == null || !needs(section, dark)) continue;
            scan(section, dark, (x, y, z, to) -> {
                section.setBlockState(x, y, z, to, false);
                changed[0]++;
            });
        }
        return changed[0];
    }
}
