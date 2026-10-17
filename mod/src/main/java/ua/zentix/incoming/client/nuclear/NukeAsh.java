package ua.zentix.incoming.client.nuclear;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.client.fx.particle.Fx;
import ua.zentix.incoming.client.fx.particle.FxBudget;
import ua.zentix.incoming.client.fx.particle.FxPool;
import ua.zentix.incoming.nuclear.Detonation;

/**
 * Пепел, клочки бумаги и мелкий мусор, что сыплются вокруг камеры после ядерной волны — как погода: в диске около
 * камеры над ней, только под открытым небом (над крышей — на крышу), сносит ветер подрыва. Сколько — по пелене над
 * местом ({@link PallField#ashRate}): из оседающей пыли — минуты после волны, из дымной мглы над горящим городом — до
 * утра; бумага — где волна рушила дома, первые минуты. Места — своя группа {@link FxBudget#ASH}.
 */
public final class NukeAsh {
    private static final RandomSource RANDOM = RandomSource.create();
    /** Радиус диска вокруг камеры, блоков. */
    static final double RADIUS = 20;
    /** Хлопьев за тик при самом густом пепле (настройка «Частицы: все»). */
    static final int MAX_PER_TICK = 14;

    private NukeAsh() {}

    /**
     * Тик: {@code rate} — пепел над камерой 0..1, {@code sigma} — пелена у камеры (в густой пыли сыплется мелкий
     * мусор), {@code a} — подрыв, чья пелена у камеры (ветер, бумага).
     */
    static void tick(ClientLevel level, Vec3 cam, double rate, double sigma, @Nullable ClientNuclear.Active a) {
        if (rate <= 0.01 || a == null || level.dimensionType().hasCeiling()) return;
        Detonation d = a.d;
        double ticks = a.ticks(0);
        double paper = a.pall.paperRate(cam.x - d.burst().x, cam.z - d.burst().z, ticks);
        int n = Math.round((float) (rate * MAX_PER_TICK * Fx.density(cam)));
        // снос — ветром подрыва, у земли слабее (как у пыли)
        double wind = d.blocks(d.windSpeed()) / 20.0 * PallField.DRIFT;
        double wx = Math.cos(d.windDir()) * wind, wz = Math.sin(d.windDir()) * wind;
        for (int i = 0; i < n; i++) {
            double ang = RANDOM.nextDouble() * Mth.TWO_PI, r = RADIUS * Math.sqrt(RANDOM.nextDouble());
            // против ветра — дальше: хлопья прилетают в диск по ветру
            double x = cam.x + Math.cos(ang) * r - wx * 40, z = cam.z + Math.sin(ang) * r - wz * 40;
            double y = cam.y + 4 + RANDOM.nextDouble() * 12;
            int bx = Mth.floor(x), bz = Mth.floor(z);
            LevelChunk chunk = level.getChunkSource().getChunk(bx >> 4, bz >> 4, false);
            if (chunk == null) continue;
            int roof = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, bx & 15, bz & 15) + 1;
            y = Math.max(y, roof + 1 + RANDOM.nextDouble() * 4);
            boolean isPaper = RANDOM.nextDouble() < paper * 0.25;
            boolean debris = !isPaper && sigma > PallField.SIGMA_DENSE * 0.3 / d.scale() && RANDOM.nextFloat() < 0.15f;
            Fx.Spec s = Fx.flake(isPaper);
            float drag = isPaper ? 0.88f : debris ? 0.94f : 0.9f;
            double ax = wx * (1 - drag) / drag, az = wz * (1 - drag) / drag;
            if (isPaper) {
                s.size(0.12f, 0.12f).color(0xE8E2D4, 0xBDB5A4).gravity(0.0025f).spin(0.18f).life(260 + RANDOM.nextInt(160));
            } else if (debris) {
                // мелкий мусор: тёмный, тяжелее, падает быстрее
                s.size(0.05f, 0.05f).color(0x2E2822, 0x2E2822).gravity(0.012f).spin(0.25f).life(120 + RANDOM.nextInt(60));
            } else {
                // пепел: серый и белёсый, от горевшего — темнее
                int c = RANDOM.nextFloat() < 0.4f ? 0xC8C4BE : RANDOM.nextFloat() < 0.5f ? 0x8E8A84 : 0x5A5652;
                s.size(0.035f + 0.03f * RANDOM.nextFloat(), 0.035f + 0.03f * RANDOM.nextFloat()).color(c, c).life(220 + RANDOM.nextInt(160));
            }
            s.drag(drag).drift(ax, az).vel(wx, -0.02, wz).budget(FxBudget.ASH).spawn(level, x, y, z);
        }
    }

    static void reset() {
    }

    /** Сколько хлопьев живёт (для строки сценария). */
    static int alive() {
        return FxPool.INSTANCE.live(FxBudget.ASH);
    }
}
