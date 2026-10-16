package ua.zentix.incoming.nuclear.flash;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.Mth;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import ua.zentix.incoming.nuclear.model.Fragility;
import ua.zentix.incoming.nuclear.model.WorldNoise;
import ua.zentix.incoming.registry.ModTags;

/**
 * Обугленная вспышкой грань блока (DESIGN-nuke §3.5): степень гари от света на грани — импульса с тенью, умноженного
 * на косинус угла между лучом от шара и нормалью грани, — по материалу. Дерево, шерсть, сено (тег
 * {@code incoming:nuke_chars}) обугливаются до черноты; кладка и бетон ({@code incoming:nuke_soots}) только темнеют —
 * копоть и опалённая побелка. Природный грунт и камень не трогаются: землю выжигает план руин (дёрн, гарь, биомы).
 * <p>
 * Степень — кривая поражения ({@link Fragility}) от света со случайностью места (шум мира и хеш грани), ступенями
 * 1–{@link #LEVELS}; 0 — грань чистая. Одна функция у плана руин (фоном), силуэтов (поток сервера) и клиента
 * (сглаживание по соседним граням).
 * <p>
 * Грань в метке — ключ: место в чанке и сторона ({@link #key}), ступень и короткий хеш блока ({@link #hash}): блок
 * сменился — метка не его (руины, игрок построил своё), и клиент её не рисует.
 */
public final class FlashChar {
    /** Ступеней гари: 1 — чуть опалено, {@code LEVELS} — черно. */
    public static final int LEVELS = 7;
    /** Материал грани: не обугливается, дерево и ткань, кладка и бетон. */
    public static final int NONE = 0, CHARS = 1, SOOTS = 2;
    /**
     * Медиана (кал/см²) и разброс кривой: тёмное дерево обугливается от ~10 кал/см² (Glasstone, табл. 7.40); кладка
     * и бетон не горят — темнеют краска, побелка и пыль на них, по порядку величины от того же света, но слабее
     * ({@link #SOOT_MAX}): у 15 кт стена, которая волну выдержала (меньше ~4 psi), видит не больше ~20 кал/см².
     */
    static final double CHAR_MEDIAN = 10, CHAR_BETA = 0.5, SOOT_MEDIAN = 12, SOOT_BETA = 0.5;
    /** Сильнее всего темнеет кладка: доля от черноты дерева. */
    static final double SOOT_MAX = 0.45;
    /** Случайность грани: шум мира (пятна) и хеш грани (зерно); сумма квадратов весов — 1. */
    public static final double SMOOTH = 0.85, GRAIN = 0.53;

    private FlashChar() {}

