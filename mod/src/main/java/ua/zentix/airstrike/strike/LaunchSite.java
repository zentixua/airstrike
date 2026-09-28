package ua.zentix.airstrike.strike;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import ua.zentix.airstrike.entity.LauncherEntity;
import ua.zentix.airstrike.nuclear.world.Terrain;
import ua.zentix.airstrike.registry.ModEntities;
import ua.zentix.airstrike.util.Local;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Где поставить пусковую: позади и сбоку от стреляющего, на ровной твёрдой земле под открытым небом, только
 * в готовых чанках (ничего не грузим ради пуска). Своя пусковая того же оружия в {@link #REUSE_RADIUS} — берём её:
 * залп идёт с одной установки. Больше {@link #MAX_PER_OWNER} установок у игрока не бывает — старая убирается.
 */
public final class LaunchSite {
    public static final double REUSE_RADIUS = 96;
    public static final int MAX_PER_OWNER = 3;

    /** Места относительно игрока: [назад, влево] в блоках, по порядку предпочтения. */
    private static final double[][] CANDIDATES = {
            {18, 11}, {18, -11}, {24, 0}, {13, 17}, {13, -17}, {30, 13}, {30, -13}, {4, 22}, {4, -22}, {38, 0}, {24, 24}, {24, -24}
    };

    private LaunchSite() {}

    /** Своя пусковая этого оружия рядом с игроком. */
    @Nullable
    public static LauncherEntity existing(ServerLevel level, ServerPlayer player, WeaponType weapon) {
        UUID id = player.getUUID();
        AABB box = player.getBoundingBox().inflate(REUSE_RADIUS, 64, REUSE_RADIUS);
        return level.getEntitiesOfClass(LauncherEntity.class, box, l -> l.isAlive() && l.weapon() == weapon && id.equals(l.ownerId()))
                .stream().min(Comparator.comparingDouble(l -> l.distanceToSqr(player))).orElse(null);
    }

    /** Место под новую пусковую или null (игрок в воде, в пещере без неба над ним поблизости, мир не готов). */
    @Nullable
    public static Vec3 find(ServerLevel level, ServerPlayer player) {
        float yaw = player.getYRot();
        for (double[] c : CANDIDATES) {
            Vec3 off = Local.offset(yaw, 0, c[1], 0, -c[0]);
            Vec3 p = player.position().add(off);
            Vec3 site = check(level, Mth.floor(p.x), Mth.floor(p.z));
            if (site != null) return site;
        }
        return null;
    }

    /** Ровно (±1 блок в квадрате 5×5), твёрдо, не вода, над головой пусто (листва тоже мешает). */
    @Nullable
    private static Vec3 check(ServerLevel level, int x, int z) {
        for (int dx = -3; dx <= 3; dx += 3) {
            for (int dz = -3; dz <= 3; dz += 3) {
                if (!Terrain.ready(level, new BlockPos(x + dx, 0, z + dz))) return null;
            }
        }
        int y = Terrain.height(level, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        if (y <= level.getMinBuildHeight() + 1) return null;
        for (int dx = -2; dx <= 2; dx += 2) {
            for (int dz = -2; dz <= 2; dz += 2) {
                int h = Terrain.height(level, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x + dx, z + dz);
                if (Math.abs(h - y) > 1) return null;
                if (Terrain.height(level, Heightmap.Types.MOTION_BLOCKING, x + dx, z + dz) > h + 1) return null;
            }
        }
        BlockPos below = new BlockPos(x, y - 1, z);
        BlockState ground = level.getBlockState(below);
        if (!ground.getFluidState().isEmpty() || !ground.isFaceSturdy(level, below, Direction.UP)) return null;
        return new Vec3(x + 0.5, y, z + 0.5);
    }

    /** Новая пусковая; лишние старые установки игрока убираются. */
    public static LauncherEntity deploy(ServerLevel level, Vec3 site, float yaw, WeaponType weapon, ServerPlayer owner) {
        List<LauncherEntity> mine = new ArrayList<>();
        for (var e : level.getEntities(ModEntities.LAUNCHER.get(), l -> owner.getUUID().equals(l.ownerId()))) mine.add(e);
        mine.sort(Comparator.comparingLong(LauncherEntity::deployedAt));
        for (int i = 0; i <= mine.size() - MAX_PER_OWNER; i++) mine.get(i).discard();
        LauncherEntity l = LauncherEntity.create(level, site, yaw, weapon, owner.getUUID());
        level.addFreshEntity(l);
        return l;
    }
}
