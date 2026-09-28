package ua.zentix.airstrike.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.model.data.ModelData;
import org.joml.Quaternionf;
import ua.zentix.airstrike.Airstrike;
import ua.zentix.airstrike.entity.BomberEntity;
import ua.zentix.airstrike.entity.FlightPhase;
import ua.zentix.airstrike.entity.StrikeProjectile;

import java.util.List;

/**
 * Модели снарядов — гладкие сетки OBJ с текстурами из {@code tools/gen_models.py} (models/weapon, textures/block/weapon).
 * Грузятся как дополнительные модели блоков (атлас блоков), рисуются слоем сущностей: свет и тени мира, под шейдерами
 * Iris — как обычные сущности. Подвижные детали — отдельные OBJ, здесь их поворачивают по фазе полёта:
 * винт шахеда, крылья и воздухозаборник ракеты, створки бомболюка B-2, стартовые ускорители до отделения.
 * Пакет РСЗО ({@link Mesh#ROCKET_RACK}) рисует {@link LauncherRenderer}.
 * Шарниры — те же числа, что в gen_models.py.
 */
public final class WeaponModels {
    private WeaponModels() {}

    /** Деталь модели: один OBJ. */
    public enum Mesh {
        DRONE_BODY("drone_body"), DRONE_PROP("drone_prop"), DRONE_DISC("drone_disc"), DRONE_BOOSTER("drone_booster"),
        MISSILE_BODY("missile_body"), MISSILE_WING_L("missile_wing_l"), MISSILE_WING_R("missile_wing_r"),
        MISSILE_INTAKE("missile_intake"), MISSILE_BOOSTER("missile_booster"),
        BOMBER_BODY("bomber_body"), BOMBER_DOOR_L_IN("bomber_door_l_in"), BOMBER_DOOR_L_OUT("bomber_door_l_out"),
        BOMBER_DOOR_R_IN("bomber_door_r_in"), BOMBER_DOOR_R_OUT("bomber_door_r_out"),
        BOMB_BODY("bomb_body"), ICBM_BODY("icbm_body"), ROCKET_BODY("rocket_body"), ROCKET_RACK("rocket_rack_body");

        final ModelResourceLocation location;

        Mesh(String name) {
            this.location = ModelResourceLocation.standalone(Airstrike.id("weapon/" + name));
        }

        private List<BakedQuad> quads() {
            return Minecraft.getInstance().getModelManager().getModel(location)
                    .getQuads(null, null, RandomSource.create(0), ModelData.EMPTY, null);
        }

        /** Нарисовать деталь в текущей системе координат (нос по +Z). */
        public void draw(PoseStack pose, VertexConsumer vc, int light, float alpha) {
            PoseStack.Pose last = pose.last();
            for (BakedQuad q : quads()) vc.putBulkData(last, q, 1, 1, 1, alpha, light, OverlayTexture.NO_OVERLAY, false);
        }

        public void draw(PoseStack pose, MultiBufferSource buffers, int light) {
            draw(pose, buffers.getBuffer(SOLID), light, 1);
        }
    }

    private static final RenderType SOLID = RenderType.entityCutoutNoCull(InventoryMenu.BLOCK_ATLAS);
    private static final RenderType TRANSLUCENT = RenderType.entityTranslucent(InventoryMenu.BLOCK_ATLAS);

    public static void register(ModelEvent.RegisterAdditional e) {
        for (Mesh m : Mesh.values()) e.register(m.location);
    }

    /** Как нарисовать снаряд в его системе координат. */
    @FunctionalInterface
    public interface Look<T extends StrikeProjectile> {
        void render(T e, float partial, PoseStack pose, MultiBufferSource buffers, int light);
    }

    /** Ускоритель ещё на снаряде: на пусковой и пока горит. */
    private static boolean boosterAttached(StrikeProjectile e) {
        return e.flightPhase().ordinal() <= FlightPhase.BOOST.ordinal();
    }

    /** Доля раскрытия (0…1) детали, которая раскрывается после отделения ускорителя за {@code ticks} тиков. */
    private static float deployed(StrikeProjectile e, float partial, float delay, float ticks) {
        FlightPhase ph = e.flightPhase();
        if (ph.ordinal() < FlightPhase.CLIMB.ordinal()) return 0;
        if (ph != FlightPhase.CLIMB) return 1;
        float k = Mth.clamp((e.phaseAge() + partial - delay) / ticks, 0, 1);
        return k * k * (3 - 2 * k);
    }

    // ---------------------------------------------------------------- шахед

    /** Шарнир винта — ось Z. Скорость винта, рад/тик: на пусковой мотор уже крутится на малом газу. */
    public static void drone(StrikeProjectile e, float partial, PoseStack pose, MultiBufferSource buffers, int light) {
        Mesh.DRONE_BODY.draw(pose, buffers, light);
        if (boosterAttached(e)) Mesh.DRONE_BOOSTER.draw(pose, buffers, light);
        FlightPhase ph = e.flightPhase();
        float rate = ph.onLauncher() ? 0.9f : ph == FlightPhase.TERMINAL ? 2.6f : 2.0f;
        float spin = (e.age() + partial) * rate;
        pose.pushPose();
        pose.mulPose(new Quaternionf().rotationZ(spin));
        Mesh.DRONE_PROP.draw(pose, buffers, light);
        pose.popPose();
        // размытый диск: чем быстрее винт, тем он плотнее
        Mesh.DRONE_DISC.draw(pose, buffers.getBuffer(TRANSLUCENT), light, Mth.clamp((rate - 0.6f) / 1.6f, 0, 1));
    }

