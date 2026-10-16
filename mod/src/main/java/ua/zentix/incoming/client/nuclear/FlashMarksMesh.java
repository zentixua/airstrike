package ua.zentix.incoming.client.nuclear;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.event.AddSectionGeometryEvent;
import net.neoforged.neoforge.client.model.IQuadTransformer;
import net.neoforged.neoforge.client.model.data.ModelData;
import net.neoforged.neoforge.client.model.lighting.QuadLighter;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.nuclear.flash.FlashChar;
import ua.zentix.incoming.nuclear.flash.FlashMarks;
import ua.zentix.incoming.nuclear.flash.FlashShadows;

import java.util.ArrayList;
import java.util.List;

/**
 * Следы вспышки в сетке секции ({@link AddSectionGeometryEvent}, без миксинов): грань со следом рисуется ещё раз
 * теми же четырёхугольниками модели блока, в том же слое, после всех блоков секции, с вершинами в тех же местах и
 * цветом вершин темнее — при равной глубине поздний побеждает, и грань выглядит обугленной, с текстурой блока под
 * гарью. Свет вершин — общий конвейер NeoForge ({@link QuadLighter}: сглаженный свет и тень сторон, как у блоков).
 * <p>
 * Ступень гари в углах грани — среднее по соседним граням той же плоскости (те, что могли бы обуглиться, но чистые,
 * идут нулём; край стены — нет): гарь переходит в чистое пятнами, а не клетками. Силуэт — полосы клеток 16×16
 * (пиксель текстуры) поверх обугленной грани: где тело заслонило шар, гарь слабее — {@code ступень · (1 − тень/3)}.
 * Метка рисуется, только пока на месте тот же блок (хеш) и грань открыта.
 */
public final class FlashMarksMesh {
    /** Сколько темнеет цвет на полной гари: каналы R, G, B (чуть теплее к коричневому). */
    private static final float DARK_R = 0.86f, DARK_G = 0.89f, DARK_B = 0.92f;
    /** На сколько клетки силуэта впереди грани, блоки: зазор глубины виден примерно до 50 блоков. */
    private static final float LIFT = 1 / 256f;

    private FlashMarksMesh() {}

