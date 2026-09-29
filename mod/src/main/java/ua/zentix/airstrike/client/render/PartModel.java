package ua.zentix.airstrike.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.RenderTypeHelper;
import net.neoforged.neoforge.client.model.data.ModelData;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToIntFunction;

/**
 * Модель из блоков: каждая деталь — блок в кубе [0, 1]³, сдвинутый и растянутый до своих размеров (в блоках).
 * Ось +Z модели — нос, +Y — верх, +X — левый борт. Свет — мира в точке сущности, как у остальных её деталей.
 * Грани всех деталей собираются в {@link QuadMesh} по слоям (заново после перезагрузки моделей) и рисуются так же,
 * как их нарисовал бы {@code renderSingleBlock}: тот же слой сущности на слой блока, та же окраска блока, — но без
 * прохода по блоку за раз в каждом кадре.
 */
public final class PartModel {
    private final List<Part> parts;
    private final QuadMesh.Cached<Map<RenderType, QuadMesh>> layers = new QuadMesh.Cached<>(this::compile);

    private PartModel(List<Part> parts) {
        this.parts = List.copyOf(parts);
    }

    public static Builder builder() {
        return new Builder();
    }

    public void render(PoseStack pose, MultiBufferSource buffers, int packedLight) {
        layers.get().forEach((type, mesh) ->
                mesh.draw(pose.last(), buffers.getBuffer(type), QuadMesh.white(1), packedLight, OverlayTexture.NO_OVERLAY));
    }

    /**
     * Грани блока — те же, что берёт {@code renderSingleBlock}: все слои модели (слой сущности для каждого —
     * {@link RenderTypeHelper#getEntityRenderType}), каждая сторона и общие грани с сидом 42, окраска — по {@code tintIndex}.
     */
    private Map<RenderType, QuadMesh> compile() {
        Minecraft mc = Minecraft.getInstance();
        Map<RenderType, QuadMesh.Builder> builders = new LinkedHashMap<>();
        RandomSource random = RandomSource.create();
        for (Part p : parts) {
            BakedModel model = mc.getBlockRenderer().getBlockModel(p.state);
            int color = mc.getBlockColors().getColor(p.state, null, null, 0);
            ToIntFunction<BakedQuad> tint = q -> q.isTinted() ? color : 0xFFFFFF;
            Matrix4f transform = new Matrix4f().translation(p.tx, p.ty, p.tz).scale(p.sx, p.sy, p.sz);
            random.setSeed(42);
            for (RenderType layer : model.getRenderTypes(p.state, random, ModelData.EMPTY)) {
                QuadMesh.Builder b = builders.computeIfAbsent(RenderTypeHelper.getEntityRenderType(layer, false), t -> QuadMesh.builder());
                for (Direction side : Direction.values()) {
                    random.setSeed(42);
                    b.add(model.getQuads(p.state, side, random, ModelData.EMPTY, layer), transform, tint);
                }
                random.setSeed(42);
                b.add(model.getQuads(p.state, null, random, ModelData.EMPTY, layer), transform, tint);
            }
        }
        Map<RenderType, QuadMesh> meshes = new LinkedHashMap<>();
        builders.forEach((type, b) -> meshes.put(type, b.build()));
        return meshes;
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
