package ua.zentix.airstrike.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.RenderShape;
import net.neoforged.neoforge.client.model.data.ModelData;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Quaternionf;
import ua.zentix.airstrike.entity.DebrisEntity;

/** Обломок кувыркается в полёте; блок рисуется так же, как падающий блок. */
public class DebrisRenderer extends EntityRenderer<DebrisEntity> {
    public DebrisRenderer(EntityRendererProvider.Context ctx) {
        super(ctx);
        this.shadowRadius = 0.4f;
    }

    @Override
    public void render(DebrisEntity e, float entityYaw, float partialTick, PoseStack pose, MultiBufferSource buffers, int packedLight) {
        BlockState state = e.blockState();
        if (state.getRenderShape() != RenderShape.MODEL) return;
        float t = e.age() + partialTick;
        boolean airborne = !e.onGround();
        pose.pushPose();
        pose.translate(0, 0.5, 0);
        if (airborne) pose.mulPose(new Quaternionf().rotationXYZ(t * e.spinX * 0.0175f, 0, t * e.spinZ * 0.0175f));
        pose.translate(-0.5, -0.5, -0.5);
        net.minecraft.client.Minecraft.getInstance().getBlockRenderer().renderSingleBlock(state, pose, buffers, packedLight, OverlayTexture.NO_OVERLAY, ModelData.EMPTY, null);
        pose.popPose();
        super.render(e, entityYaw, partialTick, pose, buffers, packedLight);
    }

    @Override
    public ResourceLocation getTextureLocation(DebrisEntity e) {
        return InventoryMenu.BLOCK_ATLAS;
    }
}
