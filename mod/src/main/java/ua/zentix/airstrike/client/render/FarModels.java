package ua.zentix.airstrike.client.render;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.shaders.Uniform;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.world.inventory.InventoryMenu;
import net.neoforged.neoforge.client.event.RegisterShadersEvent;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL30;
import ua.zentix.airstrike.Airstrike;

import java.io.IOException;
import java.nio.ByteBuffer;
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
 * Сетки деталей лежат в видеопамяти ({@link VertexBuffer}, как небо и чанки у ванили): каждая собирается и грузится
 * один раз (при входе в мир — {@link #warmup}, заново — после перезагрузки ресурсов), а кадр на деталь только ставит
 * её положение (матрицы шейдера) и зовёт отрисовку. Вершины на процессоре каждый кадр стоили на ноутбуке до 1 мс на
 * полный B-2 и росли с числом моделей. Атлас рисуется своим шейдером ({@code shaders/core/far_model}) в
 * {@code AFTER_LEVEL}: Iris заменяет чужие шейдеры только пока рисует мир. Плитки, детали, сетки и атлас живут между
 * кадрами; на кадр — только мелкие короткоживущие объекты анимаций моделей.
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
    private static final WeaponModels.Mesh[] MESHES = WeaponModels.Mesh.values();
    private static final Matrix4f IDENTITY = new Matrix4f();

    @Nullable
    private static ShaderInstance shader;

    @Nullable
    private TextureTarget atlas;
    /** Сетки деталей в видеопамяти: по две на деталь (полная, упрощённая) и сетка, из которой собрана каждая. */
    private final VertexBuffer[] meshes = new VertexBuffer[2 * MESHES.length];
    private final QuadMesh[] uploaded = new QuadMesh[2 * MESHES.length];
    private final List<Tile> tiles = new ArrayList<>();
    private int count;
    /** Полка, которую заполняем: левый край свободного места, её низ и высота. */
    private int shelfX, shelfY, shelfH;
    /** Детали моделей кадра. */
    private final List<Part> parts = new ArrayList<>();
    private int partCount;
    /** Плитка, детали которой сейчас записываются. */
    @Nullable
    private Tile current;
    private final WeaponModels.Parts record = this::record;
    private final PoseStack poses = new PoseStack();
    private final Quaternionf rotation = new Quaternionf();
    private final int[] viewport = new int[4];
    private final ByteBuffer colorWrite = BufferUtils.createByteBuffer(4);
    private final Runnable clearAndDraw = () -> {
        clear(rowsUsed());
        draw();
    }, warm = () -> {
        clear(ATLAS);
        draw();
        clear(ATLAS);
    };

    /** Одна модель кадра и её плитка. */
    static final class Tile {
        final ProjectilePose pose = new ProjectilePose();
        @Nullable
        WeaponModels.Look look;
        /** Модель → оси взгляда на неё: вправо, вверх, к ней (камера в начале координат). */
        final Matrix4f view = new Matrix4f();
        /** Оси взгляда → плитка атласа: перспектива из глаза, углы плитки — ±tan, глубина — шар модели. */
        final Matrix4f projection = new Matrix4f();
        /** Середина плитки в атласе, текселей, и её сторона. */
        int x, y, size;

        /**
         * Плитка с серединой (x, y) и стороной size для шара радиуса h на дальности d (виден под углом с тангенсом tan).
         * Перспектива: x/z, y/z в тангенсах — в квадрат плитки; глубина от d − h до d + h — на всю глубину атласа.
         */
        void place(int x, int y, int size, float d, float h, float tan) {
            this.x = x;
            this.y = y;
            this.size = size;
            float a = (float) size / ATLAS / tan, cx = 2f * x / ATLAS - 1, cy = 2f * y / ATLAS - 1, near = d - h, far = d + h;
            projection.set(a, 0, 0, 0, 0, a, 0, 0, cx, cy, (far + near) / (far - near), 1, 0, 0, -2 * far * near / (far - near), 0);
        }
    }

    /** Деталь кадра: какая сетка, в какой плитке, где (в осях взгляда), как повёрнута её нормаль (в осях мира), прозрачность. */
    private static final class Part {
        @Nullable
        WeaponModels.Mesh mesh;
        @Nullable
        VertexBuffer buffer;
        @Nullable
        Tile tile;
        final Matrix4f pose = new Matrix4f();
        final Matrix3f normal = new Matrix3f();
        float alpha;
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
        Tile t = tile();
        t.look = look;
        t.pose.copy(pose);
        t.place(shelfX + PAD + side / 2, shelfY + PAD + side / 2, side, (float) d, (float) h, (float) tan);
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

    private Tile tile() {
        if (count == tiles.size()) tiles.add(new Tile());
        return tiles.get(count++);
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

    /**
     * Всё заранее, при входе в мир и после перезагрузки ресурсов: атлас, сетки всех деталей (обе копии) в видеопамяти
     * и по одной отрисовке каждой — драйвер собирает программу и массивы вершин здесь, а не в кадре, где снаряд вдали
     * появился впервые (на ноутбуке такой кадр стоил 5 мс процессора).
     */
    public void warmup() {
        if (shader == null) return;
        begin();
        Tile t = tile();
        t.place(PAD + MIN_TILE / 2, PAD + MIN_TILE / 2, MIN_TILE, 10, 1, 0.1f);
        t.view.identity().setTranslation(0, 0, 10);
        current = t;
        poses.last().pose().set(t.view);
        poses.last().normal().identity();
        for (WeaponModels.Mesh m : MESHES) {
            record(m, poses, false, 1);
            if (m.lod) record(m, poses, true, 1);
        }
        current = null;
        count = 0;
        withAtlasBound(warm);
    }

    /**
     * Нарисовать модели кадра в атлас и уменьшить его мип-уровнями. Вызывается после сбора кадра и до отрисовки
     * квадратов; кадровый буфер и область вывода, которые были до вызова, возвращаются.
     */
    public void render() {
        if (count == 0 || shader == null) return;
        for (int i = 0; i < count; i++) {
            Tile t = tiles.get(i);
            current = t;
            PoseStack.Pose root = poses.last();
            root.pose().set(t.view);
            root.normal().identity();
            // нормали — в осях мира: в них светят направленные света Lighting, как у сущностей вблизи
            poses.pushPose();
            poses.mulPose(t.pose.rotation(rotation));
            t.look.render(t.pose, poses, record);
            poses.popPose();
            t.look = null;
        }
        current = null;
        withAtlasBound(clearAndDraw);
    }

    /** Деталь модели плитки {@link #current} в текущем положении pose (сетка — в видеопамять, если её там ещё нет). */
    private void record(WeaponModels.Mesh mesh, PoseStack pose, boolean coarse, float alpha) {
        VertexBuffer buffer = buffer(mesh, coarse);
        if (buffer == null) return;
        if (partCount == parts.size()) parts.add(new Part());
        Part p = parts.get(partCount++);
        p.mesh = mesh;
        p.buffer = buffer;
        p.tile = current;
        p.pose.set(pose.last().pose());
        p.normal.set(pose.last().normal());
        p.alpha = alpha;
    }

    /**
     * Сетка детали в видеопамяти: собирается при первой отрисовке и заново, когда сетка детали собрана заново
     * (перезагрузка ресурсов); свет — свет неба, ярче — запечённый ({@code glow}). {@code null} — сетка пуста.
     */
    @Nullable
    private VertexBuffer buffer(WeaponModels.Mesh mesh, boolean coarse) {
        int i = 2 * mesh.ordinal() + (coarse && mesh.lod ? 1 : 0);
        QuadMesh source = mesh.mesh(coarse);
        if (uploaded[i] != source) {
            uploaded[i] = source;
            try (ByteBufferBuilder bytes = new ByteBufferBuilder(Math.max(256, source.bakedBytes()))) {
                MeshData data = source.bake(bytes, LightTexture.FULL_SKY);
                if (data == null) {
                    if (meshes[i] != null) meshes[i].close();
                    meshes[i] = null;
                } else {
                    if (meshes[i] == null) meshes[i] = new VertexBuffer(VertexBuffer.Usage.STATIC);
                    meshes[i].bind();
                    meshes[i].upload(data);
                    VertexBuffer.unbind();
                }
            }
        }
        return meshes[i];
    }

    private void withAtlasBound(Runnable work) {
        int drawFbo = GlStateManager.getBoundFramebuffer(), readFbo = GlStateManager._getInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
        // маски записи — какими их оставил тот, кто рисовал до нас: очистка атласа включает обе (clear)
        boolean depthWrite = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
        GL11.glGetBooleanv(GL11.GL_COLOR_WRITEMASK, colorWrite);
        try {
            if (atlas == null) create();
            atlas.bindWrite(false);
            GlStateManager._viewport(0, 0, ATLAS, ATLAS);
            work.run();
            RenderSystem.activeTexture(GL13.GL_TEXTURE0);
            RenderSystem.bindTexture(atlas.getColorTextureId());
            GL30.glGenerateMipmap(GL11.GL_TEXTURE_2D);
        } finally {
            partCount = 0;
            RenderSystem.depthMask(depthWrite);
            RenderSystem.colorMask(colorWrite.get(0) != 0, colorWrite.get(1) != 0, colorWrite.get(2) != 0, colorWrite.get(3) != 0);
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
    }

    /** Строк атласа, занятых плитками кадра. */
    private int rowsUsed() {
        return Math.max(PAD, Math.min(ATLAS, shelfY + shelfH));
    }

    /**
     * Очистить нижние rows строк атласа (прозрачным; глубина — дальше всего). Очистка слушается масок записи, а Iris
     * с шейдерпаком оставляет к {@code AFTER_LEVEL} запись глубины выключенной ({@code FinalPassRenderer.renderFinalPass}):
     * глубина атласа копилась из кадра в кадр, и модель в движении рвалась на куски.
     */
    private static void clear(int rows) {
        RenderSystem.colorMask(true, true, true, true);
        RenderSystem.depthMask(true);
        RenderSystem.enableScissor(0, 0, ATLAS, rows);
        GlStateManager._clearColor(0, 0, 0, 0);
        GlStateManager._clearDepth(1);
        GlStateManager._clear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT, Minecraft.ON_OSX);
        RenderSystem.disableScissor();
    }

    /** Детали кадра — в атлас: сперва непрозрачные с глубиной, потом размытые диски винтов поверх, с умноженной альфой. */
    private void draw() {
        ShaderInstance s = shader;
        if (s == null || partCount == 0) return;
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level != null && level.effects().constantAmbientLight()) Lighting.setupNetherLevel();
        else Lighting.setupLevel();
        mc.gameRenderer.lightTexture().turnOnLightLayer();
        RenderSystem.setShaderTexture(0, InventoryMenu.BLOCK_ATLAS);
        RenderSystem.disableCull();
        RenderSystem.enableDepthTest();
        RenderSystem.depthFunc(GL11.GL_LEQUAL);
        RenderSystem.disableBlend();
        RenderSystem.depthMask(true);
        // как ванильные чанки: программа и текстуры — один раз, на деталь — её матрицы и вызов отрисовки
        s.setDefaultUniforms(VertexFormat.Mode.QUADS, IDENTITY, IDENTITY, mc.getWindow());
        s.apply();
        Uniform normal = s.getUniform("NormalMat");
        pass(s, normal, false);
        RenderSystem.enableBlend();
        RenderSystem.blendFuncSeparate(GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
                GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
        RenderSystem.depthMask(false);
        pass(s, normal, true);
        s.clear();
        VertexBuffer.unbind();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableBlend();
        RenderSystem.enableCull();
        mc.gameRenderer.lightTexture().turnOffLightLayer();
    }

    private void pass(ShaderInstance s, @Nullable Uniform normal, boolean translucent) {
        Tile tile = null;
        for (int i = 0; i < partCount; i++) {
            Part p = parts.get(i);
            if (p.mesh.translucent != translucent) continue;
            if (p.tile != tile) {
                tile = p.tile;
                uniform(s.PROJECTION_MATRIX, tile.projection);
            }
            uniform(s.MODEL_VIEW_MATRIX, p.pose);
            if (normal != null) {
                normal.set(p.normal);
                normal.upload();
            }
            if (s.COLOR_MODULATOR != null) {
                s.COLOR_MODULATOR.set(1, 1, 1, p.alpha);
                s.COLOR_MODULATOR.upload();
            }
            p.buffer.bind();
            p.buffer.draw();
        }
    }

    private static void uniform(@Nullable Uniform u, Matrix4f m) {
        if (u == null) return;
        u.set(m);
        u.upload();
    }
}
