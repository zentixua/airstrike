package ua.zentix.airstrike.warhead;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.SlabType;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Из чего воронка: по нему выбираются обломки, цвет пыли и «фонтан» грунта (debris/sample и fx/spray_pick датапака).
 * Сначала смотрим блок под точкой удара, затем сам поражённый блок — он важнее.
 */
public enum GroundMaterial {
    DIRT(() -> Blocks.DIRT, 0.36f, 0.27f, 0.18f,
            "coarse_dirt", "dirt", "coarse_dirt", "rooted_dirt", "dirt", "grass_block", "coarse_dirt", "gravel", "dirt"),
    STONE(() -> Blocks.STONE, 0.5f, 0.5f, 0.5f,
            "cobblestone", "cobblestone", "gravel", "stone", "cobblestone_slab", "andesite", "gravel", "cobblestone", "tuff"),
    DEEPSLATE(() -> Blocks.DEEPSLATE, 0.3f, 0.3f, 0.32f,
            "cobbled_deepslate", "cobbled_deepslate", "cobbled_deepslate", "deepslate", "cobbled_deepslate_slab", "gravel", "tuff", "cobbled_deepslate", "gravel"),
    SAND(() -> Blocks.SAND, 0.85f, 0.78f, 0.58f,
            "sand", "sand", "sand", "sandstone", "sandstone", "sandstone_slab", "sand", "smooth_sandstone", "gravel"),
    SNOW(() -> Blocks.SNOW_BLOCK, 0.95f, 0.95f, 0.97f,
            "snow_block", "snow_block", "snow_block", "packed_ice", "snow", "dirt", "coarse_dirt", "snow_block", "ice"),
    WOOD(() -> Blocks.OAK_PLANKS, 0.45f, 0.33f, 0.2f,
            "oak_planks", "spruce_planks", "stripped_dark_oak_log", "dark_oak_slab", "oak_slab", "spruce_fence", "oak_planks", "stripped_spruce_log", "dark_oak_planks"),
    BRICK(() -> Blocks.STONE_BRICKS, 0.55f, 0.52f, 0.5f,
            "cracked_stone_bricks", "cracked_stone_bricks", "stone_brick_slab", "cobblestone", "cobblestone", "stone_brick_wall", "gravel", "bricks", "cobblestone_slab"),
    TERRACOTTA(() -> Blocks.TERRACOTTA, 0.6f, 0.38f, 0.28f,
            "terracotta", "terracotta", "terracotta", "clay", "brown_terracotta", "coarse_dirt", "gravel", "terracotta", "dirt"),
    GRAVEL(() -> Blocks.GRAVEL, 0.45f, 0.42f, 0.38f,
            "cobblestone", "gravel", "gravel", "stone", "cobblestone_slab", "andesite", "coarse_dirt", "dirt", "gravel"),
    WATER(() -> Blocks.GRAVEL, 0.8f, 0.85f, 0.9f,
            "gravel", "gravel", "gravel", "sand", "sand", "clay", "clay", "dirt", "gravel");

    private final Supplier<Block> particle;
    public final float r, g, b;
    private final String[] debris;
    private List<BlockState> debrisStates;

    GroundMaterial(Supplier<Block> particle, float r, float g, float b, String... debris) {
        this.particle = particle;
        this.r = r;
        this.g = g;
        this.b = b;
        this.debris = debris;
    }

    /** Блок для частиц «фонтана» грунта. */
    public BlockState particleBlock() {
        return particle.get().defaultBlockState();
    }

    /** Девять обломков грунта (как debris/gN). */
    public List<BlockState> debris() {
        if (debrisStates == null) debrisStates = states(debris);
        return debrisStates;
    }

    public boolean isWater() {
        return this == WATER;
    }

    public static GroundMaterial sample(Level level, BlockPos at) {
        GroundMaterial below = classify(level.getBlockState(at.below()));
        GroundMaterial here = classify(level.getBlockState(at));
        return here != null ? here : below != null ? below : GRAVEL;
    }

    private static GroundMaterial classify(BlockState s) {
        if (s.is(Blocks.WATER)) return WATER;
        if (s.is(BlockTags.TERRACOTTA) || s.is(Blocks.CLAY)) return TERRACOTTA;
        if (s.is(BlockTags.STONE_BRICKS) || s.is(Blocks.COBBLESTONE) || s.is(Blocks.BRICKS) || s.is(Blocks.MOSSY_COBBLESTONE)) return BRICK;
        if (s.is(BlockTags.PLANKS) || s.is(BlockTags.LOGS) || s.is(BlockTags.LEAVES) || s.is(BlockTags.WOODEN_SLABS)) return WOOD;
        if (s.is(BlockTags.SNOW) || s.is(BlockTags.ICE)) return SNOW;
        if (s.is(BlockTags.SAND) || s.is(Blocks.SANDSTONE)) return SAND;
        if (s.is(Blocks.DEEPSLATE) || s.is(Blocks.COBBLED_DEEPSLATE) || s.is(Blocks.TUFF)) return DEEPSLATE;
        if (s.is(BlockTags.BASE_STONE_OVERWORLD) || s.is(Blocks.GRAVEL)) return STONE;
        if (s.is(BlockTags.DIRT) || s.is(Blocks.GRASS_BLOCK)) return DIRT;
        return null;
    }

    /** Блоки по именам; чего нет (другой мод не установлен) — пропускаем. «_slab» кладём нижней половиной. */
    public static List<BlockState> states(String... ids) {
        List<BlockState> out = new ArrayList<>();
        for (String id : ids) {
            ResourceLocation rl = id.contains(":") ? ResourceLocation.parse(id) : ResourceLocation.withDefaultNamespace(id);
            BuiltInRegistries.BLOCK.getOptional(rl).ifPresent(block -> {
                BlockState s = block.defaultBlockState();
                if (s.hasProperty(BlockStateProperties.SLAB_TYPE)) s = s.setValue(BlockStateProperties.SLAB_TYPE, SlabType.BOTTOM);
                out.add(s);
            });
        }
        return List.copyOf(out);
    }

    private static final GroundMaterial[] VALUES = values();

    public static GroundMaterial byId(int id) {
        return id >= 0 && id < VALUES.length ? VALUES[id] : GRAVEL;
    }
}
