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
 * Жители: боты без формы и оружия. У каждого — свой дом (точка) и что он делает: гуляет вокруг дома, бежит к
 * укрытию, смотрит на точку (в небо), отворачивается от вспышки. Как и бой ({@link Fight}), решает поток сервера раз в
 * несколько тиков и только пока мир не стоит; делает сам бот своими пакетами.
 */
final class Crowd {
    enum Mode {WANDER, FLEE, WATCH, COWER}

    static final class Person {
        final String name;
        Vec3 home;
        double roam;
        Mode mode = Mode.WANDER;
        Vec3 point = Vec3.ZERO;
        long nextAt;

        Person(String name, Vec3 home, double roam) {
            this.name = name;
            this.home = home;
            this.roam = roam;
        }
    }

    private final Cast cast;
    private final MinecraftServer server;
    private final List<Person> people = new ArrayList<>();
    private final RandomSource random = RandomSource.create(11);

    Crowd(Cast cast, MinecraftServer server) {
        this.cast = cast;
        this.server = server;
        NeoForge.EVENT_BUS.addListener(this::tick);
    }

    Person add(String name, Vec3 home, double roam) {
        cast.spawn(name, home, random.nextFloat() * 360, null);
        Person p = new Person(name, home, roam);
        p.nextAt = server.getTickCount() + 20 + random.nextInt(40);
        people.add(p);
        return p;
    }

    List<Person> people() {
        return people;
    }

    /** Все в этом круге бегут к точке (вход в подземку, подъезд) — врассыпную, с разбросом у цели. */
    void flee(Vec3 near, double radius, Vec3 to) {
        for (Person p : people) {
            if (p.home.distanceTo(near) > radius) continue;
            p.mode = Mode.FLEE;
            p.point = to;
            p.nextAt = server.getTickCount() + random.nextInt(8);
        }
    }

    /** Все смотрят на точку (в небо на ракету, на гриб); {@code jitter} — разброс взглядов, блоков. */
    void watch(Vec3 at, double jitter) {
        for (Person p : people) {
            p.mode = Mode.WATCH;
            p.point = at.add(random.nextGaussian() * jitter, random.nextGaussian() * jitter * 0.3, random.nextGaussian() * jitter);
            p.nextAt = server.getTickCount() + random.nextInt(14);
        }
    }

    /** Отвернуться от точки и закрыть лицо (взгляд вниз, присесть): вспышка. */
    void cower(Vec3 from) {
        for (Person p : people) {
            p.mode = Mode.COWER;
            p.point = from;
            p.nextAt = server.getTickCount() + random.nextInt(6);
        }
    }

    void wander() {
        for (Person p : people) {
            ServerPlayer sp = cast.player(p.name);
            if (sp != null) p.home = sp.position();
            p.mode = Mode.WANDER;
        }
    }

    void removeAll() {
        for (Person p : people) cast.remove(p.name);
        people.clear();
    }

    private void tick(ServerTickEvent.Post e) {
        if (e.getServer() != server || server.tickRateManager().isFrozen()) return;
        long now = server.getTickCount();
        for (Person p : people) {
            if (now < p.nextAt) continue;
            ServerPlayer sp = cast.player(p.name);
            if (sp == null || !sp.isAlive()) continue;
            p.nextAt = now + plan(p, sp);
        }
    }

    private int plan(Person p, ServerPlayer sp) {
        JsonArray a = Cast.steps();
        switch (p.mode) {
            case WANDER -> {
                if (random.nextFloat() < 0.35f) {
                    // постоять, оглядеться
                    Vec3 look = sp.getEyePosition().add(random.nextGaussian() * 8, random.nextGaussian(), random.nextGaussian() * 8);
                    a.add(Cast.lookAt(look));
                    cast.act(p.name, a, true);
                    return 30 + random.nextInt(60);
                }
                double ang = random.nextDouble() * Math.PI * 2, r = p.roam * Math.sqrt(random.nextDouble());
                Vec3 to = p.home.add(Math.cos(ang) * r, 0, Math.sin(ang) * r);
                a.add(Cast.walkTo(to.x, to.z, false));
                cast.act(p.name, a, true);
                return (int) (sp.position().distanceTo(to) / 0.2) + 10;
            }
            case FLEE -> {
                Vec3 to = p.point.add(random.nextGaussian() * 1.5, 0, random.nextGaussian() * 1.5);
                a.add(Cast.step("release", "all"));
                a.add(Cast.walkTo(to.x, to.z, true));
                cast.act(p.name, a, true);
                p.mode = Mode.WATCH;
                p.point = sp.getEyePosition().add(0, 30, 0);
                return (int) (sp.position().distanceTo(to) / 0.25) + 20;
            }
            case WATCH -> {
                a.add(Cast.lookAt(p.point));
                cast.act(p.name, a, true);
                return 40 + random.nextInt(40);
            }
            case COWER -> {
                Vec3 away = sp.position().subtract(p.point).multiply(1, 0, 1).normalize();
                a.add(Cast.lookAt(sp.getEyePosition().add(away.scale(4)).add(0, -3.5, 0)));
                a.add(Cast.hold("sneak", 60));
                cast.act(p.name, a, true);
                return 60;
            }
        }
        return 20;
    }
}
