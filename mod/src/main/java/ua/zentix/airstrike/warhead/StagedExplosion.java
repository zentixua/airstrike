package ua.zentix.airstrike.warhead;

import com.mojang.datafixers.util.Pair;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2BooleanOpenHashMap;
import net.minecraft.Util;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.network.protocol.game.ClientboundExplodePacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.ExplosionDamageCalculator;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.EventHooks;
import org.jetbrains.annotations.Nullable;
import ua.zentix.airstrike.compat.SubLevels;
import ua.zentix.airstrike.nuclear.world.WorkClock;
import ua.zentix.airstrike.registry.ModDamageTypes;
import ua.zentix.airstrike.registry.ModParticles;
import ua.zentix.airstrike.registry.ModSounds;
import ua.zentix.airstrike.strike.ImpactCost;
import ua.zentix.airstrike.util.Terrain;
import ua.zentix.airstrike.work.UnitQueue;
import ua.zentix.airstrike.work.WorkScheduler;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.LongSupplier;

/**
 * Ванильный взрыв по единицам работы ({@link UnitQueue}): те же шаги, что у {@code ServerLevel.explode}, только
 * порциями в разных единицах. Замер на сборке хоста (город, ракета): два взрыва силы 12–20 — 559 мс в одном тике;
 * после деления на подрыв и порции блоков одна единица «лучи и урон» всё ещё стоила до 150 мс.
 * <ol>
 *     <li>{@link Explosion} с правилами TNT, {@link EventHooks#onExplosionStart} (приваты отменяют его, как обычно),
 *     один запрос аппаратов Sable на весь охват лучей. Аппарат рядом — ванильный {@link Explosion#explode()} целиком
 *     (с уроном) в этой же единице: миксин Sable на каждом воздушном шаге луча ищет аппарат, переводит точку в плот,
 *     добавляет блоки аппарата и толкает его — это его внутренности, повторять их нельзя, и мод в этот метод не лезет. Нет аппарата — миксину Sable нечего
 *     менять, и лучи идут своим циклом (копия ванильного): здесь только событие {@code EXPLODE} и все 1352 множителя
 *     силы лучей из {@code level.random} в ванильном порядке — выборка не зависит от того, как лучи поделены.</li>
 *     <li>Лучи по {@link #RAYS_PER_UNIT} за единицу; множество выбранных копится. Внутри луча блок, в котором лежат
 *     подряд несколько шагов по 0,3 блока, читается один раз (как у Lithium {@code world.explosions}).</li>
 *     <li>Сбор сущностей и {@code ExplosionEvent.Detonate} (обработчики модов — одной единицей, со списком, по которому
 *     потом идёт урон).</li>
 *     <li>Разбор выбранного: воздух без огня отбрасывается, плоты аппаратов — раз на чанк, мир — от центра наружу по
 *     заранее посчитанному расстоянию.</li>
 *     <li>Урон и отбрасывание по {@link #ENTITIES_PER_UNIT} сущностей (копия ванильного цикла; живость, расстояние
 *     и видимость — на момент единицы); игроку пакет взрыва с его отбрасыванием уходит в той же единице.</li>
 *     <li>Блоки мира от центра наружу порциями по времени ({@link #PORTION_NANOS}) шагами {@code Explosion.finalizeExplosion}
 *     ({@code onExplosionHit}, затем огонь). Выпадение копится за весь взрыв и выпадает в конце. Блок, который сменился
 *     с момента лучей (натекла вода, поставил игрок), не трогается; сменившиеся свойства того же блока (забор потерял
 *     соседа) — не смена. Блоки аппаратов снимаются сразу при разборе: через тики плот мог уйти другому аппарату.</li>
 * </ol>
 * Между единицами мир может меняться (1–3 тика) — порции сверяют блок. Взрыв с центром в сетке плотов (на аппарате) —
 * одним ванильным {@code level.explode}: Sable переносит такой взрыв в мир своей обёрткой {@code ServerLevel.explode}.
 * <p>
 * Район держит {@link BlastArea} (свой или таймлайна), пока взрыв не кончится; единица ждёт, пока район готов, а блоки
 * в чанках, которые к её сроку не готовы, пропускает.
 */
