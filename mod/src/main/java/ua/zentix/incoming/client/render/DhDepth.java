package ua.zentix.incoming.client.render;

import net.neoforged.fml.ModList;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import ua.zentix.incoming.Incoming;

import java.util.Locale;

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
 * в параметрах — пятая часть прорисовки по углу кадра, у Артёма ~23 блока. Глубина пака, прочитанная матрицей DH, втрое
 * ближе настоящей: LOD города в 3–5 км «стоят» в 1–1,5 км, и столбы разрывов на 1,5 км пропадают за ними целиком
 * (Complementary).
 */
public final class DhDepth {
    private static boolean enabled;
    /** Как читалась глубина DH в прошлый раз, что попал в лог (направление, диапазон, кто рисует); −1 — ещё никак. */
    private static int logged = -1;
    /** Для лога: проекция DH кадра негодна. */
    private static final int UNUSABLE = 8;
    private static final Vector4f VIEW = new Vector4f();

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

        /** Как читается глубина DH — для лога и строки сценария. */
        public String describe() {
            float nearest = along(inverseProjection, forward ? 0 : 1, negativeOneToOne);
            return String.format(Locale.ROOT, "%s Z, NDC %s, empty %.1f, nearest LOD %.1f blocks (DH planes %.1f to %.0f), LOD drawn by %s",
                    forward ? "forward" : "reverse", negativeOneToOne ? "-1..1" : "0..1", empty, nearest, near, far, foreign ? "the shader pack" : "DH");
        }
    }

    /** При запуске клиента: есть ли DH с API, которое умеет отдать глубину, и подписка на его кадры. */
    public static void init() {
        if (!ModList.get().isLoaded("distanthorizons")) return;
        try {
            enabled = DhDepthApi.supported();
            if (enabled) DhDepthApi.subscribe();
        } catch (LinkageError e) {
            // API DH без нужных классов или методов: игра грузится, дым вдали не прячется за LOD
            Incoming.LOG.warn("Distant Horizons: API differs from the one the mod was built for — DH terrain does not hide distant smoke and blasts", e);
            enabled = false;
        }
    }

    /** Глубина DH этого кадра в out; false — DH в этом кадре не рисовал (или его нет). */
    public static boolean current(View out) {
        if (!enabled || !DhDepthApi.current(out)) return false;
        if (irisDrawn(out.foreign, out.forward, out.negativeOneToOne)) irisPlanes(out.projection, out.near, out.far);
        out.projection.invert(out.inverseProjection);
        if (!out.inverseProjection.isFinite()) {
            // проекция кадра негодна (плоскости DH совпали, матрица не задана): LOD этого кадра для слоя — как без DH
            if (logged != UNUSABLE) {
                logged = UNUSABLE;
                Incoming.LOG.warn("Distant Horizons depth for the effects layer: DH projection is not invertible ({}), DH terrain ignored", out.describe());
            }
            return false;
        }
        int how = (out.forward ? 1 : 0) | (out.negativeOneToOne ? 2 : 0) | (out.foreign ? 4 : 0);
        if (how != logged) {
            logged = how;
            Incoming.LOG.info("Distant Horizons depth for the effects layer: {}", out.describe());
        }
        return true;
    }

    /**
     * LOD рисует программа шейдерпака перспективой Iris (прямая глубина, NDC от −1 до 1): читать её
     * {@link #irisPlanes}. Без шейдерпака — и когда Iris стоит без него — DH рисует сам своей матрицей, обратной
     * глубиной ({@code REVERSE_Z}, очистка 0, NDC от 0 до 1 в матрице), и подмены кадра нет: матрица DH верна как есть.
     */
    static boolean irisDrawn(boolean foreign, boolean forward, boolean negativeOneToOne) {
        return foreign && forward && negativeOneToOne;
    }

    /** Расстояние по оси взгляда (блоки) из значения текстуры глубины DH — как {@code along} в {@code fx_depth}. */
    static float along(Matrix4f inverseProjection, float depth, boolean negativeOneToOne) {
        inverseProjection.transform(VIEW.set(0, 0, negativeOneToOne ? depth * 2 - 1 : depth, 1));
        return -VIEW.z / VIEW.w;
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
