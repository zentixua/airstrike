package ua.zentix.incoming.client.fx.layer;

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
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.client.far.FarRenderer;
import ua.zentix.incoming.client.far.FarView;
import ua.zentix.incoming.client.fx.particle.FxPool;
import ua.zentix.incoming.client.nuclear.NukePall;
import ua.zentix.incoming.client.render.DhDepth;
import ua.zentix.incoming.client.render.FarDraw;

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
 * позже — тот поверх (дым пусковой в 10 блоках оказывался бы за столбами попаданий в сотнях блоков), а дальняя
 * картинка после мира ложилась бы поверх ближнего дыма. Под шейдерами Iris этот этап идёт после проходов шейдерпака,
 * как ядерная картинка: его туман дальнее не съедает.
 * <p>
 * Глубина: аппаратная проверка (рельеф ближе закрывает, запись выключена — прозрачное), и ещё расстояние до мира за
 * пикселем ({@link SceneDepth}, с глубиной Distant Horizons): клуб мягко тает, входя в землю, и пропадает за LOD DH.
 * Блик и вуаль у глаза — по видимой в кадре доле своего источника ({@link SourceProbes}, по тому же расстоянию до мира).
 * Смешивание одно — с умноженной альфой ({@code ONE, ONE_MINUS_SRC_ALPHA}): дым закрывает и светит, искры и ореолы
 * только светят. Свет мира — картой освещения и светом огненных шаров ({@link FxLights}), туман Minecraft — снятый
 * до конца кадра (к нему ваниль туман гасит).
 */