final class StagedExplosion implements UnitQueue.Job {
    /** Порция блоков мира — по времени: блоки идут, пока единица короче этого. */
    static final long PORTION_NANOS = 5_000_000L;
    /** Блок дольше этого — строка в лог. */
    static final long SLOW_BLOCK_NANOS = 10_000_000L;
    /** Пачка блоков от центра наружу, внутри которой порядок перемешан. */
    static final int BATCH = 16;
    /** Лучей за единицу (всего 1352). */
    static final int RAYS_PER_UNIT = 128;
    /** Сущностей за единицу урона: смерть моба — лут и обработчики всех модов сборки. */
    static final int ENTITIES_PER_UNIT = 8;

    /** Направления 1352 лучей ванильного взрыва в ванильном порядке (грани куба 16³, нормированные). */
    private static final double[] RAY_X, RAY_Y, RAY_Z;

    static {
        List<double[]> dirs = new ArrayList<>(1352);
        for (int j = 0; j < 16; j++) {
            for (int k = 0; k < 16; k++) {
                for (int l = 0; l < 16; l++) {
                    if (j == 0 || j == 15 || k == 0 || k == 15 || l == 0 || l == 15) {
                        double d0 = (double) ((float) j / 15.0F * 2.0F - 1.0F);
                        double d1 = (double) ((float) k / 15.0F * 2.0F - 1.0F);
                        double d2 = (double) ((float) l / 15.0F * 2.0F - 1.0F);
                        double d3 = Math.sqrt(d0 * d0 + d1 * d1 + d2 * d2);
                        dirs.add(new double[]{d0 / d3, d1 / d3, d2 / d3});
                    }
                }
            }
        }
        RAY_X = new double[dirs.size()];
        RAY_Y = new double[dirs.size()];
        RAY_Z = new double[dirs.size()];
        for (int i = 0; i < dirs.size(); i++) {
            RAY_X[i] = dirs.get(i)[0];
            RAY_Y[i] = dirs.get(i)[1];
            RAY_Z[i] = dirs.get(i)[2];
        }
    }

    static int rays() {
        return RAY_X.length;
    }

    /** Что делает следующая единица. */
    private enum Phase { START, RAYS, DETONATE, SPLIT, DAMAGE, BLOCKS }

    /** Проверки: лучи ванильным {@code explode()} (как при аппарате рядом) — сравнить выборку своего цикла с ванильной. */
    boolean forceVanilla;
    /** Проверки: лучей за единицу. */
    int raysPerUnit = RAYS_PER_UNIT;
    /** Проверки: сид {@code level.random} перед лучами (выборка обоих путей из одних множителей). */
    @Nullable
    Long seed;
    /** Проверки: часы порций блоков; null — часы полосы попаданий ({@link WorkClock#sampler}: у считающих — 1 шаг на блок). */
    @Nullable
    LongSupplier clock;
    /** Проверки: срок порции блоков. */
    long portionNanos = PORTION_NANOS;
    /** Для проверок: порций блоков и самая большая из них. */
    int portions, largestPortion;

    private final BlastArea area;
    /** Взрывы, которые должны кончиться раньше (вторичные подрывы удара ждут главный). */
    private final List<StagedExplosion> after;
    private final Vec3 at;
    private final float power;
    private final boolean fire;
    private final boolean blocks;
    @Nullable
    private final Entity direct;
    @Nullable
    private final Entity owner;
    @Nullable
    private final ExplosionDamageCalculator calculator;

    private Phase phase = Phase.START;
    @Nullable
    private Explosion explosion;
    /** Калькулятор, которым считает сам {@link Explosion} (без своего — ванильный по умолчанию). */
    private ExplosionDamageCalculator damage = new ExplosionDamageCalculator();
    @Nullable
    private DamageSource source;
    /** Сила лучей: мощность × множитель из {@code level.random}, в ванильном порядке. */
    private float[] strength = new float[0];
    private int nextRay;
    private final Set<BlockPos> chosen = new HashSet<>();
    private List<Entity> entities = List.of();
    private int nextEntity;
    private final Set<ServerPlayer> told = new HashSet<>();
    /** Блоки мира, от центра наружу, и сами блоки на момент лучей. */
    private List<BlockPos> toBlow = List.of();
    private List<Block> blocksAtRays = List.of();
    private int next;
    /** Начатая пачка: индексы в {@link #toBlow}, перемешаны, берутся с конца. */
    private final IntArrayList batch = new IntArrayList();
    /** Позиции мира после раздела, до снимка ({@link #split}). */
    private List<BlockPos> picked = List.of();
    private boolean partitioned;
    /** Взрыв шёл ванильным {@code explode()} (аппарат рядом): урон уже сделан. */
    private boolean vanilla;
    /** Выпадение за весь взрыв (как {@code Explosion.addOrAppendStack}). */
    private final List<Pair<ItemStack, BlockPos>> drops = new ArrayList<>();
    private boolean done;

