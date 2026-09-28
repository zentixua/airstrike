package ua.zentix.airstrike.registry;

import net.minecraft.core.registries.Registries;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.Block;
import ua.zentix.airstrike.Airstrike;

/** Теги (data/airstrike/tags). Классы пород для бурения перенесены из датапака как есть. */
public final class ModTags {
    /** Сквозь это летим и бурим без затрат: воздух, трава, цветы, снег-слой, огонь. */
    public static final TagKey<Block> PASSABLE = block("passable");
    /** Бомба может выгрызать при бурении. */
    public static final TagKey<Block> DRILLABLE = block("drillable");
    public static final TagKey<Block> BB_SOFT = block("bb_soft");
    public static final TagKey<Block> BB_SOIL = block("bb_soil");
    public static final TagKey<Block> BB_ROCK = block("bb_rock");
    public static final TagKey<Block> BB_DEEP = block("bb_deep");
    public static final TagKey<Block> BB_HARD = block("bb_hard");
    public static final TagKey<Block> BB_VHARD = block("bb_vhard");
    public static final TagKey<Block> BB_STOP = block("bb_stop");
    public static final TagKey<Block> BB_FLUID = block("bb_fluid");
    /** Бьются ударной волной. */
    public static final TagKey<Block> SHATTERS = block("shatters");
    /** Ядерный удар: природный грунт (волна не трогает, только свет — верхний слой — и воронка). */
    public static final TagKey<Block> NUKE_GROUND = block("nuke_ground");
    /** Ядерный удар: ломается при 0.5–1 psi. */
    public static final TagKey<Block> NUKE_FRAGILE = block("nuke_fragile");
    /** Ядерный удар: лёгкие постройки, 3–5 psi. */
    public static final TagKey<Block> NUKE_LIGHT = block("nuke_light");
    /** Ядерный удар: кладка, 10–15 psi. */
    public static final TagKey<Block> NUKE_MASONRY = block("nuke_masonry");

    /** Прозрачны для прицела: «клей», сиденья, служебные сущности Create/Sable. */
    public static final TagKey<EntityType<?>> AIM_IGNORE = TagKey.create(Registries.ENTITY_TYPE, Airstrike.id("aim_ignore"));

    private ModTags() {}

    private static TagKey<Block> block(String name) {
        return TagKey.create(Registries.BLOCK, Airstrike.id(name));
    }
}
