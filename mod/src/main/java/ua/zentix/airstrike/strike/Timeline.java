package ua.zentix.airstrike.strike;

import net.minecraft.server.level.ServerLevel;

/** То, что длится несколько тиков на сервере (таймлайн взрыва, залп). */
public interface Timeline {
    /** Один тик. false — закончилось, убрать. */
    boolean tick(ServerLevel level);
}
