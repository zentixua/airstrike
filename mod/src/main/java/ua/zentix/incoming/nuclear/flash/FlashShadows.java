package ua.zentix.incoming.nuclear.flash;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.vehicle.VehicleEntity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.nuclear.model.WorldNoise;
import ua.zentix.incoming.nuclear.world.ThermalShadow;
import ua.zentix.incoming.nuclear.world.WorkClock;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * «Хиросимские тени» (DESIGN-nuke §3.5): тело между шаром и стеной заслоняет свет, и на обугленной вокруг грани за ним
 * остаётся светлый силуэт. В тике подрыва — только снимок тел (место, рамка, поворот: свет кончается раньше, чем тело
 * успеет упасть); дальше по телу на единицу работы под бюджетом ядерки: лучи от тела прочь от шара до первых полных
 * блоков ({@link #REACH} блоков, только готовые чанки), их грани к шару и соседние в той же плоскости — кандидаты;
 * клетка грани 16×16 (пиксель текстуры) в тени, если луч от неё к шару проходит через тело. Шар — не точка: три точки
 * на его диске дают полутень (тень 0, ⅓, ⅔, целиком), и чем дальше стена от тела, тем мягче край.
 * <p>
 * Тело — человек (игрок, зомби, житель: высокая узкая рамка) из прямоугольников модели игрока (ноги, туловище, руки,
 * голова) с поворотом туловища, остальные — эллипсоид в рамке, транспорт — рамка. Ступень гари грани — та же
 * {@link FlashChar#level}, что у плана руин; грань, которая не потемнела бы, силуэта не держит. Силуэты пишутся
 * в отметку чанка ({@link FlashSync#shadows}) в конце тика работы — одним пакетом на чанк.
 */
public final class FlashShadows {
    /** Докуда за телом ищется стена, блоки. */
    static final int REACH = 12;
    /** Тел на подрыв, не больше (ближние к шару первыми). */
    static final int MAX_BODIES = 256;
    /** Доля радиуса шара, на которой лежат три точки полутени. */
    static final double PENUMBRA = 0.35;

    private static final Direction[] DIRECTIONS = Direction.values();

    /** Тело в миг вспышки: низ рамки по центру, ширина и высота рамки, поворот (градусы), вид. */
    record Body(double x, double y, double z, double width, double height, float yaw, Shape shape) {
        Vec3 centre() {
            return new Vec3(x, y + height / 2, z);
        }

        double radius() {
            return Math.sqrt(width * width / 2 + height * height / 4);
        }
    }

    enum Shape {HUMAN, BLOB, BOX}

    private final Detonation d;
    private final WorldNoise noise;
    private final List<Body> bodies;
    private int next;
    private int faces;
    private long nanos;
    /** Силуэты к записи в конце тика: чанк → грани и маски. */
    private final Long2ObjectOpenHashMap<IntArrayList> pendingFaces = new Long2ObjectOpenHashMap<>();
    private final Long2ObjectOpenHashMap<LongArrayList> pendingMasks = new Long2ObjectOpenHashMap<>();

    private FlashShadows(ServerLevel level, Detonation d, List<Body> bodies) {
        this.d = d;
        this.noise = new WorldNoise(level.getSeed(), d.scale());
        this.bodies = bodies;
    }

    public Detonation detonation() {
        return d;
    }

    /**
     * Снимок тел в тике подрыва (поток сервера): сущности, рядом с которыми хоть одна грань ещё может потемнеть.
     * Null — таких нет.
     */
    @Nullable
    public static FlashShadows of(ServerLevel level, Detonation d) {
        double reach = reach(d);
        if (reach <= 0) return null;
        Vec3 b = d.burst();
        List<Entity> found = level.getEntitiesOfClass(Entity.class, AABB.ofSize(b, reach * 2, reach * 2, reach * 2),
                e -> (e instanceof LivingEntity || e instanceof VehicleEntity) && e.isAlive() && !e.isSpectator() && e.getBbHeight() >= 0.3
                        && e.getBbWidth() >= 0.3 && e.distanceToSqr(b) <= reach * reach);
        if (found.isEmpty()) return null;
        found.sort(Comparator.comparingDouble(e -> e.distanceToSqr(b)));
        List<Body> bodies = new ArrayList<>(Math.min(found.size(), MAX_BODIES));
        for (Entity e : found) {
            if (bodies.size() >= MAX_BODIES) break;
            float yaw = e instanceof LivingEntity l ? l.yBodyRot : e.getYRot();
            double w = e.getBbWidth(), h = e.getBbHeight();
            Shape shape = e instanceof VehicleEntity ? Shape.BOX : h >= 1.4 && h >= 2.5 * w ? Shape.HUMAN : Shape.BLOB;
            bodies.add(new Body(e.getX(), e.getY(), e.getZ(), w, h, yaw, shape));
        }
        return new FlashShadows(level, d, bodies);
    }

    /** Докуда от эпицентра грань к шару ещё может потемнеть хоть на ступень (по прямой, блоки). */
    static double reach(Detonation d) {
        double lo = 0, hi = Math.max(1, d.ruinRadius());
        if (!FlashChar.possible(d.fluence(d.burst().add(1, 0, 0)), FlashChar.CHARS)) return 0;
        for (int i = 0; i < 40; i++) {
            double r = (lo + hi) / 2;
            if (FlashChar.possible(d.fluence(d.burst().add(r, 0, 0)), FlashChar.CHARS)) lo = r;
            else hi = r;
        }
        return lo;
    }

    /** Три точки на диске шара радиуса {@code r} поперёк направления {@code away} от шара к телу. */
    static Vec3[] sources(Vec3 burst, Vec3 away, double r) {
        Vec3 e1 = away.cross(Math.abs(away.y) > 0.95 ? new Vec3(1, 0, 0) : new Vec3(0, 1, 0)).normalize(), e2 = away.cross(e1).normalize();
        Vec3[] out = new Vec3[3];
        for (int k = 0; k < 3; k++) {
            double a = Math.PI / 2 + k * 2 * Math.PI / 3;
            out[k] = burst.add(e1.scale(r * Math.cos(a))).add(e2.scale(r * Math.sin(a)));
        }
        return out;
    }

    /** Сколько успеем за бюджет; true — все тела пройдены. */
    public boolean work(ServerLevel level, WorkClock clock) {
        while (next < bodies.size() && clock.canStart()) {
            long t0 = clock.begin();
            try {
                cast(level, bodies.get(next++));
            } finally {
                nanos += clock.end(t0);
            }
        }
        flush(level);
        if (next < bodies.size()) return false;
        Incoming.LOG.info("Nuclear detonation #{}: flash shadows — {} faces behind {} bodies, {} ms", d.id(), faces, bodies.size(), nanos / 1_000_000);
        return true;
    }

    /** Силуэты тела: грани за ним и маски их клеток. */
    private void cast(ServerLevel level, Body body) {
        Vec3 c = body.centre();
        double fluence = d.fluence(c);
        if (!FlashChar.possible(fluence, FlashChar.CHARS)) return;
        // само тело в тени — тени не бросает
        if (!ThermalShadow.visible(level, d.burst(), c) && !ThermalShadow.visible(level, d.burst(), c.add(0, body.height / 2, 0))) return;
        Vec3 away = c.subtract(d.burst()).normalize();
        double radius = PENUMBRA * d.fireballRadius();
        Vec3[] sources = sources(d.burst(), away, radius);
        // кандидаты — грани, куда ложатся лучи из точек тела прочь от шара, и их соседи в той же плоскости
        java.util.Set<FaceAt> candidates = new java.util.LinkedHashSet<>();
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                for (int k = 0; k < 3; k++) {
                    Vec3 from = new Vec3(body.x + (i - 1) * body.width * 0.45, body.y + body.height * (0.05 + 0.45 * j), body.z + (k - 1) * body.width * 0.45);
                    FaceAt hit = march(level, from, away);
                    if (hit == null) continue;
                    for (int a = -1; a <= 1; a++) {
                        for (int b = -1; b <= 1; b++) candidates.add(new FaceAt(inPlane(hit.pos, hit.face, a, b), hit.face));
                    }
                }
            }
        }
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (FaceAt f : candidates) {
            Direction face = f.face;
            BlockPos pos = f.pos;
            LevelChunk chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
            if (chunk == null) continue;
            BlockState st = chunk.getBlockState(pos);
            int material = FlashChar.material(st);
            if (material == FlashChar.NONE) continue;
            m.setWithOffset(pos, face);
            if (open(level, m) == null) continue;
            int level0 = level(level, pos, face, material);
            if (level0 == 0) continue;
            long[] mask = mask(body, pos, face, d.burst(), sources, radius);
            if (mask == null) continue;
            int mark = FlashChar.mark(FlashChar.key(pos.getX() & 15, pos.getY(), pos.getZ() & 15, face), level0, FlashChar.hash(st.getBlock()));
            long chunkKey = ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4);
            pendingFaces.computeIfAbsent(chunkKey, k -> new IntArrayList()).add(mark);
            LongArrayList masks = pendingMasks.computeIfAbsent(chunkKey, k -> new LongArrayList());
            for (long w : mask) masks.add(w);
            faces++;
        }
    }

    /** Грань блока. */
    record FaceAt(BlockPos pos, Direction face) {}

    /** Ступень гари грани — как у плана руин ({@code FlashFaces}), тень — по картам высот мира до волны. */
    private int level(ServerLevel level, BlockPos pos, Direction face, int material) {
        double cos = FlashChar.incidence(d.burst(), pos.getX(), pos.getY(), pos.getZ(), face);
        if (cos <= 0) return 0;
        Vec3 at = FlashChar.front(pos.getX(), pos.getY(), pos.getZ(), face);
        double q = d.fluence(at) * cos;
        if (!FlashChar.possible(q, material) || !ThermalShadow.visible(level, d.burst(), at)) return 0;
        double chance = FlashChar.saturated(q, material) ? 0 : FlashChar.chance(noise, pos.getX(), pos.getY(), pos.getZ(), face);
        return FlashChar.level(q, material, chance);
    }

    /**
     * Маска тени грани: по клетке 16×16 — сколько из трёх точек шара {@code sources} (на диске радиуса {@code radius}
     * вокруг {@code burst}) закрыто телом; null — тень на грань не падает. Оси клеток — {@link #axes}.
     */
    @Nullable
    static long[] mask(Body body, BlockPos pos, Direction face, Vec3 burst, Vec3[] sources, double radius) {
        long[] out = new long[FlashMarks.MASK_LONGS];
        boolean any = false;
        Vec3 c = body.centre();
        double reach = body.radius();
        int[] ax = axes(face);
        double[] p = new double[3];
        for (int v = 0; v < FlashMarks.CELLS; v++) {
            for (int u = 0; u < FlashMarks.CELLS; u++) {
                cell(pos, face, ax, u, v, p);
                // быстрый отсев: луч от клетки к центру шара проходит дальше от тела, чем тень с полутенью
                double tx = burst.x - p[0], ty = burst.y - p[1], tz = burst.z - p[2];
                double len = Math.sqrt(tx * tx + ty * ty + tz * tz);
                double cx = c.x - p[0], cy = c.y - p[1], cz = c.z - p[2];
                double along = (cx * tx + cy * ty + cz * tz) / len;
                if (along <= 0) continue;
                double off2 = cx * cx + cy * cy + cz * cz - along * along;
                double spread = reach + radius * along / len + 0.1;
                if (off2 > spread * spread) continue;
                int shade = 0;
                for (Vec3 s : sources) if (blocks(body, p, s)) shade++;
                if (shade == 0) continue;
                int bit = 2 * (v * FlashMarks.CELLS + u);
                out[bit >> 6] |= (long) shade << (bit & 63);
                any = true;
            }
        }
        return any ? out : null;
    }

    /**
     * Оси клеток грани в мире: {@code [u, v]} — номера осей (0 — x, 1 — y, 2 — z). Грани по x: u — z, v — y; по y:
     * u — x, v — z; по z: u — x, v — y. Клиент рисует маску по тем же осям.
     */
    public static int[] axes(Direction face) {
        return switch (face.getAxis()) {
            case X -> new int[]{2, 1};
            case Y -> new int[]{0, 2};
            case Z -> new int[]{0, 1};
        };
    }

    /** Центр клетки {@code (u, v)} грани чуть впереди неё, в мире. */
    private static void cell(BlockPos pos, Direction face, int[] ax, int u, int v, double[] out) {
        out[0] = pos.getX();
        out[1] = pos.getY();
        out[2] = pos.getZ();
        int n = face.getAxis().ordinal();
        out[n] += face.getAxisDirection() == Direction.AxisDirection.POSITIVE ? 1.02 : -0.02;
        out[ax[0]] += (u + 0.5) / FlashMarks.CELLS;
        out[ax[1]] += (v + 0.5) / FlashMarks.CELLS;
    }

    /** Закрывает ли тело отрезок от точки {@code p} до точки шара {@code s}. */
    static boolean blocks(Body body, double[] p, Vec3 s) {
        // в своей системе тела: начало — низ рамки, z — куда смотрит тело, x — вбок
        double yaw = Math.toRadians(body.yaw);
        double fx = -Math.sin(yaw), fz = Math.cos(yaw), rx = Math.cos(yaw), rz = Math.sin(yaw);
        double px = p[0] - body.x, py = p[1] - body.y, pz = p[2] - body.z;
        double dx = s.x - p[0], dy = s.y - p[1], dz = s.z - p[2];
        double ox = px * rx + pz * rz, oz = px * fx + pz * fz, oy = py;
        double vx = dx * rx + dz * rz, vz = dx * fx + dz * fz, vy = dy;
        return switch (body.shape) {
            case HUMAN -> human(body.height, ox, oy, oz, vx, vy, vz);
            case BLOB -> ellipsoid(body.width / 2, body.height / 2, ox, oy - body.height / 2, oz, vx, vy, vz);
            case BOX -> box(-body.width / 2, 0, -body.width / 2, body.width / 2, body.height, body.width / 2, ox, oy, oz, vx, vy, vz);
        };
    }

    /** Модель игрока (32 пикселя в высоту рамки): ноги, туловище, руки, голова. */
    private static final double[][] HUMAN = {
            {-4, 0, -2, 0, 12, 2}, {0, 0, -2, 4, 12, 2},
            {-4, 12, -2, 4, 24, 2},
            {-8, 12, -2, -4, 24, 2}, {4, 12, -2, 8, 24, 2},
            {-4, 24, -4, 4, 32, 4}};

    private static boolean human(double height, double ox, double oy, double oz, double vx, double vy, double vz) {
        double px = height / 32;
        for (double[] b : HUMAN) {
            if (box(b[0] * px, b[1] * px, b[2] * px, b[3] * px, b[4] * px, b[5] * px, ox, oy, oz, vx, vy, vz)) return true;
        }
        return false;
    }

    /** Пересекает ли отрезок {@code o + t·v}, {@code t ∈ (0, 1)}, прямоугольник. */
    private static boolean box(double x0, double y0, double z0, double x1, double y1, double z1, double ox, double oy, double oz,
                               double vx, double vy, double vz) {
        double lo = 1e-6, hi = 1;
        double[] o = {ox, oy, oz}, v = {vx, vy, vz}, a = {x0, y0, z0}, b = {x1, y1, z1};
        for (int i = 0; i < 3; i++) {
            if (Math.abs(v[i]) < 1e-12) {
                if (o[i] < a[i] || o[i] > b[i]) return false;
                continue;
            }
            double t0 = (a[i] - o[i]) / v[i], t1 = (b[i] - o[i]) / v[i];
            if (t0 > t1) {
                double t = t0;
                t0 = t1;
                t1 = t;
            }
            lo = Math.max(lo, t0);
            hi = Math.min(hi, t1);
            if (lo > hi) return false;
        }
        return true;
    }

    /** Пересекает ли отрезок эллипсоид с полуосями {@code (rh, rv, rh)} в начале координат. */
    private static boolean ellipsoid(double rh, double rv, double ox, double oy, double oz, double vx, double vy, double vz) {
        ox /= rh;
        oz /= rh;
        vx /= rh;
        vz /= rh;
        oy /= rv;
        vy /= rv;
        double a = vx * vx + vy * vy + vz * vz, b = 2 * (ox * vx + oy * vy + oz * vz), c = ox * ox + oy * oy + oz * oz - 1;
        double disc = b * b - 4 * a * c;
        if (disc < 0) return false;
        double sq = Math.sqrt(disc), t0 = (-b - sq) / (2 * a), t1 = (-b + sq) / (2 * a);
        return t1 > 1e-6 && t0 < 1;
    }

    /** Первая полная грань по лучу из {@code from} вдоль {@code dir} (шаги по блокам, только готовые чанки); null — нет. */
    @Nullable
    private static FaceAt march(ServerLevel level, Vec3 from, Vec3 dir) {
        int x = Mth.floor(from.x), y = Mth.floor(from.y), z = Mth.floor(from.z);
        int sx = dir.x > 0 ? 1 : -1, sy = dir.y > 0 ? 1 : -1, sz = dir.z > 0 ? 1 : -1;
        double tdx = Math.abs(1 / dir.x), tdy = Math.abs(1 / dir.y), tdz = Math.abs(1 / dir.z);
        double tx = (sx > 0 ? x + 1 - from.x : from.x - x) * tdx, ty = (sy > 0 ? y + 1 - from.y : from.y - y) * tdy,
                tz = (sz > 0 ? z + 1 - from.z : from.z - z) * tdz;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        Direction entered = null;
        // первый блок — само тело (воздух вокруг него)
        for (int step = 0; step < REACH * 3; step++) {
            if (entered != null) {
                if (y < level.getMinBuildHeight() || y >= level.getMaxBuildHeight()) return null;
                LevelChunk chunk = level.getChunkSource().getChunkNow(x >> 4, z >> 4);
                if (chunk == null) return null;
                BlockState st = chunk.getBlockState(m.set(x, y, z));
                if (full(st)) return new FaceAt(m.immutable(), entered);
            }
            double t;
            if (tx <= ty && tx <= tz) {
                t = tx;
                x += sx;
                tx += tdx;
                entered = sx > 0 ? Direction.WEST : Direction.EAST;
            } else if (ty <= tz) {
                t = ty;
                y += sy;
                ty += tdy;
                entered = sy > 0 ? Direction.DOWN : Direction.UP;
            } else {
                t = tz;
                z += sz;
                tz += tdz;
                entered = sz > 0 ? Direction.NORTH : Direction.SOUTH;
            }
            if (t > REACH) return null;
        }
        return null;
    }

    private static boolean full(BlockState st) {
        return !st.isAir() && st.getFluidState().isEmpty() && net.minecraft.world.level.block.Block.isShapeFullBlock(
                st.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO));
    }

    /** Место перед гранью открыто (не полный блок, чанк готов); null — закрыто или не готов. */
    @Nullable
    private static BlockState open(ServerLevel level, BlockPos front) {
        if (front.getY() < level.getMinBuildHeight() || front.getY() >= level.getMaxBuildHeight()) return net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
        LevelChunk chunk = level.getChunkSource().getChunkNow(front.getX() >> 4, front.getZ() >> 4);
        if (chunk == null) return null;
        BlockState st = chunk.getBlockState(front);
        return full(st) ? null : st;
    }

    /** Блок в плоскости грани со сдвигом {@code (a, b)} по её осям. */
    private static BlockPos inPlane(BlockPos at, Direction face, int a, int b) {
        int[] ax = axes(face);
        int[] off = new int[3];
        off[ax[0]] = a;
        off[ax[1]] = b;
        return at.offset(off[0], off[1], off[2]);
    }

    /** Силуэты, набранные за тик, — в отметки чанков (выгруженный за это время чанк их не получает). */
    private void flush(ServerLevel level) {
        for (var e : pendingFaces.long2ObjectEntrySet()) {
            long key = e.getLongKey();
            LevelChunk chunk = level.getChunkSource().getChunkNow(ChunkPos.getX(key), ChunkPos.getZ(key));
            if (chunk == null) continue;
            FlashSync.shadows(level, chunk, d, e.getValue().toIntArray(), pendingMasks.get(key).toLongArray());
        }
        pendingFaces.clear();
        pendingMasks.clear();
    }
}
