package ua.zentix.airstrike.client.fx.layer;

import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import net.minecraft.util.Mth;
import org.joml.Vector3f;
import org.lwjgl.system.MemoryUtil;

import java.util.Arrays;

/**
 * Квадраты кадра слоя эффектов: копятся в своей памяти (вне кучи Java, между кадрами не освобождается), потом
 * сортируются от дальних к ближним по настоящему расстоянию до камеры и уходят одним вызовом отрисовки. Прозрачное
 * смешивается верно только в таком порядке: ближний клуб поверх дальнего, а не «кто родился позже».
 * <p>
 * Вершина — 36 байт ({@link #FORMAT}): место от камеры, место на листе, свет с умноженной альфой, мягкость края
 * у рельефа и множитель переноса ближе ({@code UV1}), свет мира ({@code UV2}), туман Minecraft ({@code Normal.x}),
 * частица — ближайшим пикселем ({@code Normal.y}, {@link #nearestTexels}) и её непрозрачность без текстуры
 * ({@code Normal.z}, {@link #opacity}).
 */
public final class FxQuads {
    public static final VertexFormat FORMAT = VertexFormat.builder()
            .add("Position", VertexFormatElement.POSITION)
            .add("UV0", VertexFormatElement.UV0)
            .add("Color", VertexFormatElement.COLOR)
            .add("UV1", VertexFormatElement.UV1)
            .add("UV2", VertexFormatElement.UV2)
            .add("Normal", VertexFormatElement.NORMAL)
            .padding(1)
            .build();
    private static final int VERTEX = 36, QUAD = 4 * VERTEX;
    /** Мягкость края клуба у земли и стен — доля его полуразмера. */
    public static final float SOFT = 0.5f;

    private long stage;
    private int capacity, n;
    private long write;
    private float[] keys = new float[0];
    private final FarFirst sort = new FarFirst();
    private final ByteBufferBuilder out = new ByteBufferBuilder(QUAD * 256);
    private Vector3f left = new Vector3f(1, 0, 0), up = new Vector3f(0, 1, 0);
    private byte nearest, opacity;

    /** Новый кадр: оси экрана (влево, вверх) для квадратов лицом к камере. */
    public void begin(Vector3f left, Vector3f up) {
        this.left = left;
        this.up = up;
        n = 0;
        nearest = opacity = 0;
    }

    /**
     * Следующие квадраты — частицы: вблизи ближайшим пикселем картинки, как из атласа Minecraft (сглаженный клуб
     * мыльный), и без бледного края ({@link #opacity}); мельче вдвое — гладко. Дальнее — всегда гладко (ореол в сотню
     * пикселей из картинки 64×64 иначе лестницей).
     */
    public void nearestTexels(boolean on) {
        nearest = (byte) (on ? 127 : 0);
        opacity = 0;
    }

    /**
     * Непрозрачность следующих вершин частицы без текстуры (у искр и вспышек тоже, хоть они только светят): где вместе
     * с текстурой она меньше 0,1, частица вблизи не рисуется — как у ванильного шейдера частиц, иначе бледный ореол
     * каждого клуба в плотном облаке складывался в мутную дымку вокруг него. Вдали среза нет.
     */
    public void opacity(float a) {
        opacity = (byte) Math.round(Mth.clamp(a, 0, 1) * 127);
    }

    public int size() {
        return n;
    }

    /** Начать квадрат на расстоянии {@code key} от камеры; дальше — ровно четыре {@link #vertex}. */
    public void quad(float key) {
        if (n == capacity) grow();
        keys[n] = key > 0 ? key : 0;
        write = stage + (long) n * QUAD;
        n++;
    }

    /**
     * Вершина: место от камеры, место на листе, свет (r, g, b), уже умноженный на непрозрачность, сколько закрыто
     * за ней (a), мягкость края у рельефа (блоки, 0 — без мягкости), во сколько раз точка перенесена ближе (1 — нет),
     * свет мира (упакованный), туман Minecraft 0..1.
     */
    public void vertex(float x, float y, float z, float u, float v, float r, float g, float b, float a, float soft, float unfold, int light, float fog) {
        long p = write;
        MemoryUtil.memPutFloat(p, x);
        MemoryUtil.memPutFloat(p + 4, y);
        MemoryUtil.memPutFloat(p + 8, z);
        MemoryUtil.memPutFloat(p + 12, u);
        MemoryUtil.memPutFloat(p + 16, v);
        MemoryUtil.memPutByte(p + 20, unorm(r));
        MemoryUtil.memPutByte(p + 21, unorm(g));
        MemoryUtil.memPutByte(p + 22, unorm(b));
        MemoryUtil.memPutByte(p + 23, unorm(a));
        MemoryUtil.memPutShort(p + 24, (short) Math.min(Short.MAX_VALUE, Math.round(soft * 16)));
        MemoryUtil.memPutShort(p + 26, (short) Mth.clamp(Math.round(unfold * Short.MAX_VALUE), 1, Short.MAX_VALUE));
        MemoryUtil.memPutShort(p + 28, (short) (light & 0xFFFF));
        MemoryUtil.memPutShort(p + 30, (short) (light >>> 16 & 0xFFFF));
        MemoryUtil.memPutByte(p + 32, (byte) Math.round(Mth.clamp(fog, 0, 1) * 127));
        MemoryUtil.memPutByte(p + 33, nearest);
        MemoryUtil.memPutByte(p + 34, opacity);
        MemoryUtil.memPutByte(p + 35, (byte) 0);
        write = p + VERTEX;
    }

