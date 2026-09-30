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

import ua.zentix.airstrike.Airstrike;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Перевод ламп чанка к состоянию сети: погасить (лампы → двойники) или зажечь (двойники → лампы). Секции без
 * нужных блоков пропускаются по палитре, а глобальная палитра — по точному подсчёту ({@link #contains}).
 */
public final class ChunkLights {
    /**
     * Без обновлений соседей и их форм: у двойника та же форма, что у лампы, и соседям знать не о чем (лампа из
     * красного камня от обновления зажглась бы снова). Клиентам — да, свет пересчитывает сам мир.
     */
    static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;

    private static boolean revertLogged;

    private ChunkLights() {}

    /**
     * Проход по чанку с лимитом: сколько ламп переведено, пройден ли чанк до конца и откуда продолжать ({@code next}:
     * номер секции × 4096 + номер блока в ней — место первой лампы сверх лимита). По палитре конец не виден (она помнит
     * и переведённые состояния), поэтому проход останавливается на первой лампе сверх лимита, а не на последней
     * в лимите: чанк ровно с {@code limit} лампами кончается этим же проходом, без второго холостого. Следующий проход
     * начинается с {@code next}, а не с начала чанка: иначе здание в тысячи ламп проходилось бы заново десятки раз.
     */
    public record Pass(int changed, boolean done, int next, int reverted) {
        public static final Pass NONE = new Pass(0, true, 0, 0);
    }

    @FunctionalInterface
    interface Change {
        void accept(int x, int y, int z, BlockState to);
    }

    /** Есть ли в секции, что переводить ({@link #contains}). */
    static boolean needs(LevelChunkSection section, boolean dark) {
        return !section.hasOnlyAir() && contains(section.getStates(), s -> GridLights.needs(s, dark));
    }

    /**
     * Есть ли в блоках {@code states} состояние по {@code filter}. Сперва отвечает палитра ({@code maybeHas}): у малых
     * палитр «нет» точно. Глобальная палитра (больше 256 разных состояний в секции — обычное дело в детальном городе)
     * отвечает «может быть» всегда ({@code GlobalPalette.maybeHas}), а малая помнит и ушедшие состояния, — тогда
     * точный подсчёт по блокам, как у ванили ({@code LevelChunkSection.recalcBlockCounts}). Без него каждая загрузка
     * и сохранение городского чанка проходили все блоки таких секций в потоке сервера и ставили чанк в очередь
     * блэкаута, хотя ни ламп сети, ни отключений в нём нет.
     */
    public static boolean contains(PalettedContainer<BlockState> states, Predicate<BlockState> filter) {
        if (!states.maybeHas(filter)) return false;
        boolean[] found = {false};
        states.count((state, n) -> found[0] |= filter.test(state));
        return found[0];
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
        return apply(level, chunk, dark, limit, 0);
    }

    /** То же с места {@code from} ({@link Pass#next} прошлого прохода). */
    public static Pass apply(ServerLevel level, LevelChunk chunk, boolean dark, int limit, int from) {
        LevelChunkSection[] sections = chunk.getSections();
        int x0 = chunk.getPos().getMinBlockX(), z0 = chunk.getPos().getMinBlockZ();
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        List<BlockPos> signalled = new ArrayList<>();
        int changed = 0, next = 0, reverted = 0;
        boolean done = true;
        sections:
        for (int i = from >> 12; i < sections.length; i++) {
            LevelChunkSection section = sections[i];
            if (!needs(section, dark)) continue;
            int y0 = SectionPos.sectionToBlockCoord(chunk.getSectionYFromSectionIndex(i));
            for (int b = i == from >> 12 ? from & 4095 : 0; b < 4096; b++) {
                int x = b & 15, z = b >> 4 & 15, y = b >> 8;
                BlockState to = GridLights.toward(section.getBlockState(x, y, z), dark);
                if (to == null) continue;
                if (changed >= limit) {
                    done = false;
                    next = i << 12 | b;
                    break sections;
                }
                if (!level.setBlock(p.set(x0 + x, y0 + y, z0 + z), to, FLAGS)) continue;
                changed++;
                BlockState now = section.getBlockState(x, y, z);
                if (now != to) {
                    // другой мод вернул или заменил блок: место пройдено (дальше — с места продолжения), не зацикливаемся
                    reverted++;
                    if (!revertLogged) {
                        revertLogged = true;
                        Airstrike.LOG.warn("Блэкаут: в {} поставлен {}, а стоит {} — блок меняет другой мод; такие лампы пропускаются", p, to, now);
                    }
                    continue;
                }
                if (!dark && signal(to)) signalled.add(p.immutable());
            }
        }
        for (BlockPos at : signalled) resignal(level, at);
        return new Pass(changed, done, next, reverted);
    }

    /**
     * Блоки меняются прямо в палитре секции: для мира лампа и двойник — один блок, кроме света
     * ({@link GridLights#inPlace}), и {@code setBlock} с обновлениями соседей, картами высот и Sable (≈10 мкс на лампу
     * со сборкой хоста) не нужен; а на краю загруженного мира (чанк в памяти, соседи не загружены) он и вреден — Sable
     * и соседи прочли бы соседний чанк и загрузили его сразу. Свет — {@code checkBlock} по месту (снижение и рост
     * расходятся и в соседние чанки), клиентам — изменение блока (тем, кому чанк выдан). Не больше {@code limit} ламп.
     * <p>
     * Лампы от сигнала, зажжённые здесь, сверяются с сигналом отдельно ({@link #resignal}): их места — в
     * {@code signalled}. Начинает с места {@code from} ({@link Pass#next} прошлого прохода). Пару, которую в палитре
     * менять нельзя (у ламп сети таких нет — GameTest), при загруженных соседях ({@code neighbours}) переводит мир,
     * без них — пропускает до загрузки соседей.
     */
    public static Pass applyInPlace(ServerLevel level, LevelChunk chunk, boolean dark, int limit, int from,
                                    boolean neighbours, LongArrayList signalled) {
        LevelChunkSection[] sections = chunk.getSections();
        int x0 = chunk.getPos().getMinBlockX(), z0 = chunk.getPos().getMinBlockZ();
        var light = level.getChunkSource().getLightEngine();
        int changed = 0, next = 0;
        boolean done = true;
        sections:
        for (int i = from >> 12; i < sections.length; i++) {
            LevelChunkSection section = sections[i];
            if (!needs(section, dark)) continue;
            int y0 = SectionPos.sectionToBlockCoord(chunk.getSectionYFromSectionIndex(i));
            for (int b = i == from >> 12 ? from & 4095 : 0; b < 4096; b++) {
                int x = b & 15, z = b >> 4 & 15, y = b >> 8;
                BlockState to = GridLights.toward(section.getBlockState(x, y, z), dark);
                if (to == null) continue;
                if (changed >= limit) {
                    done = false;
                    next = i << 12 | b;
                    break sections;
                }
                BlockPos p = new BlockPos(x0 + x, y0 + y, z0 + z);
                if (GridLights.inPlace(to)) {
                    section.setBlockState(x, y, z, to);
                    light.checkBlock(p);
                    level.getChunkSource().blockChanged(p);
                } else if (!neighbours || !level.setBlock(p, to, FLAGS)) {
                    // на краю такая пара ждёт следующего повода (загрузка, каскад); у ламп сети таких пар нет (GameTest)
                    continue;
                }
                if (!dark && signal(to)) signalled.add(p.asLong());
                changed++;
            }
        }
        if (changed > 0) chunk.setUnsaved(true);
        return new Pass(changed, done, next, 0);
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
            if (section != null && !section.hasOnlyAir() && contains(section.getStates(), GridLights::isUnlit)) return true;
        }
        return false;
    }

    /** Блоки секции вне чанка (копия для сохранения): меняются прямо в палитре. Возвращает, сколько ламп переведено. */
    static int apply(PalettedContainer<BlockState> states, boolean dark) {
        if (!contains(states, s -> GridLights.needs(s, dark))) return 0;
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
