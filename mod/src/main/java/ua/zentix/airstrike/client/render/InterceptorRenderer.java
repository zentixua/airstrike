package ua.zentix.airstrike.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.inventory.InventoryMenu;
import org.joml.Quaternionf;
import ua.zentix.airstrike.client.fx.Exhaust;
import ua.zentix.airstrike.defense.InterceptorSpec;
import ua.zentix.airstrike.entity.InterceptorEntity;

/**
 * Зенитная ракета: модель ({@link WeaponModels.Mesh#INTERCEPTOR_BODY}, нос по +Z), повёрнутая по курсу и тангажу,
 * и факел двигателя за соплом ({@link PlumeRenderer}): на разгоне — длинный и яркий, потом — маршевый, короче.
 */
public class InterceptorRenderer extends EntityRenderer<InterceptorEntity> {
    public InterceptorRenderer(EntityRendererProvider.Context ctx) {
        super(ctx);
        this.shadowRadius = 0;
    }

    @Override
    public void render(InterceptorEntity e, float entityYaw, float partialTick, PoseStack pose, MultiBufferSource buffers, int packedLight) {
        float yaw = Mth.rotLerp(partialTick, e.yRotO, e.getYRot()), pitch = Mth.lerp(partialTick, e.xRotO, e.getXRot());
        Quaternionf rotation = new Quaternionf().rotationYXZ(-yaw * Mth.DEG_TO_RAD, pitch * Mth.DEG_TO_RAD, 0);
        pose.pushPose();
        pose.mulPose(rotation);
        WeaponModels.Mesh.INTERCEPTOR_BODY.draw(pose, buffers, packedLight);
        PlumeRenderer.render(plume(e, partialTick), pose, buffers, rotation, e.getPosition(partialTick), entityRenderDispatcher.camera);
        pose.popPose();
        super.render(e, entityYaw, partialTick, pose, buffers, packedLight);
    }

    /** Факел: срез сопла — у хвоста модели; на разгоне длиннее и ярче, дальше — маршевый режим. */
    static Exhaust.Plume plume(InterceptorEntity e, float partial) {
        float t = e.age() + partial;
        float flicker = 0.9f + 0.1f * Mth.sin(t * 2.9f) * Mth.sin(t * 1.4f + 1);
        boolean boost = t <= InterceptorSpec.SAM.boostTicks();
        float tail = (float) -InterceptorSpec.SAM.noseLength();
        return boost
                ? new Exhaust.Plume(0, tail, 5.0f * flicker, 0.2f, 1, true, 0xFFF4E0, 0xFF8A30)
                : new Exhaust.Plume(0, tail, 2.6f * flicker, 0.15f, 0.85f, false, 0xFFF0D0, 0xFF7A28);
    }

    @Override
    public boolean shouldRender(InterceptorEntity e, Frustum frustum, double x, double y, double z) {
        // модель и факел длиннее габаритов сущности
        return frustum.isVisible(e.getBoundingBox().inflate(8));
    }

    @Override
    public ResourceLocation getTextureLocation(InterceptorEntity e) {
        return InventoryMenu.BLOCK_ATLAS;
    }
}
