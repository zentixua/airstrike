package ua.zentix.airstrike.client.map;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.material.MapColor;
import org.jetbrains.annotations.Nullable;

/** Откуда карта берёт рельеф: верх земли и его цвет в каждой колонке. */
interface TerrainSource {
    /**
     * Верх колонки: первая высота воздуха над землёй (как карта высот), цвет верхнего блока на карте и глубина
     * воды над дном (0 — суша).
     */
    record Column(int height, MapColor color, int waterDepth) {}

    /** Чтение колонок для одной плитки; держит свои кэши, закрывается после плитки. */
    interface Reader extends AutoCloseable {
        /** Колонка (x, z) или null, если о ней ничего не известно. */
        @Nullable
        Column column(int x, int z);

        @Override
        void close();
    }

    /** Можно ли читать не в потоке игры (иначе плитки строятся в кадре, понемногу). */
    boolean offThread();

    /** Читатель для мира, который сейчас у клиента; null — источник для него недоступен. */
    @Nullable
    Reader open(ClientLevel level);

    /** Через сколько перестраивать плитку, у которой рельеф есть везде (новое источник может и сообщить: {@link #changes}). */
    long refreshNanos();

    /** Чанк, рельеф которого источник обновил. */
    @FunctionalInterface
    interface ChunkSink {
        void changed(int chunkX, int chunkZ);
    }

    /** Раз в тик, в потоке игры: чанки мира {@code level}, которые источник обновил с прошлого раза. */
    default void changes(ClientLevel level, ChunkSink sink) {}

    /** Для лога: что источник отдал с начала мира (колонки, отказы и почему). */
    default String describe() {
        return "";
    }
}
