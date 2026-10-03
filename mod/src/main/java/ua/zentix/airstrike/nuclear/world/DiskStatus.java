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

import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Что за чанк на диске — одно правило на весь мод. Блоки окончательные ({@link State#blocksFinal}) — по нему строится
 * план руин с диска ({@link DiskShots}, фаза 2 подготовки {@link NuclearPrep}) и копия с руинами для LOD вдали
 * ({@link FarLods}: иначе чанк берёт в мир {@link FarZone}). Целый ({@link State#WHOLE}) — зона за волной берёт его
 * в мир ({@link NuclearPrep}: чанк, чьё окно руин целое), ничего не генерируя. Разница — чанки мира 1.17, обновлённого
 * до 1.21: блоки на месте, но при загрузке ваниль догенерирует низ мира под нулём. Их план с диска верен (сверка —
 * по старым состояниям мест плана, низ мира и бедрок в них не входят), до волны их не грузит зона, а после — грузит
 * игрок или LOD; план держится до конца зоны. До 03.10.2026 такие чанки были «не целыми», а на старых картах их почти
 * все (копии миров Артёма 03.10.2026: Zearth — 77974 из 86392 чанков на диске, Greenfield — 295718 из 295936): план
 * с диска они не получали, а LOD вдали грузил их в мир с генерацией.
 */
final class DiskStatus {
    /** Имя полной генерации в поле {@code Status}. */
    private static final ResourceLocation FULL = ResourceLocation.withDefaultNamespace("full");
    /**
     * Статусы, после которых блоки чанка окончательные: украшения соседей в радиусе 1 (они пишут и в этот чанк) уже
     * поставлены — свет у ванили ждёт их ({@code ChunkPyramid}). У чанков 1.17 полная генерация в
     * {@code below_zero_retrogen} записана как {@code heightmaps}, после переименования — {@code spawn}.
     */
    private static final Set<ResourceLocation> FINISHED = Set.of(ResourceLocation.withDefaultNamespace("light"),
            ResourceLocation.withDefaultNamespace("spawn"), ResourceLocation.withDefaultNamespace("heightmaps"), FULL);

    /** Что за чанк на диске. */
    enum State {
        /** Нет на диске, недогенерированный (кольца у края исследованного мира, начала структур), ошибка чтения. */
        PARTIAL,
        /**
         * Блоки окончательные, но в мир без генерации не войдёт: догенерация под нулём ({@code below_zero_retrogen})
         * или формат до 1.18 ({@code Status} внутри {@code Level}: что выйдет при обновлении, зависит от измерения).
         */
        FINAL,
        /** Полная генерация без догенерации: ваниль грузит его, ничего не генерируя. */
        WHOLE;

        /** Годится ли для плана руин и копии LOD с диска. */
        boolean blocksFinal() {
            return this != PARTIAL;
        }
    }

    private DiskStatus() {}

    /**
     * Что за чанк — по данным, как на диске, до обновления версии. Имя статуса без пространства имён (как в старых
     * сохранениях) — из {@code minecraft}, как читает ваниль ({@code ChunkStatus.byName}). Реестр статусов не нужен:
     * проверяется и в фоновых потоках.
     *
     * @param fields данные чанка или только поля {@link #fields}; {@code null} — чанка нет
     */
    static State of(@Nullable CompoundTag fields) {
        if (fields == null) return State.PARTIAL;
        if (fields.contains("below_zero_retrogen", Tag.TAG_COMPOUND))
            return finished(fields.getCompound("below_zero_retrogen").getString("target_status")) ? State.FINAL : State.PARTIAL;
        if (fields.contains("Status", Tag.TAG_STRING))
            return FULL.equals(ResourceLocation.tryParse(fields.getString("Status"))) ? State.WHOLE : State.PARTIAL;
        if (fields.contains("Level", Tag.TAG_COMPOUND))
            return finished(fields.getCompound("Level").getString("Status")) ? State.FINAL : State.PARTIAL;
        return State.PARTIAL;
    }

    private static boolean finished(String status) {
        // имя с недопустимыми знаками не разбирается (null): не статус ванили
        ResourceLocation name = ResourceLocation.tryParse(status);
        return name != null && FINISHED.contains(name);
    }

    /** Поля, по которым {@link #of} решает, — без разбора всего чанка ({@code chunkScanner}). */
    private static CollectFields fields() {
        return new CollectFields(new FieldSelector(StringTag.TYPE, "Status"), new FieldSelector(CompoundTag.TYPE, "below_zero_retrogen"),
                new FieldSelector("Level", StringTag.TYPE, "Status"));
    }

    /**
     * Что за чанк на диске — по его заголовку, в потоке ввода-вывода чанков ({@code chunkScanner}: не загружая и не
     * разбирая чанк). Ответ приходит в потоке ввода-вывода; ошибка чтения — {@link State#PARTIAL}.
     */
    static CompletableFuture<State> scan(ServerLevel level, ChunkPos pos) {
        CollectFields fields = fields();
        return level.getChunkSource().chunkMap.chunkScanner().scanChunk(pos, fields)
                .handle((v, e) -> e == null ? of(fields.getResult() instanceof CompoundTag tag ? tag : null) : State.PARTIAL);
    }
}