    /** Замер: время по шагам ({@link ExplosionStage}), счётчики лучей. */
    private final long[] stages = new long[ExplosionStage.values().length];
    private final RayCounts counts = new RayCounts();

    /** Счётчики лучей взрыва для итога удара. */
    static final class RayCounts {
        /** Шаги лучей (по 0,3 блока). */
        long steps;
        /** Из них в воздухе — на каждом таком шаге миксин Sable в ванильном {@code explode()} ищет аппарат. */
        long airSteps;
        /** Шаги, где блок взят из кэша луча (тот же блок, что на прошлом шаге). */
        long cached;
        /** Запросы аппаратов Sable (свои: один на взрыв). */
        int craftQueries;
        /** Взрывы, которые шли ванильным {@code explode()} целиком (аппарат рядом). */
        int vanilla;

        void add(RayCounts o) {
            steps += o.steps;
            airSteps += o.airSteps;
            cached += o.cached;
            craftQueries += o.craftQueries;
            vanilla += o.vanilla;
        }
    }

    StagedExplosion(BlastArea area, List<StagedExplosion> after, Vec3 at, float power, boolean fire, boolean blocks, @Nullable Entity direct,
                    @Nullable Entity owner, @Nullable ExplosionDamageCalculator calculator) {
        this.area = area;
        this.after = after;
        this.at = at;
        this.power = power;
        this.fire = fire;
        this.blocks = blocks;
        this.direct = direct;
        this.owner = owner;
        this.calculator = calculator;
    }

    /** Взрыв кончился (или отменён): работы удара, которые ждут его, могут идти. */
    boolean done() {
        return done;
    }

    @Override
    public boolean ready(ServerLevel level) {
        // район удара целиком (он накрывает и этот взрыв): у работ одного удара одно условие — порядок не ломается
        return area.ready(level);
    }

    /** Шёл ванильным {@code explode()}. */
    boolean vanilla() {
        return vanilla;
    }

    /** Ещё выбирает лучами или бьёт сущности (блоки не снимает). */
    boolean picking() {
        return !done && phase.ordinal() < Phase.BLOCKS.ordinal();
    }

    /** Ждёт взрывы {@code after} целиком. */
    boolean waiting() {
        return Warheads.pending(after);
    }

    @Override
    public boolean blocked() {
        return waiting() || phase == Phase.BLOCKS && area.peersPicking(this);
    }

    @Override
    public int unitKind() {
        return kind().ordinal();
    }

    private ImpactCost.Kind kind() {
        return switch (phase) {
            case START, RAYS -> ImpactCost.Kind.RAYS;
            case DETONATE, DAMAGE -> ImpactCost.Kind.HITS;
            case SPLIT, BLOCKS -> ImpactCost.Kind.BLOCKS;
        };
    }

    @Override
    public boolean step(ServerLevel level) {
        long t0 = System.nanoTime();
        ImpactCost.Kind kind = kind();
        long[] before = stages.clone();
        int count = 0;
        boolean more;
        switch (phase) {
            case START -> {
                count = 1;
                more = start(level);
            }
            case RAYS -> more = rays(level);
            case DETONATE -> more = detonate(level);
            case SPLIT -> more = split(level);
            case DAMAGE -> more = damage(level);
            default -> {
                more = portion(level);
                count = blown;
            }
        }
        area.record(level, kind, System.nanoTime() - t0, count);
        for (int i = 0; i < before.length; i++) before[i] = stages[i] - before[i];
        area.recordStages(level, before);
        if (!more) popDrops(level);
        return more;
    }

    private void time(ExplosionStage stage, long since) {
        stages[stage.ordinal()] += System.nanoTime() - since;
    }

