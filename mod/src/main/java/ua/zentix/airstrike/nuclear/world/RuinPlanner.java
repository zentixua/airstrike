package ua.zentix.airstrike.nuclear.world;

import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import ua.zentix.airstrike.AirstrikeConfig;
import ua.zentix.airstrike.nuclear.Detonation;
import ua.zentix.airstrike.nuclear.model.ThermalModel;
import ua.zentix.airstrike.registry.ModBlocks;

import java.util.ArrayList;
import java.util.List;

/**
 * Руины одного чанка от одного подрыва — заранее, без изменений в мире ({@link RuinPlan}): новые секции чанка копиями
 * старых, что с каждым столбцом сделала волна и свет. Правила столбца (DESIGN-nuke §3.2–3.4):
 * <ul>
 * <li>надземное (всё выше природного грунта: постройки, деревья, растения) ломается по давлению; выше над землёй
 * давление больше (обтекание и отражение: +4 % на блок), крыша и верхний этаж (два верхних блока) — в полтора раза;</li>
 * <li>где рухнула постройка (у земли от 5 psi), остаются руины — завал из щебня высотой до 4 блоков по тому, сколько
 * рухнуло; в огненном шаре у земли — ничего;</li>
 * <li>ствол на грунте валится от эпицентра целиком (в пределах чанка);</li>
 * <li>грунт волна не трогает (подвалы и бункеры уцелеют), от 8 psi сдирает дёрн; верхний блок меняется от света,
 * если видит огненный шар: выжженная трава, растаявший снег, тринитит и вскипевшая вода в шаре.</li>
 * </ul>
 * Всё случайное — из хеша координат: план одного места всегда один и тот же, и план, построенный во время полёта МБР,
 * совпадает с построенным в момент подрыва. Читает только сам чанк (он загружен целиком) и карту высот для тени света
 * ({@link ThermalShadow}: только готовые чанки).
 */
public final class RuinPlanner {
    /** Сколько блоков вниз от верха столбца ищем природный грунт. */
    private static final int MAX_DEPTH = 96;
    /** С какого давления волна сдирает дёрн. */
    static final double STRIP_PSI = 8;
    /** С какого давления у земли постройка оставляет завал. */
    static final double RUBBLE_PSI = 5;
    /** Рост давления с высотой над землёй, на блок. */
    private static final double PER_BLOCK_UP = 0.04;
    /** Крыша и верхний этаж: давление × 1.5 для двух верхних блоков постройки. */
    private static final double ROOF = 1.5;
    private static final int ROOF_BLOCKS = 2;

    private static final BlockState AIR = Blocks.AIR.defaultBlockState();
    private static final BlockState[] STONE_RUBBLE = {Blocks.GRAVEL.defaultBlockState(), Blocks.COBBLESTONE.defaultBlockState(),
            Blocks.ANDESITE.defaultBlockState(), Blocks.GRAVEL.defaultBlockState(), Blocks.TUFF.defaultBlockState()};
    private static final BlockState[] WOOD_RUBBLE = {Blocks.COARSE_DIRT.defaultBlockState(), Blocks.GRAVEL.defaultBlockState(),
            Blocks.COARSE_DIRT.defaultBlockState(), Blocks.ROOTED_DIRT.defaultBlockState()};

    private RuinPlanner() {}

    /** Хеш места и соли — число в [0, 1). */
    static double hash(int x, int y, int z, int salt) {
        int h = Mth.murmurHash3Mixer(Long.hashCode(BlockPos.asLong(x, y, z)) * 31 + salt);
        return (h >>> 8) / (double) (1 << 24);
    }

