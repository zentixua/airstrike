package ua.zentix.airstrike.defense;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Containers;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemHandlerHelper;
import net.neoforged.neoforge.items.ItemStackHandler;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;
import ua.zentix.airstrike.AirstrikeConfig;
import ua.zentix.airstrike.net.S2C;
import ua.zentix.airstrike.registry.ModBlockEntities;
import ua.zentix.airstrike.registry.ModItems;
import ua.zentix.airstrike.target.Sides;
import ua.zentix.airstrike.target.Sightings;
import ua.zentix.airstrike.util.Terrain;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * ЗРК на месте: кто поставил (его сторона — свои, {@link Sides}), запас зенитных ракет (воронка и воронка Create
 * заряжают его через {@link Capabilities.ItemHandler#BLOCK}; вынуть ракеты так нельзя), ракеты на направляющих и
 * перезарядка. Работает, пока тикает его чанк: раз в {@link #SWEEP} тиков радар ({@link Radar}) осматривает небо,
 * тревога ({@link AirAlert}) и разведка стороны ({@link Sightings}) узнают чужие цели, огонь ({@link FireControl})
 * пускает одну ракету по ближайшей свободной цели, своим рядом уходит экран радара ({@link S2C.RadarScope}).
 * Чанков не грузит: радар — список снарядов мира, полёт ракеты — {@link Interceptor}.
 */
public class SamBlockEntity extends BlockEntity {
    /** Радар осматривает небо раз в столько тиков; пуск — не чаще. */
    public static final int SWEEP = 10;
    /** Ячеек запаса (по 16 ракет). */
    public static final int SLOTS = 2;
    /** Экран радара видят свои ближе стольких блоков от ЗРК. */
    public static final double SCOPE_RANGE = 48;
    /** Ракета сходит с направляющей на такой высоте над низом блока. */
    static final double RAIL_HEIGHT = 1.2;

    @Nullable
    private UUID owner;
    private final ItemStackHandler stock = new ItemStackHandler(SLOTS) {
        @Override
        public boolean isItemValid(int slot, ItemStack stack) {
            return stack.is(ModItems.INTERCEPTOR.get());
        }

        @Override
        protected void onContentsChanged(int slot) {
            setChanged();
        }
    };
    /** Снаружи — только загрузка: воронка под ЗРК не вытаскивает ракеты. */
    private final IItemHandler loader = new IItemHandler() {
        @Override
        public int getSlots() {
            return stock.getSlots();
        }

        @Override
        public ItemStack getStackInSlot(int slot) {
            return stock.getStackInSlot(slot);
        }

        @Override
        public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
            return stock.insertItem(slot, stack, simulate);
        }

        @Override
        public ItemStack extractItem(int slot, int amount, boolean simulate) {
            return ItemStack.EMPTY;
        }

        @Override
        public int getSlotLimit(int slot) {
            return stock.getSlotLimit(slot);
        }

        @Override
        public boolean isItemValid(int slot, ItemStack stack) {
            return stock.isItemValid(slot, stack);
        }
    };
    /** Ракет на направляющих, готовых к пуску. */
    private int ready;
    /** Тиков идёт установка следующей ракеты на направляющую. */
    private int reload;
    /** Что радар видел при последнем осмотре (для строки состояния): целей всего, чужих. */
    private int lastTracks, lastHostile;
    /** Небо над ЗРК закрыто: под крышей он не стреляет. */
    private boolean covered;

    public SamBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.SAM.get(), pos, state);
    }

    public static void registerCapabilities(RegisterCapabilitiesEvent e) {
        e.registerBlockEntity(Capabilities.ItemHandler.BLOCK, ModBlockEntities.SAM.get(), (be, side) -> be.loader);
    }

    @Nullable
    public UUID owner() {
        return owner;
    }

    public void setOwner(@Nullable UUID owner) {
        this.owner = owner;
        setChanged();
    }

    /** Ракет на направляющих. */
    public int ready() {
        return ready;
    }

    /** Ракет в запасе. */
    public int stockCount() {
        int n = 0;
        for (int i = 0; i < stock.getSlots(); i++) n += stock.getStackInSlot(i).getCount();
        return n;
    }

    /** Положить ракеты в запас; возвращает то, что не влезло. */
    public ItemStack load(ItemStack stack) {
        return ItemHandlerHelper.insertItem(stock, stack, false);
    }

    /** Сигнал компаратора — по заполненности запаса. */
    public int comparator() {
        return ItemHandlerHelper.calcRedstoneFromInventory(stock);
    }

    /** Блок сломан или взорван: запас и ракеты с направляющих выпадают. */
    void dropContents(Level level, BlockPos pos) {
        for (int i = 0; i < stock.getSlots(); i++) {
            ItemStack s = stock.getStackInSlot(i);
            if (!s.isEmpty()) Containers.dropItemStack(level, pos.getX(), pos.getY(), pos.getZ(), s.copy());
            stock.setStackInSlot(i, ItemStack.EMPTY);
        }
        if (ready > 0) Containers.dropItemStack(level, pos.getX(), pos.getY(), pos.getZ(), new ItemStack(ModItems.INTERCEPTOR.get(), ready));
        ready = 0;
    }

    /** Строка состояния для игрока, который смотрит на ЗРК. */
    public Component status() {
        if (covered) return Component.translatable("airstrike.sam.covered").withStyle(ChatFormatting.GOLD);
        return Component.translatable("airstrike.sam.status", String.valueOf(ready), String.valueOf(AirstrikeConfig.SERVER.samRails.get()),
                String.valueOf(stockCount()), String.valueOf(lastHostile), String.valueOf(lastTracks));
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, SamBlockEntity be) {
        if (level instanceof ServerLevel server) be.tick(server, pos);
    }

    private void tick(ServerLevel level, BlockPos pos) {
        reloadTick(level, pos);
        if ((level.getGameTime() + Math.floorMod(pos.hashCode(), SWEEP)) % SWEEP == 0) sweep(level, pos);
    }

    /** Расчёт ставит на свободную направляющую ракету из запаса — по одной за {@code reload_seconds}. */
    private void reloadTick(ServerLevel level, BlockPos pos) {
        if (ready >= AirstrikeConfig.SERVER.samRails.get() || stockCount() == 0) {
            reload = 0;
            return;
        }
        if (++reload < AirstrikeConfig.SERVER.samReloadSeconds.get() * 20) return;
        for (int i = 0; i < stock.getSlots(); i++) {
            if (!stock.extractItem(i, 1, false).isEmpty()) {
                ready++;
                level.playSound(null, pos, SoundEvents.PISTON_EXTEND, SoundSource.BLOCKS, 0.5f, 0.6f);
                break;
            }
        }
        reload = 0;
        setChanged();
    }

    /** Осмотр неба: тревога и разведка стороны, пуск по ближайшей свободной цели, экран радара своим рядом. */
    private void sweep(ServerLevel level, BlockPos pos) {
        AirstrikeConfig.Server cfg = AirstrikeConfig.SERVER;
        Vec3 antenna = Vec3.atCenterOf(pos).add(0, 1, 0);
        List<Radar.Track> tracks = Radar.scan(level, antenna, cfg.samRadarRange.get(), cfg.samEngageRange.get(), owner);
        String side = owner == null ? null : Sides.side(level.getServer(), owner);
        lastTracks = tracks.size();
        lastHostile = 0;
        for (Radar.Track t : tracks) {
            if (!t.hostile()) continue;
            lastHostile++;
            // карта пульта стороны показывает чужие цели радара; свои снаряды стороне и так видны
            if (side != null) Sightings.spot(level, side, t.projectile(), Sightings.Source.RADAR);
        }
        AirAlert.sweep(level, owner, side, tracks);
        // над ЗРК открытое небо (свой чанк готов — он тикает): ракета уходит вверх и под крышей разорвалась бы о неё
        covered = Terrain.height(level, Heightmap.Types.MOTION_BLOCKING, pos.getX(), pos.getZ()) > pos.getY() + 1;
        if (ready > 0 && !covered) {
            Radar.Track shot = FireControl.pick(tracks, DefenseWorld.get(level));
            if (shot != null) {
                FireControl.launch(level, Vec3.atBottomCenterOf(pos).add(0, RAIL_HEIGHT, 0), shot, owner, pos.toShortString(), InterceptorSpec.SAM);
                ready--;
                setChanged();
            }
        }
        scope(level, antenna, tracks);
    }

    /** Экран радара своим рядом с ЗРК (без хозяина — всем рядом). */
    private void scope(ServerLevel level, Vec3 antenna, List<Radar.Track> tracks) {
        MinecraftServer server = level.getServer();
        List<ServerPlayer> crew = new ArrayList<>();
        for (ServerPlayer p : level.players()) {
            if (p.isSpectator() || p.position().distanceToSqr(antenna) > SCOPE_RANGE * SCOPE_RANGE) continue;
            if (owner == null || Sides.friendly(server, owner, p.getUUID())) crew.add(p);
        }
        if (crew.isEmpty()) return;
        DefenseWorld world = DefenseWorld.get(level);
        List<S2C.Blip> blips = new ArrayList<>();
        for (Radar.Track t : tracks) {
            if (blips.size() >= S2C.RadarScope.MAX_BLIPS) break;
            Vec3 d = t.position().subtract(antenna);
            int flags = (t.hostile() ? S2C.Blip.HOSTILE : 0) | (t.engageable() ? S2C.Blip.ENGAGEABLE : 0) | (world.engaged(t.id()) ? S2C.Blip.ENGAGED : 0);
            blips.add(new S2C.Blip((float) d.x, (float) d.z, t.projectile().getYRot(), t.projectile().weapon().id(), flags));
        }
        AirstrikeConfig.Server cfg = AirstrikeConfig.SERVER;
        S2C.RadarScope packet = new S2C.RadarScope(antenna, cfg.samRadarRange.get(), cfg.samEngageRange.get(), ready, stockCount(), blips);
        for (ServerPlayer p : crew) PacketDistributor.sendToPlayer(p, packet);
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (owner != null) tag.putUUID("owner", owner);
        tag.put("stock", stock.serializeNBT(registries));
        tag.putInt("ready", ready);
        tag.putInt("reload", reload);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        owner = tag.hasUUID("owner") ? tag.getUUID("owner") : null;
        if (tag.contains("stock")) stock.deserializeNBT(registries, tag.getCompound("stock"));
        ready = tag.getInt("ready");
        reload = tag.getInt("reload");
    }
}
