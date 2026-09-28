package ua.zentix.airstrike.client.fx.particle;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.client.particle.TextureSheetParticle;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.util.Mth;

/**
 * Частица эффектов по {@link Fx.Spec}: растёт с замедлением, тормозится воздухом, всплывает (горячее) или оседает,
 * сносится ветром, крутится; цвет и прозрачность меняются за жизнь, накал (свечение огня изнутри) гаснет.
 * Дым с накалом светится сам и подсвечен оранжевым — так огненный шар «остывает» в чёрный дым.
 */
public class FxParticle extends TextureSheetParticle {
    protected final Fx.Spec spec;
    private final SpriteSet sprites;
    /** Номер клуба в наборе (у дыма — 4 клуба × 4 стадии рассеивания). */
    private final int variant;
    private float spin;

    protected FxParticle(ClientLevel level, double x, double y, double z, Fx.Spec spec, SpriteSet sprites) {
        super(level, x, y, z);
        this.spec = spec;
        this.sprites = sprites;
        this.xd = spec.vx;
        this.yd = spec.vy;
        this.zd = spec.vz;
        this.lifetime = Math.max(1, spec.life);
        this.hasPhysics = spec.collide;
        this.friction = spec.drag;
        this.gravity = 0;
        this.variant = random.nextInt(spec.kind == Fx.Kind.SMOKE ? 4 : 8);
        this.roll = this.oRoll = random.nextFloat() * Mth.TWO_PI;
        this.spin = spec.spin * (random.nextFloat() - 0.5f) * 2;
        this.quadSize = spec.size0;
        pickSprite(0);
        update(0);
    }

    private void pickSprite(float f) {
        switch (spec.kind) {
            case SMOKE -> {
                // стадия рассеивания: клуб «тает» во второй половине жизни
                int stage = Mth.clamp((int) ((f - 0.35f) / 0.65f * 4), 0, 3);
                setSprite(sprites.get(variant * 4 + stage, 15));
            }
            case FIRE -> setSprite(sprites.get(variant, 7));
            default -> setSprite(sprites.get(0, 1));
        }
    }

    /** Размер, цвет и прозрачность в момент {@code age + partial}. */
    protected void update(float partial) {
        float f = Mth.clamp((age + partial) / lifetime, 0, 1);
        float grow = 1 - (1 - f) * (1 - f) * (1 - f) * (spec.growFast ? (1 - f) : 1);
        quadSize = spec.size0 + (spec.size1 - spec.size0) * grow;
        float cf = (float) Math.pow(f, spec.colorCurve);
        float r = Mth.lerp(cf, spec.r0, spec.r1), g = Mth.lerp(cf, spec.g0, spec.g1), b = Mth.lerp(cf, spec.b0, spec.b1);
        float heat = heat(partial);
        if (heat > 0 && spec.kind.lit) {
            // подсветка огнём изнутри
            r = Math.min(1, r + heat * 1.0f);
            g = Math.min(1, g + heat * 0.45f);
            b = Math.min(1, b + heat * 0.12f);
        }
        rCol = r;
        gCol = g;
        bCol = b;
        float fadeIn = spec.fadeIn <= 0 ? 1 : Mth.clamp((age + partial) / spec.fadeIn, 0, 1);
        float fadeOut = f < spec.fadeFrom ? 1 : 1 - (f - spec.fadeFrom) / (1 - spec.fadeFrom);
        alpha = spec.alpha * fadeIn * Math.max(0, fadeOut);
    }

    /** Накал 0..1: гаснет экспоненциально за {@code glowTicks}. */
    protected float heat(float partial) {
        if (spec.glow <= 0) return 0;
        return spec.glow * (float) Math.exp(-(age + partial) / Math.max(1f, spec.glowTicks));
    }

    @Override
    public void tick() {
        xo = x;
        yo = y;
        zo = z;
        oRoll = roll;
        if (age++ >= lifetime) {
            remove();
            return;
        }
        // горячее всплывает быстро, остывшее — едва
        yd += spec.rise * (0.15 + 0.85 * Math.exp(-age / 40.0)) - spec.gravity;
        xd += Fx.WIND_X * spec.wind;
        zd += Fx.WIND_Z * spec.wind;
        move(xd, yd, zd);
        xd *= friction;
        yd *= friction;
        zd *= friction;
        roll += spin;
        spin *= 0.985f;
        if (spec.kind == Fx.Kind.SMOKE) pickSprite((float) age / lifetime);
        if (onGround && spec.collide) {
            xd *= 0.7;
            zd *= 0.7;
        }
    }

    @Override
    public void render(VertexConsumer buffer, Camera camera, float partial) {
        update(partial);
        if (alpha <= 0.004f) return;
        super.render(buffer, camera, partial);
    }

    @Override
    public float getQuadSize(float partial) {
        return quadSize;
    }

    @Override
    protected int getLightColor(float partial) {
        if (!spec.kind.lit) return LightTexture.FULL_BRIGHT;
        int world = super.getLightColor(partial);
        int block = Math.max(LightTexture.block(world), (int) (heat(partial) * 15));
        return LightTexture.pack(block, LightTexture.sky(world));
    }

    @Override
    public ParticleRenderType getRenderType() {
        return spec.kind.additive ? FxRenderTypes.GLOW : FxRenderTypes.BLEND;
    }
}
