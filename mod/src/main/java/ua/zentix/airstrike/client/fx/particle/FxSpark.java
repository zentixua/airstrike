package ua.zentix.airstrike.client.fx.particle;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

/**
 * Искра, раскалённый осколок: светящийся штрих вдоль скорости (длина — путь за {@code streak} тиков), летит по
 * баллистике, гаснет от белого к тёмно-красному. С дымным хвостом — те самые «щупальца» дыма, которые
 * разлетаются от взрыва.
 */
public class FxSpark extends FxParticle {
    protected FxSpark(ClientLevel level, double x, double y, double z, Fx.Spec spec, SpriteSet sprites) {
        super(level, x, y, z, spec, sprites);
    }

    @Override
    public void tick() {
        super.tick();
        if (removed || spec.trail == null) return;
        // дымный хвост: клубы по пути за тик, пока осколок горячий
        float f = (float) age / lifetime;
        if (f > spec.trailUntil) return;
        double dx = x - xo, dy = y - yo, dz = z - zo;
        int n = Mth.clamp((int) (Math.sqrt(dx * dx + dy * dy + dz * dz) / spec.trailStep), 1, 6);
        for (int i = 0; i < n; i++) {
            double k = (i + random.nextDouble()) / n;
            spec.trail.copy().size(spec.trail.size0 * (1 - 0.6f * f), spec.trail.size1 * (1 - 0.5f * f))
                    .spawn(level, xo + dx * k, yo + dy * k, zo + dz * k);
        }
    }

    /** Штрих тянется назад на путь за {@code streak} тиков. */
    @Override
    public AABB getRenderBoundingBox(float partial) {
        return super.getRenderBoundingBox(partial).inflate(Math.sqrt(xd * xd + yd * yd + zd * zd) * spec.streak);
    }

    @Override
    public void render(VertexConsumer buffer, Camera camera, float partial) {
        update(partial);
        if (alpha <= 0.004f) return;
        Vec3 cam = camera.getPosition();
        float px = (float) (Mth.lerp(partial, xo, x) - cam.x);
        float py = (float) (Mth.lerp(partial, yo, y) - cam.y);
        float pz = (float) (Mth.lerp(partial, zo, z) - cam.z);
        Vector3f dir = new Vector3f((float) xd, (float) yd, (float) zd).mul(-spec.streak);
        float len = dir.length();
        float w = quadSize;
        if (len < w * 1.5f) {
            super.render(buffer, camera, partial);
            return;
        }
        Vector3f toCam = new Vector3f(px, py, pz).negate();
        Vector3f side = new Vector3f(dir).cross(toCam);
        if (side.lengthSquared() < 1e-8f) return;
        side.normalize().mul(w);
        // хвост уже головы: светящийся след
        Vector3f tail = new Vector3f(px, py, pz).add(dir);
        int light = getLightColor(partial);
        float u0 = getU0(), u1 = getU1(), v0 = getV0(), v1 = getV1(), um = (u0 + u1) / 2;
        buffer.addVertex(px - side.x, py - side.y, pz - side.z).setUv(u0, v1).setColor(rCol, gCol, bCol, alpha).setLight(light);
        buffer.addVertex(px + side.x, py + side.y, pz + side.z).setUv(u0, v0).setColor(rCol, gCol, bCol, alpha).setLight(light);
        buffer.addVertex(tail.x + side.x * 0.3f, tail.y + side.y * 0.3f, tail.z + side.z * 0.3f).setUv(um, v0).setColor(rCol, gCol * 0.7f, bCol * 0.5f, 0).setLight(light);
        buffer.addVertex(tail.x - side.x * 0.3f, tail.y - side.y * 0.3f, tail.z - side.z * 0.3f).setUv(um, v1).setColor(rCol, gCol * 0.7f, bCol * 0.5f, 0).setLight(light);
    }
}
