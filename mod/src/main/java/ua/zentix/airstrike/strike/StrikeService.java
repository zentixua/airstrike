package ua.zentix.airstrike.strike;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;
import ua.zentix.airstrike.entity.flight.ProximityFuse;
import ua.zentix.airstrike.Airstrike;
import ua.zentix.airstrike.AirstrikeConfig;
import ua.zentix.airstrike.entity.BomberEntity;
import ua.zentix.airstrike.entity.LauncherEntity;
import ua.zentix.airstrike.entity.LoiterEntity;
import ua.zentix.airstrike.entity.RocketEntity;
import ua.zentix.airstrike.entity.StrikeProjectile;
import ua.zentix.airstrike.guidance.FlightController;
import ua.zentix.airstrike.guidance.Route;
import ua.zentix.airstrike.net.S2C;
import ua.zentix.airstrike.nuclear.NuclearStrikes;
import ua.zentix.airstrike.util.Terrain;
import ua.zentix.airstrike.registry.ModEntities;
import ua.zentix.airstrike.target.Target;
import ua.zentix.airstrike.util.Local;

import java.util.UUID;

/**
 * Пуск: одно место для команд, пульта, залпов и тестов.
 * <p>
 * Шахед и крылатая ракета стартуют с мобильной пусковой рядом со стреляющим ({@link LaunchSite}): поджиг,
 * сход с направляющей на ускорителе, сброс ускорителя, выход на маршрут. Маршрут ({@link Route}) — петля в обход
 * и заход на цель с направления взгляда стреляющего, «из-за спины», на всё время полёта из настроек (шахед 50 с,
 * ракета 30 с). Большая часть пути проходит вне загруженного мира ({@link VirtualFlights}). Без стреляющего рядом
 * (консоль, командный блок, игрок в другом мире) или без места под пусковую — заход издалека по той же схеме.
 * B-2 всегда заходит издалека.
 */
public final class StrikeService {
    /** Радиус, в котором слышна сирена и видна тревога. */
    public static final double ALERT_RADIUS = 350;

    private StrikeService() {}

    /**
     * Итог пуска.
     *
     * @param eta через сколько тиков удар (для сообщения стреляющему)
     */
    public record Result(boolean ok, int eta) {
        static final Result FAILED = new Result(false, 0);
    }

    /**
     * @param approachYaw курс захода (обычно — курс взгляда игрока): снаряд приходит «из-за спины» стреляющего
     * @param siren       первый снаряд приказа (одиночный удар или первый снаряд залпа): включить сирену у цели на подлёте
     *                    (у залпа сирена одна на весь залп), искать место пусковой заново
     * @param nuke        мощность и подрыв ядерной боеголовки; у крылатой ракеты и бомбы — только с {@code onCarrier}
     *                    (МБР — всегда ядерная)
     */
    public static Result launch(ServerLevel level, WeaponType weapon, Target target, Vec3 point, float approachYaw,
                                @Nullable UUID owner, boolean siren, Loadout.Nuke nuke) {
        ServerPlayer shooter = owner == null ? null : level.getServer().getPlayerList().getPlayer(owner);
        if (shooter != null && shooter.level() != level) shooter = null;
        if (siren && shooter != null) StrikeWorld.get(level).newOrder(shooter.getUUID(), weapon);
        WeaponSpec spec = weapon.spec();
        if (spec.launch() == WeaponSpec.Launch.ICBM) {
            // МБР бьёт по координатам: за движущейся целью не следит; тревогу поднимает сам пуск
            boolean ok = target instanceof Target.Ground
                    ? NuclearStrikes.launch(level, point, true, nuke.yieldKt(), nuke.airBurst(), shooter)
                    : NuclearStrikes.launch(level, NuclearStrikes.ground(level, point), nuke.yieldKt(), nuke.airBurst(), shooter);
            return new Result(ok, AirstrikeConfig.SERVER.nukeFlightTime.get());
        }
        Loadout.Nuke warhead = nuke.onCarrier() && Loadout.carriesNuke(weapon) ? nuke : null;
        StrikeProjectile p = switch (spec.launch()) {
            case GUIDED -> launchGuided(level, weapon, target, point, approachYaw, owner, shooter);
            case ROCKET -> launchRocket(level, target, point, approachYaw, owner, shooter);
            case LOITER -> launchLoiter(level, target, point, approachYaw, owner, shooter);
            case BOMBER -> launchBomber(level, target, point, approachYaw, owner);
            case ICBM -> throw new IllegalStateException("МБР пускает NuclearStrikes");
        };
        if (p == null) return Result.FAILED;
        p.setNuclear(warhead);
        if (siren && AirstrikeConfig.SERVER.siren.get()) p.armSiren(spec.sirenLead());
        int eta = p.etaTicks();
        if (!p.isVirtual() && !level.addFreshEntity(p)) return Result.FAILED;
        return new Result(true, eta);
    }

