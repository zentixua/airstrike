package ua.zentix.airstrike.scenario.trailer;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.ryanhcode.sable.api.sublevel.ClientSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaternionf;
import org.joml.Vector3d;
import ua.zentix.airstrike.client.fx.Exhaust;
import ua.zentix.airstrike.client.fx.particle.Fx;
import ua.zentix.airstrike.client.fx.particle.FxBudget;
import ua.zentix.airstrike.client.render.PlumeRenderer;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Пара истребителей трейлера: аппараты Sable ({@link Aircraft}), которых сервер каждый тик ставит на путь
 * ({@link FlightPath}: вираж с креном), а клиент дорисовывает — факел форсажа ({@link PlumeRenderer}, как у
 * снарядов мода), дымку за соплами и срывы пара с законцовок на крутом крене — и снимает камерой в строю.
 * <p>
 * Всё, что видит клиент, берётся из его позы аппарата ({@code ClientSubLevel.renderPose}): Sable показывает аппарат
 * с задержкой на интерполяцию снимков, и камера или факел по позе сервера на 10 блоках за тик уезжали бы от него.
 */
final class Fighters {
    /** Истребитель в полёте: аппарат, путь ведущего, отставание по нему (тиков), место в строю справа (блоков). */
    record Sortie(Aircraft craft, FlightPath path, double lag, double side, long[] ticks) {
        /** Время на пути ведущего, где сейчас этот самолёт (отставание меньше нуля — впереди ведущего). */
        double time(double partial) {
            return ticks[0] + partial - lag;
        }

        Vec3 at(double t) {
            return place(path, side, t);
        }
    }

    /** Место в строю: {@code side} блоков вправо по крылу ведущего (в крене — вместе с ним) и чуть ниже. */
    static Vec3 place(FlightPath path, double side, double t) {
        Vector3d o = Aircraft.orientation(path.yaw(t), path.pitch(t), path.roll(t)).transform(new Vector3d(-side, -side * 0.1, 0));
        return path.at(t).add(o.x, o.y, o.z);
    }

    private static final Exhaust.Plume AFTERBURNER = new Exhaust.Plume(0, 0, 6.5f, 0.42f, 1, true, 0xFFF4E0, 0xFF8A30);

    /** Меняет список только поток сервера; клиент читает. */
    private final List<Sortie> sorties = new CopyOnWriteArrayList<>();
    private volatile boolean go;
    /** Где на прошлом тике клиента были сопла и законцовки (по самолётам): след кладётся отрезками. */
    private final java.util.Map<Aircraft, Vec3[]> last = new java.util.WeakHashMap<>();

    boolean isEmpty() {
        return sorties.isEmpty();
    }

    /**
     * Строит пару в начале пути (на сервере): путь поднимается над крышами, чанки уже должны быть в памяти
     * (коридор держит {@code forceload}). Самолёты висят в начале пути, пока не скажут {@link #go}.
     */
    void scramble(MinecraftServer server, FlightPath route, double clearance) {
        server.execute(() -> {
            ServerLevel level = server.overworld();
            FlightPath path = route.clear((x, z) -> {
                int bx = Mth.floor(x), bz = Mth.floor(z);
                level.getChunk(bx >> 4, bz >> 4);
                return level.getHeight(Heightmap.Types.MOTION_BLOCKING, bx, bz);
            }, clearance, 25);
            ua.zentix.airstrike.Airstrike.LOG.info("TRAILER истребители: путь {} → {}, высота {}–{}", path.start(), path.at(path.ticks()),
                    (int) java.util.Arrays.stream(path.points()).mapToDouble(Vec3::y).min().orElse(0),
                    (int) java.util.Arrays.stream(path.points()).mapToDouble(Vec3::y).max().orElse(0));
            go = false;
            // ведомый — впереди справа: камера идёт слева-сзади ведущего, и ведомый, стоящий сзади, заслонял кадр у края
            for (double[] slot : new double[][]{{0, 0}, {-1.6, 16}}) {
                // собирается над своим местом в строю и с первым тиком сервера встаёт на него
                Vec3 p = place(path, slot[1], -slot[0]);
                Aircraft craft = Aircraft.build(level, BlockPos.containing(p.add(0, 12, 0)), path.yaw(0));
                sorties.add(new Sortie(craft, path, slot[0], slot[1], new long[1]));
            }
        });
    }

