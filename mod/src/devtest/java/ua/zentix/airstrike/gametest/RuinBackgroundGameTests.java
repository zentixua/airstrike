package ua.zentix.airstrike.gametest;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.network.PlayerChunkSender;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import ua.zentix.airstrike.Airstrike;
import ua.zentix.airstrike.nuclear.Detonation;
import ua.zentix.airstrike.nuclear.NuclearStrikes;
import ua.zentix.airstrike.nuclear.NuclearWarhead;
import ua.zentix.airstrike.nuclear.world.ChunkSendGate;
import ua.zentix.airstrike.nuclear.world.NuclearWorld;
import ua.zentix.airstrike.nuclear.world.RuinPlan;
import ua.zentix.airstrike.nuclear.world.RuinPlanner;
import ua.zentix.airstrike.registry.ModAttachments;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Руины в фоне ({@code RuinWorkers}): фоновый план тот же, что в потоке сервера; решатели не зовут мир и свойства
 * блоков мимо снимков; чанк, до которого дошла волна, не уходит игроку до своих руин.
 */
@GameTestHolder(Airstrike.MOD_ID)
@PrefixGameTestTemplate(false)
public final class RuinBackgroundGameTests {
    private RuinBackgroundGameTests() {}

    /**
     * Плотный город (как в замере цены) и блок Create с блок-сущностью в нём: план каждого внутреннего чанка фоновыми
     * потоками — место в место такой же, как в потоке сервера.
     */
    @GameTest(template = "range", timeoutTicks = 100, batch = "nuke_background", skyAccess = true)
    public static void backgroundPlanMatchesServerPlan(GameTestHelper h) {
        RuinCostGameTests.city(h);
        ServerLevel level = h.getLevel();
        // блок Create (если он в сборке): вал с блок-сущностью в стене дома
        Block shaft = BuiltInRegistries.BLOCK.get(ResourceLocation.fromNamespaceAndPath("create", "shaft"));
        if (shaft != Blocks.AIR) h.setBlock(new BlockPos(24, 6, 18), shaft);
        Detonation d = NuclearGameTests.atPsi(h, new BlockPos(-600, 0, 32), true, new BlockPos(32, 1, 32), 12);
        int compared = 0, cells = 0;
        for (int rx : new int[]{24, 40}) {
            for (int rz : new int[]{24, 40}) {
                ChunkPos c = new ChunkPos(h.absolutePos(new BlockPos(rx, 1, rz)));
                for (int dx = -RuinPlanner.REACH; dx <= RuinPlanner.REACH; dx++) {
                    for (int dz = -RuinPlanner.REACH; dz <= RuinPlanner.REACH; dz++) level.getChunk(c.x + dx, c.z + dz);
                }
                LevelChunk chunk = level.getChunk(c.x, c.z);
                RuinPlan server = RuinPlanner.planFresh(level, d, chunk, false), background = RuinPlanner.planFresh(level, d, chunk, true);
                String diff = server.differs(background);
                h.assertTrue(diff == null, "фоновый план чанка " + c + " не такой, как в потоке сервера:" + diff);
                compared++;
                cells += server.changedBlocks();
            }
        }
        h.assertTrue(cells > 0, "город не разрушен: сравнивать нечего");
        Airstrike.LOG.info("Руины в фоне: {} планов совпали, {} мест", compared, cells);
        h.succeed();
    }

