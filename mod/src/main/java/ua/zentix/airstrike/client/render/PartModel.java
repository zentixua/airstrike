package ua.zentix.airstrike.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.client.model.data.ModelData;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * Модель из блоков — те же детали, что были у block/item_display датапака, с теми же преобразованиями:
 * translation · left_rotation · scale. «Блочная» деталь рисует блок в кубе [0, 1]³, «предметная» (item_display
 * с режимом none) — куб с центром в начале координат. Ось +Z модели — нос, +Y — верх, +X — левый борт.
 */
public final class PartModel {
    /** Свет деталей как у дисплеев датапака: небо 15, блоки 11 (видно и ночью). */
    public static final int LIGHT = LightTexture.pack(11, 15);
    public static final int GLOW = LightTexture.pack(15, 15);

    private final List<Part> parts;

    private PartModel(List<Part> parts) {
        this.parts = List.copyOf(parts);
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * @param spin угол вращения винта (рад) для деталей, помеченных как винт
     */
    public void render(PoseStack pose, MultiBufferSource buffers, float spin) {
        BlockRenderDispatcher blocks = Minecraft.getInstance().getBlockRenderer();
        for (Part p : parts) {
            pose.pushPose();
            pose.translate(p.translation.x, p.translation.y, p.translation.z);
            if (p.spin) pose.mulPose(new Quaternionf().rotationZ(spin));
            pose.mulPose(p.rotation);
            pose.scale(p.scale.x, p.scale.y, p.scale.z);
            if (p.centered) pose.translate(-0.5f, -0.5f, -0.5f);
            blocks.renderSingleBlock(p.state, pose, buffers, p.glow ? GLOW : LIGHT, OverlayTexture.NO_OVERLAY, ModelData.EMPTY, null);
            pose.popPose();
        }
    }

    record Part(BlockState state, boolean centered, Vector3f translation, Quaternionf rotation, Vector3f scale, boolean glow, boolean spin) {}

    public static final class Builder {
        private final List<Part> parts = new ArrayList<>();
        private boolean glow, spin;

        /** Деталь block_display: блок в кубе [0,1]³, сдвиг и масштаб. */
        public Builder block(Block block, float tx, float ty, float tz, float sx, float sy, float sz) {
            return block(block.defaultBlockState(), tx, ty, tz, sx, sy, sz, new Quaternionf());
        }

        public Builder block(BlockState state, float tx, float ty, float tz, float sx, float sy, float sz, Quaternionf rotation) {
            parts.add(new Part(state, false, new Vector3f(tx, ty, tz), rotation, new Vector3f(sx, sy, sz), glow, spin));
            glow = spin = false;
            return this;
        }

        /** Деталь item_display (режим none): куб с центром в начале координат, поворот вокруг оси. */
        public Builder item(Block block, float angle, Vector3f axis, float tx, float ty, float tz, float sx, float sy, float sz) {
            parts.add(new Part(block.defaultBlockState(), true, new Vector3f(tx, ty, tz), new Quaternionf().rotationAxis(angle, axis),
                    new Vector3f(sx, sy, sz), glow, spin));
            glow = spin = false;
            return this;
        }

        /** Следующая деталь светится сама (сопло двигателя). */
        public Builder glowing() {
            glow = true;
            return this;
        }

        /** Следующая деталь — лопасть винта (вращается вокруг оси Z). */
        public Builder propeller() {
            spin = true;
            return this;
        }

        public PartModel build() {
            return new PartModel(parts);
        }
    }
}
