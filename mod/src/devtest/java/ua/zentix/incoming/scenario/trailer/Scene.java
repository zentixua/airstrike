package ua.zentix.incoming.scenario.trailer;

import net.minecraft.world.phys.Vec3;

import java.util.Map;
import java.util.TreeMap;

/**
 * Сцена трейлера, которую можно поставить в любом месте: сама строит свою площадку (цех, аэродром, дирижабль) от
 * точки {@code at} с курсом {@code yaw} (градусы как у Minecraft: 0 — к +Z) и добавляет в съёмку свои планы. В трейлере
 * сцена ставится на карте; отдельно — {@code INCOMING_TRAILER_SCENE=<имя>}: только эта сцена на ровной площадке в
 * воздухе нового мира ({@link Trailer#testScene}).
 */
interface Scene {
    void stage(Film film, Vec3 at, float yaw);

    /** Сцены по имени — для отдельной съёмки. */
    Map<String, Scene> ALL = new TreeMap<>(Map.of(
            "street", Scene::streetFight,
            "airfield", AirfieldScene::stage,
            "factory", FactoryScene::stage
    ));

    /** Уличный бой: два отряда по шесть бойцов за укрытиями, облёт и вид из-за плеча. */
    static void streetFight(Film film, Vec3 at, float yaw) {
        ((Trailer) film).streetFight(at, yaw);
    }

    /** Точка сцены: {@code right} блоков вправо, {@code up} вверх, {@code fwd} вперёд по курсу от {@code at}. */
    static Vec3 local(Vec3 at, float yaw, double right, double up, double fwd) {
        double r = Math.toRadians(yaw);
        Vec3 f = new Vec3(-Math.sin(r), 0, Math.cos(r));
        Vec3 rt = new Vec3(-f.z, 0, f.x);
        return at.add(rt.scale(right)).add(0, up, 0).add(f.scale(fwd));
    }
}
