package ua.zentix.airstrike.util;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.TickingBlockEntity;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import ua.zentix.airstrike.Airstrike;

/**
 * Блок-сущности чанка тикают, только когда готовы все 8 соседей ({@link ua.zentix.airstrike.mixin.chunk.LevelChunkTickingMixin}).
 * <p>
 * Ваниль пускает блок-сущность тикать по двум уровням: загрузки чанка ≤ 32 ({@code LevelChunk.isTicking}) и счёта тика
 * &lt; 33 ({@code ServerLevel.shouldTickBlocksAt}); готовность соседей не проверяется. Улей или хранилище испытаний у края
 * читает соседний чанк, который ещё генерируется, и грузит его синхронно за очередью генерации — сервер стоял до 31 с
 * (облако 29.09.2026: игрок сместился за край готового квадрата, район цели на {@code main}). Случайные тики и тики
 * блоков ваниль сама пускает только в «тикающий» чанк: его будущее ({@code ChunkHolder.getTickingChunk},
 * {@code ChunkMap.prepareTickingChunk}) готово, когда все соседи полностью загружены, и соседи с уровнем ≤ 33 остаются
 * такими. Здесь то же условие и для блок-сущностей.
 * <p>
 * Аппараты Sable это не задерживает: Sable 2.0.5 кладёт держатель чанка плота {@code PlotChunkHolder} в ту же карту
 * {@code ChunkMap} ({@code ServerLevelPlot.addChunkHolder} → {@code updatingChunkMap}), а его {@code getTickingChunk} —
 * сам чанк; машины Create на аппарате тикают, как раньше (GameTest {@code craftBlockEntitiesTick} проверяет и этот путь).
 * Чанк без держателя тоже не трогаем.
 */
public final class BlockTicking {
    /** Через столько тиков после запуска сервера — проверка, что условие действительно стоит на тике блок-сущностей. */
    private static final int CHECK_EVERY = 1200;
    /** Сколько раз условие спрашивали (миксин встал и тик блок-сущностей идёт через {@code LevelChunk.isTicking}). */
    private static long checks;
    private static boolean reported;

    private BlockTicking() {}

    /** Соседи чанка готовы (или это чанк аппарата) — блок-сущности могут тикать. */
    public static boolean neighboursReady(ServerLevel level, ChunkPos pos) {
        ChunkHolder holder = level.getChunkSource().chunkMap.getVisibleChunkIfPresent(pos.toLong());
        return holder == null || holder.getTickingChunk() != null;
    }

    /** Миксин: условие спросили. */
    public static void checked() {
        checks++;
    }

    public static long checks() {
        return checks;
    }

    /**
     * Миксин необязательный ({@code required: false}): не вставший — лишь предупреждение Mixin в логе, а другой мод
     * сборки может тикать блок-сущности мимо {@code LevelChunk.isTicking}. Раз в минуту после запуска, пока не ясно:
     * есть блок-сущность, которая должна была тикать, — условие спрашивали или нет. Итог — одна строка в лог.
     */
    public static void onServerTick(ServerTickEvent.Post e) {
        if (reported || e.getServer().getTickCount() % CHECK_EVERY != 0) return;
        if (checks > 0) {
            reported = true;
            Airstrike.LOG.info("Блок-сущности тикают только при готовых соседних чанках (проверок: {})", checks);
            return;
        }
        for (ServerLevel level : e.getServer().getAllLevels()) {
            for (TickingBlockEntity t : level.blockEntityTickers) {
                BlockPos pos = t.getPos();
                if (t.isRemoved() || !level.shouldTickBlocksAt(ChunkPos.asLong(pos))) continue;
                reported = true;
                Airstrike.LOG.warn("Тик блок-сущностей идёт мимо LevelChunk.isTicking (миксин LevelChunkTickingMixin не встал или "
                        + "другой мод заменил тик): блок-сущности у края прогрузки могут грузить соседние чанки синхронно");
                return;
            }
        }
    }
}
