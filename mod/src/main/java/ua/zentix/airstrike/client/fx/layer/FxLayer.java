package ua.zentix.airstrike.client.fx.layer;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.ShaderInstance;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.RegisterShadersEvent;
import org.jetbrains.annotations.Nullable;
import org.joml.FrustumIntersection;
import org.joml.Matrix4f;
import ua.zentix.airstrike.Airstrike;
import ua.zentix.airstrike.client.far.FarRenderer;
import ua.zentix.airstrike.client.far.FarView;
import ua.zentix.airstrike.client.fx.particle.FxPool;
import ua.zentix.airstrike.client.render.DhDepth;
import ua.zentix.airstrike.client.render.FarDraw;

import java.io.IOException;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.Locale;

/**
 * Слой эффектов: дым, пыль, пламя, искры, вспышки ({@link FxPool}) и всё дальнее ({@link FarRenderer}, с плитками
 * дальних моделей) — одним
 * проходом после мира ({@code AFTER_LEVEL}), квадратами, отсортированными от дальних к ближним по настоящему
 * расстоянию ({@link FxQuads}). Ванильный движок частиц так не умеет: в слое одна очередь без сортировки, кто родился
 * позже — тот поверх (дым пусковой в 10 блоках оказывался за столбами попаданий в сотнях блоков), а дальняя картинка
 * после мира ложилась поверх ближнего дыма. Под шейдерами Iris этот этап идёт после проходов шейдерпака, как ядерная
 * картинка: его туман дальнее не съедает.
 * <p>
 * Глубина: аппаратная проверка (рельеф ближе закрывает, запись выключена — прозрачное), и ещё расстояние до мира за
 * пикселем ({@link SceneDepth}, с глубиной Distant Horizons): клуб мягко тает, входя в землю, и пропадает за LOD DH.
 * Смешивание одно — с умноженной альфой ({@code ONE, ONE_MINUS_SRC_ALPHA}): дым закрывает и светит, искры и ореолы
 * только светят. Свет мира — картой освещения и светом огненных шаров ({@link FxLights}), туман Minecraft — снятый
 * до конца кадра (к нему ваниль туман гасит).
 */
public final class FxLayer {
    private static final FxQuads QUADS = new FxQuads();
    private static final FxLights LIGHTS = new FxLights();
    private static final FxFrame.Fog FOG = new FxFrame.Fog();
    private static final Matrix4f VIEW_PROJECTION = new Matrix4f();
    private static final FrustumIntersection FRUSTUM = new FrustumIntersection();
    @Nullable
    private static ShaderInstance shader;
    /**
     * Путь кадра пройден хоть раз с этим шейдером: первая отрисовка связывает программу и заводит буферы драйвера —
     * на ноутбуке это было 35 мс в кадре первого взрыва вдали.
     */
    private static boolean warmed;

    /**
     * Время кадра слоя в потоке отрисовки (сбор, сортировка, отправка) за секунду: сумма, самый долгий; самый долгий
     * из кадров без остановки на сборку мусора и время процессора потока в нём; кадров со сборкой; прошлая секунда, мс.
     */
    private static long frameNanos, frameMax, cleanMax, cleanCpu;
    private static int frames, ticks, collected, lastQuads;
    private static double lastMean, lastMax, lastClean, lastCleanCpu;
    private static int lastCollected;
    private static final ThreadMXBean THREADS = ManagementFactory.getThreadMXBean();
    /** Сборщики, которые останавливают JVM (у ZGC и Shenandoah — «… Pauses»; «… Cycles» идут вместе с программой). */
    private static final GarbageCollectorMXBean[] PAUSES = ManagementFactory.getGarbageCollectorMXBeans().stream()
            .filter(b -> !b.getName().contains("Cycles")).toArray(GarbageCollectorMXBean[]::new);

    private FxLayer() {}

    /** Шина мода: шейдеры слоя и расстояния до мира (и заново при перезагрузке ресурсов). */
    public static void registerShaders(RegisterShadersEvent e) {
        try {
            e.registerShader(new ShaderInstance(e.getResourceProvider(), Airstrike.id("fx"), FxQuads.FORMAT), s -> {
                shader = s;
                warmed = false;
            });
        } catch (IOException ex) {
            shader = null;
            Airstrike.LOG.error("Шейдер слоя эффектов не загрузился: дыма, огня и дальней картинки не будет", ex);
        }
        try {
            e.registerShader(new ShaderInstance(e.getResourceProvider(), Airstrike.id("fx_depth"), DefaultVertexFormat.POSITION), s -> SceneDepth.shader = s);
        } catch (IOException ex) {
            SceneDepth.shader = null;
            Airstrike.LOG.error("Шейдер расстояния до мира не загрузился: дым без мягких краёв и не прячется за LOD Distant Horizons", ex);
        }
    }

    /** Раз в тик клиента (не на паузе): секундная сводка времени кадра. */
    public static void tick() {
        if (++ticks >= 20) {
            lastMean = frames > 0 ? frameNanos / 1e6 / frames : 0;
            lastMax = frameMax / 1e6;
            lastClean = cleanMax / 1e6;
            lastCleanCpu = cleanCpu / 1e6;
            lastCollected = collected;
            frameNanos = frameMax = cleanMax = cleanCpu = 0;
            frames = ticks = collected = 0;
        }
    }

