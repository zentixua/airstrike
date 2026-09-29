package ua.zentix.airstrike.grid;

import com.mojang.serialization.Codec;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.status.ChunkType;
import net.minecraft.world.level.chunk.storage.ChunkSerializer;
import net.neoforged.neoforge.event.level.ChunkDataEvent;
import ua.zentix.airstrike.Airstrike;
import ua.zentix.airstrike.registry.ModAttachments;

/**
 * На диске двойников не бывает: погашенная лампа — только состояние чанка в памяти. Сохраняется чанк с настоящими
 * лампами (и пересветом при загрузке), а загруженный в тёмном квартале гаснет сразу, в палитре, ещё до того, как
 * станет частью мира. Так мир на диске — всегда обычный мир: его можно открыть без мода, старой версией мода,
 * из резервной копии, и после сбоя сервера ничего не теряется.
 */
public final class ChunkSaves {
    /** Как у ванили ({@code ChunkSerializer}): тот же кодек блоков секции. */
    private static final Codec<PalettedContainer<BlockState>> BLOCK_STATES = PalettedContainer.codecRW(
            Block.BLOCK_STATE_REGISTRY, BlockState.CODEC, PalettedContainer.Strategy.SECTION_STATES, Blocks.AIR.defaultBlockState());
    private static final String BLOCK_TICKS = "block_ticks", TWIN_PREFIX = Airstrike.MOD_ID + ":unlit_";
    private static boolean failureLogged, foreignLogged;

    private ChunkSaves() {}

    /**
     * Тег сохраняемого чанка: секции с двойниками — заново из копии, где двойники снова лампы; свет — пересчитать
     * при загрузке (сохранённый посчитан для погашенных). Тег, устроенный не как у ванили (другой конвейер, другие
     * секции), не трогается: секция переписывается, только если блоки в теге — ровно блоки этой секции в чанке.
     */
    public static void onSave(ChunkDataEvent.Save e) {
        if (!(e.getChunk() instanceof LevelChunk chunk) || !(e.getLevel() instanceof ServerLevel)) return;
        if (!ChunkLights.anyUnlit(chunk.getSections())) return;
        CompoundTag tag = e.getData();
        if (!tag.contains(ChunkSerializer.SECTIONS_TAG, Tag.TAG_LIST)) return;
        ListTag list = tag.getList(ChunkSerializer.SECTIONS_TAG, Tag.TAG_COMPOUND);
        try {
            boolean changed = false;
            for (int i = 0; i < list.size(); i++) {
                CompoundTag s = list.getCompound(i);
                if (!s.contains("Y", Tag.TAG_ANY_NUMERIC) || !s.contains("block_states", Tag.TAG_COMPOUND)) continue;
                int index = chunk.getSectionIndexFromSectionY(s.getByte("Y"));
                if (index < 0 || index >= chunk.getSectionsCount()) continue;
                LevelChunkSection section = chunk.getSection(index);
                if (!ChunkLights.needs(section, false)) continue;
                // тег секции — ровно то, что ваниль только что записала из этой секции (кодирование дешевле разбора)
                if (!s.getCompound("block_states").equals(BLOCK_STATES.encodeStart(NbtOps.INSTANCE, section.getStates()).getOrThrow())) {
                    // тег писал не ванильный конвейер для этого чанка — не наш; двойники этой секции уйдут на диск как есть
                    if (!foreignLogged) {
                        foreignLogged = true;
                        Airstrike.LOG.warn("Блэкаут: секция {} чанка {} в сохранении не та, что в памяти (другой мод меняет тег) — погашенные лампы в ней сохранены как есть",
                                s.getByte("Y"), chunk.getPos());
                    }
                    continue;
                }
                PalettedContainer<BlockState> states = section.getStates().copy();
                ChunkLights.apply(states, false);
                s.put("block_states", BLOCK_STATES.encodeStart(NbtOps.INSTANCE, states).getOrThrow());
                changed = true;
            }
            if (changed) {
                tag.putBoolean(ChunkSerializer.IS_LIGHT_ON_TAG, false);
                // запланированные тики двойников (редстоун рядом, соседи) — уже тики лампы: на диске им не место
                if (tag.contains(BLOCK_TICKS, Tag.TAG_LIST)) tag.getList(BLOCK_TICKS, Tag.TAG_COMPOUND).removeIf(t -> t instanceof CompoundTag c && c.getString("i").startsWith(TWIN_PREFIX));
            }
        } catch (RuntimeException ex) {
            if (!failureLogged) {
                failureLogged = true;
                Airstrike.LOG.error("Блэкаут: чанк {} сохранён с погашенными лампами — вернуть их в тег не вышло", chunk.getPos(), ex);
            }
        }
    }

    /**
     * Чанк с диска (поток сервера, до того как он станет частью мира): в тёмном квартале лампы гаснут прямо в палитре
     * — без обновлений соседей, без Sable, без мигания. Их свет пришёл с диска вместе с чанком: его убирает
     * {@link BlackoutWorld}, когда загружены и соседи. Двойник в светлом квартале (сохранение без этого перехвата,
     * двойник, сдвинутый поршнем или аппаратом) снова лампа, и свет пересчитывается при загрузке.
     */
    public static void onLoad(ChunkDataEvent.Load e) {
        if (e.getType() != ChunkType.LEVELCHUNK || !(e.getChunk() instanceof LevelChunk chunk) || !(e.getLevel() instanceof ServerLevel level)) return;
        PowerGrid grid = PowerGrid.get(level);
        LevelChunkSection[] sections = chunk.getSections();
        boolean marked = chunk.hasData(ModAttachments.GRID_DARK);
        if (!marked && grid.outages().isEmpty() && !ChunkLights.anyUnlit(sections)) return;
        ChunkPos pos = chunk.getPos();
        boolean dark = grid.dark(pos.x, pos.z, level.getGameTime());
        LongArrayList stale = new LongArrayList();
        int[] lit = {0};
        for (int i = 0; i < sections.length; i++) {
            LevelChunkSection section = sections[i];
            if (section == null || !ChunkLights.needs(section, dark)) continue;
            int x0 = pos.getMinBlockX(), y0 = SectionPos.sectionToBlockCoord(chunk.getSectionYFromSectionIndex(i)), z0 = pos.getMinBlockZ();
            ChunkLights.scan(section, dark, (x, y, z, to) -> {
                section.setBlockState(x, y, z, to, false);
                if (dark) stale.add(BlockPos.asLong(x0 + x, y0 + y, z0 + z));
                else lit[0]++;
            });
        }
        if (lit[0] > 0) chunk.setLightCorrect(false);
        BlackoutWorld.get(level).staleLight(pos, stale);
        if (dark && ChunkLights.anyUnlit(sections)) chunk.setData(ModAttachments.GRID_DARK, true);
        else if (marked) chunk.removeData(ModAttachments.GRID_DARK);
    }
}
