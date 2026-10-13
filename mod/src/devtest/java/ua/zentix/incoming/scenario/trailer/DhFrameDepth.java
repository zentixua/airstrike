package ua.zentix.incoming.scenario.trailer;

import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.enums.rendering.EDhApiRenderPass;
import com.seibel.distanthorizons.api.methods.events.DhApiEventRegister;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiBeforeRenderPassEvent;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiEventParam;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiRenderParam;
import com.seibel.distanthorizons.api.objects.DhApiResult;
import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

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

    private DhFrameDepth() {}

    static void subscribe() {
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

    /** Итог кадров с прошлого вызова и сброс: 1 — LOD со своей глубиной, −1 — с глубиной мира, 0 — LOD не рисовался. */
    static int take() {
        int r = world ? -1 : own ? 1 : 0;
        own = world = false;
        return r;
    }
}
