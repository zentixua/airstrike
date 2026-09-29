package ua.zentix.airstrike.grid;

import it.unimi.dsi.fastutil.longs.LongArrayList;
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

    /**
     * Проход по чанку с лимитом: сколько ламп переведено и пройден ли чанк до конца. По палитре конец не виден (она
     * помнит и переведённые состояния), поэтому проход останавливается на первой лампе сверх лимита, а не на последней
     * в лимите: чанк ровно с {@code limit} лампами кончается этим же проходом, без второго холостого.
     */
    public record Pass(int changed, boolean done) {}

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
        return apply(level, chunk, dark, Integer.MAX_VALUE).changed();
    }

    /** То же, не больше {@code limit} ламп (единица работы под бюджетом): остальные — следующим вызовом. */
    public static Pass apply(ServerLevel level, LevelChunk chunk, boolean dark, int limit) {
        LevelChunkSection[] sections = chunk.getSections();
        int x0 = chunk.getPos().getMinBlockX(), z0 = chunk.getPos().getMinBlockZ();
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        List<BlockPos> signalled = new ArrayList<>();
        int changed = 0;
        boolean done = true;
        sections:
        for (int i = 0; i < sections.length; i++) {
            LevelChunkSection section = sections[i];
            if (!needs(section, dark)) continue;
            int y0 = SectionPos.sectionToBlockCoord(chunk.getSectionYFromSectionIndex(i));
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        BlockState to = GridLights.toward(section.getBlockState(x, y, z), dark);
                        if (to == null) continue;
                        if (changed >= limit) {
                            done = false;
                            break sections;
                        }
                        if (!level.setBlock(p.set(x0 + x, y0 + y, z0 + z), to, FLAGS)) continue;
                        if (!dark && signal(to)) signalled.add(p.immutable());
                        changed++;
                    }
                }
            }
        }
        for (BlockPos at : signalled) resignal(level, at);
        return new Pass(changed, done);
    }

    /**
     * Чанк, у которого соседи не загружены (край загруженного мира: чанк в памяти, но не тикает): блоки меняются
     * прямо в палитре — без обновлений соседей и без Sable, которые прочли бы соседний чанк и загрузили его сразу.
     * Свет — {@code checkBlock} по месту (снижение и рост расходятся и туда, где соседи есть), клиентам — изменение
     * блока (тем, кому чанк выдан). Не больше {@code limit} ламп.
     * <p>
     * Лампы от сигнала, зажжённые здесь, сверить с сигналом нельзя (он читается у соседей, а запланированный тик
     * в чанке без тика пропадает): их места — в {@code signalled}, сверка — когда соседи загружены ({@link #resignal}).
     */
    public static Pass applyInPlace(ServerLevel level, LevelChunk chunk, boolean dark, int limit, LongArrayList signalled) {
        LevelChunkSection[] sections = chunk.getSections();
        int x0 = chunk.getPos().getMinBlockX(), z0 = chunk.getPos().getMinBlockZ();
        var light = level.getChunkSource().getLightEngine();
        int changed = 0;
        boolean done = true;
        sections:
        for (int i = 0; i < sections.length; i++) {
            LevelChunkSection section = sections[i];
            if (!needs(section, dark)) continue;
            int y0 = SectionPos.sectionToBlockCoord(chunk.getSectionYFromSectionIndex(i));
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        BlockState to = GridLights.toward(section.getBlockState(x, y, z), dark);
                        if (to == null) continue;
                        if (changed >= limit) {
                            done = false;
                            break sections;
                        }
                        section.setBlockState(x, y, z, to);
                        BlockPos p = new BlockPos(x0 + x, y0 + y, z0 + z);
                        light.checkBlock(p);
                        level.getChunkSource().blockChanged(p);
                        if (!dark && signal(to)) signalled.add(p.asLong());
                        changed++;
                    }
                }
            }
        }
        if (changed > 0) chunk.setUnsaved(true);
        return new Pass(changed, done);
    }

    /** Один двойник — снова лампа (тиком двойника: поршень, аппарат). */
    public static void relight(ServerLevel level, BlockPos pos, BlockState twin) {
        BlockState lit = GridLights.lit(twin);
        if (lit == null || !level.setBlock(pos, lit, FLAGS)) return;
        if (signal(lit)) resignal(level, pos);
    }

    /** Лампа, которая горит от сигнала (из красного камня, медная). */
    static boolean signal(BlockState state) {
        return state.getBlock() instanceof RedstoneLampBlock || state.getBlock() instanceof CopperBulbBlock;
    }

    /**
     * Лампа от сигнала сверяется с сигналом, как от обновления соседа (медная — и переключается по нему, как при
     * установке). Другой блок на этом месте (лампу сломали, квартал снова погас) не трогается.
     */
    static void resignal(ServerLevel level, BlockPos pos) {
        BlockState s = level.getBlockState(pos);
        if (signal(s)) s.handleNeighborChanged(level, pos, s.getBlock(), pos, false);
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
}
