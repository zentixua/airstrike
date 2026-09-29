package ua.zentix.airstrike.client.nuclear;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4fStack;
import org.joml.Vector3f;
import ua.zentix.airstrike.Airstrike;
import ua.zentix.airstrike.net.S2C;
import ua.zentix.airstrike.nuclear.Detonation;
import ua.zentix.airstrike.nuclear.model.FireballModel;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Картинка ядерного удара в мире (DESIGN-nuke §6): огненный шар (икосфера с плазмой), гриб из клубов, пылевая
 * стена, облако Вильсона, след входа боеголовки и чёрный дождь.
 * <p>
 * Дальняя плоскость отсечения в 1.21.1 — 4 × дальность прорисовки, а гриб 15 кт — 12 км в высоту. Всё, что
 * дальше {@code 0.97·far}, переносится на эту дистанцию по тому же лучу и уменьшается во столько же раз (угловой
 * размер сохраняется). Рисуется всё после мира ({@code AFTER_LEVEL}), с проверкой глубины, но без записи: ландшафт
 * и облака ближе — закрывают гриб, небо — нет. Под шейдерами Iris только этот этап и виден: всё, что нарисовано
 * раньше, шейдерпак пропускает через свои проходы — туман на дальности прорисовки съедает гриб целиком. Туман
 * ванили тоже не действует — своя дымка по настоящей дальности.
 */
public final class NukeRenderer {
    private static final ResourceLocation PUFFS = Airstrike.id("textures/nuke/puffs.png");
    private static final ResourceLocation PLASMA = Airstrike.id("textures/nuke/plasma.png");
    private static final ResourceLocation FLARE = Airstrike.id("textures/nuke/flare.png");
    private static final ResourceLocation RAIN = Airstrike.id("textures/nuke/rain.png");
    private static final float[][] ICOSPHERE = Icosphere.build(3);

    /** Клуб, уже перенесённый к камере: координаты относительно камеры, размер, цвет. */
    private record Quad(float x, float y, float z, float size, float rot, float r, float g, float b, float a, int tex, double dist) {}

    private static final List<Quad> QUADS = new ArrayList<>();

    private NukeRenderer() {}

    public static void render(RenderLevelStageEvent e) {
        if (e.getStage() != RenderLevelStageEvent.Stage.AFTER_LEVEL || ClientNuclear.isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) return;
        Camera camera = e.getCamera();
        float partial = e.getPartialTick().getGameTimeDeltaPartialTick(false);
        float far = mc.gameRenderer.getDepthFar() * 0.97f;

        Matrix4fStack mv = RenderSystem.getModelViewStack();
        mv.pushMatrix();
        mv.identity();
        mv.mul(e.getModelViewMatrix());
        RenderSystem.applyModelViewMatrix();
        try {
            collect(level, camera, partial, far);
            drawFireballs(camera, partial, far);
            drawQuads(camera, QUADS);
            drawReentry(level, camera, partial, far);
            drawBlackRain(level, camera, partial);
        } finally {
            mv.popMatrix();
            RenderSystem.applyModelViewMatrix();
            RenderSystem.depthMask(true);
            RenderSystem.enableDepthTest();
            RenderSystem.enableCull();
            RenderSystem.defaultBlendFunc();
            RenderSystem.disableBlend();
            RenderSystem.setShaderColor(1, 1, 1, 1);
        }
    }

    // ---------------------------------------------------------------- гриб

    /** Все клубы всех подрывов за кадр (дальние — сжатые), отсортированные от дальних к ближним. */
    private static void collect(ClientLevel level, Camera camera, float partial, float far) {
        QUADS.clear();
        Vec3 cam = camera.getPosition();
        float ambient = Mth.clamp(level.getSkyDarken(partial) * 1.1f - 0.05f, 0.12f, 1f);
        float[] fog = RenderSystem.getShaderFogColor();
        for (ClientNuclear.Active a : ClientNuclear.detonations()) {
            double t = a.seconds(partial);
            double vis = a.d.visibility();
            a.puffs.sprites(t, ambient, s -> {
                double dx = s.x() - cam.x, dy = s.y() - cam.y, dz = s.z() - cam.z;
                double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
                // дымка по настоящей дальности (в метрах модели)
                float haze = (float) (1 - Math.exp(-a.d.metres(dist) / vis));
                float r = Mth.lerp(haze, s.r(), fog[0]), g = Mth.lerp(haze, s.g(), fog[1]), b = Mth.lerp(haze, s.b(), fog[2]);
                float alpha = s.a() * (1 - 0.5f * haze);
                double k = dist > far ? far / dist : 1;
                QUADS.add(new Quad((float) (dx * k), (float) (dy * k), (float) (dz * k), (float) (s.size() * k), s.rot(), r, g, b, alpha, s.tex(), dist));
            });
        }
        QUADS.sort(Comparator.comparingDouble(Quad::dist).reversed());
    }

