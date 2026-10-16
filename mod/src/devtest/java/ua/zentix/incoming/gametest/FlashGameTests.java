package ua.zentix.incoming.gametest;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.storage.ChunkSerializer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.net.S2C;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.nuclear.flash.FlashChar;
import ua.zentix.incoming.nuclear.flash.FlashMarks;
import ua.zentix.incoming.nuclear.flash.FlashShadows;
import ua.zentix.incoming.nuclear.flash.FlashSync;
import ua.zentix.incoming.nuclear.world.ColumnScar;
import ua.zentix.incoming.nuclear.world.WorkClock;

import java.util.Locale;

/**
 * Следы вспышки (DESIGN-nuke §3.5): гарь на гранях к шару — с руинами чанка, силуэт тела на стене за ним, отметка чанка
 * переживает сохранение, уходит с блоком и уходит игроку, которому чанк отдают. Стены — из брёвен (без листвы это не
 * дерево: волна их не валит) и обсидиана (не ломается). Каждая проверка — своей партией (подрыв задевает соседей).
 */
@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
public final class FlashGameTests {
    private static final BlockPos CENTER = NuclearGameTests.CENTER;
    /** Стена — в чанке, чьи соседи тоже на площадке (x и z от 16 до 47): к сроку готовы и они. */
    private static final int WALL_DX = 12;

    private FlashGameTests() {}

    /** Бревенчатая стена 5×4 лицом к взрыву (на запад): брёвна без листвы — не дерево, волна их не валит. */
    private static void wall(GameTestHelper h) {
        for (int dz = -2; dz <= 2; dz++) {
            for (int y = 0; y < 4; y++) h.setBlock(CENTER.east(WALL_DX).south(dz).above(y), Blocks.OAK_LOG);
        }
    }

    /** 15 кт в воздухе над серединой, 8 psi на стене: свет там — десятки кал/см². */
    private static Detonation burst(GameTestHelper h) {
        return NuclearGameTests.atPsi(h, CENTER, true, CENTER.east(WALL_DX).above(1), 8);
    }

    private static int marked(FlashMarks m, int detonation, BlockPos abs, Direction face, boolean shadows) {
        for (FlashMarks.Layer l : m.layers()) {
            if (l.detonation() != detonation) continue;
            int key = FlashChar.key(abs.getX() & 15, abs.getY(), abs.getZ() & 15, face);
            for (int c : shadows ? l.shadows() : l.chars()) if ((c & FlashChar.KEY_MASK) == key) return FlashChar.level(c);
        }
        return 0;
    }

