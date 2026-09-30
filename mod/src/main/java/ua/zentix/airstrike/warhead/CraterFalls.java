package ua.zentix.airstrike.warhead;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.DirectionalPlaceContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ColoredFallingBlock;
import net.minecraft.world.level.block.ConcretePowderBlock;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import org.jetbrains.annotations.Nullable;
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
 * живых падающих блоков на район взрыва и {@link #TOTAL_CAP} на мир (залп — это много районов сразу): осыпание,
 * которое видно вблизи. Остальные ложатся сразу туда, где упали бы: {@code fall} уже убрал блок с места, сущность в мир
 * не входит ({@link EntityJoinLevelEvent} отменён), блок ставится на опору под ним. Соседи поставленного блока
 * получают обновление, как от приземления, так что столбец выше осыпается следом тем же путём.
 * <p>
 * Сразу ложится только то, у чего приземление — это «поставить блок» ({@link #restingPlace}); остальное падает живым,
 * по правилам ванили. Район — горизонтальный квадрат досягаемости взрыва на всю высоту мира (осыпаются и склоны над
 * воронкой): открыт, пока взрыв держит {@link BlastArea}, и ещё {@link #GRACE} тиков после. Не сохраняется: живёт
 * в несохраняемом attachment мира {@link ModAttachments#CRATER_FALLS}.
 */
public final class CraterFalls {
    /** Живых падающих блоков на район взрыва. */
    public static final int LIVE_CAP = 48;
    /** Живых падающих блоков во всех районах мира вместе: районы залпа с большим разбросом не пересекаются. */
    public static final int TOTAL_CAP = 96;
    /** Сколько тиков после конца взрыва район ещё осыпается сам (обрушения цепочкой, поздние обновления соседей). */
    static final int GRACE = 200;
    /**
     * Дольше район не живёт, даже если конец взрыва так и не пришёл (исключение в таймлайне): самый долгий взрыв —
     * отложенный до готовности района ({@link Warheads#DEFERRED_GIVE_UP_TICKS}), и после него — осыпание.
     */
    static final int OPEN_LIMIT = Warheads.DEFERRED_GIVE_UP_TICKS + GRACE;

    private final Map<UUID, Zone> zones = new HashMap<>();
    /** Падающие блоки, которые районы пустили в мир живыми. */
    private final List<Live> live = new ArrayList<>();
    /** Тик последней чистки {@link #live} и районов. */
    private long pruned = Long.MIN_VALUE;
    /** Сколько блоков легло сразу, без сущности, с загрузки мира. */
    private long settled;

    /** Для {@link ModAttachments#CRATER_FALLS}: своё у каждого мира, не сохраняется. */
    public CraterFalls() {}

    public static CraterFalls get(ServerLevel level) {
        return level.getData(ModAttachments.CRATER_FALLS);
    }

    /** Взрыв с ключом {@code key} достаёт на {@code reach} блоков от {@code centre}: район открыт до {@link #close}. */
    void open(ServerLevel level, UUID key, Vec3 centre, double reach) {
        zones.put(key, new Zone(Mth.floor(centre.x - reach), Mth.floor(centre.z - reach),
                Mth.floor(centre.x + reach), Mth.floor(centre.z + reach), level.getGameTime() + OPEN_LIMIT));
    }

    /** Взрыв кончился: район осыпается сам ещё {@link #GRACE} тиков. */
    void close(ServerLevel level, UUID key) {
        Zone z = zones.get(key);
        if (z != null) z.until = Math.min(z.until, level.getGameTime() + GRACE);
    }

    /** Сколько блоков легло сразу, без сущности, с загрузки мира. */
    public long settled() {
        return settled;
    }

    public static void onEntityJoin(EntityJoinLevelEvent event) {
        if (event.loadedFromDisk() || !(event.getLevel() instanceof ServerLevel level)
                || event.getEntity().getType() != EntityType.FALLING_BLOCK) return;
        FallingBlockEntity falling = (FallingBlockEntity) event.getEntity();
        // только блок, который сейчас начал падать со своего места (FallingBlockEntity.fall): у /summon, копии при смене
        // измерения и сущностей других модов начальная точка не своя (не сохраняется, по умолчанию 0 0 0)
        BlockPos from = falling.getStartPos();
        if (falling.time != 0 || !from.equals(falling.blockPosition()) || !level.getBlockState(from).isAir()) return;
        if (get(level).settle(level, falling, from)) event.setCanceled(true);
    }

    /**
     * Блок {@code falling} начал падать с {@code from} (его место уже пусто). В районе взрыва сверх предела — положить
     * его сразу на опору и вернуть {@code true} (сущность в мир не входит); иначе — пустить живым.
     */
    private boolean settle(ServerLevel level, FallingBlockEntity falling, BlockPos from) {
        prune(level);
        Zone zone = null;
        for (Zone z : zones.values()) {
            if (z.contains(from)) {
                zone = z;
                break;
            }
        }
        if (zone == null) return false;
        if (zone.live < LIVE_CAP && live.size() < TOTAL_CAP) {
            admit(falling, zone);
            return false;
        }
        BlockPos rest = restingPlace(level, falling, from);
        if (rest == null || !level.setBlock(rest, Block.updateFromNeighbourShapes(falling.getBlockState(), level, rest), Block.UPDATE_ALL)) {
            admit(falling, zone);
            return false;
        }
        settled++;
        return true;
    }

    private void admit(FallingBlockEntity falling, Zone zone) {
        live.add(new Live(falling, zone));
        zone.live++;
    }

    /**
     * Раз в тик: закрыть отжившие районы и забыть ушедшие падающие блоки — приземлились, выгружены с чанком или в мир
     * так и не вошли (вход отменил обработчик другого мода). Без районов счёт не нужен.
     */
    private void prune(ServerLevel level) {
        long now = level.getGameTime();
        if (pruned == now) return;
        pruned = now;
        zones.values().removeIf(z -> z.until < now);
        if (zones.isEmpty()) {
            live.clear();
            return;
        }
        live.removeIf(l -> {
            boolean gone = l.entity.isRemoved() || level.getEntity(l.entity.getId()) != l.entity;
            if (gone) l.zone.live--;
            return gone;
        });
    }

    /**
     * Куда ляжет блок, падая от {@code from}, — или {@code null}, если приземление у ванили делает больше, чем «поставить
     * блок», и он должен упасть живым. Сразу ложатся только песок, красный песок, гравий ({@link ColoredFallingBlock})
     * и бетонная смесь: у наковальни урон и звук, у блоков других модов — свои {@code onLand}/{@code onBrokenAfterFall}.
     * Клетка — первая над опорой ({@link FallingBlock#isFree}: воздух, огонь, жидкость, заменяемое); как у ванили, её
     * содержимое заменяется ({@code canBeReplaced} с тем же контекстом), и блок там держится. Опора — с полной верхней
     * гранью: на факел, плиту, ковёр, слой снега ваниль садит сущность в клетку опоры и выбрасывает блок предметом.
     * Бетонная смесь, дошедшая до воды, застывает — она падает живой. Столбец — тот же чанк, что уже тикает блоками (его
     * соседи готовы): чтение ничего не грузит.
     */
    @Nullable
    private static BlockPos restingPlace(ServerLevel level, FallingBlockEntity falling, BlockPos from) {
        BlockState state = falling.getBlockState();
        Class<?> kind = state.getBlock().getClass();
        boolean powder = kind == ConcretePowderBlock.class;
        if (falling.blockData != null || kind != ColoredFallingBlock.class && !powder) return null;
        BlockPos.MutableBlockPos p = from.mutable();
        boolean fluid = false;
        while (p.getY() > level.getMinBuildHeight()) {
            BlockState below = level.getBlockState(p.below());
            if (!FallingBlock.isFree(below)) break;
            fluid |= !below.getFluidState().isEmpty();
            p.move(Direction.DOWN);
        }
        if (p.getY() <= level.getMinBuildHeight() || powder && fluid) return null;
        BlockPos support = p.below();
        if (!level.getBlockState(support).isFaceSturdy(level, support, Direction.UP)) return null;
        BlockState replaced = level.getBlockState(p);
        if (!replaced.canBeReplaced(new DirectionalPlaceContext(level, p, Direction.DOWN, ItemStack.EMPTY, Direction.UP))
                || !state.canSurvive(level, p)) return null;
        return p.immutable();
    }

    private record Live(FallingBlockEntity entity, Zone zone) {}

    private static final class Zone {
        final int minX, minZ, maxX, maxZ;
        long until;
        /** Живых падающих блоков, которые пустил этот район. */
        int live;

        Zone(int minX, int minZ, int maxX, int maxZ, long until) {
            this.minX = minX;
            this.minZ = minZ;
            this.maxX = maxX;
            this.maxZ = maxZ;
            this.until = until;
        }

        boolean contains(BlockPos p) {
            return p.getX() >= minX && p.getX() <= maxX && p.getZ() >= minZ && p.getZ() <= maxZ;
        }
    }
}
