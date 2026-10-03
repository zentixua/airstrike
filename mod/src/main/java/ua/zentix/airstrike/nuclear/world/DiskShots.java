package ua.zentix.airstrike.nuclear.world;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
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
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;
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
 * Берётся только чанк с окончательными блоками ({@link DiskStatus.State#blocksFinal}: и чанк мира 1.17 с догенерацией
 * под нулём); остальное пропускается с причиной ({@link Read#skip}) — его руины строятся после волны, как раньше. Карты
 * высот {@link RuinPlan#HEIGHTMAP_TYPES}, которых на диске нет или они не того размера (карты из редакторов: не все
 * карты у Greenfield — у 295855 чанков из 295936, у Newisle — у 23595 из 23644 целых), досчитываются по блокам, как при
 * загрузке у ванили ({@link Read#primed}).
 */
final class DiskShots {
    private static final Codec<PalettedContainer<BlockState>> BLOCK_STATE_CODEC = PalettedContainer.codecRW(Block.BLOCK_STATE_REGISTRY,
            BlockState.CODEC, PalettedContainer.Strategy.SECTION_STATES, Blocks.AIR.defaultBlockState());
    /** Цена чанка без карт высот на диске для окна ({@link Read#cost}): не больше 4 досчётов за один {@link RuinContext#requestWindow}. */
    static final int PRIME_COST = 6;

    private DiskShots() {}

    /**
     * Прочитанный чанк: секции (null — воздух), первый свободный по картам {@link RuinPlan#HEIGHTMAP_TYPES}
     * ({@code вид * 256 + столбец}); {@code unprimed} — карты, которых на диске не было (бит {@code 1 << вид}: их значения
     * ещё не посчитаны, {@link #primed}); {@code skip} — почему не годится (тогда остальное пусто).
     */
    record Read(ChunkPos pos, int minY, PalettedContainer<BlockState>[] states, int[] heights, int unprimed, @Nullable String skip) {
        static Read skipped(ChunkPos pos, String why) {
            return new Read(pos, 0, empty(), new int[0], 0, why);
        }

        /**
         * Карты высот, которых не было на диске, — по блокам, как их досчитывает ваниль при загрузке
         * ({@code Heightmap.primeHeightmaps}: сверху вниз до первого непрозрачного для карты блока; нет такого — низ мира).
         * Поток сервера: свойства состояний — из таблицы руин ({@code props}). Цена — в среднем 0,6 мс (до 1,4 мс) на чанк,
         * где все секции до верха мира непустые, а столбцы почти все воздух (замер в облаке 03.10.2026: столб стекла до 319
         * над плоским миром), обычно десятки микросекунд; сколько таких чанков берёт поток сервера за раз — {@link #cost}.
         */
        Read primed(Blast.PropsView props) {
            if (unprimed == 0) return this;
            int[] out = heights.clone();
            int types = RuinPlan.HEIGHTMAP_TYPES.length;
            // подряд в столбце почти всегда одно и то же состояние (воздух, вода): его карты — без поиска в таблице
            BlockState last = null;
            int opaque = 0;
            for (int c = 0; c < 256; c++) {
                int left = unprimed;
                for (int t = 0; t < types; t++) if ((left & 1 << t) != 0) out[t * 256 + c] = minY;
                for (int i = states.length - 1; i >= 0 && left != 0; i--) {
                    if (states[i] == null) continue;
                    for (int y = 15; y >= 0 && left != 0; y--) {
                        BlockState st = states[i].get(c & 15, y, c >> 4);
                        if (st != last) {
                            last = st;
                            Blast.Props p = props.get(st);
                            opaque = 0;
                            for (int t = 0; t < types; t++) if (p.opaque(t)) opaque |= 1 << t;
                        }
                        int hit = left & opaque;
                        if (hit == 0) continue;
                        for (int t = 0; t < types; t++) if ((hit & 1 << t) != 0) out[t * 256 + c] = minY + (i << 4) + y + 1;
                        left &= ~hit;
                    }
                }
            }
            return new Read(pos, minY, states, out, 0, null);
        }

        /**
         * Сколько поток сервера платит за этот чанк в {@link RuinContext#requestWindow}, в чанках с картами высот на диске:
         * досчёт карт ({@link #primed}) в худшем случае в десятки раз дороже снимка: 25 таких за раз — десятки миллисекунд
         * в одном тике, по 4 — до 4,3 мс (замер 03.10.2026).
         */
        int cost() {
            return unprimed == 0 ? 1 : PRIME_COST;
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
                  int minSection, int sections, int minY, int height, Registry<Biome> biomes) {
        static Format of(ServerLevel level) {
            return new Format(level.getChunkSource().chunkMap, level.dimension(), level.getChunkSource().getGenerator().getTypeNameForDataFixer(),
                    level.getMinSection(), level.getSectionsCount(), level.getMinBuildHeight(), level.getHeight(),
                    level.registryAccess().registryOrThrow(Registries.BIOME));
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
    static Read parse(Format f, ChunkPos pos, CompoundTag raw) {
        Checked checked = check(f, raw);
        if (checked.skip != null) return Read.skipped(pos, checked.skip);
        CompoundTag tag = checked.tag;
        // карты высот: все виды плана, в формате самой карты (биты на значение — по высоте мира); нет карты или она
        // не того размера — посчитается по блокам (Read.primed), как у ванили при загрузке
        CompoundTag maps = tag.getCompound("Heightmaps");
        int[] heights = new int[RuinPlan.HEIGHTMAP_TYPES.length * 256];
        int bits = Mth.ceillog2(f.height + 1), unprimed = 0;
        for (int t = 0; t < RuinPlan.HEIGHTMAP_TYPES.length; t++) {
            String key = RuinPlan.HEIGHTMAP_TYPES[t].getSerializationKey();
            SimpleBitStorage data = null;
            if (maps.contains(key, Tag.TAG_LONG_ARRAY)) {
                try {
                    data = new SimpleBitStorage(bits, 256, maps.getLongArray(key));
                } catch (SimpleBitStorage.InitializationException e) {
                    // не того размера: ваниль так же отбрасывает её и считает по блокам
                }
            }
            if (data == null) {
                unprimed |= 1 << t;
                continue;
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
        return new Read(pos, f.minY, states, heights, unprimed, null);
    }

    /** Данные чанка, обновлённые до текущей версии, или почему чанк не годится ({@code skip}). */
    private record Checked(CompoundTag tag, @Nullable String skip) {}

    private static Checked check(Format f, CompoundTag tag) {
        // как на диске, до обновления версии: то же правило, что у заголовка (DiskStatus.scan)
        if (!DiskStatus.of(tag).blocksFinal()) return new Checked(tag, "не догенерирован на диске");
        int version = ChunkStorage.getVersion(tag);
        if (version != SharedConstants.getCurrentVersion().getDataVersion().getVersion()) {
            try {
                tag = f.storage.upgradeChunkTag(f.dimension, () -> null, tag, f.generator);
            } catch (RuntimeException e) {
                return new Checked(tag, "не обновились данные версии " + version);
            }
        }
        return new Checked(tag, null);
    }

    /**
     * Чанк целиком — секции с блоками и биомами, как их разбирает ваниль ({@code ChunkSerializer.read}), но без мира:
     * без POI, света и карт высот. Для копии чанка с руинами в LOD Distant Horizons ({@link FarLods}). Чтение — поток
     * ввода-вывода, разбор — {@link RuinWorkers}; {@code skip} — почему чанк не годится.
     */
    record Sections(ChunkPos pos, LevelChunkSection[] sections, @Nullable String skip) {}

    static CompletableFuture<Sections> readSections(Format f, ChunkPos pos) {
        return f.storage.read(pos).thenApplyAsync(tag -> tag.map(t -> parseSections(f, pos, t)).orElseGet(() -> new Sections(pos, new LevelChunkSection[0], "нет на диске")),
                RuinWorkers.executor()).exceptionally(e -> new Sections(pos, new LevelChunkSection[0], "ошибка чтения: " + e));
    }

    static Sections parseSections(Format f, ChunkPos pos, CompoundTag raw) {
        Checked checked = check(f, raw);
        if (checked.skip != null) return new Sections(pos, new LevelChunkSection[0], checked.skip);
        Codec<PalettedContainerRO<Holder<Biome>>> biomeCodec = PalettedContainer.codecRO(f.biomes.asHolderIdMap(), f.biomes.holderByNameCodec(),
                PalettedContainer.Strategy.SECTION_BIOMES, f.biomes.getHolderOrThrow(Biomes.PLAINS));
        LevelChunkSection[] sections = new LevelChunkSection[f.sections];
        ListTag list = checked.tag.getList("sections", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag section = list.getCompound(i);
            int index = section.getByte("Y") - f.minSection;
            if (index < 0 || index >= f.sections) continue;
            PalettedContainer<BlockState> states;
            if (section.contains("block_states", Tag.TAG_COMPOUND)) {
                Optional<PalettedContainer<BlockState>> parsed = BLOCK_STATE_CODEC.parse(NbtOps.INSTANCE, section.getCompound("block_states")).result();
                if (parsed.isEmpty()) return new Sections(pos, new LevelChunkSection[0], "секция " + index + " не разобрана");
                states = parsed.get();
            } else {
                states = new PalettedContainer<>(Block.BLOCK_STATE_REGISTRY, Blocks.AIR.defaultBlockState(), PalettedContainer.Strategy.SECTION_STATES);
            }
            PalettedContainerRO<Holder<Biome>> biomes;
            if (section.contains("biomes", Tag.TAG_COMPOUND)) {
                Optional<PalettedContainerRO<Holder<Biome>>> parsed = biomeCodec.parse(NbtOps.INSTANCE, section.getCompound("biomes")).result();
                if (parsed.isEmpty()) return new Sections(pos, new LevelChunkSection[0], "биомы секции " + index + " не разобраны");
                biomes = parsed.get();
            } else {
                biomes = new PalettedContainer<>(f.biomes.asHolderIdMap(), f.biomes.getHolderOrThrow(Biomes.PLAINS), PalettedContainer.Strategy.SECTION_BIOMES);
            }
            sections[index] = new LevelChunkSection(states, biomes);
        }
        // секций, которых нет в данных, нет и в мире: воздух с биомом по умолчанию, как у ванили
        for (int i = 0; i < sections.length; i++) if (sections[i] == null) sections[i] = new LevelChunkSection(f.biomes);
        return new Sections(pos, sections, null);
    }
}
