package ua.zentix.incoming.nuclear.world;

import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.nuclear.model.Fragility;
import ua.zentix.incoming.registry.ModTags;

import java.util.List;

/**
 * Опалённые биомы: световой импульс сушит и жжёт траву и листву на километры дальше, чем ломает волна, — их цвет
 * (и цвет воды) меняется ступенями: опалённая земля (буро-жёлтая), выжженная (бурая), обугленная (тёмно-бурая). Свои
 * биомы данными ({@code data/incoming/worldgen/biome}), отличаются от равнины только цветами травы, листвы и воды.
 * Клиент сам смешивает оттенки соседних клеток (biome blend), поэтому край плавный и на земле, и на картах.
 * <p>
 * Ступень — на сетке биомов (4×4 блока по горизонтали) по свету на земле клетки и шуму мира ({@link Fragility}): план
 * руин чанка считает её в фоне ({@code RuinPlanner}), запись — при подмене, как {@code /fillbiome}
 * ({@code fillBiomesFromNoise} по секциям: старый биом, кроме клеток ступени), и пакет биомов игрокам, которые видят
 * чанк. Меняются клетки от {@link #BELOW} блоков под землёй вверх: пещеры ниже и их биомы не трогаются. Не меняются
 * биомы с тегом {@link ModTags#NUKE_KEEPS_BIOME} (моря, реки, пещеры), без осадков (пустыня, бесплодные земли, саванна —
 * и так сухие, а равнинный дождь пришёл бы туда навсегда) и снежные (тепло равнины растопило бы снег навсегда); свой
 * биом — только на ступень выше. Смена навсегда: трава в опалённом биоме так и остаётся бурой.
 */
public final class ScorchedBiomes {
    /** Ступени по порядку: 1 — опалённая земля, 3 — обугленная. */
    static final List<ResourceKey<Biome>> LEVELS = List.of(key("singed"), key("scorched"), key("charred"));
    /** Свет на земле (кал/см²), при котором половина клеток уже этой ступени: листва буреет с ~3, трава горит с ~5–6. */
    static final double[] MEDIAN = {3, 6, 12};
    /** Разброс ступеней (логнормальный, {@link Fragility}). */
    static final double BETA = 0.35;
    /** На сколько блоков под поверхностью клетки ещё меняются (смешение биомов у клиента берёт и соседние по высоте). */
    static final int BELOW = 8;
    /** Ниже этого тепла биом снежный ({@code Biome.coldEnoughToSnow} у уровня моря). */
    private static final float SNOWY = 0.15f;

    private ScorchedBiomes() {}

    private static ResourceKey<Biome> key(String name) {
        return ResourceKey.create(Registries.BIOME, Incoming.id(name));
    }

    /** Ступень клетки по свету на её земле и случайности места {@code z} (~N(0, 1)): 0 — не меняется. */
    static int level(double q, double z) {
        for (int i = LEVELS.size(); i >= 1; i--) if (Fragility.hit(q, MEDIAN[i - 1], BETA, z)) return i;
        return 0;
    }

    /** Ступень биома, если он наш, иначе 0. */
    static int levelOf(Holder<Biome> biome) {
        for (int i = 0; i < LEVELS.size(); i++) if (biome.is(LEVELS.get(i))) return i + 1;
        return 0;
    }

    /** Меняется ли биом на ступень {@code level}. */
    static boolean replaces(Holder<Biome> biome, int level) {
        int now = levelOf(biome);
        if (now > 0) return level > now;
        return !biome.is(ModTags.NUKE_KEEPS_BIOME) && biome.value().hasPrecipitation() && biome.value().getBaseTemperature() >= SNOWY;
    }

    /**
     * Ступени чанка из плана: {@code levels} — по столбцам клеток ({@code qz << 2 | qx}, 0 — не меняется), {@code from} —
     * нижний блок (y мира), с которого клетки столбца меняются.
     */
    record Cells(byte[] levels, int[] from) {
        boolean same(@Nullable Cells o) {
            return o != null && java.util.Arrays.equals(levels, o.levels) && java.util.Arrays.equals(from, o.from);
        }

        @Override
        public String toString() {
            return java.util.Arrays.toString(levels);
        }
    }

    /**
     * Записать ступени в секции чанка: в мире — в потоке сервера, в копии чанка с диска ({@link FarLods}) — в её потоке.
     * {@code minSection} — номер секции {@code sections[0]} по высоте.
     *
     * @return сколько клеток изменено
     */
    static int write(Registry<Biome> registry, LevelChunkSection[] sections, int minSection, Cells cells) {
        @SuppressWarnings("unchecked")
        Holder<Biome>[] ours = (Holder<Biome>[]) new Holder<?>[LEVELS.size()];
        for (int i = 0; i < ours.length; i++) {
            ours[i] = registry.getHolder(LEVELS.get(i)).orElse(null);
            // данных мода нет (датапак убрал биомы): цвет не меняется
            if (ours[i] == null) return 0;
        }
        int changed = 0;
        @SuppressWarnings("unchecked")
        Holder<Biome>[] next = (Holder<Biome>[]) new Holder<?>[64];
        for (int i = 0; i < sections.length; i++) {
            LevelChunkSection s = sections[i];
            int y0 = (minSection + i) << 4;
            boolean any = false;
            for (int qy = 0; qy < 4; qy++) {
                for (int qz = 0; qz < 4; qz++) {
                    for (int qx = 0; qx < 4; qx++) {
                        Holder<Biome> was = s.getNoiseBiome(qx, qy, qz);
                        int column = qz << 2 | qx, level = cells.levels[column];
                        Holder<Biome> now = was;
                        // клетка целиком ниже начала столбца — пещера под землёй, не трогается
                        if (level > 0 && y0 + (qy << 2) + 3 >= cells.from[column] && replaces(was, level)) {
                            now = ours[level - 1];
                            any = true;
                            changed++;
                        }
                        next[qy << 4 | qz << 2 | qx] = now;
                    }
                }
            }
            if (any) s.fillBiomesFromNoise((x, y, z, sampler) -> next[y << 4 | z << 2 | x], null, 0, 0, 0);
        }
        return changed;
    }

    /** Ступени в чанк мира (поток сервера) и пакет биомов игрокам, которые его видят: true — что-то изменилось. */
    static boolean write(ServerLevel level, LevelChunk chunk, Cells cells) {
        if (write(level.registryAccess().registryOrThrow(Registries.BIOME), chunk.getSections(), chunk.getMinSection(), cells) == 0) return false;
        chunk.setUnsaved(true);
        level.getChunkSource().chunkMap.resendBiomesForChunks(List.of(chunk));
        return true;
    }
}