    /** Длина маршрута на время полёта из настроек (паспорт). */
    private static double pathLength(WeaponType weapon) {
        return weapon.spec().airframe().cruiseSpeed() * weapon.spec().route().seconds() * 20;
    }

    /** Последний прямой участок перед целью (паспорт: шахед 300 блоков, ракета 500) — и всегда из-за спины стреляющего. */
    private static double entryDistance(WeaponType weapon, Vec3 point, @Nullable ServerPlayer shooter) {
        double base = weapon.spec().route().finalLeg();
        if (shooter == null) return base;
        double dx = point.x - shooter.getX(), dz = point.z - shooter.getZ();
        return Math.max(base, Math.sqrt(dx * dx + dz * dz) + 150);
    }

    /** Снаряд оружия, ещё не в мире. */
    @Nullable
    private static StrikeProjectile create(ServerLevel level, WeaponType weapon) {
        return weapon.spec().airframe().entity().get().create(level);
    }

    /** Шахед или ракета: с пусковой рядом со стреляющим, иначе издалека. Снаряд ещё не добавлен в мир. */
    @Nullable
    private static StrikeProjectile launchGuided(ServerLevel level, WeaponType weapon, Target target, Vec3 point, float yaw,
                                                 @Nullable UUID owner, @Nullable ServerPlayer shooter) {
        Vec3 dir = Local.horizontal(yaw);
        double length = pathLength(weapon);
        double entry = entryDistance(weapon, point, shooter);
        double side = level.random.nextBoolean() ? 1 : -1;
        if (shooter != null && AirstrikeConfig.SERVER.launchNearPlayer.get()) {
            StrikeProjectile p = fromLauncher(level, weapon, target, point, dir, length, entry, side, shooter);
            if (p != null) return p;
        }
        return fromAfar(level, weapon, target, point, dir, length, entry, side, owner);
    }

    /**
     * Шахед или ракета от имени {@code shooter}, как с пульта, но без поиска игрока в списке (GameTest: стреляющий —
     * {@code FakePlayer} NeoForge). Снаряд ещё не добавлен в мир: с пусковой — добавить, вне мира — уже летит.
     */
    @Nullable
    public static StrikeProjectile launchGuided(ServerLevel level, WeaponType weapon, Vec3 point, float yaw, ServerPlayer shooter) {
        StrikeWorld.get(level).newOrder(shooter.getUUID(), weapon);
        return launchGuided(level, weapon, new Target.Point(point), point, yaw, shooter.getUUID(), shooter);
    }

    /** Курс пусковой шахедов и ракет не дальше этого от направления на цель, °: иначе залп уходил бы от цели. */
    private static final float MAX_OFF_TARGET = 90;
    @Nullable
    private static StrikeProjectile fromLauncher(ServerLevel level, WeaponType weapon, Target target, Vec3 point, Vec3 dir,
                                                 double length, double entry, double side, ServerPlayer shooter) {
        // пусковая, чей сектор пуска упирается в постройку, не годится: снаряд разбился бы о неё до взведения
        LauncherEntity launcher = LaunchSite.existing(level, shooter, weapon, l -> LaunchSite.clearAhead(level, l, point));
        if (launcher == null) {
            if (StrikeWorld.get(level).noLaunchSite(shooter.getUUID(), weapon, shooter.chunkPosition())) return null;
            // пакет смотрит на первую точку маршрута — обход с одной или с другой стороны, какой свободен; иначе
            // поворачивается (не дальше MAX_OFF_TARGET от цели), пока не найдёт свободный сектор
            double[] sides = {side, -side};
            LaunchSite.Pick pick = LaunchSite.findClear(level, shooter, weapon, point, site -> {
                float[] yaws = new float[sides.length];
                for (int i = 0; i < sides.length; i++) {
                    Route plan = Route.plan(site, point, dir, length, entry, sides[i]);
                    yaws[i] = FlightController.anglesTo(site, plan.current() == null ? point : plan.current())[0];
                }
                return yaws;
            }, MAX_OFF_TARGET);
            if (pick == null) {
                noLaunchSite(level, shooter, weapon);
                return null;
            }
            if (pick.preferred() >= 0) side = sides[pick.preferred()];
            launcher = LaunchSite.deploy(level, pick.site(), pick.yaw(), weapon, shooter);
        }
        StrikeProjectile p = create(level, weapon);
        if (p == null) return null;
        Slot slot = Slot.reserve(level, launcher);
        p.placeOnLauncher(slot.rail(), launcher.getYRot(), launcher.elevation(), slot.ready(), slot.hidden(), target, point, shooter.getUUID());
        // первая точка маршрута — на курсе пусковой в дальности взведения: до неё снаряд идёт ровно по проверенному
        // сектору, а доворачивает на маршрут уже взведённым
        Vec3 gate = gate(slot.rail(), launcher.getYRot());
        Route plan = Route.plan(gate, point, dir, Math.max(0, length - ProximityFuse.ARM_DISTANCE), entry, side);
        p.setRoute(plan.after(gate, slot.rail()));
        return p;
    }

