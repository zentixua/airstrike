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
import ua.zentix.airstrike.entity.LauncherEntity;
import ua.zentix.airstrike.strike.WeaponType;

/** Пусковая: прицеп по курсу и пакет, поднимающийся на угол возвышения вокруг своей оси. */
public class LauncherRenderer extends EntityRenderer<LauncherEntity> {
    public LauncherRenderer(EntityRendererProvider.Context ctx) {
        super(ctx);
        this.shadowRadius = 2.0f;
    }

    @Override
    public void render(LauncherEntity e, float entityYaw, float partialTick, PoseStack pose, MultiBufferSource buffers, int packedLight) {
        float elev = e.deployedElevation(e.level().getGameTime(), partialTick);
        pose.pushPose();
        pose.mulPose(new Quaternionf().rotationY(-e.getYRot() * Mth.DEG_TO_RAD));
        LaunchModels.TRAILER.render(pose, buffers, 0);
        pose.translate(0, LauncherEntity.PIVOT_UP, -LauncherEntity.PIVOT_BACK);
        pose.mulPose(new Quaternionf().rotationX(-elev * Mth.DEG_TO_RAD));
        (switch (e.weapon()) {
            case MISSILE -> LaunchModels.MISSILE_RACK;
            case ROCKET -> LaunchModels.ROCKET_RACK;
            default -> LaunchModels.DRONE_RACK;
        }).render(pose, buffers, 0);
        pose.popPose();
        super.render(e, entityYaw, partialTick, pose, buffers, packedLight);
    }

    @Override
    public boolean shouldRender(LauncherEntity e, Frustum frustum, double x, double y, double z) {
        return frustum.isVisible(e.getBoundingBox().inflate(10));
    }

    @Override
    public ResourceLocation getTextureLocation(LauncherEntity e) {
        return InventoryMenu.BLOCK_ATLAS;
    }
}
