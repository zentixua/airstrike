package ua.zentix.airstrike.warhead;

import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundExplodePacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.ExplosionDamageCalculator;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.EventHooks;
import org.jetbrains.annotations.Nullable;
import ua.zentix.airstrike.compat.SubLevels;
import ua.zentix.airstrike.registry.ModDamageTypes;
import ua.zentix.airstrike.registry.ModParticles;
import ua.zentix.airstrike.registry.ModSounds;
import ua.zentix.airstrike.strike.ImpactCost;
import ua.zentix.airstrike.strike.StrikeWorld;
import ua.zentix.airstrike.util.Terrain;
import ua.zentix.airstrike.work.UnitQueue;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Ванильный взрыв по единицам работы ({@link UnitQueue}): те же шаги, что у {@code ServerLevel.explode}, только снятие
 * блоков — порциями по {@link #PORTION} в следующих единицах. Замер на сборке хоста (город, ракета): два взрыва силы
 * 12–20 — 559 мс в одном тике; стёкла и обломки рядом — десятки мс.
 * <ol>
 *     <li>Первая единица: {@link Explosion} с правилами TNT, {@link EventHooks#onExplosionStart} (приваты отменяют его, как
 *     обычно), {@link Explosion#explode()} — лучи по блокам (Sable добавляет к ним блоки аппаратов своим миксином на этот
 *     метод), {@code ExplosionEvent.Detonate}, урон и отбрасывание сущностей; игрокам рядом — ванильный пакет взрыва
 *     с их отбрасыванием и без блоков (блоки клиенты видят по мере снятия, от центра наружу).</li>
 *     <li>Дальше: блоки из {@link Explosion#getToBlow()} от центра наружу, порция за единицу — через тот же
 *     {@link Explosion#finalizeExplosion}, чей список на время порции — только она: выпадение, огонь, блок-сущности,
 *     обработчики модов на {@code onExplosionHit} — как у ванили.</li>
 * </ol>
 * Взрыв в сетке плотов Sable (на аппарате) — одним ванильным {@code level.explode}: Sable переносит такой взрыв
 * в мир своей обёрткой {@code ServerLevel.explode}, которой здесь нет.
 * <p>
 * Район держит {@link BlastArea} (свой или таймлайна), пока взрыв не кончится; порция ждёт, пока район готов, а блоки
 * в чанках, которые к её сроку не готовы, пропускает.
 */
final class StagedExplosion implements UnitQueue.Job {
    /** Блоков за единицу: у хоста снятие блока в городе с обновлениями соседей и Sable — порядка десятой мс. */
    static final int PORTION = 16;

    private final BlastArea area;
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
    private List<BlockPos> toBlow = List.of();
    private int next;

    StagedExplosion(BlastArea area, Vec3 at, float power, boolean fire, boolean blocks, @Nullable Entity direct,
                    @Nullable Entity owner, @Nullable ExplosionDamageCalculator calculator) {
        this.area = area;
        this.at = at;
        this.power = power;
        this.fire = fire;
        this.blocks = blocks;
        this.direct = direct;
        this.owner = owner;
        this.calculator = calculator;
    }

    @Override
    public boolean ready(ServerLevel level) {
        return Terrain.readyAround(level, at, Warheads.reach(power));
    }

    @Override
    public boolean step(ServerLevel level) {
        long t0 = System.nanoTime();
        if (explosion == null) {
            boolean more = start(level);
            StrikeWorld.get(level).impactCost().add(ImpactCost.Kind.RAYS, System.nanoTime() - t0, 1);
            return more;
        }
        int end = Math.min(toBlow.size(), next + PORTION);
        List<BlockPos> portion = explosion.getToBlow();
        portion.clear();
        for (int i = next; i < end; i++) {
            BlockPos p = toBlow.get(i);
            if (Terrain.ready(level, p)) portion.add(p);
        }
        next = end;
        explosion.finalizeExplosion(false);
        StrikeWorld.get(level).impactCost().add(ImpactCost.Kind.BLOCKS, System.nanoTime() - t0, portion.size());
        portion.clear();
        return next < toBlow.size();
    }

    /** Первая единица; {@code true} — остались блоки. */
    private boolean start(ServerLevel level) {
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
        explosion = new Explosion(level, null, ModDamageTypes.source(level, ModDamageTypes.STRIKE, direct, owner), calculator,
                at.x, at.y, at.z, power, fire, destroy, ModParticles.NONE.get(), ModParticles.NONE.get(), ModSounds.SILENT);
        if (EventHooks.onExplosionStart(level, explosion)) return false;
        explosion.explode();
        // в списке лучей и воздух (по нему ставится огонь): без огня воздуху порция ничего не сделает — не носить его
        List<BlockPos> all = new ArrayList<>(explosion.getToBlow());
        if (!fire) {
            // без разрушений блоков и без огня порциям нечего делать
            if (!explosion.interactsWithBlocks()) all.clear();
            else all.removeIf(p -> level.getBlockState(p).isAir());
        }
        explosion.clearToBlow();
        for (ServerPlayer p : level.players()) {
            if (p.distanceToSqr(at.x, at.y, at.z) < 4096.0) {
                p.connection.send(new ClientboundExplodePacket(at.x, at.y, at.z, power, List.of(), explosion.getHitPlayers().get(p),
                        explosion.getBlockInteraction(), explosion.getSmallExplosionParticles(), explosion.getLargeExplosionParticles(),
                        explosion.getExplosionSound()));
            }
        }
        // от центра наружу: воронка растёт, как от взрыва, а не россыпью (блоки аппаратов из плота — последними)
        all.sort(Comparator.comparingDouble(p -> p.distToCenterSqr(at)));
        toBlow = all;
        return !toBlow.isEmpty();
    }

    @Override
    public void end(ServerLevel level) {
        area.release(level);
    }

    @Override
    public String describe() {
        return String.format(java.util.Locale.ROOT, "Взрыв силы %.0f у %d %d %d", power, Mth.floor(at.x), Mth.floor(at.y), Mth.floor(at.z));
    }
}
