package ua.zentix.incoming.scenario.trailer;

import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.enums.rendering.EDhApiRenderPass;
import com.seibel.distanthorizons.api.methods.events.DhApiEventRegister;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiBeforeRenderPassEvent;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiColorDepthTextureCreatedEvent;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiEventParam;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiRenderParam;
import com.seibel.distanthorizons.api.objects.DhApiResult;
import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import ua.zentix.incoming.Incoming;

/**
 * С какой глубиной DH рисует непрозрачный LOD в кадре (класс грузится, только если DH стоит): перед проходом — текстура
 * глубины кадра, в который DH рисует ({@code GL_DRAW_FRAMEBUFFER}). Своя ({@code getDhDepthTextureGlId}) — верно;
 * глубина мира (основной кадр Minecraft) — LOD своей проекцией пишет глубину в буфер мира и ложится поверх ближнего
 * мира. Так бывает под Iris после пересоздания кадра игры в том же размере (вывод Flashback в размер окна): Iris
 * цепляет новую глубину мира ко всем своим кадрам, и к кадру LOD тоже, а свою DH цепляет обратно, только когда DH
 * пересоздал её (сменился размер). Проход теней рисует в свой кадр — ни то ни другое.
 */
final class DhFrameDepth {
    private static boolean own, world;
    /** Размер текстур LOD DH при последнем их пересоздании; −1 — ещё не создавались. */
    private static int texWidth = -1, texHeight = -1;

    private DhFrameDepth() {}

    @SuppressWarnings("deprecation")  // событие, по которому Iris цепляет глубину DH к своим кадрам (reconnectDHTextures)
    static void subscribe() {
        DhApiEventRegister.on(DhApiColorDepthTextureCreatedEvent.class, new DhApiColorDepthTextureCreatedEvent() {
            @Override
            public void onResize(DhApiEventParam<EventParam> event) {
                texWidth = event.value.newWidth;
                texHeight = event.value.newHeight;
                Incoming.LOG.info("SCENARIO film dh textures {}x{} (were {}x{})", texWidth, texHeight, event.value.previousWidth,
                        event.value.previousHeight);
            }
        });
        DhApiEventRegister.on(DhApiBeforeRenderPassEvent.class, new DhApiBeforeRenderPassEvent() {
            @Override
            public void beforeRender(DhApiEventParam<DhApiRenderParam> event) {
                EDhApiRenderPass pass = event.value.renderPass;
                if (pass != EDhApiRenderPass.OPAQUE && pass != EDhApiRenderPass.OPAQUE_AND_TRANSPARENT) return;
                if (GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING) == 0) return;
                int type = GL30.glGetFramebufferAttachmentParameteri(GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT,
                        GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE);
                if (type != GL11.GL_TEXTURE) return;
                int texture = GL30.glGetFramebufferAttachmentParameteri(GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT,
                        GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);
                DhApiResult<Integer> dh = DhApi.Delayed.renderProxy.getDhDepthTextureGlId();
                if (dh.success && dh.payload != null && texture == dh.payload) own = true;
                else if (texture == Minecraft.getInstance().getMainRenderTarget().getDepthTextureId()) world = true;
            }
        });
    }

    /**
     * Вывод размером {@code w×h} пересоздаст текстуры LOD DH: сейчас они другого размера (или их ещё нет). DH пересоздаёт
     * их, только когда кадр игры другого размера, а рисуя LOD: после прошлого вывода (они — его размера) и перемотки
     * назад (зритель входит в повтор заново, LOD грузятся снова) DH может не нарисовать LOD в окне, и вывод того же
     * размера пройдёт с глубиной мира.
     */
    static boolean recreatedBy(int w, int h) {
        return texWidth != w || texHeight != h;
    }

    /** Итог кадров с прошлого вызова и сброс: 1 — LOD со своей глубиной, −1 — с глубиной мира, 0 — LOD не рисовался. */
    static int take() {
        int r = world ? -1 : own ? 1 : 0;
        own = world = false;
        return r;
    }
}
