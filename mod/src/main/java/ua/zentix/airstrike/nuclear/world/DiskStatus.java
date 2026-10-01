package ua.zentix.airstrike.nuclear.world;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.visitors.CollectFields;
import net.minecraft.nbt.visitors.FieldSelector;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.CompletableFuture;

/**
 * Целый ли чанк на диске — одно правило на весь мод: по нему строится план руин с диска ({@link DiskShots}, фаза 2
 * подготовки {@link NuclearPrep}), зона за волной берёт чанк в мир ({@link NuclearPrep}: чанк, чьё окно руин целое)
 * и LOD вдали решает, собрать копию с диска или взять чанк в мир ({@link FarLods}, {@link FarZone}). Иначе план с диска
 * получал чанк, которого зона не грузит: план ждал до конца зоны и пропадал (игра Артёма 01.10.2026: «не дождались 453»).
 */
final class DiskStatus {
    /** Имя полной генерации в поле {@code Status}. */
    private static final ResourceLocation FULL = ResourceLocation.withDefaultNamespace("full");

    private DiskStatus() {}

    /**
     * Загрузится ли чанк в мир, ничего не генерируя: {@code Status} — полная генерация, как его читает ваниль
     * ({@code ChunkStatus.byName}: имя без пространства имён, как в старых сохранениях, — из {@code minecraft}), и без
     * незавершённой догенерации под нулём ({@code below_zero_retrogen}: её ваниль догоняет при загрузке). Чанка нет
     * ({@code null}), он недогенерирован (кольца у края исследованного мира, начала структур) или в формате до 1.18
     * ({@code Status} внутри {@code Level}: что выйдет при обновлении, зависит от измерения) — не целый. Реестр статусов
     * не нужен: проверяется и в фоновых потоках.
     *
     * @param fields данные чанка или только поля {@link #fields} — как на диске, до обновления версии
     */
    static boolean whole(@Nullable CompoundTag fields) {
        return fields != null && fields.contains("Status", Tag.TAG_STRING) && FULL.equals(ResourceLocation.tryParse(fields.getString("Status")))
                && !fields.contains("below_zero_retrogen");
    }

    /** Поля, по которым {@link #whole} решает, — без разбора всего чанка ({@code chunkScanner}). */
    private static CollectFields fields() {
        return new CollectFields(new FieldSelector(StringTag.TYPE, "Status"), new FieldSelector(CompoundTag.TYPE, "below_zero_retrogen"));
    }

    /**
     * Целый ли чанк на диске — по его заголовку, в потоке ввода-вывода чанков ({@code chunkScanner}: не загружая и не
     * разбирая чанк). Ответ приходит в потоке ввода-вывода; ошибка чтения — «не целый».
     */
    static CompletableFuture<Boolean> scan(ServerLevel level, ChunkPos pos) {
        CollectFields fields = fields();
        return level.getChunkSource().chunkMap.chunkScanner().scanChunk(pos, fields)
                .handle((v, e) -> e == null && whole(fields.getResult() instanceof CompoundTag tag ? tag : null));
    }
}
