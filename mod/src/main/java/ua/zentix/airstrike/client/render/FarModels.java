package ua.zentix.airstrike.client.render;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.world.inventory.InventoryMenu;
import net.neoforged.neoforge.client.event.RegisterShadersEvent;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL30;
import ua.zentix.airstrike.Airstrike;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Снаряды вдали — их настоящие модели ({@link WeaponModels}, тот же код и те же анимации, что у сущности вблизи)
 * там, где сущности у клиента нет. Каждая модель кадра рисуется в свою плитку атласа: перспективой из глаза, только
 * она и только её угол зрения, вчетверо подробнее экрана, светом мира, как сущность (свет неба по карте освещения,
 * два направленных света {@code Lighting}); потом атлас уменьшается мип-уровнями, и плитка ложится в кадр квадратом
 * лицом к камере ({@code client.far.FarSprites}): с дымкой воздуха в непрозрачности и в общем порядке с дымом — от
 * дальних к ближним. Так модель в несколько пикселей — сглаженный силуэт без мерцания тонких крыльев, а дальние
 * плитки, как и остальное дальнее, переносятся ближе дальней плоскости с тем же угловым размером. Мельче
 * {@link #COARSE_BELOW} пикселей — упрощённые копии деталей ({@link WeaponModels.Mesh#lod}).
 * <p>
 * Атлас рисуется своим шейдером ({@code shaders/core/far_model}) в {@code AFTER_LEVEL}: Iris заменяет чужие шейдеры
 * только пока рисует мир. Плитки, буферы и атлас живут между кадрами; на кадр — только мелкие короткоживущие объекты
 * движка (построитель вершин одноразовый) и анимаций моделей.
 */
public final class FarModels {
    /** Модель в любом повороте и с любыми раскрытыми деталями — в шаре такого радиуса на её размер ({@code FarModelsTest}). */
    public static final double TILE_RADIUS = 0.6;
    /**
     * Длина модели на экране, пикселей: короче {@link #DOT_BELOW} — точка ({@code Sight.body}: форму уже не различить,
     * виден только заслон неба), длиннее {@link #MODEL_FROM} — модель, между ними — переход.
     */
    public static final double DOT_BELOW = 2, MODEL_FROM = 3;
    /**
     * Короче {@link #COARSE_BELOW} пикселей — упрощённые копии (отход их поверхности — 0,5 % размера, меньше четверти
     * пикселя), длиннее {@link #FULL_ABOVE} — полные сетки; между — какие были (без дрожания на границе).
     */
    public static final double COARSE_BELOW = 40, FULL_ABOVE = 48;
    /**
     * Сторона атласа, текселей: ~9 МБ видеопамяти с мип-уровнями и глубиной, мип-уровни всего атласа — на каждый кадр
     * с моделями вдали. Полный атлас — остальные модели кадра точками.
     */
    static final int ATLAS = 1024;
    /**
     * Текселей на пиксель экрана; плитка — от {@link #MIN_TILE} до {@link #MAX_TILE}: самая крупная с каймой — ровно
     * полстороны атласа, четыре таких входят (плитка шире 126 px экрана — меньше 4 текселей на пиксель: крупной
     * модели сглаживание почти не нужно).
     */
    static final int SUPERSAMPLE = 4, MIN_TILE = 8, MAX_TILE = 504;
    /** Мип-уровней сверх основного (4 текселя на пиксель — уровень 2); пустая кайма вокруг плитки — чтобы они не брали соседа. */
    static final int MIP_LEVELS = 2, PAD = 1 << MIP_LEVELS;
    /** Что {@link #add} пишет о плитке: вправо и вверх (оси квадрата), полуразмер, u0, v0, u1, v1. */
    public static final int RIGHT = 0, UP = 3, HALF = 6, U0 = 7, V0 = 8, U1 = 9, V1 = 10, OUT = 11;

    @Nullable
    private static ShaderInstance shader;

    @Nullable
    private TextureTarget atlas;
    @Nullable
    private ByteBufferBuilder solidBytes, glassBytes;
    private final List<Tile> tiles = new ArrayList<>();
    private int count;
    /** Полка, которую заполняем: левый край свободного места, её низ и высота. */
    private int shelfX, shelfY, shelfH;
    private final PoseStack poses = new PoseStack();
    private final Quaternionf rotation = new Quaternionf();
    private final Projector solid = new Projector(), glass = new Projector();
    private final MultiBufferSource buffers = type -> type == WeaponModels.TRANSLUCENT ? glass : solid;
    private final int[] viewport = new int[4];
    private final Runnable clear = this::clearUsed, clearAndDraw = () -> {
        clearUsed();
        draw();
    };

    /** Одна модель кадра и её плитка. */
    private static final class Tile {
        final ProjectilePose pose = new ProjectilePose();
        @Nullable
        WeaponModels.Look look;
        /** Модель → оси взгляда на неё: вправо, вверх, к ней (камера в начале координат). */
        final Matrix4f view = new Matrix4f();
        /** Дальность до центра модели, радиус её шара, тангенс половины угла плитки. */
        float d, h, tan;
        /** Середина плитки в атласе, текселей, и её сторона. */
        int x, y, size;
    }

    /** Шина мода: шейдер атласа (и заново при перезагрузке ресурсов). */
    public static void registerShaders(RegisterShadersEvent e) {
        try {
            e.registerShader(new ShaderInstance(e.getResourceProvider(), Airstrike.id("far_model"), DefaultVertexFormat.NEW_ENTITY),
                    s -> shader = s);
        } catch (IOException ex) {
            shader = null;
            Airstrike.LOG.error("Шейдер дальних моделей не загрузился: снаряды вдали будут точками", ex);
        }
    }

    /** Начать кадр: атлас пуст. */
    public void begin() {
        count = 0;
        shelfX = shelfY = shelfH = 0;
    }

    /** Сколько моделей в кадре. */
    public int count() {
        return count;
    }

    /**
     * Поставить модель в кадр. Камера — в начале координат, центр модели — (dx, dy, dz), size — её размер
     * ({@code FarLook.size}), pixel — угол пикселя экрана, camUp — верх экрана. В out ({@link #OUT} чисел) — квадрат,
     * которым плитка ляжет в кадр: его оси, полуразмер (на дальности центра) и место в атласе.
     *
     * @return false — модели не будет: нет шейдера, она вплотную к камере (шире 60°) или на краю обзора, атлас полон
     */
    public boolean add(WeaponModels.Look look, ProjectilePose pose, double dx, double dy, double dz, double size, double pixel,
                       Vector3f camUp, float[] out) {
        if (shader == null) return false;
        double d = Math.sqrt(dx * dx + dy * dy + dz * dz), h = TILE_RADIUS * size;
        if (d < 2 * h) return false;
        double fx = dx / d, fy = dy / d, fz = dz / d;
        // вправо — поперёк луча и верха экрана, вверх — поперёк луча и «вправо»: плитка повёрнута, как экран
        double rx = fy * camUp.z() - fz * camUp.y(), ry = fz * camUp.x() - fx * camUp.z(), rz = fx * camUp.y() - fy * camUp.x();
        double rl = Math.sqrt(rx * rx + ry * ry + rz * rz);
        if (rl < 1e-3) return false;
        rx /= rl;
        ry /= rl;
        rz /= rl;
        double ux = ry * fz - rz * fy, uy = rz * fx - rx * fz, uz = rx * fy - ry * fx;
        // шар радиуса h на дальности d виден под углом asin(h/d): плитка — квадрат тангенсов этого угла
        double tan = h / Math.sqrt(d * d - h * h);
        int side = tileSize(tan, pixel);
        int slot = side + 2 * PAD;
        if (shelfX + slot > ATLAS) {
            shelfY += shelfH;
            shelfX = shelfH = 0;
        }
        if (shelfY + slot > ATLAS) return false;
        if (count == tiles.size()) tiles.add(new Tile());
        Tile t = tiles.get(count++);
        t.look = look;
        t.pose.copy(pose);
        t.d = (float) d;
        t.h = (float) h;
        t.tan = (float) tan;
        t.size = side;
        t.x = shelfX + PAD + side / 2;
        t.y = shelfY + PAD + side / 2;
        shelfX += slot;
        shelfH = Math.max(shelfH, slot);
        t.view.set((float) rx, (float) ux, (float) fx, 0, (float) ry, (float) uy, (float) fy, 0, (float) rz, (float) uz, (float) fz, 0,
                0, 0, (float) d, 1);
        out[RIGHT] = (float) rx;
        out[RIGHT + 1] = (float) ry;
        out[RIGHT + 2] = (float) rz;
        out[UP] = (float) ux;
        out[UP + 1] = (float) uy;
        out[UP + 2] = (float) uz;
        out[HALF] = (float) (d * tan);
        out[U0] = (float) (t.x - side / 2) / ATLAS;
        out[V0] = (float) (t.y - side / 2) / ATLAS;
        out[U1] = (float) (t.x + side / 2) / ATLAS;
        out[V1] = (float) (t.y + side / 2) / ATLAS;
        return true;
    }

    /**
     * Сторона плитки, текселей: {@link #SUPERSAMPLE} текселей на пиксель угла плитки (2·tan/pixel пикселей), кратно 4
     * (мип-уровень 2 не делит тексель между плитками), от {@link #MIN_TILE} до {@link #MAX_TILE}.
     */
    static int tileSize(double tan, double pixel) {
        int texels = (int) Math.ceil(SUPERSAMPLE * 2 * tan / pixel);
        return Math.clamp((texels + 3) & ~3, MIN_TILE, MAX_TILE);
    }

    /** Текстура атласа (0 — его ещё нет). */
    public int texture() {
        return atlas == null ? 0 : atlas.getColorTextureId();
    }

    /** Атлас и буферы заранее (первый кадр с моделью вдали не ждёт выделения памяти видеокарты). */
    public void warmup() {
        withAtlasBound(clear);
    }

    /**
     * Нарисовать модели кадра в атлас и уменьшить его мип-уровнями. Вызывается после сбора кадра и до отрисовки
     * квадратов; кадровый буфер и область вывода, которые были до вызова, возвращаются.
     */
    public void render() {
        if (count == 0 || shader == null) return;
        withAtlasBound(clearAndDraw);
    }

    private void withAtlasBound(Runnable work) {
        int drawFbo = GlStateManager.getBoundFramebuffer(), readFbo = GlStateManager._getInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
        try {
            if (atlas == null) create();
            atlas.bindWrite(false);
            GlStateManager._viewport(0, 0, ATLAS, ATLAS);
            work.run();
            RenderSystem.activeTexture(GL13.GL_TEXTURE0);
            RenderSystem.bindTexture(atlas.getColorTextureId());
            GL30.glGenerateMipmap(GL11.GL_TEXTURE_2D);
        } finally {
            GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, readFbo);
            GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, drawFbo);
            GlStateManager._viewport(viewport[0], viewport[1], viewport[2], viewport[3]);
        }
    }

    private void create() {
        TextureTarget t = new TextureTarget(ATLAS, ATLAS, true, Minecraft.ON_OSX);
        RenderSystem.bindTexture(t.getColorTextureId());
        GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR_MIPMAP_LINEAR);
        GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_MAX_LEVEL, MIP_LEVELS);
        atlas = t;
        solidBytes = new ByteBufferBuilder(1 << 18);
        glassBytes = new ByteBufferBuilder(1 << 14);
    }

    /** Очистить занятые полки (прозрачным; глубина — дальше всего). */
    private void clearUsed() {
        int used = Math.max(PAD, Math.min(ATLAS, shelfY + shelfH));
        RenderSystem.enableScissor(0, 0, ATLAS, used);
        GlStateManager._clearColor(0, 0, 0, 0);
        GlStateManager._clearDepth(1);
        GlStateManager._clear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT, Minecraft.ON_OSX);
        RenderSystem.disableScissor();
    }

    private void draw() {
        BufferBuilder solidOut = new BufferBuilder(solidBytes, VertexFormat.Mode.QUADS, DefaultVertexFormat.NEW_ENTITY);
        BufferBuilder glassOut = new BufferBuilder(glassBytes, VertexFormat.Mode.QUADS, DefaultVertexFormat.NEW_ENTITY);
        solid.out = solidOut;
        glass.out = glassOut;
        for (int i = 0; i < count; i++) {
            Tile t = tiles.get(i);
            solid.aim(t);
            glass.aim(t);
            PoseStack.Pose root = poses.last();
            root.pose().set(t.view);
            root.normal().identity();
            // нормали — в осях мира: в них светят направленные света Lighting, как у сущностей вблизи
            poses.pushPose();
            poses.mulPose(t.pose.rotation(rotation));
            t.look.render(t.pose, poses, buffers, LightTexture.FULL_SKY);
            poses.popPose();
            t.look = null;
        }
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level != null && level.effects().constantAmbientLight()) Lighting.setupNetherLevel();
        else Lighting.setupLevel();
        mc.gameRenderer.lightTexture().turnOnLightLayer();
        RenderSystem.setShader(() -> shader);
        RenderSystem.setShaderTexture(0, InventoryMenu.BLOCK_ATLAS);
        RenderSystem.disableCull();
        RenderSystem.enableDepthTest();
        RenderSystem.depthFunc(GL11.GL_LEQUAL);
        // непрозрачное — с глубиной; размытые диски винтов — поверх, с умноженной альфой, как и весь атлас
        RenderSystem.disableBlend();
        RenderSystem.depthMask(true);
        upload(solidOut);
        RenderSystem.enableBlend();
        RenderSystem.blendFuncSeparate(GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
                GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
        RenderSystem.depthMask(false);
        upload(glassOut);
        RenderSystem.depthMask(true);
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableBlend();
        RenderSystem.enableCull();
        mc.gameRenderer.lightTexture().turnOffLightLayer();
        solid.out = glass.out = null;
    }

    private static void upload(BufferBuilder b) {
        MeshData mesh = b.build();
        if (mesh != null) BufferUploader.drawWithShader(mesh);
    }

    /**
     * Вершины модели в оси взгляда на неё → место в плитке атласа: перспективой из глаза (x/z, y/z в тангенсах угла
     * плитки), глубина — по шару модели. Остальное (цвет, развёртка, свет, нормаль) — как есть.
     */
    private static final class Projector implements VertexConsumer {
        @Nullable
        BufferBuilder out;
        private float cx, cy, scale, d, invTan, invH;

        void aim(Tile t) {
            cx = 2f * t.x / ATLAS - 1;
            cy = 2f * t.y / ATLAS - 1;
            scale = (float) t.size / ATLAS;
            d = t.d;
            invTan = 1 / t.tan;
            invH = 1 / t.h;
        }

        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            return out.addVertex(cx + scale * invTan * x / z, cy + scale * invTan * y / z, (z - d) * invH);
        }

        @Override
        public void addVertex(float x, float y, float z, int color, float u, float v, int overlay, int light, float nx, float ny, float nz) {
            out.addVertex(cx + scale * invTan * x / z, cy + scale * invTan * y / z, (z - d) * invH, color, u, v, overlay, light, nx, ny, nz);
        }

        @Override
        public VertexConsumer setColor(int red, int green, int blue, int alpha) {
            return out.setColor(red, green, blue, alpha);
        }

        @Override
        public VertexConsumer setUv(float u, float v) {
            return out.setUv(u, v);
        }

        @Override
        public VertexConsumer setUv1(int u, int v) {
            return out.setUv1(u, v);
        }

        @Override
        public VertexConsumer setUv2(int u, int v) {
            return out.setUv2(u, v);
        }

        @Override
        public VertexConsumer setNormal(float x, float y, float z) {
            return out.setNormal(x, y, z);
        }
    }
}