    /** Точка маршрута на курсе пусковой {@code yaw} в дальности взведения от направляющей {@code rail}. */
    private static Vec3 gate(Vec3 rail, float yaw) {
        return rail.add(Local.horizontal(yaw).scale(ProximityFuse.ARM_DISTANCE));
    }

    /**
     * Места пусковой у стреляющего нет (нет ровного места под небом или сектор пуска везде упирается в постройки):
     * удар идёт издалека — строка ему над хотбаром и в лог, раз на приказ (залп): остаток залпа с этого чанка идёт
     * издалека без новых поисков ({@link StrikeWorld#noLaunchSite}).
     */
    private static void noLaunchSite(ServerLevel level, ServerPlayer shooter, WeaponType weapon) {
        if (!StrikeWorld.get(level).rememberNoLaunchSite(shooter.getUUID(), weapon, shooter.chunkPosition())) return;
        Airstrike.LOG.info("Пуск: {} — пусковую у {} негде поставить (нет места или сектор пуска упирается в постройки) у {}, заход издалека",
                weapon.getSerializedName(), shooter.getGameProfile().getName(), shooter.blockPosition());
        shooter.displayClientMessage(Component.translatable("airstrike.launch.no_site").withStyle(ChatFormatting.GOLD), true);
    }

    /**
     * Ячейка пусковой под снаряд.
     *
     * @param rail   точка схода на направляющей
     * @param ready  через сколько тиков поджиг
     * @param hidden сколько тиков снаряд скрыт в пакете, пока тот поднимается
     */
    private record Slot(Vec3 rail, int ready, int hidden) {
        static Slot reserve(ServerLevel level, LauncherEntity launcher) {
            long now = level.getGameTime();
            int[] slot = launcher.reserve(now, launcher.rack().minReady(), launcher.rack().busyTicks());
            return new Slot(launcher.railPoint(slot[0]), slot[1], launcher.raisingTicks(now));
        }
    }

    /**
     * Пусковая у стреляющего, наведённая на цель (РСЗО, барражирующие): своя — доворачивается, если молчит; нет своей —
     * ставится новая. {@code null}, если места под неё нет.
     */
    @Nullable
    private static LauncherEntity aimedLauncher(ServerLevel level, ServerPlayer shooter, WeaponType weapon, Vec3 point) {
        // своя — если на цель с неё свободен сектор пуска (катапульта, труба пакета): иначе снаряд разбился бы о постройку
        LauncherEntity launcher = LaunchSite.existing(level, shooter, weapon,
                l -> LaunchSite.clearAhead(level, l.position(), FlightController.anglesTo(l.position(), point)[0], weapon, point));
        if (launcher != null) {
            launcher.turnTo(FlightController.anglesTo(launcher.position(), point)[0], level.getGameTime());
            if (LaunchSite.clearAhead(level, launcher, point)) return launcher;
        }
        if (StrikeWorld.get(level).noLaunchSite(shooter.getUUID(), weapon, shooter.chunkPosition())) return null;
        // пакет наводится на цель сам: поворачивать его нельзя, только другое место
        LaunchSite.Pick pick = LaunchSite.findClear(level, shooter, weapon, point, site -> new float[]{FlightController.anglesTo(site, point)[0]}, 0);
        if (pick == null) {
            noLaunchSite(level, shooter, weapon);
            return null;
        }
        return LaunchSite.deploy(level, pick.site(), pick.yaw(), weapon, shooter);
    }

