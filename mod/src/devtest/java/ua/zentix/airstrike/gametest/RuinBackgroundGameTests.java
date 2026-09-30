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
     * Решатели руин, которые идут в фоновых потоках (разлом, обрушение, окно, снимок), не зовут мир, чанк, настройки
     * и методы состояний блоков, кроме чтения свойств ({@code getBlock}, {@code getValue}, {@code hasProperty},
     * {@code getFluidState}, {@code isAir}): всё остальное — из таблицы свойств, заполненной в потоке сервера.
     */
    @GameTest(template = "range", timeoutTicks = 20, batch = "nuke_background_calls", skyAccess = true)
    public static void backgroundSolversCallNoWorld(GameTestHelper h) {
        List<String> bad = new ArrayList<>();
        // класс → методы, которые идут в фоне (пусто — все)
        String[][] scope = {
                {"Blast$Solver"}, {"Collapse"}, {"RuinContext$Grid"},
                {"RuinWindow", "<init>", "get", "props", "air", "mayHave", "has", "top", "solid", "present", "stamp"},
                {"ChunkShot", "get", "props", "mayHave"},
        };
        Set<String> forbiddenOwners = Set.of("net/minecraft/world/level/Level", "net/minecraft/server/level/ServerLevel",
                "net/minecraft/world/level/chunk/LevelChunk", "net/minecraft/world/level/chunk/ChunkAccess",
                "net/minecraft/world/level/chunk/LevelChunkSection", "net/minecraft/world/level/BlockGetter",
                "net/minecraft/world/level/LevelReader", "net/minecraft/server/level/ServerChunkCache",
                "ua/zentix/airstrike/AirstrikeConfig", "ua/zentix/airstrike/AirstrikeConfig$Server",
                "net/neoforged/neoforge/common/ModConfigSpec$ConfigValue", "net/neoforged/neoforge/common/ModConfigSpec$BooleanValue");
        Set<String> stateOk = Set.of("getBlock", "getValue", "hasProperty", "getFluidState", "setValue", "isAir");
        for (String[] s : scope) {
            String name = s[0];
            Set<String> methods = s.length > 1 ? Set.of(java.util.Arrays.copyOfRange(s, 1, s.length)) : null;
            try (InputStream in = RuinPlan.class.getResourceAsStream(name + ".class")) {
                if (in == null) {
                    bad.add(name + ": класс не найден");
                    continue;
                }
                new ClassReader(in).accept(new ClassVisitor(Opcodes.ASM9) {
                    @Override
                    public MethodVisitor visitMethod(int access, String method, String desc, String sig, String[] ex) {
                        if (methods != null && !methods.contains(method)) return null;
                        return new MethodVisitor(Opcodes.ASM9) {
                            @Override
                            public void visitMethodInsn(int op, String owner, String called, String d, boolean itf) {
                                boolean state = owner.equals("net/minecraft/world/level/block/state/BlockState")
                                        || owner.equals("net/minecraft/world/level/block/state/BlockBehaviour$BlockStateBase")
                                        || owner.equals("net/minecraft/world/level/block/state/StateHolder");
                                boolean block = owner.equals("net/minecraft/world/level/block/Block") || owner.equals("net/minecraft/world/level/block/state/BlockBehaviour");
                                if (forbiddenOwners.contains(owner) || state && !stateOk.contains(called) || block && !called.equals("defaultBlockState")
                                        || owner.endsWith("/Blast") && (called.equals("props") || called.equals("ensure") || called.equals("publish"))) {
                                    bad.add(name + "." + method + " → " + owner.substring(owner.lastIndexOf('/') + 1) + "." + called);
                                }
                            }
                        };
                    }
                }, ClassReader.SKIP_DEBUG);
            } catch (IOException e) {
                bad.add(name + ": " + e);
            }
        }
        h.assertTrue(bad.isEmpty(), "фоновые решатели зовут мир или свойства мимо таблицы: " + bad);
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
