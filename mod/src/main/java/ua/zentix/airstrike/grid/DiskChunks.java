package ua.zentix.airstrike.grid;

import com.mojang.serialization.Codec;
import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.storage.ChunkStorage;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.ticks.LevelChunkTicks;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Копия чанка с диска — для LOD Distant Horizons вдали, без загрузки чанка в мир (загрузка тянет за собой
 * генерацию соседей и нагружает поток сервера). Разбор — как у ванили ({@code ChunkSerializer}): секции блоков
 * и биомов теми же кодеками; копия — отдельный {@link LevelChunk}, которого нет в мире (так же DH сам строит LOD
 * из файлов мира). Всё здесь — вне потока сервера.
 */
final class DiskChunks {
    private static final Codec<PalettedContainer<BlockState>> BLOCK_STATES = PalettedContainer.codecRW(
            Block.BLOCK_STATE_REGISTRY, BlockState.CODEC, PalettedContainer.Strategy.SECTION_STATES, Blocks.AIR.defaultBlockState());

    private DiskChunks() {}

    /** Папка регионов измерения: читать чанк можно, только если его файл региона есть (чтение ванили создало бы пустой). */
    static Path regionFolder(ServerLevel level) {
        return DimensionType.getStorageFolder(level.dimension(), level.getServer().getWorldPath(LevelResource.ROOT)).resolve("region");
    }

    static boolean regionExists(Path folder, ChunkPos pos) {
        return Files.isRegularFile(folder.resolve("r." + pos.getRegionX() + "." + pos.getRegionZ() + ".mca"));
    }

    /**
     * Копия чанка с лампами, переведёнными к состоянию сети; null — чанк не полный, другой версии игры (без
     * обновления данных читать его нельзя) или в нём нет ни ламп сети, ни погашенных (LOD менять незачем).
     * Копия нужна и тогда, когда переводить нечего: чанк, погашенный в LOD копией, при возврате света получает
     * копию того, что лежит на диске.
     */
    @Nullable
    static LevelChunk copy(ServerLevel level, ChunkPos pos, CompoundTag tag, boolean dark) {
        if (ChunkStorage.getVersion(tag) != SharedConstants.getCurrentVersion().getDataVersion().getVersion()) return null;
        if (ChunkStatus.byName(tag.getString("Status")) != ChunkStatus.FULL) return null;
        Registry<Biome> biomes = level.registryAccess().registryOrThrow(Registries.BIOME);
        Codec<PalettedContainerRO<Holder<Biome>>> biomeCodec = PalettedContainer.codecRO(biomes.asHolderIdMap(), biomes.holderByNameCodec(),
                PalettedContainer.Strategy.SECTION_BIOMES, biomes.getHolderOrThrow(Biomes.PLAINS));
        LevelChunkSection[] sections = new LevelChunkSection[level.getSectionsCount()];
        ListTag list = tag.getList("sections", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag s = list.getCompound(i);
            int index = level.getSectionIndexFromSectionY(s.getByte("Y"));
            if (index < 0 || index >= sections.length || !s.contains("block_states", Tag.TAG_COMPOUND)) continue;
            PalettedContainer<BlockState> states = BLOCK_STATES.parse(NbtOps.INSTANCE, s.getCompound("block_states")).result().orElse(null);
            if (states == null) return null;
            PalettedContainerRO<Holder<Biome>> biome = s.contains("biomes", Tag.TAG_COMPOUND)
                    ? biomeCodec.parse(NbtOps.INSTANCE, s.getCompound("biomes")).result().orElse(null) : null;
            if (biome == null) {
                biome = new PalettedContainer<>(biomes.asHolderIdMap(), biomes.getHolderOrThrow(Biomes.PLAINS), PalettedContainer.Strategy.SECTION_BIOMES);
            }
            sections[index] = new LevelChunkSection(states, biome);
        }
        if (!ChunkLights.any(sections)) return null;
        ChunkLights.apply(sections, dark);
        LevelChunk chunk = new LevelChunk(level, pos, UpgradeData.EMPTY, new LevelChunkTicks<>(), new LevelChunkTicks<>(), 0L, sections, null, null);
        Heightmap.primeHeightmaps(chunk, ChunkStatus.FULL.heightmapsAfter());
        chunk.setLightCorrect(tag.getBoolean("isLightOn"));
        return chunk;
    }
}