    /**
     * Заход издалека: снаряд начинает полёт вне мира на прямой захода, на расстоянии времени полёта от цели
     * (а если это место уже загружено — сразу в мире).
     */
    private static StrikeProjectile fromAfar(ServerLevel level, WeaponType weapon, Target target, Vec3 point, Vec3 dir,
                                             double length, double entry, double side, @Nullable UUID owner) {
        StrikeProjectile p = create(level, weapon);
        if (p == null) return null;
        // короткий полёт из настроек: старт не ближе точки входа, иначе первым делом разворот назад
        length = Math.max(length, entry);
        Vec3 start = point.subtract(dir.scale(length)).add(0, weapon.spec().airframe().cruiseHeight(), 0);
        p.launch(start, target, point, owner);
        p.setRoute(Route.plan(start, point, dir, length, entry, side));
        startVirtual(level, p);
        return p;
    }

    /**
     * РСЗО: снаряд в трубе пакета у стреляющего (пакет доворачивается на цель, если молчит), иначе с позиции
     * за вынос пусковой из паспорта ({@link WeaponSpec.Route#standoff}). Неуправляемый: своё рассеивание (паспорт, {@link WeaponSpec.Route#dispersion}), за целью не следит.
     */
    @Nullable
    private static StrikeProjectile launchRocket(ServerLevel level, Target target, Vec3 point, float yaw,
                                                 @Nullable UUID owner, @Nullable ServerPlayer shooter) {
        RocketEntity r = ModEntities.ROCKET.get().create(level);
        if (r == null) return null;
        if (shooter != null && AirstrikeConfig.SERVER.launchNearPlayer.get()) {
            LauncherEntity launcher = aimedLauncher(level, shooter, WeaponType.ROCKET, point);
            if (launcher != null) {
                Slot slot = Slot.reserve(level, launcher);
                r.placeInTube(slot.rail(), launcher.getYRot(), launcher.elevation(), slot.ready(), slot.hidden(), target,
                        scatter(level, slot.rail(), point), shooter.getUUID());
                return r;
            }
        }
        Vec3 from = point.subtract(Local.horizontal(yaw).scale(WeaponType.ROCKET.spec().route().standoff()));
        r.launchFrom(from, target, scatter(level, from, point), owner);
        startVirtual(level, r);
        return r;
    }

    /**
     * Барражирующий боеприпас: с катапульты у стреляющего (доворачивается на цель, если молчит) прямо к цели,
     * иначе — издалека на высоте круга.
     */
    @Nullable
    private static StrikeProjectile launchLoiter(ServerLevel level, Target target, Vec3 point, float yaw,
                                                 @Nullable UUID owner, @Nullable ServerPlayer shooter) {
        LoiterEntity e = ModEntities.LOITER.get().create(level);
        if (e == null) return null;
        if (shooter != null && AirstrikeConfig.SERVER.launchNearPlayer.get()) {
            LauncherEntity launcher = aimedLauncher(level, shooter, WeaponType.LOITER, point);
            if (launcher != null) {
                Slot slot = Slot.reserve(level, launcher);
                e.placeOnLauncher(slot.rail(), launcher.getYRot(), launcher.elevation(), slot.ready(), slot.hidden(), target, point,
                        shooter.getUUID());
                e.setRoute(null);
                return e;
            }
        }
        WeaponSpec spec = WeaponType.LOITER.spec();
        Vec3 from = point.subtract(Local.horizontal(yaw).scale(spec.route().standoff())).add(0, spec.airframe().cruiseHeight(), 0);
        e.launch(from, target, point, owner);
        e.setRoute(null);
        startVirtual(level, e);
        return e;
    }

    /** Рассеивание неуправляемого снаряда по нормали: СКО — доля дальности из паспорта (1 %: у «Града» на 20 км — сотни метров). */
    private static Vec3 scatter(ServerLevel level, Vec3 from, Vec3 point) {
        double sigma = WeaponType.ROCKET.spec().route().sigma(Math.sqrt(from.distanceToSqr(point.x, from.y, point.z)));
        return point.add(level.random.nextGaussian() * sigma, 0, level.random.nextGaussian() * sigma);
    }

