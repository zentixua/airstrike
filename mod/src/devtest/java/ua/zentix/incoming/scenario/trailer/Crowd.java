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

    /**
     * Уже стоящий бот (боец уличного боя) — в эту толпу, без нового появления: после вспышки ядерки бойцы пригибаются,
     * смотрят на гриб и бегут, как жители ({@code fight.adopt}). Режим — смотрит туда, куда смотрел, пока реплика не
     * скажет иначе: бродить посреди боя ему нельзя.
     */
    Person adopt(String name, Vec3 at, Vec3 look) {
        Person p = new Person(name, at, 0);
        p.mode = Mode.WATCH;
        p.point = look;
        p.nextAt = server.getTickCount();
        people.add(p);
        return p;
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

    /** Все бегут к точке {@code to}, каждый — через {@code min}…{@code max} тиков: не разом, как по команде. */
    void flee(Vec3 to, int min, int max) {
        for (Person p : people) {
            p.mode = Mode.FLEE;
            p.point = to;
            p.nextAt = server.getTickCount() + min + random.nextInt(Math.max(1, max - min + 1));
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

    /**
     * Дойти до {@code to}, как игрок: сперва повернуться туда (поворот плавный — {@link Neck}), путь — двумя отрезками
     * через точку в стороне от прямой (не по линейке), бегом — с прыжком посередине, если {@code jump}. Ответ — сколько
     * тиков это займёт с запасом.
     */
    private int walk(Person p, ServerPlayer sp, JsonArray a, Vec3 to, boolean sprint, boolean jump) {
        Vec3 from = sp.position(), d = to.subtract(from).multiply(1, 0, 1);
        double len = d.length();
        int turn = 3 + random.nextInt(5);
        a.add(Cast.lookAt(new Vec3(to.x, sp.getEyeY(), to.z)));
        a.add(Cast.step("wait", turn));
        if (len > 4) {
            Vec3 side = new Vec3(-d.z, 0, d.x).normalize().scale(len * (random.nextDouble() - 0.5) * 0.35);
            Vec3 mid = from.add(d.scale(0.4 + random.nextDouble() * 0.2)).add(side);
            a.add(Cast.walkTo(mid.x, mid.z, sprint));
            if (jump) a.add(Cast.hold("jump", 3));
        }
        a.add(Cast.walkTo(to.x, to.z, sprint));
        cast.act(p.name, a, true);
        return turn + (int) (len * 1.15 / (sprint ? 0.25 : 0.2)) + 10;
    }

    private int plan(Person p, ServerPlayer sp) {
        JsonArray a = Cast.steps();
        switch (p.mode) {
            case WANDER -> {
                if (random.nextFloat() < 0.35f) {
                    // постоять, оглядеться: два-три взгляда с паузами, как человек на улице
                    int n = 2 + random.nextInt(2), ticks = 0;
                    for (int i = 0; i < n; i++) {
                        a.add(Cast.lookAt(sp.getEyePosition().add(random.nextGaussian() * 8, random.nextGaussian() * 1.2, random.nextGaussian() * 8)));
                        int w = 14 + random.nextInt(30);
                        a.add(Cast.step("wait", w));
                        ticks += w;
                    }
                    cast.act(p.name, a, true);
                    return ticks + random.nextInt(20);
                }
                double ang = random.nextDouble() * Math.PI * 2, r = p.roam * Math.sqrt(random.nextDouble());
                Vec3 to = p.home.add(Math.cos(ang) * r, 0, Math.sin(ang) * r);
                boolean hurry = random.nextFloat() < 0.1f;
                return walk(p, sp, a, to, hurry, false);
            }
            case FLEE -> {
                Vec3 to = p.point.add(random.nextGaussian() * 1.5, 0, random.nextGaussian() * 1.5);
                a.add(Cast.step("release", "all"));
                int ticks = walk(p, sp, a, to, true, random.nextFloat() < 0.35f);
                p.mode = Mode.WATCH;
                p.point = sp.getEyePosition().add(0, 30, 0);
                return ticks + 10;
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
