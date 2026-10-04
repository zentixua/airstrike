package ua.zentix.airstrike.target;

import dev.ryanhcode.sable.companion.SubLevelAccess;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import org.jetbrains.annotations.Nullable;
import ua.zentix.airstrike.AirstrikeConfig;
import ua.zentix.airstrike.compat.SubLevels;
import ua.zentix.airstrike.entity.StrikeProjectile;
import ua.zentix.airstrike.registry.ModAttachments;
import ua.zentix.airstrike.util.Terrain;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Разведка: что видела каждая сторона ({@link Sides}: игрок или его команда {@code /team}) — глазами игроков, камерой
 * их снарядов, радаром. Чужой игрок или аппарат Sable — цель пульта, пока его сторона видит его или видела не дольше
 * {@code sight_memory} назад; карта показывает чужих так же — там, где их видели последний раз. Своих видно всегда.
 * <p>
 * Глаза: раз в {@link #SCAN_PERIOD} тиков каждый игрок осматривается — чужой замечен, если он в поле зрения (конус
 * {@link #FIELD_DEGREES}° вокруг взгляда — экран с запасом) и {@link Sight#sees} его видит. Камера снаряда: пока игрок
 * смотрит глазами своего снаряда ({@link #watch}), тот же осмотр идёт и из снаряда по взгляду камеры. Радар и другие датчики сообщают сами ({@link #spot}). Состояние мира, не сохраняется.
 */
public final class Sightings {
    /** Раз в столько тиков игроки осматриваются. */
    public static final int SCAN_PERIOD = 5;
    /** Видели не дольше стольких тиков назад — видят сейчас: цель пульта — сама сущность, а не место. */
    public static final int CURRENT = 2 * SCAN_PERIOD;
    /** Половина угла поля зрения: экран при FOV 70–90 и 16:9 — 50–60° по горизонтали от центра. */
    public static final double FIELD_DEGREES = 60;
    private static final double FIELD_COS = Math.cos(Math.toRadians(FIELD_DEGREES));

    /** Чем замечено. */
    public enum Source { EYES, CAMERA, RADAR }

    /** Что замечено: игрок, аппарат Sable, снаряд или другое. */
    public enum Kind {
        PLAYER, AIRCRAFT, PROJECTILE, OTHER;

        static Kind of(Entity e) {
            return e instanceof Player ? PLAYER : e instanceof StrikeProjectile ? PROJECTILE : OTHER;
        }
    }

    /** Замеченное: кто, имя (у игрока — ник), что это, где и когда его видели последний раз и чем. */
    public record Contact(UUID id, String name, Kind kind, Vec3 pos, long time, Source source) {
        /** Сколько тиков назад видели. */
        public long age(long now) {
            return now - time;
        }
    }

    private final Map<String, Map<UUID, Contact>> sides = new HashMap<>();

    public Sightings() {}

    public static Sightings get(ServerLevel level) {
        return level.getData(ModAttachments.SIGHTINGS.get());
    }

    /** Сколько тиков помнится замеченное ({@code sight_memory}). */
    public static long memory() {
        return AirstrikeConfig.SERVER.sightMemory.get() * 20L;
    }

    // ---------------------------------------------------------------- сообщить и спросить

    /** Сторона {@code side} видит сущность {@code seen} сейчас. */
    public static void spot(ServerLevel level, String side, Entity seen, Source source) {
        String name = seen instanceof Player p ? p.getGameProfile().getName() : seen.getName().getString();
        get(level).put(side, new Contact(seen.getUUID(), name, Kind.of(seen), seen.position(), level.getGameTime(), source));
    }

    /** Сторона {@code side} видит аппарат Sable {@code craft} сейчас. */
    public static void spot(ServerLevel level, String side, SubLevelAccess craft, Source source) {
        get(level).put(side, new Contact(craft.getUniqueId(), Objects.requireNonNullElse(SubLevels.name(craft), ""), Kind.AIRCRAFT, SubLevels.center(craft),
                level.getGameTime(), source));
    }

    /** Что сторона {@code side} знает о {@code id}: замеченное не дольше {@link #memory} тиков назад, иначе null. */
    @Nullable
    public static Contact contact(ServerLevel level, String side, UUID id) {
        Map<UUID, Contact> known = get(level).sides.get(side);
        Contact c = known == null ? null : known.get(id);
        return c != null && c.age(level.getGameTime()) <= memory() ? c : null;
    }

    /** Всё, что сторона {@code side} видела не дольше {@link #memory} тиков назад. */
    public static List<Contact> contacts(ServerLevel level, String side) {
        Map<UUID, Contact> known = get(level).sides.get(side);
        if (known == null) return List.of();
        long now = level.getGameTime(), memory = memory();
        List<Contact> out = new ArrayList<>(known.size());
        for (Contact c : known.values()) if (c.age(now) <= memory) out.add(c);
        return out;
    }

    private void put(String side, Contact c) {
        sides.computeIfAbsent(side, k -> new HashMap<>()).put(c.id(), c);
    }

    // ---------------------------------------------------------------- камера снаряда

    /** Игрок смотрит глазами снаряда {@code projectile}, камера смотрит по {@code look} (единичный вектор). */
    public record Watching(UUID projectile, Vec3 look) {}

    /** Камера снаряда, глазами которого игрок смотрит сейчас, и её взгляд. */
    private record View(StrikeProjectile camera, Vec3 look) {}

    /**
     * Игрок смотрит глазами своего снаряда {@code projectile} (null — вернулся к себе), камера повёрнута на {@code yaw},
     * {@code pitch} (градусы, как у сущности). Чужой снаряд, снаряд не в его мире и поворот не числом не принимаются.
     */
    public static void watch(ServerPlayer player, @Nullable UUID projectile, float yaw, float pitch) {
        if (projectile == null) {
            player.removeData(ModAttachments.WATCHING.get());
        } else if (Float.isFinite(yaw) && Float.isFinite(pitch) && camera(player, projectile) != null) {
            player.setData(ModAttachments.WATCHING.get(), new Watching(projectile, Vec3.directionFromRotation(Mth.clamp(pitch, -90, 90), yaw)));
        }
    }

    /** Снаряд, глазами которого игрок смотрит сейчас: свой, в его мире, в полёте. */
    @Nullable
    public static StrikeProjectile camera(ServerPlayer player) {
        View v = view(player);
        return v == null ? null : v.camera();
    }

    @Nullable
    private static View view(ServerPlayer player) {
        Watching w = player.getExistingData(ModAttachments.WATCHING.get()).orElse(null);
        StrikeProjectile camera = w == null ? null : camera(player, w.projectile());
        return camera == null ? null : new View(camera, w.look());
    }

    @Nullable
    private static StrikeProjectile camera(ServerPlayer player, UUID id) {
        return player.serverLevel().getEntity(id) instanceof StrikeProjectile p && p.isAlive()
                && player.getUUID().equals(p.ownerId()) ? p : null;
    }

    // ---------------------------------------------------------------- осмотр

    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level) || level.getGameTime() % SCAN_PERIOD != 0) return;
        if (level.players().isEmpty() && !level.hasData(ModAttachments.SIGHTINGS.get())) return;
        scan(level, level.players());
    }

    /**
     * Осмотр: каждый живой игрок не в режиме наблюдателя из {@code players} отмечает за свою сторону чужих игроков из
     * того же списка и аппараты Sable, которых видит сейчас ({@link #sees}, {@link #seesCraft}); забытое убирается.
     */
    public static void scan(ServerLevel level, List<? extends ServerPlayer> players) {
        Sightings s = get(level);
        long now = level.getGameTime(), memory = memory();
        s.sides.values().removeIf(known -> {
            known.values().removeIf(c -> c.age(now) > memory);
            return known.isEmpty();
        });
        for (ServerPlayer viewer : players) {
            if (viewer.isSpectator() || !viewer.isAlive() || viewer.level() != level) continue;
            String side = Sides.side(viewer);
            for (ServerPlayer other : players) {
                if (other == viewer || other.isSpectator() || Sides.friendly(viewer, other)) continue;
                Source by = seenBy(viewer, other);
                if (by != null) s.put(side, new Contact(other.getUUID(), other.getGameProfile().getName(), Kind.PLAYER,
                        other.position(), now, by));
            }
            if (SubLevels.mayHaveCraftNear(level, viewer.position(), Sight.viewRange(viewer))) {
                for (SubLevelAccess craft : SubLevels.near(level, viewer.position(), Sight.viewRange(viewer))) {
                    if (seesCraft(viewer, craft)) spot(level, side, craft, Source.EYES);
                }
            }
        }
    }

    /** Игрок видит сущность сейчас — глазами или камерой своего снаряда (в поле зрения и без преград). */
    public static boolean sees(ServerPlayer viewer, Entity target) {
        return seenBy(viewer, target) != null;
    }

    @Nullable
    private static Source seenBy(ServerPlayer viewer, Entity target) {
        Vec3 eye = viewer.getEyePosition();
        if (facing(eye, viewer.getViewVector(1), target.getBoundingBox().getCenter()) && Sight.sees(viewer, eye, target)) return Source.EYES;
        View v = view(viewer);
        if (v != null && v.camera() != target && facing(v.camera().position(), v.look(), target.getBoundingBox().getCenter())
                && Sight.sees(viewer, v.camera().position(), target)) return Source.CAMERA;
        return null;
    }

    /**
     * Игрок видит аппарат Sable сейчас: его середина в дальности прорисовки игрока и в поле зрения, а взгляд до неё
     * упирается в сам аппарат или ни во что (глазами или камерой своего снаряда). Чанки не грузятся.
     */
    public static boolean seesCraft(ServerPlayer viewer, SubLevelAccess craft) {
        Vec3 center = SubLevels.center(craft);
        if (!Sight.within(viewer, center)) return false;
        if (facing(viewer.getEyePosition(), viewer.getViewVector(1), center) && craftInSight(viewer.serverLevel(), viewer.getEyePosition(), craft, center)) {
            return true;
        }
        View v = view(viewer);
        return v != null && facing(v.camera().position(), v.look(), center) && craftInSight(viewer.serverLevel(), v.camera().position(), craft, center);
    }

    /** Взгляд из {@code eye} в середину аппарата упирается в сам аппарат или ни во что. */
    private static boolean craftInSight(ServerLevel level, Vec3 eye, SubLevelAccess craft, Vec3 center) {
        if (!Terrain.readyAlong(level, eye, center)) return false;
        BlockHitResult hit = level.clip(new ClipContext(eye, center, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, CollisionContext.empty()));
        if (hit.getType() == HitResult.Type.MISS) return true;
        SubLevelAccess on = SubLevels.containing(level, hit.getLocation());
        return on != null && on.getUniqueId().equals(craft.getUniqueId());
    }

    /** Точка {@code at} в поле зрения из {@code eye} по взгляду {@code look}. */
    static boolean facing(Vec3 eye, Vec3 look, Vec3 at) {
        Vec3 to = at.subtract(eye);
        double length = to.length();
        return length < 1.0e-3 || to.dot(look) >= FIELD_COS * length * look.length();
    }
}
