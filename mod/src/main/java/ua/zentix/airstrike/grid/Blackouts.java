package ua.zentix.airstrike.grid;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;
import ua.zentix.airstrike.Airstrike;
import ua.zentix.airstrike.AirstrikeConfig;
import dev.ryanhcode.sable.companion.SubLevelAccess;
import ua.zentix.airstrike.compat.SubLevels;
import ua.zentix.airstrike.net.S2C;
import ua.zentix.airstrike.nuclear.Detonation;
import ua.zentix.airstrike.nuclear.world.WorkClock;
import ua.zentix.airstrike.registry.ModAttachments;
import ua.zentix.airstrike.work.WorkScheduler;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Блэкаут: удар по узлу сети (подстанции), ядерный удар или команда обесточивают район — кварталы гаснут каскадом
 * от центра, а через {@code grid.restore_minutes} свет возвращается вразнобой. Здесь входы и подписки на события;
 * работа по чанкам — {@link BlackoutWorld}, состояние — {@link PowerGrid}.
 */
public final class Blackouts {
    /** Взрыв выбивает узел, если его центр ближе этого (блоки) плюс радиус взрыва: подстанция — площадка, не точка. */
    static final double HIT = 6;
    /** Кому слышен и виден выход подстанции из строя (блоки). */
    private static final double FAILURE_RANGE = 320;
    /** Каскад ядерного удара: электромагнитный импульс и волна гасят район почти разом (блоков за тик). */
    private static final double NUKE_SPEED = 50;
    /** Возврат света по команде — за 10 с. */
    private static final int COMMAND_RESTORE_SPREAD = 200;
    /** Самый слабый взрыв, выбивающий подстанцию: огненный шар гаста (1) — нет, крипер (3) и TNT (4) — да. */
    private static final float MIN_BLAST = 2f;

    private Blackouts() {}

    // ---------------------------------------------------------------- входы

    /**
     * Взрыв рядом с узлом сети выводит его из строя (удар мода, TNT, крипер — подстанции всё равно). Порыв ветра
     * (заряд ветра, вихрь) и совсем слабые хлопки — не взрыв для неё.
     */
    public static void onExplosion(ExplosionEvent.Detonate e) {
        if (!(e.getLevel() instanceof ServerLevel level) || !AirstrikeConfig.SERVER.gridEnabled.get()) return;
        if (e.getExplosion().getBlockInteraction() == Explosion.BlockInteraction.TRIGGER_BLOCK || e.getExplosion().radius() < MIN_BLAST) return;
        Vec3 at = e.getExplosion().center();
        double reach = HIT + e.getExplosion().radius();
        List<Node> hit = new ArrayList<>();
        for (Node n : PowerGrid.get(level).nodes()) {
            if (Vec3.atCenterOf(n.pos()).distanceToSqr(at) <= reach * reach) hit.add(n);
        }
        hit.forEach(n -> knockOut(level, n));
    }

    /**
     * Узел выведен из строя: хлопок, дуга и искры, район гаснет каскадом от него. Уже выбитый узел — свет вернут
     * позже (ремонт начинается заново).
     */
    public static void knockOut(ServerLevel level, Node node) {
        PowerGrid grid = PowerGrid.get(level);
        long now = level.getGameTime();
        grid.downOutage(node.id(), now).ifPresentOrElse(o -> {
            if (o.restoreAt() != Outage.NEVER) {
                Outage later = o.restoring(restoreAt(now), restoreSpread());
                grid.replace(later);
                BlackoutWorld.get(level).onRestore(later);
            }
        }, () -> {
            Vec3 c = Vec3.atCenterOf(node.pos());
            blackout(level, c, node.radius(), speed(), node.id());
            PacketDistributor.sendToPlayersNear(level, null, c.x, c.y, c.z, FAILURE_RANGE, new S2C.GridFailure(c, level.random.nextLong()));
        });
        Substations.sync(level, grid, now);
    }

    /**
     * Ядерный удар обесточивает всё, до чего доходит (стёкла или ожоги), сразу по вспышке; подстанции в этом радиусе
     * выбиты — каждая гасит и свой район.
     */
    public static void nuke(ServerLevel level, Detonation d) {
        if (!AirstrikeConfig.SERVER.gridEnabled.get() || !AirstrikeConfig.SERVER.gridNuke.get()) return;
        Outage o = blackout(level, d.burst(), d.radiusMax(), NUKE_SPEED, -1);
        List<Node> hit = new ArrayList<>();
        for (Node n : PowerGrid.get(level).nodes()) {
            if (Math.hypot(n.pos().getX() + 0.5 - o.x(), n.pos().getZ() + 0.5 - o.z()) <= o.radius()) hit.add(n);
        }
        hit.forEach(n -> knockOut(level, n));
    }

