package ua.zentix.airstrike.entity;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import ua.zentix.airstrike.AirstrikeConfig;
import ua.zentix.airstrike.guidance.Bearing;
import ua.zentix.airstrike.guidance.Dive;
import ua.zentix.airstrike.guidance.Orbit;
import ua.zentix.airstrike.strike.FlightTickets;
import ua.zentix.airstrike.strike.WeaponSpec;
import ua.zentix.airstrike.strike.WeaponType;
import ua.zentix.airstrike.target.Target;

import java.util.UUID;

/**
 * Барражирующий боеприпас в духе «Ланцета»: маленький электрический дрон с двумя крестами крыльев. Старт
 * с катапульты (пневматический толчок, без ускорителя), крылья раскрываются в воздухе, дальше — прямо к цели,
 * над ней — круги на высоте крейсера из паспорта выше цели ({@link FlightPhase#LOITER}) в ожидании. Круг
 * следует за движущейся целью. Время барража — {@code loiter_time} из настроек (у каждого в залпе своё ±20%),
 * потом крутое пикирование с разгоном до 4 блоков/тик. Из камеры снаряда (ЛКМ) оператор выбирает цель сам —
 * пикирование сразу, если цель рядом; иначе боеприпас летит к ней и кружит уже там.
 */
public class LoiterEntity extends StrikeProjectile {
    /** Паспорт: скорости (≈115 км/ч, в пике 4 блока/тик), пределы поворота, высота круга над целью, катапульта. */
    private static final WeaponSpec.Airframe AIR = WeaponSpec.LOITER.airframe();
    /** Радиус круга над целью. */
    public static final double LOITER_RADIUS = 40;
    /** Перенацеленный ближе этого (по горизонтали) пикирует сразу, без круга. */
    private static final double STRIKE_NOW = 140;
    /** Сколько тиков в среднем уходит на пикирование с круга (для времени до удара). */
    public static final int DIVE_TICKS = 25;
    /** Пике мимо: дальше ближайшего подхода к цели на столько блоков — снова на круг и новый заход. */
    private static final double MISSED_BY = 20;

    private double cruiseAlt;
    /** Сколько кружить над целью, тиков (задаётся при пуске). */
    private int loiterTicks;
    /** Направление круга: +1 или −1 (у залпа — вразнобой). */
    private int orbitSide = 1;
    private double orbitRadius = LOITER_RADIUS;
    /** Оператор выбрал цель из камеры (или пике прошло мимо): пикировать, не дожидаясь конца круга. */
    private boolean strikeNow;
    /** Ближайший подход к цели в этом пике, блоков. */
    private double closest = Double.MAX_VALUE;

    public LoiterEntity(EntityType<? extends LoiterEntity> type, Level level) {
        super(type, level);
    }

    @Override
    public WeaponType weapon() {
        return WeaponType.LOITER;
    }

    /** Путь кончается у цели только в пике: на подлёте и на круге барража он идёт мимо неё. */
    @Override
    @Nullable
    protected Vec3 pathEnd() {
        return flightPhase() == FlightPhase.TERMINAL ? super.pathEnd() : null;
    }

    /** Барраж и круг: у каждого боеприпаса свой (залп расходится по высоте и направлению, пикирует по очереди). */
    private void pickOrbit() {
        int seconds = AirstrikeConfig.SERVER.loiterTime.get();
        loiterTicks = (int) (seconds * 20 * (0.8 + 0.4 * random.nextDouble()));
        orbitSide = random.nextBoolean() ? 1 : -1;
        orbitRadius = LOITER_RADIUS * (0.8 + 0.4 * random.nextDouble());
    }

    /** Уже в воздухе (заход издалека, тесты): на высоте круга. */
    @Override
    public void launch(Vec3 pos, Target target, Vec3 targetPoint, @Nullable UUID owner) {
        super.launch(pos, target, targetPoint, owner);
        pickOrbit();
        cruiseAlt = Math.max(pos.y, targetPoint.y + AIR.cruiseHeight());
        altitude.reset(pos.y);
        speed = AIR.cruiseSpeed();
        setPhase(FlightPhase.CRUISE);
    }

