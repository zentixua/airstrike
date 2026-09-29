package ua.zentix.airstrike.registry;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredRegister;
import ua.zentix.airstrike.Airstrike;
import ua.zentix.airstrike.grid.SubstationBlock;
import ua.zentix.airstrike.grid.block.UnlitBlock;
import ua.zentix.airstrike.grid.block.UnlitBulbBlock;
import ua.zentix.airstrike.grid.block.UnlitEndRodBlock;
import ua.zentix.airstrike.grid.block.UnlitLampBlock;
import ua.zentix.airstrike.grid.block.UnlitLanternBlock;
import ua.zentix.airstrike.grid.block.UnlitLightBlock;
import ua.zentix.airstrike.grid.block.UnlitPillarBlock;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Supplier;

public final class ModBlocks {
    public static final DeferredRegister.Blocks REGISTER = DeferredRegister.createBlocks(Airstrike.MOD_ID);

    /** Тринитит: песок, сплавленный огненным шаром наземного ядерного взрыва в зеленоватое стекло. */
    public static final DeferredBlock<Block> TRINITITE = REGISTER.registerSimpleBlock("trinitite", BlockBehaviour.Properties.of()
            .mapColor(MapColor.COLOR_GREEN)
            .strength(1.5f, 6.0f)
            .requiresCorrectToolForDrops()
            .sound(SoundType.GLASS));

    /** Трансформаторная подстанция: узел сети; удар по ней обесточивает район вокруг ({@link ua.zentix.airstrike.grid.Blackouts}). */
    public static final DeferredBlock<SubstationBlock> SUBSTATION = REGISTER.register("substation", () -> new SubstationBlock(
            BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_GRAY)
                    .strength(5.0f, 6.0f)
                    .requiresCorrectToolForDrops()
                    .sound(SoundType.METAL)
                    .noOcclusion()));

    /** Лампа сети и её обесточенный двойник. */
    public record UnlitPair(Supplier<Block> lit, DeferredBlock<? extends Block> unlit) {}

    private static final List<UnlitPair> UNLIT_PAIRS = new ArrayList<>();
    /** Все пары «лампа — двойник» ({@link ua.zentix.airstrike.grid.GridLights}); факелы, свечи, костры и печи — не от сети. */
    public static final List<UnlitPair> UNLIT = Collections.unmodifiableList(UNLIT_PAIRS);

    static {
        unlit("glowstone", () -> Blocks.GLOWSTONE, UnlitBlock::new);
        unlit("sea_lantern", () -> Blocks.SEA_LANTERN, UnlitBlock::new);
        unlit("shroomlight", () -> Blocks.SHROOMLIGHT, UnlitBlock::new);
        unlit("ochre_froglight", () -> Blocks.OCHRE_FROGLIGHT, UnlitPillarBlock::new);
        unlit("verdant_froglight", () -> Blocks.VERDANT_FROGLIGHT, UnlitPillarBlock::new);
        unlit("pearlescent_froglight", () -> Blocks.PEARLESCENT_FROGLIGHT, UnlitPillarBlock::new);
        unlit("lantern", () -> Blocks.LANTERN, UnlitLanternBlock::new);
        unlit("soul_lantern", () -> Blocks.SOUL_LANTERN, UnlitLanternBlock::new);
        unlit("end_rod", () -> Blocks.END_ROD, UnlitEndRodBlock::new);
        unlit("redstone_lamp", () -> Blocks.REDSTONE_LAMP, UnlitLampBlock::new);
        unlit("copper_bulb", () -> Blocks.COPPER_BULB, UnlitBulbBlock::new);
        unlit("exposed_copper_bulb", () -> Blocks.EXPOSED_COPPER_BULB, UnlitBulbBlock::new);
        unlit("weathered_copper_bulb", () -> Blocks.WEATHERED_COPPER_BULB, UnlitBulbBlock::new);
        unlit("oxidized_copper_bulb", () -> Blocks.OXIDIZED_COPPER_BULB, UnlitBulbBlock::new);
        unlit("waxed_copper_bulb", () -> Blocks.WAXED_COPPER_BULB, UnlitBulbBlock::new);
        unlit("waxed_exposed_copper_bulb", () -> Blocks.WAXED_EXPOSED_COPPER_BULB, UnlitBulbBlock::new);
        unlit("waxed_weathered_copper_bulb", () -> Blocks.WAXED_WEATHERED_COPPER_BULB, UnlitBulbBlock::new);
        unlit("waxed_oxidized_copper_bulb", () -> Blocks.WAXED_OXIDIZED_COPPER_BULB, UnlitBulbBlock::new);
        unlit("light", () -> Blocks.LIGHT, UnlitLightBlock::new);
    }

    private ModBlocks() {}

    /**
     * Двойник — полная копия свойств лампы (прочность, звук, карта), только без света; добыча — таблица лампы
     * ({@code ofFullCopy} таблицу по имени блока не переносит: без {@code lootFrom} у двойника была бы своя, пустая).
     * Свойства берутся при регистрации: ванильные блоки к этому времени уже есть.
     */
    private static void unlit(String name, Supplier<Block> lit, BiFunction<BlockBehaviour.Properties, Supplier<Block>, Block> factory) {
        DeferredBlock<Block> unlit = REGISTER.register("unlit_" + name,
                () -> factory.apply(BlockBehaviour.Properties.ofFullCopy(lit.get()).lightLevel(s -> 0).lootFrom(lit), lit));
        UNLIT_PAIRS.add(new UnlitPair(lit, unlit));
    }
}
