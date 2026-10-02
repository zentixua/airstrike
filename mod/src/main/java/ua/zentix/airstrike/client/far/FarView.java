package ua.zentix.airstrike.client.far;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

/**
 * Что нужно на кадр всем дальним картинкам: где камера и куда смотрит, докуда рисует мир, угол пикселя, свет неба,
 * дальность видимости по погоде. Цвета дымки нет: она — в непрозрачности ({@link Sight}).
 *
 * @param camera  глаз
 * @param forward куда смотрит
 * @param left    влево по экрану
 * @param up      вверх по экрану
 * @param partial доля тика
 * @param far     дальше — переносить ближе с тем же угловым размером ({@link ua.zentix.airstrike.client.render.FarDraw#fold})
 * @param pixel   угол одного пикселя, рад
 * @param edge    угол от середины экрана до угла, рад: дальше — не в кадре
 * @param ambient свет неба 0.12..1 ({@link #ambient(ClientLevel, float)}): ночью дым и корпус темнее, свет вспышек ярче
 * @param range   дальность видимости, блоков ({@link Sight#range})
 * @param world   докуда мир рисует сам (дальность прорисовки, блоков): ближе дальняя картинка уступает частицам
 * @param clouds  высота облаков измерения, блоков ({@code NaN} — облаков нет: Незер, Энд)
 * @param rain    дождь 0..1: облака сплошные
 */
public record FarView(Vec3 camera, Vector3f forward, Vector3f left, Vector3f up, float partial, double far, double pixel, double edge,
                      float ambient, double range, double world, double clouds, float rain) {
    /** Свет неба мира 0,12..1: днём 1, ночью 0,17, ночью в грозу 0,12. */
    public static float ambient(ClientLevel level, float partial) {
        return Mth.clamp(level.getSkyDarken(partial) * 1.1f - 0.05f, 0.12f, 1f);
    }

    /**
     * Свет, к которому привык глаз, 0,12..1: под открытым небом — свет неба ({@link #ambient}), в пещере, доме и полости
     * бункера — темнота, по свету неба у глаза; в измерении без неба — темнота.
     */
    public static float adaptation(ClientLevel level, Vec3 eye, float partial) {
        float sky = level.getBrightness(LightLayer.SKY, BlockPos.containing(eye)) / 15f;
        return Mth.lerp(sky, 0.12f, ambient(level, partial));
    }

    /**
     * Доля дальней картинки взрыва (огненного шара и пожара; столб — на любой дальности) на дальности d: ближе 0,8
     * прорисовки её нет (там частицы), к краю прорисовки, где туман съедает частицы, — вся.
     */
    public double farShare(double d) {
        double a = 0.8 * world, t = (d - a) / Math.max(1, world - a);
        t = Math.max(0, Math.min(1, t));
        return t * t * (3 - 2 * t);
    }
}
