package ua.zentix.airstrike.client.fx;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import ua.zentix.airstrike.client.ClientWeaponSpec;
import ua.zentix.airstrike.client.fx.particle.Fx;
import ua.zentix.airstrike.client.fx.particle.FxBudget;
import ua.zentix.airstrike.entity.BomberEntity;
import ua.zentix.airstrike.entity.BunkerBusterEntity;
import ua.zentix.airstrike.entity.CruiseMissileEntity;
import ua.zentix.airstrike.entity.DebrisEntity;
import ua.zentix.airstrike.entity.DroneEntity;
import ua.zentix.airstrike.entity.FlightPhase;
import ua.zentix.airstrike.entity.IcbmEntity;
import ua.zentix.airstrike.entity.LauncherEntity;
import ua.zentix.airstrike.entity.RocketEntity;
import ua.zentix.airstrike.entity.StrikeProjectile;
import ua.zentix.airstrike.util.Local;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * Двигатели в полёте: факел (его рисует {@link ua.zentix.airstrike.client.render.StrikeProjectileRenderer} по
 * {@link #plume}), облако пуска, огонь у сопла, клубы объёма у густых шлейфов. Сам шлейф и инверсионные следы —
 * лента по точкам пути ({@code client.far.FarFlightView}, вид — {@code ClientWeaponSpec.Trail}): мест частиц она не
 * занимает, поэтому залп в сотни ракет не рвёт следы на отдельные клубы.
 */
public final class Exhaust {
    /** Факел в системе снаряда: срез сопла в (0, {@code y}, {@code z}) (нос по +Z), длина и радиус в блоках, яркость, «алмазы». */
    public record Plume(float y, float z, float length, float radius, float intensity, boolean diamonds, int core, int outer) {}

    private static final class State {
        Vec3 lastNozzle;
        /** Сопло на прошлом тике: с борта клубы кладутся с отставанием на тик (см. {@link #volume}). */
        Vec3 heldNozzle;
        /** Камера игрока — на этом снаряде (видео с борта). */
        boolean onboard;
        Vec3 pad;
        /** Передний срез трубы РСЗО, из которой сошёл снаряд. */
        Vec3 muzzle;
    }

    private static final Map<StrikeProjectile, State> STATES = new WeakHashMap<>();

    private Exhaust() {}

    /** Раз в тик на клиенте для каждого летящего снаряда. */
    public static void tick(StrikeProjectile e) {
        if (!(e.level() instanceof ClientLevel level)) return;
        State s = STATES.computeIfAbsent(e, k -> new State());
        s.onboard = Minecraft.getInstance().getCameraEntity() == e;
        switch (e) {
            case IcbmEntity icbm -> icbm(level, icbm, s);
            case CruiseMissileEntity m -> missile(level, m, s);
            case DroneEntity d -> drone(level, d, s);
            case RocketEntity rk -> rocket(level, rk, s);
            case BunkerBusterEntity b -> bomb(level, b, s);
            default -> {}
        }
    }

    /** Факел двигателя сейчас или null — не горит. */
    @Nullable
    public static Plume plume(StrikeProjectile e, float partial) {
        if (!e.isActive()) return null;
        float flicker = 0.9f + 0.1f * Mth.sin((e.age() + partial) * 2.7f) * Mth.sin((e.age() + partial) * 1.3f + 1);
        FlightPhase ph = e.flightPhase();
        if (ph == FlightPhase.READY && !(e instanceof IcbmEntity)) return null;
        ClientWeaponSpec.ClientAirframe nozzles = nozzles(e);
        ClientWeaponSpec.At engine = nozzles.enginePlume(), booster = nozzles.boosterPlume();
        if (ph.boosterLit() && e instanceof RocketEntity) {
            // РСЗО: короткая яркая струя, вспыхивает сразу (поджиг — доли секунды); в трубе её не видно
            return new Plume((float) engine.y(), (float) engine.z(), 3.0f * flicker, 0.15f, 1, false, 0xFFF4E0, 0xFF8A30);
        }
        if (ph.boosterLit() && (e instanceof DroneEntity || e instanceof CruiseMissileEntity)) {
            // твердотопливный ускоритель: на поджиге струя вырастает за полсекунды, дальше — ровный яркий факел
            float grow = ph == FlightPhase.IGNITION ? Math.min(1, (e.phaseAge() + partial) / 10f) : 1;
            return e instanceof DroneEntity
                    ? new Plume((float) booster.y(), (float) booster.z(), 2.2f * grow * flicker, 0.13f, 1, false, 0xFFF4E0, 0xFF8A30)
                    : new Plume((float) booster.y(), (float) booster.z(), 4.0f * grow * flicker, 0.25f, 1, true, 0xFFF4E0, 0xFF8A30);
        }
        return switch (e) {
            // «Минитмен»: 18 м струи с алмазами, на разгоне длиннее
            case IcbmEntity i -> new Plume((float) engine.y(), (float) engine.z(), (14 + Math.min(10, i.age() * 0.15f)) * flicker, 1.1f, 1, true,
                    0xFFF4E0, 0xFF8A30);
            // маршевый ТРД почти невидим — тусклое свечение; в пике ярче (форсаж для прорыва ПВО — художественно)
            case CruiseMissileEntity m -> ph == FlightPhase.TERMINAL
                    ? new Plume((float) engine.y(), (float) engine.z(), 2.0f * flicker, 0.18f, 0.9f, false, 0xFFE8C0, 0xFF6A20)
                    : new Plume((float) engine.y(), (float) engine.z(), 1.0f * flicker, 0.14f, 0.55f, false, 0xFFD8A0, 0xFF5A18);
            default -> null;
        };
    }

    /** Сопла сущности из клиентского паспорта (у B-2 и его бомбы — свои). */
    private static ClientWeaponSpec.ClientAirframe nozzles(StrikeProjectile e) {
        return ClientWeaponSpec.of(e.weapon()).airframe(!(e instanceof BomberEntity));
    }

    /** Точка в осях снаряда — в мир. */
    private static Vec3 at(StrikeProjectile e, ClientWeaponSpec.At p) {
        return Local.at(e.position(), e.getYRot(), e.getXRot(), p.x(), p.y(), p.z());
    }

    // ---------------------------------------------------------------- шахед

    /** Старт на ускорителе; дальше тонкий сизый выхлоп поршневого мотора — только лентой. */
    private static void drone(ClientLevel level, DroneEntity e, State s) {
        booster(level, e, s);
    }

    // ---------------------------------------------------------------- РСЗО

    /**
     * Реактивный снаряд «Града». Поджиг в трубе (3 тика): струя бьёт назад из заднего среза трубы — огонь, вспышка,
     * клубы, которые стелются по земле под пакетом. Сход: вспышка у переднего среза. Пока горит двигатель — плотный
     * серо-белый след, который висит дугой над позицией; очередь из 40 труб оставляет веер таких дуг и облако
     * у пусковой. После выгорания снаряд летит по инерции без следа.
     */
    /** Клубов объёма за тик у густого шлейфа (РСЗО, ускоритель, МБР): три полных пакета РСЗО помещаются в группу (ExhaustTest). */
    static final int VOLUME_PUFFS_PER_TICK = 1;
    /** Непрозрачность клуба объёма: под ним лента, плотнее — и клубы читались тёмными бусинами на ней. */
    private static final float VOLUME_ALPHA = 0.45f;

    private static void rocket(ClientLevel level, RocketEntity e, State s) {
        FlightPhase ph = e.flightPhase();
        if (!e.isActive() || !ph.boosterLit()) return;
        RandomSource r = level.random;
        float yaw = e.getYRot(), pitch = e.getXRot();
        Vec3 back = Local.offset(yaw, pitch, 0, 0, -1);
        if (s.pad == null) {
            // первый тик поджига: снаряд ещё в трубе, его центр — середина трубы
            s.pad = e.position().add(back.scale(LauncherEntity.TUBE_LENGTH / 2));
            s.muzzle = e.position().subtract(back.scale(LauncherEntity.TUBE_LENGTH / 2));
        }
        Vec3 nozzle = at(e, nozzles(e).engine());
        if (ph == FlightPhase.IGNITION) {
            // струя из заднего среза трубы
            for (int i = 0; i < 4; i++) {
                Fx.fire().vel(back.scale(0.5 + r.nextDouble() * 0.7).add(r.nextGaussian() * 0.08, r.nextGaussian() * 0.08, r.nextGaussian() * 0.08))
                        .size(0.4f, 1.5f).life(4 + r.nextInt(4)).alpha(0.9f).drag(0.75f).spawn(level, s.pad);
            }
            Fx.flash().size(2.5f, 3.5f).life(2).alpha(0.7f).spawn(level, s.pad);
            backblast(level, s.pad, back, 5, r);
            return;
        }
        int age = e.phaseAge();
        if (age < 2) {
            // сход: огонь вырывается из переднего среза вслед за снарядом, искры
            Fx.flash().size(2, 3).life(2).alpha(0.6f).spawn(level, s.muzzle);
            for (int i = 0; i < 6; i++) {
                Fx.spark().vel(back.scale(-0.3 - r.nextDouble() * 0.5).add(r.nextGaussian() * 0.2, r.nextGaussian() * 0.2, r.nextGaussian() * 0.2))
                        .life(6 + r.nextInt(8)).size(0.05f, 0.02f).spawn(level, s.muzzle);
            }
            Fx.smoke().vel(back.scale(-0.2)).size(0.6f, 2.2f).life(120 + r.nextInt(60)).color(0xE0DCD4, 0xA8A49E).alpha(0.6f)
                    .drag(0.88f).glow(0.7f, 4).rise(0.002f).fadeIn(1).fadeFrom(0.5f).spin(0.01f).budget(FxBudget.GROUND).spawn(level, s.muzzle);
        }
        if (age < 6) backblast(level, s.pad, back, 2, r);
        // языки огня летят вместе со снарядом и чуть отстают — иначе за ним остаются огненные бусины
        Vec3 motion = s.lastNozzle == null ? Vec3.ZERO : nozzle.subtract(s.lastNozzle);
        // плотный след (лента): у сопла подсвечен, дальше серо-белый, висит полминуты; клубы чуть шире ленты и бледнее
        // её — неровный край и плотность, а не бусины поверх
        Fx.Spec puff = Fx.smoke().size(0.7f, 3.6f).life(420 + r.nextInt(200)).color(0xEDEAE4, 0xB2AEA8)
                .colorCurve(0.6f).alpha(VOLUME_ALPHA).drag(0.9f).glow(0.85f, 4).rise(0.0012f).fadeIn(2).fadeFrom(0.55f).spin(0.012f);
        volume(level, s, nozzle, puff, r);
        for (int i = 0; i < 2; i++) {
            Fx.fire().vel(motion.scale(0.85).add(back.scale(0.2 + r.nextDouble() * 0.3)).add(r.nextGaussian() * 0.04, r.nextGaussian() * 0.04, r.nextGaussian() * 0.04))
                    .size(0.25f, 0.7f).life(2 + r.nextInt(2)).alpha(0.85f).drag(0.95f).spawn(level, nozzle);
        }
    }

    /** Выхлоп из заднего среза трубы: бьёт назад, в землю под пакетом, и растекается по ней. */
    private static void backblast(ClientLevel level, Vec3 rear, Vec3 back, int puffs, RandomSource r) {
        int n = Fx.count(puffs, rear);
        for (int i = 0; i < n; i++) {
            double a = r.nextDouble() * Mth.TWO_PI, v = 0.1 + r.nextDouble() * 0.3;
            Fx.smoke().vel(back.x * 0.6 + Math.cos(a) * v, back.y * 0.6 + r.nextDouble() * 0.06, back.z * 0.6 + Math.sin(a) * v)
                    .size(0.8f, 4.5f + r.nextFloat() * 2).life(260 + r.nextInt(160)).color(0xE4DFD6, 0xA9A399).alpha(0.75f)
                    .drag(0.9f).glow(0.6f, 6).rise(0.0015f).collide().growFast().fadeIn(2).fadeFrom(0.6f).spin(0.008f)
                    .budget(FxBudget.GROUND).spawn(level, rear.x + r.nextGaussian() * 0.2, rear.y + r.nextGaussian() * 0.2, rear.z + r.nextGaussian() * 0.2);
        }
    }

    // ---------------------------------------------------------------- крылатая ракета

    /** Горячий след ТРД — лентой; в пике — ещё и пар на корпусе (конденсация на околозвуке). */
    private static void missile(ClientLevel level, CruiseMissileEntity e, State s) {
        if (!e.isActive() || booster(level, e, s)) return;
        RandomSource r = level.random;
        Vec3 nozzle = at(e, nozzles(e).engine());
        // «воротник» пара вокруг корпуса виден только снаружи; с борта он у самого объектива
        if (e.flightPhase() == FlightPhase.TERMINAL && !s.onboard) {
            for (int i = 0; i < 3; i++) {
                double a = r.nextDouble() * Mth.TWO_PI;
                Vec3 p = Local.at(e.position(), e.getYRot(), e.getXRot(), Math.cos(a) * 0.35, Math.sin(a) * 0.35, 0.8 - r.nextDouble() * 1.6);
                Fx.smoke().size(0.3f, 1.2f).life(8).color(0xF4F6F8, 0xFFFFFF).alpha(0.45f).fadeIn(1).fadeFrom(0.2f).drag(0.8f)
                        .wind(0).spawn(level, p);
            }
        }
        if (e.flightPhase() == FlightPhase.POP_UP || e.flightPhase() == FlightPhase.TERMINAL) {
            Fx.spark().vel(e.getDeltaMovement().scale(0.3).add(r.nextGaussian() * 0.1, r.nextGaussian() * 0.1, r.nextGaussian() * 0.1))
                    .life(6 + r.nextInt(6)).size(0.06f, 0.03f).spawn(level, nozzle);
        }
    }

    // ---------------------------------------------------------------- старт с пусковой

    /**
     * Шахед и ракета на пусковой и на ускорителе: на направляющей — ничего; поджиг — огонь и клубы дыма, которые
     * бьют в пусковую и растекаются вокруг неё; разгон — плотный белый шлейф твердотопливного ускорителя, который
     * висит в воздухе дугой старта. После отделения — обычный выхлоп (вернёт false). Срез сопла ускорителя и размер
     * дыма (шахед меньше ракеты) — из клиентского паспорта. Шлейф ускорителя — лента и клубы объёма.
     */
    private static boolean booster(ClientLevel level, StrikeProjectile e, State s) {
        FlightPhase ph = e.flightPhase();
        if (ph == FlightPhase.READY) return true;
        if (!ph.boosterLit()) return false;
        RandomSource r = level.random;
        ClientWeaponSpec.ClientAirframe nozzles = nozzles(e);
        float scale = nozzles.boosterSmoke();
        Vec3 nozzle = at(e, nozzles.boosterNozzle());
        Vec3 back = Local.offset(e.getYRot(), e.getXRot(), 0, 0, -1);
        if (s.pad == null) s.pad = nozzle;
        Fx.Spec puff = Fx.smoke().size(0.5f * scale, 3.5f * scale).life(260 + r.nextInt(120)).color(0xF2EFEA, 0xC4C0BA).colorCurve(0.6f)
                .alpha(VOLUME_ALPHA).drag(0.9f).glow(0.8f, 5).rise(0.0015f).fadeIn(2).fadeFrom(0.5f).spin(0.01f);
        volume(level, s, nozzle, puff, r);
        for (int i = 0; i < 2; i++) {
            Fx.fire().vel(back.scale(0.4 + r.nextDouble() * 0.4).add(r.nextGaussian() * 0.05, r.nextGaussian() * 0.05, r.nextGaussian() * 0.05))
                    .size(0.4f * scale, 1.2f * scale).life(3 + r.nextInt(3)).alpha(0.85f).drag(0.7f).spawn(level, nozzle);
        }
        if (ph == FlightPhase.IGNITION || e.phaseAge() < 8) {
            // струя бьёт в пусковую: дым отражается и стелется по земле вокруг
            int n = Fx.count(ph == FlightPhase.IGNITION ? 6 : 3, s.pad);
            for (int i = 0; i < n; i++) {
                double a = r.nextDouble() * Mth.TWO_PI, v = (0.15 + r.nextDouble() * 0.35) * scale;
                Fx.smoke().vel(back.x * 0.3 + Math.cos(a) * v, 0.02 + r.nextDouble() * 0.08, back.z * 0.3 + Math.sin(a) * v)
                        .size(1.0f * scale, (5 + r.nextFloat() * 3) * scale).life(240 + r.nextInt(160)).color(0xE9E4DC, 0xB4AEA6)
                        .alpha(0.8f).drag(0.93f).glow(0.6f, 8).rise(0.0015f).collide().growFast().fadeIn(3).fadeFrom(0.6f).spin(0.008f)
                        .budget(FxBudget.GROUND).spawn(level, s.pad.x + r.nextGaussian() * 0.5, s.pad.y - 0.5, s.pad.z + r.nextGaussian() * 0.5);
            }
            if (ph == FlightPhase.IGNITION && e.phaseAge() % 2 == 0) Fx.flash().size(3 * scale, 4 * scale).life(3).alpha(0.5f).spawn(level, nozzle);
        }
        return true;
    }

    // ---------------------------------------------------------------- МБР

    /**
     * Твердотопливная первая ступень: густой белый шлейф (лента и клубы объёма), который висит минутами и сносится
     * ветром; у сопла он подсвечен факелом. На старте из шахты — клубы, растекающиеся по земле во все стороны, и огонь
     * у оголовка.
     */
    private static void icbm(ClientLevel level, IcbmEntity e, State s) {
        RandomSource r = level.random;
        Vec3 nozzle = at(e, nozzles(e).engine());
        // скорость ракеты за тик: языки огня летят вместе с ней и отстают, а не висят в воздухе
        Vec3 motion = s.lastNozzle == null ? Vec3.ZERO : nozzle.subtract(s.lastNozzle);
        Fx.Spec puff = Fx.smoke().size(1.8f, 8).life(900 + r.nextInt(300)).color(0xF2EFEA, 0xBDBAB6).colorCurve(0.6f)
                .alpha(VOLUME_ALPHA).drag(0.9f).glow(0.9f, 6).rise(0.0015f).fadeIn(2).fadeFrom(0.55f).spin(0.01f);
        volume(level, s, nozzle, puff, r);
        // языки огня и искры из сопла
        Vec3 back = Local.offset(e.getYRot(), e.getXRot(), 0, 0, -1);
        for (int i = 0; i < 3; i++) {
            Fx.fire().vel(motion.scale(0.8).add(back.scale(0.6 + r.nextDouble() * 0.6)).add(r.nextGaussian() * 0.08, r.nextGaussian() * 0.08, r.nextGaussian() * 0.08))
                    .size(0.9f, 2.2f).life(4 + r.nextInt(4)).alpha(0.8f).drag(0.7f).spawn(level, nozzle);
        }
        if (s.pad == null) s.pad = e.position().add(0, -9, 0);
        int age = e.age();
        if (age < 140) launchCloud(level, s.pad, age, r);
    }

    /** Облако старта: из шахты бьёт огонь, дым выдавливает вверх и стелет по земле кольцом. */
    private static void launchCloud(ClientLevel level, Vec3 pad, int age, RandomSource r) {
        int n = Fx.count(age < 40 ? 10 : 4, pad);
        for (int i = 0; i < n; i++) {
            double a = r.nextDouble() * Mth.TWO_PI, v = 0.5 + r.nextDouble() * 0.9;
            Fx.smoke().vel(Math.cos(a) * v, 0.05 + r.nextDouble() * 0.15, Math.sin(a) * v).size(2.5f, 11 + r.nextFloat() * 5)
                    .life(500 + r.nextInt(300)).color(0xE9E4DC, 0xB4AEA6).alpha(0.85f).drag(0.95f).glow(age < 40 ? 0.7f : 0.2f, 12)
                    .rise(0.0015f).collide().growFast().fadeIn(3).fadeFrom(0.6f).spin(0.008f)
                    .budget(FxBudget.GROUND).spawn(level, pad.x + Math.cos(a) * 2, pad.y + 1 + r.nextDouble() * 2, pad.z + Math.sin(a) * 2);
        }
        if (age < 50) {
            for (int i = 0; i < 4; i++) {
                Fx.fire().vel(r.nextGaussian() * 0.15, 0.3 + r.nextDouble() * 0.5, r.nextGaussian() * 0.15).size(1.5f, 3.5f)
                        .life(10 + r.nextInt(8)).spawn(level, pad.x + r.nextGaussian(), pad.y + 1, pad.z + r.nextGaussian());
            }
            if (age % 2 == 0) Fx.flash().size(10, 12).life(3).alpha(0.5f).spawn(level, pad.add(0, 3, 0));
        }
        // столб, который вытягивает за ракетой
        if (age < 90) {
            Fx.smoke().vel(r.nextGaussian() * 0.1, 0.5 + r.nextDouble() * 0.4, r.nextGaussian() * 0.1).size(3, 9).life(600 + r.nextInt(200))
                    .color(0xECE8E2, 0xBCB8B2).alpha(0.8f).drag(0.94f).glow(0.4f, 8).fadeIn(3).fadeFrom(0.6f).budget(FxBudget.GROUND)
                    .spawn(level, pad.add(0, 4, 0));
        }
    }

    // ---------------------------------------------------------------- бомба (инверсионные следы B-2 — лентой)

    /** Бомба: срыв потока с хвоста (лентой); у звукового барьера — «воротник» пара; при бурении — пыль у входа. */
    private static void bomb(ClientLevel level, BunkerBusterEntity e, State s) {
        RandomSource r = level.random;
        if (e.isDrilling()) {
            Vec3 in = e.entry();
            Fx.smoke().vel(r.nextGaussian() * 0.05, 0.08 + r.nextDouble() * 0.08, r.nextGaussian() * 0.05).size(0.6f, 3).life(120)
                    .color(0x7A7066, 0xA49A90).alpha(0.6f).rise(0.002f).budget(FxBudget.GROUND).spawn(level, in.x + r.nextGaussian() * 0.4, in.y + 0.4, in.z + r.nextGaussian() * 0.4);
            return;
        }
        if (e.speed() >= 8) {
            for (int i = 0; i < 4; i++) {
                double a = r.nextDouble() * Mth.TWO_PI;
                Vec3 p = Local.at(e.position(), e.getYRot(), e.getXRot(), Math.cos(a) * 1.1, Math.sin(a) * 1.1, 2 - r.nextDouble());
                Fx.smoke().size(0.5f, 1.6f).life(7).color(0xF6F8FA, 0xFFFFFF).alpha(0.5f).fadeIn(1).fadeFrom(0.15f).drag(0.7f)
                        .wind(0).spawn(level, p);
            }
        }
    }

    // ---------------------------------------------------------------- обломки

    private static final Map<DebrisEntity, Vec3> DEBRIS = new WeakHashMap<>();

    /** Горящий обломок: языки огня и жирный чёрный дым по всему пути; остывший — пыльный след, пока летит. */
    public static void debris(DebrisEntity e) {
        if (!(e.level() instanceof ClientLevel level)) return;
        Vec3 p = e.position().add(0, 0.5, 0);
        Vec3 from = DEBRIS.put(e, p);
        boolean moving = e.getDeltaMovement().lengthSqr() > 0.01;
        RandomSource r = level.random;
        if (e.isHot()) {
            Fx.fire().vel(r.nextGaussian() * 0.02, 0.04, r.nextGaussian() * 0.02).size(0.35f, 0.6f).life(8 + r.nextInt(6)).spawn(level, p);
            Fx.Spec smoke = Fx.smoke().vel(0, 0.03, 0).size(0.35f, 1.8f).life(90 + r.nextInt(50)).color(0x221E1C, 0x5E5854).alpha(0.7f)
                    .glow(0.5f, 4).rise(0.005f).fadeIn(2).fadeFrom(0.35f).budget(FxBudget.DEBRIS);
            if (moving) segment(level, from, p, smoke, 0.8, r);
            else if (r.nextInt(3) == 0) smoke.spawn(level, p);
        } else if (moving) {
            segment(level, from, p, Fx.smoke().size(0.2f, 0.8f).life(40).color(0x8A8278, 0xA8A098).alpha(0.35f).fadeFrom(0.3f).budget(FxBudget.DEBRIS), 1.2, r);
        }
    }

    // ---------------------------------------------------------------- общее

    /**
     * Клубы объёма поверх ленты шлейфа: {@link #VOLUME_PUFFS_PER_TICK} за тик в случайных местах пути сопла за тик,
     * в группе шлейфов (полная группа клубов не примет, а лента останется). Лента сплошная, но лицом к камере и
     * гладкая: клубы дают ей неровный край и плотность, когда смотришь вдоль следа; сами они тоже гладкие и без среза
     * бледного края ({@link Fx.Spec#smooth}), иначе ложатся на ленту белыми хлопьями. С борта камера стоит между прошлым
     * и нынешним положением снаряда, и клуб последнего отрезка (на быстром снаряде — десяток блоков и больше)
     * оказывался вплотную перед объективом (размытое пятно на весь кадр). Поэтому, пока камера на снаряде, отрезок
     * берётся тиком позже — уже за камерой.
     */
    private static void volume(ClientLevel level, State s, Vec3 nozzle, Fx.Spec puff, RandomSource r) {
        Vec3 held = s.heldNozzle;
        s.heldNozzle = nozzle;
        Vec3 to = s.onboard ? held : nozzle;
        if (to == null) return;
        Vec3 from = s.lastNozzle == null || s.lastNozzle.distanceToSqr(to) > 80 * 80 ? to : s.lastNozzle;
        s.lastNozzle = to;
        puff.budget(FxBudget.TRAIL).smooth();
        for (int i = 0; i < VOLUME_PUFFS_PER_TICK; i++) {
            double k = (i + r.nextDouble()) / VOLUME_PUFFS_PER_TICK;
            puff.spawn(level, Mth.lerp(k, from.x, to.x), Mth.lerp(k, from.y, to.y), Mth.lerp(k, from.z, to.z));
        }
    }

    /** Клубы по отрезку от прошлого положения сопла до нынешнего через {@code step} блоков (со случайным сдвигом). */
    private static Vec3 segment(ClientLevel level, @Nullable Vec3 from, Vec3 to, Fx.Spec puff, double step, RandomSource r) {
        if (from == null || from.distanceToSqr(to) > 80 * 80) from = to;
        double len = from.distanceTo(to);
        // шлейф не прореживаем даже вдали: дырявый след заметнее, чем лишние клубы
        int n = Math.max(1, (int) Math.round(len / step));
        for (int i = 0; i < n; i++) {
            double k = (i + r.nextDouble()) / n;
            puff.spawn(level, Mth.lerp(k, from.x, to.x), Mth.lerp(k, from.y, to.y), Mth.lerp(k, from.z, to.z));
        }
        return to;
    }
}
