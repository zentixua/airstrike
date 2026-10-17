package ua.zentix.incoming.client.render;

import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Глубину LOD, которую записала программа шейдерпака Iris, слой читает её проекцией: расстояние то же, что у точки.
 * Числа — как у Артёма на ноутбуке: прорисовка 12, кадр 2048×1272, в матрице DH ближняя 7,5 блока, в параметрах ~23.
 */
class DhDepthTest {
    private static final float FOV = (float) Math.toRadians(70), ASPECT = 2048f / 1272f;
    private static final float DH_NEAR = 7.5f, PARAM_NEAR = 23.1f, FAR = 6516;

    /** Проекция DH, как её строит {@code RenderUtil.setDhProjectionMatrix} с прямой глубиной: Minecraft и свои плоскости. */
    private static Matrix4f dhProjection() {
        return new Matrix4f().setPerspective(FOV, ASPECT, 0.05f, 768).m22((FAR + DH_NEAR) / (DH_NEAR - FAR))
                .m32(2 * FAR * DH_NEAR / (DH_NEAR - FAR));
    }

    /** Глубина в текстуре (0..1) точки на оси взгляда в d блоках, записанная проекцией p. */
    private static float depth(Matrix4f p, float d) {
        Vector4f clip = p.transform(new Vector4f(0, 0, -d, 1));
        return clip.z / clip.w * 0.5f + 0.5f;
    }

    /** Расстояние по оси взгляда из глубины — как в шейдере {@code fx_depth}. */
    private static float along(Matrix4f inverse, float depth) {
        Vector4f view = inverse.transform(new Vector4f(0, 0, depth * 2 - 1, 1));
        return -view.z / view.w;
    }

    @Test
    void irisDepthReadsAtItsDistance() {
        // программа пака пишет глубину перспективой Iris: угол и стороны кадра, плоскости параметров DH
        Matrix4f iris = new Matrix4f().setPerspective(FOV, ASPECT, PARAM_NEAR, FAR);
        Matrix4f inverse = DhDepth.irisPlanes(dhProjection(), PARAM_NEAR, FAR).invert();
        for (float d : new float[] {200, 1500, 3000, 5000}) {
            assertEquals(d, along(inverse, depth(iris, d)), d * 1e-3f, "LOD в " + d + " блоках");
        }
    }

    /**
     * Проекция DH без шейдерпака — и когда Iris стоит без него (DH 3.3: {@code getDepthDirection} — {@code REVERSE_Z},
     * пока шейдерпак не включён): {@code setClipPlanes(far, near, true)} — обратная глубина, NDC от 0 до 1 в матрице.
     */
    private static Matrix4f dhReverse() {
        return new Matrix4f().setPerspective(FOV, ASPECT, 0.05f, 768).m22(DH_NEAR / (FAR - DH_NEAR)).m32(DH_NEAR * FAR / (FAR - DH_NEAR));
    }

    @Test
    void reverseDepthWithoutShaderPackReadsAtItsDistance() {
        // как у ноутбука Артёма: Sodium, Iris без шейдерпака, DH 3.3 — LOD рисует сам DH обратной глубиной: в текстуре
        // 0,5..1 до дальней плоскости DH и чуть ниже 0,5 за ней (диапазон OpenGL по умолчанию), очистка 0; подмены кадра
        // нет — матрицу DH не трогать
        assertFalse(DhDepth.irisDrawn(false, false, true));
        assertFalse(DhDepth.irisDrawn(true, false, true), "обратную глубину перспективой Iris не читать");
        assertTrue(DhDepth.irisDrawn(true, true, true));
        Matrix4f inverse = dhReverse().invert();
        for (float d : new float[] {DH_NEAR, 40, 200, 1500, 3000, 6000, FAR, 20_000}) {
            float depth = depth(dhReverse(), d);
            assertTrue(depth > 0 && depth <= 1, "глубина " + depth + " в " + d + " блоках");
            assertEquals(d, DhDepth.along(inverse, depth, true), d * 2e-3f, "LOD в " + d + " блоках");
        }
        // пиксель без LOD (очистка 0) читается «за глазом»: его отсекает сравнение с пустой глубиной, а если нет —
        // проверка расстояния в fx_depth (только перед глазом и конечное)
        assertTrue(DhDepth.along(inverse, 0, true) < 0);
    }

    @Test
    void dhMatrixAloneMisreadsIrisDepth() {
        // глубина Iris, прочитанная одной матрицей DH, ставит LOD города в 3 км ближе столбов на 1,5 км
        Matrix4f iris = new Matrix4f().setPerspective(FOV, ASPECT, PARAM_NEAR, FAR);
        float misread = along(dhProjection().invert(), depth(iris, 3000));
        assertTrue(misread < 1500, "прочитано " + misread);
    }
}
