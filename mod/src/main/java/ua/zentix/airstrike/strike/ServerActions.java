package ua.zentix.airstrike.strike;

import dev.ryanhcode.sable.companion.SubLevelAccess;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jetbrains.annotations.Nullable;
import ua.zentix.airstrike.Airstrike;
import ua.zentix.airstrike.AirstrikeConfig;
import ua.zentix.airstrike.compat.SubLevels;
import ua.zentix.airstrike.entity.DebrisEntity;
import ua.zentix.airstrike.entity.LauncherEntity;
import ua.zentix.airstrike.entity.SpentBoosterEntity;
import ua.zentix.airstrike.entity.StrikeProjectile;
import ua.zentix.airstrike.item.DesignatorItem;
import ua.zentix.airstrike.net.C2S;
import ua.zentix.airstrike.net.S2C;
import ua.zentix.airstrike.nuclear.NuclearStrikes;
import ua.zentix.airstrike.registry.ModAttachments;
import ua.zentix.airstrike.registry.ModDataComponents;
import ua.zentix.airstrike.target.Target;
import ua.zentix.airstrike.target.TargetPicker;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Действия игроков с пульта (пакеты) и команд: пуск, настройки пульта, отбой. Всё проверяется здесь. */
public final class ServerActions {
    /** Пуск и отбой — не чаще раза в 4 тика с одного игрока: защита от дребезга кнопки и от спама пакетами. */
    private static final int FIRE_INTERVAL = 4;
    /** Подсказка клиента об аппарате: его точка в мире не дальше стольких блоков от точки прицела. */
    private static final double AIRCRAFT_HINT_SLACK = 32;

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
        if (tooSoon(player, ModAttachments.LAST_FIRE.get())) return;

