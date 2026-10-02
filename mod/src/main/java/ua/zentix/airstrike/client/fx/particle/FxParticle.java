package ua.zentix.airstrike.client.fx.particle;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import ua.zentix.airstrike.client.fx.layer.FxAtlas;
import ua.zentix.airstrike.client.fx.layer.FxFrame;
import ua.zentix.airstrike.client.fx.layer.FxQuads;

import java.util.List;

/**
 * Частица эффектов по {@link Fx.Spec}: растёт с замедлением, тормозится воздухом, всплывает (горячее) или оседает,
 * сносится ветром, крутится; цвет и прозрачность меняются за жизнь, накал (свечение огня изнутри) гаснет.
 * Дым с накалом светится сам и подсвечен оранжевым — так огненный шар «остывает» в чёрный дым. Искра — светящийся
 * штрих вдоль скорости, может оставлять дымный хвост; кольцо ударной волны лежит на земле.
 * <p>
 * Объект живёт в пуле ({@link FxPool}) и переиспользуется: {@link #init} копирует настройку (снимок — шаблон можно
 * менять и спаунить дальше), состояние полёта — свои поля. Движется и сталкивается как ванильная частица
 * ({@code Particle.move}: рамка 0,2 блока, упёршись по вертикали — останавливается).
 */
public final class FxParticle {
    private static final double MAX_COLLISION_SPEED_SQR = 100 * 100;
    private static final double HALF = 0.1, HEIGHT = 0.2;

    // ---------------------------------------------------------------- настройка (снимок Spec)
    Fx.Kind kind;
    FxBudget budget;
    private float size0, size1;
    private boolean growFast;
    private int life;
    private float r0, g0, b0, r1, g1, b1, colorCurve, alpha;
    private int fadeIn;
    private float fadeFrom, glow, glowTicks, drag, rise, gravity, wind;
    private boolean collide;
    private float streak;
    /** Шаблон хвоста искры: общий у всех искр залпа, после пуска не меняется. */
    private Fx.Spec trail;
    private float trailStep, trailUntil;

    // ---------------------------------------------------------------- состояние
    double x, y, z, xo, yo, zo;
    private double xd, yd, zd;
    private int age;
    private float roll, oRoll, spin;
    /** Номер клуба в наборе (у дыма — 4 клуба × 4 стадии рассеивания). */
    private int variant;
    private boolean onGround, stopped;
    /** Свет мира в месте частицы (упакованный, как у {@link LevelRenderer#getLightColor}) — раз в тик. */
    private int worldLight;
    boolean dead;

    // ---------------------------------------------------------------- вид в кадре
    private float quadSize, rCol, gCol, bCol, aCol;

    void init(Fx.Spec s, double px, double py, double pz, float sizeScale0, float sizeScale1, RandomSource random, ClientLevel level) {
        kind = s.kind;
        budget = s.budget;
        size0 = s.size0 * sizeScale0;
        size1 = s.size1 * sizeScale1;
        growFast = s.growFast;
        life = Math.max(1, s.life);
        r0 = s.r0;
        g0 = s.g0;
        b0 = s.b0;
        r1 = s.r1;
        g1 = s.g1;
        b1 = s.b1;
        colorCurve = s.colorCurve;
        alpha = s.alpha;
        fadeIn = s.fadeIn;
        fadeFrom = s.fadeFrom;
        glow = s.glow;
        glowTicks = s.glowTicks;
        drag = s.drag;
        rise = s.rise;
        gravity = s.gravity;
        wind = s.wind;
        collide = s.collide;
        streak = s.streak;
        trail = s.trail;
        trailStep = s.trailStep;
        trailUntil = s.trailUntil;
        x = xo = px;
        y = yo = py;
        z = zo = pz;
        xd = s.vx;
        yd = s.vy;
        zd = s.vz;
        age = 0;
        variant = random.nextInt(kind == Fx.Kind.SMOKE ? 4 : 8);
        roll = oRoll = random.nextFloat() * Mth.TWO_PI;
        spin = s.spin * (random.nextFloat() - 0.5f) * 2;
        onGround = stopped = dead = false;
        worldLight = light(level);
        quadSize = size0;
    }

    /** Тик: возраст, всплытие, ветер, движение со столкновениями; у искры — хвост. */
    void tick(ClientLevel level, FxPool pool) {
        xo = x;
        yo = y;
        zo = z;
        oRoll = roll;
        if (age++ >= life) {
            dead = true;
            return;
        }
        // горячее всплывает быстро, остывшее — едва
        yd += rise * (0.15 + 0.85 * Math.exp(-age / 40.0)) - gravity;
        xd += Fx.WIND_X * wind;
        zd += Fx.WIND_Z * wind;
        move(level, xd, yd, zd);
        xd *= drag;
        yd *= drag;
        zd *= drag;
        roll += spin;
        spin *= 0.985f;
        if (onGround && collide) {
            xd *= 0.7;
            zd *= 0.7;
        }
        worldLight = light(level);
        if (kind == Fx.Kind.SPARK && trail != null) tail(level, pool);
    }

