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
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.client.far.FarView;
import ua.zentix.incoming.client.far.Sight;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.ByteBuffer;
import java.util.Locale;

/**
 * Наклейка на воду в слое эффектов: тёмное кольцо фронта ядерной волны — рябь под ветром за фронтом, у эпицентра с пеной
 * (профиль по модели — {@code client/nuclear/NukeWater}). Один проход во весь кадр перед квадратами слоя: место за
 * пикселем — по расстоянию до мира ({@link SceneDepth}: глубина Minecraft и Distant Horizons) и матрицам кадра,
 * вода ли там — гладь (поверхность за пикселем горизонтальна, и соседние пиксели на той же высоте: стены, стволы,
 * трава, мобы и края предметов перед небом на той же высоте в блоке — не вода) на высоте верха стоячей воды — 8/9 блока
 * (в прорисовке; вода пишет глубину и в ванили, и под Iris; допуск растёт с ошибкой глубины вдали, под шейдерпаком —
 * ещё на его волны и дрожание кадра, {@link #PACK_DROP}, {@link #PACK_STEP}, {@link #PACK_JITTER}), дальше прорисовки (LOD DH) — гладь моря
 * или место ниже его, тогда — пересечение луча с его гладью (LOD без глубины воды показывает дно). Шум ряби и пены —
 * у каждого кольца свой, от его центра. Воду рисует сам Minecraft или шейдерпак, наклейка только темнит её и белит
 * пеной поверх, с той же дымкой воздуха и туманом Minecraft, что у частиц.
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
    /**
     * Под шейдерпаком гладь ниже 8/9 на его волны, блоков: Complementary Reimagined (у хоста) по умолчанию опускает
     * верх воды вершинами ({@code WAVING_WATER_VERTEX}: −0,05 ± 0,025 под открытым небом, −0,05 под крышей), с запасом.
     */
    static final float PACK_DROP = 0.08f;
    /** Под шейдерпаком гладь не плоская: перепад от вершины к соседней, блоков (у Complementary — до 0,05). */
    static final float PACK_STEP = 0.05f;
    /**
     * Под шейдерпаком кадр дрожит для TAA, пикселей: глубина записана лучом, сдвинутым на долю пикселя, а наклейка читает
     * её лучом через середину пикселя — высота места ошибается на сдвиг × расстояние. У Complementary по умолчанию
     * ({@code TAA_JITTER} 1) — до 0,055 пикселя.
     */
    static final float PACK_JITTER = 0.06f;
    @Nullable
    static ShaderInstance shader;
    private static final double[][] RING = new double[RINGS][4];
    private static final String[] RING_UNIFORMS = {"Ring0", "Ring1", "Ring2", "Ring3"};
    private static final ByteBuffer PIXELS = BufferUtils.createByteBuffer(SAMPLES * RINGS * 4);
    private static final Matrix4f INV_PROJ = new Matrix4f(), INV_VIEW = new Matrix4f();
    private static int count, texture = -1, lastCount;
    private static boolean wanted, packChecked;
    /** {@code IrisApi.isShaderPackInUse} (по рефлексии: мод от Iris не зависит); null — Iris нет. */
    @Nullable
    private static MethodHandle packInUse;

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
        boolean pack = shaderPack();
        s.safeGetUniform("Pack").set(pack ? PACK_DROP : 0f, pack ? PACK_STEP : 0f, pack ? PACK_JITTER : 0f);
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

    /** Мир сейчас рисует шейдерпак Iris. */
    private static boolean shaderPack() {
        if (!packChecked) {
            packChecked = true;
            packInUse = irisPackInUse();
        }
        if (packInUse == null) return false;
        try {
            return (boolean) packInUse.invokeExact();
        } catch (Throwable t) {
            packInUse = null;
            Incoming.LOG.warn("Iris: shader pack check failed, the water decal assumes no shader pack", t);
            return false;
        }
    }

    @Nullable
    private static MethodHandle irisPackInUse() {
        if (!ModList.get().isLoaded("iris")) return null;
        try {
            Class<?> api = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
            Object iris = api.getMethod("getInstance").invoke(null);
            return MethodHandles.publicLookup().findVirtual(api, "isShaderPackInUse", MethodType.methodType(boolean.class)).bindTo(iris);
        } catch (ReflectiveOperationException | LinkageError e) {
            Incoming.LOG.warn("Iris: no shader pack API, the water decal assumes no shader pack", e);
            return null;
        }
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
