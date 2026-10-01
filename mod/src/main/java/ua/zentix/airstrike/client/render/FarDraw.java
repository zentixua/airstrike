package ua.zentix.airstrike.client.render;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.MeshData;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4fStack;
import org.joml.Vector3f;

/**
 * Общее для того, что рисуется после мира ({@code AFTER_LEVEL}) на любой дальности: ядерный удар и снаряды и взрывы
 * вдали. Под шейдерами Iris виден только этот этап (всё, что нарисовано раньше, шейдерпак пропускает через свои
 * проходы, и его туман съедает дальнее). Глубина проверяется, но не пишется: рельеф ближе закрывает, небо — нет.
 * Дальше дальней плоскости отсечения точка переносится ближе по тому же лучу и уменьшается во столько же раз —
 * угловой размер тот же ({@link #fold}).
 */
public final class FarDraw {
    private FarDraw() {}

    /** Начать: своя матрица вида (камера в начале координат), состояние — как оставил мир. */
    public static void begin(RenderLevelStageEvent e) {
        Matrix4fStack mv = RenderSystem.getModelViewStack();
        mv.pushMatrix();
        mv.identity();
        mv.mul(e.getModelViewMatrix());
        RenderSystem.applyModelViewMatrix();
    }

    /** Вернуть матрицу и состояние, которые ждёт остальная отрисовка. */
    public static void end() {
        RenderSystem.getModelViewStack().popMatrix();
        RenderSystem.applyModelViewMatrix();
        RenderSystem.depthMask(true);
        RenderSystem.enableDepthTest();
        RenderSystem.enableCull();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableBlend();
        RenderSystem.setShaderColor(1, 1, 1, 1);
    }

    /** Дальше {@code far} — во сколько раз приблизить точку (и уменьшить её размер), иначе 1. */
    public static double fold(double dist, double far) {
        return dist > far ? far / dist : 1;
    }

    /** Своя текстура и смешивание. */
    public static void setup(ResourceLocation texture, boolean additive) {
        RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
        RenderSystem.setShaderTexture(0, texture);
        blend(additive);
    }

    /** Обычное смешивание или сложение света; глубина проверяется, но не пишется (прозрачное поверх мира). */
    public static void blend(boolean additive) {
        RenderSystem.enableBlend();
        if (additive) {
            RenderSystem.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE);
        } else {
            RenderSystem.defaultBlendFunc();
        }
        RenderSystem.depthMask(false);
        RenderSystem.enableDepthTest();
        RenderSystem.disableCull();
    }

    /** Квадрат лицом к камере с центром (x, y, z) относительно камеры, полуразмером half, повёрнутый на rot. */
    public static void billboard(BufferBuilder b, Vector3f left, Vector3f up, float x, float y, float z, float half, float rot,
                                 float u0, float v0, float u1, float v1, float r, float g, float bl, float a) {
        float c = Mth.cos(rot) * half, s = Mth.sin(rot) * half;
        // оси квадрата в плоскости экрана, повёрнутые на rot
        float ax = left.x() * c + up.x() * s, ay = left.y() * c + up.y() * s, az = left.z() * c + up.z() * s;
        float bx = -left.x() * s + up.x() * c, by = -left.y() * s + up.y() * c, bz = -left.z() * s + up.z() * c;
        b.addVertex(x - ax - bx, y - ay - by, z - az - bz).setUv(u1, v1).setColor(r, g, bl, a);
        b.addVertex(x - ax + bx, y - ay + by, z - az + bz).setUv(u1, v0).setColor(r, g, bl, a);
        b.addVertex(x + ax + bx, y + ay + by, z + az + bz).setUv(u0, v0).setColor(r, g, bl, a);
        b.addVertex(x + ax - bx, y + ay - by, z + az - bz).setUv(u0, v1).setColor(r, g, bl, a);
    }

    public static void draw(BufferBuilder b) {
        MeshData mesh = b.build();
        if (mesh != null) BufferUploader.drawWithShader(mesh);
    }
}