    /**
     * Квадрат лицом к камере с центром (x, y, z) от камеры, полуразмером half, повёрнутый на rot, на расстоянии key
     * (для сортировки), текстура — место на листе.
     */
    public void billboard(float x, float y, float z, float key, float half, float rot, FxAtlas.Sprite s, float r, float g, float b, float a,
                          float soft, float unfold, int light, float fog) {
        float c = Mth.cos(rot) * half, sn = Mth.sin(rot) * half;
        // оси квадрата в плоскости экрана, повёрнутые на rot
        float ax = left.x * c + up.x * sn, ay = left.y * c + up.y * sn, az = left.z * c + up.z * sn;
        float bx = -left.x * sn + up.x * c, by = -left.y * sn + up.y * c, bz = -left.z * sn + up.z * c;
        quad(key);
        vertex(x - ax - bx, y - ay - by, z - az - bz, s.u1(), s.v1(), r, g, b, a, soft, unfold, light, fog);
        vertex(x - ax + bx, y - ay + by, z - az + bz, s.u1(), s.v0(), r, g, b, a, soft, unfold, light, fog);
        vertex(x + ax + bx, y + ay + by, z + az + bz, s.u0(), s.v0(), r, g, b, a, soft, unfold, light, fog);
        vertex(x + ax - bx, y + ay - by, z + az - bz, s.u0(), s.v1(), r, g, b, a, soft, unfold, light, fog);
    }

    /** Отсортировать и нарисовать текущим шейдером; память кадра остаётся для следующего. */
    void draw() {
        if (n == 0) return;
        int[] order = sort.sort(keys, n);
        long dst = out.reserve(n * QUAD);
        for (int i = 0; i < n; i++) MemoryUtil.memCopy(stage + (long) order[i] * QUAD, dst + (long) i * QUAD, QUAD);
        ByteBufferBuilder.Result result = out.build();
        if (result == null) return;
        BufferUploader.drawWithShader(new MeshData(result, new MeshData.DrawState(FORMAT, n * 4, n * 6, VertexFormat.Mode.QUADS,
                VertexFormat.IndexType.least(n * 4))));
    }

    private void grow() {
        int cap = Math.max(1024, capacity * 2);
        long p = stage == 0 ? MemoryUtil.nmemAlloc((long) cap * QUAD) : MemoryUtil.nmemRealloc(stage, (long) cap * QUAD);
        if (p == 0) throw new OutOfMemoryError("Слой эффектов: нет памяти на " + cap + " квадратов");
        stage = p;
        capacity = cap;
        keys = Arrays.copyOf(keys, cap);
    }

    private static byte unorm(float f) {
        return (byte) (int) (Mth.clamp(f, 0, 1) * 255 + 0.5f);
    }

    /**
     * Порядок от дальних к ближним: поразрядная сортировка (три прохода по 11, 11 и 10 бит) по битам расстояния —
     * у неотрицательных чисел порядок битов и есть порядок значений, инверсия даёт убывание; равные — в порядке записи.
     * Массивы живут между кадрами.
     */
    static final class FarFirst {
        private static final int RADIX = 11, BUCKETS = 1 << RADIX;
        private final int[] counts = new int[BUCKETS];
        private int[] order = new int[0], spare = new int[0], bits = new int[0], spareBits = new int[0];

        /** Номера первых n ключей (неотрицательных) по убыванию; массив — до следующего вызова. */
        int[] sort(float[] keys, int n) {
            if (order.length < n) {
                int cap = Math.max(n, order.length * 2);
                order = new int[cap];
                spare = new int[cap];
                bits = new int[cap];
                spareBits = new int[cap];
            }
            int[] idx = order, idx2 = spare, k = bits, k2 = spareBits;
            for (int i = 0; i < n; i++) {
                idx[i] = i;
                k[i] = ~Float.floatToRawIntBits(keys[i]);
            }
            for (int shift = 0; shift < 32; shift += RADIX) {
                Arrays.fill(counts, 0);
                for (int i = 0; i < n; i++) counts[k[i] >>> shift & BUCKETS - 1]++;
                for (int i = 0, sum = 0; i < BUCKETS; i++) {
                    int c = counts[i];
                    counts[i] = sum;
                    sum += c;
                }
                for (int i = 0; i < n; i++) {
                    int to = counts[k[i] >>> shift & BUCKETS - 1]++;
                    idx2[to] = idx[i];
                    k2[to] = k[i];
                }
                int[] t = idx;
                idx = idx2;
                idx2 = t;
                t = k;
                k = k2;
                k2 = t;
            }
            order = idx;
            spare = idx2;
            bits = k;
            spareBits = k2;
            return idx;
        }
    }
}
