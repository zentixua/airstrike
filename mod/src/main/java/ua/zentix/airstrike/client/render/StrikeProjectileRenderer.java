package ua.zentix.airstrike.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import org.joml.Quaternionf;
import ua.zentix.airstrike.entity.BunkerBusterEntity;
import ua.zentix.airstrike.entity.StrikeProjectile;

/**
 * Снаряд — модель из блоков, повёрнутая как display-сущность датапака (курс, тангаж), плюс крен в вираже.
 * Позиция приходит каждый тик и плавно интерполируется между тиками.
 */
public class StrikeProjectileRenderer<T extends StrikeProjectile> extends EntityRenderer<T> {
    private final PartModel model;
    /** Скорость винта, рад/тик (0 — винта нет). */
    private final float propellerSpeed;

    public StrikeProjectileRenderer(EntityRendererProvider.Context ctx, PartModel model, float propellerSpeed) {
        super(ctx);
        this.model = model;
        this.propellerSpeed = propellerSpeed;
        this.shadowRadius = 0;
    }

    @Override
    public void render(T entity, float entityYaw, float partialTick, PoseStack pose, MultiBufferSource buffers, int packedLight) {
        if (!entity.isActive() || entity instanceof BunkerBusterEntity b && b.isDrilling()) return;
        float yaw = Mth.rotLerp(partialTick, entity.yRotO, entity.getYRot());
        float pitch = Mth.lerp(partialTick, entity.xRotO, entity.getXRot());
        pose.pushPose();
        pose.mulPose(new Quaternionf().rotationYXZ(-yaw * Mth.DEG_TO_RAD, pitch * Mth.DEG_TO_RAD, entity.roll() * Mth.DEG_TO_RAD));
        model.render(pose, buffers, (entity.age() + partialTick) * propellerSpeed);
        pose.popPose();
        super.render(entity, entityYaw, partialTick, pose, buffers, packedLight);
    }

    @Override
    public boolean shouldRender(T entity, Frustum frustum, double x, double y, double z) {
        // модель длиннее габаритов сущности: проверяем по раздутому объёму
        return entity.isActive() && frustum.isVisible(entity.getBoundingBox().inflate(24));
    }

    @Override
    public ResourceLocation getTextureLocation(T entity) {
        return InventoryMenu.BLOCK_ATLAS;
    }
}
