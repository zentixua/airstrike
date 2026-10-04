package ua.zentix.airstrike.launcher;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.items.IItemHandlerModifiable;
import org.jetbrains.annotations.Nullable;
import ua.zentix.airstrike.entity.LaunchQueue;
import ua.zentix.airstrike.entity.Launcher;
import ua.zentix.airstrike.entity.LauncherMount;
import ua.zentix.airstrike.guidance.FlightController;
import ua.zentix.airstrike.registry.ModBlockEntities;
import ua.zentix.airstrike.strike.Munitions;
import ua.zentix.airstrike.strike.WeaponType;
import ua.zentix.airstrike.util.Magazine;

import java.util.UUID;

/**
 * Стационарная пусковая на месте ({@link FixedLauncherBlock}): кто поставил (от его имени летят снаряды, его сторона —
 * свои), запас боеприпасов, задача ({@link Mission}) и пакет на поворотном круге над блоком ({@link Launcher},
 * опора {@link LauncherMount#PAD}). Запас заряжают воронка, воронка и жёлоб Create, механическая рука и лента — через
 * воронку ({@link Capabilities.ItemHandler#BLOCK} с любой стороны; вынуть так нельзя). Передний фронт сигнала
 * редстоуна — один приказ по задаче ({@link LauncherOrders#fire}), тем же путём, что приказ игрока: правила, предел
 * снарядов в работе, сектор пуска, «Отбой». Клиенту уходят только оружие пакета, курс и время подъёма — задача и запас
 * остаются на сервере.
 */
public class FixedLauncherBlockEntity extends BlockEntity implements Launcher {
    /** Ячеек запаса — как у раздатчика. */
    public static final int SLOTS = 9;

    @Nullable
    private UUID owner;
    /**
     * Запас боеприпасов: снаружи — только загрузка; запасная ячейка — под остаток вскрытого пакета «Града», когда воронка
     * держит открытые полными.
     */
    private final Magazine stock = new Magazine(SLOTS, 1, FixedLauncherBlockEntity::munition, this::setChanged);
    @Nullable
    private Mission mission;
    /** Оружие пакета на круге (клиент рисует его); null — задачи нет. */
    @Nullable
    private WeaponType rack;
    private float yaw;
    private long deployedAt = Long.MIN_VALUE / 2;
    private final LaunchQueue queue = new LaunchQueue();
    /** Чем кончился последний сигнал или пуск по нему — для строки состояния (не сохраняется). */
    @Nullable
    private Component last;

