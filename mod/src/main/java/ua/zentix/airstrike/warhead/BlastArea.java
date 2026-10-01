package ua.zentix.airstrike.warhead;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import ua.zentix.airstrike.Airstrike;
import ua.zentix.airstrike.strike.AreaLoader;
import ua.zentix.airstrike.strike.ImpactCost;
import ua.zentix.airstrike.strike.StrikeWorld;
import ua.zentix.airstrike.util.Terrain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Чанки, до которых достаёт взрыв (или весь таймлайн взрыва): ванильный тикет региона ({@link AreaLoader}: тикать
 * начинают, только когда готовы соседи) держит их загруженными целиком, а неготовые грузит в фоне. Нужен потому, что
 * снаряд, взорвавшись, отпускает свои тикеты и тикет района цели: соседние чанки сразу опускаются ниже «готового»,
 * и вторичный подрыв через несколько тиков читал бы их синхронно, ожидая загрузку прямо в тике. Тикет не сохраняется
 * в мире; ключ — свой у каждого района, соседние взрывы залпа не снимают его друг у друга. Тот же район — и район
 * осыпания воронки ({@link CraterFalls}).
 * <p>
 * Держателей может быть несколько: таймлайн взрыва и его единицы работы в очереди попаданий ({@link StagedExplosion}
 * и другие), которые при большом залпе кончаются позже таймлайна. Район отпускается с последним ({@link #retain}).
 * <p>
 * Единицы работы удара пишут сюда свой замер ({@link #record}); с последним держателем — итоговая строка в лог: сколько
 * единиц, взрывов и снятых блоков, за сколько тиков, самая долгая единица и из чего сложились лучи взрывов.
 */
final class BlastArea {
    /** Долгих блоков в логе за удар. */
    static final int SLOW_BLOCKS_LOGGED = 5;
    private static final TicketType<UUID> TYPE = TicketType.create("airstrike_blast", Comparator.<UUID>naturalOrder());

    private final Vec3 centre;
    private final double reach;
    private final ChunkPos chunk;
    /** Уровень тикета 33 − distance: полностью загружен квадрат ±distance чанков вокруг {@link #chunk}. */
    private final int distance;
    private final UUID key = UUID.randomUUID();
    /** Сколько держателей ещё не отпустили район. */
    private int holders = 1;

    /** Итог работы удара: единицы, их время, самая долгая, что сделано по видам, шаги лучей взрывов. */
    private long heldAt;
    private long lastUnitAt;
    private int units;
    private long nanos;
    private long maxUnit;
    private ImpactCost.Kind maxKind = ImpactCost.Kind.RAYS;
    private final int[] done = new int[ImpactCost.Kind.values().length];
    private final long[] rayStages = new long[ExplosionStage.values().length];
    private final StagedExplosion.RayCounts rayCounts = new StagedExplosion.RayCounts();
    /**
     * Взрывы района, ещё не кончившиеся, с тиком постановки. Взрывы одного тика (подрывы бетонобойной бомбы) выбирают
     * лучами и бьют сущности по нетронутому, а блоки снимают, когда все они это сделали ({@link #peersPicking}), —
     * иначе лучи следующего шли то по снятому, то нет, и воронка зависела бы от того, сколько успевает поток сервера.
     */
    private final List<Queued> explosions = new ArrayList<>();
    /** Долгие блоки удара ({@link #slowBlock}): в логе — первые {@link #SLOW_BLOCKS_LOGGED}. */
    private int slowBlocks;

    private record Queued(StagedExplosion explosion, long tick) {}

    private BlastArea(Vec3 centre, double reach) {
        this.centre = centre;
        this.reach = reach;
        this.chunk = new ChunkPos(Mth.floor(centre.x) >> 4, Mth.floor(centre.z) >> 4);
        int west = chunk.x - (Mth.floor(centre.x - reach) >> 4);
        int east = (Mth.floor(centre.x + reach) >> 4) - chunk.x;
        int north = chunk.z - (Mth.floor(centre.z - reach) >> 4);
        int south = (Mth.floor(centre.z + reach) >> 4) - chunk.z;
        this.distance = Math.max(Math.max(west, east), Math.max(north, south));
    }

    /** Взять район: всё в {@code reach} блоков от {@code centre} грузится и остаётся готовым до {@link #release}. */
    static BlastArea hold(ServerLevel level, Vec3 centre, double reach) {
        BlastArea area = new BlastArea(centre, reach);
        area.heldAt = level.getGameTime();
        StrikeWorld.get(level).areas().hold(level, area.area());
        CraterFalls.get(level).open(level, area.key, centre, reach);
        return area;
    }

    /** Взрыв района поставлен в очередь в тике {@code tick}. */
    void queued(StagedExplosion e, long tick) {
        explosions.removeIf(q -> q.explosion().done());
        explosions.add(new Queued(e, tick));
    }

    /**
     * Взрыв того же тика, что и {@code e}, ещё выбирает лучами или бьёт сущности: блоки {@code e} ждут. Взрыв, который
     * сам ждёт другие целиком (огненный шар и вторичные — главный), не в счёт: он и должен идти по снятому.
     */
    boolean peersPicking(StagedExplosion e) {
        explosions.removeIf(q -> q.explosion().done());
        long tick = Long.MIN_VALUE;
        for (Queued q : explosions) if (q.explosion() == e) tick = q.tick();
        for (Queued q : explosions) {
            StagedExplosion p = q.explosion();
            if (p != e && q.tick() == tick && p.picking() && !p.waiting()) return true;
        }
        return false;
    }

    /** Ещё один держатель (единица работы, которая может кончиться позже взявшего): отпустить — своим {@link #release}. */
    BlastArea retain() {
        if (holders <= 0) throw new IllegalStateException("район взрыва уже отпущен");
        holders++;
        return this;
    }

    /**
     * Единица работы удара вида {@code kind} заняла {@code took} нс и сделала {@code count} (взрывов, блоков, обломков):
     * в замер тика мира ({@link ImpactCost}) и в итог удара.
     */
    void record(ServerLevel level, ImpactCost.Kind kind, long took, int count) {
        record(level, StrikeWorld.get(level).impactCost(), kind, took, count);
    }

    void record(ServerLevel level, ImpactCost cost, ImpactCost.Kind kind, long took, int count) {
        cost.add(kind, took, count);
        units++;
        nanos += took;
        done[kind.ordinal()] += count;
        if (took > maxUnit) {
            maxUnit = took;
            maxKind = kind;
        }
        lastUnitAt = level.getGameTime();
    }

    /** Шаги взрыва за единицу ({@link ExplosionStage}, нс). */
    void recordStages(ServerLevel level, long[] stages) {
        StrikeWorld.get(level).impactCost().addRayStages(stages);
        for (int i = 0; i < stages.length; i++) rayStages[i] += stages[i];
    }

    /**
     * Блок снимался дольше {@link StagedExplosion#SLOW_BLOCK_NANOS}: строка в лог, первые {@link #SLOW_BLOCKS_LOGGED}
     * за удар — чей блок держит порцию (у хоста порция из 16 блоков шла 65 мс).
     */
    void slowBlock(ServerLevel level, BlockState state, BlockPos pos, long took) {
        if (slowBlocks++ >= SLOW_BLOCKS_LOGGED) return;
        Airstrike.LOG.warn(String.format(Locale.ROOT, "Взрыв: блок %s снимался %.1f мс у %d %d %d (%s, блок-сущность: %s)",
                BuiltInRegistries.BLOCK.getKey(state.getBlock()), took / 1e6, pos.getX(), pos.getY(), pos.getZ(),
                level.dimension().location(), state.hasBlockEntity() ? "да" : "нет"));
    }

    /** Счётчики лучей взрыва — в итог удара. */
    void recordCounts(StagedExplosion.RayCounts counts) {
        rayCounts.add(counts);
    }

    int slowBlocks() {
        return slowBlocks;
    }

    boolean ready(ServerLevel level) {
        return Terrain.readyAround(level, centre, reach);
    }

    /** Держатель отпускает район; последний — снимает тикет и закрывает осыпание. */
    void release(ServerLevel level) {
        if (holders <= 0 || --holders > 0) return;
        StrikeWorld.get(level).areas().release(level, area());
        CraterFalls.get(level).close(level, key);
        if (units > 0) {
            Airstrike.LOG.info(summary(level.dimension().location().toString()));
            StrikeWorld.get(level).impactCost().strikeDone();
        }
    }

    /** Итоговая строка удара (для {@code tools/logscan.py}). */
    String summary(String dimension) {
        StringBuilder rays = new StringBuilder();
        for (ExplosionStage st : ExplosionStage.values()) {
            if (rays.length() > 0) rays.append(", ");
            rays.append(st.label).append(' ').append(ms(rayStages[st.ordinal()]));
        }
        return String.format(Locale.ROOT,
                "Итог удара (%s) у %d %d %d: %d единиц, взрывов %d, снято блоков %d, стёкол %d, обломков %d, за %d тиков, всего %s мс, "
                        + "самая долгая единица %s мс (%s); шаги взрывов: %s мс; лучи: шагов %d, в воздухе %d, из кэша %d, "
                        + "запросов аппаратов %d, ванильных взрывов %d; блоков дольше 10 мс %d",
                dimension, Mth.floor(centre.x), Mth.floor(centre.y), Mth.floor(centre.z), units,
                done[ImpactCost.Kind.RAYS.ordinal()], done[ImpactCost.Kind.BLOCKS.ordinal()], done[ImpactCost.Kind.GLASS.ordinal()],
                done[ImpactCost.Kind.DEBRIS.ordinal()], lastUnitAt - heldAt + 1, ms(nanos), ms(maxUnit), maxKind.label(), rays,
                rayCounts.steps, rayCounts.airSteps, rayCounts.cached, rayCounts.craftQueries, rayCounts.vanilla, slowBlocks);
    }

    private static String ms(long nanos) {
        return String.format(Locale.ROOT, "%.1f", nanos / 1e6);
    }

    private AreaLoader.Area area() {
        return new AreaLoader.Area(TYPE, chunk, distance, key);
    }

    Vec3 centre() {
        return centre;
    }
}
