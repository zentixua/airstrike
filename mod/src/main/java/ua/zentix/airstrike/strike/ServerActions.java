package ua.zentix.airstrike.strike;

import dev.ryanhcode.sable.companion.SubLevelAccess;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jetbrains.annotations.Nullable;
import ua.zentix.airstrike.AirstrikeConfig;
import ua.zentix.airstrike.compat.SubLevels;
import ua.zentix.airstrike.entity.DebrisEntity;
import ua.zentix.airstrike.entity.StrikeProjectile;
import ua.zentix.airstrike.item.DesignatorItem;
import ua.zentix.airstrike.net.C2S;
import ua.zentix.airstrike.net.S2C;
import ua.zentix.airstrike.registry.ModDataComponents;
import ua.zentix.airstrike.target.Target;
import ua.zentix.airstrike.target.TargetPicker;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

/** Действия игроков с пульта (пакеты) и команд: пуск, настройки пульта, отбой. Всё проверяется здесь. */
public final class ServerActions {
    /** Не чаще раза в 4 тика с одного игрока: защита от дребезга кнопки и от спама пакетами. */
    private static final Map<ServerPlayer, Long> LAST_FIRE = new WeakHashMap<>();

    private ServerActions() {}

    public static boolean mayUse(Player player) {
        return AirstrikeConfig.SERVER.designatorForEveryone.get() || player.hasPermissions(2);
    }

    // ---------------------------------------------------------------- пакеты

    public static void fire(C2S.Fire p, IPayloadContext ctx) {
        if (!(ctx.player() instanceof ServerPlayer player)) return;
        if (!mayUse(player)) {
            player.displayClientMessage(Component.translatable("airstrike.no_permission").withStyle(ChatFormatting.RED), true);
            return;
        }
        long now = player.serverLevel().getGameTime();
        Long last = LAST_FIRE.get(player);
        if (last != null && now - last < 4) return;
        LAST_FIRE.put(player, now);

        Loadout l = clamp(p.loadout());
        Aim aim = p.aim().isPresent() ? fromHint(player, p.aim().get()) : fromMode(player, l, p.aircraft().orElse(null));
        if (aim == null) return;
        strike(player, l.weapon(), l.count(), l.spread(), aim);
    }

    public static void setLoadout(C2S.SetLoadout p, IPayloadContext ctx) {
        if (!(ctx.player() instanceof ServerPlayer player)) return;
        ItemStack stack = player.getItemInHand(p.hand());
        if (!(stack.getItem() instanceof DesignatorItem)) return;
        stack.set(ModDataComponents.LOADOUT.get(), clamp(p.loadout()));
    }

    public static void clear(C2S.Clear p, IPayloadContext ctx) {
        if (!(ctx.player() instanceof ServerPlayer player)) return;
        if (!mayUse(player)) return;
        int n = clearAll(player.server);
        player.sendSystemMessage(Component.translatable("airstrike.cleared", n).withStyle(ChatFormatting.GRAY));
    }

    // ---------------------------------------------------------------- общая логика

    /** Цель пуска: что преследовать, где оно сейчас, как назвать. */
    public record Aim(Target target, Vec3 point, @Nullable Component label) {}

    public static Loadout clamp(Loadout l) {
        return new Loadout(l.weapon(), Math.min(l.count(), AirstrikeConfig.SERVER.maxSalvo.get()),
                Math.min(l.spread(), AirstrikeConfig.SERVER.maxSpread.get()), l.mode(), l.player());
    }

    /**
     * Пустить: один снаряд точно в цель или залп.
     *
     * @return true, если пуск состоялся
     */
    public static boolean strike(ServerPlayer player, WeaponType weapon, int count, int spread, Aim aim) {
        ServerLevel level = player.serverLevel();
        float yaw = player.getYRot();
        if (aim.label() != null) {
            player.sendSystemMessage(Component.translatable("airstrike.target.locked", aim.label()).withStyle(ChatFormatting.GOLD));
        }
        if (count <= 1 && spread <= 0) {
            StrikeProjectile e = StrikeService.launch(level, weapon, aim.target(), aim.point(), yaw, player.getUUID(), true);
            if (e == null) {
                player.displayClientMessage(Component.translatable("airstrike.launch_failed").withStyle(ChatFormatting.RED), true);
                return false;
            }
            StrikeService.confirm(player, weapon);
            return true;
        }
        SalvoData.start(level, weapon, Math.max(1, count), spread, aim.target(), aim.point(), yaw, player);
        return true;
    }

