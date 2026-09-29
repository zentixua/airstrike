package ua.zentix.airstrike.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import ua.zentix.airstrike.Airstrike;
import ua.zentix.airstrike.registry.ModEntities;
import ua.zentix.airstrike.strike.VirtualFlights;
import ua.zentix.airstrike.strike.WeaponType;
import ua.zentix.airstrike.target.Target;
import ua.zentix.airstrike.util.Terrain;

import java.util.UUID;

/**
 * B-2 на эшелоне +170 над местом, куда упадёт бомба (цель перенацелили выше или ниже, у цели крыша или башня —
 * эшелон меняется полого, не круче 6°): заходит издалека (большая часть пути — вне загруженного мира), идёт по прямой
 * 12 блоков/тик (240 м/с), сбрасывает бетонобойную бомбу на эшелоне за ~85 блоков до цели по горизонтали (бомба сама
 * доворачивает и входит почти отвесно; не на эшелоне у этой черты или уже за ней — заходит снова) и уходит с разворотом
 * и набором высоты.
 */
public class BomberEntity extends StrikeProjectile {
    public static final double ALTITUDE = 170;
    public static final double RELEASE_DISTANCE = 85;
    public static final double CRUISE_SPEED = 12;
    /** Разворот, °/тик: радиус ~690 блоков. */
    private static final double TURN_RATE = 1.0;
    /** Смена эшелона: тангаж не круче этого, ° (набор и снижение B-2 — пологие). */
    private static final double LEVEL_CHANGE_PITCH = 6;
    /** Тангаж на блок ошибки эшелона, °: у эшелона — плавный выход в горизонт. */
    private static final double PITCH_PER_BLOCK = 0.15;
    /** Сброс — только на эшелоне: с другой высоты бомба с {@link #RELEASE_DISTANCE} перелетает или не долетает. */
    private static final double RELEASE_ALTITUDE_TOLERANCE = 8;
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

    /** Бомбардировщик проходит цель и уходит дальше: его путь у неё не кончается. */
    @Override
    @Nullable
    protected Vec3 pathEnd() {
        return null;
    }

    /**
     * Запас над рельефом у возврата в мир — только чтобы не возникнуть в доме или склоне впереди: B-2 и так идёт
     * на эшелоне +170 над местом падения бомбы. С запасом 120 его поднимало над каждым домом или холмом выше 50 блоков
     * на курсе выше эшелона, и с возврата у цели он не успевал снизиться — уходил на второй заход (трейлер, 29.09.2026).
     */
    @Override
    protected double clearance() {
        return 30;
    }

    public boolean hasReleased() {
        return released;
    }

    /** До удара бомбы: дойти до точки сброса и ~20 тиков падения (без второго захода — его заранее не знает никто). */
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
        // эшелон — над тем, куда упадёт бомба: у пуска он от первой оценки поверхности, а перенацеливание на точку ниже
        // или выше, крыша или башня, которой не было в оценке генератора, меняют её — с 214 блоков над точкой бомба
        // перелетала её на 42 блока (стенд, 29.09.2026)
        double echelon = 0;
        if (!released) {
            echelon = surfaceUnder(level, aim).y + ALTITUDE;
            boolean onLevel = Math.abs(getY() - echelon) <= RELEASE_ALTITUDE_TOLERANCE;
            // сброс — только на черте дальности сброса: за ней бомба перелетает точку (вышел на эшелон внутри черты —
            // перелёт 130–155 блоков, перенацелили на точку в 40 блоках впереди — 111); не на эшелоне у черты, уже
            // за ней или точка сзади (бомба падает по курсу и назад не рулит) — зайти снова, меняя высоту на заходе
            if (onLevel && atReleaseLine(b) && ahead(b)) release(level, aim);
        }
        if (age >= maxAge() && !released) {
            Airstrike.LOG.warn("B-2 {} не сбросил бомбу за срок жизни и убран у {} (точка {}, вне мира {})", getUUID(),
                    blockPosition(), BlockPos.containing(aim), isVirtual());
        }
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
        } else {
            flight.holdPitch(Mth.clamp((getY() - echelon) * PITCH_PER_BLOCK, -LEVEL_CHANGE_PITCH, LEVEL_CHANGE_PITCH), 0.1, 0.3, 0.05);
        }
        if (!released && b.horizontal() > RELEASE_DISTANCE + 40) {
            // точка сброса внутри круга разворота (перенацелили сбоку, проскочил её): на пределе поворота он
            // кружил бы вокруг неё без конца — прямо, пока она не выйдет из круга, и новый заход
            if (insideTurn(aim, TURN_RATE)) flight.settleYaw(0.1);
            else flight.steerYaw(b.yaw(), 0.1, TURN_RATE, 0.1);
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
        bomb.drop(position().add(0, -4, 0), flight.yaw(), surfaceUnder(level, aim), goal, ownerId());
        bomb.setNuclear(nuclear);
        // по UUID бомбы из предупреждений снаряда находится её B-2
        Airstrike.LOG.info("B-2 {} сбросил бомбу {} у {} (вне мира {})", getUUID(), bomb.getUUID(), blockPosition(), isVirtual());
        if (isVirtual()) VirtualFlights.launch(level, bomb);
        else level.addFreshEntity(bomb);
    }

    /**
     * Черта дальности сброса пересечена на этом тике: по горизонтали до точки не дальше {@link #RELEASE_DISTANCE} и
     * не ближе на шаг полёта — за тик B-2 приближается к ней не больше чем на шаг, так что черту он не перескакивает.
     */
    private static boolean atReleaseLine(Bearing b) {
        return b.horizontal() <= RELEASE_DISTANCE && b.horizontal() > RELEASE_DISTANCE - CRUISE_SPEED;
    }

    /**
     * Точка сброса впереди или почти под брюхом — там, куда бомба может упасть: позади она уже «пройдена»
     * ({@link BunkerBusterEntity}: падает круто вниз по курсу, не рулит).
     */
    private boolean ahead(Bearing b) {
        return !BunkerBusterEntity.passed(b, flight.yaw());
    }

    /**
     * Куда падать: у пуска поверхность под целью бывает оценкой генератора (чанк не был готов) — к сбросу чанк
     * у цели обычно готов, и высота берётся из него.
     */
    private static Vec3 surfaceUnder(ServerLevel level, Vec3 aim) {
        int x = Mth.floor(aim.x), z = Mth.floor(aim.z);
        if (!Terrain.ready(level, x >> 4, z >> 4)) return aim;
        return new Vec3(aim.x, Terrain.height(level, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 0.5, aim.z);
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
