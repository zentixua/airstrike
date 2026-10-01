package ua.zentix.airstrike.util;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

/**
 * Высота рельефа без ожидания: на сервере {@code Level.getHeight} для чанка, который загружен, но ещё не готов
 * к выдаче, ждёт его в {@code managedBlock} — и выполняет в это время чужие задачи загрузки (до сотен мс за вызов).
 * Здесь берётся только уже готовый чанк ({@code getChunkNow}); нет чанка — как у ванили, нижняя граница мира.
 */
public final class Terrain {
    private Terrain() {}

    /**
     * Чанк уже загружен и готов: на сервере — {@code getChunkNow}, а не {@code hasChunk}/{@code isLoaded}
     * (те верны и для чанка, который ещё грузится, и чтение из него ждёт загрузку).
     */
    public static boolean ready(Level level, int chunkX, int chunkZ) {
        if (level instanceof ServerLevel server) return server.getChunkSource().getChunkNow(chunkX, chunkZ) != null;
        return level.hasChunk(chunkX, chunkZ);
    }

    public static boolean ready(Level level, BlockPos pos) {
        return ready(level, pos.getX() >> 4, pos.getZ() >> 4);
    }

    /** Готовы чанк и все восемь соседей: цепочка обновлений от блока в нём (до 16 блоков) не выходит за готовые. */
    public static boolean neighbourhoodReady(Level level, int chunkX, int chunkZ) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) if (!ready(level, chunkX + dx, chunkZ + dz)) return false;
        }
        return true;
    }

    /** Готовы все чанки, которых касается квадрат со стороной {@code 2r} вокруг точки. */
    public static boolean readyAround(Level level, Vec3 centre, double r) {
        for (int cx = Mth.floor(centre.x - r) >> 4; cx <= Mth.floor(centre.x + r) >> 4; cx++) {
            for (int cz = Mth.floor(centre.z - r) >> 4; cz <= Mth.floor(centre.z + r) >> 4; cz++) {
                if (!ready(level, cx, cz)) return false;
            }
        }
        return true;
    }

    /**
     * Докуда отрезок {@code from → to} идёт по готовым чанкам: точка чуть до входа в первый неготовый чанк (или
     * {@code to}, если весь путь готов). Луч {@code Level.clip} читает каждый блок на пути, а на сервере чтение
     * незагруженного чанка грузит и генерирует его прямо в тике — длинный луч (прицел до 1024 блоков, нос снаряда)
     * вставал бы на секунды. На клиенте незагруженный чанк — пустой, обрезать нечего.
     */
    public static Vec3 readyUntil(Level level, Vec3 from, Vec3 to) {
        if (!(level instanceof ServerLevel)) return to;
        double t = readyFraction(from.x, from.z, to.x, to.z, (x, z) -> ready(level, x, z));
        return t >= 1 ? to : from.lerp(to, t);
    }

    /** Весь отрезок идёт по готовым чанкам (см. {@link #readyUntil}). */
    public static boolean readyAlong(Level level, Vec3 from, Vec3 to) {
        return !(level instanceof ServerLevel) || readyFraction(from.x, from.z, to.x, to.z, (x, z) -> ready(level, x, z)) >= 1;
    }

    /** Готов ли чанк (x, z). */
    @FunctionalInterface
    public interface ChunkReady {
        boolean test(int chunkX, int chunkZ);
    }

    /**
     * Доля отрезка (0..1) до первой неготовой колонки чанков на его пути. Колонки обходятся по сетке
     * ({@link GridWalk}): ни одна пересечённая колонка не пропускается, даже если отрезок срезает угол.
     * Конец берётся на {@link #EDGE} блока раньше границы, чтобы последний проверяемый блок остался в готовом чанке.
     */
    public static double readyFraction(double fromX, double fromZ, double toX, double toZ, ChunkReady ready) {
        double t = GridWalk.walk(fromX, fromZ, toX, toZ, 16, ready::test);
        return t >= 1 ? 1 : Math.max(0, t - EDGE / Math.max(1.0e-9, Math.hypot(toX - fromX, toZ - fromZ)));
    }

    private static final double EDGE = 0.01;

    /**
     * Первый воздух над картой высот (как {@link Level#getHeight}) — источник {@link Source#CHUNK} без выбора: для кода,
     * который сам знает, что чанк готов (район взрыва, очередь ядерки), или которому низ мира — верный ответ. «Где земля»
     * там, где чанк может быть не готов, — {@link #estimate}.
     */
    public static int height(Level level, Heightmap.Types type, int x, int z) {
        if (level instanceof ServerLevel server) {
            LevelChunk chunk = server.getChunkSource().getChunkNow(x >> 4, z >> 4);
            return chunk == null ? level.getMinBuildHeight() : chunk.getHeight(type, x & 15, z & 15) + 1;
        }
        return level.getHeight(type, x, z);
    }

    /** Откуда известна высота поверхности — от точного к грубому; {@link #estimate} берёт первый разрешённый. */
    public enum Source {
        /** Карта высот готового чанка. */
        CHUNK,
        /**
         * Карта высот готового чанка в мире с потолком (Незер): это верх потолка из коренной породы, а не земля. В мире
         * она годится для обхода препятствий, для полёта вне мира и точки на земле — нет.
         */
        CEILING,
        /** Верх по карте клиента, на которой игрок выбрал место (Distant Horizons или чанки клиента), — подсказка приказа. */
        CLIENT_MAP,
        /**
         * Рельеф генератора мира ({@code ChunkGenerator.getBaseHeight}: шум без загрузки чанка, без деревьев и построек;
         * ≈3 мс на точку — только для приказа, не для тика), не ниже его уровня моря (над водой поверхность — сама вода).
         * У мира, построенного не этим генератором, он далёк от поверхности: город с карты мира (Greenfield 30.09.2026:
         * 63 под крышей на 107), мир 1.17, поднятый до 1.21 (Newisle: генератор давал дно мира, удар «по 83 -64 -370»);
         * уровень моря держит оценку хотя бы у суши.
         */
        GENERATOR,
        /**
         * Уровень моря генератора — дёшево, для тика: ниже него суша почти не бывает. Не {@code Level.getSeaLevel}:
         * в 1.21.1 он всегда 63, а у плоского мира море −63.
         */
        SEA,
        /** Ни один разрешённый источник не знает: низ мира, как у ванили для незагруженного чанка. */
        UNKNOWN
    }

    /** Высота поверхности (первый воздух над ней) и откуда она известна. */
    public record Surface(int y, Source source) {
        /** Высоту дал какой-то источник (не {@link Source#UNKNOWN}). */
        public boolean known() {
            return source != Source.UNKNOWN;
        }
    }

    /**
     * Какие источники вызывающему разрешены и подсказка карты клиента, если она есть. Цена — в самом наборе:
     * {@link #CHUNK} и {@link #FLIGHT} годятся для тика, {@link #ORDER} читает генератор — только для приказа.
     */
    public static final class Allowed {
        /** В мире, в тике: только готовый чанк (и потолок, где он есть), иначе низ мира. */
        public static final Allowed CHUNK = new Allowed(Set.of(Source.CHUNK, Source.CEILING), Optional.empty());
        /** Полёт вне мира, в тике: готовый чанк, иначе уровень моря; потолок — не земля. */
        public static final Allowed FLIGHT = new Allowed(Set.of(Source.CHUNK, Source.SEA), Optional.empty());
        /** Приказ (раз на пуск): готовый чанк, иначе рельеф генератора. */
        public static final Allowed ORDER = new Allowed(Set.of(Source.CHUNK, Source.CEILING, Source.GENERATOR), Optional.empty());

        private final Set<Source> sources;
        private final Optional<Integer> map;

        /** Снаружи — только наборы выше и {@link #withMap}. */
        Allowed(Set<Source> sources, Optional<Integer> map) {
            if (sources.contains(Source.CLIENT_MAP) != map.isPresent()) throw new IllegalArgumentException("CLIENT_MAP — только с подсказкой карты");
            this.sources = Set.copyOf(sources);
            this.map = map;
        }

        /**
         * Те же источники и ещё {@link Source#CLIENT_MAP}: {@code top} — первый воздух над землёй по карте клиента;
         * пустая подсказка или верх, чей блок вне высот мира, не в счёт.
         */
        public Allowed withMap(Optional<Integer> top) {
            if (top.isEmpty()) return this;
            Set<Source> s = EnumSet.of(Source.CLIENT_MAP);
            s.addAll(sources);
            return new Allowed(s, top);
        }

        boolean permits(Source source) {
            return sources.contains(source);
        }

        /** Подсказка карты клиента (есть, только если разрешён {@link Source#CLIENT_MAP}). */
        Optional<Integer> map() {
            return map;
        }
    }

    /**
     * Высота поверхности в колонке (x, z) — единственный ответ мода на «где земля»: первый источник из {@code allowed}
     * по порядку {@link Source}, который её знает. Чанк не грузится: готовность — {@link #ready}. На клиенте генератора
     * нет — только чанк и карта; там же нет карт высот {@code …_NO_LEAVES} (клиенту приходят лишь
     * {@code MOTION_BLOCKING} и {@code WORLD_SURFACE}).
     * Не высота цели: цель бывает в воздухе (игрок в полёте, аппарат), а поверхность под ней — нет.
     */
    public static Surface estimate(Level level, Heightmap.Types type, int x, int z, Allowed allowed) {
        return choose(new LevelColumn(level, type, x, z), allowed);
    }

    /** Что мир знает о колонке — по источникам {@link Source}; {@link #generatorBase} дорог и зовётся, только если нужен. */
    interface Column {
        /** Чанк колонки готов. */
        boolean ready();

        /** Первый воздух над картой высот готового чанка. */
        int chunkHeight();

        /** В мире есть потолок (карта высот — его верх). */
        boolean ceiling();

        /** Есть генератор мира (сервер). */
        boolean hasGenerator();

        int sea();

        int generatorBase();

        /** Блок {@code y} вне высот мира. */
        boolean outside(int y);

        int bottom();
    }

    /** Выбор источника (без Minecraft — для {@code TerrainTest}). */
    static Surface choose(Column c, Allowed allowed) {
        if (c.ready()) {
            Source chunk = c.ceiling() ? Source.CEILING : Source.CHUNK;
            if (allowed.permits(chunk)) return new Surface(c.chunkHeight(), chunk);
        }
        if (allowed.permits(Source.CLIENT_MAP)) {
            int top = allowed.map().orElseThrow();
            if (!c.outside(top - 1)) return new Surface(top, Source.CLIENT_MAP);
        }
        if (c.hasGenerator()) {
            if (allowed.permits(Source.GENERATOR)) return new Surface(Math.max(c.sea(), c.generatorBase()), Source.GENERATOR);
            if (allowed.permits(Source.SEA)) return new Surface(c.sea(), Source.SEA);
        }
        return new Surface(c.bottom(), Source.UNKNOWN);
    }

    private record LevelColumn(Level level, Heightmap.Types type, int x, int z) implements Column {
        @Override
        public boolean ready() {
            return Terrain.ready(level, x >> 4, z >> 4);
        }

        @Override
        public int chunkHeight() {
            return height(level, type, x, z);
        }

        @Override
        public boolean ceiling() {
            return level.dimensionType().hasCeiling();
        }

        @Override
        public boolean hasGenerator() {
            return level instanceof ServerLevel;
        }

        @Override
        public int sea() {
            return generator().getSeaLevel();
        }

        @Override
        public int generatorBase() {
            ServerLevel server = (ServerLevel) level;
            return generator().getBaseHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG, server, server.getChunkSource().randomState());
        }

        @Override
        public boolean outside(int y) {
            return level.isOutsideBuildHeight(y);
        }

        @Override
        public int bottom() {
            return level.getMinBuildHeight();
        }

        private ChunkGenerator generator() {
            return ((ServerLevel) level).getChunkSource().getGenerator();
        }
    }
}