    /**
     * Отключение района с центром {@code at}: каскад со скоростью {@code speed} блоков за тик, свет вернётся по
     * настройке. Радиус — не больше {@code grid.max_radius}, откуда бы он ни пришёл (узел, команда, ядерный удар).
     *
     * @param node узел, чей выход из строя его вызвал; -1 — не узел
     */
    public static Outage blackout(ServerLevel level, Vec3 at, double radius, double speed, int node) {
        radius = Math.min(radius, AirstrikeConfig.SERVER.gridMaxRadius.get());
        long now = level.getGameTime();
        Outage o = PowerGrid.get(level).addOutage(at.x, at.z, radius, now, speed, restoreAt(now), restoreSpread(), node);
        BlackoutWorld.get(level).onOutage(o);
        Airstrike.LOG.info("Блэкаут №{} ({}): {} {}, радиус {}, {}", o.id(), level.dimension().location(), Math.round(at.x), Math.round(at.z), Math.round(radius),
                o.restoreAt() == Outage.NEVER ? "свет не вернётся сам" : String.format(Locale.ROOT, "свет вернётся через %d мин",
                        (o.restoreAt() - now) / 1200));
        return o;
    }

    /**
     * Вернуть свет сейчас (за 10 с вразнобой) в отключениях, чей центр ближе {@code radius} к {@code at}; null — во всех.
     * Отключение, в которое свет уже возвращается, — быстрее: оставшиеся кварталы тоже за 10 с. Чанки вне
     * загруженного мира ничего не ждут: на диске у них настоящие лампы ({@link ChunkSaves}).
     *
     * @return сколько отключений снято
     */
    public static int restore(ServerLevel level, @Nullable Vec3 at, double radius) {
        return restore(level, at, radius, COMMAND_RESTORE_SPREAD);
    }

    /** То же, свет — вразнобой за {@code spread} тиков (0 — во всех кварталах сейчас). */
    public static int restore(ServerLevel level, @Nullable Vec3 at, double radius, int spread) {
        PowerGrid grid = PowerGrid.get(level);
        long now = level.getGameTime();
        int n = 0;
        for (Outage o : List.copyOf(grid.outages())) {
            if (at != null && Math.hypot(o.x() - at.x, o.z() - at.z) > radius) continue;
            Outage restoring;
            if (o.restoreAt() > now) {
                restoring = o.restoring(now, spread);
            } else {
                // разброс только сжимается: кварталы, где свет уже есть, так и остаются светлыми
                long left = Math.min(o.restoreSpread(), now - o.restoreAt() + spread);
                if (left >= o.restoreSpread()) continue;
                restoring = o.restoring(o.restoreAt(), (int) left);
            }
            grid.replace(restoring);
            // каскад возврата — заново по новым срокам
            grid.swept(o.id(), true, Long.MIN_VALUE);
            BlackoutWorld.get(level).onRestore(restoring);
            Airstrike.LOG.info("Блэкаут №{} ({}): свет возвращают по команде", o.id(), level.dimension().location());
            n++;
        }
        Substations.sync(level, grid, now);
        return n;
    }

    // ---------------------------------------------------------------- настройки

    private static double speed() {
        return AirstrikeConfig.SERVER.gridCascadeSpeed.get() / 20;
    }

    private static long restoreAt(long now) {
        int minutes = AirstrikeConfig.SERVER.gridRestoreMinutes.get();
        return minutes == 0 ? Outage.NEVER : now + minutes * 1200L;
    }

    private static int restoreSpread() {
        return AirstrikeConfig.SERVER.gridRestoreSpread.get() * 20;
    }

    // ---------------------------------------------------------------- события

