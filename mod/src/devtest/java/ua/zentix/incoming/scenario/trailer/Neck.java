package ua.zentix.incoming.scenario.trailer;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.HashMap;
import java.util.Map;

/**
 * Поворот статистов как у живого игрока. Шаг программы бота Almighty ({@code look_at}, {@code walk_to}) ставит поворот
 * игрока сразу, за один тик: голова и тело статистов щёлкали на цель, как у робота (13.10: «вертят головой как-то
 * искусственно, по скриптовому»). Здесь в конце тика сервера (после тика ботов Almighty) поворот, который поставил шаг,
 * становится целью, а игроку ставится свой: пружина с запаздыванием реакции, разгоном, лёгким перелётом и успокоением,
 * у каждого бота своя (по имени). Стоящий без новых целей дольше {@link #QUIET} тиков оглядывается: взгляд в сторону и
 * обратно, медленное покачивание. Двигается бот по шагам программы, как раньше: идёт он туда, куда шаг его повернул,
 * а видно — свой поворот (поэтому перед ходьбой статисты сперва поворачиваются, {@link Crowd}, {@link Fight}).
 */
final class Neck {
    /** Без новой цели столько тиков — бот стоит и оглядывается. */
    static final int QUIET = 50;
    /** Быстрее этого голова не поворачивается, градусов за тик (720°/с — резкий поворот мышью). */
    static final double MAX_SPEED = 36;

    private static final class Head {
        final double freq, damp, glance;
        /** Мелкое движение головы живого человека и когда он следит за целью, градусов. */
        final double tremor;
        final int react;
        final double phase;
        boolean init;
        /** Свой поворот (курс без перехода через ±180°) и скорость, градусов за тик. */
        double yaw, pitch, vy, vp;
        /** Цель от программы и цель, к которой голова уже идёт (после запаздывания реакции). */
        double wantYaw, wantPitch, aimYaw, aimPitch;
        int delay, quiet;
        /** Взгляд в сторону при оглядывании и тик, когда он сменится. */
        double gy, gp;
        long glanceAt;
        float setYaw, setPitch;

        Head(String name) {
            RandomSource r = RandomSource.create(name.hashCode() * 31L + 7);
            // скорость поворота и перелёт у каждого свои (17.10: головы толпы шли одинаково): от неторопливого с
            // заметным перелётом до быстрого и точного
            freq = 1.5 + r.nextDouble() * 2.2;
            damp = 0.42 + r.nextDouble() * 0.43;
            react = 1 + r.nextInt(5);
            glance = 0.6 + r.nextDouble() * 0.8;
            phase = r.nextDouble() * Math.PI * 2;
            tremor = 0.35 + r.nextDouble() * 0.7;
        }
    }

    private final Cast cast;
    private final MinecraftServer server;
    private final Map<String, Head> heads = new HashMap<>();
    private final RandomSource random = RandomSource.create(23);

    Neck(Cast cast, MinecraftServer server) {
        this.cast = cast;
        this.server = server;
        // после тика ботов Almighty (обычный приоритет): шаги этого тика уже поставили поворот
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, this::tick);
    }

    void add(String name) {
        heads.put(name, new Head(name));
    }

    void remove(String name) {
        heads.remove(name);
    }

    private void tick(ServerTickEvent.Post e) {
        if (e.getServer() != server || server.tickRateManager().isFrozen()) return;
        long now = server.getTickCount();
        for (var entry : heads.entrySet()) {
            ServerPlayer p = cast.player(entry.getKey());
            Head h = entry.getValue();
            if (p == null || !p.isAlive()) {
                h.init = false;
                continue;
            }
            turn(h, p, now);
        }
    }

    private void turn(Head h, ServerPlayer p, long now) {
        float ry = p.getYRot(), rp = p.getXRot();
        if (!h.init) {
            h.init = true;
            h.yaw = h.wantYaw = h.aimYaw = ry;
            h.pitch = h.wantPitch = h.aimPitch = rp;
            h.vy = h.vp = 0;
            h.setYaw = ry;
            h.setPitch = rp;
            h.glanceAt = now + 40 + random.nextInt(80);
            return;
        }
        // программа повернула бота (поворот не тот, что поставили мы): это новая цель
        if (Math.abs(Mth.wrapDegrees(ry - h.setYaw)) > 0.01 || Math.abs(rp - h.setPitch) > 0.01) {
            double y = h.yaw + Mth.wrapDegrees(ry - (float) h.yaw);
            boolean big = Math.abs(y - h.wantYaw) > 20 || Math.abs(rp - h.wantPitch) > 15;
            h.wantYaw = y;
            h.wantPitch = rp;
            // на новое — реакция не сразу; поправка (шаг ходьбы, слежение) — сразу
            if (big) h.delay = h.react + random.nextInt(2);
            h.quiet = 0;
            h.gy = h.gp = 0;
        } else if (p.getDeltaMovement().horizontalDistanceSqr() > 0.003) {
            // идёт: игрок смотрит, куда идёт, — не оглядывается
            h.quiet = 0;
            h.gy = h.gp = 0;
        } else {
            h.quiet++;
        }
        if (h.delay > 0) {
            h.delay--;
        } else {
            h.aimYaw = h.wantYaw;
            h.aimPitch = h.wantPitch;
        }
        // стоит без дела — оглядывается
        if (h.quiet > QUIET && now >= h.glanceAt) {
            boolean away = h.gy == 0 && h.gp == 0 && random.nextFloat() < 0.7f;
            h.gy = away ? (random.nextBoolean() ? 1 : -1) * (8 + random.nextDouble() * 30) * h.glance : 0;
            h.gp = away ? (random.nextDouble() * 2 - 1) * 8 * h.glance : 0;
            h.glanceAt = now + (away ? 15 + random.nextInt(35) : 40 + random.nextInt(90));
        }
        double sway = Math.min(1, h.quiet / 40.0);
        double live = h.tremor * (0.6 * Math.sin(now * 0.13 + h.phase * 2.3) + 0.4 * Math.sin(now * 0.29 + h.phase));
        double ty = h.aimYaw + h.gy + sway * 1.5 * Math.sin(now * 0.031 + h.phase) + live;
        double tp = h.aimPitch + h.gp + sway * 1.0 * Math.sin(now * 0.047 + h.phase * 1.7) + 0.6 * live;
        // пружина: ω — в радианах за тик, четыре шага на тик
        double w = 2 * Math.PI * h.freq / 20;
        for (int i = 0; i < 4; i++) {
            double dt = 0.25;
            h.vy += (w * w * (ty - h.yaw) - 2 * h.damp * w * h.vy) * dt;
            h.vp += (w * w * (tp - h.pitch) - 2 * h.damp * w * h.vp) * dt;
            h.vy = Mth.clamp(h.vy, -MAX_SPEED, MAX_SPEED);
            h.vp = Mth.clamp(h.vp, -MAX_SPEED, MAX_SPEED);
            h.yaw += h.vy * dt;
            h.pitch += h.vp * dt;
        }
        h.pitch = Mth.clamp(h.pitch, -90, 90);
        p.absRotateTo(Mth.wrapDegrees((float) h.yaw), (float) h.pitch);
        p.setYHeadRot(p.getYRot());
        h.setYaw = p.getYRot();
        h.setPitch = p.getXRot();
    }
}
