package ua.zentix.airstrike.client.render;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Глаз кадра ({@link FarDraw#eye}) — центр проекции и при покачивании вида: точка, перенесённая к нему по лучу (блик
 * в 0,25 блока перед глазом, дальнее у дальней плоскости), остаётся на экране там же, где источник. От позиции камеры
 * блик огня при ходьбе гулял по дуге шага, а мир за ним стоял.
 */
class FarDrawTest {
    private static final Matrix4f PROJECTION = new Matrix4f().perspective((float) Math.toRadians(70), 16f / 10f, 0.05f, 1024f);
    /** Взгляд камеры: повёрнута по курсу и немного вниз (вид — обратный поворот, как у Minecraft). */
    private static final Matrix4f ROTATION = new Matrix4f().rotationXYZ(0.35f, -2.2f, 0f);
    /** Точки мира от камеры: прямо, сбоку и внизу, далеко. */
    private static final Vector3f[] POINTS = {new Vector3f(0, -12, -100), new Vector3f(30, -40, -90), new Vector3f(-60, 5, -400)};

    /** Покачивание вида при ходьбе, как {@code GameRenderer.bobView}: фаза шага и сила (у идущего по земле 0,1). */
    private static Matrix4f bobbing(float phase, float bob) {
        float s = (float) Math.sin(phase * Math.PI), c = (float) Math.cos(phase * Math.PI);
        return new Matrix4f().translate(s * bob * 0.5f, -Math.abs(c * bob), 0)
                .rotateZ((float) Math.toRadians(s * bob * 3))
                .rotateX((float) Math.toRadians(Math.abs((float) Math.cos(phase * Math.PI - 0.2f) * bob) * 5));
    }

    private static Vector3f screen(Matrix4f projection, Matrix4f view, Vector3f p) {
        Vector4f v = new Matrix4f(projection).mul(view).transform(new Vector4f(p, 1));
        return new Vector3f(v.x / v.w, v.y / v.w, v.z / v.w);
    }

    /** Точка p, перенесённая к eye по лучу так, чтобы до глаза осталось at блоков. */
    private static Vector3f toward(Vector3f eye, Vector3f p, float at) {
        Vector3f ray = new Vector3f(p).sub(eye);
        return ray.mul(at / ray.length()).add(eye);
    }

    @Test
    void foldedPointStaysOnScreenWhileWalking() {
        for (int i = 0; i < 8; i++) {
            Matrix4f bob = bobbing(i / 8f, 0.1f);
            // ваниль: покачивание в проекции; Iris с шейдерпаком: в матрице вида
            Matrix4f[][] layouts = {{new Matrix4f(PROJECTION).mul(bob), ROTATION}, {PROJECTION, new Matrix4f(bob).mul(ROTATION)}};
            for (Matrix4f[] m : layouts) {
                Vector3f eye = FarDraw.eyeOffset(m[0], m[1], new Vector3f());
                for (Vector3f p : POINTS) {
                    Vector3f source = screen(m[0], m[1], p);
                    Vector3f glare = screen(m[0], m[1], toward(eye, p, 0.25f));
                    assertEquals(source.x, glare.x, 1e-4, "блик у глаза по горизонтали, фаза " + i);
                    assertEquals(source.y, glare.y, 1e-4, "блик у глаза по вертикали, фаза " + i);
                    // так было: перенос к позиции камеры — блик уходил от огня на доли экрана
                    Vector3f old = screen(m[0], m[1], toward(new Vector3f(), p, 0.25f));
                    assertTrue(Math.hypot(old.x - source.x, old.y - source.y) > 0.05, "перенос к камере, фаза " + i);
                }
            }
        }
    }

    @Test
    void eyeIsCameraWithoutBobbing() {
        Vector3f eye = FarDraw.eyeOffset(PROJECTION, ROTATION, new Vector3f());
        assertEquals(0, eye.length(), 1e-5);
    }
}
