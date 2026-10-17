package ua.zentix.incoming.client.nuclear;

import it.unimi.dsi.fastutil.longs.Long2LongMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/**
 * Огонь, который клиент видит: блоки огня в своих чанках вокруг камеры, по чанку за чанком, сколько успеется за
 * {@link #BUDGET_NANOS} тика (весь круг прорисовки — за секунды; пожары меняются минутами). Секция без огня в палитре не
 * смотрится ({@code maybeHas}); с огнём — выборкой: каждый {@link #SAMPLE}-й блок, счёт ×{@link #SAMPLE}. Полный счёт
 * ({@code PalettedContainer.count}: все 4096 мест в новую карту) стоил бы миллисекунд за тик: в подробном городе палитра
 * секции глобальная ({@code maybeHas} всегда да), а малая помнит сгоревший огонь. По этому огню звук горящего города ставит
 * огонь, который рядом, на его место, а дальнюю модель ({@link FireField}) — в меру того, что горит на деле (настройки
 * пожаров сервера клиент не знает: пожары могут быть выключены или погашены).
 */
final class FireWatch {
    /** Время на огонь за тик, нс (хотя бы один чанк). */
    static final long BUDGET_NANOS = 100_000;
    /** Чанков за тик не больше. */
    static final int MAX_PER_TICK = 8;
    /** Выборка в секции: каждый столько-й блок (по x — 2 места из 16, все y и z). */
    static final int SAMPLE = 8;
    /** Чанк → (число огня << 16) | высота огня над дном мира. */
    private static final Long2LongOpenHashMap SEEN = new Long2LongOpenHashMap();
    @Nullable
    private static ClientLevel level;
    private static int cursor, total, phase;
    /** Цена с прошлой строки сценария: время за тики с огнём, сколько тиков, самый долгий тик (нс), чанков и секций. */
    private static long spent, ticks, slowest, chunks, scanned;

    private FireWatch() {}

    /** Ещё часть чанков в радиусе {@code radius} чанков от чанка камеры. */
    static void tick(ClientLevel lvl, int cx, int cz, int radius) {
        long t0 = System.nanoTime();
        if (lvl != level) reset();
        level = lvl;
        int side = 2 * radius + 1, all = side * side;
        for (int k = 0; k < Math.min(MAX_PER_TICK, all); k++) {
            if (k > 0 && System.nanoTime() - t0 > BUDGET_NANOS) break;
            int i = cursor++ % all;
            if (cursor >= all) {
                cursor = 0;
                phase = (phase + 1) % SAMPLE;
                prune(lvl);
            }
            int x = cx - radius + i % side, z = cz - radius + i / side;
            LevelChunk chunk = lvl.getChunkSource().getChunk(x, z, false);
            long key = ChunkPos.asLong(x, z);
            long was = SEEN.remove(key);
            total -= (int) (was >>> 16);
            if (chunk == null) continue;
            chunks++;
            long now = scan(chunk, phase);
            if (now != 0) {
                SEEN.put(key, now);
                total += (int) (now >>> 16);
            }
        }
        long dt = System.nanoTime() - t0;
        spent += dt;
        ticks++;
        slowest = Math.max(slowest, dt);
    }

    /** Огонь чанка выборкой: (число << 16) | средняя высота; {@code offset} — сдвиг выборки (свой в каждом обходе). */
    private static long scan(LevelChunk chunk, int offset) {
        LevelChunkSection[] sections = chunk.getSections();
        long count = 0, ySum = 0;
        for (int i = 0; i < sections.length; i++) {
            LevelChunkSection s = sections[i];
            if (s.hasOnlyAir() || !s.maybeHas(FireWatch::fire)) continue;
            scanned++;
            PalettedContainer<BlockState> states = s.getStates();
            int n = 0;
            // индекс секции — (y << 8) | (z << 4) | x
            for (int j = offset; j < 4096; j += SAMPLE) {
                if (fire(states.get(j & 15, j >> 8, (j >> 4) & 15))) n++;
            }
            n *= SAMPLE;
            count += n;
            ySum += (long) n * (i * 16 + 8);
        }
        if (count == 0) return 0;
        return Math.min(count, 0xFFFFFFL) << 16 | (ySum / count) & 0xFFFF;
    }

    private static boolean fire(BlockState state) {
        return state.getBlock() instanceof BaseFireBlock;
    }

    /** Чанки, которых у клиента больше нет. */
    private static void prune(ClientLevel lvl) {
        for (var it = SEEN.long2LongEntrySet().iterator(); it.hasNext(); ) {
            Long2LongMap.Entry e = it.next();
            if (lvl.getChunkSource().getChunk(ChunkPos.getX(e.getLongKey()), ChunkPos.getZ(e.getLongKey()), false) == null) {
                total -= (int) (e.getLongValue() >>> 16);
                it.remove();
            }
        }
    }

    /** Огонь по чанкам. */
    interface Visitor {
        void fire(double x, double y, double z, int count);
    }

    static void forEach(Visitor v) {
        if (level == null) return;
        int minY = level.getMinBuildHeight();
        for (Long2LongMap.Entry e : SEEN.long2LongEntrySet()) {
            long key = e.getLongKey(), val = e.getLongValue();
            v.fire(ChunkPos.getX(key) * 16 + 8, minY + (val & 0xFFFF), ChunkPos.getZ(key) * 16 + 8, (int) (val >>> 16));
        }
    }

    static int total() {
        return total;
    }

    /** Ничего не горит: забыть мир и чанки (мир не держится после смены измерения). */
    static void idle() {
        if (level != null || !SEEN.isEmpty()) reset();
    }

    static void reset() {
        SEEN.clear();
        cursor = total = 0;
        level = null;
    }

    /**
     * Для строки {@code SCENARIO}: цена за тик с прошлой строки (средняя и самая долгая, мс), чанков и секций с огнём
     * в палитре за тик, огня всего; счёт — заново.
     */
    static String describe() {
        String s = ticks == 0 ? "watch -" : String.format(Locale.ROOT, "watch %.3f/%.3f ms/tick chunks %.1f sections %.1f fire %d",
                spent / 1e6 / ticks, slowest / 1e6, chunks / (double) ticks, scanned / (double) ticks, total);
        spent = ticks = slowest = chunks = scanned = 0;
        return s;
    }
}