    /**
     * Гарь с руинами: грани досок к шару потемнели, спины — нет; грань, закрытая камнем вплотную, и грань в тени
     * высокой стены — чистые. Отметка переживает запись чанка (как на диск), снятый блок уходит из следов, а игрок,
     * которому чанк отдают, получает следы пакетом, который разбирается обратно в то же.
     */
    @GameTest(template = "range", timeoutTicks = 60, batch = "nuke_flash_chars", skyAccess = true)
    public static void charredFacesTowardFireball(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        wall(h);
        // закрытая грань: обсидиан вплотную к западу
        BlockPos covered = CENTER.east(WALL_DX).south(6);
        h.setBlock(covered, Blocks.OAK_LOG);
        h.setBlock(covered.west(), Blocks.OBSIDIAN);
        // грань в тени: бревно за высокой стеной из обсидиана
        BlockPos shaded = CENTER.east(WALL_DX).north(6);
        h.setBlock(shaded, Blocks.OAK_LOG);
        for (int dz = -2; dz <= 2; dz++) for (int y = 0; y < 20; y++) h.setBlock(shaded.west(2).south(dz).above(y), Blocks.OBSIDIAN);
        Detonation d = burst(h);
        double q = d.fluence(Vec3.atCenterOf(h.absolutePos(CENTER.east(WALL_DX).above(1))));
        h.assertTrue(q > 15, String.format(Locale.ROOT, "свет на стене %.1f кал/см² — слабо для проверки", q));
        NuclearGameTests.scarAll(h, d, new ColumnScar.Budget(true));
        int lit = 0, faces = 0;
        for (int dz = -2; dz <= 2; dz++) {
            for (int y = 0; y < 4; y++) {
                BlockPos at = CENTER.east(WALL_DX).south(dz).above(y);
                h.assertBlockPresent(Blocks.OAK_LOG, at);
                BlockPos abs = h.absolutePos(at);
                FlashMarks m = FlashSync.of(level.getChunkAt(abs));
                faces++;
                if (marked(m, d.id(), abs, Direction.WEST, false) > 0) lit++;
                if (marked(m, d.id(), abs, Direction.EAST, false) > 0) h.fail("обуглена спина стены " + at.toShortString());
            }
        }
        h.assertTrue(lit >= faces * 3 / 4, "обуглено граней к шару " + lit + " из " + faces + String.format(Locale.ROOT, " (%.1f кал/см²)", q));
        BlockPos coveredAbs = h.absolutePos(covered), shadedAbs = h.absolutePos(shaded);
        if (marked(FlashSync.of(level.getChunkAt(coveredAbs)), d.id(), coveredAbs, Direction.WEST, false) > 0) h.fail("обуглена грань под камнем");
        if (marked(FlashSync.of(level.getChunkAt(shadedAbs)), d.id(), shadedAbs, Direction.WEST, false) > 0) h.fail("обуглена грань в тени стены");

        // запись чанка: отметка — в данных чанка и читается обратно той же
        BlockPos probe = CENTER.east(WALL_DX).above(1);
        LevelChunk chunk = level.getChunkAt(h.absolutePos(probe));
        FlashMarks before = FlashSync.of(chunk);
        CompoundTag saved = ChunkSerializer.write(level, chunk);
        Tag attachment = saved.getCompound(net.neoforged.neoforge.attachment.AttachmentHolder.ATTACHMENTS_NBT_KEY).get(Incoming.MOD_ID + ":flash_marks");
        h.assertTrue(attachment != null, "следов нет в записи чанка: " + saved.getCompound(net.neoforged.neoforge.attachment.AttachmentHolder.ATTACHMENTS_NBT_KEY).getAllKeys());
        FlashMarks read = FlashMarks.CODEC.parse(NbtOps.INSTANCE, attachment).getOrThrow();
        h.assertTrue(same(before, read), "следы после записи не те");

        // пакет игроку, которому отдают чанк, — те же следы
        S2C.NukeFlashMarks packet = FlashSync.packet(chunk);
        h.assertTrue(packet != null, "пакета следов нет");
        io.netty.buffer.ByteBuf buf = io.netty.buffer.Unpooled.buffer();
        S2C.NukeFlashMarks.CODEC.encode(buf, packet);
        S2C.NukeFlashMarks back = S2C.NukeFlashMarks.CODEC.decode(buf);
        h.assertTrue(back.chunk() == chunk.getPos().toLong() && same(back.marks(), before), "пакет следов разобрался не тем");

        // снятый блок уходит из следов
        BlockPos gone = h.absolutePos(probe);
        h.assertTrue(marked(before, d.id(), gone, Direction.WEST, false) > 0, "у снимаемой доски нет следа");
        level.setBlock(gone, Blocks.AIR.defaultBlockState(), 2);
        S2C.NukeFlashMarks after = FlashSync.packet(chunk);
        FlashMarks left = after == null ? FlashMarks.EMPTY : after.marks();
        h.assertTrue(marked(left, d.id(), gone, Direction.WEST, false) == 0, "след снятого блока остался");
        h.assertTrue(marked(FlashSync.of(chunk), d.id(), gone, Direction.WEST, false) == 0, "след снятого блока остался в отметке");
        h.succeed();
    }

