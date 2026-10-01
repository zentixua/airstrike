package ua.zentix.airstrike.scenario;

import net.minecraft.client.Minecraft;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import ua.zentix.airstrike.Airstrike;
import ua.zentix.airstrike.entity.StrikeProjectile;
import ua.zentix.airstrike.registry.ModAttachments;
import ua.zentix.airstrike.strike.PickHints;
import ua.zentix.airstrike.strike.VirtualFlights;
import ua.zentix.airstrike.stress.StressDirector;
import ua.zentix.airstrike.util.Terrain;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Замер потока сервера вокруг ударов на копии мира игрока ({@code tools/prod_client.py strike-profile --world …
 * --prop airstrike.profile.steps=…}, полная сборка, JFR — {@code --jvm -XX:StartFlightRecording=…}): шаги через «;»,
 * каждый — {@code tp x y z} (перенос игрока, как телепорт в игре) или {@code оружие сколько разброс x y z} (удар от
 * имени игрока, как с пульта: за 5 с до него — подсказка карты по той же точке, как клик по карте). Первый шаг —
 * через 30 с после входа, следующие — через {@code airstrike.profile.gap} тиков (по умолчанию 2400). В лог: каждый
 * тик сервера дольше 50 мс ({@code SCENARIO strike-profile tick}), промежуток между началами тиков дольше 100 мс
 * ({@code … period}: туда входят и задачи потока сервера между тиками — разбор чанков с диска, перевод в FULL — и
 * ожидание до следующего тика, поэтому порог выше) и раз в секунду средний тик, самый долгий и сколько настенного
 * времени заняли 20 тиков ({@code … second}) — с тиками сервера и настенными мс от последнего шага. На первый взрыв
 * каждого снаряда — {@code … hit}: где он был при попадании, фаза, взведён ли, курс, точка цели и блок, в который он
 * попал (удар далеко от точки — врезался по пути или нет). Первой строкой — {@code … clock utc …}: настоящее время
 * в UTC рядом с временем строки лога. Часы лога, GC-лога и JFR у одного клиента бывают в разных поясах (ноутбук хоста:
 * лог +3, gc.log +2); выжимка сопоставляет их в UTC, а сдвиг лога берёт из этой строки.
 */
final class StrikeProfile {
    private static final long SLOW_NANOS = 50_000_000L, SLOW_PERIOD_NANOS = 100_000_000L;
    private static final int FIRST = 600, PICK_LEAD = 100;

    private final List<String[]> steps = new ArrayList<>();
    private final int gap = Integer.getInteger("airstrike.profile.gap", 2400);
    private int tick;
    /** Серверный тик последнего шага и его имя (для строк лога). */
    private volatile long stepServerTick = -1, stepNanos;
    private volatile String stepName = "-";
    // поток сервера
    private long tickStart, secondStart, secondNanos, secondMax;
    private int secondTicks;
    /** Снаряды, чей первый взрыв уже в логе (у боевой части несколько взрывов). */
    private final Set<UUID> hit = new HashSet<>();

    StrikeProfile() {
        Airstrike.LOG.info("SCENARIO strike-profile clock utc {}", Instant.now());
        String spec = System.getProperty("airstrike.profile.steps", "");
        for (String s : spec.split(";")) {
            String[] a = s.strip().split("\\s+");
            if (a.length == 4 && a[0].equals("tp") || a.length == 6) steps.add(a);
            else if (!s.isBlank()) Airstrike.LOG.warn("SCENARIO strike-profile: шаг «{}» пропущен — нужно «tp x y z» или «оружие сколько разброс x y z»", s);
        }
        NeoForge.EVENT_BUS.addListener(this::onClientTick);
        NeoForge.EVENT_BUS.addListener(this::onServerTickPre);
        NeoForge.EVENT_BUS.addListener(this::onServerTickPost);
        NeoForge.EVENT_BUS.addListener(this::onBlast);
    }