    /** Дымный хвост искры: клубы по пути за тик, пока осколок горячий. */
    private void tail(ClientLevel level, FxPool pool) {
        float f = (float) age / life;
        if (f > trailUntil) return;
        double dx = x - xo, dy = y - yo, dz = z - zo;
        int n = Mth.clamp((int) (Math.sqrt(dx * dx + dy * dy + dz * dz) / trailStep), 1, 6);
        for (int i = 0; i < n; i++) {
            double k = (i + pool.random.nextDouble()) / n;
            pool.spawn(trail, level, xo + dx * k, yo + dy * k, zo + dz * k, 1 - 0.6f * f, 1 - 0.5f * f);
        }
    }

    /** Как {@code Particle.move}: рамка 0,2 блока, столкновения с блоками; упёрлась по вертикали — больше не движется. */
    private void move(ClientLevel level, double dx, double dy, double dz) {
        if (stopped) return;
        double ix = dx, iy = dy, iz = dz;
        if (collide && (dx != 0 || dy != 0 || dz != 0) && dx * dx + dy * dy + dz * dz < MAX_COLLISION_SPEED_SQR) {
            Vec3 v = Entity.collideBoundingBox(null, new Vec3(dx, dy, dz), new AABB(x - HALF, y, z - HALF, x + HALF, y + HEIGHT, z + HALF), level,
                    List.of());
            dx = v.x;
            dy = v.y;
            dz = v.z;
        }
        x += dx;
        y += dy;
        z += dz;
        if (Math.abs(iy) >= 1.0E-5F && Math.abs(dy) < 1.0E-5F) stopped = true;
        onGround = iy != dy && iy < 0;
        if (ix != dx) xd = 0;
        if (iz != dz) zd = 0;
    }

    private int light(ClientLevel level) {
        if (!kind.lit) return LightTexture.FULL_BRIGHT;
        BlockPos pos = BlockPos.containing(x, y, z);
        // чанка у клиента нет (дальше прорисовки: дым залпа, от которого зритель ушёл) — открытое небо, как у ленты
        // шлейфа ({@code FarFlightView}); у ванильной частицы там темно, и клубы на светлой ленте выходили чёрными бусинами
        return level.getChunkSource().hasChunk(SectionPos.blockToSectionCoord(pos.getX()), SectionPos.blockToSectionCoord(pos.getZ()))
                ? LevelRenderer.getLightColor(level, pos) : LightTexture.FULL_SKY;
    }

    /** Накал 0..1: гаснет экспоненциально за {@code glowTicks}. */
    private float heat(float partial) {
        if (glow <= 0) return 0;
        return glow * (float) Math.exp(-(age + partial) / Math.max(1f, glowTicks));
    }

    /** Размер, цвет и прозрачность в момент {@code age + partial}. */
    private void update(float partial) {
        float f = Mth.clamp((age + partial) / life, 0, 1);
        float grow = 1 - (1 - f) * (1 - f) * (1 - f) * (growFast ? (1 - f) : 1);
        quadSize = size0 + (size1 - size0) * grow;
        float cf = (float) Math.pow(f, colorCurve);
        float r = Mth.lerp(cf, r0, r1), g = Mth.lerp(cf, g0, g1), b = Mth.lerp(cf, b0, b1);
        float heat = heat(partial);
        if (heat > 0 && kind.lit) {
            // подсветка огнём изнутри
            r = Math.min(1, r + heat * 1.0f);
            g = Math.min(1, g + heat * 0.45f);
            b = Math.min(1, b + heat * 0.12f);
        }
        rCol = r;
        gCol = g;
        bCol = b;
        float in = fadeIn <= 0 ? 1 : Mth.clamp((age + partial) / fadeIn, 0, 1);
        float out = f < fadeFrom ? 1 : 1 - (f - fadeFrom) / (1 - fadeFrom);
        aCol = alpha * in * Math.max(0, out);
    }

    /** Свет для карты освещения: мир, у дыма с накалом — не темнее свечения огня. */
    private int lightColor(float partial) {
        if (!kind.lit) return LightTexture.FULL_BRIGHT;
        int block = Math.max(LightTexture.block(worldLight), (int) (heat(partial) * 15));
        return LightTexture.pack(block, LightTexture.sky(worldLight));
    }

    /** Наибольший радиус частицы (для отсечения по кадру): квадрат целиком, у искры — со штрихом. */
    double reach() {
        double v = Math.sqrt(xd * xd + yd * yd + zd * zd);
        return Math.max(size0, size1) + v * (1 + streak);
    }

