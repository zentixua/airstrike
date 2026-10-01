package ua.zentix.airstrike.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.resources.ResourceLocation;
import org.joml.Quaternionf;
import ua.zentix.airstrike.client.fx.Exhaust;
import ua.zentix.airstrike.entity.BunkerBusterEntity;
import ua.zentix.airstrike.entity.StrikeProjectile;

/**
 * Снаряд — модель {@link WeaponModels}, повёрнутая по курсу и тангажу, плюс крен в вираже. Позиция приходит каждый
 * тик и плавно интерполируется между тиками. За соплом — факел двигателя ({@link PlumeRenderer}).
 */
public class StrikeProjectileRenderer<T extends StrikeProjectile> extends EntityRenderer<T> {
    private final WeaponModels.Look look;
    /** Поза кадра: рендерер рисует сущности по одной в потоке отрисовки. */
    private final ProjectilePose state = new ProjectilePose();
    private final WeaponModels.Buffers parts = new WeaponModels.Buffers();

    public StrikeProjectileRenderer(EntityRendererProvider.Context ctx, WeaponModels.Look look) {
        super(ctx);
        this.look = look;
        this.shadowRadius = 0;
    }

    @Override
    public void render(T entity, float entityYaw, float partialTick, PoseStack pose, MultiBufferSource buffers, int packedLight) {
        if (!entity.isActive() || entity instanceof BunkerBusterEntity b && b.isDrilling()) return;
        Quaternionf rotation = state.set(entity, partialTick).rotation(new Quaternionf());
        pose.pushPose();
        pose.mulPose(rotation);
        look.render(state, pose, parts.into(buffers, packedLight));
        Exhaust.Plume plume = Exhaust.plume(entity, partialTick);
        if (plume != null) PlumeRenderer.render(plume, pose, buffers, rotation, entity.getPosition(partialTick), entityRenderDispatcher.camera);
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
