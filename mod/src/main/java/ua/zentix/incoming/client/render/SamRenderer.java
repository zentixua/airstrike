package ua.zentix.incoming.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import org.joml.Quaternionf;
import ua.zentix.incoming.defense.SamBlock;
import ua.zentix.incoming.defense.SamBlockEntity;

/**
 * ЗРК: над блоком (кабина радара — модель блока) — пакет из четырёх вертикальных контейнеров в настоящую величину
 * ({@link WeaponModels.Mesh#SAM_PACK}), повёрнутый по {@link SamBlock#FACING}.
 */
public class SamRenderer implements BlockEntityRenderer<SamBlockEntity> {
    public SamRenderer(BlockEntityRendererProvider.Context ctx) {}

    @Override
    public void render(SamBlockEntity be, float partialTick, PoseStack pose, MultiBufferSource buffers, int packedLight, int packedOverlay) {
        Level level = be.getLevel();
        if (level == null) return;
        // кабина — целый непрозрачный блок: свет внутри неё — ноль, пакет над ней освещён как место над блоком
        int light = LevelRenderer.getLightColor(level, be.getBlockPos().above());
        pose.pushPose();
        pose.translate(0.5, 0, 0.5);
        pose.mulPose(new Quaternionf().rotationY(-be.getBlockState().getValue(SamBlock.FACING).toYRot() * Mth.DEG_TO_RAD));
        WeaponModels.Mesh.SAM_PACK.draw(pose, buffers, light);
        pose.popPose();
    }

    /** Пакет больше блока (контейнеры по 4.3 м, плита шире кабины): без этого его отсекало бы по кубу блока. */
    @Override
    public AABB getRenderBoundingBox(SamBlockEntity be) {
        return new AABB(be.getBlockPos()).inflate(SamBlockEntity.PACK_HALF_WIDTH - 0.5, 0, SamBlockEntity.PACK_HALF_WIDTH - 0.5)
                .expandTowards(0, SamBlockEntity.PACK_TOP - 1, 0);
    }

    /**
     * Пакет выходит за секцию чанка блока: секцию отсекло (взгляд вверх у самого пакета, склон), а верх пакета в кадре —
     * без этого пропал бы весь пакет. Такие рисуются отдельным списком, рамка и дальность проверяются так же.
     */
    @Override
    public boolean shouldRenderOffScreen(SamBlockEntity be) {
        return true;
    }

    /** Пакет видно издалека, как пусковую (по умолчанию блок-сущности рисуются до 64 блоков). */
    @Override
    public int getViewDistance() {
        return 160;
    }
}
