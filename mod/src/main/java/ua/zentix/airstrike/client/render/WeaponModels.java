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
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.model.data.ModelData;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import ua.zentix.airstrike.Airstrike;
import ua.zentix.airstrike.entity.BomberEntity;
import ua.zentix.airstrike.entity.FlightPhase;

import java.util.List;

/**
 * Модели снарядов — гладкие сетки OBJ с текстурами из {@code tools/gen_models.py} (models/weapon, textures/block/weapon).
 * Грузятся как дополнительные модели блоков (атлас блоков), разбираются в {@link QuadMesh} и рисуются слоем
 * сущностей: свет и тени мира, под шейдерами Iris — как обычные сущности. Подвижные детали — отдельные OBJ, здесь их поворачивают по фазе полёта:
 * винт шахеда и «Ланцета», крылья ракеты и «Ланцета», воздухозаборник ракеты, створки бомболюка B-2, стартовые ускорители до отделения.
 * Пакет РСЗО ({@link Mesh#ROCKET_RACK}) рисует {@link LauncherRenderer}.
 * Шарниры — те же числа, что в gen_models.py.
 * <p>
 * Рисуют по позе снаряда ({@link ProjectilePose}), а не по сущности, и отдают детали в {@link Parts}: тот же код
 * рисует снаряд вблизи (вершины в буфер слоя сущностей, {@link Buffers}) и вдали, где сущности у клиента нет
 * ({@link FarModels}: детали лежат в видеопамяти, на кадр — только их положение). У деталей есть упрощённые копии
 * ({@code <деталь>_lod1}, gen_models.py) — для модели, мелкой на экране.
 */
public final class WeaponModels {
    private WeaponModels() {}

    /**
     * Деталь модели: один OBJ и, если {@code lod}, его упрощённая копия {@code <имя>_lod1}; {@code translucent} —
     * полупрозрачная (размытый диск винта), рисуется поверх непрозрачных без записи глубины.
     */
    public enum Mesh {
        DRONE_BODY("drone_body", true), DRONE_PROP("drone_prop", true), DRONE_DISC("drone_disc", false, true),
        DRONE_BOOSTER("drone_booster", true),
        MISSILE_BODY("missile_body", true), MISSILE_WING_L("missile_wing_l", true), MISSILE_WING_R("missile_wing_r", true),
        MISSILE_INTAKE("missile_intake", true), MISSILE_BOOSTER("missile_booster", true),
        BOMBER_BODY("bomber_body", true), BOMBER_DOOR_L_IN("bomber_door_l_in", false), BOMBER_DOOR_L_OUT("bomber_door_l_out", false),
        BOMBER_DOOR_R_IN("bomber_door_r_in", false), BOMBER_DOOR_R_OUT("bomber_door_r_out", false),
        BOMB_BODY("bomb_body", true), ICBM_BODY("icbm_body", true), ROCKET_BODY("rocket_body", true), ROCKET_RACK("rocket_rack_body", false),
        LOITER_BODY("loiter_body", true), LOITER_WINGS("loiter_wings", true), LOITER_PROP("loiter_prop", true), LOITER_DISC("loiter_disc", false, true),
        /** Зенитная ракета ЗРК: вдали её не рисуют (полёт короткий, вид — только в мире), упрощённой копии нет. */
        INTERCEPTOR_BODY("interceptor_body", false);

        /** Имя OBJ (models/weapon/&lt;имя&gt;.obj). */
        public final String file;
        /** Есть упрощённая копия. */
        public final boolean lod;
        public final boolean translucent;
        private final ModelResourceLocation location, lodLocation;
        private final QuadMesh.Cached<QuadMesh> mesh, coarse;

        Mesh(String file, boolean lod) {
            this(file, lod, false);
        }

