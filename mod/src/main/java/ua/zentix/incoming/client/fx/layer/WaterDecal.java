package ua.zentix.incoming.client.fx.layer;

import com.mojang.blaze3d.platform.GlConst;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.shaders.FogShape;
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
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.world.level.material.FogType;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import ua.zentix.incoming.client.far.FarView;
import ua.zentix.incoming.client.far.Sight;

import java.nio.ByteBuffer;
import java.util.Locale;

/**
 * Наклейка на воду в слое эффектов: тёмное кольцо фронта ядерной волны — рябь под ветром за фронтом, у эпицентра с пеной
 * (профиль по модели — {@code client/nuclear/NukeWater}). Один проход во весь кадр перед квадратами слоя: место за
 * пикселем — по расстоянию до мира ({@link SceneDepth}: глубина Minecraft и Distant Horizons) и матрицам кадра,
 * вода ли там — гладь (поверхность за пикселем горизонтальна по соседним пикселям: стены, стволы, трава и мобы на той
 * же высоте в блоке — не вода) на высоте верха стоячей воды — 8/9 блока (в прорисовке; вода пишет глубину и в ванили,
 * и под Iris; допуск растёт с ошибкой глубины вдали), дальше прорисовки (LOD DH) — гладь моря или место ниже его, тогда
 * — пересечение луча с его гладью (LOD без глубины воды показывает дно). Шум ряби и пены — от центра кольца. Воду
 * рисует сам Minecraft или шейдерпак, наклейка только темнит её и белит пеной поверх, с той же дымкой воздуха и туманом
 * Minecraft, что у частиц.
 * <p>
 * Профиль кольца — строка текстуры {@link #SAMPLES}×{@link #RINGS}: темнота и пена за фронтом на {@link #sampleAt}
 * блоков (выборки гуще у фронта: там кольцо резкое).
 */
public final class WaterDecal {
    /** Колец в кадре — не больше; выборок профиля. */
    public static final int RINGS = 4, SAMPLES = 64;
    /** Верх стоячей воды над низом блока (у {@code LiquidBlockRenderer} — 8/9). */
    static final double SURFACE = 8.0 / 9.0;
    /**
     * Уровень моря на клиенте: генератора у клиента нет, а {@code Level.getSeaLevel} в 1.21.1 устарел и всегда 63 —
     * море обычного мира (вода до 62, первый воздух над ней — 63).
     */
    public static final int SEA_LEVEL = 63;
    @Nullable
    static ShaderInstance shader;
    private static final double[][] RING = new double[RINGS][4];
    private static final String[] RING_UNIFORMS = {"Ring0", "Ring1", "Ring2", "Ring3"};
    private static final ByteBuffer PIXELS = BufferUtils.createByteBuffer(SAMPLES * RINGS * 4);
    private static final Matrix4f INV_PROJ = new Matrix4f(), INV_VIEW = new Matrix4f();
    private static int count, texture = -1, lastCount;
    private static boolean wanted;

    private WaterDecal() {}

    /** На сколько блоков за фронтом выборка {@code i} профиля длины {@code length}: гуще у фронта. */
    public static double sampleAt(int i, double length) {
        double f = (double) i / (SAMPLES - 1);
        return length * f * f;
    }

    /** Начало кадра: колец ещё нет; нужны ли они (шейдер есть, у измерения небо, камера не в воде). */
    static void begin(@Nullable ClientLevel level, Camera camera) {
        lastCount = count;
        count = 0;
        wanted = shader != null && level != null && level.dimensionType().hasSkyLight() && !level.dimensionType().hasCeiling()
                && camera.getFluidInCamera() == FogType.NONE;
    }

    /** Кольца в этом кадре нужны (иначе профили можно не считать). */
    public static boolean wanted() {
        return wanted && count < RINGS;
    }

    /**
     * Кольцо в кадр: центр (мир, блоки), радиус фронта по воде, длина профиля за ним и сам профиль — темнота воды и пена
     * 0..1 на {@link #sampleAt} блоков за фронтом. False — места нет.
     */
    public static boolean ring(double x, double z, double radius, double length, float[] dark, float[] foam) {
        if (!wanted()) return false;
        int row = count++;
        RING[row][0] = x;
        RING[row][1] = z;
        RING[row][2] = radius;
        RING[row][3] = Math.max(1, length);
        for (int i = 0; i < SAMPLES; i++) {
            int at = (row * SAMPLES + i) * 4;
            PIXELS.put(at, (byte) Math.round(Math.max(0, Math.min(1, dark[i])) * 255));
            PIXELS.put(at + 1, (byte) Math.round(Math.max(0, Math.min(1, foam[i])) * 255));
            PIXELS.put(at + 2, (byte) 0);
            PIXELS.put(at + 3, (byte) 255);
        }
        return true;
    }