        Loadout l = clamp(p.loadout());
        Aim aim = p.aim().isPresent() ? fromHint(player, p.aim().get()) : fromMode(player, l, p.aircraft().orElse(null));
        if (aim == null) return;
        strike(player, l.weapon(), l.count(), l.spread(), aim, l.nuke());
    }

    /** Перенацелить свой снаряд из его камеры: сервер находит то же, что под прицелом камеры, и проверяет. */
    public static void retarget(C2S.Retarget p, IPayloadContext ctx) {
        if (!(ctx.player() instanceof ServerPlayer player) || !mayUse(player)) return;
        ServerLevel level = player.serverLevel();
        if (!(level.getEntity(p.projectile()) instanceof StrikeProjectile proj) || !player.getUUID().equals(proj.ownerId())) return;
        C2S.AimHint h = p.aim();
        // из камеры видно не дальше дальности прорисовки снаряда
        if (!valid(h) || h.point().distanceToSqr(proj.position()) > 1024 * 1024) return;
        if (tooSoon(player, ModAttachments.LAST_RETARGET.get())) return;
        Aim aim = resolveHint(level, player, h);
        if (aim == null || !proj.retarget(aim.target(), aim.point())) return;
        Component what = aim.label() != null ? aim.label() : Component.translatable("airstrike.target.point");
        player.displayClientMessage(Component.translatable("airstrike.retargeted", what).withStyle(ChatFormatting.GOLD), true);
        Airstrike.LOG.info("Перенацеливание: {} → {} {} {} — {}", proj.getType().getDescriptionId(), Mth.floor(aim.point().x),
                Mth.floor(aim.point().y), Mth.floor(aim.point().z), player.getGameProfile().getName());
    }

    public static void setLoadout(C2S.SetLoadout p, IPayloadContext ctx) {
        if (!(ctx.player() instanceof ServerPlayer player)) return;
        ItemStack stack = player.getItemInHand(p.hand());
        if (!(stack.getItem() instanceof DesignatorItem)) return;
        stack.set(ModDataComponents.LOADOUT.get(), clamp(p.loadout()));
    }

    public static void clear(C2S.Clear p, IPayloadContext ctx) {
        if (!(ctx.player() instanceof ServerPlayer player)) return;
        if (!mayUse(player) || tooSoon(player, ModAttachments.LAST_FIRE.get())) return;
        int n = clearAll(player.server, mayUseNuke(player));
        player.sendSystemMessage(Component.translatable("airstrike.cleared", n).withStyle(ChatFormatting.GRAY));
    }

    /** Действие того же рода было меньше {@link #FIRE_INTERVAL} тиков назад; иначе запомнить это. */
    private static boolean tooSoon(ServerPlayer player, AttachmentType<Long> last) {
        long now = player.serverLevel().getGameTime();
        if (player.hasData(last) && now - player.getData(last) < FIRE_INTERVAL) return true;
        player.setData(last, now);
        return false;
    }

    /** Координаты подсказки — конечные числа: NaN проходит любые сравнения дальности, бесконечность ломает чанки. */
    private static boolean valid(C2S.AimHint h) {
        return finite(h.point()) && finite(h.plotPos());
    }

    private static boolean finite(Vec3 v) {
        return Double.isFinite(v.x) && Double.isFinite(v.y) && Double.isFinite(v.z);
    }

    // ---------------------------------------------------------------- общая логика

    /** Цель пуска: что преследовать, где оно сейчас, как назвать. */
    public record Aim(Target target, Vec3 point, @Nullable Component label) {}

    public static Loadout clamp(Loadout l) {
        boolean onCarrier = l.nuke().onCarrier() && AirstrikeConfig.SERVER.carrierNukes.get();
        Loadout.Nuke n = new Loadout.Nuke(Math.min(l.nuke().yieldKt(), AirstrikeConfig.SERVER.nukeMaxYield.get()), l.nuke().airBurst(), onCarrier);
        Loadout clamped = new Loadout(l.weapon(), l.count(), l.spread(), l.mode(), l.player(), n);
        // ядерных залпов нет: одна МБР, одна ракета, одна бомба
        if (clamped.nuclear()) return new Loadout(l.weapon(), 1, 0, l.mode(), l.player(), n);
        return new Loadout(l.weapon(), Math.min(l.count(), AirstrikeConfig.SERVER.maxSalvo.get()),
                Math.min(l.spread(), AirstrikeConfig.SERVER.maxSpread.get()), l.mode(), l.player(), n);
    }

    /** Ядерное оружие: включено ли и можно ли этому игроку (по умолчанию — только операторам). */
    public static boolean mayUseNuke(ServerPlayer player) {
        return AirstrikeConfig.SERVER.nukeEnabled.get() && (!AirstrikeConfig.SERVER.nukeOpsOnly.get() || player.hasPermissions(2));
    }

    /**
     * Пустить: один снаряд точно в цель или залп.
     *
     * @return true, если пуск состоялся
     */
    public static boolean strike(ServerPlayer player, WeaponType weapon, int count, int spread, Aim aim, Loadout.Nuke nuke) {
        ServerLevel level = player.serverLevel();
        float yaw = player.getYRot();
        boolean nuclear = weapon == WeaponType.NUKE || nuke.onCarrier() && Loadout.carriesNuke(weapon);
        if (nuclear && !mayUseNuke(player)) {
            player.displayClientMessage(Component.translatable(AirstrikeConfig.SERVER.nukeEnabled.get()
                    ? "airstrike.nuke.ops_only" : "airstrike.nuke.disabled").withStyle(ChatFormatting.RED), true);
            return false;
        }
        if (aim.label() != null) {
            player.sendSystemMessage(Component.translatable("airstrike.target.locked", aim.label()).withStyle(ChatFormatting.GOLD));
        }
        StrikeService.log(player.getGameProfile().getName(), weapon, count, spread, aim.point());
        if (count <= 1 && spread <= 0) {
            StrikeService.Result r = StrikeService.launch(level, weapon, aim.target(), aim.point(), yaw, player.getUUID(), true, nuke, nuke.onCarrier());
            if (!r.ok()) {
                player.displayClientMessage(Component.translatable("airstrike.launch_failed").withStyle(ChatFormatting.RED), true);
                return false;
            }
            StrikeService.confirm(player, weapon, r.eta());
            return true;
        }
        SalvoData.start(level, weapon, Math.max(1, count), spread, aim.target(), aim.point(), yaw, player, nuke);
        return true;
    }

    /**
     * Цель по подсказке бинокля или карты: сервер находит у себя то же, что видит клиент, и проверяет дальность —
     * прицела (бинокль видит не дальше {@code aim_range}) или карты ({@code map_range} по горизонтали, в границах мира).
     */
    @Nullable
    private static Aim fromHint(ServerPlayer player, C2S.AimHint h) {
        if (!valid(h)) {
            notFound(player);
            return null;
        }
        if (h.kind() == C2S.AimHint.GROUND) {
            // у мира с потолком (Незер) верх колонки — крыша из бедрока: места «на земле» по карте нет
            if (player.level().dimensionType().hasCeiling()) {
                player.displayClientMessage(Component.translatable("airstrike.map.no_ceiling").withStyle(ChatFormatting.RED), true);
                return null;
            }
            double range = AirstrikeConfig.SERVER.mapRange.get();
            double dx = h.point().x - player.getX(), dz = h.point().z - player.getZ();
            if (dx * dx + dz * dz > range * range || !player.level().getWorldBorder().isWithinBounds(h.point().x, h.point().z)) {
                player.displayClientMessage(Component.translatable("airstrike.map.out_of_range", (int) range).withStyle(ChatFormatting.RED), true);
                return null;
            }
        } else {
            double range = AirstrikeConfig.SERVER.aimRange.get() + 32;
            if (h.point().distanceToSqr(player.getEyePosition()) > range * range) {
                notFound(player);
                return null;
            }
        }
        return resolveHint(player.serverLevel(), player, h);
    }

    /**
     * Цель по подсказке клиента: сущность и аппарат — если они у сервера там же, где прицел (и в сущность можно
     * целиться, как в {@link TargetPicker#aimable}), иначе точка прицела.
     */
    @Nullable
    private static Aim resolveHint(ServerLevel level, ServerPlayer player, C2S.AimHint h) {
        switch (h.kind()) {
            case C2S.AimHint.ENTITY -> {
                Entity e = level.getEntity(h.entityId());
                if (e != null && TargetPicker.aimable(player).test(e) && e.getBoundingBox().inflate(8).contains(h.point())) {
                    return new Aim(Target.OfEntity.of(e, h.point()), h.point(), e.getDisplayName());
                }
            }
            case C2S.AimHint.AIRCRAFT -> {
                SubLevelAccess sub = SubLevels.containing(level, h.plotPos());
                Vec3 world = sub == null ? null : SubLevels.toWorld(level, h.plotPos());
                // иначе проверка дальности по точке прицела не держала бы: точка рядом, а аппарат — где угодно
                if (world != null && world.distanceToSqr(h.point()) <= AIRCRAFT_HINT_SLACK * AIRCRAFT_HINT_SLACK) {
                    return new Aim(new Target.OfSubLevel(h.plotPos()), world, SubLevels.describe(sub));
                }
            }
            case C2S.AimHint.GROUND -> {
                Target.Ground ground = Target.Ground.at(level, h.point().x, h.point().z);
                return new Aim(ground, ground.pos(), Component.translatable("airstrike.target.map_point",
                        Mth.floor(h.point().x), Mth.floor(h.point().z)));
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
            case MAP -> {
                // место на карте присылает клиент (подсказка GROUND); без неё цели нет
                notFound(player);
                return null;
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
        return server.getPlayerList().getPlayerByName(name); // без учёта регистра, как в ванили
    }

    private static void notFound(ServerPlayer player) {
        player.displayClientMessage(Component.translatable("airstrike.target_not_found").withStyle(ChatFormatting.RED), true);
    }

    /**
     * Отбой: все снаряды и обломки во всех мирах убраны без взрыва, залпы отменены.
     *
     * @param nuclear отменить и ядерные удары (только тем, кому можно ядерное оружие)
     */
    public static int clearAll(MinecraftServer server, boolean nuclear) {
        int n = 0;
        for (ServerLevel level : server.getAllLevels()) {
            List<Entity> kill = new ArrayList<>();
            for (Entity e : level.getAllEntities()) {
                if (e instanceof StrikeProjectile || e instanceof DebrisEntity || e instanceof LauncherEntity || e instanceof SpentBoosterEntity) kill.add(e);
            }
            for (Entity e : kill) {
                if (e instanceof StrikeProjectile) n++;
                e.discard();
            }
            n += VirtualFlights.get(level).clear();
            StrikeWorld.clearSalvos(level);
            if (nuclear) n += NuclearStrikes.clear(level);
        }
        PacketDistributor.sendToAllPlayers(new S2C.Cleared());
        return n;
    }
}
