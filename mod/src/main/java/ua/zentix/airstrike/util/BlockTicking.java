package ua.zentix.airstrike.util;

import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

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
 * Аппараты Sable это не задевает: для чанка плота {@code ChunkMap.getVisibleChunkIfPresent} (миксин Sable) отдаёт
 * {@code PlotChunkHolder}, у которого {@code getTickingChunk} — сам чанк, и машины Create на аппарате тикают, как раньше
 * (GameTest {@code craftBlockEntitiesTick}). Чанк без держателя тоже не трогаем.
 */
public final class BlockTicking {
    private BlockTicking() {}

    /** Соседи чанка готовы (или это чанк аппарата) — блок-сущности могут тикать. */
    public static boolean neighboursReady(ServerLevel level, ChunkPos pos) {
        ChunkHolder holder = level.getChunkSource().chunkMap.getVisibleChunkIfPresent(pos.toLong());
        return holder == null || holder.getTickingChunk() != null;
    }
}