    public FixedLauncherBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.FIXED_LAUNCHER.get(), pos, state);
    }

    public static void registerCapabilities(RegisterCapabilitiesEvent e) {
        e.registerBlockEntity(Capabilities.ItemHandler.BLOCK, ModBlockEntities.FIXED_LAUNCHER.get(), (be, side) -> be.stock.loader());
    }

    /** Боеприпас оружия, которое берёт пусковая ({@link Mission#accepts}): шахеды, «Ланцеты», ракеты, пакеты «Града». */
    public static boolean munition(ItemStack stack) {
        for (WeaponType w : WeaponType.values()) {
            if (Mission.accepts(w) && stack.is(w.spec().munition().item().get())) return true;
        }
        return false;
    }

    @Nullable
    public UUID owner() {
        return owner;
    }

    public void setOwner(@Nullable UUID owner) {
        this.owner = owner;
        setChanged();
    }

    @Nullable
    public Mission mission() {
        return mission;
    }

    /**
     * Новая задача (null — снять): пакет её оружия встаёт на круг и доворачивается на цель или первую точку маршрута,
     * если молчит.
     */
    public void setMission(@Nullable Mission mission) {
        this.mission = mission;
        WeaponType shown = mission != null && Mission.accepts(mission.weapon()) ? mission.weapon() : null;
        long now = level != null ? level.getGameTime() : 0;
        if (shown != rack) {
            // другой пакет ставится заново: поднимается с нуля
            rack = shown;
            deployedAt = now;
        }
        if (mission != null && rack != null) turnTo(yawTo(mission.heading()), now);
        sync();
    }

    /** Курс с места пусковой на точку {@code p}, °. */
    public float yawTo(Vec3 p) {
        return FlightController.anglesTo(position(), p)[0];
    }

    /** Запас боеприпасов (оплата пуска — {@link Munitions#pay(Munitions.Store, Munitions.Bill)}). */
    public Munitions.Store store() {
        return new Munitions.Store() {
            @Override
            public IItemHandlerModifiable items() {
                return stock;
            }

            @Override
            public void put(ItemStack s) {
                // возврат и остаток вскрытого пакета — в запасную ячейку, если открытые полны; не влезло и туда — выпадает
                ItemStack rest = stock.stow(s);
                if (!rest.isEmpty() && level != null) Block.popResource(level, worldPosition.above(), rest);
            }
        };
    }

    /** Положить боеприпасы в запас; возвращает то, что не влезло. */
    public ItemStack load(ItemStack stack) {
        return stock.load(stack);
    }

    /** Предметов в запасе. */
    public int stockCount() {
        return stock.count();
    }

    /** Сигнал компаратора — по заполненности запаса. */
    public int comparator() {
        return stock.comparator();
    }

    /** Блок сломан или взорван: запас выпадает. */
    void dropContents(Level level, BlockPos pos) {
        stock.dropAll(level, pos);
    }

    /**
     * Сигнал редстоуна дошёл (или команда ведущего): приказ по задаче; не вышло — щелчок, как у пустого раздатчика, и
     * причина в строке состояния.
     *
     * @return null — приказ отдан; иначе — почему нет
     */
    @Nullable
    public Component fire(ServerLevel level) {
        Component problem = LauncherOrders.fire(level, this);
        report(problem != null ? problem : Component.translatable("airstrike.fixed_launcher.fired"));
        if (problem != null) level.levelEvent(1001, worldPosition, 0);
        return problem;
    }

    /** Что вышло с приказом или снарядом залпа с этой пусковой: в строку состояния. */
    public void report(Component what) {
        last = what;
    }

    /** Чем кончился последний сигнал или пуск по нему; null — с загрузки ничего. */
    @Nullable
    public Component lastReport() {
        return last;
    }

    /** Строка состояния для игрока, который смотрит на пусковую. */
    public Component status() {
        Component state = mission == null ? Component.translatable("airstrike.fixed_launcher.no_mission", stockCount())
                : Component.translatable("airstrike.fixed_launcher.status", mission.weapon().displayName(), mission.count(),
                Math.round(mission.point().x), Math.round(mission.point().z), mission.spread(),
                Munitions.held(stock, mission.weapon().spec().munition().item().get(), mission.weapon().spec().munition().rounds()));
        return last == null ? state : Component.translatable("airstrike.fixed_launcher.status.last", state, last);
    }

    // ---------------------------------------------------------------- пакет (Launcher)

    @Override
    public WeaponType weapon() {
        return rack != null ? rack : WeaponType.DRONE;
    }

    /** На круге стоит пакет (есть задача с оружием, у которого он есть). */
    public boolean hasRack() {
        return rack != null;
    }

    @Override
    public Vec3 position() {
        return Vec3.atBottomCenterOf(worldPosition);
    }

    @Override
    public float yaw() {
        return yaw;
    }

    /** Курс пакета без подъёма заново: при установке блока — от ставящего. */
    public void setYaw(float yaw) {
        this.yaw = yaw;
        setChanged();
    }

    @Override
    public LauncherMount mount() {
        return LauncherMount.PAD;
    }

    @Override
    public long deployedAt() {
        return deployedAt;
    }

    @Override
    public LaunchQueue queue() {
        return queue;
    }

    @Override
    public void turnTo(float yaw, long now) {
        if (turnedYaw(yaw, now) == this.yaw) return;
        this.yaw = yaw;
        deployedAt = now;
        sync();
    }

    /** Сохранить и отдать клиентам то, что они рисуют. */
    private void sync() {
        setChanged();
        if (level != null && !level.isClientSide) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
    }

    // ---------------------------------------------------------------- сохранение и клиент

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (owner != null) tag.putUUID("owner", owner);
        tag.put("stock", stock.serializeNBT(registries));
        if (mission != null) Mission.CODEC.encodeStart(registries.createSerializationContext(NbtOps.INSTANCE), mission).ifSuccess(t -> tag.put("mission", t));
        saveShown(tag);
    }

    /** То, что видит клиент: оружие пакета, курс, тик подъёма. */
    private void saveShown(CompoundTag tag) {
        if (rack != null) tag.putString("rack", rack.getSerializedName());
        tag.putFloat("yaw", yaw);
        tag.putLong("deployed_at", deployedAt);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        // пакет обновления клиенту — без хозяина, запаса и задачи: их не трогать
        if (tag.hasUUID("owner")) owner = tag.getUUID("owner");
        if (tag.contains("stock")) stock.deserializeNBT(registries, tag.getCompound("stock"));
        if (tag.contains("mission")) {
            mission = Mission.CODEC.parse(registries.createSerializationContext(NbtOps.INSTANCE), tag.get("mission")).result().orElse(null);
        }
        rack = tag.contains("rack") ? WeaponType.CODEC.parse(NbtOps.INSTANCE, tag.get("rack")).result().orElse(null) : null;
        yaw = tag.getFloat("yaw");
        deployedAt = tag.contains("deployed_at") ? tag.getLong("deployed_at") : Long.MIN_VALUE / 2;
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        saveShown(tag);
        return tag;
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }
}