    /** Первая единица; {@code true} — есть ещё единицы. */
    private boolean start(ServerLevel level) {
        long t = System.nanoTime();
        Level.ExplosionInteraction interaction = blocks ? Level.ExplosionInteraction.TNT : Level.ExplosionInteraction.NONE;
        if (SubLevels.inPlotGrid(level, new ChunkPos(BlockPos.containing(at)))) {
            counts.vanilla++;
            explosion = level.explode(null, ModDamageTypes.source(level, ModDamageTypes.STRIKE, direct, owner), calculator,
                    at.x, at.y, at.z, power, fire, interaction, ModParticles.NONE.get(), ModParticles.NONE.get(), ModSounds.SILENT);
            return false;
        }
        // Level.explode: правила TNT (без обломков, если выключено выпадение из взрывов TNT)
        Explosion.BlockInteraction destroy = !blocks ? Explosion.BlockInteraction.KEEP
                : level.getGameRules().getBoolean(GameRules.RULE_TNT_EXPLOSION_DROP_DECAY)
                ? Explosion.BlockInteraction.DESTROY_WITH_DECAY : Explosion.BlockInteraction.DESTROY;
        source = ModDamageTypes.source(level, ModDamageTypes.STRIKE, direct, owner);
        Explosion e = new Explosion(level, null, source, calculator,
                at.x, at.y, at.z, power, fire, destroy, ModParticles.NONE.get(), ModParticles.NONE.get(), ModSounds.SILENT);
        explosion = e;
        if (calculator != null) damage = calculator;
        boolean cancelled = EventHooks.onExplosionStart(level, e);
        time(ExplosionStage.START, t);
        if (cancelled) return false;
        // в воздухе калькулятор сопротивления не даёт, и сила луча падает только на 0,225 за шаг в 0,3 блока: луч
        // уходит до 1,3 × 1,33 ≈ 1,73 силы — аппарат ищем в охвате района взрыва (2 силы + 1)
        double reach = Warheads.reach(power);
        counts.craftQueries++;
        if (seed != null) level.random.setSeed(seed);
        if (forceVanilla || SubLevels.mayHaveCraftNear(level, at, reach)) return vanilla(level, e);
        t = System.nanoTime();
        level.gameEvent(null, GameEvent.EXPLODE, at);
        time(ExplosionStage.GAME_EVENT, t);
        strength = new float[rays()];
        for (int i = 0; i < strength.length; i++) strength[i] = power * (0.7F + level.random.nextFloat() * 0.6F);
        phase = Phase.RAYS;
        return true;
    }

    /**
     * Аппарат рядом: ванильный {@code explode()} целиком (лучи с миксином Sable, сбор сущностей, {@code Detonate}, урон
     * и отбрасывание) — одним шагом замера; блоки аппаратов — в этой же единице, снимок блоков мира — следующей. Урон здесь
     * не порциями: делить его значило бы лезть внутрь ванильного метода, а взрыв у аппарата — редкий случай.
     */
    private boolean vanilla(ServerLevel level, Explosion e) {
        counts.vanilla++;
        vanilla = true;
        long t = System.nanoTime();
        e.explode();
        time(ExplosionStage.VANILLA, t);
        for (ServerPlayer p : level.players()) {
            if (p.distanceToSqr(at.x, at.y, at.z) < 4096.0) tell(p, e.getHitPlayers().get(p));
        }
        partition(level);
        phase = Phase.SPLIT;
        return true;
    }

    /** Лучи {@code [nextRay, nextRay + raysPerUnit)} — копия ванильного цикла {@code Explosion.explode}. */
    private boolean rays(ServerLevel level) {
        long t = System.nanoTime();
        Explosion e = explosion;
        int end = Math.min(rays(), nextRay + raysPerUnit);
        for (int r = nextRay; r < end; r++) {
            float f = strength[r];
            double x = at.x, y = at.y, z = at.z;
            double dx = RAY_X[r], dy = RAY_Y[r], dz = RAY_Z[r];
            BlockPos last = null;
            BlockState state = null;
            Optional<Float> resistance = Optional.empty();
            while (f > 0.0F) {
                BlockPos pos = BlockPos.containing(x, y, z);
                if (pos.equals(last)) {
                    counts.cached++;
                } else {
                    if (!level.isInWorldBounds(pos)) break;
                    state = level.getBlockState(pos);
                    FluidState fluid = level.getFluidState(pos);
                    resistance = damage.getBlockExplosionResistance(e, level, pos, state, fluid);
                    last = pos;
                }
                counts.steps++;
                if (state.isAir()) counts.airSteps++;
                if (resistance.isPresent()) f -= (resistance.get() + 0.3F) * 0.3F;
                if (f > 0.0F && damage.shouldBlockExplode(e, level, pos, state, f)) chosen.add(pos);
                x += dx * 0.3F;
                y += dy * 0.3F;
                z += dz * 0.3F;
                f -= 0.22500001F;
            }
        }
        nextRay = end;
        time(ExplosionStage.RAYS, t);
        if (nextRay >= rays()) phase = Phase.DETONATE;
        return true;
    }

