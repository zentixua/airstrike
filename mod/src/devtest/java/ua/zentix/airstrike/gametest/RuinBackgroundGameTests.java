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
        // корни: класс, метод (описание не важно — все перегрузки)
        String[][] roots = {{"Collapse", "solve"}, {"Blast", "solve"}, {"RuinPlanner", "finish"}, {"DiskShots", "parse"}};
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
        for (String[] r : roots) todo.add(new String[]{pkg + r[0], r[1], null, r[0] + "." + r[1]});
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
        h.assertTrue(bad.isEmpty(), "фоновые задачи руин зовут мир или свойства мимо таблицы: " + String.join("; ", bad.subList(0, Math.min(bad.size(), 20))));
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
