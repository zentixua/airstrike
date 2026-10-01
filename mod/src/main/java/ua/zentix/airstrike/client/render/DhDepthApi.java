package ua.zentix.airstrike.client.render;

import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.enums.config.EDhApiDepthRange;
import com.seibel.distanthorizons.api.interfaces.render.IDhApiRenderProxy;
import com.seibel.distanthorizons.api.methods.events.DhApiEventRegister;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiBeforeRenderEvent;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiCancelableEventParam;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiRenderParam;
import com.seibel.distanthorizons.api.objects.DhApiResult;
import com.seibel.distanthorizons.api.objects.math.DhApiMat4f;
import org.joml.Matrix4f;
import ua.zentix.airstrike.Airstrike;

/**
 * Связь {@link DhDepth} с API Distant Horizons; класс грузится, только если DH стоит. Проекция — из события
 * {@code DhApiBeforeRenderEvent} этого кадра (последнего с перспективой: под Iris DH рисует ещё и тени, своей
 * проекцией без перспективы), текстура и диапазон глубины — из {@code renderProxy} (API 7.2: диапазон и направление
 * глубины).
 */
final class DhDepthApi {
    /** Мажорная версия API, против которой собран мод; 7.2 — с диапазоном и направлением глубины. */
    private static final int API_MAJOR = 7, API_MINOR = 2;
    private static final float[] ROWS = new float[16];
    private static final Matrix4f PROJECTION = new Matrix4f();
    private static boolean captured;

    private DhDepthApi() {}

    static boolean supported() {
        int major = DhApi.getApiMajorVersion(), minor = DhApi.getApiMinorVersion();
        if (major == API_MAJOR && minor >= API_MINOR) return true;
        Airstrike.LOG.warn("Distant Horizons {}: API {}.{}, мод собран под {}.{}+ — рельеф DH не закрывает дым и взрывы вдали",
                DhApi.getModVersion(), major, minor, API_MAJOR, API_MINOR);
        return false;
    }

    static void subscribe() {
        DhApiEventRegister.on(DhApiBeforeRenderEvent.class, new DhApiBeforeRenderEvent() {
            @Override
            public void beforeRender(DhApiCancelableEventParam<DhApiRenderParam> event) {
                DhApiMat4f m = event.value.dhProjectionMatrix;
                // строка 3 перспективы — (0, 0, −1, 0); у проекции теней её нет
                if (Math.abs(m.m32 + 1) > 1e-3f) return;
                m.putValuesInArray(ROWS);
                // DH отдаёт по строкам, JOML читает по столбцам
                PROJECTION.set(ROWS).transpose();
                captured = true;
            }
        });
    }

    static boolean current(DhDepth.View out) {
        if (!captured) return false;
        IDhApiRenderProxy proxy = DhApi.Delayed.renderProxy;
        if (proxy == null) return false;
        try {
            DhApiResult<Integer> texture = proxy.getDhDepthTextureGlId();
            if (!texture.success || texture.payload == null || texture.payload <= 0) return false;
            out.texture = texture.payload;
            out.negativeOneToOne = proxy.getDepthRange() == EDhApiDepthRange.NEG_ONE_TO_POS_ONE;
            out.empty = proxy.getDepthDirection().farDepth;
        } catch (IllegalStateException e) {
            // рендерер DH ещё не создан
            return false;
        }
        PROJECTION.invert(out.inverseProjection);
        return true;
    }

    static void endFrame() {
        captured = false;
    }
}