    /** Выбранное лучами — во взрыв, сбор сущностей, {@code ExplosionEvent.Detonate}. */
    private boolean detonate(ServerLevel level) {
        Explosion e = explosion;
        e.getToBlow().addAll(chosen);
        chosen.clear();
        strength = new float[0];
        long t = System.nanoTime();
        float f2 = power * 2.0F;
        int x0 = Mth.floor(at.x - f2 - 1.0), x1 = Mth.floor(at.x + f2 + 1.0);
        int y0 = Mth.floor(at.y - f2 - 1.0), y1 = Mth.floor(at.y + f2 + 1.0);
        int z0 = Mth.floor(at.z - f2 - 1.0), z1 = Mth.floor(at.z + f2 + 1.0);
        entities = new ArrayList<>(level.getEntities((Entity) null, new AABB(x0, y0, z0, x1, y1, z1)));
        time(ExplosionStage.ENTITIES, t);
        t = System.nanoTime();
        EventHooks.onExplosionDetonate(level, e, entities, f2);
        time(ExplosionStage.DETONATE, t);
        phase = Phase.SPLIT;
        return true;
    }

    /**
     * Раздел выбранного: плот аппарата — вопрос к Sable раз на чанк, его блоки (кроме воздуха без огня) снимаются
     * сразу; позиции мира ждут снимка ({@link #split}).
     */
    private void partition(ServerLevel level) {
        long t = System.nanoTime();
        Explosion e = explosion;
        List<BlockPos> plot = new ArrayList<>();
        List<BlockPos> world = new ArrayList<>();
        // без разрушений блоков и без огня порциям нечего делать
        if (e.interactsWithBlocks() || fire) {
            Long2BooleanOpenHashMap plotChunk = new Long2BooleanOpenHashMap();
            for (BlockPos p : e.getToBlow()) {
                long chunk = ChunkPos.asLong(SectionPos.blockToSectionCoord(p.getX()), SectionPos.blockToSectionCoord(p.getZ()));
                boolean inPlot = plotChunk.computeIfAbsent(chunk, c -> SubLevels.inPlotGrid(level, new ChunkPos(c)));
                if (!inPlot) world.add(p);
                else if (fire || !level.getBlockState(p).isAir()) plot.add(p);
            }
        }
        e.clearToBlow();
        picked = world;
        partitioned = true;
        time(ExplosionStage.SPLIT, t);
        // аппараты — сейчас: их плот живёт своей жизнью
        t = System.nanoTime();
        blow(level, plot);
        time(ExplosionStage.CRAFTS, t);
    }

    /**
     * Разбор выбранного (после раздела): воздух без огня отбрасывается (у силы 20 выбранных — десятки тысяч, в основном
     * воздух), мир — от центра наружу по заранее посчитанному расстоянию. Блок для сверки порции ({@link #portion}) —
     * тот, что стоит на месте в момент разбора (после всех лучей), а не в момент луча.
     */
    private boolean split(ServerLevel level) {
        if (!partitioned) partition(level);
        long t = System.nanoTime();
        List<Target> world = new ArrayList<>(picked.size());
        for (BlockPos p : picked) {
            BlockState s = level.getBlockState(p);
            if (fire || !s.isAir()) world.add(new Target(p.distToCenterSqr(at), p, s.getBlock()));
        }
        picked = List.of();
        time(ExplosionStage.SPLIT, t);
        t = System.nanoTime();
        world.sort(Comparator.comparingDouble(Target::distSqr));
        List<BlockPos> kept = new ArrayList<>(world.size());
        List<Block> seen = new ArrayList<>(world.size());
        for (Target w : world) {
            kept.add(w.pos());
            seen.add(w.block());
        }
        toBlow = kept;
        blocksAtRays = seen;
        time(ExplosionStage.SNAPSHOT, t);
        // урон уже сделал ванильный explode()
        phase = vanilla ? Phase.BLOCKS : Phase.DAMAGE;
        return phase == Phase.DAMAGE || !toBlow.isEmpty();
    }