    /** Пара срывается с места (на сервере — со следующего тика). */
    void go() {
        go = true;
    }

    void land(MinecraftServer server) {
        server.execute(() -> {
            for (Sortie s : sorties) s.craft().remove();
            sorties.clear();
            go = false;
        });
    }

    /** Тик сервера: самолёты — в следующую точку пути (мир стоит — стоят и они). */
    void fly(ServerTickEvent.Post e) {
        if (sorties.isEmpty() || !e.getServer().overworld().tickRateManager().runsNormally()) return;
        for (Sortie s : sorties) {
            if (go) s.ticks()[0]++;
            double t = s.time(0);
            s.craft().fly(s.at(t), s.path().yaw(t), s.path().pitch(t), s.path().roll(t));
        }
    }

    // ================================================================ клиент

    @Nullable
    Sortie lead() {
        return sorties.isEmpty() ? null : sorties.getFirst();
    }

    /**
     * Ведущий уже есть у клиента (его рисуют) и стоит у клиента на своём месте в строю: до этого камера плана смотрела
     * на пустое место пути, а собранный на 12 блоков выше аппарат клиент ещё несколько тиков вёл к месту — первый кадр
     * был общим планом с мелким ведущим (ноутбук, kfcheck3).
     */
    boolean visible() {
        Sortie lead = lead();
        Pose3dc pose = lead == null ? null : renderPose(lead.craft(), 0);
        if (pose == null) return false;
        Vec3 want = lead.at(lead.time(0));
        return want.distanceToSqr(pose.position().x(), pose.position().y(), pose.position().z()) < 1.5 * 1.5;
    }

    /** Где ведущий в этот кадр у клиента (для проверки кадров), или null. */
    @Nullable
    Object leadOnScreen() {
        Sortie lead = lead();
        Pose3dc pose = lead == null ? null : renderPose(lead.craft(), CineCamera.partial());
        return pose == null ? null : new Vec3(pose.position().x(), pose.position().y(), pose.position().z());
    }

