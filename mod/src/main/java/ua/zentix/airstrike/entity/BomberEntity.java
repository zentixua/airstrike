package ua.zentix.airstrike.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import ua.zentix.airstrike.Airstrike;
import ua.zentix.airstrike.registry.ModEntities;
import ua.zentix.airstrike.strike.VirtualFlights;
import ua.zentix.airstrike.strike.WeaponType;
import ua.zentix.airstrike.target.Target;

import java.util.UUID;

/**
 * B-2 на эшелоне цель+170: заходит издалека (большая часть пути — вне загруженного мира), идёт по прямой
 * 12 блоков/тик (240 м/с), сбрасывает бетонобойную бомбу за ~85 блоков до цели по горизонтали (бомба сама
 * доворачивает и входит почти отвесно) и уходит с разворотом и набором высоты.
 */
public class BomberEntity extends StrikeProjectile {
    public static final double ALTITUDE = 170;
    public static final double RELEASE_DISTANCE = 85;
    public static final double CRUISE_SPEED = 12;
    /** После сброса улетает и исчезает через столько тиков (или раньше — на краю загруженного мира). */
    private static final int EGRESS_TICKS = 400;

    private boolean released;
    /** Точка под землёй, к которой бомба пробивается (цель в пещере); null — бурит вниз. */
    @Nullable
    private BlockPos goal;
    /** В какую сторону уходить после сброса: +1 — влево, -1 — вправо. */
    private float breakSide = 1;

    public BomberEntity(EntityType<? extends BomberEntity> type, Level level) {
        super(type, level);
    }

    @Override
    public WeaponType weapon() {
        return WeaponType.BUNKER;
    }

    @Override
    protected double noseLength() {
        return 8.5;
    }

    @Override
    public double cruiseSpeed() {
        return CRUISE_SPEED;
    }

    @Override
    protected int defaultLifetime() {
        return 120;
    }

    /**
     * До сброса держит чанки, как все: иначе, стоит игрокам уйти, он замирал в выгруженном чанке и так и не
     * сбрасывал бомбу. После сброса уходит и исчезает — держать нечего.
     */
    @Override
    protected boolean holdsChunks() {
        return !released;
    }

    @Override
    protected boolean fliesVirtually() {
        return !released;
    }

    @Override
    protected boolean acceptsRetarget() {
        return !released;
    }

    @Override
    protected double clearance() {
        return 120;
    }

    public boolean hasReleased() {
        return released;
    }

    /** До удара бомбы: дойти до точки сброса и ~20 тиков падения. */
    @Override
    public int etaTicks() {
        if (tracker == null) return 0;
        Bearing b = bearingTo(tracker.point());
        return (int) Math.ceil(Math.max(0, b.horizontal() - RELEASE_DISTANCE) / CRUISE_SPEED) + 20;
    }

    /**
     * @param surface точка на поверхности над целью (туда падает бомба)
     * @param goal    цель под землёй или null
     */
    public void launch(Vec3 pos, Vec3 surface, @Nullable BlockPos goal, @Nullable UUID owner) {
        Vec3 start = new Vec3(pos.x, surface.y + ALTITUDE, pos.z);
        super.launch(start, new Target.Point(surface), surface, owner);
        this.goal = goal;
        this.speed = CRUISE_SPEED;
        this.breakSide = random.nextBoolean() ? 1 : -1;
        setPhase(FlightPhase.CRUISE);
    }

    @Override
    protected void serverTick(ServerLevel level) {
        Vec3 aim = tracker.point();
        Bearing b = bearingTo(aim);
        if (!released && b.horizontal() <= RELEASE_DISTANCE) release(level, aim);
        // вне мира после сброса лететь незачем: уход никто не увидит
        if (age >= maxAge() || released && (phaseAge() >= EGRESS_TICKS || isVirtual())) {
            discard();
            return;
        }
        if (released) {
            // уход: вираж на 70° от курса и набор высоты
            flight.holdPitch(-6, 0.05, 0.4, 0.04);
            if (phaseAge() < 60) flight.steerYaw(flight.yaw() + 20 * breakSide, 0.08, 1.2, 0.08);
            else flight.settleYaw(0.08);
        } else if (b.horizontal() > RELEASE_DISTANCE + 40) {
            flight.steerYaw(b.yaw(), 0.1, 1.0, 0.1);
        }
        Vec3 dir = flight.forward();
        Vec3 next = position().add(dir.scale(speed));
        if (leavesTickingChunks(level, next)) return;
        moveAlong(level, next, dir);
    }

    private void release(ServerLevel level, Vec3 aim) {
        released = true;
        setPhase(FlightPhase.EGRESS);
        BunkerBusterEntity bomb = ModEntities.BUNKER_BUSTER.get().create(level);
        if (bomb == null) return;
        bomb.drop(position().add(0, -4, 0), flight.yaw(), aim, goal, ownerId());
        bomb.setNuclear(nuclear);
        // в debug.log: по UUID бомбы из предупреждений снаряда находится её B-2
        Airstrike.LOG.debug("B-2 {} сбросил бомбу {} у {} (вне мира {})", getUUID(), bomb.getUUID(), blockPosition(), isVirtual());
        if (isVirtual()) VirtualFlights.launch(level, bomb);
        else level.addFreshEntity(bomb);
    }

    @Override
    protected void impact(ServerLevel level, Vec3 point, @Nullable Entity hitEntity) {
        discard();
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        released = tag.getBoolean("released");
        goal = NbtUtils.readBlockPos(tag, "goal").orElse(null);
        breakSide = tag.contains("break_side") ? tag.getFloat("break_side") : 1;
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putBoolean("released", released);
        if (goal != null) tag.put("goal", NbtUtils.writeBlockPos(goal));
        tag.putFloat("break_side", breakSide);
    }
}
