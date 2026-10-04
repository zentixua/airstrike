package ua.zentix.airstrike.entity;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.entity.IEntityWithComplexSpawn;
import org.jetbrains.annotations.Nullable;
import ua.zentix.airstrike.defense.DefenseWorld;
import ua.zentix.airstrike.defense.Interceptor;
import ua.zentix.airstrike.guidance.FlightController;
import ua.zentix.airstrike.registry.ModEntities;

/**
 * Зенитная ракета в мире — то, что видят и слышат игроки. Сама не летит: полёт ({@link Interceptor}) ведёт
 * {@link DefenseWorld} и в мире, и вне его, а сущность — его вид, пока он идёт над тикающими чанками: сервер ставит
 * её на место полёта каждый тик, клиент рисует модель, факел и дым и играет мотор. Не сохраняется (полёт — секунды),
 * не сбивается и ни с чем не сталкивается: разрыв решает полёт.
 */
public class InterceptorEntity extends Entity implements IEntityWithComplexSpawn {
    @Nullable
    private Interceptor flight;
    /** Тиков с пуска (клиенту — с пакетом появления: факел и звук пуска только у молодой ракеты). */
    private int age;
    private int lerpSteps;
    private double lerpX, lerpY, lerpZ;
    private float lerpYRot, lerpXRot;

    public InterceptorEntity(EntityType<? extends InterceptorEntity> type, Level level) {
        super(type, level);
        this.noPhysics = true;
        this.setNoGravity(true);
    }

    /** Вид полёта {@code flight} на его месте. */
    public static InterceptorEntity of(ServerLevel level, Interceptor flight) {
        InterceptorEntity e = new InterceptorEntity(ModEntities.INTERCEPTOR.get(), level);
        e.flight = flight;
        e.follow(flight);
        e.xo = e.getX();
        e.yo = e.getY();
        e.zo = e.getZ();
        e.yRotO = e.getYRot();
        e.xRotO = e.getXRot();
        return e;
    }

    /** Сервер: встать туда, где полёт сейчас, носом по курсу. */
    public void follow(Interceptor f) {
        Vec3 p = f.position();
        float[] a = FlightController.anglesTo(Vec3.ZERO, f.direction());
        setPos(p.x, p.y, p.z);
        setYRot(a[0]);
        setXRot(a[1]);
        setDeltaMovement(f.direction().scale(f.speed()));
        age = f.age();
    }

    public int age() {
        return age;
    }

    @Override
    public void tick() {
        super.tick();
        if (level().isClientSide) {
            if (lerpSteps > 0) {
                lerpPositionAndRotationStep(lerpSteps, lerpX, lerpY, lerpZ, lerpYRot, lerpXRot);
                lerpSteps--;
            }
            age++;
            return;
        }
        // полёт кончился или идёт уже без этого вида (вне тикающих чанков, новый вид)
        if (flight == null || flight.done() || flight.view() != this) discard();
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        return false;
    }

    @Override
    public boolean isPickable() {
        return false;
    }

    @Override
    public boolean isAttackable() {
        return false;
    }

    @Override
    public boolean shouldRenderAtSqrDistance(double distanceSq) {
        // видно издалека: ограничивает только дальность отслеживания сущности сервером
        return distanceSq < 512 * 512;
    }

    @Override
    public ItemStack getPickResult() {
        return ItemStack.EMPTY;
    }

    @Override
    public void lerpTo(double x, double y, double z, float yRot, float xRot, int steps) {
        // позиция приходит каждый тик: до неё — за один тик, а не за три ванильных (ракета отставала бы от полёта)
        this.lerpX = x;
        this.lerpY = y;
        this.lerpZ = z;
        this.lerpYRot = yRot;
        this.lerpXRot = xRot;
        this.lerpSteps = 1;
    }

    @Override
    public double lerpTargetX() {
        return lerpSteps > 0 ? lerpX : getX();
    }

    @Override
    public double lerpTargetY() {
        return lerpSteps > 0 ? lerpY : getY();
    }

    @Override
    public double lerpTargetZ() {
        return lerpSteps > 0 ? lerpZ : getZ();
    }

    @Override
    public void writeSpawnData(RegistryFriendlyByteBuf buf) {
        buf.writeVarInt(age);
    }

    @Override
    public void readSpawnData(RegistryFriendlyByteBuf buf) {
        age = buf.readVarInt();
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {}

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {}

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {}
}
