package ua.zentix.airstrike.strike;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
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
import ua.zentix.airstrike.entity.Launcher;
import ua.zentix.airstrike.entity.LauncherEntity;
import ua.zentix.airstrike.entity.LoiterEntity;
import ua.zentix.airstrike.entity.RocketEntity;
import ua.zentix.airstrike.entity.StrikeProjectile;
import ua.zentix.airstrike.guidance.FlightController;
import ua.zentix.airstrike.guidance.Route;
import ua.zentix.airstrike.launcher.FixedLauncherBlockEntity;
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
 * ракета 30 с), а если оператор поставил на карте свои точки ({@link Waypoints}) — по ним. Большая часть пути проходит
 * вне загруженного мира ({@link VirtualFlights}). Без стреляющего рядом (консоль, командный блок, игрок в другом мире)
 * или без места под пусковую — заход издалека по той же схеме. B-2 всегда заходит издалека.
 * <p>
 * Приказ с местом пуска ({@code /airstrike salvo … from x z}) ставит пусковую на нём, кто бы ни приказал
 * ({@link LaunchSite.Post}); нет там места под неё или чанк не готов — снаряд стартует из этого места вне мира.
 * Приказ стационарной пусковой ({@link LaunchOrigin.Fixed}) пускает с её пакета и платит её запасом — по снаряду,
 * когда тот встаёт на направляющую; нельзя с неё (чанк не готов, блока нет, запас пуст, сектор закрыт) — пуск не
 * удался: стационарная пусковая не заменяется заходом издалека.
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
     * @param via         точки оператора: шахед, ракета и «Ланцет» летят по ним вместо петли в обход (курс захода
     *                    {@code approachYaw} им тогда не нужен); у остального оружия маршрута нет
     * @param from        откуда пуск ({@link LaunchOrigin}). Место пуска из приказа, по горизонтали (высоту берёт пуск):
     *                    шахед, ракета, РСЗО и «Ланцет» стартуют с пусковой на нём, а без места под неё — из него вне
     *                    мира; B-2 заходит с его стороны по курсу {@code approachYaw}, МБР — со своей площадки.
     *                    Стационарная пусковая — только то, что стартует с пакета, и только с неё. Null — у стреляющего
     *                    или издалека
     */
    public static Result launch(ServerLevel level, WeaponType weapon, Target target, Vec3 point, float approachYaw,
                                @Nullable UUID owner, boolean siren, Loadout.Nuke nuke, Waypoints via, @Nullable LaunchOrigin from) {
        ServerPlayer shooter = owner == null ? null : level.getServer().getPlayerList().getPlayer(owner);
        return launch(level, weapon, target, point, approachYaw, owner, shooter, siren, nuke, via, from);
    }

    /**
     * Пуск от имени игрока, который уже известен (пульт, команда игрока), — как {@link #launch}, но без поиска его
     * в списке игроков (GameTest: стреляющий — {@code FakePlayer} NeoForge, которого в списке нет).
     */
    public static Result launchBy(ServerLevel level, WeaponType weapon, Target target, Vec3 point, float approachYaw,
                                  ServerPlayer shooter, boolean siren, Loadout.Nuke nuke, Waypoints via, @Nullable LaunchOrigin from) {
        return launch(level, weapon, target, point, approachYaw, shooter.getUUID(), shooter, siren, nuke, via, from);
    }

    private static Result launch(ServerLevel level, WeaponType weapon, Target target, Vec3 point, float approachYaw, @Nullable UUID owner,
                                 @Nullable ServerPlayer shooter, boolean siren, Loadout.Nuke nuke, Waypoints via, @Nullable LaunchOrigin from) {
        if (shooter != null && shooter.level() != level) shooter = null;
        WeaponSpec spec = weapon.spec();
        FixedLauncherBlockEntity fixed = null;
        Munitions.Bill shot = null;
        if (from instanceof LaunchOrigin.Fixed f) {
            // с пакета стационарной пусковой: она на месте, на круге пакет этого оружия, и она платит снаряд из запаса
            fixed = f.launcher(level);
            if (fixed == null || spec.rack() == null || !fixed.hasRack() || fixed.weapon() != weapon) return Result.FAILED;
            shot = new Munitions.Bill(weapon, 1, 0);
            MutableComponent missing = Munitions.pay(fixed.store(), shot);
            if (missing != null) {
                fixed.report(missing);
                return Result.FAILED;
            }
        }
        boolean ok = false;
        try {
            Result r = launch(level, weapon, target, point, approachYaw, owner, shooter, siren, nuke, via, from, fixed);
            ok = r.ok();
            return r;
        } finally {
            // неудачный и упавший с ошибкой пуск — снаряд обратно в запас
            if (!ok && fixed != null) Munitions.refund(fixed.store(), shot);
        }
    }

    private static Result launch(ServerLevel level, WeaponType weapon, Target target, Vec3 point, float approachYaw, @Nullable UUID owner,
                                 @Nullable ServerPlayer shooter, boolean siren, Loadout.Nuke nuke, Waypoints via, @Nullable LaunchOrigin from,
                                 @Nullable FixedLauncherBlockEntity fixed) {
        if (siren) StrikeWorld.get(level).newOrder(owner, weapon);
        WeaponSpec spec = weapon.spec();
        // промах этого пуска (паспорт): снаряд целится в цель со сдвигом — у сущности сдвиг относительно неё
        Vec3 miss = spec.route().miss(level.random);
        if (!miss.equals(Vec3.ZERO)) {
            target = target.offset(miss);
            point = point.add(miss);
        }
        if (spec.launch() == WeaponSpec.Launch.ICBM) return launchIcbm(level, target, point, nuke, shooter, AirstrikeConfig.SERVER.nukeFlightTime.get());
        Loadout.Nuke warhead = nuke.onCarrier() && Loadout.carriesNuke(weapon) ? nuke : null;
        Stand stand = fixed != null ? Stand.fixed(fixed) : new Stand(post(level, shooter, owner, from, point), null, from == null ? null : from.at());
        StrikeProjectile p = switch (spec.launch()) {
            case GUIDED -> launchGuided(level, weapon, target, point, approachYaw, owner, shooter, stand, via);
            case ROCKET -> launchRocket(level, target, point, approachYaw, owner, shooter, stand);
            case LOITER -> launchLoiter(level, target, point, approachYaw, owner, shooter, stand, via);
            case BOMBER -> launchBomber(level, target, point, approachYaw, owner);
            case ICBM -> throw new IllegalStateException("МБР пускает NuclearStrikes");
        };
        if (p == null) return Result.FAILED;
        p.setNuclear(warhead);
        if (siren && AirstrikeConfig.SERVER.siren.get()) p.armSiren(spec.sirenLead());
        int eta = p.etaTicks();
        if (!p.isVirtual()) {
            // на пусковой: тикеты — до входа в мир, место пуска из приказа может быть там, где сущности не тикают
            p.holdChunks(level);
            if (!level.addFreshEntity(p)) {
                p.discard();
                return Result.FAILED;
            }
        }
        return new Result(true, eta);
    }

    /**
     * МБР: бьёт по координатам — за движущейся целью не следит; тревогу поднимает сам пуск.
     *
     * @param flightTicks полёт от пуска до подрыва (настройка мира; у удара не оператора — не меньше 90 с, {@link NuclearKeys})
     */
    public static Result launchIcbm(ServerLevel level, Target target, Vec3 point, Loadout.Nuke nuke, @Nullable ServerPlayer shooter, int flightTicks) {
        if (shooter != null && shooter.level() != level) shooter = null;
        boolean ok = target instanceof Target.Ground
                ? NuclearStrikes.launch(level, point, true, nuke.yieldKt(), nuke.airBurst(), shooter, flightTicks)
                : NuclearStrikes.launch(level, NuclearStrikes.ground(level, point), false, nuke.yieldKt(), nuke.airBurst(), shooter, flightTicks);
        return new Result(ok, flightTicks);
    }

    /**
     * Где встаёт пусковая: на месте пуска {@code from} из приказа, иначе у стреляющего (если так настроен мир); null —
     * пускать без пусковой.
     */
    @Nullable
    private static LaunchSite.Post post(ServerLevel level, @Nullable ServerPlayer shooter, @Nullable UUID owner, @Nullable LaunchOrigin from, Vec3 point) {
        if (from != null) return LaunchSite.Post.ordered(level, from.at(), point, owner);
        return shooter != null && AirstrikeConfig.SERVER.launchNearPlayer.get() ? LaunchSite.Post.of(shooter) : null;
    }

    /**
     * Откуда стартует снаряд шахеда, ракеты, РСЗО и «Ланцета».
     *
     * @param post  огневая позиция мобильной пусковой (у стреляющего или на месте пуска из приказа); null — без неё
     * @param fixed стационарная пусковая: только с неё, без пусковой у стреляющего и без захода издалека
     * @param from  место пуска (по горизонтали): без пусковой снаряд стартует из него вне мира, и заход — из-за него
     */
    private record Stand(@Nullable LaunchSite.Post post, @Nullable FixedLauncherBlockEntity fixed, @Nullable Vec3 from) {
        static Stand fixed(FixedLauncherBlockEntity be) {
            return new Stand(null, be, be.position());
        }
    }

    /** Длина маршрута на время полёта из настроек (паспорт). */
    private static double pathLength(WeaponType weapon) {
        return weapon.spec().airframe().cruiseSpeed() * weapon.spec().route().seconds() * 20;
    }

    /**
     * Последний прямой участок перед целью (паспорт: шахед 300 блоков, ракета 500) — и всегда из-за спины того, кто
     * стреляет, или из-за места пуска ({@code stand}; null — ни того, ни другого).
     */
    private static double entryDistance(WeaponType weapon, Vec3 point, @Nullable Vec3 stand) {
        double base = weapon.spec().route().finalLeg();
        if (stand == null) return base;
        double dx = point.x - stand.x, dz = point.z - stand.z;
        return Math.max(base, Math.sqrt(dx * dx + dz * dz) + 150);
    }

    /** Снаряд оружия, ещё не в мире. */
    @Nullable
    private static StrikeProjectile create(ServerLevel level, WeaponType weapon) {
        return weapon.spec().airframe().entity().get().create(level);
    }

    /**
     * Шахед или ракета: со стационарной пусковой, с пусковой огневой позиции, иначе из места пуска вне мира или
     * издалека ({@link Stand}). Снаряд ещё не добавлен в мир.
     */
    @Nullable
    private static StrikeProjectile launchGuided(ServerLevel level, WeaponType weapon, Target target, Vec3 point, float yaw, @Nullable UUID owner,
                                                 @Nullable ServerPlayer shooter, Stand stand, Waypoints via) {
        Vec3 behind = stand.from() != null ? stand.from() : shooter != null ? shooter.position() : null;
        Course course = via.isEmpty()
                ? new Loop(point, Local.horizontal(yaw), pathLength(weapon), entryDistance(weapon, point, behind), level.random.nextBoolean() ? 1 : -1)
                : new Through(point, via);
        if (stand.fixed() != null) {
            int option = aimFixed(level, stand.fixed(), weapon, point, course);
            return option < 0 ? null : onLauncher(level, weapon, target, point, course, stand.fixed(), option, owner);
        }
        if (stand.post() != null) {
            StrikeProjectile p = fromLauncher(level, weapon, target, point, course, stand.post(), shooter);
            if (p != null) return p;
        }
        return fromAfar(level, weapon, target, point, course, owner, stand.from());
    }

    /**
     * Шахед или ракета от имени {@code shooter}, как с пульта, но без поиска игрока в списке (GameTest: стреляющий —
     * {@code FakePlayer} NeoForge). Снаряд ещё не добавлен в мир: с пусковой — добавить, вне мира — уже летит.
     */
    @Nullable
    public static StrikeProjectile launchGuided(ServerLevel level, WeaponType weapon, Vec3 point, float yaw, ServerPlayer shooter, Waypoints via) {
        StrikeWorld.get(level).newOrder(shooter.getUUID(), weapon);
        Stand stand = new Stand(post(level, shooter, shooter.getUUID(), null, point), null, null);
        return launchGuided(level, weapon, new Target.Point(point), point, yaw, shooter.getUUID(), shooter, stand, via);
    }

    /**
     * Путь шахеда или ракеты к цели от места старта: петля в обход ({@link Loop}) или маршрут оператора ({@link Through}).
     * Пусковая смотрит на первую точку маршрута, поэтому её курс выбирается по маршруту от её места.
     */
    private sealed interface Course permits Loop, Through {
        /** Сколько вариантов маршрута, по порядку предпочтения (у петли — обход с одной и с другой стороны). */
        int options();

        /** Маршрут варианта {@code option} от {@code start}, до которого снаряд от пусковой уже пролетел {@code travelled} блоков. */
        Route from(Vec3 start, double travelled, int option);

        /** Курс пусковой — не дальше {@link #MAX_OFF_TARGET} от направления на эту точку: туда идёт путь. */
        Vec3 heading();

        /** Откуда заходит снаряд издалека (по горизонтали; высоту ставит пуск) — по варианту 0. */
        Vec3 afar(WeaponType weapon);
    }

    /**
     * Петля в обход и заход из-за спины стреляющего ({@link Route#plan}): путь {@code length} на время полёта из настроек,
     * последний прямой участок {@code entry}, обход со стороны {@code side} (вариант 1 — с другой).
     */
    private record Loop(Vec3 point, Vec3 dir, double length, double entry, double side) implements Course {
        @Override
        public int options() {
            return 2;
        }

        @Override
        public Route from(Vec3 start, double travelled, int option) {
            return Route.plan(start, point, dir, Math.max(0, length - travelled), entry, option == 0 ? side : -side);
        }

        @Override
        public Vec3 heading() {
            return point;
        }

        /** На прямой захода, на длину пути от цели; короткий полёт из настроек — не ближе точки входа, иначе первым делом разворот назад. */
        @Override
        public Vec3 afar(WeaponType weapon) {
            double back = Math.max(length, entry);
            return new Vec3(point.x - dir.x * back, 0, point.z - dir.z * back);
        }
    }

    /** Маршрут оператора: по его точкам, после последней — на цель. */
    private record Through(Vec3 point, Waypoints via) implements Course {
        @Override
        public int options() {
            return 1;
        }

        @Override
        public Route from(Vec3 start, double travelled, int option) {
            return via.route(start);
        }

        @Override
        public Vec3 heading() {
            return via.points().getFirst();
        }

        /** Перед первой точкой на последний прямой участок из паспорта: к ней снаряд подходит по курсу маршрута. */
        @Override
        public Vec3 afar(WeaponType weapon) {
            return via.afar(point, weapon.spec().route().finalLeg());
        }
    }

    /**
     * Курс пусковой шахедов и ракет не дальше этого от направления пути ({@link Course#heading}: на цель, у маршрута
     * оператора — на его первую точку), °: иначе залп уходил бы не туда.
     */
    private static final float MAX_OFF_TARGET = 90;

    /** Шахед или ракета на пусковой огневой позиции {@code post}; null — пусковой там нет и поставить её негде. */
    @Nullable
    private static StrikeProjectile fromLauncher(ServerLevel level, WeaponType weapon, Target target, Vec3 point, Course course,
                                                 LaunchSite.Post post, @Nullable ServerPlayer shooter) {
        // пусковая, чей сектор пуска упирается в постройку, не годится: снаряд разбился бы о неё до взведения; проверка
        // своей и поиск места — под одним пределом
        LaunchSite.Budget budget = new LaunchSite.Budget();
        LauncherEntity launcher = LaunchSite.existing(level, post, weapon, l -> LaunchSite.clearAhead(level, l, point, budget));
        int option = 0;
        if (launcher == null) {
            if (StrikeWorld.get(level).noLaunchSite(post.owner(), weapon, post.chunk())) return null;
            // пакет смотрит на первую точку маршрута — у петли обход с одной или с другой стороны, какой свободен; иначе
            // поворачивается (не дальше MAX_OFF_TARGET от направления пути), пока не найдёт свободный сектор
            LaunchSite.Pick pick = LaunchSite.findClear(level, post, weapon, course.heading(), site -> {
                float[] yaws = new float[course.options()];
                for (int i = 0; i < yaws.length; i++) {
                    Route plan = course.from(site, 0, i);
                    yaws[i] = FlightController.anglesTo(site, plan.current() == null ? point : plan.current())[0];
                }
                return yaws;
            }, MAX_OFF_TARGET, budget);
            if (pick == null) {
                noLaunchSite(level, post, shooter, weapon, budget);
                return null;
            }
            option = Math.max(0, pick.preferred());
            launcher = LaunchSite.deploy(level, pick.site(), pick.yaw(), weapon, post);
        }
        return onLauncher(level, weapon, target, point, course, launcher, option, post.owner());
    }

    /** Шахед или ракета в ячейке пусковой {@code launcher} (мобильной или стационарной), путь — вариант {@code option}. */
    @Nullable
    private static StrikeProjectile onLauncher(ServerLevel level, WeaponType weapon, Target target, Vec3 point, Course course, Launcher launcher,
                                               int option, @Nullable UUID owner) {
        StrikeProjectile p = create(level, weapon);
        if (p == null) return null;
        Slot slot = Slot.reserve(level, launcher);
        p.placeOnLauncher(slot.rail(), launcher.yaw(), launcher.elevation(), slot.ready(), slot.hidden(), target, point, owner);
        // первая точка маршрута — на курсе пусковой в дальности взведения: до неё снаряд идёт ровно по проверенному
        // сектору, а доворачивает на маршрут уже взведённым
        Vec3 gate = gate(slot.rail(), launcher.yaw());
        p.setRoute(course.from(gate, ProximityFuse.ARM_DISTANCE, option).after(gate, slot.rail()));
        return p;
    }

    /**
     * Курс стационарной пусковой {@code be} под шахед или ракету по пути {@code course}. Пакет, который не молчит
     * (идёт залп), пускает своим курсом, если его сектор свободен; молчащий остаётся на курсе, если путь и так идёт по
     * нему (не дальше {@link Launcher#TURN_THRESHOLD}) и сектор свободен, иначе доворачивается: на вариант пути, чей
     * сектор свободен, или поворотом не дальше {@link #MAX_OFF_TARGET} от направления пути ({@link LaunchSite#pickOn}
     * на её опоре). Ставить пусковую в другом месте нельзя: она блок.
     *
     * @return вариант пути; −1 — сектор закрыт (причина — в состоянии пусковой, {@link FixedLauncherBlockEntity#report})
     */
    private static int aimFixed(ServerLevel level, FixedLauncherBlockEntity be, WeaponType weapon, Vec3 point, Course course) {
        long now = level.getGameTime();
        Vec3 site = be.position();
        float[] want = new float[course.options()];
        int option = 0;
        for (int i = 0; i < want.length; i++) {
            Route plan = course.from(site, 0, i);
            want[i] = FlightController.anglesTo(site, plan.current() == null ? point : plan.current())[0];
            if (Math.abs(Mth.wrapDegrees(want[i] - be.yaw())) < Math.abs(Mth.wrapDegrees(want[option] - be.yaw()))) option = i;
        }
        LaunchSite.Budget budget = new LaunchSite.Budget();
        boolean silent = be.queue().silent(now);
        if (!silent || Math.abs(Mth.wrapDegrees(want[option] - be.yaw())) <= Launcher.TURN_THRESHOLD) {
            if (LaunchSite.clearAhead(level, be, point, budget)) return option;
            if (!silent) return sectorClosed(be);
        }
        LaunchSite.Pick pick = LaunchSite.pickOn(level, be.mount(), site, weapon, want, course.heading(), MAX_OFF_TARGET, budget);
        if (pick == null) return sectorClosed(be);
        be.turnTo(pick.yaw(), now);
        // свободный курс в пределах мелкого доворота: пакет остался на прежнем, а его сектор проверен выше
        if (be.yaw() != pick.yaw() && !LaunchSite.clearAhead(level, be, point, budget)) return sectorClosed(be);
        return Math.max(0, pick.preferred());
    }

    /** Сектор пуска стационарной пусковой закрыт: причина — в её состоянии. */
    private static int sectorClosed(FixedLauncherBlockEntity be) {
        be.report(Component.translatable("airstrike.fixed_launcher.sector"));
        return -1;
    }

    /** Точка маршрута на курсе пусковой {@code yaw} в дальности взведения от направляющей {@code rail}. */
    private static Vec3 gate(Vec3 rail, float yaw) {
        return rail.add(Local.horizontal(yaw).scale(ProximityFuse.ARM_DISTANCE));
    }

    /**
     * Места пусковой у огневой позиции {@code post} нет (нет ровного места под небом, сектор пуска везде упирается
     * в постройки или проверки дошли до предела {@code budget}): удар идёт издалека, а с места пуска из приказа —
     * из него без пусковой. Строка стреляющему над хотбаром и в лог — раз на приказ (залп): остаток залпа с этого
     * чанка идёт без пусковой без новых поисков ({@link StrikeWorld#noLaunchSite}).
     */
    private static void noLaunchSite(ServerLevel level, LaunchSite.Post post, @Nullable ServerPlayer shooter, WeaponType weapon,
                                     LaunchSite.Budget budget) {
        if (!StrikeWorld.get(level).rememberNoLaunchSite(post.owner(), weapon, post.chunk())) return;
        String why = budget.out() ? "проверки сектора пуска дошли до предела в " + LaunchSite.Budget.CELLS + " клеток"
                : "нет места или сектор пуска упирается в постройки";
        String who = shooter != null ? shooter.getGameProfile().getName() : "консоли";
        Airstrike.LOG.info("Пуск: {} — пусковую {} негде поставить ({}) у {}, {}", weapon.getSerializedName(),
                post.ordered() ? "на месте пуска" : "у " + who, why, BlockPos.containing(post.center()),
                post.ordered() ? "старт оттуда без неё" : "заход издалека");
        if (shooter != null) {
            shooter.displayClientMessage(Component.translatable(post.ordered() ? "airstrike.launch.no_site.ordered" : "airstrike.launch.no_site")
                    .withStyle(ChatFormatting.GOLD), true);
        }
    }

    /**
     * Ячейка пусковой под снаряд.
     *
     * @param rail   точка схода на направляющей
     * @param ready  через сколько тиков поджиг
     * @param hidden сколько тиков снаряд скрыт в пакете, пока тот поднимается
     */
    private record Slot(Vec3 rail, int ready, int hidden) {
        static Slot reserve(ServerLevel level, Launcher launcher) {
            long now = level.getGameTime();
            int[] slot = launcher.reserve(now, launcher.rack().minReady(), launcher.rack().busyTicks());
            return new Slot(launcher.railPoint(slot[0]), slot[1], launcher.raisingTicks(now));
        }
    }

    /**
     * Пусковая огневой позиции {@code post}, наведённая на точку {@code point} (РСЗО — на цель, барражирующие — на цель
     * или первую точку маршрута оператора): своя — доворачивается, если молчит; нет своей — ставится новая. {@code null},
     * если места под неё нет.
     */
    @Nullable
    private static LauncherEntity aimedLauncher(ServerLevel level, LaunchSite.Post post, @Nullable ServerPlayer shooter, WeaponType weapon, Vec3 point) {
        // своя — если свободен сектор пуска (катапульта, труба пакета) с курсом, который у пакета будет после доворота на
        // цель: иначе снаряд разбился бы о постройку; проверка своей и поиск места — под одним пределом
        long now = level.getGameTime();
        LaunchSite.Budget budget = new LaunchSite.Budget();
        LauncherEntity launcher = LaunchSite.existing(level, post, weapon,
                l -> LaunchSite.clearAhead(level, l.position(), l.turnedYaw(FlightController.anglesTo(l.position(), point)[0], now), weapon, point, budget));
        if (launcher != null) {
            launcher.turnTo(FlightController.anglesTo(launcher.position(), point)[0], now);
            return launcher;
        }
        if (StrikeWorld.get(level).noLaunchSite(post.owner(), weapon, post.chunk())) return null;
        // пакет наводится на цель сам: поворачивать его нельзя, только другое место
        LaunchSite.Pick pick = LaunchSite.findClear(level, post, weapon, point, site -> new float[]{FlightController.anglesTo(site, point)[0]}, 0, budget);
        if (pick == null) {
            noLaunchSite(level, post, shooter, weapon, budget);
            return null;
        }
        return LaunchSite.deploy(level, pick.site(), pick.yaw(), weapon, post);
    }

    /**
     * Стационарная пусковая {@code be}, наведённая на точку {@code point}, как {@link #aimedLauncher}: молчащая
     * доворачивается; null — сектор пуска с её курсом закрыт (причина — в её состоянии).
     */
    @Nullable
    private static Launcher aimedFixed(ServerLevel level, FixedLauncherBlockEntity be, WeaponType weapon, Vec3 point) {
        long now = level.getGameTime();
        float yaw = be.turnedYaw(FlightController.anglesTo(be.position(), point)[0], now);
        if (!LaunchSite.clearAhead(level, be.mount(), be.position(), yaw, weapon, point, new LaunchSite.Budget())) {
            sectorClosed(be);
            return null;
        }
        be.turnTo(yaw, now);
        return be;
    }

    /** Пусковая под РСЗО и «Ланцет» по точке {@code point}: стационарная, иначе огневой позиции; null — нет. */
    @Nullable
    private static Launcher aimed(ServerLevel level, Stand stand, @Nullable ServerPlayer shooter, WeaponType weapon, Vec3 point) {
        if (stand.fixed() != null) return aimedFixed(level, stand.fixed(), weapon, point);
        return stand.post() == null ? null : aimedLauncher(level, stand.post(), shooter, weapon, point);
    }

    /**
     * Без пусковой: снаряд начинает полёт вне мира в месте пуска {@code from}, а без него — издалека: на прямой захода,
     * на расстоянии времени полёта от цели, а по маршруту оператора — перед его первой точкой (если это место уже
     * загружено — сразу в мире).
     */
    private static StrikeProjectile fromAfar(ServerLevel level, WeaponType weapon, Target target, Vec3 point, Course course,
                                             @Nullable UUID owner, @Nullable Vec3 from) {
        StrikeProjectile p = create(level, weapon);
        if (p == null) return null;
        Vec3 at = from != null ? from : course.afar(weapon);
        Vec3 start = new Vec3(at.x, point.y + weapon.spec().airframe().cruiseHeight(), at.z);
        p.launch(start, target, point, owner);
        p.setRoute(course.from(start, 0, 0));
        startVirtual(level, p);
        return p;
    }

    /**
     * РСЗО: снаряд в трубе пакета стационарной пусковой или огневой позиции (пакет доворачивается на цель, если
     * молчит), иначе с места пуска или с позиции за вынос пусковой из паспорта ({@link WeaponSpec.Route#standoff}).
     * Неуправляемый: своё рассеивание (паспорт, {@link WeaponSpec.Route#dispersion}), за целью не следит.
     */
    @Nullable
    private static StrikeProjectile launchRocket(ServerLevel level, Target target, Vec3 point, float yaw, @Nullable UUID owner,
                                                 @Nullable ServerPlayer shooter, Stand stand) {
        RocketEntity r = ModEntities.ROCKET.get().create(level);
        if (r == null) return null;
        Launcher launcher = aimed(level, stand, shooter, WeaponType.ROCKET, point);
        if (launcher != null) {
            Slot slot = Slot.reserve(level, launcher);
            r.placeInTube(slot.rail(), launcher.yaw(), launcher.elevation(), slot.ready(), slot.hidden(), target,
                    scatter(level, slot.rail(), point), owner);
            return r;
        }
        if (stand.fixed() != null) return null;
        Vec3 from = stand.from();
        Vec3 start = from != null ? new Vec3(from.x, point.y, from.z)
                : point.subtract(Local.horizontal(yaw).scale(WeaponType.ROCKET.spec().route().standoff()));
        r.launchFrom(start, target, scatter(level, start, point), owner);
        startVirtual(level, r);
        return r;
    }

    /**
     * Барражирующий боеприпас: с катапульты стационарной пусковой или огневой позиции (доворачивается на цель, если
     * молчит) прямо к цели, иначе — с места пуска или издалека на высоте круга. По маршруту оператора катапульта
     * смотрит на его первую точку, а издалека боеприпас заходит к ней за вынос пусковой из паспорта.
     */
    @Nullable
    private static StrikeProjectile launchLoiter(ServerLevel level, Target target, Vec3 point, float yaw, @Nullable UUID owner,
                                                 @Nullable ServerPlayer shooter, Stand stand, Waypoints via) {
        LoiterEntity e = ModEntities.LOITER.get().create(level);
        if (e == null) return null;
        Launcher launcher = aimed(level, stand, shooter, WeaponType.LOITER, via.isEmpty() ? point : via.points().getFirst());
        if (launcher != null) {
            Slot slot = Slot.reserve(level, launcher);
            e.placeOnLauncher(slot.rail(), launcher.yaw(), launcher.elevation(), slot.ready(), slot.hidden(), target, point, owner);
            e.setRoute(via.isEmpty() ? null : via.route(slot.rail()));
            return e;
        }
        if (stand.fixed() != null) return null;
        Vec3 from = stand.from();
        WeaponSpec spec = WeaponType.LOITER.spec();
        Vec3 start = from != null ? new Vec3(from.x, point.y, from.z)
                : via.isEmpty() ? point.subtract(Local.horizontal(yaw).scale(spec.route().standoff()))
                : via.afar(point, spec.route().standoff()).add(0, point.y, 0);
        start = start.add(0, spec.airframe().cruiseHeight(), 0);
        e.launch(start, target, point, owner);
        e.setRoute(via.isEmpty() ? null : via.route(start));
        startVirtual(level, e);
        return e;
    }

    /** Рассеивание неуправляемого снаряда по нормали: СКО — доля дальности из паспорта (1 %: у «Града» на 20 км — сотни метров). */
    private static Vec3 scatter(ServerLevel level, Vec3 from, Vec3 point) {
        double sigma = WeaponType.ROCKET.spec().route().sigma(Math.sqrt(from.distanceToSqr(point.x, from.y, point.z)));
        return point.add(level.random.nextGaussian() * sigma, 0, level.random.nextGaussian() * sigma);
    }

    /**
     * Бомба бьёт по точке на поверхности над целью (промах — паспорт, {@link WeaponSpec.Route#error}) и за движущейся
     * целью не следит; если цель глубже 4 блоков под поверхностью (пещера, бункер), бомба пробивается к ней.
     */
    private static StrikeProjectile launchBomber(ServerLevel level, Target target, Vec3 point, float yaw, @Nullable UUID owner) {
        int sx = Mth.floor(point.x), sz = Mth.floor(point.z);
        // поверхность под целью (чанк ради пуска не грузим): цель бывает в воздухе, а бомба падает на землю под ней;
        // у неготового чанка место с карты уже несёт свою оценку (карта клиента лучше генератора); к сбросу B-2
        // уточняет её по готовому чанку
        Terrain.Surface under = Terrain.estimate(level, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, sx, sz,
                target instanceof Target.Ground ? Terrain.Allowed.CHUNK : Terrain.Allowed.ORDER);
        double sy = under.known() ? under.y() : point.y + 0.5;
        Vec3 surface = new Vec3(point.x, sy - 0.5, point.z);
        // место с карты — на поверхности, бункера под ним нет (его высота бывает оценкой, а сосед по промаху — готов)
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

    /**
     * Строка в лог сервера на каждый приказ (для tools/logscan.py): кто, чем, сколько, куда, за чем снаряды следят и,
     * если приказ их задал, — место пуска и маршрут по точкам оператора.
     */
    public static void log(ServerLevel level, String who, WeaponType weapon, int count, int spread, Target target, Vec3 point, Waypoints via,
                           @Nullable LaunchOrigin from) {
        StringBuilder route = new StringBuilder();
        switch (from) {
            case LaunchOrigin.Place p -> route.append(", пуск с ").append(Mth.floor(p.at().x)).append(' ').append(Mth.floor(p.at().z));
            case LaunchOrigin.Fixed f -> route.append(", с пусковой ").append(f.pos().toShortString());
            case null -> {}
        }
        for (int i = 0; i < via.size(); i++) {
            Vec3 q = via.points().get(i);
            route.append(i == 0 ? ", маршрут " : " → ").append(Mth.floor(q.x)).append(' ').append(Mth.floor(q.z));
        }
        Airstrike.LOG.info("Удар: {} ×{} разброс {} по {} {} {} ({}{}) — {}", weapon.getSerializedName(), count, spread,
                Mth.floor(point.x), Mth.floor(point.y), Mth.floor(point.z), describe(level, target), route, who);
    }

    /** Цель для лога: точка, место с карты, игрок по нику, сущность по типу, аппарат; у замеченной — «по месту, где видели». */
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
            case Target.Sighted s -> describe(level, s.quarry()) + " по месту, где видели";
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
