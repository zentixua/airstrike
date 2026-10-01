package ua.zentix.airstrike.nuclear.world;

import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Что на диске у чанка за волной ({@link FarLods#wholeOnDisk}): целый — только {@code minecraft:full} без догенерации под
 * нулём; нет на диске, недогенерированный (с блоками или без), старые форматы — одинаково не целые (в мир через
 * {@code FarZone}). Чанки как в копиях мира Артёма «Newisle 2.3.0»: нет на диске, начала структур без блоков, биомы,
 * {@code structure_starts} без пространства имён.
 */
class FarLodsDiskTest {
    private static CompoundTag status(String status) {
        CompoundTag tag = new CompoundTag();
        tag.putString("Status", status);
        return tag;
    }

    @Test
    void onlyFullWithoutRetrogenIsWhole() {
        assertTrue(FarLods.wholeOnDisk(status("minecraft:full")));

        CompoundTag retrogen = status("minecraft:full");
        retrogen.put("below_zero_retrogen", new CompoundTag());
        assertFalse(FarLods.wholeOnDisk(retrogen), "догенерация под нулём");
    }

    @Test
    void absentAndProtoChunksAreTheSame() {
        assertFalse(FarLods.wholeOnDisk(null), "нет на диске");
        assertFalse(FarLods.wholeOnDisk(new CompoundTag()), "заголовок без Status");
        for (String s : new String[] {"minecraft:structure_starts", "minecraft:biomes", "minecraft:carvers", "minecraft:initialize_light", "minecraft:light",
                "structure_starts", "full"}) {
            assertFalse(FarLods.wholeOnDisk(status(s)), s);
        }
        // формат до 1.18: Status внутри Level, сверху его нет
        CompoundTag old = new CompoundTag();
        old.put("Level", status("full"));
        assertFalse(FarLods.wholeOnDisk(old), "формат до 1.18");
    }
}
