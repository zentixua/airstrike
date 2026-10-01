package ua.zentix.airstrike.compat;

import com.mojang.logging.LogUtils;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import org.slf4j.Logger;

/**
 * Руины, записанные прямо в секции ({@code RuinPlan}), — физике аппаратов Sable. Sable 2.0.5 держит копию блоков мира
 * у аппаратов (секции в сцене Rapier, {@code PhysicsChunkTicketManager}: секции рядом с аппаратами, до 20 тиков после
 * того, как аппарат ушёл) и обновляет её только из {@code LevelChunk.setBlockState} (свой миксин
 * {@code plot.LevelChunkMixin} → {@code SableCommonEvents.handleBlockChange}); запись в секцию мимо него аппарат
 * не видит — он врезался бы в стоявший там дом. Поэтому места плана у аппаратов сообщаются ему тем же вызовом.
 * Вызов читает шесть соседей места — план ставится, только когда соседние чанки загружены.
 * <p>
 * Сам Sable — только в {@link Hook}: он грузится при первом вызове, а вызов идёт, только если Sable стоит.
 */
public final class SableTerrain {
    private static final Logger LOG = LogUtils.getLogger();
    /** Насколько от аппарата (по габаритам) Sable может держать секции мира, блоки: с запасом на 20 тиков полёта. */
    private static final double NEAR = 64;
    private static Boolean present;
    private static boolean broken;

    private SableTerrain() {}

    public static boolean present() {
        if (present == null) present = ModList.get() != null && ModList.get().isLoaded("sable");
        return present && !broken;
    }

    /** Есть ли у чанка аппараты, чья физика может держать его секции. */
    public static boolean watched(ServerLevel level, LevelChunk chunk) {
        if (!present()) return false;
        var pos = chunk.getPos();
        Vec3 centre = new Vec3(pos.getMiddleBlockX(), (level.getMinBuildHeight() + level.getMaxBuildHeight()) / 2.0, pos.getMiddleBlockZ());
        return !SubLevels.near(level, centre, 8 + NEAR + (level.getMaxBuildHeight() - level.getMinBuildHeight()) / 2.0).isEmpty();
    }

    /** Место чанка сменилось мимо {@code LevelChunk.setBlockState}: Sable обновит свою копию. */
    public static void changed(ServerLevel level, LevelChunk chunk, int x, int y, int z, BlockState old, BlockState now) {
        if (broken || old == now) return;
        try {
            Hook.changed(level, chunk, x, y, z, old, now);
        } catch (RuntimeException | LinkageError e) {
            broken = true;
            LOG.error("Sable: изменения руин физике аппаратов больше не сообщаются", e);
        }
    }

    private static final class Hook {
        static void changed(ServerLevel level, LevelChunk chunk, int x, int y, int z, BlockState old, BlockState now) {
            dev.ryanhcode.sable.SableCommonEvents.handleBlockChange(level, chunk, x, y, z, old, now);
        }
    }
}