    @Override
    public void placeOnLauncher(Vec3 rail, float yaw, float elevation, int readyTicks, int hiddenTicks, Target target, Vec3 targetPoint,
                                @Nullable UUID owner) {
        super.placeOnLauncher(rail, yaw, elevation, readyTicks, hiddenTicks, target, targetPoint, owner);
        cruiseAlt = Math.max(rail.y + 30, targetPoint.y + AIR.cruiseHeight());
    }

    /**
     * Запас хода — ещё и на круг над целью (на маршевой скорости) и на пике с него (на скорости пике: в тиках плана
     * {@link #DIVE_TICKS} оно быстрее круга, и на маршевой запаса на пике не хватало бы).
     */
    @Override
    protected double extraRange() {
        return loiterTicks * AIR.cruiseSpeed() + DIVE_TICKS * AIR.diveSpeed();
    }

    /** Катапульта: хлопок и облако пара; ускорителя нет — сбрасывать нечего. */
    @Override
    protected void separate(ServerLevel level) {}

    /**
     * Круг (до 48 блоков и занос на развороте ~10) должен весь лежать в тикающих чанках, где бы в чанке ни стояла
     * цель: район цели — 9×9 тикающих чанков (±64 блока от края чанка цели).
     */
    @Override
    protected int targetArea() {
        return FlightTickets.LOITER_DISTANCE;
    }

    @Override
    protected boolean launchTick(ServerLevel level) {
        if (flightPhase() == FlightPhase.IGNITION && phaseAge() == 0) {
            Vec3 back = flight.forward().scale(-1);
            level.sendParticles(ParticleTypes.CLOUD, getX() + back.x * 2, getY() + back.y * 2, getZ() + back.z * 2, 14, 0.5, 0.3, 0.5, 0.05);
        }
        return super.launchTick(level);
    }

    @Override
    public int etaTicks() {
        if (tracker == null) return 0;
        Vec3 aim = tracker.point();
        return switch (flightPhase()) {
            case TERMINAL -> (int) Math.ceil(position().distanceTo(aim) / Math.max(speed, AIR.cruiseSpeed()));
            case LOITER -> strikeNow ? DIVE_TICKS : Math.max(0, loiterTicks - phaseAge()) + DIVE_TICKS;
            default -> {
                double dx = aim.x - getX(), dz = aim.z - getZ();
                double toOrbit = Math.max(0, Math.sqrt(dx * dx + dz * dz) - orbitRadius);
                yield (int) Math.ceil(toOrbit / AIR.cruiseSpeed()) + launchTicksLeft() + (strikeNow ? DIVE_TICKS : loiterTicks + DIVE_TICKS);
            }
        };
    }

    /**
     * Путь по плану до удара, как у времени до удара ({@link #etaTicks}): до круга, остаток круга на маршевой и пике на
     * скорости пике. Цель, потерянная на круге, не обрывает барраж: «Ланцет» докружит и зайдёт на её последнюю точку.
     */
    @Override
    protected double plannedPathLeft() {
        double dive = DIVE_TICKS * AIR.diveSpeed();
        return switch (flightPhase()) {
            case TERMINAL -> super.plannedPathLeft();
            case LOITER -> (strikeNow ? 0 : Math.max(0, loiterTicks - phaseAge()) * AIR.cruiseSpeed()) + dive;
            default -> {
                Vec3 aim = tracker.point();
                double dx = aim.x - getX(), dz = aim.z - getZ();
                double toOrbit = Math.max(0, Math.sqrt(dx * dx + dz * dz) - orbitRadius);
                yield toOrbit + (strikeNow ? 0 : loiterTicks * AIR.cruiseSpeed()) + dive;
            }
        };
    }

    @Override
    protected void onRetarget() {
        // рядом — атака сразу; далеко — лететь туда и кружить уже там
        Bearing b = bearingTo(tracker.point());
        strikeNow = b.horizontal() < STRIKE_NOW;
        if (!strikeNow && flightPhase() == FlightPhase.LOITER) setPhase(FlightPhase.CRUISE);
        cruiseAlt = Math.max(cruiseAlt, tracker.point().y + AIR.cruiseHeight());
    }