    /** План руин чанка (чанк загружен целиком, поток сервера). */
    public static RuinPlan plan(ServerLevel level, Detonation d, LevelChunk chunk) {
        Sections s = new Sections(chunk);
        boolean blockDamage = AirstrikeConfig.SERVER.nukeBlockDamage.get();
        boolean treeFall = AirstrikeConfig.SERVER.nukeTreeFall.get();
        boolean fires = AirstrikeConfig.SERVER.nukeFires.get();
        int x0 = chunk.getPos().getMinBlockX(), z0 = chunk.getPos().getMinBlockZ();
        int minY = chunk.getMinBuildHeight();
        List<long[]> trees = new ArrayList<>(); // {base, height, direction}
        List<BlockState> treeLogs = new ArrayList<>();
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int lz = 0; lz < 16; lz++) {
            for (int lx = 0; lx < 16; lx++) {
                int x = x0 + lx, z = z0 + lz;
                int top = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, lx, lz);
                int bottom = Math.max(minY, top - MAX_DEPTH);
                int ground = Integer.MIN_VALUE;
                for (int y = top; y >= bottom; y--) {
                    BlockState st = s.get(lx, y, lz);
                    if (!st.isAir() && BlockResponse.of(st).kind() == BlockResponse.Kind.GROUND) {
                        ground = y;
                        break;
                    }
                }
                int base = ground != Integer.MIN_VALUE ? ground : bottom;
                double groundPsi = d.psi(new Vec3(x + 0.5, base + 1, z + 0.5));
                int removed = 0, wood = 0, structure = 0;
                if (blockDamage) {
                    for (int y = top; y > base; y--) {
                        BlockState st = s.get(lx, y, lz);
                        if (st.isAir()) continue;
                        BlockResponse r = BlockResponse.of(st);
                        if (r.kind() == BlockResponse.Kind.NONE) continue;
                        double psi = groundPsi * (1 + PER_BLOCK_UP * (y - base)) * (structure < ROOF_BLOCKS && r.kind() == BlockResponse.Kind.BREAK ? ROOF : 1);
                        structure++;
                        if (r.kind() == BlockResponse.Kind.LOG && treeFall && psi >= r.thresholdPsi()) {
                            // комель: ствол на грунте валится целиком (брёвна кладутся вторым проходом, когда все столбцы уже прошли)
                            int b = y;
                            while (b - 1 > base && s.get(lx, b - 1, lz).is(BlockTags.LOGS)) b--;
                            if (b - 1 == ground) {
                                int height = 0;
                                for (int yy = b; height < 24 && yy < chunk.getMaxBuildHeight() && s.get(lx, yy, lz).is(BlockTags.LOGS); yy++, height++) {
                                    s.set(lx, yy, lz, AIR);
                                }
                                double dx = x - d.burst().x, dz = z - d.burst().z;
                                Direction dir = Math.abs(dx) > Math.abs(dz) ? (dx > 0 ? Direction.EAST : Direction.WEST) : (dz > 0 ? Direction.SOUTH : Direction.NORTH);
                                trees.add(new long[]{BlockPos.asLong(x, b, z), height, dir.get3DDataValue()});
                                treeLogs.add(st);
                                y = b; // ниже комля — грунт
                                continue;
                            }
                        }
                        if (!r.breaksAt(psi, Mth.murmurHash3Mixer(Long.hashCode(BlockPos.asLong(x, y, z))))) continue;
                        s.set(lx, y, lz, AIR);
                        // завал — из стен и перекрытий: трава, листва, стекло и шерсть его не дают
                        if (r.kind() == BlockResponse.Kind.BREAK && st.getBlock().defaultDestroyTime() >= 1) {
                            removed++;
                            if (st.is(BlockTags.MINEABLE_WITH_AXE)) wood++;
                        }
                        if (fires && r.kind() == BlockResponse.Kind.LEAVES && psi < 3 && ColumnScar.lit(level, d, m.set(x, y, z)) >= 10) s.fire(x, y, z, 1.0);
                    }
                }
                if (ground == Integer.MIN_VALUE) continue;
                Vec3 at = new Vec3(x + 0.5, ground + 0.5, z + 0.5);
                boolean inFireball = d.surface() && Math.hypot(at.x - d.burst().x, at.z - d.burst().z) < d.fireballRadius() * 0.8
                        && at.y > d.groundY() - d.fireballRadius();
                if (blockDamage && !inFireball && groundPsi >= RUBBLE_PSI && removed >= 3) rubble(s, lx, ground, lz, x, z, removed, wood);
                // скоростной напор сдирает дёрн и траву: от 8 psi — голая земля, даже в тени
                if (blockDamage && groundPsi >= STRIP_PSI) strip(s, lx, ground, lz, x, z);
                scorch(level, d, s, lx, ground, lz, x, z, inFireball, groundPsi, fires);
            }
        }
        for (int i = 0; i < trees.size(); i++) lay(s, trees.get(i), treeLogs.get(i));
        return s.finish(level, chunk);
    }

    /** Завал на месте рухнувшей постройки: высота — по тому, сколько рухнуло, материал — по тому, из чего. */
    private static void rubble(Sections s, int lx, int ground, int lz, int x, int z, int removed, int wood) {
        int height = Math.min(4, Math.max(1, Math.round(removed * 0.12f)));
        BlockState[] kind = wood * 2 > removed ? WOOD_RUBBLE : STONE_RUBBLE;
        for (int i = 1; i <= height; i++) {
            int y = ground + i;
            if (y >= s.maxY() || !s.get(lx, y, lz).isAir()) break;
            s.set(lx, y, lz, kind[(int) (hash(x, y, z, 17) * kind.length)]);
        }
    }

    private static void strip(Sections s, int lx, int ground, int lz, int x, int z) {
        BlockState st = s.get(lx, ground, lz);
        if (st.is(Blocks.GRASS_BLOCK) || st.is(Blocks.PODZOL) || st.is(Blocks.MYCELIUM) || st.is(Blocks.MOSS_BLOCK)) {
            s.set(lx, ground, lz, (hash(x, ground, z, 3) < 1 / 3.0 ? Blocks.DIRT : Blocks.COARSE_DIRT).defaultBlockState());
        }
    }

    /** Световой импульс на верхнем блоке грунта: пожары, выгоревшая трава, растаявший снег, тринитит у шара. */
    private static void scorch(ServerLevel level, Detonation d, Sections s, int lx, int ground, int lz, int x, int z, boolean inFireball,
                               double groundPsi, boolean fires) {
        BlockPos top = new BlockPos(x, ground, z);
        double q = ColumnScar.lit(level, d, top);
        if (q < ThermalModel.IGNITE) return;
        BlockState st = s.get(lx, ground, lz);
        if (inFireball) {
            // внутри огненного шара у земли: песок сплавляется, вода вскипает
            if (st.is(BlockTags.SAND)) s.set(lx, ground, lz, ModBlocks.TRINITITE.get().defaultBlockState());
            for (int i = 1; i <= 3 && ground + i < s.maxY(); i++) {
                if (!s.get(lx, ground + i, lz).getFluidState().isSource()) break;
                s.set(lx, ground + i, lz, AIR);
            }
            return;
        }
        if (q >= 15) {
            if (st.is(Blocks.GRASS_BLOCK) || st.is(Blocks.PODZOL) || st.is(Blocks.MYCELIUM)) {
                s.set(lx, ground, lz, (hash(x, ground, z, 5) < 0.5 ? Blocks.COARSE_DIRT : Blocks.DIRT).defaultBlockState());
            }
            if (st.is(Blocks.SNOW_BLOCK)) s.set(lx, ground, lz, Blocks.WATER.defaultBlockState());
        }
        if (!fires || ground + 1 >= s.maxY()) return;
        // волна гасит часть огня там, где давление ≥ 2 psi
        double chance = Math.min(1, q / 20) * (groundPsi >= 2 ? 0.5 : 1);
        BlockState now = s.get(lx, ground, lz);
        if (s.get(lx, ground + 1, lz).isAir() && (now.isFlammable(level, top, Direction.UP) || now.is(Blocks.GRASS_BLOCK) || now.is(BlockTags.DIRT))) s.fire(x, ground + 1, z, chance);
    }

    /** Поваленный ствол: брёвна лёжа по направлению от эпицентра, по поверхности. */
    private static void lay(Sections s, long[] tree, BlockState log) {
        BlockPos base = BlockPos.of(tree[0]);
        Direction dir = Direction.from3DDataValue((int) tree[2]);
        BlockState lying = log.hasProperty(RotatedPillarBlock.AXIS) ? log.setValue(BlockStateProperties.AXIS, dir.getAxis()) : log;
        for (int i = 0; i < tree[1]; i++) {
            BlockPos p = base.relative(dir, i);
            if (p.getX() >> 4 != s.chunkX || p.getZ() >> 4 != s.chunkZ) {
                // дальше по ходу ствола — соседний чанк: брёвна кладутся туда через мир при подмене
                s.outside.add(p.asLong());
                s.outsideState.add(lying);
                continue;
            }
            int lx = p.getX() & 15, lz = p.getZ() & 15;
            // первый непроходимый блок сверху вниз от уровня комля (+8: склон вверх)
            int y = Math.min(s.maxY() - 1, base.getY() + 8);
            while (y > s.minY && s.get(lx, y, lz).canBeReplaced()) y--;
            if (y + 1 >= s.maxY() || !s.get(lx, y + 1, lz).canBeReplaced()) break;
            s.set(lx, y + 1, lz, lying);
        }
    }

    /**
     * Секции чанка для плана: чтение — из копии, если секция уже менялась, иначе из самого чанка; запись — в копию.
     * Блоки с блок-сущностью и места POI запоминаются: их меняет медленный путь ({@link ColumnScar#replace}).
     */
    static final class Sections {
        final LevelChunk chunk;
        final LevelChunkSection[] original;
        final LevelChunkSection[] copies;
        final int minY, chunkX, chunkZ;
        /** Места, которые меняются через мир (блок-сущность, POI): упакованная позиция → итоговое состояние. */
        final Long2ObjectLinkedOpenHashMap<BlockState> slow = new Long2ObjectLinkedOpenHashMap<>();
        /** Пожары: позиция и вероятность. */
        final LongArrayList fires = new LongArrayList();
        final it.unimi.dsi.fastutil.doubles.DoubleArrayList fireChance = new it.unimi.dsi.fastutil.doubles.DoubleArrayList();
        /** Брёвна поваленных деревьев в соседних чанках: место (высота — по поверхности при подмене) и состояние. */
        final LongArrayList outside = new LongArrayList();
        final List<BlockState> outsideState = new ArrayList<>();
        /** Изменённые места по столбцам: упакованные позиции (для света). */
        final LongArrayList changed = new LongArrayList();

        Sections(LevelChunk chunk) {
            this.chunk = chunk;
            this.original = chunk.getSections();
            this.copies = new LevelChunkSection[original.length];
            this.minY = chunk.getMinBuildHeight();
            this.chunkX = chunk.getPos().x;
            this.chunkZ = chunk.getPos().z;
        }

        int maxY() {
            return chunk.getMaxBuildHeight();
        }

        BlockState get(int lx, int y, int lz) {
            int i = (y - minY) >> 4;
            if (i < 0 || i >= original.length) return AIR;
            LevelChunkSection sec = copies[i] != null ? copies[i] : original[i];
            return sec.getBlockState(lx, y & 15, lz);
        }

        void set(int lx, int y, int lz, BlockState state) {
            int i = (y - minY) >> 4;
            if (i < 0 || i >= original.length) return;
            LevelChunkSection copy = copies[i];
            if (copy == null) {
                copy = copies[i] = new LevelChunkSection(original[i].getStates().copy(), original[i].getBiomes());
            }
            BlockState old = original[i].getBlockState(lx, y & 15, lz);
            copy.setBlockState(lx, y & 15, lz, state, false);
            long pos = BlockPos.asLong((chunkX << 4) + lx, y, (chunkZ << 4) + lz);
            changed.add(pos);
            if (old.hasBlockEntity() || PoiTypes.forState(old).isPresent()) slow.put(pos, state);
        }

        void fire(int x, int y, int z, double chance) {
            if (chance <= 0) return;
            fires.add(BlockPos.asLong(x, y, z));
            fireChance.add(chance);
        }

        RuinPlan finish(ServerLevel level, LevelChunk chunk) {
            return RuinPlan.of(level, chunk, original, copies, slow, fires, fireChance, changed, outside, outsideState);
        }
    }
}
