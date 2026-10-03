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

    /**
     * Сколько чтений из фоновых потоков сразу имеет смысл, когда карта ждёт только источника: столько, сколько у него
     * своих потоков на чтение (больше — ждали бы в его же очереди). Источнику, что читается в потоке игры, не нужно.
     */
    default int parallelism() {
        return 1;
    }

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

    /**
     * {@link #changes} сообщает о чанках, данные которых появились (пришли клиенту), а не изменились: перечитывать
     * нужно только плитки с дырами.
     */
    default boolean arrivals() {
        return false;
    }

    /** Для лога: что источник отдал с начала мира (колонки, отказы и почему). */
    default String describe() {
        return "";
    }
}