    // ---------------------------------------------------------------- крылатая ракета

    /** Шарниры крыльев: (±0.42, 0, 0.9), поворот вокруг Y; сложенное крыло лежит вдоль корпуса назад. */
    private static final float WING_PIVOT_X = 0.42f, WING_PIVOT_Z = 0.9f;

    public static void missile(StrikeProjectile e, float partial, PoseStack pose, MultiBufferSource buffers, int light) {
        Mesh.MISSILE_BODY.draw(pose, buffers, light);
        if (boosterAttached(e)) Mesh.MISSILE_BOOSTER.draw(pose, buffers, light);
        float wings = deployed(e, partial, 2, 10);
        for (int side = -1; side <= 1; side += 2) {
            pose.pushPose();
            pose.translate(side * WING_PIVOT_X, 0, WING_PIVOT_Z);
            pose.mulPose(new Quaternionf().rotationY(side * Mth.HALF_PI * (1 - wings)));
            pose.translate(-side * WING_PIVOT_X, 0, -WING_PIVOT_Z);
            (side > 0 ? Mesh.MISSILE_WING_L : Mesh.MISSILE_WING_R).draw(pose, buffers, light);
            pose.popPose();
        }
        // воздухозаборник выдвигается из брюха после крыльев
        float intake = deployed(e, partial, 8, 6);
        pose.pushPose();
        pose.translate(0, 0.32f * (1 - intake), 0);
        Mesh.MISSILE_INTAKE.draw(pose, buffers, light);
        pose.popPose();
    }

    // ---------------------------------------------------------------- B-2

    /** Шарниры створок — по кромкам отсеков (ось Z): внутренние у x = ±0.4, внешние у ±3.0; y — низ крыла там. */
    private static final float DOOR_HINGE_IN = 0.4f, DOOR_HINGE_OUT = 3.0f, DOOR_Y_IN = -0.93f, DOOR_Y_OUT = -0.68f;
    /** За сколько блоков до точки сброса открываются створки (≈ 10 тиков полёта). */
    private static final double DOORS_OPEN_AHEAD = 120;

    public static void bomber(StrikeProjectile e, float partial, PoseStack pose, MultiBufferSource buffers, int light) {
        Mesh.BOMBER_BODY.draw(pose, buffers, light);
        float open = bayOpen(e, partial);
        door(pose, buffers, light, Mesh.BOMBER_DOOR_L_IN, DOOR_HINGE_IN, DOOR_Y_IN, -open);
        door(pose, buffers, light, Mesh.BOMBER_DOOR_L_OUT, DOOR_HINGE_OUT, DOOR_Y_OUT, open);
        door(pose, buffers, light, Mesh.BOMBER_DOOR_R_IN, -DOOR_HINGE_IN, DOOR_Y_IN, open);
        door(pose, buffers, light, Mesh.BOMBER_DOOR_R_OUT, -DOOR_HINGE_OUT, DOOR_Y_OUT, -open);
    }

    /** Створки: открываются на подходе к точке сброса, закрываются через секунду после него. */
    private static float bayOpen(StrikeProjectile e, float partial) {
        FlightPhase ph = e.flightPhase();
        if (ph == FlightPhase.EGRESS) return 1 - Mth.clamp((e.phaseAge() + partial - 20) / 20f, 0, 1);
        Vec3 aim = e.aimPoint();
        double d = Math.hypot(aim.x - e.getX(), aim.z - e.getZ()) - BomberEntity.RELEASE_DISTANCE;
        return (float) Mth.clamp(1 - d / DOORS_OPEN_AHEAD, 0, 1);
    }

    /** Створка поворачивается вокруг оси Z на шарнире; знак угла — наружу от отсека. */
    private static void door(PoseStack pose, MultiBufferSource buffers, int light, Mesh mesh, float hingeX, float hingeY, float open) {
        pose.pushPose();
        pose.translate(hingeX, hingeY, 0);
        pose.mulPose(new Quaternionf().rotationZ(open * 1.45f));
        pose.translate(-hingeX, -hingeY, 0);
        mesh.draw(pose, buffers, light);
        pose.popPose();
    }

    // ---------------------------------------------------------------- бомба и МБР

    public static void bomb(StrikeProjectile e, float partial, PoseStack pose, MultiBufferSource buffers, int light) {
        // бомба медленно вращается в падении — решётчатые рули держат её вращение
        pose.pushPose();
        pose.mulPose(new Quaternionf().rotationZ((e.age() + partial) * 0.05f));
        Mesh.BOMB_BODY.draw(pose, buffers, light);
        pose.popPose();
    }

    /** Реактивный снаряд РСЗО: в полёте вращается — его крутят косо поставленные перья стабилизатора. */
    public static void rocket(StrikeProjectile e, float partial, PoseStack pose, MultiBufferSource buffers, int light) {
        pose.pushPose();
        if (!e.flightPhase().onLauncher()) pose.mulPose(new Quaternionf().rotationZ((e.age() + partial) * 0.5f));
        Mesh.ROCKET_BODY.draw(pose, buffers, light);
        pose.popPose();
    }

    public static void icbm(StrikeProjectile e, float partial, PoseStack pose, MultiBufferSource buffers, int light) {
        Mesh.ICBM_BODY.draw(pose, buffers, light);
    }
}
