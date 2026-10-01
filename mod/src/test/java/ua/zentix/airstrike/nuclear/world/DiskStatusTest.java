package ua.zentix.airstrike.nuclear.world;

import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Целый ли чанк на диске ({@link DiskStatus#whole}) — одно правило у плана с диска, зоны за волной и LOD вдали: целый —
 * полная генерация (имя с пространством имён или без, как в старых сохранениях) без догенерации под нулём; нет на диске,
 * недогенерированный (с блоками или без), формат до 1.18 — одинаково не целые. Чанки как в копиях мира Артёма
 * «Newisle 2.3.0»: нет на диске, начала структур без блоков, биомы, {@code structure_starts} без пространства имён.
 */
class DiskStatusTest {
    private static CompoundTag status(String status) {
        CompoundTag tag = new CompoundTag();
        tag.putString("Status", status);
        return tag;
    }

    @Test
    void fullWithoutRetrogenIsWhole() {
        assertTrue(DiskStatus.whole(status("minecraft:full")));
        // старое сохранение: ваниль читает имя без пространства имён как minecraft:full и грузит чанк без генерации
        assertTrue(DiskStatus.whole(status("full")), "full без пространства имён");

        CompoundTag retrogen = status("minecraft:full");
        retrogen.put("below_zero_retrogen", new CompoundTag());
        assertFalse(DiskStatus.whole(retrogen), "догенерация под нулём");
    }

    @Test
    void absentAndProtoChunksAreTheSame() {
        assertFalse(DiskStatus.whole(null), "нет на диске");
        assertFalse(DiskStatus.whole(new CompoundTag()), "заголовок без Status");
        for (String s : new String[] {"minecraft:structure_starts", "minecraft:biomes", "minecraft:carvers", "minecraft:initialize_light", "minecraft:light",
                "minecraft:spawn", "structure_starts", "other:full", ""}) {
            assertFalse(DiskStatus.whole(status(s)), s);
        }
        // формат до 1.18: Status внутри Level, сверху его нет
        CompoundTag old = new CompoundTag();
        old.put("Level", status("full"));
        assertFalse(DiskStatus.whole(old), "формат до 1.18");
    }
}
