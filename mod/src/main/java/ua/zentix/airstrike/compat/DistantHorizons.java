package ua.zentix.airstrike.compat;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.neoforged.fml.ModList;
import ua.zentix.airstrike.Airstrike;

import java.util.function.LongConsumer;

/**
 * Связь с Distant Horizons (необязателен): стоит ли он и подходит ли его API. Классы DH трогаются только за этой
 * проверкой ({@link DistantHorizonsLod}, клиентская карта) — без DH ссылки на его API не разрешатся.
 */
public final class DistantHorizons {
    /** Мажорная версия API, против которой собран мод (7.2.0, DH 3.3.x): по javadoc DH она меняется при несовместимости. */
    public static final int API_MAJOR = 7;
    private static volatile Boolean present;

    private DistantHorizons() {}

    /** DH стоит, и его API той версии, под которую собран мод (иначе — запись в лог, один раз). */
    public static boolean present() {
        Boolean p = present;
        if (p == null) {
            p = ModList.get().isLoaded("distanthorizons") && supported();
            present = p;
        }
        return p;
    }

    private static boolean supported() {
        try {
            int major = DistantHorizonsLod.apiMajor();
            if (major == API_MAJOR) return true;
            Airstrike.LOG.warn("Distant Horizons {}: API {}, мод собран под {}.x — дальний рельеф на карте и блэкаут в LOD выключены",
                    DistantHorizonsLod.modVersion(), major, API_MAJOR);
        } catch (LinkageError e) {
            // API DH без нужных классов или методов (DH новее, чем мод знает): игра грузится, без DH
            Airstrike.LOG.warn("Distant Horizons: API не то, под которое собран мод, — дальний рельеф на карте и блэкаут в LOD выключены", e);
        }
        return false;
    }

    /**
     * Передать DH чанк, чтобы его LOD обновился сразу, а не при сохранении чанка (так кварталы вдали гаснут
     * по каскаду). Чанк — из мира или копия с диска; зовётся из потока сервера. Без DH — ничего.
     *
     * @return DH принял чанк в свою очередь — жди {@link #lodSaved}
     */
    public static boolean updateLod(ServerLevel level, ChunkAccess chunk) {
        return present() && DistantHorizonsLod.update(level, chunk);
    }

    /**
     * Чанки, чей LOD DH сохранил после {@link #updateLod} (событие DH; дальше он сам пересчитывает крупные LOD вдали).
     * Без DH — ничего.
     */
    public static void lodSaved(ServerLevel level, LongConsumer consumer) {
        if (present()) DistantHorizonsLod.confirmed(level, consumer);
    }

    /** Подтверждения для чанка больше не ждать. */
    public static void lodForget(ServerLevel level, long chunk) {
        if (present()) DistantHorizonsLod.forget(level, chunk);
    }
}