    /** Свой квадрат в кадр (координаты — от камеры); прозрачный — не пишется. */
    void emit(FxFrame frame, FxQuads out, float partial) {
        update(partial);
        if (aCol <= 0.004f) return;
        double px = Mth.lerp(partial, xo, x) - frame.cam.x, py = Mth.lerp(partial, yo, y) - frame.cam.y, pz = Mth.lerp(partial, zo, z) - frame.cam.z;
        double d = Math.sqrt(px * px + py * py + pz * pz);
        // дымка воздуха — та же, что у дальней картинки; к краю прорисовки частицу гасит туман Minecraft
        float a = aCol * frame.haze(d);
        int light = lightColor(partial);
        float fog = frame.fog(px, py, pz);
        // свет — уже умноженный на непрозрачность; искры и вспышки только светят (свет складывается)
        float r = rCol * a, g = gCol * a, b = bCol * a, cover = kind.additive ? 0 : a;
        FxAtlas.Sprite sprite = sprite();
        out.opacity(a);
        switch (kind) {
            case SPARK -> streak(out, sprite, (float) px, (float) py, (float) pz, (float) d, r, g, b, light, fog);
            case RING -> ring(out, sprite, (float) px, (float) py, (float) pz, (float) d, r, g, b, cover, light, fog);
            default -> out.billboard((float) px, (float) py, (float) pz, (float) d, quadSize, Mth.lerp(partial, oRoll, roll), sprite, r, g, b, cover,
                    quadSize * FxQuads.SOFT, 1, light, fog);
        }
    }

    private FxAtlas.Sprite sprite() {
        return switch (kind) {
            // стадия рассеивания: клуб «тает» во второй половине жизни
            case SMOKE -> FxAtlas.smoke(variant * 4 + Mth.clamp((int) ((((float) age / life) - 0.35f) / 0.65f * 4), 0, 3));
            case FIRE -> FxAtlas.fire(variant);
            case SPARK -> FxAtlas.spark();
            case FLASH -> FxAtlas.flash();
            case RING -> FxAtlas.ring();
        };
    }

    /** Искра: штрих назад на путь за {@code streak} тиков, хвост гаснет; короткий — обычный квадрат. */
    private void streak(FxQuads out, FxAtlas.Sprite sprite, float px, float py, float pz, float d, float r, float g, float b,
                        int light, float fog) {
        Vector3f dir = new Vector3f((float) xd, (float) yd, (float) zd).mul(-streak);
        float len = dir.length(), w = quadSize;
        if (len < w * 1.5f) {
            out.billboard(px, py, pz, d, w, 0, sprite, r, g, b, 0, w, 1, light, fog);
            return;
        }
        Vector3f side = new Vector3f(dir).cross(-px, -py, -pz);
        if (side.lengthSquared() < 1e-8f) return;
        side.normalize().mul(w);
        float tx = px + dir.x, ty = py + dir.y, tz = pz + dir.z;
        float u0 = sprite.u0(), u1 = sprite.u1(), v0 = sprite.v0(), v1 = sprite.v1(), um = (u0 + u1) / 2;
        out.quad(d);
        out.vertex(px - side.x, py - side.y, pz - side.z, u0, v1, r, g, b, 0, w, 1, light, fog);
        out.vertex(px + side.x, py + side.y, pz + side.z, u0, v0, r, g, b, 0, w, 1, light, fog);
        // хвост уже головы и гаснет: светящийся след
        out.opacity(0);
        out.vertex(tx + side.x * 0.3f, ty + side.y * 0.3f, tz + side.z * 0.3f, um, v0, 0, 0, 0, 0, w, 1, light, fog);
        out.vertex(tx - side.x * 0.3f, ty - side.y * 0.3f, tz - side.z * 0.3f, um, v1, 0, 0, 0, 0, w, 1, light, fog);
    }

    /** Кольцо ударной волны: плоское, лежит на земле (видно и сверху, и снизу — без отсечения граней). */
    private void ring(FxQuads out, FxAtlas.Sprite sprite, float px, float py, float pz, float d, float r, float g, float b, float cover,
                      int light, float fog) {
        // мягкий край — тоньше высоты над землёй (0.25–0.4): сверху кольцо от земли под ним в долях блока по лучу
        float s = quadSize, soft = 0.125f;
        float u0 = sprite.u0(), u1 = sprite.u1(), v0 = sprite.v0(), v1 = sprite.v1();
        out.quad(d);
        out.vertex(px - s, py, pz - s, u0, v0, r, g, b, cover, soft, 1, light, fog);
        out.vertex(px - s, py, pz + s, u0, v1, r, g, b, cover, soft, 1, light, fog);
        out.vertex(px + s, py, pz + s, u1, v1, r, g, b, cover, soft, 1, light, fog);
        out.vertex(px + s, py, pz - s, u1, v0, r, g, b, cover, soft, 1, light, fog);
    }
}
