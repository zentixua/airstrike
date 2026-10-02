package ua.zentix.airstrike.client.render;

import net.neoforged.fml.ModList;
import org.joml.Matrix4f;
import ua.zentix.airstrike.Airstrike;

/**
 * Глубина рельефа Distant Horizons в этом кадре — для того, что рисуется после мира: аппаратная проверка глубины его
 * не видит (DH рисует LOD в свою текстуру глубины и переносит в кадр только цвет; под шейдерами Iris — та же
 * текстура, {@code dhDepthTex} пака). Текстура и проекция DH — из его публичного API ({@link DhDepthApi}); без DH
 * или с API другой версии глубины DH нет, и после мира виден только рельеф Minecraft.
 * <p>
 * Чьей проекцией записана глубина: LOD рисует сам DH — своей {@code dhProjectionMatrix}. Шейдерпак с поддержкой DH
 * рисует их программой пака в свой кадр (Iris подменяет {@code IDhApiFramebuffer}), и Iris строит ей перспективу
 * с плоскостями отсечения из {@code DhApiRenderParam} ({@code nearClipPlane}, {@code farClipPlane}). Эти плоскости
 * у DH не те, что в его матрице: в матрице ближняя не дальше 7,5 блока ({@code RenderUtil.setDhProjectionMatrix}),
 * в параметрах — пятая часть прорисовки по углу кадра, у Артёма ~23 блока. Глубину пака, прочитанную матрицей DH, дым
 * брал за втрое ближе настоящей: LOD города в 3–5 км «стояли» в 1–1,5 км, и столбы разрывов на 1,5 км пропадали за ними
 * целиком (ноутбук, Complementary, 02.10.2026).
 */
public final class DhDepth {
    private static boolean enabled;

    private DhDepth() {}

    /** Глубина DH в кадре: текстура, обратная проекция, как читать значение. */
    public static final class View {
        /** Текстура глубины OpenGL. */
        public int texture;
        /** Глубина → координаты вида (обратная проекция, которой записана глубина). */
        public final Matrix4f inverseProjection = new Matrix4f();
        /** Глубина в [0, 1] — это NDC от −1 до 1 (обычный OpenGL), иначе NDC от 0 до 1. */
        public boolean negativeOneToOne;
        /** Глубина пикселя без LOD (очищенная). */
        public float empty;
        /** Дальше — больше (прямая глубина), иначе обратная. */
        boolean forward;
        /** LOD рисует чужая программа (шейдерпак Iris) в свой кадр. */
        boolean foreign;
        /** Проекция DH ({@code dhProjectionMatrix}) и плоскости отсечения его параметров кадра, блоков. */
        final Matrix4f projection = new Matrix4f();
        float near, far;
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
        if (!enabled || !DhDepthApi.current(out)) return false;
        if (out.foreign && out.forward && out.negativeOneToOne) irisPlanes(out.projection, out.near, out.far);
        out.projection.invert(out.inverseProjection);
        return true;
    }

    /**
     * Проекция, которой Iris рисует LOD программой шейдерпака ({@code LodRendererEvents}: {@code setPerspective} с углом
     * и сторонами кадра Minecraft и плоскостями {@code DhApiRenderParam}), из проекции DH: та же, кроме строки глубины —
     * прямая глубина, NDC от −1 до 1.
     */
    static Matrix4f irisPlanes(Matrix4f projection, float near, float far) {
        return projection.m22((far + near) / (near - far)).m32(2 * far * near / (near - far));
    }

    /** Конец кадра: следующий кадр DH должен нарисовать заново, иначе его глубина — не этого кадра. */
    public static void endFrame() {
        if (enabled) DhDepthApi.endFrame();
    }
}