    private static void drawQuads(Camera camera, List<Quad> quads) {
        if (quads.isEmpty()) return;
        setup(PUFFS, false);
        Vector3f left = camera.getLeftVector(), up = camera.getUpVector();
        BufferBuilder b = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        for (Quad q : quads) {
            float u0 = (q.tex() % 4) * 0.25f, v0 = (q.tex() / 4) * 0.5f;
            billboard(b, left, up, q.x(), q.y(), q.z(), q.size() * 0.5f, q.rot(), u0, v0, u0 + 0.25f, v0 + 0.5f, q.r(), q.g(), q.b(), q.a());
        }
        draw(b);
    }

    // ---------------------------------------------------------------- огненный шар

    /** Шар и корона: плазма с прокруткой, складывается со светом (ярче всего вокруг). */
    private static void drawFireballs(Camera camera, float partial, float far) {
        Vec3 cam = camera.getPosition();
        for (ClientNuclear.Active a : ClientNuclear.detonations()) {
            Detonation d = a.d;
            double t = a.seconds(partial);
            if (t <= 0) continue;
            double tau = t / FireballModel.secondMaximumSeconds(d.yieldKt());
            if (tau > 80) continue;
            double r = d.blocks(FireballModel.radius(t, d.yieldKt(), d.surface()));
            double cy = d.groundY() + d.blocks(FireballModel.centreHeight(t, d.hobMetres(), d.yieldKt()));
            double dx = d.burst().x - cam.x, dy = cy - cam.y, dz = d.burst().z - cam.z;
            double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (dist > far) {
                double k = far / dist;
                dx *= k;
                dy *= k;
                dz *= k;
                r *= k;
            }
            int rgb = FireballModel.colorArgb(t, d.yieldKt());
            // шар гаснет, когда его закрывает шапка (она проступает на тех же τ, см. CloudPuffs)
            float fade = (float) (1 - CloudPuffs.smooth(8, 45, tau));
            float glow = (float) Math.max(0.35, Math.min(1, FireballModel.brightness(t, d.yieldKt()) * 3 + 0.35)) * fade;
            float cr = ((rgb >> 16) & 0xFF) / 255f, cg = ((rgb >> 8) & 0xFF) / 255f, cb = (rgb & 0xFF) / 255f;
            float scroll = (float) (t * 0.04);
            setup(PLASMA, true);
            sphere((float) dx, (float) dy, (float) dz, (float) r, scroll, cr, cg, cb, glow);
            sphere((float) dx, (float) dy, (float) dz, (float) (r * 1.18), -scroll * 0.7f, cr, cg * 0.9f, cb * 0.8f, glow * 0.35f);
            // ореол: плоское свечение к камере, в 3 раза шире шара
            setup(FLARE, true);
            Vector3f left = camera.getLeftVector(), up = camera.getUpVector();
            BufferBuilder b = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
            billboard(b, left, up, (float) dx, (float) dy, (float) dz, (float) (r * 3), 0, 0, 0, 1, 1, cr, cg, cb, glow * 0.5f);
            draw(b);
        }
    }

