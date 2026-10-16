package ua.zentix.incoming.client.nuclear;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import ua.zentix.incoming.client.fx.Effects;
import ua.zentix.incoming.client.fx.particle.Fx;
import ua.zentix.incoming.client.fx.particle.FxBudget;
import ua.zentix.incoming.client.fx.particle.FxPool;
import ua.zentix.incoming.grid.GridLights;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.nuclear.flash.FlashChar;
import ua.zentix.incoming.nuclear.model.Fragility;
import ua.zentix.incoming.nuclear.world.ThermalShadow;
import ua.zentix.incoming.registry.ModBlocks;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Что вспышка делает с округой в первые секунды, до волны (только вживую — из перемотки повтора не рождается):
 * <ul>
 *     <li>ЭМИ — фонари и лампы сети (те, что гасит блэкаут, {@link GridLights}) и подстанции в радиусе отключения
 *     коротят: голубая дуга и искры, вразнобой, чаще сразу, до прихода волны к лампе и не позже {@link #ARC_WINDOW}
 *     тиков; дуга трещит — 2–4 разряда через 1–3 тика, у части ламп потом ещё раз. Клиент сам ищет их в своих чанках по палитрам секций, не больше {@link #SECTIONS_PER_TICK} секций за тик;
 *     дуг — не больше {@link #MAX_ARCS} ближних к камере;</li>
 *     <li>дымки с того, что свет поджёг: крыши, земля с травой, листва и деревянные стены лицом к шару курятся тонкими
 *     струйками, пока до них не дойдёт волна. Места — случайные точки перед камерой (в кадре, до {@link #SMOKE_RANGE}
 *     блоков), свет — как у гари ({@link FlashChar}): импульс × угол × тень по картам высот клиента; частицы — своей
 *     группой {@link FxBudget#SCORCH}.</li>
 * </ul>
 */
public final class FlashAftermath {
    /** Дуг ЭМИ, не больше (ближние к камере). */
    static final int MAX_ARCS = 48;
    /** Секций, просмотренных за тик в поисках ламп. */
    static final int SECTIONS_PER_TICK = 48;
    /** Дольше этого (тики) после вспышки лампы не коротят. */
    static final int ARC_WINDOW = 50;
    /** Голубовато-белый свет дуги и его синий край — как у подстанции ({@code GridEffects}). */
    private static final int ARC = 0xDCE8FF, ARC_EDGE = 0x5A7CFF;
    /** Докуда от камеры ищутся дымки, блоки, и проб за тик. */
    static final double SMOKE_RANGE = 128;
    static final int SMOKE_TRIES = 96;
    /** Медиана и разброс света, от которого поверхность курится, кал/см². */
    static final double SMOKE_Q = 5, SMOKE_BETA = 0.6;
    /** Сколько тиков вспышка поджигает новые дымки (основной импульс — первые секунды). */
    static final int SMOKE_TICKS = 80;

    /** Дуг у последнего подрыва (строка сценария клиента). */
    private static volatile int lastArcs = -1;

    private FlashAftermath() {}

    /** Строка {@code SCENARIO}: сколько дуг ЭМИ нашлось у последнего подрыва и сколько мест у дымков. */
    public static String describe() {
        FxPool pool = FxPool.INSTANCE;
        return "emp arcs " + lastArcs + ", scorch smoke " + pool.live(FxBudget.SCORCH) + " live, " + pool.refused(FxBudget.SCORCH) + " refused";
    }

    /** Живой подрыв: ЭМИ и дымки. */
    static void detonation(Detonation d) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) return;
        lastArcs = -1;
        Effects.add(new Emp(d, level));
        Effects.add(new Smoke(d));
    }

    // ---------------------------------------------------------------- ЭМИ

    /** Поиск ламп по секциям у камеры, потом дуги по расписанию. */
    private static final class Emp implements Effects.Effect {
        private final Detonation d;
        private final RandomSource rnd;
        private final List<long[]> sections = new ArrayList<>();
        private final List<Arc> found = new ArrayList<>();
        private List<Arc> arcs;
        private int next;

        Emp(Detonation d, ClientLevel level) {
            this.d = d;
            this.rnd = RandomSource.create(d.seed() ^ 0x5EED);
            // секции в радиусе отключения у камеры: ближние первыми
            Vec3 cam = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
            double radius = d.radiusMax();
            int view = Minecraft.getInstance().options.getEffectiveRenderDistance();
            int ccx = Mth.floor(cam.x) >> 4, ccz = Mth.floor(cam.z) >> 4;
            for (int cz = ccz - view; cz <= ccz + view; cz++) {
                for (int cx = ccx - view; cx <= ccx + view; cx++) {
                    double x = (cx << 4) + 8, z = (cz << 4) + 8;
                    if (Math.hypot(x - d.burst().x, z - d.burst().z) > radius + 12) continue;
                    LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
                    if (chunk == null) continue;
                    LevelChunkSection[] secs = chunk.getSections();
                    for (int i = 0; i < secs.length; i++) {
                        if (secs[i].hasOnlyAir() || !secs[i].getStates().maybeHas(FlashAftermath::arcs)) continue;
                        sections.add(new long[]{cx, i, cz});
                    }
                }
            }
            sections.sort(Comparator.comparingDouble(s -> Math.hypot((s[0] << 4) + 8 - cam.x, (s[2] << 4) + 8 - cam.z)));
        }

        @Override
        public boolean tick(ClientLevel level, int t) {
            if (arcs == null) {
                scan(level);
                if (next < sections.size()) return true;
                Vec3 cam = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
                found.sort(Comparator.comparingDouble(a -> a.pos.distSqr(BlockPos.containing(cam))));
                arcs = new ArrayList<>(found.subList(0, Math.min(MAX_ARCS, found.size())));
                // короткое замыкание — вразнобой, чаще сразу, до прихода волны к лампе: после неё лампа в пыли и обломках
                // (в малом масштабе удара волна — за тики, тогда — только первый разряд). Дуга не гаснет сразу: разряд
                // проскакивает снова, пока цепь не разомкнётся (2–4 раза через 1–3 тика); у части ламп замыкание
                // повторяется позже, слабее
                for (Arc a : arcs) {
                    double dist = Math.hypot(a.pos.getX() + 0.5 - d.burst().x, a.pos.getZ() + 0.5 - d.burst().z);
                    int wave = (int) d.arrivalTicks(dist);
                    int window = Mth.clamp(wave - t, 3, ARC_WINDOW);
                    float r = rnd.nextFloat();
                    int at = t + (int) (r * r * window);
                    int again = rnd.nextFloat() < 0.4f ? at + 8 + rnd.nextInt(16) : -1;
                    int first = 2 + rnd.nextInt(3), second = again < 0 ? 0 : 1 + rnd.nextInt(2);
                    a.strikes = new int[first + second];
                    a.strength = new float[first + second];
                    for (int i = 0, when = at; i < a.strikes.length; i++) {
                        boolean late = i >= first;
                        when = i == 0 ? at : i == first ? again : when + 1 + rnd.nextInt(3);
                        a.strikes[i] = i > 0 && when >= wave ? -1 : when;
                        a.strength[i] = (late ? 0.6f : 1) * (i == 0 || i == first ? 1 : 0.55f + 0.35f * rnd.nextFloat());
                    }
                }
                lastArcs = arcs.size();
            }
            boolean left = false;
            for (Arc a : arcs) {
                for (int i = 0; i < a.strikes.length; i++) {
                    if (a.strikes[i] == t) arc(level, a, a.strength[i]);
                    if (a.strikes[i] > t) left = true;
                }
            }
            return left;
        }

        private void scan(ClientLevel level) {
            for (int n = 0; n < SECTIONS_PER_TICK && next < sections.size(); n++) {
                long[] s = sections.get(next++);
                LevelChunk chunk = level.getChunkSource().getChunkNow((int) s[0], (int) s[2]);
                if (chunk == null) continue;
                LevelChunkSection sec = chunk.getSections()[(int) s[1]];
                int y0 = chunk.getMinBuildHeight() + ((int) s[1] << 4);
                for (int y = 0; y < 16; y++) {
                    for (int z = 0; z < 16; z++) {
                        for (int x = 0; x < 16; x++) {
                            BlockState st = sec.getBlockState(x, y, z);
                            if (!arcs(st)) continue;
                            BlockPos pos = new BlockPos(((int) s[0] << 4) + x, y0 + y, ((int) s[2] << 4) + z);
                            if (Math.hypot(pos.getX() + 0.5 - d.burst().x, pos.getZ() + 0.5 - d.burst().z) > d.radiusMax()) continue;
                            found.add(new Arc(pos, st.is(ModBlocks.SUBSTATION.get())));
                        }
                    }
                }
            }
        }

        private void arc(ClientLevel level, Arc a, float strength) {
            float big = a.substation ? 3 : 1;
            Vec3 at = Vec3.atCenterOf(a.pos).add(rnd.nextGaussian() * 0.15, (a.substation ? 1 : 0.2) + rnd.nextGaussian() * 0.1, rnd.nextGaussian() * 0.15);
            Fx.flash().color(ARC, ARC_EDGE).size(1.4f * big * strength, 2.6f * big * strength).life(2 + rnd.nextInt(2)).spawn(level, at);
            Fx.flash().color(0xFFFFFF, ARC).size(0.5f * big, 0.9f * big).life(2).spawn(level, at);
            int n = (int) ((a.substation ? 18 : 6) * strength);
            for (int i = 0; i < n; i++) {
                Fx.spark().color(0xFFFFFF, 0x80A0FF).vel(rnd.nextGaussian() * 0.18, 0.05 + rnd.nextDouble() * 0.25, rnd.nextGaussian() * 0.18)
                        .life(8 + rnd.nextInt(14)).gravity(0.05f).spawn(level, at);
            }
        }
    }

    /** Лампа сети или подстанция: тики разрядов дуги и их сила. */
    private static final class Arc {
        final BlockPos pos;
        final boolean substation;
        int[] strikes = new int[0];
        float[] strength = new float[0];

        Arc(BlockPos pos, boolean substation) {
            this.pos = pos;
            this.substation = substation;
        }
    }

    /** Коротит ли блок от ЭМИ: видимая лампа сети или подстанция. */
    private static boolean arcs(BlockState st) {
        return GridLights.isLit(st) && st.getRenderShape() == RenderShape.MODEL || st.is(ModBlocks.SUBSTATION.get());
    }

    // ---------------------------------------------------------------- дымки

    /** Пробы мест перед камерой: где свет поджёг поверхность и волны ещё нет — струйка дыма. */
    private static final class Smoke implements Effects.Effect {
        private final Detonation d;
        private final RandomSource rnd;
        private final double reach;

        Smoke(Detonation d) {
            this.d = d;
            this.rnd = RandomSource.create(d.seed() ^ 0x5307E);
            double lo = 0, hi = Math.max(1, d.ruinRadius());
            for (int i = 0; i < 30; i++) {
                double r = (lo + hi) / 2;
                if (Fragility.possible(d.fluence(d.burst().add(r, 0, 0)), SMOKE_Q, SMOKE_BETA)) lo = r;
                else hi = r;
            }
            this.reach = lo;
        }

        @Override
        public boolean tick(ClientLevel level, int t) {
            if (t >= SMOKE_TICKS || reach <= 0) return false;
            var camera = Minecraft.getInstance().gameRenderer.getMainCamera();
            Vec3 cam = camera.getPosition();
            double yaw = Math.toRadians(camera.getYRot());
            BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
            // в первые тики струек больше: импульс сильнее всего в начале
            int tries = (int) (SMOKE_TRIES * (t < 20 ? 1 : 0.5));
            for (int k = 0; k < tries; k++) {
                // плотность проб ∝ 1/r: у камеры струйки гуще, вдали их и так не разглядеть
                double a = yaw + (rnd.nextDouble() - 0.5) * Math.toRadians(120), r = 4 + SMOKE_RANGE * rnd.nextDouble();
                int x = Mth.floor(cam.x - Math.sin(a) * r), z = Mth.floor(cam.z + Math.cos(a) * r);
                double dist = Math.hypot(x + 0.5 - d.burst().x, z + 0.5 - d.burst().z);
                if (dist > reach || t >= d.arrivalTicks(dist)) continue;
                int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) - 1;
                if (top <= level.getMinBuildHeight()) continue;
                Direction face = Direction.UP;
                int y = top;
                // половина проб — стена к шару под крышей
                if (rnd.nextBoolean()) {
                    face = Direction.getNearest(d.burst().x - x - 0.5, 0, d.burst().z - z - 0.5);
                    y = top - rnd.nextInt(8);
                }
                BlockState st = level.getBlockState(m.set(x, y, z));
                if (!burns(st, face)) continue;
                BlockState ahead = level.getBlockState(m.move(face));
                if (!ahead.isAir() && !ahead.canBeReplaced()) continue;
                double cos = FlashChar.incidence(d.burst(), x, y, z, face);
                if (cos <= 0) continue;
                Vec3 at = FlashChar.front(x, y, z, face);
                double q = d.fluence(at) * cos;
                if (!Fragility.possible(q, SMOKE_Q, SMOKE_BETA) || !ThermalShadow.visible(level, d.burst(), at)) continue;
                if (!Fragility.hit(q, SMOKE_Q, SMOKE_BETA, rnd.nextGaussian())) continue;
                puff(level, at, Math.min(1, Math.log(q / SMOKE_Q) / 2 + 0.5));
            }
            return true;
        }

        private static boolean burns(BlockState st, Direction face) {
            if (st.isAir()) return false;
            if (FlashChar.material(st) == FlashChar.CHARS || st.is(BlockTags.LEAVES)) return true;
            return face == Direction.UP && (st.is(Blocks.GRASS_BLOCK) || st.is(BlockTags.REPLACEABLE_BY_TREES) || st.is(Blocks.HAY_BLOCK));
        }

        private void puff(ClientLevel level, Vec3 at, double strength) {
            float s = (float) Math.max(0.3, strength);
            Fx.smoke().vel(rnd.nextGaussian() * 0.01, 0.03 + 0.05 * rnd.nextDouble(), rnd.nextGaussian() * 0.01).size(0.3f * s, 1.4f + 1.8f * s)
                    .life(80 + rnd.nextInt(70)).color(0x6A625A, 0xB4AEA6).alpha(0.45f + 0.3f * s).drag(0.97f).rise(0.003f).fadeIn(3).fadeFrom(0.4f)
                    .budget(FxBudget.SCORCH).spawn(level, at.add(rnd.nextGaussian() * 0.2, 0.1, rnd.nextGaussian() * 0.2));
        }
    }
}