    /** Секция строится заново: следы в ней — снимком сейчас, рисование — в потоке сборки. */
    public static void onSection(AddSectionGeometryEvent e) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || e.getLevel() != mc.level) return;
        BlockPos o = e.getSectionOrigin();
        int cx = o.getX() >> 4, cz = o.getZ() >> 4;
        long now = mc.level.getGameTime();
        List<FlashMarks.Layer> layers = FlashMarksClient.visible(ChunkPos.asLong(cx, cz), now);
        if (layers == null) return;
        Section s = Section.of(layers, cx, o.getY(), cz, now);
        if (s != null) e.addRenderer(s::render);
    }

    /** Вклад одного подрыва в грань: ступень и маска силуэта (null — без силуэта). */
    private record Part(int level, @Nullable long[] mask) {}

    /** Грань секции со всеми вкладами. */
    private static final class Face {
        final int key, hash;
        final List<Part> parts = new ArrayList<>(1);

        Face(int key, int hash) {
            this.key = key;
            this.hash = hash;
        }

        boolean masked() {
            for (Part p : parts) if (p.mask != null) return true;
            return false;
        }

        /** Ступень грани без силуэта (для углов и соседей). */
        int level() {
            int l = 0;
            for (Part p : parts) l = Math.max(l, p.level);
            return l;
        }

        /** Доля гари клетки: наибольшая по подрывам, с тенью силуэта. */
        double cell(int u, int v) {
            double out = 0;
            for (Part p : parts) {
                int shade = p.mask == null ? 0 : (int) (p.mask[(2 * (v * FlashMarks.CELLS + u)) >> 6] >>> ((2 * (v * FlashMarks.CELLS + u)) & 63)) & 3;
                out = Math.max(out, p.level * (1 - shade / 3.0));
            }
            return out;
        }
    }

    /** Снимок следов секции для потока сборки. */
    private record Section(int cx, int sy, int cz, List<Face> faces, Neighbours neighbours) {
        @Nullable
        static Section of(List<FlashMarks.Layer> layers, int cx, int y0, int cz, long now) {
            Int2ObjectOpenHashMap<Face> faces = new Int2ObjectOpenHashMap<>();
            for (FlashMarks.Layer l : layers) {
                // силуэт подрыва заменяет его же гарь этой грани
                Int2ObjectOpenHashMap<long[]> masks = new Int2ObjectOpenHashMap<>();
                for (int k = 0; k < l.shadows().length; k++) {
                    int m = l.shadows()[k];
                    if (FlashChar.y(m) >> 4 != y0 >> 4) continue;
                    masks.put(m & FlashChar.KEY_MASK, java.util.Arrays.copyOfRange(l.masks(), k * FlashMarks.MASK_LONGS, (k + 1) * FlashMarks.MASK_LONGS));
                    add(faces, m, masks.get(m & FlashChar.KEY_MASK));
                }
                for (int m : l.chars()) {
                    if (FlashChar.y(m) >> 4 != y0 >> 4 || masks.containsKey(m & FlashChar.KEY_MASK)) continue;
                    add(faces, m, null);
                }
            }
            if (faces.isEmpty()) return null;
            return new Section(cx, y0 >> 4, cz, new ArrayList<>(faces.values()), Neighbours.around(cx, cz, now));
        }

        private static void add(Int2ObjectOpenHashMap<Face> faces, int mark, @Nullable long[] mask) {
            Face f = faces.computeIfAbsent(mark & FlashChar.KEY_MASK, k -> new Face(k, FlashChar.hashOf(mark)));
            f.parts.add(new Part(FlashChar.level(mark), mask));
        }

        void render(AddSectionGeometryEvent.SectionRenderingContext ctx) {
            BlockAndTintGetter region = ctx.getRegion();
            Minecraft mc = Minecraft.getInstance();
            RandomSource rand = RandomSource.create();
            PoseStack pose = ctx.getPoseStack();
            BlockPos.MutableBlockPos front = new BlockPos.MutableBlockPos();
            for (Face f : faces) {
                BlockPos pos = new BlockPos(cx << 4 | FlashChar.lx(f.key), FlashChar.y(f.key), cz << 4 | FlashChar.lz(f.key));
                Direction dir = FlashChar.face(f.key);
                BlockState state = region.getBlockState(pos);
                if (state.getRenderShape() != RenderShape.MODEL || FlashChar.hash(state.getBlock()) != f.hash || FlashChar.material(state) == FlashChar.NONE) continue;
                front.setWithOffset(pos, dir);
                if (!Block.shouldRenderFace(state, region, pos, dir, front)) continue;
                double[] corners = corners(region, pos, dir, f.level());
                BakedModel model = mc.getBlockRenderer().getBlockModel(state);
                ModelData data = model.getModelData(region, pos, state, region.getModelData(pos));
                long seed = state.getSeed(pos);
                rand.setSeed(seed);
                for (RenderType type : model.getRenderTypes(state, rand, data)) {
                    rand.setSeed(seed);
                    List<BakedQuad> quads = model.getQuads(state, dir, rand, data, type);
                    if (quads.isEmpty()) continue;
                    boolean smooth = Minecraft.useAmbientOcclusion() && switch (model.useAmbientOcclusion(state, data, type)) {
                        case TRUE -> true;
                        case DEFAULT -> state.getLightEmission(region, pos) == 0;
                        case FALSE -> false;
                    };
                    QuadLighter lighter = ctx.getQuadLighter(smooth);
                    lighter.setup(region, pos, state);
                    VertexConsumer out = ctx.getOrCreateChunkBuffer(type);
                    pose.pushPose();
                    pose.translate(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15);
                    for (BakedQuad q : quads) emit(q, dir, f, corners, lighter, out, pose.last());
                    pose.popPose();
                    lighter.reset();
                }
            }
        }

        /**
         * Ступень гари в углах грани: {@code [u0v0, u1v0, u0v1, u1v1]} по осям клеток ({@link FlashShadows#axes}) —
         * среднее по своей грани и соседним в плоскости, которые есть (со следом или чистые, но обугливаемые и открытые).
         */
        private double[] corners(BlockAndTintGetter region, BlockPos pos, Direction dir, int own) {
            int[] ax = FlashShadows.axes(dir);
            double[] out = new double[4];
            for (int c = 0; c < 4; c++) {
                int du = (c & 1) == 0 ? -1 : 1, dv = (c & 2) == 0 ? -1 : 1;
                double sum = own;
                int n = 1;
                for (int k = 1; k <= 3; k++) {
                    int[] off = new int[3];
                    if ((k & 1) != 0) off[ax[0]] = du;
                    if ((k & 2) != 0) off[ax[1]] = dv;
                    int l = neighbour(region, pos.offset(off[0], off[1], off[2]), dir);
                    if (l < 0) continue;
                    sum += l;
                    n++;
                }
                out[c] = sum / n;
            }
            return out;
        }

        /** Ступень соседней грани той же стороны: со следом — его, чистая обугливаемая и открытая — 0, иначе −1. */
        private int neighbour(BlockAndTintGetter region, BlockPos at, Direction dir) {
            int l = neighbours.level(at, dir);
            if (l > 0) return l;
            BlockState st = region.getBlockState(at);
            if (FlashChar.material(st) == FlashChar.NONE) return -1;
            BlockPos ahead = at.relative(dir);
            return Block.shouldRenderFace(st, region, at, dir, ahead) ? 0 : -1;
        }
    }

    /** Ступени граней в чанке секции и вокруг (для углов у края чанка): снимок карт {@link FlashMarksClient}. */
    private record Neighbours(int cx, int cz, Int2IntOpenHashMap[] maps) {
        static Neighbours around(int cx, int cz, long now) {
            Int2IntOpenHashMap[] maps = new Int2IntOpenHashMap[9];
            for (int dz = -1; dz <= 1; dz++) {
                for (int dx = -1; dx <= 1; dx++) maps[(dz + 1) * 3 + dx + 1] = FlashMarksClient.levels(ChunkPos.asLong(cx + dx, cz + dz), now);
            }
            return new Neighbours(cx, cz, maps);
        }

        int level(BlockPos at, Direction dir) {
            int dx = (at.getX() >> 4) - cx, dz = (at.getZ() >> 4) - cz;
            if (dx < -1 || dx > 1 || dz < -1 || dz > 1) return 0;
            Int2IntOpenHashMap m = maps[(dz + 1) * 3 + dx + 1];
            return m == null ? 0 : m.get(FlashChar.key(at.getX() & 15, at.getY(), at.getZ() & 15, dir));
        }
    }

    // ---------------------------------------------------------------- четырёхугольники

    /**
     * Грань ещё раз: целиком, темнее по углам, в тех же местах вершин, что у модели (равная глубина — побеждает
     * поздний). Силуэт — поверх: клетки в тени тела светлее, полосами, на {@link #LIFT} впереди грани — полосы в тех же
     * местах дрались бы за глубину с гранью модели (интерполяция глубины по разным треугольникам расходится в последних
     * битах) и шли бы светлыми строками. Вдали, где точности глубины не хватает на этот зазор, силуэт сливается с гарью.
     */
    private static void emit(BakedQuad q, Direction dir, Face f, double[] corners, QuadLighter lighter, VertexConsumer out, PoseStack.Pose pose) {
        int[] v = q.getVertices();
        int[] ax = FlashShadows.axes(dir);
        int stride = IQuadTransformer.STRIDE;
        int[] copy = v.clone();
        for (int i = 0; i < 4; i++) {
            double value = bilerp(corners, pos(v, i, ax[0]), pos(v, i, ax[1]));
            copy[i * stride + IQuadTransformer.COLOR] = darken(v[i * stride + IQuadTransformer.COLOR], value);
        }
        lighter.process(out, pose, new BakedQuad(copy, q.getTintIndex(), q.getDirection(), q.getSprite(), q.isShade(), q.hasAmbientOcclusion()), OverlayTexture.NO_OVERLAY);
        if (!f.masked()) return;
        // углы четырёхугольника по осям клеток: роль вершины — (u больше, v больше)
        float u0 = Float.MAX_VALUE, u1 = -Float.MAX_VALUE, v0 = Float.MAX_VALUE, v1 = -Float.MAX_VALUE;
        for (int i = 0; i < 4; i++) {
            float pu = pos(v, i, ax[0]), pv = pos(v, i, ax[1]);
            u0 = Math.min(u0, pu);
            u1 = Math.max(u1, pu);
            v0 = Math.min(v0, pv);
            v1 = Math.max(v1, pv);
        }
        int[] role = new int[4];
        int[] at = {-1, -1, -1, -1};
        for (int i = 0; i < 4; i++) {
            boolean hu = pos(v, i, ax[0]) > (u0 + u1) / 2, hv = pos(v, i, ax[1]) > (v0 + v1) / 2;
            role[i] = (hu ? 1 : 0) | (hv ? 2 : 0);
            at[role[i]] = i;
        }
        if (at[0] < 0 || at[1] < 0 || at[2] < 0 || at[3] < 0 || u1 - u0 < 1e-4 || v1 - v0 < 1e-4) return;
        int normal = dir.getAxis().ordinal();
        float lift = LIFT * dir.getAxisDirection().getStep();
        // полосы клеток с одной тенью: клетка (u, v) — [u/16, (u+1)/16] × [v/16, (v+1)/16] блока
        int n = FlashMarks.CELLS;
        for (int cv = 0; cv < n; cv++) {
            float sv0 = Math.max(v0, cv / (float) n), sv1 = Math.min(v1, (cv + 1) / (float) n);
            if (sv1 <= sv0) continue;
            int cu = 0;
            while (cu < n) {
                double share = f.cell(cu, cv) / Math.max(1, f.level());
                int end = cu + 1;
                while (end < n && f.cell(end, cv) / Math.max(1, f.level()) == share) end++;
                float su0 = Math.max(u0, cu / (float) n), su1 = Math.min(u1, end / (float) n);
                cu = end;
                // без тени клетка уже тёмная — её рисует сама грань
                if (su1 <= su0 || share >= 1) continue;
                int[] sub = v.clone();
                for (int i = 0; i < 4; i++) {
                    float pu = (role[i] & 1) != 0 ? su1 : su0, pv = (role[i] & 2) != 0 ? sv1 : sv0;
                    float s = (pu - u0) / (u1 - u0), t = (pv - v0) / (v1 - v0);
                    int o = i * stride;
                    setPos(sub, i, ax[0], pu);
                    setPos(sub, i, ax[1], pv);
                    setPos(sub, i, normal, pos(v, i, normal) + lift);
                    // текстура — по углам исходного четырёхугольника
                    for (int c = 0; c < 2; c++) {
                        float a = uv(v, at[0], c), b = uv(v, at[1], c), cc = uv(v, at[2], c), dd = uv(v, at[3], c);
                        float val = (a * (1 - s) + b * s) * (1 - t) + (cc * (1 - s) + dd * s) * t;
                        sub[o + IQuadTransformer.UV0 + c] = Float.floatToRawIntBits(val);
                    }
                    int colour = v[at[0] * stride + IQuadTransformer.COLOR];
                    sub[o + IQuadTransformer.COLOR] = darken(colour, bilerp(corners, pu, pv) * share);
                }
                lighter.process(out, pose, new BakedQuad(sub, q.getTintIndex(), q.getDirection(), q.getSprite(), q.isShade(), q.hasAmbientOcclusion()),
                        OverlayTexture.NO_OVERLAY);
            }
        }
    }

    private static float pos(int[] v, int vertex, int axis) {
        return Float.intBitsToFloat(v[vertex * IQuadTransformer.STRIDE + IQuadTransformer.POSITION + axis]);
    }

    private static void setPos(int[] v, int vertex, int axis, float value) {
        v[vertex * IQuadTransformer.STRIDE + IQuadTransformer.POSITION + axis] = Float.floatToRawIntBits(value);
    }

    private static float uv(int[] v, int vertex, int c) {
        return Float.intBitsToFloat(v[vertex * IQuadTransformer.STRIDE + IQuadTransformer.UV0 + c]);
    }

    /** Ступень в точке грани по углам {@code [u0v0, u1v0, u0v1, u1v1]}. */
    private static double bilerp(double[] c, float u, float v) {
        double s = Math.clamp(u, 0, 1), t = Math.clamp(v, 0, 1);
        return (c[0] * (1 - s) + c[1] * s) * (1 - t) + (c[2] * (1 - s) + c[3] * s) * t;
    }

    /** Цвет вершины (ABGR) темнее на ступень гари {@code level} из {@link FlashChar#LEVELS}. */
    static int darken(int abgr, double level) {
        double f = Math.clamp(level / FlashChar.LEVELS, 0, 1);
        if (f <= 0) return abgr;
        f = Math.pow(f, 0.9);
        int r = abgr & 0xFF, g = abgr >> 8 & 0xFF, b = abgr >> 16 & 0xFF;
        r = (int) Math.round(r * (1 - DARK_R * f));
        g = (int) Math.round(g * (1 - DARK_G * f));
        b = (int) Math.round(b * (1 - DARK_B * f));
        return abgr & 0xFF000000 | b << 16 | g << 8 | r;
    }
}
