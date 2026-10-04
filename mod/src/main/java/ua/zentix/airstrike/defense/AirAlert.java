package ua.zentix.airstrike.defense;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import ua.zentix.airstrike.registry.ModSounds;
import ua.zentix.airstrike.target.Sides;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Воздушная тревога от радара: когда радар стороны видит новую чужую цель, игроки этой стороны в том же мире получают
 * строку «Воздушная тревога» — что летит, откуда (сторона света от самого игрока) и как далеко, — и короткий сигнал.
 * Одна цель — одна тревога на сторону, сколько бы радаров её ни видели; цель, которую радары стороны не видели
 * {@link #FORGET} тиков, забывается — появившись снова, она снова тревога.
 */
public final class AirAlert {
    /** Цель, которую радары стороны не видят столько тиков, забыта. */
    static final int FORGET = 200;
    /** Сигнал стороне — не чаще раза в столько тиков (строки — на каждую новую цель). */
    static final int SOUND_GAP = 100;
    private static final String[] COMPASS = {"n", "ne", "e", "se", "s", "sw", "w", "nw"};

    private AirAlert() {}

    /**
     * Отметить, что радар стороны {@code side} видит {@code tracks}, и объявить тревогу о новых чужих.
     *
     * @param owner чей радар: его сторона ({@link Sides}) получает тревогу; null — ничей, тревоги нет
     */
    public static void sweep(ServerLevel level, @Nullable UUID owner, @Nullable String side, List<Radar.Track> tracks) {
        if (owner == null || side == null) return;
        DefenseWorld world = DefenseWorld.get(level);
        long now = level.getGameTime();
        Map<UUID, Long> seen = world.alerted(side);
        List<Radar.Track> fresh = new ArrayList<>();
        for (Radar.Track t : tracks) {
            if (t.hostile() && seen.put(t.id(), now) == null) fresh.add(t);
        }
        seen.values().removeIf(t -> now - t > FORGET);
        if (fresh.isEmpty()) return;
        boolean sound = world.alertSound(side, now, SOUND_GAP);
        MinecraftServer server = level.getServer();
        for (ServerPlayer p : level.players()) {
            if (!Sides.friendly(server, owner, p.getUUID())) continue;
            p.sendSystemMessage(message(p.position(), fresh));
            if (sound) p.playNotifySound(ModSounds.DESIGNATOR_LOCK.get(), SoundSource.AMBIENT, 1.0f, 0.7f);
        }
    }

    /** Строка тревоги для игрока в {@code listener}: ближайшая к нему из новых целей (и сколько их всего). */
    static Component message(Vec3 listener, List<Radar.Track> fresh) {
        Radar.Track near = fresh.getFirst();
        for (Radar.Track t : fresh) {
            if (t.position().distanceToSqr(listener) < near.position().distanceToSqr(listener)) near = t;
        }
        Vec3 d = near.position().subtract(listener);
        Component what = near.projectile().getType().getDescription();
        Component dir = Component.translatable("airstrike.compass." + compass(d.x, d.z));
        String km = String.format(Locale.ROOT, "%.1f", Math.hypot(d.x, d.z) / 1000);
        MutableComponent line = fresh.size() == 1
                ? Component.translatable("airstrike.sam.alert", what, dir, km)
                : Component.translatable("airstrike.sam.alert_many", String.valueOf(fresh.size()), what, dir, km);
        return line.withStyle(ChatFormatting.RED);
    }

    /** Сторона света по сдвигу (север — −Z, восток — +X): «n», «ne» … «nw». */
    static String compass(double dx, double dz) {
        double deg = Math.toDegrees(Math.atan2(dx, -dz));
        return COMPASS[Math.floorMod((int) Math.round(deg / 45), 8)];
    }
}
