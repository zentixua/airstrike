package ua.zentix.airstrike.nuclear.world;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import org.jetbrains.annotations.Nullable;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import ua.zentix.airstrike.nuclear.Detonation;
import ua.zentix.airstrike.util.Terrain;

/**
 * Руины одного подрыва по всем чанкам: что сломала волна в каждом чанке ({@link Blast}, по исходным блокам его окна) и
 * чьи руины уже стоят (их старые блоки — исходный мир для соседей). План чанка — функция исходных блоков вокруг него,
 * поэтому он один и тот же заранее и на месте, в каком бы порядке ни шли чанки.
 * <p>
 * Живёт, пока идёт подрыв (подготовка во время полёта, потом очередь руин); что больше никому не нужно — отпускает:
 * разлом чанка — когда планы есть у всех его соседей, старые блоки стоящих руин — когда руины стоят во всём квадрате
 * 5×5 вокруг (их читают окна соседей и соседей соседей).
 */
final class RuinContext {
    /** По чему строятся руины (у подготовки — место без номера; у подрыва — он сам). */
    final Detonation d;
    private final Long2ObjectOpenHashMap<Blast> blasts = new Long2ObjectOpenHashMap<>();
    private final Long2ObjectOpenHashMap<Applied> applied = new Long2ObjectOpenHashMap<>();
    private final LongOpenHashSet planned = new LongOpenHashSet(), stood = new LongOpenHashSet();
    /** Когда контекст в последний раз брали (игровое время) — очередь забывает давно брошенные. */
    long used;

    /**
     * Руины чанка стоят: план (старые блоки его мест; null — отпущены), счётчик изменений чанка при подмене и карта
     * высот {@code MOTION_BLOCKING} до неё (первый воздух; по ней тень светового импульса — он шёл до волны).
     */
    record Applied(@Nullable RuinPlan plan, long edits, int id, short[] motion) {}

    RuinContext(Detonation d) {
        this.d = d;
    }

    @Nullable
    Applied applied(long chunk) {
        return applied.get(chunk);
    }

    /**
     * Счётчик изменений чанка для окна плана: у чанка с руинами — на момент подмены (дальше его меняют сами руины:
     * огонь, текущая вода, брёвна), у остальных — живой.
     */
    long edits(long pos, LevelChunk chunk) {
        Applied a = applied.get(pos);
        return a != null ? a.edits : RuinPlan.edits(chunk);
    }

    /** Разлом чанка из кэша, если окно с тех пор не менялось; иначе — заново. Все чанки окна загружены. */
    Blast blast(ServerLevel level, ChunkPos pos) {
        long key = pos.toLong();
        Blast b = blasts.get(key);
        if (b != null && b.stamp().current(level, this, pos)) return b;
        b = Blast.solve(level, this, pos);
        blasts.put(key, b);
        return b;
    }

    /** План чанка построен: разломы, нужные только планам вокруг, можно отпустить. */
    void planned(ChunkPos pos) {
        planned.add(pos.toLong());
        forget(pos, 1, planned, k -> blasts.remove(k));
    }

    /** Руины чанка встали по плану. */
    void applied(LevelChunk chunk, RuinPlan plan, long edits, short[] motion) {
        ChunkPos pos = chunk.getPos();
        applied.put(pos.toLong(), new Applied(plan, edits, System.identityHashCode(chunk), motion));
        planned.add(pos.toLong());
        stood.add(pos.toLong());
        forget(pos, 1, stood, k -> blasts.remove(k));
        forget(pos, 2, stood, k -> {
            Applied a = applied.get(k);
            if (a != null && a.plan != null) applied.put(k, new Applied(null, a.edits, a.id, a.motion));
        });
    }

    /** Чанки в радиусе r от pos, у которых весь квадрат радиуса r в {@code done}, — в {@code drop}. */
    private static void forget(ChunkPos pos, int r, LongOpenHashSet done, java.util.function.LongConsumer drop) {
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                int x = pos.x + dx, z = pos.z + dz;
                boolean all = true;
                for (int ex = -r; all && ex <= r; ex++) {
                    for (int ez = -r; all && ez <= r; ez++) all = done.contains(ChunkPos.asLong(x + ex, z + ez));
                }
                if (all) drop.accept(ChunkPos.asLong(x, z));
            }
        }
    }

    /**
     * Световой импульс на месте, каким его видел мир до волны: у чанков, чьи руины уже стоят, — их карта высот до руин.
     */
    double lit(ServerLevel level, net.minecraft.core.BlockPos pos) {
        Vec3 p = Vec3.atCenterOf(pos).add(0, 0.5, 0);
        boolean seen = ThermalShadow.visible(d.burst(), p, (x, z) -> {
            Applied a = applied.get(ChunkPos.asLong(x >> 4, z >> 4));
            int now = Terrain.height(level, Heightmap.Types.MOTION_BLOCKING, x, z);
            return a == null ? now : Math.max(now, a.motion[(z & 15) << 4 | (x & 15)]);
        });
        return seen ? d.fluence(p) : 0;
    }
}