    public static void render(RenderLevelStageEvent e) {
        // этап зовёт сам Minecraft (не отрисовка секций, которую подменяет Sodium); туман — уже туман мира
        if (e.getStage() == RenderLevelStageEvent.Stage.AFTER_ENTITIES) {
            captureFog();
            return;
        }
        if (e.getStage() != RenderLevelStageEvent.Stage.AFTER_LEVEL) return;
        try {
            draw(e);
        } finally {
            DhDepth.endFrame();
        }
    }

    /** Туман мира, как его настроил кадр (вода, лава, Незер, слепота, пепел ядерки): к концу кадра ваниль его гасит. */
    private static void captureFog() {
        FOG.start = RenderSystem.getShaderFogStart();
        FOG.end = RenderSystem.getShaderFogEnd();
        FOG.shape = RenderSystem.getShaderFogShape();
        System.arraycopy(RenderSystem.getShaderFogColor(), 0, FOG.color, 0, 4);
    }

    private static void draw(RenderLevelStageEvent e) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        ShaderInstance s = shader;
        int atlas = FxAtlas.INSTANCE.id();
        if (level == null || s == null || atlas < 0) return;
        long start = System.nanoTime(), cpu = cpuNanos(), pauses = pauses();
        FarView view = FarRenderer.view(e, mc, level);
        boolean warmup = !warmed;
        // атлас дальних моделей со всеми сетками в видеопамяти — до сбора кадра (прогрев начинает атлас заново)
        if (warmup) FarRenderer.warmup();
        FRUSTUM.set(VIEW_PROJECTION.set(e.getProjectionMatrix()).mul(e.getModelViewMatrix()));
        QUADS.begin(view.left(), view.up());
        LIGHTS.begin();
        QUADS.nearestTexels(true);
        FxPool.INSTANCE.collect(new FxFrame(view, FRUSTUM, FOG), QUADS, view.partial());
        QUADS.nearestTexels(false);
        FarRenderer.collect(view, QUADS, LIGHTS);
        if (warmup) {
            // невидимый квадрат: ни света, ни заслона — кадр проходит весь путь до первого настоящего
            QUADS.quad(16);
            for (int i = 0; i < 4; i++) QUADS.vertex(i % 2, i / 2, -16, 0, 0, 0, 0, 0, 0, 1, 1, LightTexture.FULL_BRIGHT, 0);
        }
        lastQuads = QUADS.size();
        if (QUADS.size() == 0) return;
        int models = FarRenderer.renderModels();
        int scene = SceneDepth.update(e.getProjectionMatrix());
        LightTexture light = mc.gameRenderer.lightTexture();
        FarDraw.begin(e);
        try {
            RenderSystem.setShader(() -> s);
            RenderSystem.setShaderTexture(0, atlas);
            RenderSystem.setShaderTexture(1, scene >= 0 ? scene : atlas);
            RenderSystem.setShaderTexture(3, models > 0 ? models : atlas);
            light.turnOnLightLayer();
            s.safeGetUniform("FxFogColor").set(FOG.color[0], FOG.color[1], FOG.color[2], FOG.color[3]);
            s.safeGetUniform("FxScene").set(scene >= 0 ? 1f : 0f);
            s.safeGetUniform("FxBalls").set(LIGHTS.balls);
            s.safeGetUniform("FxBallLight").set(LIGHTS.light);
            RenderSystem.enableBlend();
            RenderSystem.blendFuncSeparate(GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
                    GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
            RenderSystem.depthMask(false);
            RenderSystem.enableDepthTest();
            RenderSystem.disableCull();
            QUADS.draw();
        } finally {
            light.turnOffLightLayer();
            FarDraw.end();
        }
        long spent = System.nanoTime() - start;
        if (warmup) {
            warmed = true;
            Airstrike.LOG.info("Слой эффектов: прогрев {} мс", String.format(Locale.ROOT, "%.1f", spent / 1e6));
            return;
        }
        frameNanos += spent;
        frameMax = Math.max(frameMax, spent);
        frames++;
        if (pauses() != pauses) {
            collected++;
        } else if (spent > cleanMax) {
            cleanMax = spent;
            cleanCpu = cpuNanos() - cpu;
        }
    }

    /** Время кадра слоя за прошлую секунду — для строки сценария. */
    public static String describeTiming() {
        return String.format(Locale.ROOT, "квадратов %d, %.3f мс (самый долгий за секунду %.3f; без сборки мусора %.3f, процессор %.3f; кадров со сборкой %d), глубина: %s",
                lastQuads, lastMean, lastMax, lastClean, Math.max(0, lastCleanCpu), lastCollected, SceneDepth.describe());
    }

    /** Выход из мира или смена измерения. */
    public static void reset() {
        frameNanos = frameMax = cleanMax = cleanCpu = 0;
        frames = ticks = collected = lastCollected = lastQuads = 0;
        lastMean = lastMax = lastClean = lastCleanCpu = 0;
    }

    /** Время процессора потока отрисовки, нс (−1 — JVM не умеет: тогда в строке 0). */
    private static long cpuNanos() {
        return THREADS.isCurrentThreadCpuTimeSupported() ? THREADS.getCurrentThreadCpuTime() : -1;
    }

    /** Сколько раз сборка мусора останавливала JVM с запуска. */
    private static long pauses() {
        long n = 0;
        for (GarbageCollectorMXBean b : PAUSES) n += Math.max(0, b.getCollectionCount());
        return n;
    }
}
