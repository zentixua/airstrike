package ua.zentix.airstrike.client.fx.particle;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.ResourceLocation;

/**
 * Слои частиц эффектов. Оба полупрозрачные и без записи глубины: мягкие края клубов не «срезают» соседние клубы,
 * как у ванильного дыма. У каждого слоя своя очередь в движке частиц (до 16384 штук), ванильным частицам они
 * места не занимают. Искры и вспышки — прибавлением света: раскаляют то, что за ними. Пламя — обычным смешиванием:
 * сложение красного с голубым небом дало бы розовый.
 */
public final class FxRenderTypes {
    /** Атлас частиц (ванильная константа помечена устаревшей). */
    private static final ResourceLocation PARTICLES = ResourceLocation.withDefaultNamespace("textures/atlas/particles.png");

    private FxRenderTypes() {}

    /** Дым, пыль, пар, пламя: обычное смешивание по прозрачности. */
    public static final ParticleRenderType BLEND = new ParticleRenderType() {
        @Override
        public BufferBuilder begin(Tesselator tesselator, TextureManager textures) {
            RenderSystem.depthMask(false);
            RenderSystem.setShaderTexture(0, PARTICLES);
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            return tesselator.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.PARTICLE);
        }

        @Override
        public String toString() {
            return "airstrike:blend";
        }
    };

    /** Искры, вспышки: свет складывается. */
    public static final ParticleRenderType GLOW = new ParticleRenderType() {
        @Override
        public BufferBuilder begin(Tesselator tesselator, TextureManager textures) {
            RenderSystem.depthMask(false);
            RenderSystem.setShaderTexture(0, PARTICLES);
            RenderSystem.enableBlend();
            RenderSystem.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE);
            return tesselator.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.PARTICLE);
        }

        @Override
        public String toString() {
            return "airstrike:glow";
        }
    };
}
