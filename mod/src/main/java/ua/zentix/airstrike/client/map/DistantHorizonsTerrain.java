package ua.zentix.airstrike.client.map;

import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.interfaces.block.IDhApiBlockStateWrapper;
import com.seibel.distanthorizons.api.interfaces.data.IDhApiTerrainDataCache;
import com.seibel.distanthorizons.api.interfaces.data.IDhApiTerrainDataRepo;
import com.seibel.distanthorizons.api.interfaces.world.IDhApiLevelWrapper;
import com.seibel.distanthorizons.api.interfaces.world.IDhApiWorldProxy;
import com.seibel.distanthorizons.api.objects.DhApiResult;
import com.seibel.distanthorizons.api.objects.data.DhApiTerrainDataPoint;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;
import org.jetbrains.annotations.Nullable;
import ua.zentix.airstrike.Airstrike;

/**
 * Рельеф из данных Distant Horizons через его публичный API ({@code DhApi.Delayed.terrainRepo}): всё, что DH уже
 * посчитал для дальней прорисовки, — на километры вокруг, без загрузки чанков. Чтение идёт из базы DH и может
 * занимать миллисекунды, поэтому — не в потоке игры; кэш данных DH ({@code createSoftCache}) — на плитку, иначе
 * каждая колонка заново читала бы и распаковывала свой участок 64×64.
 * <p>
 * Класс загружается, только если DH стоит (см. {@link TerrainTiles}): без DH его ссылки на API не разрешаются.
 */
final class DistantHorizonsTerrain implements TerrainSource {
    /** С этой версии API есть {@code createSoftCache}; чтение без кэша DH отклоняет. */
    private static final int MIN_API_MAJOR = 5;
    private static final int MAX_WATER_DEPTH = 16;

    /** DH стоит, но его API старше нужного — источника нет. */
    static boolean supported() {
        return DhApi.getApiMajorVersion() >= MIN_API_MAJOR;
    }

    @Override
    public boolean offThread() {
        return true;
    }

    @Nullable
    @Override
    public Reader open(ClientLevel level) {
        IDhApiTerrainDataRepo repo = DhApi.Delayed.terrainRepo;
        IDhApiLevelWrapper dhLevel = dhLevel(level);
        if (repo == null || dhLevel == null) return null;
        IDhApiTerrainDataCache cache = repo.createSoftCache();
        return new Reader() {
            @Nullable
            @Override
            public Column column(int x, int z) {
                DhApiResult<DhApiTerrainDataPoint[]> r = repo.getColumnDataAtBlockPos(dhLevel, x, z, cache);
                return r.success && r.payload != null ? top(r.payload) : null;
            }

            @Override
            public void close() {
                cache.close();
            }
        };
    }

    /**
     * Уровень DH для мира клиента. В одиночной игре данные DH — у мира сервера ({@code getSinglePlayerLevel}), по сети —
     * у клиентского: его обёртка держит тот же {@code ClientLevel}.
     */
    @Nullable
    private static IDhApiLevelWrapper dhLevel(ClientLevel level) {
        IDhApiWorldProxy world = DhApi.Delayed.worldProxy;
        if (world == null || !world.worldLoaded()) return null;
        try {
            if (Minecraft.getInstance().hasSingleplayerServer()) return world.getSinglePlayerLevel();
            for (IDhApiLevelWrapper w : world.getAllLoadedLevelWrappers()) {
                if (w.getWrappedMcObject() == level) return w;
            }
        } catch (IllegalStateException e) {
            // мир DH закрылся между проверкой и чтением
            Airstrike.LOG.debug("Distant Horizons: мир недоступен: {}", e.getMessage());
        }
        return null;
    }

    /** Верх колонки DH (данные идут сверху вниз): первый блок с цветом на карте; под водой — глубина до дна. */
    @Nullable
    private static Column top(DhApiTerrainDataPoint[] column) {
        for (int i = 0; i < column.length; i++) {
            DhApiTerrainDataPoint p = column[i];
            MapColor color = color(p);
            if (color == MapColor.NONE) continue;
            int depth = p.blockStateWrapper.isLiquid() ? waterDepth(column, i) : 0;
            return new Column(p.topYBlockPos, color, depth);
        }
        return null;
    }

    private static int waterDepth(DhApiTerrainDataPoint[] column, int water) {
        int surface = column[water].topYBlockPos;
        for (int i = water + 1; i < column.length; i++) {
            DhApiTerrainDataPoint p = column[i];
            if (p != null && !p.blockStateWrapper.isAir() && !p.blockStateWrapper.isLiquid()) {
                return Math.min(MAX_WATER_DEPTH, surface - p.topYBlockPos);
            }
        }
        return MAX_WATER_DEPTH;
    }

    private static MapColor color(@Nullable DhApiTerrainDataPoint p) {
        if (p == null) return MapColor.NONE;
        IDhApiBlockStateWrapper block = p.blockStateWrapper;
        if (block == null || block.isAir() || !(block.getWrappedMcObject() instanceof BlockState state)) return MapColor.NONE;
        // цвет блока без мира вокруг: у ванильных и почти всех модовых блоков он от мира не зависит
        return state.getMapColor(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
    }
}
