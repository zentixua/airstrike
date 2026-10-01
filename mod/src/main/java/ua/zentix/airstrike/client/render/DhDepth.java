package ua.zentix.airstrike.client.render;

import net.neoforged.fml.ModList;
import org.joml.Matrix4f;
import ua.zentix.airstrike.Airstrike;

/**
 * Глубина рельефа Distant Horizons в этом кадре — для того, что рисуется после мира: аппаратная проверка глубины его
 * не видит (DH рисует LOD в свою текстуру глубины и переносит в кадр только цвет; под шейдерами Iris — та же
 * текстура, {@code dhDepthTex} пака). Текстура и проекция DH — из его публичного API ({@link DhDepthApi}); без DH
 * или с API другой версии глубины DH нет, и после мира виден только рельеф Minecraft.
 */
public final class DhDepth {
    private static boolean enabled;

    private DhDepth() {}

    /** Глубина DH в кадре: текстура, обратная проекция, как читать значение. */
    public static final class View {
        /** Текстура глубины OpenGL. */
        public int texture;
        /** Глубина → координаты вида (обратная проекция DH). */
        public final Matrix4f inverseProjection = new Matrix4f();
        /** Глубина в [0, 1] — это NDC от −1 до 1 (обычный OpenGL), иначе NDC от 0 до 1. */
        public boolean negativeOneToOne;
        /** Глубина пикселя без LOD (очищенная). */
        public float empty;
    }

    /** При запуске клиента: есть ли DH с API, которое умеет отдать глубину, и подписка на его кадры. */
    public static void init() {
        if (!ModList.get().isLoaded("distanthorizons")) return;
        try {
            enabled = DhDepthApi.supported();
            if (enabled) DhDepthApi.subscribe();
        } catch (LinkageError e) {
            // API DH без нужных классов или методов: игра грузится, дым вдали не прячется за LOD
            Airstrike.LOG.warn("Distant Horizons: API не то, под которое собран мод, — рельеф DH не закрывает дым и взрывы вдали", e);
            enabled = false;
        }
    }

    /** Глубина DH этого кадра в out; false — DH в этом кадре не рисовал (или его нет). */
    public static boolean current(View out) {
        return enabled && DhDepthApi.current(out);
    }

    /** Конец кадра: следующий кадр DH должен нарисовать заново, иначе его глубина — не этого кадра. */
    public static void endFrame() {
        if (enabled) DhDepthApi.endFrame();
    }
}
