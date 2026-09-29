package ua.zentix.airstrike.client.render;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.InventoryMenu;
import net.neoforged.neoforge.client.event.RegisterRenderBuffersEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import ua.zentix.airstrike.Airstrike;

import java.util.List;

/**
 * Слои снарядов и пусковых со своими буферами. Состояния — те же, что у ванильных {@code entityCutoutNoCull},
 * {@code entityTranslucent} и {@code eyes} (те же шейдеры: под Iris их, как у любой сущности, заменяют программы
 * шейдерпака), а экземпляры свои — чтобы завести им буферы ({@link RegisterRenderBuffersEvent}), не занимая ванильные
 * слои, которые может зарегистрировать и другой мод.
 * <p>
 * Ванильные слои сущностей без своего буфера делят один общий, и каждая смена слоя рисует накопленное: шахед (корпус,
 * диск винта, факел, ореол) уходил в несколько вызовов отрисовки, а залп — в сотни. Свой буфер копит слой со всех
 * снарядов и пусковых кадра; рисуется он сразу после сущностей ({@link #afterEntities}), там же, где ваниль дорисовывает
 * слои сущностей по атласу блоков, — до полупрозрачного мира и частиц. Под Iris буферы сущностей собирает сам Iris.
 */
public final class WeaponRenderTypes extends RenderType {
    /** Модели: непрозрачные, без отсечения задних граней (плоские детали сеток — одна грань). */
    public static final RenderType MODEL = create("airstrike_model", DefaultVertexFormat.NEW_ENTITY, VertexFormat.Mode.QUADS,
            1 << 18, true, false, CompositeState.builder()
                    .setShaderState(RENDERTYPE_ENTITY_CUTOUT_NO_CULL_SHADER)
                    .setTextureState(new TextureStateShard(InventoryMenu.BLOCK_ATLAS, false, false))
                    .setTransparencyState(NO_TRANSPARENCY)
                    .setCullState(NO_CULL)
                    .setLightmapState(LIGHTMAP)
                    .setOverlayState(OVERLAY)
                    .createCompositeState(true));

    /** Полупрозрачные детали моделей: размытые диски винтов. */
    public static final RenderType MODEL_TRANSLUCENT = create("airstrike_model_translucent", DefaultVertexFormat.NEW_ENTITY,
            VertexFormat.Mode.QUADS, 1 << 14, true, true, CompositeState.builder()
                    .setShaderState(RENDERTYPE_ENTITY_TRANSLUCENT_SHADER)
                    .setTextureState(new TextureStateShard(InventoryMenu.BLOCK_ATLAS, false, false))
                    .setTransparencyState(TRANSLUCENT_TRANSPARENCY)
                    .setCullState(NO_CULL)
                    .setLightmapState(LIGHTMAP)
                    .setOverlayState(OVERLAY)
                    .createCompositeState(true));

    /** Факел двигателя ({@link PlumeRenderer}): сложение, как «глаза» паука — светится и ночью. */
    public static final RenderType PLUME = glow("plume", Airstrike.id("textures/fx/plume.png"));
    public static final RenderType PLUME_DIAMONDS = glow("plume_diamonds", Airstrike.id("textures/fx/plume_diamonds.png"));
    public static final RenderType PLUME_HALO = glow("plume_halo", Airstrike.id("textures/fx/halo.png"));

    private static final List<RenderType> ALL = List.of(MODEL, MODEL_TRANSLUCENT, PLUME, PLUME_DIAMONDS, PLUME_HALO);

    private static RenderType glow(String name, ResourceLocation texture) {
        return create("airstrike_" + name, DefaultVertexFormat.NEW_ENTITY, VertexFormat.Mode.QUADS, 1 << 14, false, true,
                CompositeState.builder()
                        .setShaderState(RENDERTYPE_EYES_SHADER)
                        .setTextureState(new TextureStateShard(texture, false, false))
                        .setTransparencyState(ADDITIVE_TRANSPARENCY)
                        .setWriteMaskState(COLOR_WRITE)
                        .createCompositeState(false));
    }

    /** Только ради доступа к состояниям {@link net.minecraft.client.renderer.RenderStateShard}: экземпляров нет. */
    private WeaponRenderTypes(String name, VertexFormat format, VertexFormat.Mode mode, int bufferSize, boolean crumbling,
                              boolean sort, Runnable setup, Runnable clear) {
        super(name, format, mode, bufferSize, crumbling, sort, setup, clear);
        throw new UnsupportedOperationException();
    }

    public static void registerBuffers(RegisterRenderBuffersEvent e) {
        for (RenderType t : ALL) e.registerRenderBuffer(t);
    }

    public static void afterEntities(RenderLevelStageEvent e) {
        if (e.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES) return;
        MultiBufferSource.BufferSource buffers = Minecraft.getInstance().renderBuffers().bufferSource();
        for (RenderType t : ALL) buffers.endBatch(t);
    }
}