        Mesh(String file, boolean lod, boolean translucent) {
            this.file = file;
            this.lod = lod;
            this.translucent = translucent;
            this.location = location(file);
            this.lodLocation = lod ? location(file + "_lod1") : location;
            this.mesh = new QuadMesh.Cached<>(() -> compile(location));
            this.coarse = lod ? new QuadMesh.Cached<>(() -> compile(lodLocation)) : mesh;
        }

        private static ModelResourceLocation location(String file) {
            return ModelResourceLocation.standalone(Airstrike.id("weapon/" + file));
        }

        private static QuadMesh compile(ModelResourceLocation location) {
            List<BakedQuad> quads = Minecraft.getInstance().getModelManager().getModel(location)
                    .getQuads(null, null, RandomSource.create(0), ModelData.EMPTY, null);
            return QuadMesh.builder().add(quads, new Matrix4f()).build();
        }

        /** Сетка детали; coarse — упрощённая копия, если она есть (без неё — та же полная). */
        QuadMesh mesh(boolean coarse) {
            return (coarse ? this.coarse : mesh).get();
        }

        /** Нарисовать деталь в текущей системе координат (нос по +Z); coarse — упрощённую копию, если она есть. */
        public void draw(PoseStack pose, VertexConsumer vc, int light, float alpha, boolean coarse) {
            mesh(coarse).draw(pose.last(), vc, QuadMesh.white(alpha), light, OverlayTexture.NO_OVERLAY);
        }

        /** Полную копию, непрозрачной (пусковые, сброшенный ускоритель). */
        public void draw(PoseStack pose, MultiBufferSource buffers, int light) {
            draw(pose, buffers.getBuffer(SOLID), light, 1, false);
        }
    }

    /** Слой непрозрачных деталей и полупрозрачных (размытые диски винтов). */
    static final RenderType SOLID = RenderType.entityCutoutNoCull(InventoryMenu.BLOCK_ATLAS);
    static final RenderType TRANSLUCENT = RenderType.entityTranslucent(InventoryMenu.BLOCK_ATLAS);

    public static void register(ModelEvent.RegisterAdditional e) {
        for (Mesh m : Mesh.values()) {
            e.register(m.location);
            if (m.lod) e.register(m.lodLocation);
        }
    }

    /** Ресурсы перезагружены (F3+T, пакет ресурсов): сетки снарядов и пусковых собираются из новых моделей. */
    public static void baked(ModelEvent.BakingCompleted e) {
        QuadMesh.Cached.invalidateAll();
    }

    /** Как нарисовать снаряд в его системе координат (нос по +Z) по его позе: какие детали и где. */
    @FunctionalInterface
    public interface Look {
        void render(ProjectilePose p, PoseStack pose, Parts parts);
    }

    /** Куда идут детали модели. */
    @FunctionalInterface
    public interface Parts {
        /** Деталь в текущей системе координат pose; coarse — упрощённая копия, alpha — прозрачность полупрозрачной. */
        void part(Mesh mesh, PoseStack pose, boolean coarse, float alpha);

        default void part(Mesh mesh, PoseStack pose, boolean coarse) {
            part(mesh, pose, coarse, 1);
        }
    }

    /** Детали — вершинами в буферы слоёв сущностей со светом мира (снаряд вблизи); один на рендерер, поток отрисовки. */
    public static final class Buffers implements Parts {
        private MultiBufferSource buffers;
        private int light;

        public Buffers into(MultiBufferSource buffers, int light) {
            this.buffers = buffers;
            this.light = light;
            return this;
        }

        @Override
        public void part(Mesh mesh, PoseStack pose, boolean coarse, float alpha) {
            mesh.draw(pose, buffers.getBuffer(mesh.translucent ? TRANSLUCENT : SOLID), light, alpha, coarse);
        }
    }

    /** Ускоритель ещё на снаряде: на пусковой и пока горит. */
    private static boolean boosterAttached(ProjectilePose p) {
        return p.phase.ordinal() <= FlightPhase.BOOST.ordinal();
    }

