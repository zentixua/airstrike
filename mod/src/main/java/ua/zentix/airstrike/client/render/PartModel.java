package ua.zentix.airstrike.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.model.data.ModelData;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * Модель из блоков: каждая деталь — блок в кубе [0, 1]³, сдвинутый и растянутый до своих размеров (в блоках).
 * Ось +Z модели — нос, +Y — верх, +X — левый борт. Свет — мира в точке сущности, как у остальных её деталей.
 */
public final class PartModel {
    private final List<Part> parts;

    private PartModel(List<Part> parts) {
        this.parts = List.copyOf(parts);
    }

    public static Builder builder() {
        return new Builder();
    }

    public void render(PoseStack pose, MultiBufferSource buffers, int packedLight) {
        BlockRenderDispatcher blocks = Minecraft.getInstance().getBlockRenderer();
        for (Part p : parts) {
            pose.pushPose();
            pose.translate(p.translation.x, p.translation.y, p.translation.z);
            pose.scale(p.scale.x, p.scale.y, p.scale.z);
            blocks.renderSingleBlock(p.state, pose, buffers, packedLight, OverlayTexture.NO_OVERLAY, ModelData.EMPTY, null);
            pose.popPose();
        }
    }

    private record Part(BlockState state, Vector3f translation, Vector3f scale) {}

    public static final class Builder {
        private final List<Part> parts = new ArrayList<>();

        /** Деталь: блок, сдвиг его угла и размеры. */
        public Builder block(Block block, float tx, float ty, float tz, float sx, float sy, float sz) {
            parts.add(new Part(block.defaultBlockState(), new Vector3f(tx, ty, tz), new Vector3f(sx, sy, sz)));
            return this;
        }

        public PartModel build() {
            return new PartModel(parts);
        }
    }
}