    /** Урон и отбрасывание по {@link #ENTITIES_PER_UNIT} сущностей — копия ванильного цикла {@code Explosion.explode}. */
    private boolean damage(ServerLevel level) {
        long t = System.nanoTime();
        Explosion e = explosion;
        float f2 = power * 2.0F;
        int end = Math.min(entities.size(), nextEntity + ENTITIES_PER_UNIT);
        for (int i = nextEntity; i < end; i++) {
            Entity entity = entities.get(i);
            // между единицами сущность могла умереть или уйти в другое измерение
            if (!entity.isAlive() || entity.level() != level || entity.ignoreExplosion(e)) continue;
            double d11 = Math.sqrt(entity.distanceToSqr(at)) / (double) f2;
            if (d11 > 1.0) continue;
            double d5 = entity.getX() - at.x;
            double d7 = (entity instanceof PrimedTnt ? entity.getY() : entity.getEyeY()) - at.y;
            double d9 = entity.getZ() - at.z;
            double d12 = Math.sqrt(d5 * d5 + d7 * d7 + d9 * d9);
            if (d12 == 0.0) continue;
            d5 /= d12;
            d7 /= d12;
            d9 /= d12;
            if (damage.shouldDamageEntity(e, entity)) entity.hurt(source, damage.getEntityDamageAmount(e, entity));
            double d13 = (1.0 - d11) * (double) Explosion.getSeenPercent(at, entity) * (double) damage.getKnockbackMultiplier(entity);
            double d10 = entity instanceof LivingEntity living ? d13 * (1.0 - living.getAttributeValue(Attributes.EXPLOSION_KNOCKBACK_RESISTANCE)) : d13;
            Vec3 push = EventHooks.getExplosionKnockback(level, e, entity, new Vec3(d5 * d10, d7 * d10, d9 * d10));
            entity.setDeltaMovement(entity.getDeltaMovement().add(push));
            if (entity instanceof Player player && !player.isSpectator() && (!player.isCreative() || !player.getAbilities().flying)) {
                e.getHitPlayers().put(player, push);
                if (player instanceof ServerPlayer sp && sp.distanceToSqr(at.x, at.y, at.z) < 4096.0) tell(sp, push);
            }
            // как у ванили (источник взрыва — null): игрок помнит толчок — урон от падения после него, булава
            entity.onExplosionHit(null);
        }
        nextEntity = end;
        time(ExplosionStage.DAMAGE, t);
        if (nextEntity < entities.size()) return true;
        // ваниль шлёт пакет всем игрокам в 64 блоках, и без отбрасывания
        for (ServerPlayer p : level.players()) {
            if (!told.contains(p) && p.distanceToSqr(at.x, at.y, at.z) < 4096.0) tell(p, null);
        }
        entities = List.of();
        phase = Phase.BLOCKS;
        return !toBlow.isEmpty();
    }

    /** Ванильный пакет взрыва игроку: его отбрасывание, без блоков (блоки клиенты видят по мере снятия). */
    private void tell(ServerPlayer p, @Nullable Vec3 push) {
        Explosion e = explosion;
        told.add(p);
        p.connection.send(new ClientboundExplodePacket(at.x, at.y, at.z, power, List.of(), push,
                e.getBlockInteraction(), e.getSmallExplosionParticles(), e.getLargeExplosionParticles(), e.getExplosionSound()));
    }

    /** Сколько блоков сняла последняя порция (блок, сменившийся с лучей, не в счёт) — для «снято блоков». */
    private int blown;

