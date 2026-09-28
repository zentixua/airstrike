package ua.zentix.airstrike.client.fx.particle;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Кольцо ударной волны: плоское, лежит на земле, разбегается с замедлением от size0 до size1 и тает. */
public class FxRing extends FxParticle {
    protected FxRing(ClientLevel level, double x, double y, double z, Fx.Spec spec, SpriteSet sprites) {
        super(level, x, y, z, spec, sprites);
    }

    @Override
    public void render(VertexConsumer buffer, Camera camera, float partial) {
        update(partial);
        if (alpha <= 0.004f) return;
        Vec3 cam = camera.getPosition();
        float px = (float) (Mth.lerp(partial, xo, x) - cam.x);
        float py = (float) (Mth.lerp(partial, yo, y) - cam.y);
        float pz = (float) (Mth.lerp(partial, zo, z) - cam.z);
        float r = quadSize;
        int light = getLightColor(partial);
        float u0 = getU0(), u1 = getU1(), v0 = getV0(), v1 = getV1();
        // обе стороны: видно и сверху, и снизу
        vertex(buffer, px - r, py, pz - r, u0, v0, light);
        vertex(buffer, px - r, py, pz + r, u0, v1, light);
        vertex(buffer, px + r, py, pz + r, u1, v1, light);
        vertex(buffer, px + r, py, pz - r, u1, v0, light);
        vertex(buffer, px + r, py, pz - r, u1, v0, light);
        vertex(buffer, px + r, py, pz + r, u1, v1, light);
        vertex(buffer, px - r, py, pz + r, u0, v1, light);
        vertex(buffer, px - r, py, pz - r, u0, v0, light);
    }

    private void vertex(VertexConsumer buffer, float x, float y, float z, float u, float v, int light) {
        buffer.addVertex(x, y, z).setUv(u, v).setColor(rCol, gCol, bCol, alpha).setLight(light);
    }

    @Override
    public AABB getRenderBoundingBox(float partialTicks) {
        return new AABB(x - spec.size1, y - 1, z - spec.size1, x + spec.size1, y + 1, z + spec.size1);
    }
}
