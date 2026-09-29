package ua.zentix.airstrike.grid;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

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
    private interface Change {
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

    private static void scan(LevelChunkSection section, boolean dark, Change change) {
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
     */
    public static int apply(ServerLevel level, LevelChunk chunk, boolean dark) {
        LevelChunkSection[] sections = chunk.getSections();
        int x0 = chunk.getPos().getMinBlockX(), z0 = chunk.getPos().getMinBlockZ();
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        int[] changed = {0};
        for (int i = 0; i < sections.length; i++) {
            if (!needs(sections[i], dark)) continue;
            int y0 = SectionPos.sectionToBlockCoord(chunk.getSectionYFromSectionIndex(i));
            scan(sections[i], dark, (x, y, z, to) -> {
                if (level.setBlock(p.set(x0 + x, y0 + y, z0 + z), to, FLAGS)) changed[0]++;
            });
        }
        return changed[0];
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
