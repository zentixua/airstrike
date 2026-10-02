package ua.zentix.airstrike.util;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Места POI (кровати, рабочие места жителей, колокола), которых нет в данных POI мира. Постройку, вставленную мимо
 * {@code setBlock} (WorldEdit, схемы карт, сборка аппарата Sable прямо в секции), ваниль в данные POI не вносит,
 * а снятие такого блока через мир ({@code ServerLevel.onBlockStateChange} → {@code PoiManager.remove}) пишет в лог
 * ошибку PoiSection «POI data mismatch: never registered» — на каждый блок. Город карты у Артёма: 151 строка за вечер
 * залпов (01.10.2026).
 */
public final class PoiRecords {
    private PoiRecords() {}

    /**
     * Место POI блока {@code state} в {@code pos} — в данные, если его там нет. Звать перед тем, как блок снимет
     * мир: удаление из данных идёт задачей сервера после смены блока и найдёт запись.
     */
    public static void recordIfMissing(ServerLevel level, BlockPos pos, BlockState state) {
        PoiTypes.forState(state).ifPresent(type -> {
            if (level.getPoiManager().getType(pos).isEmpty()) level.getPoiManager().add(pos, type);
        });
    }

    /**
     * То же для места и шести его соседей. Снятый блок уводит за собой соседа обновлением формы: изножье — изголовье
     * кровати (место POI у кровати — изголовье), опора — колокол. Сосед уходит через мир мимо того, кто снимал блок,
     * и без записи в данных POI ваниль пишет ошибку (игра 02.10.2026: 17 строк после ракет по домам Newisle — пары
     * соседних мест по этажам). Сосед, который устоял, остаётся в данных — как поставленный через мир.
     */
    public static void recordWithNeighbours(ServerLevel level, BlockPos pos, BlockState state) {
        recordIfMissing(level, pos, state);
        BlockPos.MutableBlockPos n = new BlockPos.MutableBlockPos();
        for (Direction d : Direction.values()) {
            n.setWithOffset(pos, d);
            if (!Terrain.ready(level, n)) continue;
            BlockState s = level.getBlockState(n);
            if (PoiTypes.hasPoi(s)) recordIfMissing(level, n.immutable(), s);
        }
    }
}
