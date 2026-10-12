package ua.zentix.incoming.scenario.trailer;

import com.google.gson.JsonArray;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * Бой статистов: у каждого бойца своё укрытие и сторона; раз в несколько тиков ему даётся следующая программа —
 * очередь по врагу (взгляд на него с разбросом, состояние «огонь очередью» для других игроков, выстрелы через тик-два),
 * перебежка к соседнему укрытию, перезарядка после магазина. Решает поток сервера (тик сервера), выполняет — сам бот
 * своими пакетами, как живой игрок. Бойцы без цели стоят в укрытии и смотрят в сторону врага.
 */
final class Fight {
    /** Боец: имя бота, сторона (0 — Запад, 1 — Восток), где его укрытие и куда смотреть, если врага нет. */
    static final class Soldier {
        final String name;
        final int side;
        Vec3 post;
        final Vec3 facing;
        long nextAt;
        int shots;
        boolean crouch;

        Soldier(String name, int side, Vec3 post, Vec3 facing) {
            this.name = name;
            this.side = side;
            this.post = post;
            this.facing = facing;
        }
    }

    private final Cast cast;
    private final MinecraftServer server;
    private final List<Soldier> soldiers = new ArrayList<>();
    private final RandomSource random = RandomSource.create(7);
    /** Разброс прицела у цели, блоков: бойцы попадают не каждой очередью. */
    double spread = 1.6;
    /** Доля программ «перебежка». */
    double moveChance = 0.12;
    /** Перебежка — не дальше этого от своего укрытия. */
    double roam = 4;
    private boolean on;

    Fight(Cast cast, MinecraftServer server) {
        this.cast = cast;
        this.server = server;
        NeoForge.EVENT_BUS.addListener(this::tick);
    }

    Soldier add(String name, int side, Vec3 post, Vec3 facing) {
        Soldier s = new Soldier(name, side, post, facing);
        s.crouch = random.nextFloat() < 0.4f;
        soldiers.add(s);
        return s;
    }

    List<Soldier> soldiers() {
        return soldiers;
    }

    void start() {
        long now = server.getTickCount();
        for (Soldier s : soldiers) s.nextAt = now + random.nextInt(30);
        on = true;
    }

    /** Огонь прекратить: бойцы опускают оружие (конец войны в финале — все смотрят вверх). */
    void stop() {
        on = false;
        for (Soldier s : soldiers) {
            ServerPlayer p = cast.player(s.name);
            Cast.Gun g = p == null ? null : Cast.gun(p);
            JsonArray a = Cast.steps();
            a.add(Cast.step("release", "all"));
            if (g != null) a.add(g.state(Cast.IDLE));
            cast.act(s.name, a, true);
        }
    }

    private void tick(ServerTickEvent.Post e) {
        if (!on || e.getServer() != server) return;
        long now = server.getTickCount();
        for (Soldier s : soldiers) {
            if (now < s.nextAt) continue;
            ServerPlayer p = cast.player(s.name);
            if (p == null || !p.isAlive()) continue;
            Cast.Gun gun = Cast.gun(p);
            if (gun == null) {
                s.nextAt = now + 5;
                continue;
            }
            s.nextAt = now + plan(s, p, gun);
        }
    }

    /** Следующая программа бойца; ответ — сколько тиков она идёт. */
    private int plan(Soldier s, ServerPlayer p, Cast.Gun gun) {
        JsonArray a = Cast.steps();
        if (s.shots >= 26) {
            s.shots = 0;
            a.add(gun.state(Cast.RELOAD));
            a.add(gun.reload());
            a.add(Cast.step("wait", 50));
            a.add(gun.state(Cast.IDLE));
            cast.act(s.name, a, true);
            return 52;
        }
        if (random.nextDouble() < moveChance) {
            Vec3 to = s.post.add((random.nextDouble() - 0.5) * 2 * roam, 0, (random.nextDouble() - 0.5) * 2 * roam);
            a.add(Cast.step("release", "all"));
            a.add(Cast.walkTo(to.x, to.z, true));
            cast.act(s.name, a, true);
            return (int) (p.position().distanceTo(to) / 0.28) + 12;
        }
        ServerPlayer target = target(s, p);
        if (target == null) {
            a.add(Cast.lookAt(p.getEyePosition().add(s.facing.scale(20))));
            cast.act(s.name, a, true);
            return 20;
        }
        Vec3 aim = target.position().add(0, 1.2, 0).add(random.nextGaussian() * spread, random.nextGaussian() * spread * 0.5,
                random.nextGaussian() * spread);
        int burst = 3 + random.nextInt(6);
        if (s.crouch) a.add(Cast.hold("sneak", burst * 2 + 12));
        a.add(Cast.lookAt(aim));
        a.add(gun.state(Cast.FIRE_AUTO));
        for (int i = 0; i < burst; i++) {
            a.add(gun.fire(random.nextLong(), false));
            a.add(Cast.step("wait", 2));
        }
        a.add(gun.state(Cast.IDLE));
        int pause = 8 + random.nextInt(25);
        a.add(Cast.step("wait", pause));
        s.shots += burst;
        cast.act(s.name, a, true);
        return burst * 2 + pause;
    }

    /** Ближайший живой враг в 80 блоках. */
    private ServerPlayer target(Soldier s, ServerPlayer p) {
        ServerPlayer best = null;
        double bd = 80 * 80;
        for (Soldier o : soldiers) {
            if (o.side == s.side) continue;
            ServerPlayer q = cast.player(o.name);
            if (q == null || !q.isAlive()) continue;
            double d = q.distanceToSqr(p);
            if (d < bd) {
                bd = d;
                best = q;
            }
        }
        return best;
    }
}