public final class FxLayer {
    private static final FxQuads QUADS = new FxQuads();
    private static final FxLights LIGHTS = new FxLights();
    private static final SourceProbes PROBES = new SourceProbes();
    private static final FxFrame.Fog FOG = new FxFrame.Fog();
    private static final Matrix4f VIEW_PROJECTION = new Matrix4f(), VIEW = new Matrix4f();
    private static final FrustumIntersection FRUSTUM = new FrustumIntersection();
    @Nullable
    private static ShaderInstance shader;
    /**
     * Путь кадра пройден хоть раз с этим шейдером: первая отрисовка связывает программу и заводит буферы драйвера —
     * на ноутбуке это 35 мс в кадре первого взрыва вдали.
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
            e.registerShader(new ShaderInstance(e.getResourceProvider(), Incoming.id("fx"), FxQuads.FORMAT), s -> {
                shader = s;
                warmed = false;
            });
        } catch (IOException ex) {
            shader = null;
            Incoming.LOG.error("Effects layer shader failed to load: no smoke, fire, mushroom cloud or far rendering", ex);
        }
        try {
            e.registerShader(new ShaderInstance(e.getResourceProvider(), Incoming.id("fx_depth"), DefaultVertexFormat.POSITION), s -> SceneDepth.shader = s);
        } catch (IOException ex) {
            SceneDepth.shader = null;
            Incoming.LOG.error("Scene depth shader failed to load: smoke has no soft edges and is not hidden by Distant Horizons LODs", ex);
        }
        try {
            e.registerShader(new ShaderInstance(e.getResourceProvider(), Incoming.id("fx_probe"), SourceProbes.FORMAT), s -> SourceProbes.shader = s);
        } catch (IOException ex) {
            SourceProbes.shader = null;
            Incoming.LOG.error("Source probe shader failed to load: glare of hidden distant fire is not dimmed", ex);
        }
        try {
            e.registerShader(new ShaderInstance(e.getResourceProvider(), Incoming.id("fx_water"), DefaultVertexFormat.POSITION), s -> WaterDecal.shader = s);
        } catch (IOException ex) {
            WaterDecal.shader = null;
            Incoming.LOG.error("Water decal shader failed to load: no dark blast ring on water", ex);
        }
        try {
            e.registerShader(new ShaderInstance(e.getResourceProvider(), Incoming.id("fx_pall"), DefaultVertexFormat.POSITION), s -> PallPass.march = s);
            e.registerShader(new ShaderInstance(e.getResourceProvider(), Incoming.id("fx_pall_resolve"), DefaultVertexFormat.POSITION), s -> PallPass.resolve = s);
            e.registerShader(new ShaderInstance(e.getResourceProvider(), Incoming.id("fx_pall_composite"), DefaultVertexFormat.POSITION), s -> PallPass.composite = s);
        } catch (IOException ex) {
            PallPass.march = PallPass.resolve = PallPass.composite = null;
            Incoming.LOG.error("Nuclear dust pall shaders failed to load: no dust pall", ex);
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
            PallPass.second();
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
        FRUSTUM.set(FarDraw.viewProjection(e, view.camera(), VIEW_PROJECTION));
        QUADS.begin(view.left(), view.up());
        WaterDecal.begin(level, e.getCamera());
        LIGHTS.begin();
        PROBES.begin();
        QUADS.nearestTexels(true);
        FxPool.INSTANCE.collect(new FxFrame(view, FRUSTUM, FOG), QUADS, view.partial());
        QUADS.nearestTexels(false);
        FarRenderer.collect(view, QUADS, LIGHTS, PROBES);
        if (warmup) {
            // невидимый квадрат: ни света, ни заслона — кадр проходит весь путь до первого настоящего, с пробой источника
            // перед глазом (проба считается, квадрат её берёт)
            QUADS.seenBy(PROBES.add(view.forward().x() * 16, view.forward().y() * 16, view.forward().z() * 16, 1));
            QUADS.quad(16);
            for (int i = 0; i < 4; i++) QUADS.vertex(i % 2, i / 2, -16, 0, 0, 0, 0, 0, 0, 1, 1, LightTexture.FULL_BRIGHT, 0);
            QUADS.seenBy(0);
        }
        PallPass.Frame pall = NukePall.frame(level, view.camera(), view.partial(), FRUSTUM);
        lastQuads = QUADS.size();
        if (QUADS.size() == 0 && pall == null && !WaterDecal.active()) {
            PallPass.reset();
            return;
        }
        int models = FarRenderer.renderModels();
        int scene = SceneDepth.update(e.getProjectionMatrix());
        int seen = PROBES.update(scene, FarDraw.view(e, view.camera(), VIEW), e.getProjectionMatrix(), view.pixel());
        LightTexture light = mc.gameRenderer.lightTexture();
        FarDraw.begin(e, view.camera());
        try {
            // наклейка на воду — под пеленой и квадратами: пыль, брызги и дым над водой ложатся на неё
            if (WaterDecal.active()) WaterDecal.draw(e, view, scene, FOG);
            // пелена — до квадратов: они ложатся на неё со своей долей пелены перед ними (fx.vsh)
            boolean pallDrawn = pall != null && scene >= 0 && PallPass.draw(pall, scene, VIEW, e.getProjectionMatrix(), view.camera());
            if (!pallDrawn) PallPass.reset();
            PallPass.Frame front = pallDrawn ? pall : PallPass.empty();
            RenderSystem.setShader(() -> s);
            RenderSystem.setShaderTexture(0, atlas);
            RenderSystem.setShaderTexture(1, scene >= 0 ? scene : atlas);
            RenderSystem.setShaderTexture(3, models > 0 ? models : atlas);
            RenderSystem.setShaderTexture(4, seen >= 0 ? seen : atlas);
            RenderSystem.setShaderTexture(5, pallDrawn ? PallPass.table() : atlas);
            RenderSystem.setShaderTexture(6, pallDrawn ? PallPass.frame() : atlas);
            // карты огненного шторма: свет пожаров снизу на квадратах слоя
            int fire0 = pallDrawn ? PallPass.fireMap(0) : -1, fire1 = pallDrawn ? PallPass.fireMap(1) : -1;
            RenderSystem.setShaderTexture(7, fire0 >= 0 ? fire0 : atlas);
            RenderSystem.setShaderTexture(8, fire1 >= 0 ? fire1 : atlas);
            light.turnOnLightLayer();
            s.safeGetUniform("FxFogColor").set(FOG.color[0], FOG.color[1], FOG.color[2], FOG.color[3]);
            s.safeGetUniform("FxScene").set(scene >= 0 ? 1f : 0f);
            s.safeGetUniform("FxProbes").set(seen >= 0 ? 1f : 0f);
            s.safeGetUniform("FxBalls").set(LIGHTS.balls);
            s.safeGetUniform("FxBallLight").set(LIGHTS.light);
            s.safeGetUniform("PallSlot0").set(front.slot0);
            s.safeGetUniform("PallSlot1").set(front.slot1);
            s.safeGetUniform("PallMin").set(front.min.x, front.min.y, front.min.z);
            s.safeGetUniform("PallMax").set(front.max.x, front.max.y, front.max.z);
            s.safeGetUniform("PallAmbient").set(front.ambient.x, front.ambient.y, front.ambient.z, front.ambient.w);
            s.safeGetUniform("PallFire0").set(fire0 >= 0 ? front.fire0.params : PallPass.empty().fire0.params);
            s.safeGetUniform("PallFire1").set(fire1 >= 0 ? front.fire1.params : PallPass.empty().fire1.params);
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
            Incoming.LOG.info("Effects layer: warm-up {} ms", String.format(Locale.ROOT, "%.1f", spent / 1e6));
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
        return String.format(Locale.ROOT, "quads %d, %.3f ms (longest in the second %.3f; without GC %.3f, CPU %.3f; frames with GC %d), depth: %s",
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
