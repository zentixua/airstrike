package ua.zentix.airstrike.client.render;

import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    @Test
    void dhMatrixAloneMisreadsIrisDepth() {
        // то, что было: глубина Iris, прочитанная матрицей DH, — LOD города в 3 км «стоял» ближе столбов на 1,5 км
        Matrix4f iris = new Matrix4f().setPerspective(FOV, ASPECT, PARAM_NEAR, FAR);
        float misread = along(dhProjection().invert(), depth(iris, 3000));
        assertTrue(misread < 1500, "прочитано " + misread);
    }
}
