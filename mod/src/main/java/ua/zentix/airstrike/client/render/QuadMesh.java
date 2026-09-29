package ua.zentix.airstrike.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.core.Vec3i;
import net.minecraft.util.FastColor;
import net.neoforged.neoforge.client.model.IQuadTransformer;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;

/**
 * Сетка из граней запечённых моделей, разобранная один раз: позиция, UV и нормаль каждой вершины — числами,
 * запечённый свет (материал {@code glow} в OBJ) — отдельно. Кадр пишет вершины одним вызовом
 * {@link VertexConsumer#addVertex(float, float, float, int, float, float, int, int, float, float, float)} на вершину,
 * как ванильный {@code ModelPart}, а не разбирает {@link BakedQuad} заново ({@code putBulkData} копирует каждую
 * вершину через {@code MemoryStack} и заводит на неё векторы). Путь прежний — буфер слоя сущностей, поэтому свет мира,
 * контур свечения, Iris и его проход теней работают как у любой сущности.
 */
final class QuadMesh {
    /** x y z, u v, нормаль x y z. */
    private static final int STRIDE = 8;

    private final float[] vertices;
    /** Запечённый свет вершин (упакован как свет мира) или {@code null}, если его нет ни у одной. */
    private final int[] bakedLight;

    private QuadMesh(float[] vertices, int[] bakedLight) {
        this.vertices = vertices;
        this.bakedLight = bakedLight;
    }

    /**
     * Нарисовать в текущей системе координат.
     *
     * @param color ARGB: множитель текстуры, альфа — прозрачность детали
     */
    void draw(PoseStack.Pose pose, VertexConsumer vc, int color, int light, int overlay) {
        Matrix4f m = pose.pose();
        Vector3f p = new Vector3f(), n = new Vector3f();
        float[] v = vertices;
        for (int i = 0, k = 0; i < v.length; i += STRIDE, k++) {
            m.transformPosition(v[i], v[i + 1], v[i + 2], p);
            pose.transformNormal(v[i + 5], v[i + 6], v[i + 7], n);
            int l = bakedLight == null ? light : brighter(light, bakedLight[k]);
            vc.addVertex(p.x, p.y, p.z, color, v[i + 3], v[i + 4], overlay, l, n.x, n.y, n.z);
        }
    }

    static int white(float alpha) {
        return FastColor.ARGB32.colorFromFloat(alpha, 1, 1, 1);
    }

    /** Свет вершины — ярче из света мира и запечённого, по блочному и небесному отдельно (как NeoForge в putBulkData). */
    private static int brighter(int a, int b) {
        return Math.max(a & 0xFFFF, b & 0xFFFF) | Math.max(a >>> 16, b >>> 16) << 16;
    }

    static Builder builder() {
        return new Builder();
    }

    static final class Builder {
        private float[] vertices = new float[STRIDE * 256];
        private int[] light = new int[256];
        private int count;
        private boolean anyLight;

        /** Грани, сдвинутые и растянутые {@code transform} (нормали — обратной транспонированной, заново единичные). */
        Builder add(List<BakedQuad> quads, Matrix4f transform) {
            Matrix3f normals = transform.normal(new Matrix3f());
            Vector3f p = new Vector3f(), n = new Vector3f();
            for (BakedQuad q : quads) {
                int[] d = q.getVertices();
                Vec3i face = q.getDirection().getNormal();
                for (int o = 0; o < d.length; o += IQuadTransformer.STRIDE) {
                    transform.transformPosition(Float.intBitsToFloat(d[o + IQuadTransformer.POSITION]),
                            Float.intBitsToFloat(d[o + IQuadTransformer.POSITION + 1]),
                            Float.intBitsToFloat(d[o + IQuadTransformer.POSITION + 2]), p);
                    // нормаль вершины (гладкие сетки OBJ), а если её нет — нормаль грани, как в putBulkData
                    int packed = d[o + IQuadTransformer.NORMAL];
                    byte nx = (byte) packed, ny = (byte) (packed >> 8), nz = (byte) (packed >> 16);
                    if (nx != 0 || ny != 0 || nz != 0) n.set(nx / 127f, ny / 127f, nz / 127f);
                    else n.set(face.getX(), face.getY(), face.getZ());
                    normals.transform(n).normalize();
                    int baked = d[o + IQuadTransformer.UV2];
                    vertex(p, Float.intBitsToFloat(d[o + IQuadTransformer.UV0]), Float.intBitsToFloat(d[o + IQuadTransformer.UV0 + 1]), n, baked);
                }
            }
            return this;
        }

        private void vertex(Vector3f p, float u, float v, Vector3f n, int baked) {
            if ((count + 1) * STRIDE > vertices.length) {
                vertices = Arrays.copyOf(vertices, vertices.length * 2);
                light = Arrays.copyOf(light, light.length * 2);
            }
            int i = count * STRIDE;
            vertices[i] = p.x;
            vertices[i + 1] = p.y;
            vertices[i + 2] = p.z;
            vertices[i + 3] = u;
            vertices[i + 4] = v;
            vertices[i + 5] = n.x;
            vertices[i + 6] = n.y;
            vertices[i + 7] = n.z;
            light[count++] = baked;
            anyLight |= baked != 0;
        }

        QuadMesh build() {
            return new QuadMesh(Arrays.copyOf(vertices, count * STRIDE), anyLight ? Arrays.copyOf(light, count) : null);
        }
    }

    /**
     * Сетка, собранная при первом рисовании и заново после каждой перезагрузки моделей (F3+T, пакет ресурсов):
     * грани берутся из менеджера моделей, а он готов только после загрузки ресурсов.
     */
    static final class Cached {
        private static int generation;

        private final Supplier<QuadMesh> build;
        private QuadMesh mesh;
        private int builtAt = -1;

        Cached(Supplier<QuadMesh> build) {
            this.build = build;
        }

        QuadMesh get() {
            if (builtAt != generation) {
                mesh = build.get();
                builtAt = generation;
            }
            return mesh;
        }

        /** Модели перезапечены: все сетки соберутся заново при следующем рисовании. */
        static void invalidateAll() {
            generation++;
        }
    }
}