    private void onClientTick(ClientTickEvent.Post e) {
        Minecraft mc = Minecraft.getInstance();
        MinecraftServer server = mc.getSingleplayerServer();
        if (mc.player == null || server == null) return;
        tick++;
        // неуязвим: удар по месту игрока — тоже шаг замера
        if (tick == 20) server.execute(() -> server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),
                "gamemode creative " + player(server).getGameProfile().getName()));
        for (int i = 0; i < steps.size(); i++) {
            String[] a = steps.get(i);
            int at = FIRST + i * gap;
            if (!a[0].equals("tp") && tick == at - PICK_LEAD) server.execute(() -> pick(server, a));
            if (tick == at) server.execute(() -> run(server, a));
        }
        if (tick == FIRST + steps.size() * gap) {
            Airstrike.LOG.info("SCENARIO done");
            mc.stop();
        }
    }

    private static ServerPlayer player(MinecraftServer server) {
        return server.getPlayerList().getPlayers().getFirst();
    }

    private static void pick(MinecraftServer server, String[] a) {
        ServerPlayer p = player(server);
        PickHints.pick(p.serverLevel(), p.getUUID(), p.getData(ModAttachments.PICK_HINT.get()), Double.parseDouble(a[3]), Double.parseDouble(a[5]));
    }

    private void run(MinecraftServer server, String[] a) {
        ServerPlayer p = player(server);
        String name = p.getGameProfile().getName();
        String c = a[0].equals("tp")
                ? String.format(Locale.ROOT, "tp %s %s %s %s", name, a[1], a[2], a[3])
                : String.format(Locale.ROOT, "execute as %s at @s run airstrike salvo %s %s %s at %s %s %s", name, a[0], a[1], a[2], a[3], a[4], a[5]);
        stepServerTick = server.getTickCount();
        stepNanos = System.nanoTime();
        stepName = String.join(" ", a);
        Airstrike.LOG.info("SCENARIO strike-profile step «{}» at {} {} {}: chunks={}", stepName, Math.round(p.getX()), Math.round(p.getY()), Math.round(p.getZ()),
                p.serverLevel().getChunkSource().getLoadedChunksCount());
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), c);
    }

    private void onServerTickPre(ServerTickEvent.Pre e) {
        long now = System.nanoTime();
        if (tickStart != 0 && now - tickStart > SLOW_PERIOD_NANOS) {
            Airstrike.LOG.info("SCENARIO strike-profile period {} ms after «{}» {}", (now - tickStart) / 1_000_000, stepName, since(e.getServer(), now));
        }
        if (secondTicks == 0) secondStart = now;
        tickStart = now;
    }

    /** От шага: тиков сервера и настенных миллисекунд (сервер позади — тиков меньше, чем мс / 50). */
    private String since(MinecraftServer server, long now) {
        return stepServerTick < 0 ? "-" : String.format(Locale.ROOT, "+%d ticks +%d ms", server.getTickCount() - stepServerTick, (now - stepNanos) / 1_000_000);
    }

    private void onServerTickPost(ServerTickEvent.Post e) {
        long end = System.nanoTime(), took = end - tickStart;
        if (took > SLOW_NANOS) {
            Airstrike.LOG.info("SCENARIO strike-profile tick {} ms after «{}» {}", took / 1_000_000, stepName, since(e.getServer(), end));
        }
        secondNanos += took;
        secondMax = Math.max(secondMax, took);
        if (e.getServer().getTickCount() % 100 == 0) logFlights(e.getServer().overworld());
        if (++secondTicks == 20) {
            Airstrike.LOG.info("SCENARIO strike-profile second mspt={} max={} wall={} after «{}» {}", String.format(Locale.ROOT, "%.1f", secondNanos / 20 / 1e6),
                    secondMax / 1_000_000, (end - secondStart) / 1_000_000, stepName, since(e.getServer(), end));
            secondNanos = 0;
            secondMax = 0;
            secondTicks = 0;
        }
    }

    /**
     * Первый взрыв снаряда: снаряд — источник урона взрыва, уже убранный из мира, с местом, фазой и курсом на тик
     * попадания. Блок — в точке взрыва на полшага по курсу (точка столкновения лежит на грани блока).
     */
    private void onBlast(ExplosionEvent.Start e) {
        if (!(e.getLevel() instanceof ServerLevel level)) return;
        StrikeProjectile p = StressDirector.blastProjectile(e.getExplosion());
        if (p == null || !hit.add(p.getUUID())) return;
        try {
            logHit(level, p, e.getExplosion().center());
        } catch (RuntimeException x) {
            // строка для сведения: замер не должен падать из-за неё
            Airstrike.LOG.warn("SCENARIO strike-profile hit: строка не записана", x);
        }
    }

    private void logHit(ServerLevel level, StrikeProjectile p, Vec3 at) {
        Vec3 pos = p.position(), aim = p.aimPoint(), v = p.getDeltaMovement();
        double heading = (Math.toDegrees(Math.atan2(v.x, -v.z)) + 360) % 360;
        double pitch = Math.toDegrees(Math.atan2(v.y, v.horizontalDistance()));
        Vec3 dir = v.lengthSqr() > 1e-6 ? v.normalize() : Vec3.ZERO;
        Airstrike.LOG.info("SCENARIO strike-profile hit {} {} at {} (blast {}) phase {} armed {} heading {} pitch {} speed {} aim {}: {} blocks horizontal, {} above; block {} after «{}» {}",
                p.weapon().getSerializedName(), p.getUUID().toString().substring(0, 8), xyz(pos), xyz(at), p.flightPhase(), armed(p),
                Math.round(heading), Math.round(pitch), String.format(Locale.ROOT, "%.2f", v.length()), xyz(aim),
                Math.round(Math.hypot(at.x - aim.x, at.z - aim.z)), Math.round(at.y - aim.y), block(level, at, dir), stepName, since(level.getServer(), System.nanoTime()));
    }

    private static String xyz(Vec3 v) {
        return String.format(Locale.ROOT, "%.0f %.0f %.0f", v.x, v.y, v.z);
    }

    /** Блок в точке взрыва или на шаг дальше по курсу; в неготовый чанк не заглядывает. */
    private static String block(ServerLevel level, Vec3 at, Vec3 dir) {
        for (double d : new double[] {0.3, 1.0}) {
            BlockPos b = BlockPos.containing(at.add(dir.scale(d)));
            if (!Terrain.ready(level, b)) return "?";
            BlockState s = level.getBlockState(b);
            if (!s.isAir()) return BuiltInRegistries.BLOCK.getKey(s.getBlock()).toString();
        }
        return "air";
    }

    /** {@code StrikeProjectile.armed()} (защищённый); нет такого метода — в строке «armed ?», а не падение замера. */
    private static final Method ARMED = armedMethod();

    private static Method armedMethod() {
        try {
            Method m = StrikeProjectile.class.getDeclaredMethod("armed");
            m.setAccessible(true);
            return m;
        } catch (ReflectiveOperationException | RuntimeException e) {
            Airstrike.LOG.warn("SCENARIO strike-profile: метода StrikeProjectile.armed() нет ({}) — в строках hit «armed ?»", e.toString());
            return null;
        }
    }

    private static String armed(StrikeProjectile p) {
        if (ARMED == null) return "?";
        try {
            return (boolean) ARMED.invoke(p) ? "yes" : "no";
        } catch (ReflectiveOperationException e) {
            return "?";
        }
    }

    /**
     * Раз в 5 с, пока летят снаряды: сколько их в мире и вне его по фазам полёта — удар без единого попадания
     * (прогон 740eec9) иначе не отличить от снарядов, застрявших в полёте или на пусковой.
     */
    private void logFlights(ServerLevel level) {
        Map<String, Integer> in = new TreeMap<>(), off = new TreeMap<>();
        for (Entity en : level.getAllEntities()) {
            if (en instanceof StrikeProjectile p) in.merge(p.weapon().getSerializedName() + " " + p.flightPhase(), 1, Integer::sum);
        }
        for (StrikeProjectile p : VirtualFlights.get(level).flights()) off.merge(p.weapon().getSerializedName() + " " + p.flightPhase(), 1, Integer::sum);
        if (in.isEmpty() && off.isEmpty()) return;
        Airstrike.LOG.info("SCENARIO strike-profile flights in world {} off world {} after «{}» {}", in, off, stepName, since(level.getServer(), System.nanoTime()));
    }
}