    /** Цель по подсказке бинокля: сервер находит у себя то же, что видит клиент, и проверяет дальность. */
    @Nullable
    private static Aim fromHint(ServerPlayer player, C2S.AimHint h) {
        ServerLevel level = player.serverLevel();
        double range = AirstrikeConfig.SERVER.aimRange.get() + 32;
        if (h.point().distanceToSqr(player.getEyePosition()) > range * range) {
            notFound(player);
            return null;
        }
        switch (h.kind()) {
            case C2S.AimHint.ENTITY -> {
                Entity e = level.getEntity(h.entityId());
                if (e != null && e.isAlive() && e.getBoundingBox().inflate(8).contains(h.point())) {
                    return new Aim(Target.OfEntity.of(e, h.point()), h.point(), e.getDisplayName());
                }
            }
            case C2S.AimHint.AIRCRAFT -> {
                SubLevelAccess sub = SubLevels.containing(level, h.plotPos());
                if (sub != null) {
                    return new Aim(new Target.OfSubLevel(h.plotPos()), SubLevels.toWorld(level, h.plotPos()), SubLevels.describe(sub));
                }
            }
            default -> {}
        }
        return new Aim(new Target.Point(h.point()), h.point(), null);
    }

    /** Цель по режиму экрана пульта. */
    @Nullable
    public static Aim fromMode(ServerPlayer player, Loadout l, @Nullable UUID aircraft) {
        ServerLevel level = player.serverLevel();
        switch (l.mode()) {
            case AROUND_ME -> {
                return new Aim(new Target.Point(player.position()), player.position(), null);
            }
            case PLAYER -> {
                ServerPlayer victim = findPlayer(player.server, l.player());
                if (victim == null) {
                    player.displayClientMessage(Component.translatable("airstrike.player_not_found", l.player()).withStyle(ChatFormatting.RED), false);
                    return null;
                }
                if (victim.level() != level) {
                    player.displayClientMessage(Component.translatable("airstrike.player_other_world", victim.getDisplayName()).withStyle(ChatFormatting.RED), false);
                    return null;
                }
                return atPlayer(victim);
            }
            case AIRCRAFT -> {
                SubLevelAccess sub = aircraft == null ? null : SubLevels.byId(level, player.position(), aircraft);
                if (sub == null) {
                    notFound(player);
                    return null;
                }
                Vec3 c = SubLevels.center(sub);
                return new Aim(new Target.OfSubLevel(SubLevels.toPlot(sub, c)), c, SubLevels.describe(sub));
            }
            default -> {
                TargetPicker.Pick pick = TargetPicker.pick(level, player, player.getEyePosition(), player.getLookAngle(), AirstrikeConfig.SERVER.aimRange.get());
                if (pick == null) {
                    notFound(player);
                    return null;
                }
                Component label = pick.kind() == TargetPicker.Kind.ENTITY || pick.kind() == TargetPicker.Kind.PLAYER
                        || pick.kind() == TargetPicker.Kind.AIRCRAFT ? pick.label() : null;
                return new Aim(pick.target(), pick.point(), label);
            }
        }
    }

    /** Удар по игроку: центр тела (на блок выше ног), снаряд идёт за ним. */
    public static Aim atPlayer(Entity victim) {
        return new Aim(new Target.OfEntity(victim.getUUID(), new Vec3(0, 1, 0)), victim.position().add(0, 1, 0), null);
    }

    @Nullable
    public static ServerPlayer findPlayer(MinecraftServer server, String name) {
        if (name == null || name.isBlank()) return null;
        ServerPlayer exact = server.getPlayerList().getPlayerByName(name);
        if (exact != null) return exact;
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (p.getGameProfile().getName().equalsIgnoreCase(name)) return p;
        }
        return null;
    }

    private static void notFound(ServerPlayer player) {
        player.displayClientMessage(Component.translatable("airstrike.target_not_found").withStyle(ChatFormatting.RED), true);
    }

    /** Отбой: все снаряды и обломки во всех мирах убраны без взрыва, залпы отменены. */
    public static int clearAll(MinecraftServer server) {
        int n = 0;
        for (ServerLevel level : server.getAllLevels()) {
            List<Entity> kill = new ArrayList<>();
            for (Entity e : level.getAllEntities()) {
                if (e instanceof StrikeProjectile || e instanceof DebrisEntity) kill.add(e);
            }
            for (Entity e : kill) {
                if (e instanceof StrikeProjectile) n++;
                e.discard();
            }
            StrikeWorld.clearSalvos(level);
        }
        PacketDistributor.sendToAllPlayers(new S2C.Cleared());
        return n;
    }
}