    /** Доля раскрытия (0…1) детали, которая раскрывается после отделения ускорителя за {@code ticks} тиков. */
    private static float deployed(ProjectilePose p, float delay, float ticks) {
        FlightPhase ph = p.phase;
        if (ph.ordinal() < FlightPhase.CLIMB.ordinal()) return 0;
        if (ph != FlightPhase.CLIMB) return 1;
        float k = Mth.clamp((p.phaseAge - delay) / ticks, 0, 1);
        return k * k * (3 - 2 * k);
    }

    // ---------------------------------------------------------------- шахед

    /** Шарнир винта — ось Z. Скорость винта, рад/тик: на пусковой мотор уже крутится на малом газу. */
    public static void drone(ProjectilePose p, PoseStack pose, Parts parts) {
        parts.part(Mesh.DRONE_BODY, pose, p.coarse);
        if (boosterAttached(p)) parts.part(Mesh.DRONE_BOOSTER, pose, p.coarse);
        FlightPhase ph = p.phase;
        float rate = ph.onLauncher() ? 0.9f : ph == FlightPhase.TERMINAL ? 2.6f : 2.0f;
        pose.pushPose();
        pose.mulPose(new Quaternionf().rotationZ(p.age * rate));
        parts.part(Mesh.DRONE_PROP, pose, p.coarse);
        pose.popPose();
        // размытый диск: чем быстрее винт, тем он плотнее
        parts.part(Mesh.DRONE_DISC, pose, p.coarse, Mth.clamp((rate - 0.6f) / 1.6f, 0, 1));
    }

    // ---------------------------------------------------------------- крылатая ракета

    /** Шарниры крыльев: (±0.21, 0, 0.45), поворот вокруг Y; сложенное крыло лежит вдоль корпуса назад. */
    private static final float WING_PIVOT_X = 0.21f, WING_PIVOT_Z = 0.45f;

    public static void missile(ProjectilePose p, PoseStack pose, Parts parts) {
        parts.part(Mesh.MISSILE_BODY, pose, p.coarse);
        if (boosterAttached(p)) parts.part(Mesh.MISSILE_BOOSTER, pose, p.coarse);
        float wings = deployed(p, 2, 10);
        for (int side = -1; side <= 1; side += 2) {
            pose.pushPose();
            pose.translate(side * WING_PIVOT_X, 0, WING_PIVOT_Z);
            pose.mulPose(new Quaternionf().rotationY(side * Mth.HALF_PI * (1 - wings)));
            pose.translate(-side * WING_PIVOT_X, 0, -WING_PIVOT_Z);
            parts.part(side > 0 ? Mesh.MISSILE_WING_L : Mesh.MISSILE_WING_R, pose, p.coarse);
            pose.popPose();
        }
        // воздухозаборник выдвигается из брюха после крыльев
        float intake = deployed(p, 8, 6);
        pose.pushPose();
        pose.translate(0, 0.16f * (1 - intake), 0);
        parts.part(Mesh.MISSILE_INTAKE, pose, p.coarse);
        pose.popPose();
    }

    // ---------------------------------------------------------------- B-2

    /** Шарниры створок — по кромкам отсеков (ось Z): внутренние у x = ±0.4, внешние у ±3.0; y — низ крыла там. */
    private static final float DOOR_HINGE_IN = 0.4f, DOOR_HINGE_OUT = 3.0f, DOOR_Y_IN = -0.93f, DOOR_Y_OUT = -0.68f;
    /** За сколько блоков до точки сброса открываются створки (≈ 10 тиков полёта). */
    private static final double DOORS_OPEN_AHEAD = 120;

