package ua.zentix.airstrike.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.inventory.InventoryMenu;
import org.joml.Quaternionf;
import ua.zentix.airstrike.entity.SpentBoosterEntity;
import ua.zentix.airstrike.strike.WeaponType;

/** Отработавший ускоритель кувыркается в падении; на земле лежит на боку. */
public class SpentBoosterRenderer extends EntityRenderer<SpentBoosterEntity> {
    public SpentBoosterRenderer(EntityRendererProvider.Context ctx) {
        super(ctx);
        this.shadowRadius = 0.3f;
    }

    @Override
    public void render(SpentBoosterEntity e, float entityYaw, float partialTick, PoseStack pose, MultiBufferSource buffers, int packedLight) {
        boolean missile = e.weapon() == WeaponType.MISSILE;
        float t = e.age() + partialTick;
        pose.pushPose();
        pose.translate(0, missile ? 0.45 : 0.2, 0);
        float tumble = e.landed() ? 90 : e.getXRot() + t * e.spin;
        pose.mulPose(new Quaternionf().rotationYXZ(-e.getYRot() * Mth.DEG_TO_RAD, tumble * Mth.DEG_TO_RAD, 0));
        // сетка ускорителя — в системе снаряда: сдвинуть её середину в начало
        if (missile) pose.translate(0, 0, 6.3);
        else pose.translate(0, 0.58, 2.43);
        (missile ? WeaponModels.Mesh.MISSILE_BOOSTER : WeaponModels.Mesh.DRONE_BOOSTER).draw(pose, buffers, packedLight);
        pose.popPose();
        super.render(e, entityYaw, partialTick, pose, buffers, packedLight);
    }

    @Override
    public ResourceLocation getTextureLocation(SpentBoosterEntity e) {
        return InventoryMenu.BLOCK_ATLAS;
    }
}
