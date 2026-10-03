package ua.zentix.airstrike.nuclear.world;

import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Что за чанк на диске ({@link DiskStatus#of}): целый — полная генерация (имя с пространством имён или без, как в старых
 * сохранениях) без догенерации под нулём; с окончательными блоками — и чанк мира 1.17 после обновления (статус «пусто»,
 * догенерация под нулём до полной генерации 1.17) или ещё в формате до 1.18; нет на диске и недогенерированный (с блоками
 * или без) — одинаково нет. Чанки как в копиях мира Артёма «Newisle 2.3.0»: нет на диске, начала структур без блоков,
 * биомы, {@code structure_starts} без пространства имён.
 */
class DiskStatusTest {
    private static CompoundTag status(String status) {
        CompoundTag tag = new CompoundTag();
        tag.putString("Status", status);
        return tag;
    }

    /** Как оставляет чанк обновление мира 1.17 ({@code ChunkHeightAndBiomeFix}): статус «пусто», цель догенерации. */
    private static CompoundTag retrogen(String target) {
        CompoundTag tag = status("minecraft:empty");
        CompoundTag retrogen = new CompoundTag();
        retrogen.putString("target_status", target);
        retrogen.putLongArray("missing_bedrock", new long[4]);
        tag.put("below_zero_retrogen", retrogen);
        return tag;
    }

    @Test
    void fullWithoutRetrogenIsWhole() {
        assertEquals(DiskStatus.State.WHOLE, DiskStatus.of(status("minecraft:full")));
        // старое сохранение: ваниль читает имя без пространства имён как minecraft:full и грузит чанк без генерации
        assertEquals(DiskStatus.State.WHOLE, DiskStatus.of(status("full")), "full без пространства имён");
    }

    @Test
    void finishedRetrogenHasFinalBlocks() {
        // полная генерация 1.17 — heightmaps, после переименования статусов — spawn
        for (String s : new String[] {"minecraft:spawn", "minecraft:heightmaps", "minecraft:light", "spawn", "heightmaps"}) {
            assertEquals(DiskStatus.State.FINAL, DiskStatus.of(retrogen(s)), s);
            assertTrue(DiskStatus.of(retrogen(s)).blocksFinal(), s);
        }
        // загружался до соседей и сохранён раньше цели: блоки над нулём те же
        CompoundTag partly = retrogen("minecraft:spawn");
        partly.putString("Status", "minecraft:carvers");
        assertEquals(DiskStatus.State.FINAL, DiskStatus.of(partly), "статус ниже цели догенерации");
        // украшения соседей ещё не встали: они пишут и в этот чанк
        for (String s : new String[] {"minecraft:features", "minecraft:carvers", "minecraft:noise", "minecraft:structure_starts", ""}) {
            assertEquals(DiskStatus.State.PARTIAL, DiskStatus.of(retrogen(s)), s);
        }
    }

    @Test
    void absentAndProtoChunksAreTheSame() {
        assertEquals(DiskStatus.State.PARTIAL, DiskStatus.of(null), "нет на диске");
        assertEquals(DiskStatus.State.PARTIAL, DiskStatus.of(new CompoundTag()), "заголовок без Status");
        for (String s : new String[] {"minecraft:structure_starts", "minecraft:biomes", "minecraft:carvers", "minecraft:initialize_light", "minecraft:light",
                "minecraft:spawn", "structure_starts", "other:full", ""}) {
            assertEquals(DiskStatus.State.PARTIAL, DiskStatus.of(status(s)), s);
            assertFalse(DiskStatus.of(status(s)).blocksFinal(), s);
        }
    }

    @Test
    void oldFormatByItsStatus() {
        // формат до 1.18: Status внутри Level, сверху его нет; что выйдет при загрузке, зависит от измерения — не целый
        CompoundTag old = new CompoundTag();
        old.put("Level", status("full"));
        assertEquals(DiskStatus.State.FINAL, DiskStatus.of(old), "формат до 1.18, полная генерация");
        old.put("Level", status("features"));
        assertEquals(DiskStatus.State.PARTIAL, DiskStatus.of(old), "формат до 1.18, без украшений соседей");
    }
}