    public static void bomber(ProjectilePose p, PoseStack pose, Parts parts) {
        parts.part(Mesh.BOMBER_BODY, pose, p.coarse);
        float open = bayOpen(p);
        door(pose, parts, Mesh.BOMBER_DOOR_L_IN, DOOR_HINGE_IN, DOOR_Y_IN, -open);
        door(pose, parts, Mesh.BOMBER_DOOR_L_OUT, DOOR_HINGE_OUT, DOOR_Y_OUT, open);
        door(pose, parts, Mesh.BOMBER_DOOR_R_IN, -DOOR_HINGE_IN, DOOR_Y_IN, open);
        door(pose, parts, Mesh.BOMBER_DOOR_R_OUT, -DOOR_HINGE_OUT, DOOR_Y_OUT, -open);
    }

    /** Створки: открываются на подходе к точке сброса, закрываются через секунду после него. */
    private static float bayOpen(ProjectilePose p) {
        if (p.phase == FlightPhase.EGRESS) return 1 - Mth.clamp((p.phaseAge - 20) / 20f, 0, 1);
        double d = Math.hypot(p.aimX - p.x, p.aimZ - p.z) - BomberEntity.releaseLine(p.y - p.aimY);
        return (float) Mth.clamp(1 - d / DOORS_OPEN_AHEAD, 0, 1);
    }

    /** Створка поворачивается вокруг оси Z на шарнире; знак угла — наружу от отсека. */
    private static void door(PoseStack pose, Parts parts, Mesh mesh, float hingeX, float hingeY, float open) {
        pose.pushPose();
        pose.translate(hingeX, hingeY, 0);
        pose.mulPose(new Quaternionf().rotationZ(open * 1.45f));
        pose.translate(-hingeX, -hingeY, 0);
        parts.part(mesh, pose, false);
        pose.popPose();
    }

    // ---------------------------------------------------------------- бомба и МБР

    public static void bomb(ProjectilePose p, PoseStack pose, Parts parts) {
        // бомба медленно вращается в падении — решётчатые рули держат её вращение
        pose.pushPose();
        pose.mulPose(new Quaternionf().rotationZ(p.age * 0.05f));
        parts.part(Mesh.BOMB_BODY, pose, p.coarse);
        pose.popPose();
    }

    /** Реактивный снаряд РСЗО: в полёте вращается — его крутят косо поставленные перья стабилизатора. */
    public static void rocket(ProjectilePose p, PoseStack pose, Parts parts) {
        pose.pushPose();
        if (!p.phase.onLauncher()) pose.mulPose(new Quaternionf().rotationZ(p.age * 0.5f));
        parts.part(Mesh.ROCKET_BODY, pose, p.coarse);
        pose.popPose();
    }

    /**
     * «Ланцет»: крылья сложены вдоль корпуса на катапульте и раскрываются сразу после толчка (рисуем их кресты
     * вырастающими от корпуса), толкающий винт с размытым диском; на круге — ровный газ, в пике — полный.
     */
    public static void loiter(ProjectilePose p, PoseStack pose, Parts parts) {
        parts.part(Mesh.LOITER_BODY, pose, p.coarse);
        FlightPhase ph = p.phase;
        float open = ph.onLauncher() ? 0 : ph == FlightPhase.BOOST ? Mth.clamp((p.phaseAge - 2) / 6f, 0, 1) : 1;
        float span = 0.15f + 0.85f * open * open * (3 - 2 * open);
        pose.pushPose();
        pose.scale(span, span, 1);
        parts.part(Mesh.LOITER_WINGS, pose, p.coarse);
        pose.popPose();
        float rate = ph == FlightPhase.READY ? 0 : ph == FlightPhase.TERMINAL ? 3.0f : 2.2f;
        pose.pushPose();
        pose.mulPose(new Quaternionf().rotationZ(p.age * rate));
        parts.part(Mesh.LOITER_PROP, pose, p.coarse);
        pose.popPose();
        if (rate > 0) parts.part(Mesh.LOITER_DISC, pose, p.coarse, Mth.clamp((rate - 0.6f) / 1.6f, 0, 1));
    }

    public static void icbm(ProjectilePose p, PoseStack pose, Parts parts) {
        parts.part(Mesh.ICBM_BODY, pose, p.coarse);
    }
}
