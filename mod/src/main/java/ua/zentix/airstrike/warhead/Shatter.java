package ua.zentix.airstrike.warhead;

import it.unimi.dsi.fastutil.longs.LongArrays;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import ua.zentix.airstrike.compat.DhUpdates;
import ua.zentix.airstrike.nuclear.model.BlastModel;
import ua.zentix.airstrike.nuclear.world.BlockResponse;
import ua.zentix.airstrike.registry.ModTags;
import ua.zentix.airstrike.strike.ImpactCost;
import ua.zentix.airstrike.strike.StrikeWorld;
import ua.zentix.airstrike.util.Palettes;
import ua.zentix.airstrike.util.Terrain;
import ua.zentix.airstrike.work.UnitQueue;

import java.util.Arrays;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * Ударная волна выбивает стёкла (у ракеты — и листву): работа в очереди попаданий, единица — до {@link #PORTION} блоков
 * в секции 16³, где такие блоки есть; секции идут от центра взрыва наружу, как волна. Где и что выбивается —
 * {@link Zone}: стёкла — по давлению волны заряда боевой части ({@link Wave}), листва — в коробке ({@link Box}).
 * <p>
 * Блоки читаются только из готовых чанков; секцию без таких блоков отсекает палитра ({@link Palettes#contains}), и
 * даже шар ракеты в сотни секций обходится дёшево. У чанка с неготовым соседом обновления соседей расходятся цепочкой
 * дальше любого запаса в блоках (форма панели, потерявшей связь; дверь рядом проверяет сигнал редстоуна и читает
 * соседей проводящего блока; рельсы и провод — ещё дальше) и грузили бы неготовый чанк синхронно. Поэтому там блок
 * убирается без обновлений соседей ({@link Warheads#EDGE_FLAGS}: клиентам и без форм соседей — у соседней панели
 * остаётся связь), а в {@link Warheads#EDGE} блоках от неготового чанка не убирается совсем: Sable читает соседние
 * блоки каждого изменённого. Чанки, где что-то выбито, уходят в LOD Distant Horizons ({@link DhUpdates#mark}).
 */
final class Shatter implements UnitQueue.Job {
    /** Секцию отсекла палитра или её нет в мире: не единица работы. */
    private static final int SKIPPED = -1;
    /**
     * Блоков за единицу, не больше: у стеклянной высотки в секции бывают сотни окон, а снять блок со всеми
     * обновлениями соседей — около 10 мкс, со сборкой хоста — и в разы дольше.
     */
    static final int PORTION = 32;

    /** Что и где выбивает волна. */
    sealed interface Zone permits Box, Wave {
        /** Блоки, которые волна может выбить. */
        TagKey<Block> tag();

        /** Угол границ, где волна может что-то выбить. */
        BlockPos min();

        BlockPos max();

        /** Может ли волна выбить хоть что-то в секции {@code (sx, sy, sz)}: секции дальше блоки не читаются. */
        boolean reaches(int sx, int sy, int sz);

        /** Выбивает ли волна блок {@code state} из тега в месте {@code (x, y, z)} внутри границ. */
        boolean breaks(BlockState state, int x, int y, int z);
    }

    /** Всё из тега в коробке {@code min..max}. */
    record Box(TagKey<Block> tag, BlockPos min, BlockPos max) implements Zone {
        /** Коробка вокруг {@code centre}: {@code radius} в стороны, {@code below} вниз и {@code above} вверх. */
        static Box around(Vec3 centre, int radius, int below, int above, TagKey<Block> tag) {
            BlockPos c = BlockPos.containing(centre);
            return new Box(tag, c.offset(-radius, -below, -radius), c.offset(radius, above, radius));
        }

        @Override
        public boolean reaches(int sx, int sy, int sz) {
            return true;
        }

        @Override
        public boolean breaks(BlockState state, int x, int y, int z) {
            return true;
        }
    }

    /**
     * Стёкла ({@link ModTags#SHATTERS}) от наземного взрыва заряда {@code tntKg} кг ТНТ: давление волны на расстоянии от
     * центра — по Кинни–Грэхему ({@link BlastModel#surfaceOverpressureKpa}), а ломается ли стекло — тем же порогом
     * с разбросом, что и у ядерного подрыва ({@link BlockResponse#breaksAt}: стекло — 0,8 psi ± 15 %, DESIGN-nuke §3.3,
     * у соседних окон по-разному), поэтому у края шара окна выбиты через одно, а не по линейке. Шар — до давления, ниже которого
     * не ломается ничто ({@link BlockResponse#FRAGILE_PSI} × {@link BlockResponse#JITTER_MIN}). Тень от построек волна
     * огибает (давление за домом ниже, но окна там тоже вылетают), её здесь нет.
     *
     * @param radius шар, блоков
     */
    record Wave(Vec3 centre, double tntKg, double radius) implements Zone {
        static Wave of(Vec3 centre, double tntKg) {
            double lowest = BlastModel.kpa(BlockResponse.FRAGILE_PSI * BlockResponse.JITTER_MIN);
            return new Wave(centre, tntKg, BlastModel.rangeForSurfaceOverpressure(lowest, tntKg));
        }

        @Override
        public TagKey<Block> tag() {
            return ModTags.SHATTERS;
        }

        @Override
        public BlockPos min() {
            return BlockPos.containing(centre.subtract(radius, radius, radius));
        }

        @Override
        public BlockPos max() {
            return BlockPos.containing(centre.add(radius, radius, radius));
        }

        @Override
        public boolean reaches(int sx, int sy, int sz) {
            // ближняя к центру точка секции
            double dx = Mth.clamp(centre.x, sx << 4, (sx << 4) + 16) - centre.x;
            double dy = Mth.clamp(centre.y, sy << 4, (sy << 4) + 16) - centre.y;
            double dz = Mth.clamp(centre.z, sz << 4, (sz << 4) + 16) - centre.z;
            return dx * dx + dy * dy + dz * dz <= radius * radius;
        }

        @Override
        public boolean breaks(BlockState state, int x, int y, int z) {
            double d = Math.sqrt(centre.distanceToSqr(x + 0.5, y + 0.5, z + 0.5));
            if (d > radius) return false;
            double psi = BlastModel.psi(BlastModel.surfaceOverpressureKpa(d, tntKg));
            return BlockResponse.of(state).breaksAt(psi, Mth.murmurHash3Mixer(Long.hashCode(BlockPos.asLong(x, y, z))));
        }
    }

    @Nullable
    private final BlastArea area;
    private final List<StagedExplosion> after;
    private final Vec3 centre;
    private final Zone zone;
    private final BlockPos min, max;
    private final BiConsumer<ServerLevel, Integer> first;
    /** Секции границ, до которых волна достаёт ({@link SectionPos#asLong}), — от ближних к центру к дальним. */
    private final long[] sections;
    /** Чанки, где что-то выбито: в LOD Distant Horizons. */
    private final LongOpenHashSet touched = new LongOpenHashSet();
    /** Следующая секция в {@link #sections}. */
    private int index;
    /** Секция, которую выбивают сейчас, и место в ней (y·256 + z·16 + x), с которого продолжать; −1 — взять следующую. */
    private long current;
    private int cursor = -1;
    private boolean broke;

    /**
     * @param area  район удара (держится, пока работа не кончится); null — без района и очереди ({@link #now})
     * @param after выбивать после этих взрывов (главный взрыв удара)
     * @param first звук и частицы — с первой секцией, где что-то выбито (сколько выбито в ней)
     */
    Shatter(@Nullable BlastArea area, List<StagedExplosion> after, Vec3 centre, Zone zone, BiConsumer<ServerLevel, Integer> first) {
        this.area = area;
        this.after = after;
        this.centre = centre;
        this.zone = zone;
        this.first = first;
        min = zone.min();
        max = zone.max();
        int sx0 = SectionPos.blockToSectionCoord(min.getX()), sx1 = SectionPos.blockToSectionCoord(max.getX());
        int sy0 = SectionPos.blockToSectionCoord(min.getY()), sy1 = SectionPos.blockToSectionCoord(max.getY());
        int sz0 = SectionPos.blockToSectionCoord(min.getZ()), sz1 = SectionPos.blockToSectionCoord(max.getZ());
        long[] all = new long[(sx1 - sx0 + 1) * (sy1 - sy0 + 1) * (sz1 - sz0 + 1)];
        int n = 0;
        for (int sx = sx0; sx <= sx1; sx++) {
            for (int sy = sy0; sy <= sy1; sy++) {
                for (int sz = sz0; sz <= sz1; sz++) if (zone.reaches(sx, sy, sz)) all[n++] = SectionPos.asLong(sx, sy, sz);
            }
        }
        sections = Arrays.copyOf(all, n);
        LongArrays.quickSort(sections, (a, b) -> Double.compare(distanceSqr(a), distanceSqr(b)));
    }

    /** Выбить сразу, без очереди и без района, теми же порциями (проверки). @return сколько блоков выбито */
    static int now(ServerLevel level, Vec3 centre, Zone zone) {
        Shatter job = new Shatter(null, List.of(), centre, zone, (l, n) -> {});
        int broken = 0;
        while (job.hasNext()) broken += Math.max(0, job.nextPortion(level, PORTION));
        return broken;
    }

    /** Поставить в очередь попаданий удара: после взрывов {@code after}, пока держится район {@code area}. */
    static void queue(ServerLevel level, BlastArea area, List<StagedExplosion> after, Vec3 centre, Zone zone,
                      BiConsumer<ServerLevel, Integer> first) {
        StrikeWorld.get(level).impacts().add(level, new Shatter(area.retain(), after, centre, zone, first));
    }

    private double distanceSqr(long section) {
        return centre.distanceToSqr(SectionPos.sectionToBlockCoord(SectionPos.x(section), 8),
                SectionPos.sectionToBlockCoord(SectionPos.y(section), 8), SectionPos.sectionToBlockCoord(SectionPos.z(section), 8));
    }

    boolean hasNext() {
        return cursor >= 0 || index < sections.length;
    }

    /**
     * Следующая порция: до {@code limit} выбитых блоков в текущей секции (с места, где кончилась прошлая порция) или
     * в следующей по порядку. @return сколько выбито, или {@link #SKIPPED} — секцию отсекла палитра, блоки не читались
     */
    int nextPortion(ServerLevel level, int limit) {
        TagKey<Block> tag = zone.tag();
        if (cursor < 0) {
            current = sections[index++];
            LevelChunkSection section = section(level);
            if (section == null || section.hasOnlyAir() || !section.maybeHas(st -> st.is(tag))) return SKIPPED;
            // палитра помнит и выбитые раньше, а глобальная отвечает «может быть» всегда: точно — подсчётом
            if (!Palettes.contains(section.getStates(), st -> st.is(tag))) return 0;
            cursor = 0;
        }
        // между порциями чанк мог уйти из памяти
        LevelChunkSection section = section(level);
        if (section == null) {
            cursor = -1;
            return 0;
        }
        int sx = SectionPos.x(current), sz = SectionPos.z(current);
        int bx = SectionPos.sectionToBlockCoord(sx), by = SectionPos.sectionToBlockCoord(SectionPos.y(current)), bz = SectionPos.sectionToBlockCoord(sz);
        // соседи чанка готовы — любой его блок меняется как обычно; нет — без обновлений соседей и не у края
        boolean edgesReady = Terrain.neighbourhoodReady(level, sx, sz);
        int broken = 0;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int i = cursor; i < 4096; i++) {
            int x = bx + (i & 15), y = by + (i >> 8), z = bz + (i >> 4 & 15);
            if (x < min.getX() || x > max.getX() || y < min.getY() || y > max.getY() || z < min.getZ() || z > max.getZ()) continue;
            // из уже взятой секции: setBlock меняет её же, так что следующие чтения верны
            BlockState st = section.getBlockState(i & 15, i >> 8, i >> 4 & 15);
            if (!st.is(tag) || !zone.breaks(st, x, y, z)) continue;
            m.set(x, y, z);
            if (!edgesReady && !Terrain.readyAround(level, Vec3.atCenterOf(m), Warheads.EDGE)) continue;
            level.setBlock(m, Blocks.AIR.defaultBlockState(), edgesReady ? Block.UPDATE_ALL : Warheads.EDGE_FLAGS);
            touched.add(ChunkPos.asLong(sx, sz));
            if (++broken == limit) {
                cursor = i + 1;
                return broken;
            }
        }
        cursor = -1;
        return broken;
    }

    /** Секция {@link #current} из готового чанка, или null. */
    @Nullable
    private LevelChunkSection section(ServerLevel level) {
        LevelChunk chunk = level.getChunkSource().getChunkNow(SectionPos.x(current), SectionPos.z(current));
        if (chunk == null) return null;
        int idx = chunk.getSectionIndexFromSectionY(SectionPos.y(current));
        return idx < 0 || idx >= chunk.getSectionsCount() ? null : chunk.getSection(idx);
    }

    @Override
    public boolean ready(ServerLevel level) {
        return area == null || area.ready(level);
    }

    @Override
    public boolean blocked() {
        return Warheads.pending(after);
    }

    @Override
    public int unitKind() {
        return ImpactCost.Kind.GLASS.ordinal();
    }

    @Override
    public boolean step(ServerLevel level) {
        long t0 = System.nanoTime();
        int broken = SKIPPED;
        // секции, которые отсекла палитра, — в той же единице: работа — порция секции, чьи блоки пришлось смотреть
        while (hasNext() && broken == SKIPPED) broken = nextPortion(level, PORTION);
        broken = Math.max(0, broken);
        if (broken > 0 && !broke) {
            broke = true;
            first.accept(level, broken);
        }
        long took = System.nanoTime() - t0;
        if (area != null) area.record(level, ImpactCost.Kind.GLASS, took, broken);
        else StrikeWorld.get(level).impactCost().add(ImpactCost.Kind.GLASS, took, broken);
        return hasNext();
    }

    @Override
    public void end(ServerLevel level) {
        long at = level.getGameTime() + DhUpdates.SETTLE;
        touched.forEach(c -> DhUpdates.mark(level, new ChunkPos(c), at));
        if (area != null) area.release(level);
    }

    @Override
    public String describe() {
        return "Стёкла у " + Mth.floor(centre.x) + " " + Mth.floor(centre.y) + " " + Mth.floor(centre.z);
    }
}
