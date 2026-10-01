package ua.zentix.airstrike.client.far;

import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

/**
 * Что нужно на кадр всем дальним картинкам: где камера и куда смотрит, докуда рисует мир, угол пикселя, цвет дымки
 * и свет неба, дальность видимости по погоде.
 *
 * @param camera  глаз
 * @param left    влево по экрану
 * @param up      вверх по экрану
 * @param partial доля тика
 * @param far     дальше — переносить ближе с тем же угловым размером ({@link ua.zentix.airstrike.client.render.FarDraw#fold})
 * @param pixel   угол одного пикселя, рад
 * @param fog     цвет дымки у горизонта (r, g, b)
 * @param ambient свет неба 0.12..1: ночью дым и корпус темнее
 * @param range   дальность видимости, блоков ({@link Sight#range})
 * @param world   докуда мир рисует сам (дальность прорисовки, блоков): ближе дальняя картинка уступает частицам
 */
public record FarView(Vec3 camera, Vector3f left, Vector3f up, float partial, double far, double pixel, float[] fog, float ambient,
                      double range, double world) {
    /**
     * Доля дальней картинки взрыва на дальности d: ближе 0,8 прорисовки её нет (там частицы), к краю прорисовки, где
     * туман съедает частицы, — вся.
     */
    public double farShare(double d) {
        double a = 0.8 * world, t = (d - a) / Math.max(1, world - a);
        t = Math.max(0, Math.min(1, t));
        return t * t * (3 - 2 * t);
    }

    /** Цвет тёмного на дальности: смешан с дымкой по Кошмидеру. */
    public float hazed(float c, int channel, double t) {
        return (float) (c * ambient * t + fog[channel] * (1 - t));
    }
}