    /** Поза аппарата, как его рисует клиент в этот кадр, или null (ещё не пришёл). */
    @Nullable
    static Pose3dc renderPose(Aircraft craft, float partial) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) return null;
        ClientSubLevelContainer c = SubLevelContainer.getContainer(level);
        SubLevel sub = c == null ? null : c.getSubLevel(craft.subLevel().getUniqueId());
        return sub instanceof ClientSubLevel cs && !cs.isRemoved() ? cs.renderPose(partial) : null;
    }

    /**
     * Камера в строю: слева-сзади ведущего, чуть выше, и отстаёт — пара уходит вперёд на форсаже; крен камеры —
     * доля {@code fall} от половины крена ведущего (горизонт валится, но город внизу читается).
     *
     * @param k    доля плана 0…1
     * @param fall куда камера валится вместе с креном (знак проверен кадрами)
     */
    CineCamera.Pose camera(double k, float fall) {
        Sortie lead = lead();
        if (lead == null) return null;
        float pt = CineCamera.partial();
        double t = lead.time(pt);
        Pose3dc pose = renderPose(lead.craft(), pt);
        Vec3 at = pose != null ? new Vec3(pose.position().x(), pose.position().y(), pose.position().z()) : lead.at(t);
        Vec3 fwd = pose != null ? pose.transformNormal(new Vec3(0, 0, 1)) : FlightPath.heading(lead.path().yaw(t), 0);
        Vec3 flat = new Vec3(fwd.x, 0, fwd.z).normalize();
        Vec3 right = new Vec3(-flat.z, 0, flat.x);
        double e = k * k * (3 - 2 * k);
        double back = Mth.lerp(e, 15, 26), side = Mth.lerp(e, -12, -9), up = Mth.lerp(e, 3, 5);
        Vec3 from = at.add(flat.scale(-back)).add(right.scale(side)).add(0, up, 0);
        // вблизи взгляд ближе к самому ведущему: при взгляде на 40 блоков вперёд его хвост уходил за правый край кадра
        double ahead = Mth.lerp(e, 10, 40);
        return CineCamera.Pose.look(from, at.add(flat.scale(ahead)).add(0, Mth.lerp(e, -1, -3), 0), fall * 0.5f * lead.path().roll(t), 55);
    }

    /** Тик клиента: дымка за соплами и пар с законцовок на крутом крене — отрезками по пути за тик. */
    void fx(ClientTickEvent.Post e) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null || sorties.isEmpty()) {
            last.clear();
            return;
        }
        RandomSource r = level.random;
        for (Sortie s : sorties) {
            Pose3dc pose = renderPose(s.craft(), 0);
            if (pose == null) continue;
            List<Vec3> nozzles = s.craft().nozzles(), tips = s.craft().wingtips();
            Vec3[] now = new Vec3[nozzles.size() + tips.size()];
            for (int i = 0; i < nozzles.size(); i++) now[i] = pose.transformPosition(nozzles.get(i));
            for (int i = 0; i < tips.size(); i++) now[nozzles.size() + i] = pose.transformPosition(tips.get(i));
            Vec3[] was = last.put(s.craft(), now);
            if (was == null) continue;
            Fx.Spec haze = Fx.smoke().size(0.7f, 3.2f).life(45).color(0xB8B4AE, 0xDADAD8).alpha(0.09f).drag(0.95f)
                    .fadeIn(2).fadeFrom(0.25f).rise(0.001f).budget(FxBudget.TRAIL);
            for (int i = 0; i < nozzles.size(); i++) segment(level, was[i], now[i], haze, 3, r);
            float bank = Math.abs(s.path().roll(s.time(0)));
            if (bank > 40) {
                float a = Math.min(1, (bank - 40) / 20f) * 0.4f;
                Fx.Spec vapor = Fx.smoke().size(0.25f, 0.9f).life(22).color(0xF2F4F6, 0xFFFFFF).alpha(a).drag(0.9f)
                        .fadeIn(1).fadeFrom(0.15f).wind(0).budget(FxBudget.TRAIL);
                for (int i = nozzles.size(); i < now.length; i++) segment(level, was[i], now[i], vapor, 1.5, r);
            }
        }
    }

    private static void segment(ClientLevel level, Vec3 from, Vec3 to, Fx.Spec puff, double step, RandomSource r) {
        double len = from.distanceTo(to);
        if (len > 60) return;
        int n = Math.max(1, (int) Math.round(len / step));
        for (int i = 0; i < n; i++) {
            double k = (i + r.nextDouble()) / n;
            puff.spawn(level, Mth.lerp(k, from.x, to.x), Mth.lerp(k, from.y, to.y), Mth.lerp(k, from.z, to.z));
        }
    }

    /** Факел форсажа из каждого сопла — как у снарядов мода, по позе аппарата в этот кадр. */
    void render(RenderLevelStageEvent e) {
        if (e.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS || sorties.isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        float pt = e.getPartialTick().getGameTimeDeltaPartialTick(false);
        Camera camera = e.getCamera();
        Vec3 cam = camera.getPosition();
        PoseStack stack = e.getPoseStack();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        long time = mc.level == null ? 0 : mc.level.getGameTime();
        for (Sortie s : sorties) {
            Pose3dc pose = renderPose(s.craft(), pt);
            if (pose == null) continue;
            Quaternionf rot = new Quaternionf(pose.orientation());
            for (Vec3 local : s.craft().nozzles()) {
                Vec3 at = pose.transformPosition(local);
                float flicker = 0.9f + 0.1f * Mth.sin((time + pt) * 2.3f + (float) local.x);
                Exhaust.Plume p = new Exhaust.Plume(AFTERBURNER.y(), AFTERBURNER.z(), AFTERBURNER.length() * flicker, AFTERBURNER.radius(),
                        AFTERBURNER.intensity(), AFTERBURNER.diamonds(), AFTERBURNER.core(), AFTERBURNER.outer());
                stack.pushPose();
                stack.translate(at.x - cam.x, at.y - cam.y, at.z - cam.z);
                stack.mulPose(rot);
                PlumeRenderer.render(p, stack, buffers, rot, at, camera);
                stack.popPose();
            }
        }
        buffers.endBatch();
    }
}
