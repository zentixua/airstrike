package ua.zentix.airstrike.nuclear.world;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.util.SimpleBitStorage;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.storage.ChunkStorage;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Чанки, которых нет в памяти, — прямо из файлов региона, мимо загрузки в мир (фаза 2 подготовки руин,
 * {@link NuclearPrep}): чтение — поток ввода-вывода чанков ({@code ChunkStorage.read}, тот же, что у загрузки мира:
 * несохранённые изменения он отдаёт из своей очереди записи), обновление старой версии данных и разбор — фоновые потоки
 * руин ({@link RuinWorkers#executor}), как и у ванили (она обновляет и разбирает чанки в фоновых потоках). В потоке
 * сервера остаётся только {@link ChunkShot#fromDisk}: состояния палитр — в таблицу свойств.
 * <p>
 * Берётся только полностью сгенерированный чанк ({@code Status} — {@code minecraft:full}) без незавершённой догенерации
 * под нулём ({@code below_zero_retrogen}) и с картами высот всех видов {@link RuinPlan#HEIGHTMAP_TYPES}; остальное
 * пропускается с причиной ({@link Read#skip}) — его руины строятся после волны, как раньше.
 */
final class DiskShots {
    private static final Codec<PalettedContainer<BlockState>> BLOCK_STATE_CODEC = PalettedContainer.codecRW(Block.BLOCK_STATE_REGISTRY,
            BlockState.CODEC, PalettedContainer.Strategy.SECTION_STATES, Blocks.AIR.defaultBlockState());
    /** Самая старая версия данных, которую обновляет {@link ChunkStorage#upgradeChunkTag} без данных мира (структуры до 1.13). */
    private static final int OLDEST = 1493;

    private DiskShots() {}

    /**
     * Прочитанный чанк: секции (null — воздух), первый свободный по картам {@link RuinPlan#HEIGHTMAP_TYPES}
     * ({@code вид * 256 + столбец}); {@code skip} — почему не годится (тогда остальное пусто).
     */
    record Read(ChunkPos pos, int minY, PalettedContainer<BlockState>[] states, int[] heights, @Nullable String skip) {
        static Read skipped(ChunkPos pos, String why) {
            return new Read(pos, 0, empty(), new int[0], why);
        }

        /** Первый воздух над {@code MOTION_BLOCKING} — для тени светового импульса ({@link RuinContext#putHeights}). */
        int[] motion() {
            int t = ChunkShot.type(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING);
            return java.util.Arrays.copyOfRange(heights, t * 256, t * 256 + 256);
        }

        @SuppressWarnings("unchecked")
        private static PalettedContainer<BlockState>[] empty() {
            return (PalettedContainer<BlockState>[]) new PalettedContainer<?>[0];
        }
    }

    /** Что нужно разбору чанка из мира (снимается в потоке сервера: сам разбор мира не читает). */
    record Format(ChunkMap storage, ResourceKey<Level> dimension, Optional<ResourceKey<MapCodec<? extends ChunkGenerator>>> generator,
                  int minSection, int sections, int minY, int height) {
        static Format of(ServerLevel level) {
            return new Format(level.getChunkSource().chunkMap, level.dimension(), level.getChunkSource().getGenerator().getTypeNameForDataFixer(),
                    level.getMinSection(), level.getSectionsCount(), level.getMinBuildHeight(), level.getHeight());
        }
    }

    /**
     * Прочитать и разобрать чанк (поток сервера отдаёт, ответ — в фоновом потоке руин): чтение — поток ввода-вывода,
     * разбор — {@link RuinWorkers}. Чанка нет на диске — {@code skip}.
     */
    static CompletableFuture<Read> read(Format f, ChunkPos pos) {
        long diag = NukeDiag.readStart();
        return f.storage.read(pos).whenComplete((t, e) -> NukeDiag.readDone(diag)).thenApplyAsync(tag -> tag.map(t -> parse(f, pos, t)).orElseGet(() -> Read.skipped(pos, "нет на диске")),
                RuinWorkers.executor()).exceptionally(e -> Read.skipped(pos, "ошибка чтения: " + e));
    }

    /** Разобрать данные чанка (любой поток). */
    static Read parse(ServerLevel level, ChunkPos pos, CompoundTag tag) {
        return parse(Format.of(level), pos, tag);
    }

    @SuppressWarnings("unchecked")
    static Read parse(Format f, ChunkPos pos, CompoundTag tag) {
        int version = ChunkStorage.getVersion(tag);
        if (version < OLDEST) return Read.skipped(pos, "данные старше 1.13");
        if (version != SharedConstants.getCurrentVersion().getDataVersion().getVersion()) {
            try {
                tag = f.storage.upgradeChunkTag(f.dimension, () -> null, tag, f.generator);
            } catch (RuntimeException e) {
                return Read.skipped(pos, "не обновились данные версии " + version);
            }
        }
        if (ChunkStatus.byName(tag.getString("Status")) != ChunkStatus.FULL) return Read.skipped(pos, "не сгенерирован до конца");
        if (tag.contains("below_zero_retrogen", Tag.TAG_COMPOUND)) return Read.skipped(pos, "догенерация под нулём");
        // карты высот: все виды плана, в формате самой карты (биты на значение — по высоте мира)
        CompoundTag maps = tag.getCompound("Heightmaps");
        int[] heights = new int[RuinPlan.HEIGHTMAP_TYPES.length * 256];
        int bits = Mth.ceillog2(f.height + 1);
        for (int t = 0; t < RuinPlan.HEIGHTMAP_TYPES.length; t++) {
            String key = RuinPlan.HEIGHTMAP_TYPES[t].getSerializationKey();
            if (!maps.contains(key, Tag.TAG_LONG_ARRAY)) return Read.skipped(pos, "нет карты высот " + key);
            SimpleBitStorage data;
            try {
                data = new SimpleBitStorage(bits, 256, maps.getLongArray(key));
            } catch (SimpleBitStorage.InitializationException e) {
                return Read.skipped(pos, "карта высот " + key + " другого размера");
            }
            for (int c = 0; c < 256; c++) heights[t * 256 + c] = data.get(c) + f.minY;
        }
        PalettedContainer<BlockState>[] states = (PalettedContainer<BlockState>[]) new PalettedContainer<?>[f.sections];
        ListTag list = tag.getList("sections", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag section = list.getCompound(i);
            int index = section.getByte("Y") - f.minSection;
            if (index < 0 || index >= f.sections || !section.contains("block_states", Tag.TAG_COMPOUND)) continue;
            Optional<PalettedContainer<BlockState>> parsed = BLOCK_STATE_CODEC.parse(NbtOps.INSTANCE, section.getCompound("block_states")).result();
            if (parsed.isEmpty()) return Read.skipped(pos, "секция " + index + " не разобрана");
            PalettedContainer<BlockState> c = parsed.get();
            if (c.maybeHas(st -> !st.isAir())) states[index] = c;
        }
        return new Read(pos, f.minY, states, heights, null);
    }
}