    /**
     * Силуэт: стойка для брони (рамка человека) в полутора блоках перед бревенчатой стеной к шару — на стене за ней грани
     * с тенью, клетки в полной тени есть и лежат на продолжении луча от шара через тело; грани стены в стороне — без тени.
     */
    @GameTest(template = "range", timeoutTicks = 60, batch = "nuke_flash_shadow", skyAccess = true)
    public static void silhouetteBehindBody(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        for (int dz = -4; dz <= 4; dz++) {
            for (int y = 0; y < 5; y++) h.setBlock(CENTER.east(WALL_DX).south(dz).above(y), Blocks.OAK_LOG);
        }
        Detonation d = burst(h);
        BlockPos standAt = CENTER.east(WALL_DX - 2);
        Vec3 feet = Vec3.atBottomCenterOf(h.absolutePos(standAt)).add(0.5, 0, 0);
        ArmorStand stand = EntityType.ARMOR_STAND.create(level);
        h.assertTrue(stand != null, "нет стойки");
        stand.moveTo(feet.x, feet.y, feet.z, 90, 0);
        stand.setYBodyRot(90);
        level.addFreshEntity(stand);
        FlashShadows job = FlashShadows.of(level, d);
        h.assertTrue(job != null, "тел перед стеной не нашлось");
        stand.discard();
        WorkClock clock = WorkClock.counting(1_000_000L);
        int ticks = 0;
        do {
            clock.start(30_000_000L);
        } while (!job.work(level, clock) && ++ticks < 100);
        // где луч от шара через середину тела упирается в стену
        Vec3 c = feet.add(0, stand.getBbHeight() / 2, 0), dir = c.subtract(d.burst()).normalize();
        double wallX = h.absolutePos(CENTER.east(WALL_DX)).getX();
        Vec3 hit = c.add(dir.scale((wallX - c.x) / dir.x));
        int shadowFaces = 0, full = 0;
        BlockPos hitBlock = BlockPos.containing(wallX + 0.5, hit.y, hit.z);
        for (int dz = -4; dz <= 4; dz++) {
            for (int y = 0; y < 5; y++) {
                BlockPos abs = h.absolutePos(CENTER.east(WALL_DX).south(dz).above(y));
                FlashMarks m = FlashSync.of(level.getChunkAt(abs));
                for (FlashMarks.Layer l : m.layers()) {
                    if (l.detonation() != d.id()) continue;
                    for (int k = 0; k < l.shadows().length; k++) {
                        int s = l.shadows()[k];
                        if ((s & FlashChar.KEY_MASK) != FlashChar.key(abs.getX() & 15, abs.getY(), abs.getZ() & 15, Direction.WEST)) continue;
                        shadowFaces++;
                        if (Math.abs(abs.getZ() - hitBlock.getZ()) > 2 || Math.abs(abs.getY() - hitBlock.getY()) > 3) {
                            h.fail("тень далеко от тела: " + abs.toShortString() + ", луч упирается в " + hitBlock.toShortString());
                        }
                        for (int u = 0; u < FlashMarks.CELLS; u++) for (int v = 0; v < FlashMarks.CELLS; v++) if (l.shade(k, u, v) == 3) full++;
                    }
                }
            }
        }
        h.assertTrue(shadowFaces > 0, "силуэта на стене нет (луч от шара через тело — в " + hitBlock.toShortString() + ")");
        h.assertTrue(full >= 20, "клеток в полной тени " + full + " — силуэт пустой");
        h.succeed();
    }

    /** Те же следы (слои, метки, маски). */
    private static boolean same(FlashMarks a, FlashMarks b) {
        if (a.layers().size() != b.layers().size()) return false;
        for (int i = 0; i < a.layers().size(); i++) {
            FlashMarks.Layer x = a.layers().get(i), y = b.layers().get(i);
            if (x.detonation() != y.detonation() || x.time() != y.time() || !java.util.Arrays.equals(x.chars(), y.chars())
                    || !java.util.Arrays.equals(x.shadows(), y.shadows()) || !java.util.Arrays.equals(x.masks(), y.masks())) return false;
        }
        return true;
    }
}
