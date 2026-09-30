package ua.zentix.airstrike.warhead;

import com.mojang.datafixers.util.Pair;
import net.minecraft.Util;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundExplodePacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.ExplosionDamageCalculator;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.EventHooks;
import org.jetbrains.annotations.Nullable;
import ua.zentix.airstrike.compat.SubLevels;
import ua.zentix.airstrike.registry.ModDamageTypes;
import ua.zentix.airstrike.registry.ModParticles;
import ua.zentix.airstrike.registry.ModSounds;
import ua.zentix.airstrike.strike.ImpactCost;
import ua.zentix.airstrike.util.Terrain;
import ua.zentix.airstrike.work.UnitQueue;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Ванильный взрыв по единицам работы ({@link UnitQueue}): те же шаги, что у {@code ServerLevel.explode}, только снятие
 * блоков мира — порциями по {@link #PORTION} в следующих единицах. Замер на сборке хоста (город, ракета): два взрыва силы
 * 12–20 — 559 мс в одном тике; стёкла и обломки рядом — десятки мс.
 * <ol>
 *     <li>Первая единица: {@link Explosion} с правилами TNT, {@link EventHooks#onExplosionStart} (приваты отменяют его, как
 *     обычно), {@link Explosion#explode()} — лучи по блокам (Sable добавляет к ним блоки аппаратов своим миксином на этот
 *     метод), {@code ExplosionEvent.Detonate}, урон и отбрасывание сущностей; игрокам рядом — ванильный пакет взрыва
 *     с их отбрасыванием и без блоков (блоки клиенты видят по мере снятия, от центра наружу). Блоки аппаратов (в сетке
 *     плотов Sable) снимаются здесь же: через тики аппарат мог уже расколоться, а его плот — уйти другому аппарату.</li>
 *     <li>Дальше: блоки мира от центра наружу, порция за единицу — шагами {@code Explosion.finalizeExplosion}
 *     ({@code onExplosionHit} каждого блока, затем огонь). Выпадение копится за весь взрыв и выпадает в конце, как
 *     у ванили одним списком. Блок, который сменился с момента лучей (натекла вода, поставил игрок), не трогается.</li>
 * </ol>
 * Взрыв с центром в сетке плотов (на аппарате) — одним ванильным {@code level.explode}: Sable переносит такой взрыв
 * в мир своей обёрткой {@code ServerLevel.explode}, которой здесь нет.
 * <p>
 * Район держит {@link BlastArea} (свой или таймлайна), пока взрыв не кончится; порция ждёт, пока район готов, а блоки
 * в чанках, которые к её сроку не готовы, пропускает.
 */
final class StagedExplosion implements UnitQueue.Job {
    /** Блоков за единицу. */
    static final int PORTION = 16;

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

    @Nullable
    private Explosion explosion;
    /** Блоки мира, от центра наружу, и их состояния на момент лучей. */
    private List<BlockPos> toBlow = List.of();
    private List<BlockState> states = List.of();
    private int next;
    /** Выпадение за весь взрыв (как {@code Explosion.addOrAppendStack}). */
    private final List<Pair<ItemStack, BlockPos>> drops = new ArrayList<>();
    private boolean done;

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

    @Override
    public boolean blocked() {
        return Warheads.pending(after);
    }

    @Override
    public int unitKind() {
        return (explosion == null ? ImpactCost.Kind.RAYS : ImpactCost.Kind.BLOCKS).ordinal();
    }

    @Override
    public boolean step(ServerLevel level) {
        long t0 = System.nanoTime();
        if (explosion == null) {
            long[] stages = new long[ExplosionTimer.stages()];
            boolean more = start(level, stages);
            area.record(level, ImpactCost.Kind.RAYS, System.nanoTime() - t0, 1);
            area.recordRays(level, stages);
            if (!more) popDrops(level);
            return more;
        }
        int end = Math.min(toBlow.size(), next + PORTION);
        List<BlockPos> portion = new ArrayList<>(end - next);
        for (int i = next; i < end; i++) {
            BlockPos p = toBlow.get(i);
            if (Terrain.ready(level, p) && level.getBlockState(p) == states.get(i)) portion.add(p);
        }
        next = end;
        blow(level, portion);
        boolean more = next < toBlow.size();
        if (!more) popDrops(level);
        area.record(level, ImpactCost.Kind.BLOCKS, System.nanoTime() - t0, portion.size());
        return more;
    }

    /** Первая единица; {@code true} — остались блоки мира. */
    private boolean start(ServerLevel level, long[] stages) {
        Level.ExplosionInteraction interaction = blocks ? Level.ExplosionInteraction.TNT : Level.ExplosionInteraction.NONE;
        if (SubLevels.inPlotGrid(level, new ChunkPos(BlockPos.containing(at)))) {
            explosion = level.explode(null, ModDamageTypes.source(level, ModDamageTypes.STRIKE, direct, owner), calculator,
                    at.x, at.y, at.z, power, fire, interaction, ModParticles.NONE.get(), ModParticles.NONE.get(), ModSounds.SILENT);
            return false;
        }
        // Level.explode: правила TNT (без обломков, если выключено выпадение из взрывов TNT)
        Explosion.BlockInteraction destroy = !blocks ? Explosion.BlockInteraction.KEEP
                : level.getGameRules().getBoolean(GameRules.RULE_TNT_EXPLOSION_DROP_DECAY)
                ? Explosion.BlockInteraction.DESTROY_WITH_DECAY : Explosion.BlockInteraction.DESTROY;
        Explosion e = new Explosion(level, null, ModDamageTypes.source(level, ModDamageTypes.STRIKE, direct, owner), calculator,
                at.x, at.y, at.z, power, fire, destroy, ModParticles.NONE.get(), ModParticles.NONE.get(), ModSounds.SILENT);
        explosion = e;
        if (EventHooks.onExplosionStart(level, e)) return false;
        ExplosionTimer.begin(stages);
        try {
            e.explode();
        } finally {
            ExplosionTimer.end();
        }
        List<BlockPos> world = new ArrayList<>(), plot = new ArrayList<>();
        // без разрушений блоков и без огня порциям нечего делать
        if (e.interactsWithBlocks() || fire) {
            for (BlockPos p : e.getToBlow()) (SubLevels.inPlotGrid(level, new ChunkPos(p)) ? plot : world).add(p);
        }
        e.clearToBlow();
        for (ServerPlayer p : level.players()) {
            if (p.distanceToSqr(at.x, at.y, at.z) < 4096.0) {
                p.connection.send(new ClientboundExplodePacket(at.x, at.y, at.z, power, List.of(), e.getHitPlayers().get(p),
                        e.getBlockInteraction(), e.getSmallExplosionParticles(), e.getLargeExplosionParticles(), e.getExplosionSound()));
            }
        }
        // аппараты — сейчас: их плот живёт своей жизнью
        blow(level, plot);
        // в списке лучей и воздух (по нему ставится огонь): без огня воздуху порция ничего не сделает — не носить его
        List<BlockState> seen = new ArrayList<>(world.size());
        List<BlockPos> kept = new ArrayList<>(world.size());
        world.sort(Comparator.comparingDouble(p -> p.distToCenterSqr(at)));
        for (BlockPos p : world) {
            BlockState s = level.getBlockState(p);
            if (!fire && s.isAir()) continue;
            kept.add(p);
            seen.add(s);
        }
        toBlow = kept;
        states = seen;
        return !toBlow.isEmpty();
    }

    /** Шаги {@code Explosion.finalizeExplosion} на части списка: снятие блоков (выпадение — в общий список), огонь. */
    private void blow(ServerLevel level, List<BlockPos> part) {
        Explosion e = explosion;
        if (part.isEmpty() || e == null) return;
        if (e.interactsWithBlocks()) {
            Util.shuffle(part, level.random);
            for (BlockPos p : part) level.getBlockState(p).onExplosionHit(level, p, e, (stack, at) -> addOrAppend(stack, at));
        }
        if (fire) {
            for (BlockPos p : part) {
                if (level.random.nextInt(3) == 0 && level.getBlockState(p).isAir() && level.getBlockState(p.below()).isSolidRender(level, p.below())) {
                    level.setBlockAndUpdate(p, BaseFireBlock.getState(level, p));
                }
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
        area.release(level);
    }

    @Override
    public String describe() {
        return String.format(Locale.ROOT, "Взрыв силы %.0f у %d %d %d", power, Mth.floor(at.x), Mth.floor(at.y), Mth.floor(at.z));
    }
}