    /** Материал состояния для гари (поток сервера или клиента: теги). Только полный непрозрачный куб — у него целые грани. */
    public static int material(BlockState st) {
        if (st.isAir() || !st.canOcclude()) return NONE;
        boolean chars = st.is(ModTags.NUKE_CHARS), soots = !chars && st.is(ModTags.NUKE_SOOTS);
        if (!chars && !soots) return NONE;
        if (!Block.isShapeFullBlock(st.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO))) return NONE;
        return chars ? CHARS : SOOTS;
    }

    /**
     * Ступень гари грани (любой поток).
     *
     * @param q        свет на грани, кал/см² (импульс с тенью × косинус угла падения)
     * @param material {@link #CHARS} или {@link #SOOTS}
     * @param z        случайность грани ~N(0, 1) (шум мира и хеш), усечена до ±{@link Fragility#TAIL}
     * @return 0 — чистая, 1–{@link #LEVELS}
     */
    public static int level(double q, int material, double z) {
        if (q <= 0 || material == NONE) return 0;
        double median = material == CHARS ? CHAR_MEDIAN : SOOT_MEDIAN, beta = material == CHARS ? CHAR_BETA : SOOT_BETA;
        double degree = Fragility.cdf(Math.log(q / median) / beta + Math.max(-Fragility.TAIL, Math.min(Fragility.TAIL, z)));
        if (material == SOOTS) degree *= SOOT_MAX;
        return Math.min(LEVELS, (int) Math.round(degree * LEVELS));
    }

    /** Может ли грань с таким светом потемнеть хоть на ступень при самой большой случайности: нет — шум не нужен. */
    public static boolean possible(double q, int material) {
        return q > 0 && material != NONE && level(q, material, Fragility.TAIL) > 0;
    }

    /** Ступень при любой случайности одна и та же (свет так силён, что всё черно): шум не нужен. */
    public static boolean saturated(double q, int material) {
        return level(q, material, -Fragility.TAIL) == level(q, material, Fragility.TAIL);
    }

    /** Слой шума мира для пятен гари (у плана руин слои 1–5). */
    public static final int LAYER = 6;

    /**
     * Случайность грани ~N(0, 1) (любой поток): шум мира в объёме у центра грани — пятна в несколько блоков — и хеш
     * грани — зерно. Одна и та же у плана руин, силуэтов и клиента.
     */
    public static double chance(WorldNoise noise, int x, int y, int z, Direction face) {
        double smooth = noise.at(x + 0.5 + 0.5 * face.getStepX(), y + 0.5 + 0.5 * face.getStepY(), z + 0.5 + 0.5 * face.getStepZ(), LAYER);
        int h = Mth.murmurHash3Mixer(Long.hashCode(BlockPos.asLong(x, y, z)) * 31 + 53 + face.get3DDataValue());
        return SMOOTH * smooth + GRAIN * Fragility.inverse(((h >>> 8) + 0.5) / (1 << 24));
    }

    /**
     * Косинус угла между нормалью грани и направлением на точку {@code burst} от центра грани; 0 — грань к ней спиной
     * или боком.
     */
    public static double incidence(Vec3 burst, int x, int y, int z, Direction face) {
        double rx = burst.x - (x + 0.5 + 0.5 * face.getStepX()), ry = burst.y - (y + 0.5 + 0.5 * face.getStepY()),
                rz = burst.z - (z + 0.5 + 0.5 * face.getStepZ());
        double len = Math.sqrt(rx * rx + ry * ry + rz * rz);
        if (len < 1e-6) return 1;
        return Math.max(0, (rx * face.getStepX() + ry * face.getStepY() + rz * face.getStepZ()) / len);
    }

    /** Точка у грани для тени и света: центр грани чуть впереди неё. */
    public static Vec3 front(int x, int y, int z, Direction face) {
        return new Vec3(x + 0.5 + 0.55 * face.getStepX(), y + 0.5 + 0.55 * face.getStepY(), z + 0.5 + 0.55 * face.getStepZ());
    }

    // ---------------------------------------------------------------- упаковка

    /** Ключ грани в чанке: x, z (по 4 бита), y + 2048 (12 бит), сторона (3 бита) — 23 бита. */
    public static int key(int lx, int y, int lz, Direction face) {
        return lx & 15 | (lz & 15) << 4 | (y + 2048 & 0xFFF) << 8 | face.get3DDataValue() << 20;
    }

    public static final int KEY_MASK = (1 << 23) - 1;

    /** Метка: ключ, ступень (3 бита) и хеш блока (6 бит). */
    public static int mark(int key, int level, int hash) {
        return key & KEY_MASK | (level & 7) << 23 | (hash & 63) << 26;
    }

    public static int lx(int mark) {
        return mark & 15;
    }

    public static int lz(int mark) {
        return mark >> 4 & 15;
    }

    public static int y(int mark) {
        return (mark >> 8 & 0xFFF) - 2048;
    }

    public static Direction face(int mark) {
        return Direction.from3DDataValue(mark >> 20 & 7);
    }

    public static int level(int mark) {
        return mark >>> 23 & 7;
    }

    public static int hashOf(int mark) {
        return mark >>> 26 & 63;
    }

    /** Короткий хеш блока (не состояния: дверь открыли — метка её): по имени в реестре, одинаковый на сервере и клиенте. */
    public static int hash(Block block) {
        return Mth.murmurHash3Mixer(BuiltInRegistries.BLOCK.getKey(block).hashCode()) >>> 7 & 63;
    }

    /** Место грани в мире по метке чанка {@code cx, cz}. */
    public static BlockPos pos(int mark, int cx, int cz) {
        return new BlockPos(cx << 4 | lx(mark), y(mark), cz << 4 | lz(mark));
    }
}