    /** Есть что рисовать. */
    static boolean active() {
        return count > 0 && shader != null;
    }

    /**
     * Проход наклейки: до квадратов слоя, после {@code FarDraw.begin} (вид от глаза; здесь — свои матрицы во весь кадр).
     * {@code scene} — текстура расстояний до мира ({@link SceneDepth#update}).
     */
    static void draw(RenderLevelStageEvent e, FarView view, int scene, FxFrame.Fog fog) {
        ShaderInstance s = shader;
        if (s == null || count == 0 || scene < 0) return;
        upload();
        Camera camera = e.getCamera();
        var cam = camera.getPosition();
        RenderSystem.setShader(() -> s);
        RenderSystem.setShaderTexture(0, scene);
        RenderSystem.setShaderTexture(1, texture);
        s.safeGetUniform("InvProj").set(INV_PROJ.set(e.getProjectionMatrix()).invert());
        s.safeGetUniform("InvView").set(INV_VIEW.set(e.getModelViewMatrix()).invert());
        s.safeGetUniform("CamY").set((float) cam.y);
        double sea = SEA_LEVEL - 1 + SURFACE;
        s.safeGetUniform("WaterParams").set((float) (0.8 * view.world()), (float) (sea - cam.y), (float) count, view.ambient());
        s.safeGetUniform("Haze").set((float) (Sight.KOSCHMIEDER / view.range()), fog.end > fog.start ? fog.start : 1e9f,
                fog.end > fog.start ? fog.end : 2e9f, fog.shape == FogShape.CYLINDER ? 1f : 0f);
        s.safeGetUniform("Pixel").set((float) view.pixel());
        for (int i = 0; i < RINGS; i++) {
            double[] r = RING[i];
            s.safeGetUniform(RING_UNIFORMS[i]).set((float) (r[0] - cam.x), (float) (r[1] - cam.z), (float) r[2], (float) r[3]);
        }
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.enableBlend();
        RenderSystem.blendFuncSeparate(GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
                GlStateManager.SourceFactor.ZERO, GlStateManager.DestFactor.ONE);
        BufferBuilder b = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION);
        b.addVertex(-1, -1, 0);
        b.addVertex(1, -1, 0);
        b.addVertex(1, 1, 0);
        b.addVertex(-1, 1, 0);
        MeshData mesh = b.build();
        if (mesh != null) BufferUploader.drawWithShader(mesh);
        RenderSystem.enableDepthTest();
    }

    /** Профили кадра — в текстуру (своя, 64×4, RGBA8: темнота, пена; место — один раз, дальше — только пиксели). */
    private static void upload() {
        boolean fresh = texture < 0;
        if (fresh) {
            texture = GlStateManager._genTexture();
            GlStateManager._bindTexture(texture);
            GlStateManager._texParameter(GlConst.GL_TEXTURE_2D, GlConst.GL_TEXTURE_MIN_FILTER, GlConst.GL_LINEAR);
            GlStateManager._texParameter(GlConst.GL_TEXTURE_2D, GlConst.GL_TEXTURE_MAG_FILTER, GlConst.GL_LINEAR);
            GlStateManager._texParameter(GlConst.GL_TEXTURE_2D, GlConst.GL_TEXTURE_WRAP_S, GlConst.GL_CLAMP_TO_EDGE);
            GlStateManager._texParameter(GlConst.GL_TEXTURE_2D, GlConst.GL_TEXTURE_WRAP_T, GlConst.GL_CLAMP_TO_EDGE);
        }
        GlStateManager._bindTexture(texture);
        GlStateManager._pixelStore(GL11.GL_UNPACK_ROW_LENGTH, 0);
        GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_PIXELS, 0);
        GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_ROWS, 0);
        GlStateManager._pixelStore(GL11.GL_UNPACK_ALIGNMENT, 4);
        PIXELS.position(0);
        if (fresh) {
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, SAMPLES, RINGS, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, PIXELS);
        } else {
            GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, SAMPLES, RINGS, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, PIXELS);
        }
    }

    /** Для строки сценария: колец на воде в прошлом кадре. */
    public static String describe() {
        return String.format(Locale.ROOT, "%d ring(s)%s", lastCount, shader == null ? " (no shader)" : "");
    }
}
