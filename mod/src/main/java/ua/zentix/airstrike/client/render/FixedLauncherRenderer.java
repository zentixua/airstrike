package ua.zentix.airstrike.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import org.joml.Quaternionf;
import ua.zentix.airstrike.launcher.FixedLauncherBlockEntity;

/**
 * Стационарная пусковая: поворотный круг над блоком и пакет оружия задачи — тот же, что у прицепа
 * ({@link LauncherRenderer#renderRack}), по курсу пакета и с подъёмом на угол возвышения.
 */
public class FixedLauncherRenderer implements BlockEntityRenderer<FixedLauncherBlockEntity> {
    public FixedLauncherRenderer(BlockEntityRendererProvider.Context ctx) {}

    @Override
    public void render(FixedLauncherBlockEntity be, float partialTick, PoseStack pose, MultiBufferSource buffers, int packedLight, int packedOverlay) {
        Level level = be.getLevel();
        if (level == null) return;
        // блок целый и непрозрачный: свет внутри него — ноль, круг и пакет над ним освещены как место над блоком
        int light = LevelRenderer.getLightColor(level, be.getBlockPos().above());
        pose.pushPose();
        pose.translate(0.5, 0, 0.5);
        pose.mulPose(new Quaternionf().rotationY(-be.yaw() * Mth.DEG_TO_RAD));
        LaunchModels.PAD.render(pose, buffers, light);
        if (be.hasRack()) LauncherRenderer.renderRack(be, level.getGameTime(), partialTick, pose, buffers, light);
        pose.popPose();
    }

    /** Пакет больше блока (контейнеры ракет — 7 м): без этого его отсекало бы по кубу блока. */
    @Override
    public AABB getRenderBoundingBox(FixedLauncherBlockEntity be) {
        return new AABB(be.getBlockPos()).inflate(7, 0, 7).expandTowards(0, 7, 0);
    }

    /** Пакет видно издалека, как прицеп (по умолчанию блок-сущности рисуются до 64 блоков). */
    @Override
    public int getViewDistance() {
        return 160;
    }
}
