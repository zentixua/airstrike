package ua.zentix.airstrike.client.map;

import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.interfaces.block.IDhApiBlockStateWrapper;
import com.seibel.distanthorizons.api.interfaces.data.IDhApiTerrainDataCache;
import com.seibel.distanthorizons.api.interfaces.data.IDhApiTerrainDataRepo;
import com.seibel.distanthorizons.api.interfaces.world.IDhApiLevelWrapper;
import com.seibel.distanthorizons.api.methods.events.DhApiEventRegister;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiAfterDhInitEvent;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiLevelLoadEvent;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiLevelUnloadEvent;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiEventParam;
import com.seibel.distanthorizons.api.objects.DhApiResult;
import com.seibel.distanthorizons.api.objects.data.DhApiTerrainDataPoint;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;
import org.jetbrains.annotations.Nullable;
import ua.zentix.airstrike.Airstrike;

import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Рельеф из данных Distant Horizons через его публичный API ({@code DhApi.Delayed.terrainRepo}): всё, что DH уже
 * посчитал для дальней прорисовки, — на километры вокруг, без загрузки чанков. Чтение идёт из базы DH и может
 * занимать миллисекунды, поэтому — не в потоке игры; кэш данных DH ({@code createSoftCache}) — на плитку, иначе
 * каждая колонка заново читала бы и распаковывала свой участок 64×64.
 * <p>
 * Когда API готово и какие миры DH загрузил, узнаём из событий API ({@code DhApiAfterDhInitEvent},
 * {@code DhApiLevelLoadEvent}/{@code DhApiLevelUnloadEvent}), как велит javadoc {@code DhApi.Delayed}.
 * <p>
 * Класс загружается, только если DH стоит (см. {@link TerrainTiles}): без DH его ссылки на API не разрешаются.
 */
final class DistantHorizonsTerrain implements TerrainSource {
    /**
     * Мажорная версия API, против которой собран мод (7.2.0, DH 3.3.x). По javadoc {@code getApiMajorVersion} она
     * меняется только с несовместимыми изменениями, поэтому другая — не наша: рельеф тогда только из чанков.
     */
    private static final int API_MAJOR = 7;
    private static final int MAX_WATER_DEPTH = 16;

    /** Для лога: колонки с верхом, без данных, отказы API и первый отказ; почему не открылся мир. */
    private final AtomicLong found = new AtomicLong(), empty = new AtomicLong(), failed = new AtomicLong();
    /** Исключение из API уже в логе со стеком (дальше — только в счётчике отказов). */
    private final AtomicBoolean threw = new AtomicBoolean();
    private volatile String firstFailure = "", notOpened = "";

    /** {@code DhApi.Delayed} заполнен (после первой инициализации DH). */
    private static volatile boolean initialized;
    /** Миры, которые DH сейчас держит загруженными. */
    private static final Set<IDhApiLevelWrapper> loaded = ConcurrentHashMap.newKeySet();

    /** Версия API DH — та, против которой мод собран; иначе источника нет (и запись в лог). */
    static boolean supported() {
        int major = DhApi.getApiMajorVersion();
        if (major == API_MAJOR) return true;
        Airstrike.LOG.warn("Distant Horizons {}: API {}.{}, мод собран под {}.x — дальний рельеф на карте выключен",
                DhApi.getModVersion(), major, DhApi.getApiMinorVersion(), API_MAJOR);
        return false;
    }

    /** Один раз при запуске клиента: подписка на события API DH. */
    static void subscribe() {
        DhApiEventRegister.on(DhApiAfterDhInitEvent.class, new DhApiAfterDhInitEvent() {
            @Override
            public void afterDistantHorizonsInit(DhApiEventParam<Void> input) {
                initialized = true;
            }
        });
        DhApiEventRegister.on(DhApiLevelLoadEvent.class, new DhApiLevelLoadEvent() {
            @Override
            public void onLevelLoad(DhApiEventParam<EventParam> input) {
                loaded.add(input.value.levelWrapper);
            }
        });
        DhApiEventRegister.on(DhApiLevelUnloadEvent.class, new DhApiLevelUnloadEvent() {
            @Override
            public void onLevelUnload(DhApiEventParam<EventParam> input) {
                loaded.remove(input.value.levelWrapper);
            }
        });
        // DH мог инициализироваться раньше подписки: тогда событие уже прошло, а поля уже заполнены
        if (DhApi.Delayed.terrainRepo != null) initialized = true;
    }

    @Override
    public boolean offThread() {
        return true;
    }

    @Nullable
    @Override
    public Reader open(ClientLevel level) {
        IDhApiLevelWrapper dhLevel = initialized ? dhLevel(level) : null;
        if (dhLevel == null) {
            notOpened = !initialized ? "DH ещё не инициализирован" : "DH не загрузил мир " + level.dimension().location();
            return null;
        }
        IDhApiTerrainDataRepo repo = DhApi.Delayed.terrainRepo;
        notOpened = "";
        IDhApiTerrainDataCache cache = repo.createSoftCache();
        return new Reader() {
            @Nullable
            @Override
            public Column column(int x, int z) {
                DhApiResult<DhApiTerrainDataPoint[]> r;
                try {
                    r = repo.getColumnDataAtBlockPos(dhLevel, x, z, cache);
                } catch (RuntimeException e) {
                    // отказ API исключением, а не через DhApiResult: колонка без данных, как при любом отказе, —
                    // одна колонка не роняет всю плитку (её перестраивали бы каждые 5 с со стеком в лог)
                    if (failed.getAndIncrement() == 0) firstFailure = e.toString();
                    if (!threw.getAndSet(true)) Airstrike.LOG.warn("Distant Horizons: чтение колонки ({}, {}) бросило исключение", x, z, e);
                    return null;
                }
                if (!r.success || r.payload == null) {
                    if (failed.getAndIncrement() == 0) firstFailure = r.message;
                    return null;
                }
                Column c = top(r.payload);
                (c == null ? empty : found).incrementAndGet();
                return c;
            }

            @Override
            public void close() {
                cache.close();
            }
        };
    }

    @Override
    public String describe() {
        return String.format(Locale.ROOT, "DH %s (API %d.%d): колонок с верхом %d, пустых %d, отказов %d%s%s", DhApi.getModVersion(),
                DhApi.getApiMajorVersion(), DhApi.getApiMinorVersion(), found.get(), empty.get(), failed.get(),
                firstFailure.isEmpty() ? "" : " (первый: " + firstFailure + ")", notOpened.isEmpty() ? "" : ", не открыт: " + notOpened);
    }

    /**
     * Уровень DH для мира клиента. В одиночной игре данные DH — у мира сервера (как {@code getSinglePlayerLevel}), по
     * сети — у клиентского: его обёртка держит тот же {@code ClientLevel}.
     */
    @Nullable
    private static IDhApiLevelWrapper dhLevel(ClientLevel level) {
        boolean singlePlayer = Minecraft.getInstance().hasSingleplayerServer();
        for (IDhApiLevelWrapper w : loaded) {
            Object mc = w.getWrappedMcObject();
            if (singlePlayer ? mc instanceof ServerLevel server && server.dimension() == level.dimension() : mc == level) return w;
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