    /**
     * Бомба бьёт по точке на поверхности над целью (с разбросом ±2.5 блока) и за движущейся целью не следит;
     * если цель глубже 4 блоков под поверхностью (пещера, бункер), бомба пробивается к ней.
     */
    private static StrikeProjectile launchBomber(ServerLevel level, Target target, Vec3 point, float yaw, @Nullable UUID owner) {
        double jx = (level.random.nextInt(51) - 25) / 10.0, jz = (level.random.nextInt(51) - 25) / 10.0;
        int sx = Mth.floor(point.x + jx), sz = Mth.floor(point.z + jz);
        // поверхность под целью (чанк ради пуска не грузим): цель бывает в воздухе, а бомба падает на землю под ней;
        // у неготового чанка место с карты уже несёт свою оценку (карта клиента лучше генератора); к сбросу B-2
        // уточняет её по готовому чанку
        Terrain.Surface under = Terrain.estimate(level, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, sx, sz,
                target instanceof Target.Ground ? Terrain.Allowed.CHUNK : Terrain.Allowed.ORDER);
        double sy = under.known() ? under.y() : point.y + 0.5;
        Vec3 surface = new Vec3(point.x + jx, sy - 0.5, point.z + jz);
        // место с карты — на поверхности, бункера под ним нет (его высота бывает оценкой, а сосед по разбросу — готов)
        BlockPos goal = !(target instanceof Target.Ground) && surface.y - point.y >= 4 ? BlockPos.containing(point) : null;
        BomberEntity e = ModEntities.BOMBER.get().create(level);
        if (e == null) return null;
        WeaponSpec spec = WeaponType.BUNKER.spec();
        double length = spec.airframe().cruiseSpeed() * spec.route().seconds() * 20 + spec.route().finalLeg();
        e.launch(surface.subtract(Local.horizontal(yaw).scale(length)), surface, goal, owner);
        e.setRoute(null);
        startVirtual(level, e);
        return e;
    }

    /** Полёт начинается вне мира; в загруженном месте снаряд вернётся в мир в ближайшем тике. */
    private static void startVirtual(ServerLevel level, StrikeProjectile p) {
        VirtualFlights.launch(level, p);
    }

    /** Сирена и «ВОЗДУШНАЯ ТРЕВОГА» у всех в 350 блоках от цели (у каждого своя память, см. клиент). */
    public static void siren(ServerLevel level, WeaponType weapon, Vec3 at) {
        if (!AirstrikeConfig.SERVER.siren.get()) return;
        int kind = weapon.siren() == WeaponType.SirenKind.MISSILE ? S2C.Siren.MISSILE : S2C.Siren.AIR_RAID;
        PacketDistributor.sendToPlayersNear(level, null, at.x, at.y, at.z, ALERT_RADIUS, new S2C.Siren(at, kind));
    }

    /** Строка в лог сервера на каждый приказ (для tools/logscan.py): кто, чем, сколько, куда и за чем снаряды следят. */
    public static void log(ServerLevel level, String who, WeaponType weapon, int count, int spread, Target target, Vec3 point) {
        Airstrike.LOG.info("Удар: {} ×{} разброс {} по {} {} {} ({}) — {}", weapon.getSerializedName(), count, spread,
                Mth.floor(point.x), Mth.floor(point.y), Mth.floor(point.z), describe(level, target), who);
    }

    /** Цель для лога: точка, место с карты, игрок по нику, сущность по типу, аппарат. */
    private static String describe(ServerLevel level, Target target) {
        return switch (target) {
            case Target.Point p -> "точка";
            case Target.Ground g -> "место с карты";
            case Target.OfEntity e -> {
                // игрок — где бы он ни был (удар по игроку в другом измерении)
                ServerPlayer player = level.getServer().getPlayerList().getPlayer(e.uuid());
                if (player != null) yield "игрок " + player.getGameProfile().getName();
                Entity ent = level.getEntity(e.uuid());
                yield ent == null ? "сущность " + e.uuid() : "сущность " + BuiltInRegistries.ENTITY_TYPE.getKey(ent.getType());
            }
            case Target.OfSubLevel s -> "аппарат";
        };
    }

    /** Строка над хотбаром и щелчок пульта у того, кто пустил: что пущено и через сколько удар. */
    public static void confirm(ServerPlayer player, WeaponType weapon, int etaTicks) {
        player.displayClientMessage(Component.translatable("airstrike.launched." + weapon.getSerializedName(), (etaTicks + 19) / 20,
                        Component.keybind(Airstrike.CAMERA_KEY))
                .withStyle(ChatFormatting.RED), true);
        player.playNotifySound(SoundEvents.UI_BUTTON_CLICK.value(), SoundSource.MASTER, 1.0f, weapon.spec().confirmPitch());
    }
}
