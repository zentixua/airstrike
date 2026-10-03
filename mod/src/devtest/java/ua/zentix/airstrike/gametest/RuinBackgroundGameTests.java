package ua.zentix.airstrike.gametest;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.server.network.PlayerChunkSender;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
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
import ua.zentix.airstrike.nuclear.world.NuclearPrep;
import ua.zentix.airstrike.nuclear.world.NuclearWorld;
import ua.zentix.airstrike.nuclear.world.RuinPlan;
import ua.zentix.airstrike.nuclear.world.RuinPlanner;
import ua.zentix.airstrike.nuclear.world.ScarQueue;
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
     * — место в место такой же, как план по чанкам в памяти. Так же — у чанков, какими их оставляет обновление мира 1.17,
     * и у чанков без карт высот на диске (карты из редакторов).
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
                // мир 1.17 после обновления: блоки на месте, низ мира догенерируется при загрузке — план тот же
                diff = memory.differs(RuinPlanner.planFromDisk(level, d, chunk, RuinBackgroundGameTests::asRetrogen));
                h.assertTrue(diff == null, "план чанка " + c + " с догенерацией под нулём не такой, как по чанку в памяти:" + diff);
                // карта из редактора: карт высот на диске нет — досчитываются по блокам, как у ванили при загрузке
                diff = memory.differs(RuinPlanner.planFromDisk(level, d, chunk, tag -> {
                    tag.remove("Heightmaps");
                    return tag;
                }));
                h.assertTrue(diff == null, "план чанка " + c + " без карт высот на диске не такой, как по чанку в памяти:" + diff);
                compared++;
                cells += memory.changedBlocks();
            }
        }
        h.assertTrue(cells > 0, "город не разрушен: сравнивать нечего");
        Airstrike.LOG.info("Руины с диска: {} планов совпали, {} мест", compared, cells);
        h.succeed();
    }

    /**
     * Чанк, каким его оставляет обновление мира 1.17 (ваниль {@code ChunkHeightAndBiomeFix}, затем переименование
     * статусов): статус «пусто», догенерация под нулём до полной генерации 1.17 ({@code heightmaps} → {@code spawn}).
     */
    private static net.minecraft.nbt.CompoundTag asRetrogen(net.minecraft.nbt.CompoundTag tag) {
        net.minecraft.nbt.CompoundTag retrogen = new net.minecraft.nbt.CompoundTag();
        retrogen.putString("target_status", "minecraft:spawn");
        retrogen.putLongArray("missing_bedrock", new long[4]);
        tag.putString("Status", "minecraft:empty");
        tag.put("below_zero_retrogen", retrogen);
        return tag;
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
            long threads = ruinThreadsLeft();
            String left = "квадратов " + w.prepTiles() + ", в подготовке " + w.plannedChunks() + ", в очереди " + w.queuedChunks()
                    + ", тикетов руин " + held + ", потоков руин " + threads;
            NuclearStrikes.clear(level);
            h.assertTrue(stopped, "фоновые потоки не остановились за срок; " + left);
            h.assertTrue(ua.zentix.airstrike.nuclear.world.RuinWorkers.stopped() && threads == 0, "остались потоки руин; " + left);
            h.assertTrue(w.prepTiles() == 0 && w.plannedChunks() == 0 && w.queuedChunks() == 0 && held == 0, "после остановки осталась работа: " + left);
            h.succeed();
        });
    }

    /**
     * Живые потоки руин после остановки пула. Пул объявляет остановку, когда счёт его потоков дошёл до нуля, а поток,
     * уже вышедший из цикла пула, ещё доходит до конца {@code run()}: на CI 01.10.2026 «потоков руин 1» — в той же
     * миллисекунде, что строка «фоновые потоки остановлены». Поэтому каждый ждётся, не дольше секунды: зависший так
     * и остаётся в счёте.
     */
    private static long ruinThreadsLeft() {
        long n = 0;
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            if (!t.getName().startsWith("Airstrike ruins")) continue;
            try {
                t.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (t.isAlive()) n++;
        }
        return n;
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

    /**
     * Выход из мира доходит до конца, когда тикет мода начал генерацию, а она держит держатель, уже поставленный на
     * выгрузку (ноутбук 30.09.2026 после ядерки: выгрузка без конца). В Незере на свежем месте: чанк A загружен и снят
     * (он в очереди выгрузки), тикет загрузки на B рядом — генерация B берёт A обратно. Дальше — как при выходе:
     * {@code ServerStoppingEvent} — мод снимает тикет и ждёт генерацию ({@link ua.zentix.airstrike.util.StopDrain#drain});
     * после этого ни один держатель Незера не занят генерацией, и ванильный цикл остановки как есть
     * ({@code removeTicketsOnClosing}, {@code ServerChunkCache.tick(() -> true, false)}, задачи сервера со сроком тика
     * через 1 мс, как {@code waitUntilNextTick}) кончается: карта Незера пустеет ({@code hasWork()} ложно, тикетов нет).
     * Без ожидания ванильный вызов {@code tick} на этом держателе не возвращается, пока генерацию не кончит чужой поток:
     * задача выгрузки A кладёт себя в очередь обратно, а слоям генерации нужен поток сервера. Настенное время — только
     * пределы против зависания (вызов дольше 10 с — тест красный), не мера скорости.
     */
    @GameTest(template = "range", timeoutTicks = 100, batch = "stop_drain", skyAccess = true)
    public static void stopDrainLetsVanillaUnloadFinish(GameTestHelper h) {
        net.minecraft.server.MinecraftServer server = h.getLevel().getServer();
        ServerLevel nether = server.getLevel(net.minecraft.world.level.Level.NETHER);
        h.assertTrue(nether != null, "нет Незера");
        var cache = nether.getChunkSource();
        var map = cache.chunkMap;
        ChunkPos a = new ChunkPos(20_000 + h.getLevel().random.nextInt(4000), 20_000 + h.getLevel().random.nextInt(4000));
        boolean noSave = nether.noSave;
        long nextTick;
        try {
            java.lang.reflect.Field next = net.minecraft.server.MinecraftServer.class.getDeclaredField("nextTickTimeNanos");
            next.setAccessible(true);
            nextTick = next.getLong(server);
            java.lang.reflect.Field pendingF = net.minecraft.server.level.ChunkMap.class.getDeclaredField("pendingUnloads");
            java.lang.reflect.Field updatingF = net.minecraft.server.level.ChunkMap.class.getDeclaredField("updatingChunkMap");
            java.lang.reflect.Field unloadF = net.minecraft.server.level.ChunkMap.class.getDeclaredField("unloadQueue");
            pendingF.setAccessible(true);
            updatingF.setAccessible(true);
            unloadF.setAccessible(true);
            var pending = (it.unimi.dsi.fastutil.longs.Long2ObjectMap<?>) pendingF.get(map);
            var updating = (it.unimi.dsi.fastutil.longs.Long2ObjectMap<?>) updatingF.get(map);
            var unloadQueue = (java.util.Queue<?>) unloadF.get(map);
            nether.noSave = false;
            try {
                // A — полный чанк; его тикет UNKNOWN истекает, и A уходит в очередь выгрузки — когда его отпустит
                // генерация соседей (processUnloads пропускает держатель с generationRefCount > 0): задачи чанков и
                // потоки генерации, до 10 с
                nether.getChunk(a.x, a.z);
                long setup = System.nanoTime() + 10_000_000_000L;
                while (!pending.containsKey(a.toLong()) && System.nanoTime() < setup) {
                    cache.tick(() -> false, false);
                    while (cache.pollTask()) {
                        // задачи чанков потока сервера: шаги генерации соседей
                    }
                    java.util.concurrent.locks.LockSupport.parkNanos(1_000_000L);
                }
                h.assertTrue(pending.containsKey(a.toLong()) && !unloadQueue.isEmpty(), "A не встал в очередь выгрузки");
                // тикет загрузки на B рядом, как у районов мода: A возвращается в работу, генерация B его держит
                ChunkPos b = new ChunkPos(a.x + 2, a.z);
                net.minecraft.server.level.TicketType<ChunkPos> type = net.minecraft.server.level.TicketType.create("airstrike_test_stop",
                        java.util.Comparator.comparingLong(ChunkPos::toLong));
                cache.addRegionTicket(type, b, 0, b);
                cache.tick(() -> false, false);
                h.assertTrue(updating.get(a.toLong()) instanceof net.minecraft.server.level.GenerationChunkHolder g && g.getGenerationRefCount() > 0,
                        "генерация B не держит A — случай не воспроизведён");
                // ServerStoppingEvent: мод снимает свой тикет и ждёт генерацию, начатую до выхода
                cache.removeRegionTicket(type, b, 0, b);
                boolean drained = ua.zentix.airstrike.util.StopDrain.drain(java.util.List.of(nether),
                        System.nanoTime() + ua.zentix.airstrike.util.StopDrain.LIMIT_NANOS);
                int busy = ua.zentix.airstrike.util.StopDrain.inFlight(nether);
                h.assertTrue(drained && busy == 0, "генерация не кончилась за предел ожидания: держателей занято " + busy);
                h.assertTrue(!(updating.get(a.toLong()) instanceof net.minecraft.server.level.GenerationChunkHolder g) || g.getGenerationRefCount() == 0,
                        "A всё ещё держит генерация");
                // ванильный цикл остановки как есть: каждый вызов tick возвращается, карта пустеет
                long deadline = System.nanoTime() + 60_000_000_000L;
                int rounds = 0;
                while (map.hasWork()) {
                    if (System.nanoTime() > deadline) {
                        throw new net.minecraft.gametest.framework.GameTestAssertException("выход не кончился за " + rounds + " кругов: очередь выгрузки "
                                + unloadQueue.size() + ", тикеты " + (map.getDistanceManager().hasTickets() ? "есть" : "нет"));
                    }
                    next.setLong(server, net.minecraft.Util.getNanos() + 1_000_000L);
                    cache.removeTicketsOnClosing();
                    long spinEnd = System.nanoTime() + 10_000_000_000L;
                    try {
                        cache.tick(() -> {
                            if (System.nanoTime() > spinEnd) throw new VanillaSpin();
                            return true;
                        }, false);
                    } catch (VanillaSpin e) {
                        throw new net.minecraft.gametest.framework.GameTestAssertException("ванильный круг выгрузки не вернулся за 10 с (круг " + rounds
                                + "): держатель A занят генерацией — " + (updating.get(a.toLong()) instanceof net.minecraft.server.level.GenerationChunkHolder g
                                ? g.getGenerationRefCount() : -1));
                    }
                    while (server.pollTask()) {
                        // задачи сервера, как waitUntilNextTick: задачи чанков — только пока срок не прошёл
                    }
                    java.util.concurrent.locks.LockSupport.parkNanos(1_000_000L);
                    rounds++;
                }
                h.assertFalse(map.getDistanceManager().hasTickets(), "после выхода остались тикеты");
            } finally {
                nether.noSave = noSave;
                next.setLong(server, nextTick);
            }
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        h.succeed();
    }

    /**
     * Остановка после залпа: районы мода (здесь — район цели радиуса 8 в свежем Незере, чанки не готовы) отпускаются
     * ({@code StrikeWorld.releaseAreas}) до ожидания генерации, и {@code StopDrain} ждёт только начатое — без этого он
     * применил бы тикеты районов и ждал генерацию всего залпа дольше своего предела. Настенное время — только предел
     * {@code StopDrain.LIMIT_NANOS}.
     */
    @GameTest(template = "range", timeoutTicks = 100, batch = "stop_drain_areas", skyAccess = true)
    public static void stopReleasesAreasBeforeDrain(GameTestHelper h) {
        ServerLevel nether = h.getLevel().getServer().getLevel(net.minecraft.world.level.Level.NETHER);
        h.assertTrue(nether != null, "нет Незера");
        ChunkPos c = new ChunkPos(-30_000 - h.getLevel().random.nextInt(4000), 20_000 + h.getLevel().random.nextInt(4000));
        java.util.UUID flight = java.util.UUID.randomUUID();
        ua.zentix.airstrike.strike.FlightTickets.hold(nether, c, 8, flight, true);
        nether.getChunkSource().tick(() -> false, false);
        h.assertTrue(ua.zentix.airstrike.util.StopDrain.inFlight(nether) > 0, "район не запустил генерацию — случай не воспроизведён");
        ua.zentix.airstrike.strike.StrikeWorld.releaseAreas(nether);
        h.assertTrue(ua.zentix.airstrike.strike.StrikeWorld.get(nether).areas().size() == 0, "районы не отпущены");
        boolean drained = ua.zentix.airstrike.util.StopDrain.drain(java.util.List.of(nether),
                System.nanoTime() + ua.zentix.airstrike.util.StopDrain.LIMIT_NANOS);
        int busy = ua.zentix.airstrike.util.StopDrain.inFlight(nether);
        h.assertTrue(drained && busy == 0, "генерация не кончилась за предел ожидания: держателей занято " + busy);
        h.assertTrue(ua.zentix.airstrike.strike.FlightTickets.held(nether, flight) == 0, "тикет района остался");
        h.succeed();
    }

    /** Выход из ванильного круга выгрузки, если он не вернулся ({@link #stopDrainLetsVanillaUnloadFinish}). */
    private static final class VanillaSpin extends RuntimeException {
        private static final long serialVersionUID = 1L;

        VanillaSpin() {
            super(null, null, false, false);
        }
    }

    /**
     * Чанк на краю загруженного мира (тикет радиуса 0, соседи не загружены) с постройкой во весь чанк: его фоновый план
     * строится, как у очереди руин, — окно с диска, соседей нет на диске (не сохранены) — сплошной массив, и ничего не
     * ждёт ({@link #windowWithoutNeighboursOnDisk}). Готовый план без мест через мир у края ставится без соседей: и соседи
     * при этом не грузятся, и блоки чанка — секция в секцию те же, что при загруженных соседях (тот же план второй раз
     * после возврата блоков). Второй случай: чанку у аппарата Sable подмена по-прежнему ждёт соседей радиуса 1
     * ({@code RuinPlan.needsNeighbours}: Sable на каждое место читает 6 соседей), дальнему чанку — нет.
     */
    @GameTest(template = "range", timeoutTicks = 600, batch = "nuke_edge_apply", skyAccess = true)
    public static void readyPlanSameWithoutNeighbours(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        var chunks = level.getChunkSource();
        ChunkPos pad = new ChunkPos(h.absolutePos(NuclearGameTests.CENTER));
        ChunkPos x = new ChunkPos(pad.x + 40, pad.z);
        boolean fires = ua.zentix.airstrike.AirstrikeConfig.SERVER.nukeFires.get();
        RuinPlan[] plan = new RuinPlan[1];
        int[][] before = new int[1][], afterAlone = new int[1][];
        Runnable done = () -> {
            chunks.removeRegionTicket(NuclearGameTests.HOLD, x, 0, x);
            chunks.removeRegionTicket(NuclearGameTests.HOLD, x, 1, x);
            ua.zentix.airstrike.AirstrikeConfig.SERVER.nukeFires.set(fires);
            NuclearStrikes.clear(level);
        };
        // и после провала по сроку: тикеты, пожары в настройке, очереди
        StrikeGameTests.afterTest(h, done);
        // X — сразу (сервер GameTest тикает быстрее фоновой генерации), тикет держит его полным, соседей — нет
        chunks.addRegionTicket(NuclearGameTests.HOLD, x, 0, x);
        level.getChunk(x.x, x.z);
        h.startSequence()
                .thenExecute(() -> {
                    LevelChunk chunk = chunks.getChunkNow(x.x, x.z);
                    h.assertTrue(chunk != null, "X не загружен");
                    BlockPos base = new BlockPos(x.getMinBlockX(), 200, x.getMinBlockZ());
                    // этажи во весь чанк на столбах, стены у края (места плана в блоке от границы чанка; на самой
                    // границе setBlock не годится: Sable читает соседей места и загрузил бы соседний чанк)
                    for (int ly = 0; ly < 16; ly++) {
                        for (int lx = 1; lx < 15; lx++) {
                            for (int lz = 1; lz < 15; lz++) {
                                boolean edge = lx == 1 || lx == 14 || lz == 1 || lz == 14, pillar = (lx == 4 || lx == 11) && (lz == 4 || lz == 11);
                                Block b = ly % 4 == 3 ? Blocks.STONE : pillar ? Blocks.STONE_BRICKS : edge ? (ly % 4 == 1 ? Blocks.GLASS : Blocks.BRICKS) : null;
                                if (b != null) level.setBlock(base.offset(lx, ly, lz), b.defaultBlockState(), 2 | 16);
                            }
                        }
                    }
                    ua.zentix.airstrike.AirstrikeConfig.SERVER.nukeFires.set(false);
                    Detonation d = NuclearGameTests.atPsi(h, NuclearGameTests.CENTER, true, h.relativePos(base.offset(8, 8, 8)), 6);
                    for (int dx = -1; dx <= 1; dx++) {
                        for (int dz = -1; dz <= 1; dz++) {
                            if ((dx != 0 || dz != 0) && chunks.getChunkNow(x.x + dx, x.z + dz) != null) {
                                throw new net.minecraft.gametest.framework.GameTestAssertException("сосед X загружен до подмены — случай не воспроизведён");
                            }
                        }
                    }
                    int[] window = new int[2];
                    plan[0] = RuinPlanner.planWithWindow(level, d, chunk, window);
                    h.assertTrue(plan[0].changedBlocks() > 0, "постройка не разрушена: сравнивать нечего");
                    h.assertFalse(plan[0].needsNeighbours(level, chunk), "плану X нужны соседи — случай не тот");
                    h.assertTrue(plan[0].waitsNeighbours(level, chunk) == 0, "очередь ждала бы соседей X");
                    before[0] = blocks(chunk);
                    h.assertTrue(plan[0].apply(level, chunk, new ua.zentix.airstrike.nuclear.world.ColumnScar.Budget(false)), "план X устарел");
                    for (int dx = -1; dx <= 1; dx++) {
                        for (int dz = -1; dz <= 1; dz++) {
                            if ((dx != 0 || dz != 0) && chunks.getChunkNow(x.x + dx, x.z + dz) != null) {
                                throw new net.minecraft.gametest.framework.GameTestAssertException("подмена загрузила соседа " + (x.x + dx) + ", " + (x.z + dz));
                            }
                        }
                    }
                    afterAlone[0] = blocks(chunk);
                    // вернуть блоки, как до руин, и загрузить соседей
                    restore(level, chunk, before[0]);
                    h.assertTrue(java.util.Arrays.equals(blocks(chunk), before[0]), "блоки X не вернулись");
                    // соседи — сразу, тикет держит их полными
                    chunks.addRegionTicket(NuclearGameTests.HOLD, x, 1, x);
                    for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) level.getChunk(x.x + dx, x.z + dz);
                })
                .thenWaitUntil(() -> h.assertTrue(ua.zentix.airstrike.nuclear.world.NuclearTickets.neighbourhoodLoaded(level, x, 1), "соседи X не полные"))
                .thenExecute(() -> {
                    LevelChunk chunk = chunks.getChunkNow(x.x, x.z);
                    h.assertTrue(plan[0].apply(level, chunk, new ua.zentix.airstrike.nuclear.world.ColumnScar.Budget(false)), "план X устарел во второй раз");
                    String diff = firstDifference(chunk, afterAlone[0], blocks(chunk));
                    h.assertTrue(diff == null, "руины X без соседей и с соседями разные: " + diff);
                    Airstrike.LOG.info("Руины у края: {} мест, без соседей и с соседями — одно и то же", plan[0].changedBlocks());
                })
                .thenExecute(() -> assembleCraft(h))
                .thenWaitUntil(() -> h.assertFalse(!sable() || ua.zentix.airstrike.compat.SubLevels.near(level, craftCentre(h), 8).isEmpty(), "аппарат собирается"))
                .thenExecute(() -> craftChunkWaits(h, x, plan[0]))
                .thenSucceed();
    }

    /**
     * Блок-сущность не из ванили в середине чанка (подставка Create): её {@code onRemove} ходит цепочкой через соседние
     * блоки (лента Create — до 20 блоков), поэтому подмена ждёт соседей радиуса {@code RuinPlanner.REACH}, а не 1 и не 0,
     * как у сундука там же. Оба блока — в огненном шаре: план их снимает.
     */
    @GameTest(template = "range", timeoutTicks = 200, batch = "nuke_be_reach", skyAccess = true)
    public static void moddedBlockEntityWaitsReach(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        StrikeGameTests.afterTest(h, () -> NuclearStrikes.clear(level));
        Block depot = BuiltInRegistries.BLOCK.get(ResourceLocation.fromNamespaceAndPath("create", "depot"));
        h.assertTrue(depot != Blocks.AIR, "нет create:depot — Create не загружен");
        BlockPos centre = h.absolutePos(NuclearGameTests.CENTER);
        ChunkPos c = new ChunkPos(centre);
        BlockPos spot = new BlockPos(c.getMinBlockX() + 8, centre.getY() + 1, c.getMinBlockZ() + 8);
        LevelChunk chunk = level.getChunkAt(spot);
        Detonation d = NuclearGameTests.detonation(h, NuclearGameTests.CENTER, 0, 15, 0.05f);
        level.setBlock(spot, depot.defaultBlockState(), 3);
        h.assertTrue(level.getBlockState(spot).hasBlockEntity(), "у подставки нет блок-сущности");
        RuinPlan withDepot = RuinPlanner.planWithWindow(level, d, chunk, new int[2]);
        h.assertTrue(withDepot.neighbourRadius(level, chunk) == RuinPlanner.REACH,
                "подставка Create в середине чанка: подмена ждёт соседей радиуса " + withDepot.neighbourRadius(level, chunk) + ", а не " + RuinPlanner.REACH);
        level.setBlock(spot, Blocks.CHEST.defaultBlockState(), 3);
        RuinPlan withChest = RuinPlanner.planWithWindow(level, d, chunk, new int[2]);
        h.assertTrue(withChest.neighbourRadius(level, chunk) == 0, "сундук в середине чанка: подмена ждёт соседей радиуса " + withChest.neighbourRadius(level, chunk));
        h.assertTrue(withChest.apply(level, chunk, new ua.zentix.airstrike.nuclear.world.ColumnScar.Budget(false)), "план устарел");
        h.assertFalse(level.getBlockState(spot).is(Blocks.CHEST), "сундук не снят: случай не тот");
        h.succeed();
    }

    private static final net.minecraft.server.level.TicketType<java.util.UUID> TEST_TILE =
            net.minecraft.server.level.TicketType.create("airstrike_test_tile", java.util.Comparator.<java.util.UUID>naturalOrder());

    private static ua.zentix.airstrike.strike.AreaLoader.Area tile(ChunkPos c) {
        return new ua.zentix.airstrike.strike.AreaLoader.Area(TEST_TILE, c, 0, new java.util.UUID(7L, c.toLong()), false);
    }

    /**
     * Тупик зоны за волной (диагностика 01.10.2026): чанк P загружен под тикетом с соседями чанка Q (свой тикет он не
     * берёт), квадрат зоны держит P, а подмена P ждёт соседа R, которого никто не грузит, — и квадрат ждёт P. Здесь Q
     * и P держат тикеты-«квадраты» радиуса 0, сундуки у восточного края Q и P требуют соседей радиуса 1. Пока Q не
     * отпустил свой тикет, P ждёт; после — ждёт и дальше (200 тиков, R не грузится), а {@code holdForTile} даёт P свой
     * тикет: R грузится, руины P встают. В тот же тик сундук P заменяется блок-сущностью Create: план P устаревает,
     * новый ждёт соседей радиуса 2 — свой тикет P (взятый за квадрат, P загружен под чужим) должен расшириться до r2,
     * иначе P ждал бы вечно и держал удержание квадрата.
     */
    @GameTest(template = "range", timeoutTicks = 1600, batch = "nuke_tile_hold", skyAccess = true)
    public static void tileReleaseHoldsWaitingNeighbours(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        ChunkPos pad = new ChunkPos(h.absolutePos(NuclearGameTests.CENTER));
        ChunkPos q = new ChunkPos(pad.x + 40, pad.z), p = new ChunkPos(q.x + 1, q.z), r = new ChunkPos(q.x + 2, q.z);
        var areas = ua.zentix.airstrike.strike.StrikeWorld.get(level).areas();
        var scars = NuclearWorld.get(level).scars();
        // наземный подрыв у западного края P без воронки: воронка держит свои чанки с соседями (NuclearTickets) и
        // загрузила бы R сразу после подрыва — руины P встали бы, не дождавшись тупика, смотря что раньше: план P или
        // выгрузка R после воронки
        boolean crater = ua.zentix.airstrike.AirstrikeConfig.SERVER.nukeCrater.get();
        ua.zentix.airstrike.AirstrikeConfig.SERVER.nukeCrater.set(false);
        StrikeGameTests.afterTest(h, () -> {
            ua.zentix.airstrike.AirstrikeConfig.SERVER.nukeCrater.set(crater);
            areas.release(level, tile(q));
            areas.release(level, tile(p));
            NuclearStrikes.clear(level);
        });
        // всё вокруг — на диске целым; сундуки у восточного края Q и P (места через мир у края — соседи радиуса 1)
        BlockPos[] chests = new BlockPos[2];
        for (int x = q.x - 2; x <= r.x + 2; x++) {
            for (int z = q.z - 2; z <= q.z + 2; z++) level.getChunk(x, z);
        }
        int i = 0;
        for (ChunkPos c : new ChunkPos[]{q, p}) {
            int x = c.getMinBlockX() + 15, z = c.getMinBlockZ() + 8;
            chests[i] = new BlockPos(x, level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE, x, z), z);
            level.setBlock(chests[i++], Blocks.CHEST.defaultBlockState(), 3);
        }
        int[] stage = {0};
        long[] since = {0};
        String[] waited = {""};
        boolean[] widened = {false};
        var depot = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getOptional(net.minecraft.resources.ResourceLocation.parse("create:depot"));
        Detonation[] det = {null};
        long[] tickAt = {0};
        h.onEachTick(() -> {
            // после подготовки — в темпе игры: ожидания ниже в тиках, а загрузка чанков и фоновые планы идут по часам
            if (stage[0] > 0) gamePace(tickAt);
            long now = level.getGameTime();
            switch (stage[0]) {
                case 0 -> {
                    // ждём, пока сгенерированное выгрузится: Q, P и R загрузит только сам сценарий. Выгрузится совсем, а не
                    // только уйдёт из видимой карты: чанк, ждущий выгрузки, подрыв ставит в очередь сразу, и P, ещё не
                    // под тикетом Q, брал бы свой тикет с соседями — R загружался, руины P вставали без тупика. Пока
                    // без темпа игры: отстающий сервер ждущие выгрузки не выгружает (ваниль — только пока есть время в тике)
                    if (!unloaded(level, List.of(q, p, r))) return;
                    areas.hold(level, tile(q));
                    tickAt[0] = System.nanoTime();
                    stage[0] = 1;
                }
                case 1 -> {
                    if (level.getChunkSource().getChunkNow(q.x, q.z) == null) return;
                    det[0] = NuclearWarhead.detonate(level, new Vec3(p.getMinBlockX(), chests[1].getY(), p.getMinBlockZ() + 8.5), 1, false, null, 0.1f);
                    since[0] = now;
                    stage[0] = 2;
                }
                case 2 -> {
                    // Q ждёт соседей своим тикетом — он грузит P; P встаёт в очередь под чужим тикетом
                    if (!scars.queued(p.toLong())) {
                        if (now - since[0] > 600) h.fail("P не встал в очередь: Q " + (scars.queued(q.toLong()) ? "в очереди" : "готов") + " — тикета с соседями у Q не было");
                        return;
                    }
                    areas.hold(level, tile(p));
                    stage[0] = 3;
                }
                case 3 -> {
                    if (scars.queued(q.toLong())) return;
                    h.assertFalse(level.getBlockState(chests[0]).is(Blocks.CHEST), "сундук в Q цел: случай не тот");
                    since[0] = now;
                    stage[0] = 4;
                }
                case 4 -> {
                    // тикет Q отпущен: P ждёт R, а R никто не грузит (так стояли квадраты зоны)
                    h.assertTrue(scars.queued(p.toLong()), "руины P встали без соседа R: тупика нет, случай не тот");
                    h.assertTrue(level.getChunkSource().getChunkNow(r.x, r.z) == null, "R загружен");
                    if (now - since[0] < 200) return;
                    waited[0] = scars.diagState(p.toLong(), now);
                    h.assertTrue(scars.waitsNeighbours(p.toLong()), "P ждёт не соседей: " + waited[0]);
                    h.assertTrue(scars.holdForTile(level, p.toLong()), "P не взял тикет с соседями");
                    // сундук — место плана; блок-сущность не из ванили вместо него: старые состояния не те, план заново,
                    // и новый ждёт соседей r2
                    h.assertTrue(depot.isPresent(), "нет create:depot (Create не загружен)");
                    level.setBlock(chests[1], depot.get().defaultBlockState(), 3);
                    areas.release(level, tile(p));
                    since[0] = now;
                    stage[0] = 5;
                }
                case 5 -> {
                    // квадрат отпущен: P держит себя сам, пока его руины не встали (иначе он выгрузился бы без руин)
                    if (scars.queued(p.toLong())) {
                        if (scars.diagState(p.toLong(), now).contains("тикет r2")) {
                            widened[0] = true;
                            // расширенный тикет за квадрат — всё ещё в счёте удержаний
                            h.assertTrue(scars.tileHoldsLeft() == 63, "тикет P r2 вне счёта удержаний: свободно " + scars.tileHoldsLeft());
                        }
                        h.assertTrue(level.getChunkSource().getChunkNow(p.x, p.z) != null, "P выгрузился без руин после отпуска квадрата");
                        if (now - since[0] > 600) h.fail("руины P не встали за 600 тиков после своего тикета с соседями");
                        return;
                    }
                    // работа P кончилась руинами, а не выгрузкой: отметка подрыва у P. Отпустив свой тикет, P выгружается
                    // в том же тике сервера, что и руины, — тогда он читается с диска (здесь можно: проверка)
                    boolean wasLoaded = level.getChunkSource().getChunkNow(p.x, p.z) != null;
                    LevelChunk pc = level.getChunk(p.x, p.z);
                    h.assertTrue(pc != null && pc.getExistingData(ModAttachments.CHUNK_SCAR).orElse(0) == det[0].id(), "руины P не встали (P в памяти " + wasLoaded
                            + ", отметка подрыва " + (pc == null ? "?" : pc.getExistingData(ModAttachments.CHUNK_SCAR).orElse(0)) + " при подрыве " + det[0].id()
                            + "); P ждал: " + waited[0] + "; давление у блока " + String.format(java.util.Locale.ROOT, "%.0f", det[0].psi(Vec3.atCenterOf(chests[1]))) + " psi");
                    h.assertTrue(widened[0], "P не ждал соседей r2 своим тикетом: план с блоком Create не потребовал r2, случай не тот");
                    h.assertTrue(scars.tileHoldsLeft() == 64, "тикет P не отпущен после руин: свободно " + scars.tileHoldsLeft());
                    h.succeed();
                }
                default -> {
                }
            }
        });
    }

    private static boolean sable() {
        return net.neoforged.fml.ModList.get().isLoaded("sable");
    }

    private static final BlockPos CRAFT_FROM = NuclearGameTests.CENTER.offset(6, 5, -2), CRAFT_TO = CRAFT_FROM.offset(3, 1, 3);

    private static Vec3 craftCentre(GameTestHelper h) {
        return Vec3.atCenterOf(h.absolutePos(CRAFT_FROM.offset(1, 0, 1)));
    }

    /** Аппарат из досок над площадкой (как у {@code aircraftBlast}). Без Sable — ничего. */
    private static void assembleCraft(GameTestHelper h) {
        if (!sable()) return;
        ServerLevel level = h.getLevel();
        BlockPos.betweenClosed(CRAFT_FROM, CRAFT_TO).forEach(p -> h.setBlock(p, Blocks.OAK_PLANKS));
        BlockPos a = h.absolutePos(CRAFT_FROM), b = h.absolutePos(CRAFT_TO);
        var server = level.getServer();
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withLevel(level).withSuppressedOutput(),
                String.format(java.util.Locale.ROOT, "sable assemble area %d %d %d %d %d %d", Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()),
                        Math.min(a.getZ(), b.getZ()), Math.max(a.getX(), b.getX()), Math.max(a.getY(), b.getY()), Math.max(a.getZ(), b.getZ())));
    }

    /**
     * Чанку у аппарата подмене нужны соседи, дальнему (X) — нет: тот же план (его места не через мир), разница — только
     * аппарат. Без Sable — нечего проверять.
     */
    private static void craftChunkWaits(GameTestHelper h, ChunkPos far, RuinPlan plan) {
        if (!sable()) return;
        ServerLevel level = h.getLevel();
        LevelChunk under = level.getChunkAt(BlockPos.containing(craftCentre(h)));
        h.assertTrue(plan.needsNeighbours(level, under), "чанку у аппарата подмена не ждёт соседей: Sable прочитал бы их синхронно");
        LevelChunk x = level.getChunk(far.x, far.z);
        h.assertFalse(plan.needsNeighbours(level, x), "дальнему чанку без аппаратов подмена ждёт соседей");
    }

    /** Все места чанка (номера состояний), снизу вверх по секциям. */
    private static int[] blocks(LevelChunk chunk) {
        var sections = chunk.getSections();
        int[] out = new int[sections.length * 4096];
        for (int i = 0; i < sections.length; i++) {
            for (int k = 0; k < 4096; k++) out[i * 4096 + k] = Block.getId(sections[i].getBlockState(k & 15, k >> 8, (k >> 4) & 15));
        }
        return out;
    }

    /** Вернуть чанку блоки снимка (через мир, без обновлений соседей). */
    private static void restore(ServerLevel level, LevelChunk chunk, int[] was) {
        int[] now = blocks(chunk);
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int k = 0; k < was.length; k++) {
            if (now[k] == was[k]) continue;
            int i = k >> 12, c = k & 4095;
            m.set(chunk.getPos().getMinBlockX() + (c & 15), chunk.getMinBuildHeight() + (i << 4) + (c >> 8), chunk.getPos().getMinBlockZ() + ((c >> 4) & 15));
            level.setBlock(m, Block.stateById(was[k]), 2 | 16);
        }
    }

    @org.jetbrains.annotations.Nullable
    private static String firstDifference(LevelChunk chunk, int[] a, int[] b) {
        int n = 0;
        String first = null;
        for (int k = 0; k < a.length; k++) {
            if (a[k] == b[k]) continue;
            if (n++ == 0) {
                int i = k >> 12, c = k & 4095;
                first = "секция " + i + " место " + (c & 15) + "," + (c >> 8) + "," + ((c >> 4) & 15) + ": " + Block.stateById(a[k]) + " против " + Block.stateById(b[k]);
            }
        }
        return n == 0 ? null : n + " мест, первое — " + first;
    }

    /**
     * Сосед окна, которого нет на диске (не сгенерирован до конца или не сохранён): окно фонового плана не ждёт его
     * загрузки — {@code RuinContext.requestWindow} читает соседей с диска, а не найденного считает сплошным массивом,
     * как край мира. План такой же, как в потоке сервера с теми же соседями вне памяти (там их снимков тоже нет).
     */
    @GameTest(template = "range", timeoutTicks = 200, batch = "nuke_window_absent", skyAccess = true)
    public static void windowWithoutNeighboursOnDisk(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        var chunks = level.getChunkSource();
        ChunkPos pad = new ChunkPos(h.absolutePos(NuclearGameTests.CENTER));
        ChunkPos y = new ChunkPos(pad.x - 40, pad.z + 7);
        StrikeGameTests.afterTest(h, () -> {
            chunks.removeRegionTicket(NuclearGameTests.HOLD, y, 0, y);
            NuclearStrikes.clear(level);
        });
        // Y — сразу (сервер GameTest тикает быстрее фоновой генерации), тикет держит его полным, соседей — нет
        chunks.addRegionTicket(NuclearGameTests.HOLD, y, 0, y);
        level.getChunk(y.x, y.z);
        h.startSequence()
                .thenExecute(() -> {
                    LevelChunk chunk = chunks.getChunkNow(y.x, y.z);
                    h.assertTrue(chunk != null, "Y не загружен");
                    BlockPos base = new BlockPos(y.getMinBlockX(), 200, y.getMinBlockZ());
                    // не на границе чанка: Sable на setBlock читает соседей места и загрузил бы соседний чанк
                    for (int ly = 0; ly < 8; ly++) {
                        for (int lx = 1; lx < 15; lx++) {
                            for (int lz = 1; lz < 15; lz++) {
                                boolean edge = lx == 1 || lx == 14 || lz == 1 || lz == 14;
                                if (edge || ly == 7) level.setBlock(base.offset(lx, ly, lz), (ly % 3 == 1 ? Blocks.GLASS : Blocks.BRICKS).defaultBlockState(), 2 | 16);
                            }
                        }
                    }
                    Detonation d = NuclearGameTests.atPsi(h, NuclearGameTests.CENTER, true, h.relativePos(base.offset(8, 4, 8)), 6);
                    int[] window = new int[2];
                    RuinPlan disk = RuinPlanner.planWithWindow(level, d, chunk, window);
                    int inMemory = 0;
                    for (int dx = -RuinPlanner.REACH; dx <= RuinPlanner.REACH; dx++) {
                        for (int dz = -RuinPlanner.REACH; dz <= RuinPlanner.REACH; dz++) {
                            if ((dx != 0 || dz != 0) && chunks.getChunkNow(y.x + dx, y.z + dz) != null) inMemory++;
                        }
                    }
                    int side = 2 * RuinPlanner.REACH + 1;
                    h.assertTrue(window[0] == side * side - 1 - inMemory, "соседей окна прочитано с диска " + window[0] + ", а не в памяти "
                            + (side * side - 1 - inMemory));
                    h.assertTrue(window[1] > 0, "все соседи окна нашлись на диске — случай не воспроизведён");
                    RuinPlan server = RuinPlanner.planFresh(level, d, chunk, false);
                    h.assertTrue(disk.changedBlocks() > 0, "постройка не разрушена: сравнивать нечего");
                    String diff = disk.differs(server);
                    h.assertTrue(diff == null, "план с окном с диска не такой, как в потоке сервера:" + diff);
                    Airstrike.LOG.info("Окно без соседей на диске: прочитано {}, нет на диске {}, план — {} мест", window[0], window[1], disk.changedBlocks());
                })
                .thenSucceed();
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

    /**
     * Срок удержания чанка без руин считается от первой просьбы игрока, а не от волны: чанк, загруженный через тысячи
     * тиков после волны (зона за волной до него не дошла, игрок перенёсся), сперва ждёт руин, а не уходит сразу
     * (gate 5: 587 чанков ушло игроку до руин).
     */
    @GameTest(template = "range", timeoutTicks = 200, batch = "nuke_send_ask", skyAccess = true)
    public static void withholdCountsFromFirstAsk(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        Detonation d = NuclearWarhead.detonate(level, Vec3.atBottomCenterOf(h.absolutePos(NuclearGameTests.CENTER)), 1, false, null, 0.1f);
        LevelChunk chunk = level.getChunkAt(h.absolutePos(NuclearGameTests.CENTER.west(16)));
        long key = chunk.getPos().toLong();
        ScarQueue q = new ScarQueue();
        q.offer(chunk, d);
        long late = d.gameTime() + 100_000;
        h.assertTrue(q.withholds(key, late), "чанк, впервые попросившийся к игроку долго после волны, ушёл без руин сразу");
        h.assertTrue(q.withholds(key, late + 199), "удержание кончилось раньше срока от первой просьбы");
        h.assertFalse(q.withholds(key, late + 200), "удержание не кончилось через срок от первой просьбы");
        NuclearStrikes.clear(level);
        h.succeed();
    }

    /**
     * Чанк, чей срок пришёл, когда его никто не видел, стоит в очереди невидимых — за чанками зоны и LOD вдали. Игрок
     * перенёсся к нему и ждёт его руин: первая просьба переводит чанк к видимым (игра Артёма в Newisle, подрыв №2: после
     * телепорта руины чанков у игрока шли за невидимыми, и 1085 чанков ушли к нему до руин). Бюджета — на одну единицу:
     * второй чанк остаётся в очереди.
     */
    @GameTest(template = "range", timeoutTicks = 200, batch = "nuke_send_promote", skyAccess = true)
    public static void askedChunkGoesAheadOfUnseen(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        Detonation d = NuclearWarhead.detonate(level, Vec3.atBottomCenterOf(h.absolutePos(NuclearGameTests.CENTER)), 1, false, null, 0.1f);
        ScarQueue q = new ScarQueue();
        List<Long> keys = new ArrayList<>();
        for (int west : new int[]{16, 32}) {
            LevelChunk chunk = level.getChunkAt(h.absolutePos(NuclearGameTests.CENTER.west(west)));
            q.offer(chunk, d);
            keys.add(chunk.getPos().toLong());
        }
        long late = d.gameTime() + 100_000;
        ua.zentix.airstrike.nuclear.world.WorkClock clock = ua.zentix.airstrike.nuclear.world.WorkClock.counting(1_000_000);
        clock.start(0);
        q.work(level, late, clock);
        List<Long> left = keys.stream().filter(k -> "в очереди готовых".equals(q.diagState(k, late))).toList();
        h.assertTrue(left.size() == 1, "в очереди невидимых не один чанк: " + keys.stream().map(k -> q.diagState(k, late)).toList());
        q.withholds(left.get(0), late);
        h.assertTrue("в очереди готовых, видимых".equals(q.diagState(left.get(0), late)), "попросившийся чанк остался среди невидимых: " + q.diagState(left.get(0), late));
        NuclearStrikes.clear(level);
        h.succeed();
    }

    /**
     * Зона за волной у края исследованного мира (игра Артёма 01.10.2026, удар №2: «не дождались 453 чанков»): чанк, чьё
     * окно руин целое на диске, получает план с диска, а квадрат зоны, где хоть один чанк окон его чанков не целый,
     * пропускался целиком — план ждал до конца зоны и пропадал, руин на диске не было. Здесь место удара (9×9 вокруг цели,
     * его держит район подрыва с пуска) и полоса B к востоку (6×7 чанков) сгенерированы и выгружены, вокруг — только
     * недогенерированное кольцо. Чанки B с целым окном, которых нет в памяти, получают план с диска и руины на диске,
     * подготовка кончается без единого оставшегося плана, а мир не догенерируется: поле {@code Status} чанков округи до
     * и после — то же (зона не берёт чанк, который ваниль стала бы генерировать).
     */
    @GameTest(template = "range", timeoutTicks = 6000, batch = "nuke_zone_edge", skyAccess = true)
    public static void zoneLoadsEveryChunkWithPlan(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        var chunks = level.getChunkSource();
        ChunkPos pad = new ChunkPos(h.absolutePos(NuclearGameTests.CENTER));
        // дальше радиуса руин от площадки: её чанки в памяти, и их руины держали бы подготовку
        ChunkPos t = new ChunkPos(pad.x + 80, pad.z);
        LongOpenHashSet whole = new LongOpenHashSet();
        for (int dx = -4; dx <= 4; dx++) {
            for (int dz = -4; dz <= 4; dz++) whole.add(ChunkPos.asLong(t.x + dx, t.z + dz));
        }
        for (int dx = 3; dx <= 8; dx++) {
            for (int dz = -3; dz <= 3; dz++) whole.add(ChunkPos.asLong(t.x + dx, t.z + dz));
        }
        for (long c : whole) level.getChunk(ChunkPos.getX(c), ChunkPos.getZ(c));
        Vec3 target = new Vec3(t.getMiddleBlockX() + 0.5,
                level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, t.getMiddleBlockX(), t.getMiddleBlockZ()), t.getMiddleBlockZ() + 0.5);
        // чанки B с целым окном руин за районом подрыва: план им — только с диска
        List<ChunkPos> far = new ArrayList<>();
        for (int dx = 5; dx <= 6; dx++) {
            for (int dz = -1; dz <= 1; dz++) far.add(new ChunkPos(t.x + dx, t.z + dz));
        }
        // округа, где зона могла бы что-то загрузить: поле Status до и после
        List<ChunkPos> around = new ArrayList<>();
        for (int x = t.x - 12; x <= t.x + 14; x++) {
            for (int z = t.z - 12; z <= t.z + 12; z++) around.add(new ChunkPos(x, z));
        }
        java.util.Map<Long, String> before = new java.util.concurrent.ConcurrentHashMap<>(), after = new java.util.concurrent.ConcurrentHashMap<>();
        double scale = ua.zentix.airstrike.AirstrikeConfig.SERVER.nukeEffectsScale.get();
        List<String> waited = java.util.Collections.synchronizedList(new ArrayList<>());
        boolean[] prepared = {false};
        Runnable unwatch = NuclearGameTests.watchLog(line -> {
            if (line.contains("не дождались")) waited.add(line);
            if (line.startsWith("Руины удара №") && line.contains(" готовы:")) prepared[0] = true;
        });
        StrikeGameTests.afterTest(h, () -> {
            unwatch.run();
            ua.zentix.airstrike.AirstrikeConfig.SERVER.nukeEffectsScale.set(scale);
            NuclearStrikes.clear(level);
        });
        NuclearWorld w = NuclearWorld.get(level);
        ua.zentix.airstrike.nuclear.NuclearEvents events = ua.zentix.airstrike.nuclear.NuclearEvents.get(level);
        int[] stage = {0};
        long[] since = {0}, tickAt = {0};
        Detonation[] det = {null};
        LongOpenHashSet planned = new LongOpenHashSet();
        h.onEachTick(() -> {
            long now = level.getGameTime();
            switch (stage[0]) {
                case 0 -> {
                    // сгенерированное выгрузилось и записано: заголовки с диска — те, что останутся
                    if (!unloaded(level, around)) return;
                    for (ChunkPos c : around) readStatus(level, c, before);
                    stage[0] = 1;
                }
                case 1 -> {
                    if (before.size() < around.size()) return;
                    for (long c : whole) h.assertTrue("minecraft:full".equals(before.get(c)), "чанк " + new ChunkPos(c) + " не целый на диске: " + before.get(c));
                    ua.zentix.airstrike.AirstrikeConfig.SERVER.nukeEffectsScale.set(0.25);
                    events.schedule(new ua.zentix.airstrike.nuclear.NuclearEvents.ScheduledStrike(events.nextId(), target, 1, false, now, now + 1200, target,
                            java.util.Optional.empty(), true));
                    since[0] = now;
                    stage[0] = 2;
                }
                case 2 -> {
                    if (det[0] == null) {
                        det[0] = events.detonations().stream().filter(x -> x.burst().distanceTo(target) < 20).findFirst().orElse(null);
                        if (det[0] == null) {
                            if (now - since[0] > 1500) h.fail("подрыва нет");
                            // планы с диска строят фоновые потоки по настенным часам: полёт — в темпе игры, пока они не готовы
                            if (!prepared[0]) gamePace(tickAt);
                            return;
                        }
                        h.assertTrue(prepared[0], "руины заранее не готовы к подрыву за 60 с полёта");
                        for (ChunkPos c : far) h.assertTrue(chunks.getChunkNow(c.x, c.z) == null, "чанк " + c + " в памяти при подрыве: план не с диска, случай не тот");
                        since[0] = now;
                    }
                    for (ChunkPos c : far) if (w.scars().pendingPlan(det[0].id(), c.toLong())) planned.add(c.toLong());
                    if (w.plannedChunks() > 0 || w.prepTiles() > 0) {
                        // заголовки с диска, загрузка чанков и планы — по настенным часам: зона за волной — в темпе игры
                        // (сервер GameTest на CI тикает без пауз, ~2000 тиков/с, и срок кончался раньше чтения с диска)
                        gamePace(tickAt);
                        if (now - since[0] > 2400) {
                            StringBuilder left = new StringBuilder();
                            for (ChunkPos c : far) {
                                left.append(' ').append(c).append(w.scars().pendingPlan(det[0].id(), c.toLong()) ? " план ждёт" : "")
                                        .append(chunks.getChunkNow(c.x, c.z) != null ? " в памяти" : "").append(w.scars().ruined(det[0].id(), c.toLong()) ? " руины" : "");
                            }
                            h.fail("подготовка не кончилась за 2400 тиков после подрыва: планов " + w.plannedChunks() + ", квадратов " + w.prepTiles() + ";" + left);
                        }
                        return;
                    }
                    h.assertTrue(planned.size() == far.size(), "план с диска построен не у всех чанков B с целым окном: " + planned.size() + " из " + far.size());
                    for (ChunkPos c : far) {
                        LevelChunk chunk = level.getChunk(c.x, c.z);
                        h.assertTrue(chunk.getExistingData(ModAttachments.CHUNK_SCAR).orElse(0) == det[0].id(), "у чанка " + c + " руин нет: зона его не грузила");
                    }
                    for (String line : waited) {
                        // строка «не дождались N чанков (в зоне за волной M, …)» — только если план остался
                        h.assertFalse(line.startsWith("Руины подрыва №" + det[0].id() + ": не дождались") && !line.contains("в зоне за волной 0,"), "планы зоны пропали: " + line);
                    }
                    stage[0] = 3;
                }
                case 3 -> {
                    if (!unloaded(level, around)) return;
                    for (ChunkPos c : around) readStatus(level, c, after);
                    stage[0] = 4;
                }
                case 4 -> {
                    if (after.size() < around.size()) return;
                    for (ChunkPos c : around) {
                        h.assertTrue(before.get(c.toLong()).equals(after.get(c.toLong())), "чанк " + c + " догенерирован: " + before.get(c.toLong()) + " → " + after.get(c.toLong()));
                    }
                    h.succeed();
                }
                default -> {
                }
            }
        });
    }

    /**
     * Остановка по куче (живой объём кучи после сборок выше предела): руины заранее больше не строятся, но готовые планы
     * не пропадают — зона за волной грузит их чанки. До этого она после остановки не грузила ничего (игра Артёма
     * 01.10.2026: остановка с готовыми 453 планами, «не дождались»). Чанк без плана зона не грузит: полоса C к западу
     * становится целой только после остановки, плана ей нет, и её чанки так и не загружаются полностью (полную загрузку
     * на два чанка вокруг себя дают только тикеты очереди руин у загруженных чанков, C — дальше).
     */
    @GameTest(template = "range", timeoutTicks = 8000, batch = "nuke_zone_heap", skyAccess = true)
    public static void heapStopZoneLoadsOnlyPlanned(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        var chunks = level.getChunkSource();
        // своё место вдали, куда не ходит ни одна проверка: партии GameTest идут на одних и тех же местах площадок, и чанки
        // в 120 чанках от площадки загружали полностью прошлые партии полного прогона — окна полосы C бывали целыми на
        // диске до подготовки (места вдали других проверок — до 6000 блоков по диагонали и 12000 по осям)
        ChunkPos t = new ChunkPos(h.absolutePos(NuclearGameTests.CENTER).offset(-9000, 0, -9000));
        LongOpenHashSet whole = new LongOpenHashSet();
        for (int dx = -4; dx <= 4; dx++) {
            for (int dz = -4; dz <= 4; dz++) whole.add(ChunkPos.asLong(t.x + dx, t.z + dz));
        }
        for (int dx = 3; dx <= 8; dx++) {
            for (int dz = -3; dz <= 3; dz++) whole.add(ChunkPos.asLong(t.x + dx, t.z + dz));
        }
        for (long c : whole) level.getChunk(ChunkPos.getX(c), ChunkPos.getZ(c));
        Vec3 target = new Vec3(t.getMiddleBlockX() + 0.5,
                level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, t.getMiddleBlockX(), t.getMiddleBlockZ()), t.getMiddleBlockZ() + 0.5);
        // B: окно целое на диске до подготовки — план с диска
        List<ChunkPos> far = new ArrayList<>();
        for (int dx = 5; dx <= 6; dx++) {
            for (int dz = -1; dz <= 1; dz++) far.add(new ChunkPos(t.x + dx, t.z + dz));
        }
        // C: окно станет целым после остановки (генерируется полоса G). Полная загрузка без зоны — не дальше 6 чанков
        // от цели (место подрыва 9×9 и тикеты очереди руин с соседями радиуса 2), поэтому чанки G от 7 и дальше
        // загружаются полностью, только если их берёт зона
        List<ChunkPos> west = new ArrayList<>(), strip = new ArrayList<>(), never = new ArrayList<>();
        for (int dx = -8; dx <= -7; dx++) {
            for (int dz = -1; dz <= 1; dz++) west.add(new ChunkPos(t.x + dx, t.z + dz));
        }
        for (int dx = -10; dx <= -5; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                strip.add(new ChunkPos(t.x + dx, t.z + dz));
                if (dx <= -7) never.add(new ChunkPos(t.x + dx, t.z + dz));
            }
        }
        List<ChunkPos> around = new ArrayList<>(strip);
        for (long c : whole) around.add(new ChunkPos(c));
        java.util.Map<Long, String> before = new java.util.concurrent.ConcurrentHashMap<>();
        double scale = ua.zentix.airstrike.AirstrikeConfig.SERVER.nukeEffectsScale.get();
        List<String> waited = java.util.Collections.synchronizedList(new ArrayList<>());
        boolean[] prepared = {false}, stopped = {false};
        Runnable unwatch = NuclearGameTests.watchLog(line -> {
            if (line.contains("не дождались")) waited.add(line);
            if (line.startsWith("Руины удара №") && line.contains(" готовы:")) prepared[0] = true;
            if (line.contains("дальше руины заранее не строятся")) stopped[0] = true;
        });
        StrikeGameTests.afterTest(h, () -> {
            unwatch.run();
            ua.zentix.airstrike.AirstrikeConfig.SERVER.nukeEffectsScale.set(scale);
            NuclearStrikes.clear(level);
        });
        NuclearWorld w = NuclearWorld.get(level);
        ua.zentix.airstrike.nuclear.NuclearEvents events = ua.zentix.airstrike.nuclear.NuclearEvents.get(level);
        int[] stage = {0};
        long[] since = {0}, tickAt = {0};
        Detonation[] det = {null};
        LongOpenHashSet planned = new LongOpenHashSet();
        h.onEachTick(() -> {
            long now = level.getGameTime();
            switch (stage[0]) {
                case 0 -> {
                    if (!unloaded(level, around)) return;
                    for (ChunkPos c : around) readStatus(level, c, before);
                    stage[0] = 1;
                }
                case 1 -> {
                    if (before.size() < around.size()) return;
                    for (long c : whole) h.assertTrue("minecraft:full".equals(before.get(c)), "чанк " + new ChunkPos(c) + " не целый на диске: " + before.get(c));
                    for (ChunkPos c : west) {
                        boolean partial = false;
                        for (int dx = -2; dx <= 2; dx++) {
                            for (int dz = -2; dz <= 2; dz++) partial |= !"minecraft:full".equals(before.get(ChunkPos.asLong(c.x + dx, c.z + dz)));
                        }
                        h.assertTrue(partial, "окно чанка C " + c + " целое на диске до подготовки: план ему будет, случай не тот");
                    }
                    ua.zentix.airstrike.AirstrikeConfig.SERVER.nukeEffectsScale.set(0.25);
                    events.schedule(new ua.zentix.airstrike.nuclear.NuclearEvents.ScheduledStrike(events.nextId(), target, 1, false, now, now + 2400, target,
                            java.util.Optional.empty(), true));
                    since[0] = now;
                    stage[0] = 2;
                }
                case 2 -> {
                    if (now - since[0] > 2300) h.fail("руины заранее не готовы за 2300 тиков полёта");
                    // планы с диска строят фоновые потоки по настенным часам: полёт — в темпе игры, пока они не готовы
                    if (!prepared[0]) {
                        gamePace(tickAt);
                        return;
                    }
                    h.assertTrue(w.plannedChunks() > 0, "руины заранее готовы, а планов нет");
                    w.stopPrepForHeap();
                    for (ChunkPos c : strip) level.getChunk(c.x, c.z);
                    stage[0] = 3;
                }
                case 3 -> {
                    // полоса G сгенерирована и больше не загружена полностью (до подрыва её держит ниже полной загрузки
                    // район места подрыва): зона видит окна C целыми, но грузить их может только сама
                    if (events.detonations().stream().anyMatch(x -> x.burst().distanceTo(target) < 20)) h.fail("подрыв раньше, чем полоса G опустилась ниже полной загрузки");
                    for (ChunkPos c : strip) if (chunks.getChunkNow(c.x, c.z) != null) return;
                    stage[0] = 4;
                }
                case 4 -> {
                    if (det[0] == null) {
                        det[0] = events.detonations().stream().filter(x -> x.burst().distanceTo(target) < 20).findFirst().orElse(null);
                        if (det[0] == null) {
                            if (now - since[0] > 2700) h.fail("подрыва нет");
                            return;
                        }
                        h.assertTrue(stopped[0], "остановки по куче нет в логе");
                        for (ChunkPos c : far) h.assertTrue(chunks.getChunkNow(c.x, c.z) == null, "чанк " + c + " в памяти при подрыве: план не с диска, случай не тот");
                        since[0] = now;
                    }
                    for (ChunkPos c : never) h.assertTrue(chunks.getChunkNow(c.x, c.z) == null, "зона загрузила чанк " + c + " без плана после остановки по куче");
                    for (ChunkPos c : far) if (w.scars().pendingPlan(det[0].id(), c.toLong())) planned.add(c.toLong());
                    if (w.plannedChunks() > 0 || w.prepTiles() > 0) {
                        gamePace(tickAt);
                        if (now - since[0] > 2400) {
                            StringBuilder left = new StringBuilder();
                            for (ChunkPos c : far) {
                                left.append(' ').append(c).append(w.scars().pendingPlan(det[0].id(), c.toLong()) ? " план ждёт" : "")
                                        .append(chunks.getChunkNow(c.x, c.z) != null ? " в памяти" : "").append(w.scars().ruined(det[0].id(), c.toLong()) ? " руины" : "");
                            }
                            h.fail("после остановки по куче готовые планы не дождались зоны за 2400 тиков: планов " + w.plannedChunks() + ", квадратов " + w.prepTiles() + ";" + left);
                        }
                        return;
                    }
                    h.assertTrue(planned.size() == far.size(), "план с диска построен не у всех чанков B: " + planned.size() + " из " + far.size());
                    for (ChunkPos c : far) {
                        LevelChunk chunk = level.getChunk(c.x, c.z);
                        h.assertTrue(chunk.getExistingData(ModAttachments.CHUNK_SCAR).orElse(0) == det[0].id(), "у чанка " + c + " с планом руин нет: зона его не грузила");
                    }
                    for (String line : waited) {
                        h.assertFalse(line.startsWith("Руины подрыва №" + det[0].id() + ": не дождались") && !line.contains("в зоне за волной 0,"), "планы зоны пропали: " + line);
                    }
                    h.succeed();
                }
                default -> {
                }
            }
        });
    }

    /** Тик не короче 50 мс, как в игре: {@code tickAt} — когда кончился прошлый такой тик. */
    static void gamePace(long[] tickAt) {
        long deadline = tickAt[0] + 50_000_000L, left;
        while ((left = deadline - System.nanoTime()) > 0) java.util.concurrent.locks.LockSupport.parkNanos(left);
        tickAt[0] = System.nanoTime();
    }

    /** Ни одного чанка из списка нет в памяти (и в очереди выгрузки): записанное ими уже видно чтению с диска. */
    private static boolean unloaded(ServerLevel level, List<ChunkPos> list) {
        var map = level.getChunkSource().chunkMap;
        for (ChunkPos c : list) if (map.getVisibleChunkIfPresent(c.toLong()) != null || map.pendingUnloads.containsKey(c.toLong())) return false;
        return true;
    }

    /** Поле {@code Status} чанка с диска (в потоке ввода-вывода чанков); «нет» — чанка на диске нет. */
    private static void readStatus(ServerLevel level, ChunkPos c, java.util.Map<Long, String> out) {
        net.minecraft.nbt.visitors.CollectFields f = new net.minecraft.nbt.visitors.CollectFields(
                new net.minecraft.nbt.visitors.FieldSelector(net.minecraft.nbt.StringTag.TYPE, "Status"));
        level.getChunkSource().chunkMap.chunkScanner().scanChunk(c, f).whenComplete((v, e) -> out.put(c.toLong(),
                e != null ? "ошибка " + e : f.getResult() instanceof net.minecraft.nbt.CompoundTag tag ? tag.getString("Status") : "нет"));
    }

    /**
     * Квадрат зоны за волной пропускается, только если все его чанки полностью загружены сейчас: чанк в памяти, опущенный
     * ниже полной загрузки (край загруженного мира), квадрат не закрывает (gate 6: ряд из 11 чанков у края остался без руин).
     */
    @GameTest(template = "range", timeoutTicks = 1200, batch = "nuke_tile_full", skyAccess = true)
    public static void tileWithDemotedChunkIsNotFull(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        // квадрат на +40 чанков генерируется в фоне: тест — в темпе игры (иначе срок кончается раньше генерации на CI)
        StrikeGameTests.gameSpeed(h);
        var chunks = level.getChunkSource();
        ChunkPos base = new ChunkPos(h.absolutePos(BlockPos.ZERO));
        ChunkPos centre = new ChunkPos(base.x + 40, base.z);
        ChunkPos ring = new ChunkPos(centre.x + 2, centre.z);
        StrikeGameTests.afterTest(h, () -> {
            chunks.removeRegionTicket(TicketType.FORCED, centre, 2, centre);
            chunks.removeRegionTicket(TicketType.FORCED, centre, 1, centre);
        });
        chunks.addRegionTicket(TicketType.FORCED, centre, 2, centre);
        int[] stage = {0};
        h.onEachTick(() -> {
            if (stage[0] == 0 && NuclearPrep.tileFull(level, centre)) {
                // край квадрата опускается ниже полной загрузки, но остаётся в памяти
                chunks.addRegionTicket(TicketType.FORCED, centre, 1, centre);
                chunks.removeRegionTicket(TicketType.FORCED, centre, 2, centre);
                stage[0] = 1;
            }
        });
        h.succeedWhen(() -> {
            h.assertTrue(stage[0] == 1, "квадрат ещё не загрузился полностью");
            h.assertTrue(chunks.getChunkNow(ring.x, ring.z) == null, "край квадрата ещё полностью загружен");
            var holder = chunks.chunkMap.getVisibleChunkIfPresent(ring.toLong());
            h.assertTrue(holder != null && holder.getChunkIfPresentUnchecked(ChunkStatus.FULL) instanceof LevelChunk,
                    "край квадрата выгружен, а не опущен: не проверено");
            h.assertFalse(NuclearPrep.tileFull(level, centre), "квадрат с опущенным ниже полной загрузки чанком считается полным");
        });
    }
}