    private static void sphere(float x, float y, float z, float r, float scroll, float cr, float cg, float cb, float a) {
        BufferBuilder b = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_TEX_COLOR);
        for (float[] v : ICOSPHERE) {
            // v: нормаль (x, y, z) и u, v развёртки; к краю диска шар прозрачнее — мягкий край
            b.addVertex(x + v[0] * r, y + v[1] * r, z + v[2] * r).setUv(v[3] + scroll, v[4]).setColor(cr, cg, cb, a);
        }
        draw(b);
    }

    // ---------------------------------------------------------------- вход боеголовки

    /** Последние 3 с перед подрывом: светящаяся черта с головой-звездой сверху вниз к цели. */
    private static void drawReentry(ClientLevel level, Camera camera, float partial, float far) {
        Vec3 cam = camera.getPosition();
        double now = level.getGameTime() + partial;
        for (S2C.NukeWarning w : ClientNuclear.warnings()) {
            double left = w.detonateTime() - now;
            if (left < 0 || left > NukeSounds.REENTRY_TICKS) continue;
            Vec3 end = w.target().add(0, w.airBurst() ? w.burstHeight() : 0, 0);
            Vec3 dir = reentryDirection(w);
            double f = left / NukeSounds.REENTRY_TICKS; // 1 → 0
            // от 60 км до точки подрыва, почти по прямой (≈ 7 км/с)
            double path = 60_000 * w.scale();
            Vec3 head = end.add(dir.scale(-path * f));
            Vec3 tail = head.add(dir.scale(-Math.min(path * 0.25, path * (1 - f) + 2000 * w.scale())));
            setup(FLARE, true);
            Vector3f l = camera.getLeftVector(), up = camera.getUpVector();
            BufferBuilder b = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
            int steps = 24;
            for (int i = 0; i <= steps; i++) {
                Vec3 p = head.lerp(tail, i / (double) steps).subtract(cam);
                double dist = p.length();
                double k = dist > far ? far / dist : 1;
                // угловой размер: голова-звезда ~2°, хвост сужается; раскалённая плазма — от белого к оранжевому
                float size = (float) (dist * k * (i == 0 ? 0.035 : 0.012 * (1 - 0.7 * i / (double) steps)));
                float a = (float) (i == 0 ? 1 : 0.8 * (1 - i / (double) steps));
                billboard(b, l, up, (float) (p.x * k), (float) (p.y * k), (float) (p.z * k), size, 0, 0, 0, 1, 1, 1f, 0.9f - 0.4f * i / steps, 0.7f - 0.5f * i / steps, a);
            }
            draw(b);
        }
    }

    /** Направление входа: со стороны пуска, под крутым углом (~70° к горизонту). */
    static Vec3 reentryDirection(S2C.NukeWarning w) {
        Vec3 h = w.target().subtract(w.launchPos());
        Vec3 flat = new Vec3(h.x, 0, h.z);
        flat = flat.lengthSqr() < 1 ? new Vec3(1, 0, 0) : flat.normalize();
        return flat.scale(0.34).add(0, -0.94, 0).normalize();
    }

    // ---------------------------------------------------------------- чёрный дождь

    /** Тёмные тяжёлые капли вокруг игрока в следе осадков: как ванильный дождь, только свой и местный. */
    private static void drawBlackRain(ClientLevel level, Camera camera, float partial) {
        float k = NukeSky.blackRain();
        if (k <= 0) return;
        Vec3 cam = camera.getPosition();
        int cx = Mth.floor(cam.x), cz = Mth.floor(cam.z);
        int radius = 10;
        float time = (level.getGameTime() % 100_000) + partial;
        setup(RAIN, false);
        BufferBuilder b = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        for (int x = cx - radius; x <= cx + radius; x++) {
            for (int z = cz - radius; z <= cz + radius; z++) {
                double ddx = x + 0.5 - cam.x, ddz = z + 0.5 - cam.z;
                double hd = Math.sqrt(ddx * ddx + ddz * ddz);
                if (hd > radius) continue;
                int ground = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
                float top = (float) Math.max(ground, cam.y + 12), bottom = (float) Math.max(ground, cam.y - 6);
                if (top <= bottom) continue;
                long seed = BlockPos.asLong(x, 0, z) * 3129871L;
                float off = (seed & 0xFF) / 255f;
                // блок ширины, лицом к камере, как ванильный дождь; на текстуре — много тонких капель
                float nx = (float) (-ddz / Math.max(hd, 0.01)) * 0.5f, nz = (float) (ddx / Math.max(hd, 0.01)) * 0.5f;
                float px = (float) ddx, pz = (float) ddz;
                float v0 = -(time * 0.045f + off * 7), v1 = v0 + (top - bottom) / 8;
                float a = (float) (0.55 * k * (1 - hd / (radius + 1)));
                float y0 = (float) (bottom - cam.y), y1 = (float) (top - cam.y);
                b.addVertex(px - nx, y1, pz - nz).setUv(0, v0).setColor(0.12f, 0.11f, 0.10f, a);
                b.addVertex(px + nx, y1, pz + nz).setUv(1, v0).setColor(0.12f, 0.11f, 0.10f, a);
                b.addVertex(px + nx, y0, pz + nz).setUv(1, v1).setColor(0.12f, 0.11f, 0.10f, a);
                b.addVertex(px - nx, y0, pz - nz).setUv(0, v1).setColor(0.12f, 0.11f, 0.10f, a);
            }
        }
        draw(b);
    }

    // ---------------------------------------------------------------- общее

    /** Своя текстура и смешивание; глубина проверяется, но не пишется (прозрачное поверх непрозрачного мира). */
    private static void setup(ResourceLocation texture, boolean additive) {
        RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
        RenderSystem.setShaderTexture(0, texture);
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

    private static void billboard(BufferBuilder b, Vector3f left, Vector3f up, float x, float y, float z, float half, float rot,
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

    private static void draw(BufferBuilder b) {
        MeshData mesh = b.build();
        if (mesh != null) BufferUploader.drawWithShader(mesh);
    }

    /** Икосфера: список вершин треугольников {x, y, z, u, v} на единичной сфере, шов развёртки без растяжки. */
    static final class Icosphere {
        private Icosphere() {}

        static float[][] build(int subdivisions) {
            float p = (1 + Mth.sqrt(5)) / 2;
            List<Vector3f> tris = new ArrayList<>();
            float[][] v = {{-1, p, 0}, {1, p, 0}, {-1, -p, 0}, {1, -p, 0}, {0, -1, p}, {0, 1, p}, {0, -1, -p}, {0, 1, -p},
                    {p, 0, -1}, {p, 0, 1}, {-p, 0, -1}, {-p, 0, 1}};
            int[][] f = {{0, 11, 5}, {0, 5, 1}, {0, 1, 7}, {0, 7, 10}, {0, 10, 11}, {1, 5, 9}, {5, 11, 4}, {11, 10, 2}, {10, 7, 6},
                    {7, 1, 8}, {3, 9, 4}, {3, 4, 2}, {3, 2, 6}, {3, 6, 8}, {3, 8, 9}, {4, 9, 5}, {2, 4, 11}, {6, 2, 10}, {8, 6, 7}, {9, 8, 1}};
            for (int[] t : f) {
                tris.add(new Vector3f(v[t[0]]).normalize());
                tris.add(new Vector3f(v[t[1]]).normalize());
                tris.add(new Vector3f(v[t[2]]).normalize());
            }
            for (int s = 0; s < subdivisions; s++) {
                List<Vector3f> next = new ArrayList<>(tris.size() * 4);
                for (int i = 0; i < tris.size(); i += 3) {
                    Vector3f a = tris.get(i), b = tris.get(i + 1), c = tris.get(i + 2);
                    Vector3f ab = new Vector3f(a).add(b).normalize(), bc = new Vector3f(b).add(c).normalize(), ca = new Vector3f(c).add(a).normalize();
                    next.addAll(List.of(a, ab, ca, b, bc, ab, c, ca, bc, ab, bc, ca));
                }
                tris = next;
            }
            float[][] out = new float[tris.size()][];
            for (int i = 0; i < tris.size(); i += 3) {
                float[] u = new float[3];
                for (int j = 0; j < 3; j++) {
                    Vector3f n = tris.get(i + j);
                    u[j] = (float) (Math.atan2(n.z, n.x) / (2 * Math.PI) + 0.5);
                }
                float max = Math.max(u[0], Math.max(u[1], u[2]));
                for (int j = 0; j < 3; j++) {
                    if (max - u[j] > 0.5f) u[j] += 1; // треугольник на шве: развёртка не перескакивает через всю текстуру
                    Vector3f n = tris.get(i + j);
                    out[i + j] = new float[]{n.x, n.y, n.z, u[j] * 2, (float) (Math.acos(Mth.clamp(n.y, -1, 1)) / Math.PI)};
                }
            }
            return out;
        }
    }
}
