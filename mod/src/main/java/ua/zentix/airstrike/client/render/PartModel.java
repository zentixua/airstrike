package ua.zentix.airstrike.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.model.data.ModelData;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;

/**
 * Модель из блоков: каждая деталь — блок в кубе [0, 1]³, сдвинутый и растянутый до своих размеров (в блоках).
 * Ось +Z модели — нос, +Y — верх, +X — левый борт. Свет — мира в точке сущности, как у остальных её деталей.
 * Грани всех деталей собираются в одну {@link QuadMesh} (заново после перезагрузки моделей), а не проходят
 * {@code renderSingleBlock} по блоку за раз в каждом кадре. Детали — блоки без окраски по биому (бетон, терракота, железо).
 */
public final class PartModel {
    private final List<Part> parts;
    private final QuadMesh.Cached mesh = new QuadMesh.Cached(this::compile);

    private PartModel(List<Part> parts) {
        this.parts = List.copyOf(parts);
    }

    public static Builder builder() {
        return new Builder();
    }

    public void render(PoseStack pose, MultiBufferSource buffers, int packedLight) {
        mesh.get().draw(pose.last(), buffers.getBuffer(WeaponRenderTypes.MODEL), QuadMesh.white(1), packedLight, OverlayTexture.NO_OVERLAY);
    }

    /** Грани блока — те же, что берёт {@code renderSingleBlock}: все слои модели, каждая сторона и общие, сид 42. */
    private QuadMesh compile() {
        QuadMesh.Builder b = QuadMesh.builder();
        RandomSource random = RandomSource.create();
        for (Part p : parts) {
            BakedModel model = Minecraft.getInstance().getBlockRenderer().getBlockModel(p.state);
            Matrix4f transform = new Matrix4f().translation(p.tx, p.ty, p.tz).scale(p.sx, p.sy, p.sz);
            random.setSeed(42);
            for (RenderType layer : model.getRenderTypes(p.state, random, ModelData.EMPTY)) {
                for (Direction side : Direction.values()) {
                    random.setSeed(42);
                    b.add(model.getQuads(p.state, side, random, ModelData.EMPTY, layer), transform);
                }
                random.setSeed(42);
                b.add(model.getQuads(p.state, null, random, ModelData.EMPTY, layer), transform);
            }
        }
        return b.build();
    }

    private record Part(BlockState state, float tx, float ty, float tz, float sx, float sy, float sz) {}

    public static final class Builder {
        private final List<Part> parts = new ArrayList<>();

        /** Деталь: блок, сдвиг его угла и размеры. */
        public Builder block(Block block, float tx, float ty, float tz, float sx, float sy, float sz) {
            parts.add(new Part(block.defaultBlockState(), tx, ty, tz, sx, sy, sz));
            return this;
        }

        public PartModel build() {
            return new PartModel(parts);
        }
    }
}
