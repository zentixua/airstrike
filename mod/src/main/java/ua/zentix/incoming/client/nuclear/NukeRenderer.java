package ua.zentix.incoming.client.nuclear;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RegisterShadersEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.client.far.FarSprites;
import ua.zentix.incoming.client.far.FarView;
import ua.zentix.incoming.client.far.Sight;
import ua.zentix.incoming.client.fx.layer.FxLights;
import ua.zentix.incoming.client.render.FarDraw;
import ua.zentix.incoming.net.S2C;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.nuclear.model.BlackBody;
import ua.zentix.incoming.nuclear.model.MushroomModel;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Картинка ядерного удара в мире (DESIGN-nuke §6): янтарное небо, след входа боеголовки и чёрный дождь — своим
 * проходом; огненный шар, гриб и столб воды ({@link MushroomField}), стена пыли и облако Вильсона ({@link BlastWall}) —
 * объёмом в проходе пелены слоя эффектов; свет шара вокруг — светом частиц и бликом с вуалью там же ({@link #fireballs}).
 * <p>
 * Дальняя плоскость отсечения в 1.21.1 — 4 × дальность прорисовки, а гриб 15 кт — 12 км в высоту. Всё, что
 * дальше {@code 0.97·far}, переносится на эту дистанцию по тому же лучу и уменьшается во столько же раз (угловой
 * размер сохраняется). Рисуется всё после мира ({@code AFTER_LEVEL}), с проверкой глубины, но без записи: ландшафт
 * и облака ближе — закрывают гриб, небо — нет. Под шейдерами Iris только этот этап и виден: всё, что нарисовано
 * раньше, шейдерпак пропускает через свои проходы — туман на дальности прорисовки съедает гриб целиком. Туман
 * ванили тоже не действует — своя дымка по настоящей дальности.
 */
public final class NukeRenderer {
    private static final ResourceLocation RAIN = Incoming.id("textures/nuke/rain.png");
    private static final float[][] ICOSPHERE = Icosphere.build(3);
    /** Свет шара кадра ({@link BlackBody#light}), середина шапки в мире, числа блика ({@link Sight#light}). */
    private static final double[] BALL = new double[4], CENTRE = new double[3], GLARE = new double[5];

    /** Круглое свечение по формуле ({@code nuke_flare}): голова следа боеголовки, послеобраз вспышки. */
    @Nullable
    static ShaderInstance flare;

    private NukeRenderer() {}

    /** Шина мода: шейдер свечения (и заново при перезагрузке ресурсов). */
    public static void registerShaders(RegisterShadersEvent e) {
        try {
            e.registerShader(new ShaderInstance(e.getResourceProvider(), Incoming.id("nuke_flare"), DefaultVertexFormat.POSITION_TEX_COLOR), s -> flare = s);
        } catch (IOException ex) {
            flare = null;
            Incoming.LOG.error("Nuclear glow shader failed to load: no re-entry streak head or afterimage", ex);
        }
    }

    /** Свечение по формуле, сложением света; шейдера нет — false (не рисовать). */
    static boolean setupFlare() {
        ShaderInstance s = flare;
        if (s == null) return false;
        RenderSystem.setShader(() -> s);
        FarDraw.blend(true);
        return true;
    }

    public static void render(RenderLevelStageEvent e) {
        if (e.getStage() != RenderLevelStageEvent.Stage.AFTER_LEVEL || ClientNuclear.isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) return;
        Camera camera = e.getCamera();
        float partial = e.getPartialTick().getGameTimeDeltaPartialTick(false);
        float far = mc.gameRenderer.getDepthFar() * 0.97f;

        Vec3 eye = FarDraw.eye(e);
        FarDraw.begin(e, eye);
        try {
            drawSkyGlow(eye, partial, far);
            drawReentry(level, camera, eye, partial, far);
        } finally {
            FarDraw.end();
        }
    }

    /** Чёрный дождь — после слоя эффектов: капли у самой камеры ближе любого дыма. */
    public static void renderRain(RenderLevelStageEvent e) {
        if (e.getStage() != RenderLevelStageEvent.Stage.AFTER_LEVEL || NukeSky.blackRain() <= 0) return;
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) return;
        Vec3 eye = FarDraw.eye(e);
        FarDraw.begin(e, eye);
        try {
            drawBlackRain(level, eye, e.getPartialTick().getGameTimeDeltaPartialTick(false));
        } finally {
            FarDraw.end();
        }
    }

    // ---------------------------------------------------------------- огненный шар

    /**
     * Свет огненного шара и шапки вокруг (DESIGN-nuke, шаг M2) — тело рисует объём гриба ({@link MushroomField}): шар
     * светит частицам и лентам кадра ({@link FxLights}: пыль и дым у шара), а в глазу — бликом и вуалью
     * ({@link FarSprites#eyeLight}) с пробой видимости тела (закрыт ли он рельефом и постройками, с LOD Distant Horizons,
     * пеленой и стеной пыли, чужими шапками и ножками грибов). Яркость — чёрное тело
     * видимой температуры шапки снаружи ({@link MushroomModel#edgeTemperature}): шар, пока шар, — днём ярче неба
     * секунды, ночью — дольше (глаз привык к темноте, {@link Sight#adapted}); свернувшаяся шапка снаружи смешана
     * с воздухом и гаснет. Дымка воздуха — по настоящей дальности (в метрах модели).
     */
    public static void fireballs(FarView view, FarSprites out, FxLights lights) {
        List<ClientNuclear.Active> all = ClientNuclear.detonations();
        if (all.isEmpty()) return;
        Vec3 cam = view.camera();
        for (ClientNuclear.Active a : all) {
            MushroomModel.Shape s = MushroomField.shape(a, view.partial());
            if (s == null) continue;
            Detonation d = a.d;
            BlackBody.light(MushroomModel.edgeTemperature(s.t(), d.yieldKt(), d.surface()), BALL);
            double surface = Sight.adapted(BALL[3], view.ambient());
            if (surface < 1e-3) continue;
            MushroomField.centre(a, s, CENTRE);
            double dx = CENTRE[0] - cam.x, dy = CENTRE[1] - cam.y, dz = CENTRE[2] - cam.z, r = d.blocks(s.capR());
            double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
            // цвет: свет частицам — линейный на единицу яркости, блику и вуали — на экране, самый яркий канал — 1
            float lr = (float) (BALL[0] / BALL[3]), lg = (float) (BALL[1] / BALL[3]), lb = (float) (BALL[2] / BALL[3]);
            double top = Math.max(BALL[0], Math.max(BALL[1], BALL[2]));
            float cr = (float) Math.pow(BALL[0] / top, 1 / 2.2), cg = (float) Math.pow(BALL[1] / top, 1 / 2.2), cb = (float) Math.pow(BALL[2] / top, 1 / 2.2);
            double air = Sight.transmittance(d.metres(dist), view.range()), seen = surface * air;
            // огонь шара на экране (доля белого): свет вокруг и блик не ярче его
            double shown = Math.min(1, Math.pow(seen, 1 / 2.2));
            lights.add(dx, dy, dz, r, surface, Math.min(1, Math.pow(surface, 1 / 2.2)), lr, lg, lb);
            if (seen > Sight.THRESHOLD) out.eyeLight(dx, dy, dz, r, seen, view.pixel(), cr, cg, cb, shown, GLARE);
        }
    }

    // ---------------------------------------------------------------- небо

    /**
     * Янтарное небо после вспышки ({@link NukeSky#amber}): купол у дальней плоскости — мир ближе закрывает его,
     * поэтому окрашено только небо, сильнее всего в сторону подрыва.
     */
    private static void drawSkyGlow(Vec3 cam, float partial, float far) {
        for (ClientNuclear.Active a : ClientNuclear.detonations()) {
            float amber = NukeSky.amber(a, cam, partial);
            if (amber <= 0.004f) continue;
            Vec3 dir = a.d.burst().subtract(cam).normalize();
            RenderSystem.setShader(GameRenderer::getPositionColorShader);
            FarDraw.blend(false);
            BufferBuilder b = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);
            for (float[] v : ICOSPHERE) {
                double toward = Math.max(0, v[0] * dir.x + v[1] * dir.y + v[2] * dir.z);
                float alpha = amber * (0.2f + 0.5f * (float) (toward * toward * toward));
                b.addVertex(v[0] * far, v[1] * far, v[2] * far).setColor(1f, 0.55f, 0.22f, alpha);
            }
            FarDraw.draw(b);
        }
    }

    // ---------------------------------------------------------------- вход боеголовки

    /** Последние 3 с перед подрывом: светящаяся черта с головой-звездой сверху вниз к цели. */
    private static void drawReentry(ClientLevel level, Camera camera, Vec3 cam, float partial, float far) {
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
            if (!setupFlare()) return;
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
                FarDraw.billboard(b, l, up, (float) (p.x * k), (float) (p.y * k), (float) (p.z * k), size, 0, 0, 0, 1, 1, 1f, 0.9f - 0.4f * i / steps, 0.7f - 0.5f * i / steps, a);
            }
            FarDraw.draw(b);
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
    private static void drawBlackRain(ClientLevel level, Vec3 cam, float partial) {
        float k = NukeSky.blackRain();
        if (k <= 0) return;
        int cx = Mth.floor(cam.x), cz = Mth.floor(cam.z);
        int radius = 10;
        float time = (level.getGameTime() % 100_000) + partial;
        FarDraw.setup(RAIN, false);
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
        FarDraw.draw(b);
    }

    // ---------------------------------------------------------------- общее

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