    /**
     * Блэкаут всех измерений — полоса {@link WorkScheduler.Lane#GRID} общего бюджета тика: не больше
     * {@code grid.ms_per_tick} и не больше, чем оставили полосы выше. {@code levels} — первым идёт следующее
     * измерение, как у ядерных очередей.
     *
     * @param clock часы полосы, уже запущены
     */
    public static void work(MinecraftServer server, List<ServerLevel> levels, WorkClock clock) {
        boolean plots = server.getTickCount() % BlackoutWorld.PLOT_SCAN == 0 && ModList.get().isLoaded("sable");
        boolean second = server.getTickCount() % 20 == 0;
        for (ServerLevel level : levels) {
            PowerGrid grid = PowerGrid.get(level);
            // подстанции следуют за сетью и без отключений: блок, чей чанк не был готов, выравнивается, когда станет
            if (second) Substations.sync(level, grid, level.getGameTime());
            // двойники в плотах аппаратов Sable — и после перезапуска, когда отключений уже нет
            List<SubLevelAccess> ships = plots ? SubLevels.all(level) : List.of();
            if (!ships.isEmpty()) BlackoutWorld.get(level).relightPlots(level, ships);
            // миры без отключений и без работы блэкаута не тратят времени
            if (!grid.outages().isEmpty() || level.hasData(ModAttachments.BLACKOUT_WORLD) && BlackoutWorld.get(level).busy()) {
                BlackoutWorld.get(level).tick(level, clock);
            }
        }
    }

    /**
     * Есть ли у блэкаута работа в очереди хоть в одном мире (полосам выше — не весь бюджет). Само отключение — ещё
     * не работа: каскад ставит кварталы в очередь по времени, а между ними блэкауту делать нечего.
     */
    public static boolean pending(MinecraftServer server) {
        for (ServerLevel level : server.getAllLevels()) {
            if (level.hasData(ModAttachments.BLACKOUT_WORLD) && BlackoutWorld.get(level).busy()) return true;
        }
        return false;
    }

    /** Часы бюджета блэкаута — одни на сервер (в верхнем мире). */
    public static WorkClock clock(MinecraftServer server) {
        return server.overworld().getData(ModAttachments.GRID_CLOCK);
    }

    /**
     * Часы бюджета блэкаута: единицы очереди ровные (до ≈3 мс в полной сборке, {@link BlackoutWorld#UNIT_WORK}), и оценка
     * тает вдвое к каждому тику, чтобы один всплеск (пауза GC, чужой затык) не держал очередь на одной единице за тик
     * ({@link WorkClock#decaying}).
     */
    public static WorkClock newClock() {
        return WorkClock.decaying(0.5);
    }

    /** Подменить часы бюджета (проверки — считающими, {@link WorkClock#counting}; вернуть — {@link #newClock}). */
    public static void useClock(MinecraftServer server, WorkClock clock) {
        server.overworld().setData(ModAttachments.GRID_CLOCK, clock);
    }

    public static void onChunkLoad(ChunkEvent.Load e) {
        // в чанке погашенные лампы или его задевает отключение — привести к сети; остальные миры и чанки не трогаем
        if (e.getLevel() instanceof ServerLevel level && e.getChunk() instanceof LevelChunk chunk
                && (chunk.hasData(ModAttachments.GRID_DARK) || PowerGrid.get(level).covered(chunk.getPos().x, chunk.getPos().z)
                || ChunkLights.anyUnlit(chunk.getSections()))) {
            BlackoutWorld.get(level).enqueue(chunk.getPos().toLong());
        }
        // соседу этого чанка, возможно, только его и не хватало для сверки ламп с сигналом
        if (e.getLevel() instanceof ServerLevel level && e.getChunk() instanceof LevelChunk chunk && level.hasData(ModAttachments.BLACKOUT_WORLD)) {
            BlackoutWorld.get(level).neighbourLoaded(chunk.getPos());
        }
    }

    public static void onChunkUnload(ChunkEvent.Unload e) {
        if (e.getLevel() instanceof ServerLevel level && level.hasData(ModAttachments.BLACKOUT_WORLD)) {
            BlackoutWorld.get(level).forget(e.getChunk().getPos().toLong());
        }
    }

    /** Лампа, поставленная в тёмном квартале, гаснет. */
    public static void onBlockPlaced(BlockEvent.EntityPlaceEvent e) {
        if (e.getLevel() instanceof ServerLevel level && GridLights.isLit(e.getPlacedBlock())) {
            BlockPos pos = e.getPos();
            if (PowerGrid.get(level).dark(pos.getX() >> 4, pos.getZ() >> 4, level.getGameTime())) {
                BlackoutWorld.get(level).lampPlaced(level, pos);
            }
        }
    }
}
