package ua.zentix.airstrike.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import ua.zentix.airstrike.Airstrike;
import ua.zentix.airstrike.client.fx.Exhaust;

/**
 * Факел двигателя: полоса вдоль оси сопла, развёрнутая к камере (с любой стороны — струя, а не плоская
 * картинка), в два слоя — широкий оранжевый и узкое белое ядро, — и ореол у среза сопла. Рисуется сложением,
 * как «глаза» паука: светится и ночью, под шейдерами Iris проходит как излучающая текстура.
 */
public final class PlumeRenderer {
    private static final ResourceLocation PLUME = Airstrike.id("textures/fx/plume.png");
    private static final ResourceLocation DIAMONDS = Airstrike.id("textures/fx/plume_diamonds.png");
    private static final ResourceLocation HALO = Airstrike.id("textures/fx/halo.png");

    private PlumeRenderer() {}

    /**
     * @param pose     система снаряда (нос по +Z), уже повёрнутая
     * @param rotation поворот, которым её повернули (чтобы перевести камеру в систему снаряда)
     * @param origin   положение снаряда в мире в этот кадр
     */
    public static void render(Exhaust.Plume p, PoseStack pose, MultiBufferSource buffers, Quaternionf rotation, Vec3 origin, Camera camera) {
        Quaternionf inv = new Quaternionf(rotation).conjugate();
        Vec3 c = camera.getPosition().subtract(origin);
        Vector3f cam = new Vector3f((float) c.x, (float) c.y, (float) c.z).rotate(inv).sub(0, p.y(), 0);
        pose.pushPose();
        pose.translate(0, p.y(), 0);

        VertexConsumer vc = buffers.getBuffer(RenderType.eyes(p.diamonds() ? DIAMONDS : PLUME));
        strip(vc, pose, cam, p.z(), p.length(), p.radius() * 2.6f, p.outer(), p.intensity() * 0.75f);
        strip(vc, pose, cam, p.z(), p.length() * 0.6f, p.radius(), p.core(), p.intensity());

        // в копии: векторы камеры — её живые поля, rotate без dest поворачивал их самих до следующего кадра, и всё, что
        // после снарядов ставит квадраты лицом к камере (дым слоя эффектов, дальнее), ложилось боком плоскими блинами
        Vector3f left = camera.getLeftVector().rotate(inv, new Vector3f()), up = camera.getUpVector().rotate(inv, new Vector3f());
        VertexConsumer halo = buffers.getBuffer(RenderType.eyes(HALO));
        float s = p.radius() * 4.5f;
        billboard(halo, pose, new Vector3f(0, 0, p.z() - p.radius() * 0.5f), left, up, s, p.outer(), p.intensity() * 0.8f);
        billboard(halo, pose, new Vector3f(0, 0, p.z() - p.radius() * 0.3f), left, up, s * 0.45f, p.core(), p.intensity());
        pose.popPose();
    }

    /** Полоса от среза сопла назад на {@code length}, повёрнутая вокруг оси Z лицом к камере. */
    private static void strip(VertexConsumer vc, PoseStack pose, Vector3f cam, float z0, float length, float halfWidth, int rgb, float k) {
        Vector3f mid = new Vector3f(0, 0, z0 - length / 2);
        Vector3f toCam = new Vector3f(cam).sub(mid);
        Vector3f side = new Vector3f(0, 0, -1).cross(toCam);
        if (side.lengthSquared() < 1e-6f) side.set(1, 0, 0);
        side.normalize().mul(halfWidth);
        float z1 = z0 - length;
        // v = 0 — у сопла, 1 — хвост; обе стороны (задние грани отсекаются)
        vertex(vc, pose, -side.x, -side.y, z0, 0, 0, rgb, k);
        vertex(vc, pose, side.x, side.y, z0, 1, 0, rgb, k);
        vertex(vc, pose, side.x, side.y, z1, 1, 1, rgb, k);
        vertex(vc, pose, -side.x, -side.y, z1, 0, 1, rgb, k);
        vertex(vc, pose, -side.x, -side.y, z1, 0, 1, rgb, k);
        vertex(vc, pose, side.x, side.y, z1, 1, 1, rgb, k);
        vertex(vc, pose, side.x, side.y, z0, 1, 0, rgb, k);
        vertex(vc, pose, -side.x, -side.y, z0, 0, 0, rgb, k);
    }

    private static void billboard(VertexConsumer vc, PoseStack pose, Vector3f at, Vector3f left, Vector3f up, float s, int rgb, float k) {
        float lx = left.x * s, ly = left.y * s, lz = left.z * s, ux = up.x * s, uy = up.y * s, uz = up.z * s;
        vertex(vc, pose, at.x - lx - ux, at.y - ly - uy, at.z - lz - uz, 0, 1, rgb, k);
        vertex(vc, pose, at.x - lx + ux, at.y - ly + uy, at.z - lz + uz, 0, 0, rgb, k);
        vertex(vc, pose, at.x + lx + ux, at.y + ly + uy, at.z + lz + uz, 1, 0, rgb, k);
        vertex(vc, pose, at.x + lx - ux, at.y + ly - uy, at.z + lz - uz, 1, 1, rgb, k);
    }

    /** Цвет × яркость: при сложении прозрачность не участвует, яркость — в самом цвете. */
    private static void vertex(VertexConsumer vc, PoseStack pose, float x, float y, float z, float u, float v, int rgb, float k) {
        float r = (rgb >> 16 & 0xFF) / 255f * k, g = (rgb >> 8 & 0xFF) / 255f * k, b = (rgb & 0xFF) / 255f * k;
        vc.addVertex(pose.last(), x, y, z).setColor(r, g, b, 1).setUv(u, v).setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(LightTexture.FULL_BRIGHT).setNormal(pose.last(), 0, 1, 0);
    }
}