    /**
     * Всё, что идёт в фоновых потоках руин, не зовёт мир, чанк, настройки и методы состояний блоков, кроме чтения
     * свойств ({@code getBlock}, {@code getValue}, {@code hasProperty}, {@code getFluidState}, {@code setValue},
     * {@code isAir}): остальное — из таблицы свойств, заполненной в потоке сервера. Обход — по графу вызовов от корней
     * фоновых задач (план: {@code Collapse.solve}, {@code Blast.solve}, {@code RuinPlanner.finish}, лямбды задач
     * {@code RuinContext}; разбор чанка с диска: {@code DiskShots.parse}) через все классы мода, с телами лямбд. Чтение
     * изменяемого статического поля мода и запись в статическое поле вне {@code <clinit>} — тоже ошибка. Вызов через
     * {@code Blast.PropsView} не обходится: в фоне это опубликованная таблица ({@code Blast.publish}), а его реализация
     * для потока сервера ({@code Blast.SERVER}) сама ловится правилом «{@code Blast.props} — только в потоке сервера».
     */
    @GameTest(template = "range", timeoutTicks = 20, batch = "nuke_background_calls", skyAccess = true)
    public static void backgroundSolversCallNoWorld(GameTestHelper h) {
        String pkg = "ua/zentix/airstrike/nuclear/world/";
        // корни: класс, метод, описание (null — все перегрузки); DiskShots.parse(ServerLevel, …) — для проверок, в потоке сервера
        String[][] roots = {{"Collapse", "solve", null}, {"Blast", "solve", null}, {"RuinPlanner", "finish", null},
                {"DiskShots", "parse", "(L" + pkg + "DiskShots$Format;Lnet/minecraft/world/level/ChunkPos;Lnet/minecraft/nbt/CompoundTag;)L" + pkg + "DiskShots$Read;"}};
        Set<String> forbiddenOwners = Set.of("net/minecraft/world/level/Level", "net/minecraft/server/level/ServerLevel",
                "net/minecraft/world/level/chunk/LevelChunk", "net/minecraft/world/level/chunk/ChunkAccess",
                "net/minecraft/world/level/chunk/LevelChunkSection", "net/minecraft/world/level/BlockGetter",
                "net/minecraft/world/level/LevelReader", "net/minecraft/server/level/ServerChunkCache",
                "net/minecraft/world/level/LevelAccessor", "net/minecraft/world/level/CommonLevelAccessor",
                "ua/zentix/airstrike/AirstrikeConfig", "ua/zentix/airstrike/AirstrikeConfig$Server",
                "net/neoforged/neoforge/common/ModConfigSpec$ConfigValue", "net/neoforged/neoforge/common/ModConfigSpec$BooleanValue",
                "net/neoforged/neoforge/common/ModConfigSpec$IntValue", "net/neoforged/neoforge/common/ModConfigSpec$DoubleValue");
        Set<String> stateOk = Set.of("getBlock", "getValue", "hasProperty", "getFluidState", "setValue", "isAir");
        Set<String> serverOnly = Set.of("props", "ensure", "publish", "computeProps", "clearProps", "ensureOwnStates");
        List<String> bad = new ArrayList<>();
        java.util.ArrayDeque<String[]> todo = new java.util.ArrayDeque<>();
        Set<String> seen = new java.util.HashSet<>();
        for (String[] r : roots) todo.add(new String[]{pkg + r[0], r[1], r[2], r[0] + "." + r[1]});
        java.util.Map<String, java.util.Map<String, Integer>> fieldAccess = new java.util.HashMap<>();
        int walked = 0;
        while (!todo.isEmpty()) {
            String[] m = todo.poll();
            String owner = m[0], name = m[1], desc = m[2], path = m[3];
            if (!seen.add(owner + "." + name + (desc == null ? "*" : desc))) continue;
            boolean[] found = {false};
            String[] superName = {null};
            byte[] bytes = classBytes(owner);
            if (bytes == null) {
                bad.add(path + ": класс " + owner + " не найден");
                continue;
            }
            walked++;
            new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public void visit(int version, int access, String n, String sig, String sup, String[] itf) {
                    superName[0] = sup;
                }

                @Override
                public MethodVisitor visitMethod(int access, String method, String d, String sig, String[] ex) {
                    if (!method.equals(name) || desc != null && !desc.equals(d)) return null;
                    found[0] = true;
                    String here = owner.substring(owner.lastIndexOf('/') + 1) + "." + method;
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override
                        public void visitMethodInsn(int op, String o, String called, String cd, boolean itf) {
                            boolean state = o.equals("net/minecraft/world/level/block/state/BlockState")
                                    || o.equals("net/minecraft/world/level/block/state/BlockBehaviour$BlockStateBase")
                                    || o.equals("net/minecraft/world/level/block/state/StateHolder");
                            boolean block = o.startsWith("net/minecraft/world/level/block/") && !o.contains("/state/") && !o.endsWith("/Blocks")
                                    && !o.contains("/entity/");
                            if (forbiddenOwners.contains(o) || state && !stateOk.contains(called) || block && !called.equals("defaultBlockState")
                                    || o.equals(pkg + "Blast") && serverOnly.contains(called) || o.equals(pkg + "RuinPlanner") && serverOnly.contains(called)) {
                                bad.add(path + " → " + here + " → " + o.substring(o.lastIndexOf('/') + 1) + "." + called);
                            }
                            if (o.startsWith("ua/zentix/airstrike/") && !o.equals(pkg + "Blast$PropsView")) {
                                todo.add(new String[]{o, called, cd, path + " → " + here});
                            }
                        }

                        @Override
                        public void visitInvokeDynamicInsn(String n, String d, org.objectweb.asm.Handle bsm, Object... args) {
                            for (Object a : args) {
                                if (a instanceof org.objectweb.asm.Handle hd && hd.getOwner().startsWith("ua/zentix/airstrike/")) {
                                    todo.add(new String[]{hd.getOwner(), hd.getName(), hd.getDesc(), path + " → " + here});
                                }
                            }
                        }

                        @Override
                        public void visitFieldInsn(int op, String o, String field, String fd) {
                            if (!o.startsWith("ua/zentix/airstrike/") || op != Opcodes.GETSTATIC && op != Opcodes.PUTSTATIC) return;
                            if (op == Opcodes.PUTSTATIC && !method.equals("<clinit>")) {
                                bad.add(path + " → " + here + " пишет статическое поле " + o.substring(o.lastIndexOf('/') + 1) + "." + field);
                                return;
                            }
                            Integer access = fieldAccess.computeIfAbsent(o, RuinBackgroundGameTests::fields).get(field);
                            if (access != null && (access & Opcodes.ACC_FINAL) == 0 && !method.equals("<clinit>")) {
                                bad.add(path + " → " + here + " читает изменяемое статическое поле " + o.substring(o.lastIndexOf('/') + 1) + "." + field);
                            }
                        }
                    };
                }
            }, ClassReader.SKIP_DEBUG);
            // унаследованный метод — ищем у предка (только классы мода)
            if (!found[0] && superName[0] != null && superName[0].startsWith("ua/zentix/airstrike/")) todo.add(new String[]{superName[0], name, desc, path});
        }
        h.assertTrue(walked > 20, "обход графа вызовов нашёл только " + walked + " методов: корни не найдены?");
        h.assertTrue(bad.isEmpty(), "фоновые задачи руин зовут мир или свойства мимо таблицы: " + bad.size() + ": " + String.join("; ", new java.util.LinkedHashSet<>(bad).stream().limit(40).toList()));
        Airstrike.LOG.info("Фоновые задачи руин: обойдено {} методов, мира не зовут", walked);
        h.succeed();
    }

    private static byte[] classBytes(String owner) {
        try (InputStream in = RuinPlan.class.getResourceAsStream("/" + owner + ".class")) {
            return in == null ? null : in.readAllBytes();
        } catch (IOException e) {
            return null;
        }
    }

    /** Статические поля класса и их флаги. */
    private static java.util.Map<String, Integer> fields(String owner) {
        java.util.Map<String, Integer> out = new java.util.HashMap<>();
        byte[] bytes = classBytes(owner);
        if (bytes == null) return out;
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public org.objectweb.asm.FieldVisitor visitField(int access, String name, String desc, String sig, Object value) {
                if ((access & Opcodes.ACC_STATIC) != 0) out.put(name, access);
                return null;
            }
        }, ClassReader.SKIP_CODE);
        return out;
    }

    /**
     * План по чанкам, прочитанным с диска (фаза 2: записаны, как их записал бы мир, и разобраны как чанки не в памяти),
     * — место в место такой же, как план по чанкам в памяти.
     */
    @GameTest(template = "range", timeoutTicks = 100, batch = "nuke_background_disk", skyAccess = true)
    public static void diskPlanMatchesMemoryPlan(GameTestHelper h) {
        RuinCostGameTests.city(h);
        ServerLevel level = h.getLevel();
        Detonation d = NuclearGameTests.atPsi(h, new BlockPos(-600, 0, 32), true, new BlockPos(32, 1, 32), 12);
        int compared = 0, cells = 0;
        for (int rx : new int[]{24, 40}) {
            for (int rz : new int[]{24, 40}) {
                ChunkPos c = new ChunkPos(h.absolutePos(new BlockPos(rx, 1, rz)));
                for (int dx = -RuinPlanner.REACH; dx <= RuinPlanner.REACH; dx++) {
                    for (int dz = -RuinPlanner.REACH; dz <= RuinPlanner.REACH; dz++) level.getChunk(c.x + dx, c.z + dz);
                }
                LevelChunk chunk = level.getChunk(c.x, c.z);
                RuinPlan memory = RuinPlanner.planFresh(level, d, chunk, true), disk = RuinPlanner.planFromDisk(level, d, chunk);
                String diff = memory.differs(disk);
                h.assertTrue(diff == null, "план чанка " + c + " с диска не такой, как по чанку в памяти:" + diff);
                compared++;
                cells += memory.changedBlocks();
            }
        }
        h.assertTrue(cells > 0, "город не разрушен: сравнивать нечего");
        Airstrike.LOG.info("Руины с диска: {} планов совпали, {} мест", compared, cells);
        h.succeed();
    }

    /**
     * Остановка сервера посреди зоны за волной (выход из мира после ядерки): вся работа руин бросается — тикеты
     * подготовки, зоны и держащие чанки отпущены, очереди пусты, фоновые потоки остановились за свой срок
     * ({@code RuinWorkers.shutdown}: ждёт не дольше 3 с). Настенное время не меряется: срок — у самого пула.
     */
    @GameTest(template = "range", timeoutTicks = 900, batch = "nuke_stop", skyAccess = true)
    public static void stopMidZoneLeavesNoWork(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        double scale = ua.zentix.airstrike.AirstrikeConfig.SERVER.nukeEffectsScale.get();
        ua.zentix.airstrike.AirstrikeConfig.SERVER.nukeEffectsScale.set(0.02);
        NuclearWorld w = NuclearWorld.get(level);
        ua.zentix.airstrike.nuclear.NuclearEvents events = ua.zentix.airstrike.nuclear.NuclearEvents.get(level);
        long now = level.getGameTime();
        Vec3 target = Vec3.atBottomCenterOf(h.absolutePos(NuclearGameTests.CENTER));
        events.schedule(new ua.zentix.airstrike.nuclear.NuclearEvents.ScheduledStrike(events.nextId(), target, 15, true, now, now + 300, target,
                java.util.Optional.empty(), false));
        h.onEachTick(() -> {
            boolean detonated = events.detonations().stream().anyMatch(x -> x.burst().distanceTo(target) < 20);
            boolean midZone = detonated && (w.prepTiles() > 0 || w.plannedChunks() > 0 || w.queuedChunks() > 0);
            if (!midZone) {
                if (level.getGameTime() > now + 850) {
                    ua.zentix.airstrike.AirstrikeConfig.SERVER.nukeEffectsScale.set(scale);
                    NuclearStrikes.clear(level);
                    throw new net.minecraft.gametest.framework.GameTestAssertException("зона за волной так и не началась: подрыв " + detonated);
                }
                return;
            }
            boolean stopped = NuclearWorld.onServerStopping(level.getServer());
            ua.zentix.airstrike.AirstrikeConfig.SERVER.nukeEffectsScale.set(scale);
            int held = ruinTickets(level);
            long threads = Thread.getAllStackTraces().keySet().stream().filter(t -> t.isAlive() && t.getName().startsWith("Airstrike ruins")).count();
            String left = "квадратов " + w.prepTiles() + ", в подготовке " + w.plannedChunks() + ", в очереди " + w.queuedChunks()
                    + ", тикетов руин " + held + ", потоков руин " + threads;
            NuclearStrikes.clear(level);
            h.assertTrue(stopped, "фоновые потоки не остановились за срок; " + left);
            h.assertTrue(ua.zentix.airstrike.nuclear.world.RuinWorkers.stopped() && threads == 0, "остались потоки руин; " + left);
            h.assertTrue(w.prepTiles() == 0 && w.plannedChunks() == 0 && w.queuedChunks() == 0 && held == 0, "после остановки осталась работа: " + left);
            h.succeed();
        });
    }

    /** Тикеты ядерки на карте расстояний мира: свои и загрузки районов, чей район — ядерный (подготовка, зона). */
    private static int ruinTickets(ServerLevel level) {
        try {
            java.lang.reflect.Field f = net.minecraft.server.level.DistanceManager.class.getDeclaredField("tickets");
            f.setAccessible(true);
            @SuppressWarnings("unchecked")
            var map = (it.unimi.dsi.fastutil.longs.Long2ObjectMap<net.minecraft.util.SortedArraySet<net.minecraft.server.level.Ticket<?>>>)
                    f.get(level.getChunkSource().chunkMap.getDistanceManager());
            java.lang.reflect.Field key = net.minecraft.server.level.Ticket.class.getDeclaredField("key");
            key.setAccessible(true);
            int n = 0;
            for (var set : map.values()) {
                for (net.minecraft.server.level.Ticket<?> t : set) {
                    String type = t.getType().toString();
                    if (type.startsWith("airstrike_nuclear")) n++;
                    else if (type.equals("airstrike_area_load") && key.get(t) instanceof ua.zentix.airstrike.strike.AreaLoader.Area a
                            && a.type().toString().startsWith("airstrike_nuclear")) n++;
                }
            }
            return n;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Цикл выгрузки чанков при остановке сервера получил предел времени ({@code StopServerChunksMixin}). */
    @GameTest(template = "range", timeoutTicks = 20, batch = "nuke_send_gate", skyAccess = true)
    public static void stopPumpBounded(GameTestHelper h) {
        var methods = java.util.Arrays.stream(net.minecraft.server.MinecraftServer.class.getDeclaredMethods()).map(java.lang.reflect.Method::getName).toList();
        h.assertTrue(methods.stream().anyMatch(m -> m.contains("boundStopPump")), "StopServerChunksMixin не встал: выход из мира может зависнуть в выгрузке чанков");
        h.succeed();
    }

    /** Миксин на отправку чанков игроку встал. */
    @GameTest(template = "range", timeoutTicks = 20, batch = "nuke_send_gate", skyAccess = true)
    public static void chunkSendGateApplied(GameTestHelper h) {
        h.assertTrue(new PlayerChunkSender(false) instanceof ChunkSendGate.Gated, "PlayerChunkSenderMixin не встал: чанки уйдут игроку до руин");
        h.succeed();
    }

    /**
     * Чанк, до которого дошла волна, пока его руины строятся (фоновый план — не раньше следующего тика), игроку не
     * отдаётся: из очереди отправки он убран; когда руины встали — отдаётся.
     */
    @GameTest(template = "range", timeoutTicks = 1200, batch = "nuke_send_hold", skyAccess = true)
    public static void chunkWithheldUntilRuins(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos glass = NuclearGameTests.CENTER.west(16);
        h.setBlock(glass, Blocks.GLASS);
        long chunk = new ChunkPos(h.absolutePos(glass)).toLong();
        NuclearWorld w = NuclearWorld.get(level);
        Detonation d = NuclearWarhead.detonate(level, Vec3.atBottomCenterOf(h.absolutePos(NuclearGameTests.CENTER)), 1, false, null, 0.1f);
        boolean[] held = {false};
        h.succeedWhen(() -> {
            LevelChunk c = level.getChunkSource().getChunkNow(ChunkPos.getX(chunk), ChunkPos.getZ(chunk));
            boolean scarred = c != null && c.getExistingData(ModAttachments.CHUNK_SCAR).orElse(0) >= d.id();
            LongOpenHashSet pending = new LongOpenHashSet(new long[]{chunk});
            var out = ChunkSendGate.withhold(level, pending);
            if (!scarred) {
                if (w.withholds(level, chunk)) {
                    h.assertTrue(out != null && out.contains(chunk) && pending.isEmpty(), "чанк без руин остался в очереди отправки");
                    held[0] = true;
                }
                h.fail("руины ещё не встали");
            }
            h.assertTrue(out == null && pending.contains(chunk), "чанк с руинами не отдаётся игроку");
            h.assertTrue(held[0], "чанк ни разу не ждал руин: не проверено");
            NuclearStrikes.clear(level);
        });
    }
}