    /**
     * Порция блоков мира по времени: блоки идут, пока порция короче {@link #portionNanos}, хоть один — всегда. Порядок —
     * от центра наружу пачками по {@link #BATCH}, внутри пачки перемешан (как весь список у ванили); начатая пачка
     * доходит в следующей порции. Огонь — по снятому этой порцией. Блок дольше {@link #SLOW_BLOCK_NANOS} — в лог
     * ({@link BlastArea#slowBlock}).
     */
    private boolean portion(ServerLevel level) {
        Explosion e = explosion;
        List<BlockPos> hit = new ArrayList<>();
        LongSupplier clock = this.clock != null ? this.clock : WorkScheduler.impactClock(level.getServer()).sampler();
        long t0 = clock.getAsLong(), last = t0;
        while (!batch.isEmpty() || next < toBlow.size()) {
            if (batch.isEmpty()) {
                int end = Math.min(toBlow.size(), next + BATCH);
                for (int i = next; i < end; i++) batch.add(i);
                next = end;
                Util.shuffle(batch, level.random);
            }
            int i = batch.removeInt(batch.size() - 1);
            BlockPos p = toBlow.get(i);
            if (!Terrain.ready(level, p)) continue;
            BlockState s = level.getBlockState(p);
            if (!s.is(blocksAtRays.get(i))) continue;
            if (e.interactsWithBlocks()) s.onExplosionHit(level, p, e, (stack, at) -> addOrAppend(stack, at));
            hit.add(p);
            long now = clock.getAsLong();
            if (now - last > SLOW_BLOCK_NANOS) area.slowBlock(level, s, p, now - last);
            last = now;
            if (now - t0 >= portionNanos) break;
        }
        blown = hit.size();
        portions++;
        largestPortion = Math.max(largestPortion, blown);
        if (fire) burn(level, hit);
        return !batch.isEmpty() || next < toBlow.size();
    }

    /** Позиция мира, выбранная лучами: расстояние до центра (ключ порядка) и блок в момент лучей. */
    private record Target(double distSqr, BlockPos pos, Block block) {}

    /** Шаги {@code Explosion.finalizeExplosion} на части списка: снятие блоков (выпадение — в общий список), огонь. */
    private void blow(ServerLevel level, List<BlockPos> part) {
        Explosion e = explosion;
        if (part.isEmpty() || e == null) return;
        if (e.interactsWithBlocks()) {
            Util.shuffle(part, level.random);
            for (BlockPos p : part) level.getBlockState(p).onExplosionHit(level, p, e, (stack, at) -> addOrAppend(stack, at));
        }
        if (fire) burn(level, part);
    }

    /** Огонь {@code Explosion.finalizeExplosion}: треть снятого, где воздух над твёрдым. */
    private void burn(ServerLevel level, List<BlockPos> part) {
        for (BlockPos p : part) {
            if (level.random.nextInt(3) == 0 && level.getBlockState(p).isAir() && level.getBlockState(p.below()).isSolidRender(level, p.below())) {
                level.setBlockAndUpdate(p, BaseFireBlock.getState(level, p));
            }
        }
    }

    /** {@code Explosion.addOrAppendStack}: одинаковые предметы — в одну стопку (до 16), у места первого. */
    private void addOrAppend(ItemStack stack, BlockPos pos) {
        for (int i = 0; i < drops.size(); i++) {
            Pair<ItemStack, BlockPos> pair = drops.get(i);
            ItemStack have = pair.getFirst();
            if (ItemEntity.areMergable(have, stack)) {
                drops.set(i, Pair.of(ItemEntity.merge(have, stack, 16), pair.getSecond()));
                if (stack.isEmpty()) return;
            }
        }
        drops.add(Pair.of(stack, pos));
    }

    private void popDrops(ServerLevel level) {
        for (Pair<ItemStack, BlockPos> pair : drops) {
            if (Terrain.ready(level, pair.getSecond())) Block.popResource(level, pair.getSecond(), pair.getFirst());
        }
        drops.clear();
    }

    @Override
    public void end(ServerLevel level) {
        // отменён или упал посреди работы: выпавшее уже снятыми блоками — всё равно выпадает
        popDrops(level);
        done = true;
        area.recordCounts(counts);
        area.release(level);
    }

    int slowBlocks() {
        return area.slowBlocks();
    }

    @Override
    public String describe() {
        return String.format(Locale.ROOT, "Взрыв силы %.0f у %d %d %d", power, Mth.floor(at.x), Mth.floor(at.y), Mth.floor(at.z));
    }
}
