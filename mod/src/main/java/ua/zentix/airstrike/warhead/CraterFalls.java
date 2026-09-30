package ua.zentix.airstrike.warhead;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import ua.zentix.airstrike.registry.ModAttachments;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Осыпание в воронках. Взрыв оставляет песок и гравий без опоры (и бомба сама кладёт щебень над полостью), и каждый
 * такой блок ваниль превращает в падающую сущность ({@code FallingBlock.tick} → {@code FallingBlockEntity.fall}):
 * в песке или под сводом полости их сотни за секунды, и сервер несёт их все. Здесь — не больше {@link #LIVE_CAP}
 * живых падающих блоков на район взрыва (осыпание, которое видно вблизи); остальные ложатся сразу туда, где
 * упали бы: {@code fall} уже убрал блок с места, сущность в мир не входит ({@link EntityJoinLevelEvent} отменён),
 * блок ставится на первую опору под ним. Соседи поставленного блока получают обновление как от приземления, так что
 * столбец выше осыпается следом тем же путём.
 * <p>
 * Район — горизонтальный квадрат досягаемости взрыва на всю высоту мира (осыпаются и склоны над воронкой): открыт,
 * пока взрыв держит {@link BlastArea}, и ещё {@link #GRACE} тиков после. Не сохраняется: живёт в несохраняемом
 * attachment мира {@link ModAttachments#CRATER_FALLS}.
 */
public final class CraterFalls {
    /** Живых падающих блоков на район взрыва; соседние районы залпа считают общие. */
    public static final int LIVE_CAP = 48;
    /** Сколько тиков после конца взрыва район ещё осыпается сам (обрушения цепочкой, поздние обновления соседей). */
    static final int GRACE = 200;

    private final Map<UUID, Zone> zones = new HashMap<>();
    /** Падающие блоки, которые районы пустили в мир живыми. */
    private final List<FallingBlockEntity> live = new ArrayList<>();

    /** Для {@link ModAttachments#CRATER_FALLS}: своё у каждого мира, не сохраняется. */
    public CraterFalls() {}

    public static CraterFalls get(ServerLevel level) {
        return level.getData(ModAttachments.CRATER_FALLS);
    }

    /** Взрыв с ключом {@code key} достаёт на {@code reach} блоков от {@code centre}: район открыт до {@link #close}. */
    void open(ServerLevel level, UUID key, Vec3 centre, double reach) {
        prune(level);
        zones.put(key, new Zone(Mth.floor(centre.x - reach), Mth.floor(centre.z - reach),
                Mth.floor(centre.x + reach), Mth.floor(centre.z + reach)));
    }

    /** Взрыв кончился: район осыпается сам ещё {@link #GRACE} тиков. */
    void close(ServerLevel level, UUID key) {
        Zone z = zones.get(key);
        if (z != null) z.until = level.getGameTime() + GRACE;
    }

    public static void onEntityJoin(EntityJoinLevelEvent event) {
        if (event.loadedFromDisk() || !(event.getLevel() instanceof ServerLevel level)
                || event.getEntity().getType() != EntityType.FALLING_BLOCK) return;
        FallingBlockEntity falling = (FallingBlockEntity) event.getEntity();
        if (get(level).settle(level, falling)) event.setCanceled(true);
    }

    /**
     * Блок начал падать ({@code FallingBlockEntity.fall}: его место уже пусто). В районе взрыва сверх {@link #LIVE_CAP}
     * — положить его сразу на опору и вернуть {@code true} (сущность в мир не входит); иначе — пустить живым.
     */
    private boolean settle(ServerLevel level, FallingBlockEntity falling) {
        BlockPos from = falling.getStartPos();
        prune(level);
        Zone zone = null;
        for (Zone z : zones.values()) {
            if (z.contains(from)) {
                zone = z;
                break;
            }
        }
        if (zone == null) return false;
        // ушли: приземлились, выгружены с чанком — или в мир так и не вошли (вход отменил обработчик другого мода)
        live.removeIf(e -> e.isRemoved() || level.getEntity(e.getId()) != e);
        int inZone = 0;
        for (FallingBlockEntity e : live) {
            if (zone.contains(e.getStartPos())) inZone++;
        }
        BlockPos rest = inZone < LIVE_CAP ? null : restingPlace(level, falling, from);
        if (rest == null) {
            live.add(falling);
            return false;
        }
        BlockState state = Block.updateFromNeighbourShapes(falling.getBlockState(), level, rest);
        level.setBlock(rest, state, Block.UPDATE_ALL);
        return true;
    }

    private void prune(ServerLevel level) {
        long now = level.getGameTime();
        zones.values().removeIf(z -> z.until < now);
    }

    /**
     * Куда ляжет блок, падая от {@code from}: первая клетка над опорой ({@link FallingBlock#isFree} — воздух, огонь,
     * жидкость, заменяемое). Только туда, где падение не делает ничего сверх «поставить блок»: клетка — воздух (не вода:
     * бетонная смесь застывает, блок с {@code waterlogged} набирает воду), блок там держится, у него нет данных
     * блок-сущности и он не проваливается под низ мира; остальное (редкое) падает живым, по правилам ванили.
     * Столбец — тот же чанк, что уже тикает блоками: чтение ничего не грузит.
     */
    private static BlockPos restingPlace(ServerLevel level, FallingBlockEntity falling, BlockPos from) {
        BlockState state = falling.getBlockState();
        if (falling.blockData != null || !(state.getBlock() instanceof FallingBlock)) return null;
        BlockPos.MutableBlockPos p = from.mutable();
        while (p.getY() > level.getMinBuildHeight() && FallingBlock.isFree(level.getBlockState(p.below()))) p.move(0, -1, 0);
        if (p.getY() <= level.getMinBuildHeight() || !level.getBlockState(p).isAir() || !state.canSurvive(level, p)) return null;
        return p.immutable();
    }

    private static final class Zone {
        final int minX, minZ, maxX, maxZ;
        long until = Long.MAX_VALUE;

        Zone(int minX, int minZ, int maxX, int maxZ) {
            this.minX = minX;
            this.minZ = minZ;
            this.maxX = maxX;
            this.maxZ = maxZ;
        }

        boolean contains(BlockPos p) {
            return p.getX() >= minX && p.getX() <= maxX && p.getZ() >= minZ && p.getZ() <= maxZ;
        }
    }
}
