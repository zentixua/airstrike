package ua.zentix.airstrike.strike;

import dev.ryanhcode.sable.companion.SubLevelAccess;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.util.StringUtil;
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
import ua.zentix.airstrike.nuclear.NuclearEvents;
import ua.zentix.airstrike.nuclear.NuclearStrikes;
import ua.zentix.airstrike.registry.ModAttachments;
import ua.zentix.airstrike.registry.ModDataComponents;
import ua.zentix.airstrike.target.Sides;
import ua.zentix.airstrike.target.Sightings;
import ua.zentix.airstrike.target.Target;
import ua.zentix.airstrike.target.TargetPicker;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

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

        Loadout l = p.loadout();
        Aim aim = p.aim().isPresent() ? fromHint(player, p.aim().get()) : fromMode(player, l, p.aircraft().orElse(null));
        if (aim == null || !routeFits(player, l.weapon(), p.via(), aim)) return;
        strike(player, l.weapon(), l.count(), l.spread(), aim, l.nuke(), p.via(), true);
    }

    /**
     * Маршрут оператора годится ({@link Waypoints}): оружие летает по точкам, точки там же, где можно выбрать место
     * на карте ({@link #groundInRange}), и путь от игрока через них до цели не длиннее дальности оружия по маршруту
     * (паспорт, {@link WeaponSpec.Route#reach}). Иначе — строка игроку, пуска нет. Без точек — годится.
     */
    public static boolean routeFits(ServerPlayer player, WeaponType weapon, Waypoints via, Aim aim) {
        if (via.isEmpty()) return true;
        WeaponSpec.Route route = weapon.spec().route();
        if (!route.waypoints()) {
            player.displayClientMessage(Component.translatable("airstrike.route.unsupported", weapon.displayName()).withStyle(ChatFormatting.RED), true);
            return false;
        }
        if (!via.finite() || via.points().stream().anyMatch(q -> !groundInRange(player, q.x, q.z))) {
            player.displayClientMessage(Component.translatable("airstrike.route.out_of_range", AirstrikeConfig.SERVER.mapRange.get())
                    .withStyle(ChatFormatting.RED), true);
            return false;
        }
        if (!via.within(weapon, player.position(), aim.point())) {
            player.displayClientMessage(Component.translatable("airstrike.route.too_long", weapon.displayName(), kilometres(via.length(player.position(), aim.point())),
                    kilometres(route.reach())).withStyle(ChatFormatting.RED), true);
            return false;
        }
        return true;
    }

    /** Километры с одним знаком — для строк игроку (точка, а не запятая русской локали: строка из перевода). */
    private static String kilometres(double blocks) {
        return String.format(Locale.ROOT, "%.1f", blocks / 1000);
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
        Aim hinted = resolveHint(level, player, h);
        Aim aim = hinted == null ? null : sighted(player, hinted, subject -> CameraLink.holds(level, proj, subject, hinted.point()));
        if (aim == null || !proj.retarget(aim.target(), aim.point())) return;
        Component what = aim.label() != null ? aim.label() : Component.translatable("airstrike.target.point");
        player.displayClientMessage(Component.translatable("airstrike.retargeted", what).withStyle(ChatFormatting.GOLD), true);
        Airstrike.LOG.info("Перенацеливание: {} → {} {} {} — {}", proj.getType().getDescriptionId(), Mth.floor(aim.point().x),
                Mth.floor(aim.point().y), Mth.floor(aim.point().z), player.getGameProfile().getName());
    }

    /** Камера снаряда смотрит с борта своего снаряда ({@link CameraLink}). */
    public static void cameraView(C2S.CameraView p, IPayloadContext ctx) {
        if (ctx.player() instanceof ServerPlayer player) CameraLink.receive(player, p.projectile(), p.yaw(), p.pitch());
    }

    public static void setLoadout(C2S.SetLoadout p, IPayloadContext ctx) {
        if (!(ctx.player() instanceof ServerPlayer player)) return;
        ItemStack stack = player.getItemInHand(p.hand());
        if (!(stack.getItem() instanceof DesignatorItem)) return;
        stack.set(ModDataComponents.LOADOUT.get(), clamp(p.loadout()));
    }

    /**
     * Игроки и замеченное для карты наведения — тем, кому можно пульт ({@link #mapPlayers(ServerPlayer, Collection)}).
     */
    public static void mapPlayers(C2S.MapPlayers p, IPayloadContext ctx) {
        if (!(ctx.player() instanceof ServerPlayer player)) return;
        S2C.MapPlayers marks = mapPlayers(player, player.server.getPlayerList().getPlayers());
        if (marks != null) PacketDistributor.sendToPlayer(player, marks);
    }

    /**
     * Метки на карте пульта у {@code viewer}: в его измерении, не дальше {@code map_range} (дальше удар и так не
     * примут), кроме него самого, наблюдателей и невидимых. Свои по команде ({@link Sides}) из {@code players} — где они
     * сейчас; чужие игроки из {@code players} и прочее, что видела его сторона ({@link Sightings}), — там, где видели
     * последний раз, с давностью; без {@code sight_rules} — все игроки там, где они сейчас. Те же метки — список целей
     * пульта в режиме «игрок»: {@code map_players} выключает только рисование на карте. Null — не отвечать: нет прав
     * на пульт или запрос чаще раза в {@link #FIRE_INTERVAL} тиков.
     */
    @Nullable
    public static S2C.MapPlayers mapPlayers(ServerPlayer viewer, Collection<? extends ServerPlayer> players) {
        if (!mayUse(viewer) || tooSoon(viewer, ModAttachments.LAST_MAP_PLAYERS.get())) return null;
        return new S2C.MapPlayers(marks(viewer, players), AirstrikeConfig.SERVER.mapPlayers.get());
    }

    private static List<S2C.MapPlayer> marks(ServerPlayer viewer, Collection<? extends ServerPlayer> players) {
        boolean rules = AirstrikeConfig.SERVER.sightRules.get();
        ServerLevel level = viewer.serverLevel();
        List<S2C.MapPlayer> marks = new ArrayList<>();
        Map<UUID, ServerPlayer> others = new HashMap<>();
        for (ServerPlayer other : players) {
            if (other == viewer || other.level() != level || other.isSpectator() || other.isInvisible()) continue;
            boolean friendly = Sides.friendly(viewer, other);
            if (!friendly && rules) {
                others.put(other.getUUID(), other);
            } else if (withinMapRange(viewer, other.getX(), other.getZ())) {
                marks.add(new S2C.MapPlayer(other.getUUID(), mapName(other.getGameProfile().getName()), other.getX(), other.getZ(),
                        Sightings.Kind.PLAYER, friendly, 0));
            }
        }
        if (!rules) return marks;
        long now = level.getGameTime();
        for (Sightings.Contact c : Sightings.contacts(level, Sides.side(viewer))) {
            // игрок, что ушёл из мира, перешёл в своих или стал наблюдателем, — не цель
            if (c.kind() == Sightings.Kind.PLAYER && !others.containsKey(c.id())) continue;
            if (!withinMapRange(viewer, c.pos().x, c.pos().z)) continue;
            int age = (int) (Math.max(0, c.age(now) - Sightings.CURRENT) / 20);
            marks.add(new S2C.MapPlayer(c.id(), mapName(c.name()), c.pos().x, c.pos().z, c.kind(), false, age));
        }
        return marks;
    }

    /** Имя длиннее предела кодек не пишет (исключение при отправке): у модов бывают длинные. */
    private static String mapName(String name) {
        return StringUtil.truncateStringIfNecessary(name, S2C.MapPlayer.MAX_NAME, false);
    }

    public static void clear(C2S.Clear p, IPayloadContext ctx) {
        if (!(ctx.player() instanceof ServerPlayer player)) return;
        if (!mayUse(player) || tooSoon(player, ModAttachments.LAST_FIRE.get())) return;
        boolean nuclear = mayUseNuke(player);
        int n = clearAll(player.server, nuclear, player.getGameProfile().getName());
        player.sendSystemMessage(clearedMessage(n, nuclear).withStyle(ChatFormatting.GRAY));
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
     * Пустить от имени игрока (заход из-за его спины или по его точкам {@code via}, уже проверенным {@link #routeFits}):
     * один снаряд точно в цель или залп — после проверки прав.
     *
     * @param rules приказ по правилам игроков: с пульта — всегда (и у операторов), командой — у игрока без прав оператора:
     *              цель не дальше {@code map_range}, чужой игрок и аппарат — только замеченные ({@link #sighted}); команда
     *              оператора сервера, как и консоль ({@link #dispatch}), бьёт без них
     * @return true, если пуск состоялся
     */
    public static boolean strike(ServerPlayer player, WeaponType weapon, int count, int spread, Aim aim, Loadout.Nuke nuke, Waypoints via,
                                 boolean rules) {
        if (rules) {
            if (!withinMapRange(player, aim.point().x, aim.point().z)) {
                player.displayClientMessage(Component.translatable("airstrike.map.out_of_range", AirstrikeConfig.SERVER.mapRange.get())
                        .withStyle(ChatFormatting.RED), true);
                return false;
            }
            aim = sighted(player, aim, subject -> seesNow(player, subject));
            if (aim == null) return false;
        }
        Loadout l = order(weapon, count, spread, nuke);
        if (l.nuclear() && !mayUseNuke(player)) {
            player.displayClientMessage(Component.translatable(AirstrikeConfig.SERVER.nukeEnabled.get()
                    ? "airstrike.nuke.ops_only" : "airstrike.nuke.disabled").withStyle(ChatFormatting.RED), true);
            return false;
        }
        int limit = AirstrikeConfig.SERVER.maxActivePerPlayer.get();
        if (limit > 0 && !player.hasPermissions(2)) {
            int active = StrikeWorld.active(player.server, player.getUUID());
            if (active >= limit) {
                player.displayClientMessage(Component.translatable("airstrike.too_many_active", active, limit).withStyle(ChatFormatting.RED), true);
                return false;
            }
        }
        if (aim.label() != null) player.sendSystemMessage(locked(weapon, aim).withStyle(ChatFormatting.GOLD));
        return launch(player.serverLevel(), player.getGameProfile().getName(), player, player.getYRot(), l, aim, via);
    }

    /**
     * Правила приказа игрока. Чужой игрок (и сущность, на которой он едет) и аппарат Sable — только замеченные
     * ({@code sight_rules}, {@link Sightings}): стрелявший видит цель сейчас ({@code seen}: с пульта — глазами или
     * камерой своего снаряда, {@link #seesNow}; перенацеливание из камеры — в её кадре, {@link CameraLink#holds}) или его
     * сторона видела её только что — по ней; видели раньше, не дольше {@code sight_memory}, — по месту, где видели
     * последний раз; иначе отказ. Движущаяся цель (сущность, аппарат) становится замеченной ({@link Target.Sighted}):
     * удар туда, где её видели, а дальше за ней идёт только оружие с камерой, пока оператор держит её в кадре
     * ({@link WeaponSpec.Tracking}). Иначе по нику били в любой точке карты, и снаряд вёл цель до конца (игра
     * 03.10.2026: сотни шахедов по одному игроку).
     *
     * @param seen стрелявший видит цель приказа ({@link Target.OfEntity} или {@link Target.OfSubLevel}) сейчас
     * @return цель приказа; null — приказ не принят (игроку — строка)
     */
    @Nullable
    public static Aim sighted(ServerPlayer player, Aim aim, Predicate<Target> seen) {
        Target target = aim.target();
        if (!(target instanceof Target.OfEntity) && !(target instanceof Target.OfSubLevel)) return aim;
        Aim sighted = new Aim(new Target.Sighted(target, aim.point()), aim.point(), aim.label());
        if (!AirstrikeConfig.SERVER.sightRules.get()) return sighted;
        ServerLevel level = player.serverLevel();
        UUID id;
        Component name;
        if (target instanceof Target.OfEntity e) {
            Player rider = level.getEntity(e.uuid()) instanceof Entity victim ? rider(victim) : null;
            if (rider == null || Sides.friendly(player, rider)) return sighted;
            id = rider.getUUID();
            name = rider.getDisplayName();
        } else {
            SubLevelAccess craft = SubLevels.containing(level, ((Target.OfSubLevel) target).plotPos());
            if (craft == null) return sighted;
            id = craft.getUniqueId();
            name = SubLevels.describe(craft);
        }
        if (seen.test(target)) return sighted;
        Sightings.Contact c = Sightings.contact(level, Sides.side(player), id);
        if (c == null) {
            player.displayClientMessage(Component.translatable("airstrike.target.unseen", name).withStyle(ChatFormatting.RED), true);
            return null;
        }
        long age = c.age(level.getGameTime());
        if (age <= Sightings.CURRENT) return sighted;
        Vec3 at = c.kind() == Sightings.Kind.PLAYER ? c.pos().add(0, 1, 0) : c.pos();
        player.sendSystemMessage(Component.translatable("airstrike.target.last_seen", name, Math.max(1, age / 20)).withStyle(ChatFormatting.GOLD));
        return new Aim(new Target.Point(at), at, null);
    }

    /** Игрок видит цель приказа {@code subject} сейчас — глазами или камерой своего снаряда ({@link Sightings}). */
    private static boolean seesNow(ServerPlayer player, Target subject) {
        ServerLevel level = player.serverLevel();
        return switch (subject) {
            case Target.OfEntity e -> level.getEntity(e.uuid()) instanceof Entity victim
                    && (Sightings.sees(player, victim) || rider(victim) instanceof Player rider && rider != victim && Sightings.sees(player, rider));
            case Target.OfSubLevel s -> SubLevels.containing(level, s.plotPos()) instanceof SubLevelAccess craft && Sightings.seesCraft(player, craft);
            default -> true;
        };
    }

    /** Строка «цель взята»: идёт ли снаряд за ней и как. */
    private static MutableComponent locked(WeaponType weapon, Aim aim) {
        return switch (aim.target()) {
            case Target.Sighted s -> weapon.spec().tracking() == WeaponSpec.Tracking.CAMERA
                    ? Component.translatable("airstrike.target.locked.camera", aim.label(), Component.keybind(Airstrike.CAMERA_KEY))
                    : Component.translatable("airstrike.target.locked.point", aim.label());
            // приказ без правил: снаряд идёт за ней
            case Target.OfEntity e -> Component.translatable("airstrike.target.locked", aim.label());
            case Target.OfSubLevel s -> Component.translatable("airstrike.target.locked", aim.label());
            case Target.Point p -> Component.translatable("airstrike.target.locked.place", aim.label());
            case Target.Ground g -> Component.translatable("airstrike.target.locked.place", aim.label());
        };
    }

    /**
     * Пустить без игрока — от консоли или командного блока ({@code who} — для лога): заход по курсу {@code yaw}.
     * Права проверяет сама команда.
     *
     * @return true, если пуск состоялся
     */
    public static boolean dispatch(ServerLevel level, String who, float yaw, WeaponType weapon, int count, int spread, Aim aim, Loadout.Nuke nuke) {
        return launch(level, who, null, yaw, order(weapon, count, spread, nuke), aim, Waypoints.NONE);
    }

    /** Приказ в пределах настроек сервера ({@link #clamp}). */
    private static Loadout order(WeaponType weapon, int count, int spread, Loadout.Nuke nuke) {
        return clamp(new Loadout(weapon, count, spread, TargetMode.LOOK, "", nuke));
    }

    /** Строка в лог и пуск: один снаряд или залп; стреляющему ({@code owner}, если есть) — итог. */
    private static boolean launch(ServerLevel level, String who, @Nullable ServerPlayer owner, float yaw, Loadout l, Aim aim, Waypoints via) {
        StrikeService.log(level, who, l.weapon(), l.count(), l.spread(), aim.target(), aim.point(), via);
        UUID ownerId = owner == null ? null : owner.getUUID();
        if (l.count() > 1 || l.spread() > 0) {
            SalvoData.start(level, l.weapon(), l.count(), l.spread(), aim.target(), aim.point(), yaw, ownerId, l.nuke(), via);
            return true;
        }
        StrikeService.Result r = StrikeService.launch(level, l.weapon(), aim.target(), aim.point(), yaw, ownerId, true, l.nuke(), via);
        if (owner != null) {
            if (r.ok()) StrikeService.confirm(owner, l.weapon(), r.eta());
            else owner.displayClientMessage(Component.translatable("airstrike.launch_failed").withStyle(ChatFormatting.RED), true);
        }
        return r.ok();
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
            if (!groundInRange(player, h.point().x, h.point().z)) {
                player.displayClientMessage(Component.translatable("airstrike.map.out_of_range", AirstrikeConfig.SERVER.mapRange.get())
                        .withStyle(ChatFormatting.RED), true);
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

    /** Место с карты годится: мир без потолка, не дальше {@code map_range} от игрока по горизонтали, в границах мира. */
    public static boolean groundInRange(ServerPlayer player, double x, double z) {
        if (player.level().dimensionType().hasCeiling()) return false;
        return withinMapRange(player, x, z) && player.level().getWorldBorder().isWithinBounds(x, z);
    }

    /** Не дальше {@code map_range} от игрока по горизонтали: докуда бьёт пульт не по прицелу (карта, игрок). */
    public static boolean withinMapRange(ServerPlayer player, double x, double z) {
        double range = AirstrikeConfig.SERVER.mapRange.get();
        double dx = x - player.getX(), dz = z - player.getZ();
        return dx * dx + dz * dz <= range * range;
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
                return groundAim(level, h);
            }
            default -> {}
        }
        return new Aim(new Target.Point(h.point()), h.point(), null);
    }

    /** Место с карты: x и z из подсказки, высота — {@link Target.Ground#at} (верх по карте клиента — оценка). */
    public static Aim groundAim(ServerLevel level, C2S.AimHint h) {
        Target.Ground ground = Target.Ground.at(level, h.point().x, h.point().z, h.mapSurface());
        return new Aim(ground, ground.pos(), Component.translatable("airstrike.target.map_point",
                Mth.floor(h.point().x), Mth.floor(h.point().z)));
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
                // как у места с карты: район цели сервер грузит и генерирует, дальность мира её ограничивает
                if (!withinMapRange(player, victim.getX(), victim.getZ())) {
                    player.displayClientMessage(Component.translatable("airstrike.player_out_of_range", victim.getDisplayName(),
                            AirstrikeConfig.SERVER.mapRange.get()).withStyle(ChatFormatting.RED), false);
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

    /** Игрок, которого касается удар по сущности: она сама или тот, кто едет на ней или с ней; null — никто. */
    @Nullable
    private static Player rider(Entity e) {
        if (e instanceof Player p) return p;
        Entity root = e.getRootVehicle();
        if (root instanceof Player p) return p;
        for (Entity passenger : root.getIndirectPassengers()) {
            if (passenger instanceof Player p) return p;
        }
        return null;
    }

    /** Удар по игроку или сущности: центр тела (на блок выше ног); приказ без правил идёт за ней. */
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
     * Отбой: снаряды, обломки и пусковые во всех мирах убраны без взрыва, залпы отменены. Ядерные удары (МБР, ракета
     * и B-2 с ядерной БЧ) отменяет только ядерный отбой; пусковая, на которой стоит такая ракета, остаётся до её пуска.
     *
     * Что и кем снято — строкой в лог: отбой снимает и чужие удары, а нажавший видит только итог в чате (игра 02.10.2026:
     * МБР №3 пропала из лога без следа — её снял «Отбоем» другой игрок).
     *
     * @param nuclear отменить и ядерные удары (только тем, кому можно ядерное оружие)
     * @param who     кто дал отбой — для лога
     */
    public static int clearAll(MinecraftServer server, boolean nuclear, String who) {
        Predicate<StrikeProjectile> cancelled = p -> nuclear || !p.isNuclear();
        // отменённые снаряды: клиенты глушат их звук и камеру, а оставшиеся ядерные летят со своим
        List<UUID> projectiles = new ArrayList<>();
        int n = 0, virtual = 0, launchersRemoved = 0, salvos = 0, detonations = 0;
        List<String> strikes = new ArrayList<>();
        for (ServerLevel level : server.getAllLevels()) {
            List<UUID> outside = VirtualFlights.get(level).clear(level, cancelled);
            virtual += outside.size();
            projectiles.addAll(outside);
            List<Entity> kill = new ArrayList<>();
            List<LauncherEntity> launchers = new ArrayList<>();
            // оставшиеся снаряды на направляющей (в мире и вне его): их пусковые стоят до пуска
            List<StrikeProjectile> onRail = new ArrayList<>();
            for (StrikeProjectile p : VirtualFlights.get(level).flights()) {
                if (p.flightPhase().onLauncher()) onRail.add(p);
            }
            for (Entity e : level.getAllEntities()) {
                if (e instanceof StrikeProjectile p) {
                    if (cancelled.test(p)) kill.add(p);
                    else if (p.flightPhase().onLauncher()) onRail.add(p);
                } else if (e instanceof LauncherEntity l) {
                    launchers.add(l);
                } else if (e instanceof DebrisEntity || e instanceof SpentBoosterEntity) {
                    kill.add(e);
                }
            }
            for (LauncherEntity l : launchers) {
                if (onRail.stream().noneMatch(l::serves)) kill.add(l);
            }
            for (Entity e : kill) {
                if (e instanceof StrikeProjectile) projectiles.add(e.getUUID());
                else if (e instanceof LauncherEntity) launchersRemoved++;
                e.discard();
            }
            // залпы ядерными не бывают (ServerActions.clamp): одна ракета, одна бомба
            salvos += SalvoData.get(level).size();
            StrikeWorld.clearSalvos(level);
            if (nuclear) {
                NuclearEvents events = NuclearEvents.get(level);
                for (NuclearEvents.ScheduledStrike s : events.scheduled()) {
                    strikes.add("МБР №" + s.id() + " (" + Math.round(s.yieldKt()) + " кт по " + Mth.floor(s.target().x) + " "
                            + Mth.floor(s.target().y) + " " + Mth.floor(s.target().z) + ")");
                }
                detonations += events.detonations().size();
                n += NuclearStrikes.clear(level);
            }
        }
        PacketDistributor.sendToAllPlayers(new S2C.Cleared(nuclear, projectiles));
        Airstrike.LOG.info("Отбой{} — {}: снарядов {} (вне мира {}), пусковых {}, залпов {}{}{}", nuclear ? " с ядерными" : "", who,
                projectiles.size(), virtual, launchersRemoved, salvos, strikes.isEmpty() ? "" : "; " + String.join(", ", strikes),
                detonations == 0 ? "" : "; забыто подрывов " + detonations);
        return n + projectiles.size();
    }

    /** Итог отбоя для того, кто его дал: отменены ли и ядерные удары. */
    public static MutableComponent clearedMessage(int n, boolean nuclear) {
        return Component.translatable(nuclear ? "airstrike.cleared" : "airstrike.cleared.conventional", n);
    }
}
