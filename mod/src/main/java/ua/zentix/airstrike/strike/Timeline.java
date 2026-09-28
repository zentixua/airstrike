package ua.zentix.airstrike.strike;

import net.minecraft.server.level.ServerLevel;

/** То, что длится несколько тиков на сервере (таймлайн взрыва наземного или подземного). */
public interface Timeline {
    /** Один тик. false — закончилось, убрать. */
    boolean tick(ServerLevel level);

    /** Таймлайн убран — закончился или упал с ошибкой: отпустить то, что он держит (тикеты чанков). */
    default void end(ServerLevel level) {}
}