    @Override
    protected void serverTick(ServerLevel level) {
        Vec3 aim = updateTarget(level);
        if (launchTick(level)) return;

        Bearing b = bearingTo(aim);
        FlightPhase ph = flightPhase();
        double terrain = terrainAhead(level, 10, 20, 35);
        double floor = terrain + 15;

        if (ph == FlightPhase.CLIMB) {
            // крылья раскрылись, винт тянет: набор высоты к кругу
            speed += (AIR.cruiseSpeed() - speed) * 0.08;
            holdAltitude(Math.max(cruiseAlt, floor), 0.12, 1.4, 0.15);
            flight.steerYaw(b.yaw(), 0.08, AIR.climbTurnRate(), 0.15);
            if (phaseAge() > 40) setPhase(FlightPhase.CRUISE);
        } else if (ph == FlightPhase.CRUISE) {
            speed += (AIR.cruiseSpeed() - speed) * 0.05;
            holdAltitude(Math.max(cruiseAlt, floor), 0.12, 1.2, 0.15);
            if (strikeNow) {
                flight.steerYaw(b.yaw(), 0.15, AIR.turnRate(), 0.3);
                if (b.pitch() >= 30 || b.horizontal() <= orbitRadius + 15) setPhase(FlightPhase.TERMINAL);
            } else {
                // к кругу — по тому же полю курсов, что и на круге: выход на него по касательной, без перелёта
                orbit().steer(flight, position(), aim, tracker.velocity(), speed);
                if (orbit().captured(position(), aim, tracker.velocity(), speed, flight.yaw())) setPhase(FlightPhase.LOITER);
            }
        } else if (ph == FlightPhase.LOITER) {
            speed += (AIR.cruiseSpeed() - speed) * 0.05;
            holdAltitude(Math.max(aim.y + AIR.cruiseHeight(), floor), 0.12, 1.2, 0.15);
            orbit().steer(flight, position(), aim, tracker.velocity(), speed);
            // заход в пике — когда цель под крылом (под углом 40° и круче) и время вышло
            boolean due = strikeNow || phaseAge() >= loiterTicks;
            if (due && b.pitch() >= 40) setPhase(FlightPhase.TERMINAL);
        } else if (ph == FlightPhase.TERMINAL) {
            closest = Math.min(closest, b.distance());
            if (b.distance() > closest + MISSED_BY) {
                // прошёл мимо (цель увернулась, или угла в пике не хватило): не петлять вокруг неё, а, как настоящий
                // «Ланцет», уйти на круг и зайти снова, как только цель опять под крылом
                strikeNow = true;
                setPhase(FlightPhase.LOITER);
            } else {
                Dive.steer(flight, position(), speed, aim, tracker.velocity());
                speed = Math.min(AIR.diveSpeed(), speed + 0.1);
            }
        }

        advance(level, aim, AIR.reachPad());
    }

    @Override
    protected void setPhase(FlightPhase phase) {
        super.setPhase(phase);
        if (phase == FlightPhase.TERMINAL) closest = Double.MAX_VALUE;
    }

    private Orbit orbit() {
        return new Orbit(orbitRadius, orbitSide);
    }

    /** Радиус круга этого боеприпаса (у каждого в залпе свой), блоков. */
    public double orbitRadius() {
        return orbitRadius;
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        cruiseAlt = tag.getDouble("cruise_alt");
        loiterTicks = tag.getInt("loiter_ticks");
        orbitSide = tag.getInt("orbit_side") < 0 ? -1 : 1;
        orbitRadius = tag.contains("orbit_radius") ? tag.getDouble("orbit_radius") : LOITER_RADIUS;
        strikeNow = tag.getBoolean("strike_now");
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putDouble("cruise_alt", cruiseAlt);
        tag.putInt("loiter_ticks", loiterTicks);
        tag.putInt("orbit_side", orbitSide);
        tag.putDouble("orbit_radius", orbitRadius);
        tag.putBoolean("strike_now", strikeNow);
    }
}
