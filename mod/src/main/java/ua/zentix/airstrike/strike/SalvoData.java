package ua.zentix.airstrike.strike;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;
import ua.zentix.airstrike.Airstrike;
import ua.zentix.airstrike.net.S2C;
import ua.zentix.airstrike.util.Terrain;
import ua.zentix.airstrike.registry.ModTags;
import ua.zentix.airstrike.target.Target;
import ua.zentix.airstrike.util.Nbt;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Залпы: N снарядов с разбросом вокруг цели, по одному через случайную паузу (шахеды 20–40 тиков, ракеты 15–30,
 * B-2 60–80) — с одной пусковой по ячейкам. Цель может двигаться — каждый снаряд целится со своим смещением
 * относительно неё; маршруты разные (обход слева или справа, курс захода ±35°), поэтому залп приходит волной
 * с разных сторон.
 * Хранится в мире: незаконченный залп продолжится после перезахода.
 */
public final class SalvoData extends SavedData {
    private static final String NAME = Airstrike.MOD_ID + "_salvos";
    private static final Factory<SalvoData> FACTORY = new Factory<>(SalvoData::new, SalvoData::load, null);
    private final List<Salvo> salvos = new ArrayList<>();

    public static SalvoData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(FACTORY, NAME);
    }

    public void add(Salvo s) {
        salvos.add(s);
        setDirty();
    }

    public void clear() {
        if (!salvos.isEmpty()) {
            salvos.clear();
            setDirty();
        }
    }

    public int size() {
        return salvos.size();
    }

    void tick(ServerLevel level) {
        if (salvos.isEmpty()) return;
        salvos.removeIf(s -> {
            try {
                return !s.tick(level);
            } catch (RuntimeException e) {
                Airstrike.LOG.error("Залп упал с ошибкой и отменён", e);
                return true;
            }
        });
        setDirty();
    }

    /**
     * Начать залп.
     *
     * @param center      цель (точка, сущность или аппарат)
     * @param centerPoint где цель сейчас
     * @param yaw         курс захода
     */
    public static void start(ServerLevel level, WeaponType weapon, int count, int radius, Target center, Vec3 centerPoint,
                             float yaw, @Nullable ServerPlayer owner, Loadout.Nuke nuke) {
        Salvo s = new Salvo(weapon, count, count, radius, center, centerPoint, yaw, owner == null ? null : owner.getUUID(), 1, nuke);
        get(level).add(s);
        if (owner != null) {
            owner.sendSystemMessage(Component.translatable("airstrike.salvo.started." + weapon.getSerializedName(), count, radius)
                    .withStyle(ChatFormatting.RED));
            PacketDistributor.sendToPlayer(owner, new S2C.SalvoStatus(weapon.id(), 0, count));
        }
    }

    public static final class Salvo {
        final WeaponType weapon;
        final int total;
        int remaining;
        final int radius;
        final Target center;
        Vec3 lastCenter;
        final float yaw;
        @Nullable
        final UUID owner;
        int cooldown;
        final Loadout.Nuke nuke;

        Salvo(WeaponType weapon, int total, int remaining, int radius, Target center, Vec3 lastCenter, float yaw, @Nullable UUID owner, int cooldown,
              Loadout.Nuke nuke) {
            this.weapon = weapon;
            this.total = total;
            this.remaining = remaining;
            this.radius = radius;
            this.center = center;
            this.lastCenter = lastCenter;
            this.yaw = yaw;
            this.owner = owner;
            this.cooldown = cooldown;
            this.nuke = nuke;
        }

        boolean tick(ServerLevel level) {
            if (--cooldown > 0) return true;
            ServerPlayer ownerPlayer = owner == null ? null : level.getServer().getPlayerList().getPlayer(owner);
            if (remaining <= 0) {
                if (ownerPlayer != null) {
                    ownerPlayer.displayClientMessage(Component.translatable("airstrike.salvo.done").withStyle(ChatFormatting.GRAY), true);
                    PacketDistributor.sendToPlayer(ownerPlayer, new S2C.SalvoStatus(weapon.id(), total, total));
                }
                return false;
            }
            fire(level);
            remaining--;
            cooldown = weapon.salvoGap(level.random);
            if (ownerPlayer != null) PacketDistributor.sendToPlayer(ownerPlayer, new S2C.SalvoStatus(weapon.id(), total - remaining, total));
            return true;
        }

        private void fire(ServerLevel level) {
            center.resolve(level).ifPresent(p -> lastCenter = p);
            int dx = 0, dz = 0;
            if (radius > 0) {
                // равномерно в круге: до 8 попыток выбросить точку из квадрата
                for (int i = 0; i < 9; i++) {
                    dx = level.random.nextIntBetweenInclusive(-radius, radius);
                    dz = level.random.nextIntBetweenInclusive(-radius, radius);
                    if (dx * dx + dz * dz <= radius * radius) break;
                }
            }
            Target shot;
            Vec3 point;
            if (!(center instanceof Target.Point)) {
                // движущаяся цель или место на земле: своё смещение относительно неё (у места — своя высота земли)
                shot = center.offset(new Vec3(dx, 0, dz));
                point = shot.resolve(level).orElse(lastCenter.add(dx, 0, dz));
            } else if (weapon == WeaponType.BUNKER || radius == 0) {
                // бомба — на глубине центра (найдёт пещеру под игроком)
                point = lastCenter.add(dx, 1, dz);
                shot = new Target.Point(point);
            } else if (inAir(level, lastCenter)) {
                point = lastCenter.add(dx, 1, dz);
                shot = new Target.Point(point);
            } else {
                int x = Mth.floor(lastCenter.x + dx), z = Mth.floor(lastCenter.z + dz);
                // высота земли — только из готового чанка (иначе по высоте центра): чанк ради пуска не грузим
                double y = Terrain.ready(level, new BlockPos(x, 0, z))
                        ? Terrain.height(level, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 0.5 : lastCenter.y;
                point = new Vec3(lastCenter.x + dx, y, lastCenter.z + dz);
                shot = new Target.Point(point);
            }
            float shotYaw = yaw + (level.random.nextInt(7001) - 3500) / 100f;
            // сирена одна на залп: её включит первый снаряд, когда его «увидят» на подлёте
            StrikeService.launch(level, weapon, shot, point, shotYaw, owner, remaining == total, nuke, false);
        }

        /** Центр залпа в воздухе (игрок на аппарате, в полёте): бьём по высоте центра, а не по земле под ним. */
        private static boolean inAir(ServerLevel level, Vec3 c) {
            BlockPos p = BlockPos.containing(c);
            if (!Terrain.ready(level, p)) return false;
            for (int i = 1; i <= 3; i++) {
                if (!level.getBlockState(p.below(i)).is(ModTags.PASSABLE)) return false;
            }
            return true;
        }

        CompoundTag save() {
            CompoundTag t = new CompoundTag();
            t.putString("weapon", weapon.getSerializedName());
            t.putInt("total", total);
            t.putInt("remaining", remaining);
            t.putInt("radius", radius);
            Target.CODEC.encodeStart(NbtOps.INSTANCE, center).resultOrPartial(Airstrike.LOG::error).ifPresent(c -> t.put("center", c));
            Nbt.putVec(t, "", lastCenter);
            t.putFloat("yaw", yaw);
            if (owner != null) t.putUUID("owner", owner);
            t.putInt("cooldown", cooldown);
            Loadout.Nuke.CODEC.encodeStart(NbtOps.INSTANCE, nuke).resultOrPartial(Airstrike.LOG::error).ifPresent(n -> t.put("nuke", n));
            return t;
        }

        static Optional<Salvo> load(CompoundTag t) {
            WeaponType w = WeaponType.parse(t.getString("weapon"));
            if (w == null) return Optional.empty();
            Vec3 last = Nbt.getVec(t, "");
            if (last == null) last = Vec3.ZERO;
            Target c = Target.CODEC.parse(NbtOps.INSTANCE, t.get("center")).resultOrPartial(Airstrike.LOG::error).orElse(new Target.Point(last));
            Loadout.Nuke nuke = Loadout.Nuke.CODEC.parse(NbtOps.INSTANCE, t.get("nuke")).result().orElse(Loadout.Nuke.DEFAULT);
            return Optional.of(new Salvo(w, t.getInt("total"), t.getInt("remaining"), t.getInt("radius"), c, last, t.getFloat("yaw"),
                    t.hasUUID("owner") ? t.getUUID("owner") : null, t.getInt("cooldown"), nuke));
        }
    }

    private static SalvoData load(CompoundTag tag, HolderLookup.Provider registries) {
        SalvoData d = new SalvoData();
        for (Tag t : tag.getList("salvos", Tag.TAG_COMPOUND)) Salvo.load((CompoundTag) t).ifPresent(d.salvos::add);
        return d;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (Salvo s : salvos) list.add(s.save());
        tag.put("salvos", list);
        return tag;
    }
}
