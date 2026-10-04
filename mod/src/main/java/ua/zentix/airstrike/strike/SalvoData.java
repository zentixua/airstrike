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
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
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

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Залпы: N снарядов с разбросом вокруг цели, по одному через случайную паузу (шахеды 20–40 тиков, ракеты 15–30,
 * B-2 60–80) — с одной пусковой по ячейкам. Цель может двигаться — каждый снаряд целится со своим смещением
 * относительно неё; маршруты разные (обход слева или справа, курс захода ±35°), поэтому залп приходит волной
 * с разных сторон. По точкам оператора ({@link Waypoints}) весь залп идёт одним маршрутом.
 * Хранится в мире: незаконченный залп продолжится после перезахода.
 * <p>
 * Залп, оплаченный боеприпасами ({@link Munitions}), возвращает владельцу то, что не вылетело: невыпущенные снаряды
 * отменённого залпа (отбой, ошибка) и снаряды, чей пуск не удался, — в конце залпа.
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

    /**
     * Отменить залпы владельца {@code owner} (null — все): невыпущенные оплаченные снаряды — владельцу, если он среди
     * {@code online} ({@link Munitions#refund}).
     *
     * @return сколько залпов отменено
     */
    public int cancel(ServerLevel level, @Nullable UUID owner, Collection<? extends ServerPlayer> online) {
        int n = 0;
        for (Iterator<Salvo> it = salvos.iterator(); it.hasNext(); ) {
            Salvo s = it.next();
            if (owner != null && !owner.equals(s.owner)) continue;
            it.remove();
            refund(s, online, "залп отменён");
            n++;
        }
        if (n > 0) setDirty();
        return n;
    }

    public int size() {
        return salvos.size();
    }

    /** Цели залпов этого игрока (для проверок). */
    public List<Target> centers(UUID owner) {
        List<Target> out = new ArrayList<>();
        for (Salvo s : salvos) {
            if (owner.equals(s.owner)) out.add(s.center);
        }
        return out;
    }

    /** Сколько снарядов ещё не выпущено в залпах этого игрока. */
    public int remaining(UUID owner) {
        int n = 0;
        for (Salvo s : salvos) {
            if (owner.equals(s.owner)) n += s.remaining;
        }
        return n;
    }

    /**
     * Залп уходит из списка: оплаченный возвращает владельцу (если он среди {@code online}) невыпущенные снаряды и те,
     * чей пуск не удался ({@link Munitions#refund}). Единственное место возврата: его зовёт каждый путь, которым залп
     * убирается, — отмена, ошибка, конец залпа. Залп ядерным не бывает: ядерных БЧ в возврате нет.
     */
    private static void refund(Salvo s, Collection<? extends ServerPlayer> online, String why) {
        if (s.paid) Munitions.refund(online, s.owner, new Munitions.Bill(s.weapon, s.remaining + s.failed, 0), why);
        s.failed = 0;
    }

    void tick(ServerLevel level) {
        if (salvos.isEmpty()) return;
        salvos.removeIf(s -> {
            try {
                return !s.tick(level);
            } catch (RuntimeException e) {
                Airstrike.LOG.error("Залп упал с ошибкой и отменён", e);
                refund(s, level.getServer().getPlayerList().getPlayers(), "залп упал с ошибкой");
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
     * @param owner       кто пустил (ему — сообщения о залпе), null — консоль или командный блок
     * @param via         точки оператора: весь залп летит по ним
     * @param paid        залп оплачен боеприпасами владельца: что не вылетит, вернётся ему ({@link Munitions})
     */
    public static void start(ServerLevel level, WeaponType weapon, int count, int radius, Target center, Vec3 centerPoint,
                             float yaw, @Nullable UUID owner, Loadout.Nuke nuke, Waypoints via, boolean paid) {
        get(level).add(new Salvo(weapon, count, count, radius, center, centerPoint, yaw, owner, 1, nuke, via, paid, 0));
        ServerPlayer player = owner == null ? null : level.getServer().getPlayerList().getPlayer(owner);
        if (player != null) {
            player.sendSystemMessage(Component.translatable("airstrike.salvo.started." + weapon.getSerializedName(), count, radius)
                    .withStyle(ChatFormatting.RED));
            PacketDistributor.sendToPlayer(player, new S2C.SalvoStatus(weapon.id(), 0, count));
        }
    }

    public static final class Salvo {
        final WeaponType weapon;
        final int total;
        int remaining;
        final int radius;
        /** Цель залпа; погибла цель-сущность — её последняя точка ({@link #watchCenter}). */
        Target center;
        Vec3 lastCenter;
        /**
         * Сущность-цель, за которой залп шёл в прошлый тик (не сохраняется: после загрузки — первая найденная). Игрок,
         * погибший и возрождённый, — новый {@code ServerPlayer} с тем же UUID: без неё залп переходил на место
         * возрождения и бил туда. Слабая ссылка, как в {@code TargetTracker}: выгруженную или ушедшую сущность залп не держит.
         */
        @Nullable
        private WeakReference<Entity> followed;
        final float yaw;
        @Nullable
        final UUID owner;
        int cooldown;
        final Loadout.Nuke nuke;
        /** Точки оператора: у всего залпа одни. */
        final Waypoints via;
        /** Оплачен боеприпасами владельца ({@link Munitions}): что не вылетело, вернётся ему. */
        final boolean paid;
        /** Сколько пусков залпа не удалось: возвращаются владельцу в конце залпа (одной строкой, а не на каждый). */
        int failed;

        Salvo(WeaponType weapon, int total, int remaining, int radius, Target center, Vec3 lastCenter, float yaw, @Nullable UUID owner, int cooldown,
              Loadout.Nuke nuke, Waypoints via, boolean paid, int failed) {
            this.weapon = weapon;
            this.total = total;
            this.remaining = remaining;
            this.radius = radius;
            this.center = center;
            this.lastCenter = lastCenter;
            this.yaw = yaw;
            this.owner = owner;
            this.cooldown = cooldown;
            // ядерных залпов нет (ServerActions.clamp): снаряды залпа ядерной БЧ не несут
            this.nuke = nuke.withOnCarrier(false);
            this.via = via;
            this.paid = paid;
            this.failed = failed;
        }


        boolean tick(ServerLevel level) {
            watchCenter(level);
            if (--cooldown > 0) return true;
            ServerPlayer ownerPlayer = owner == null ? null : level.getServer().getPlayerList().getPlayer(owner);
            if (remaining <= 0) {
                if (ownerPlayer != null) {
                    ownerPlayer.displayClientMessage(Component.translatable("airstrike.salvo.done").withStyle(ChatFormatting.GRAY), true);
                    PacketDistributor.sendToPlayer(ownerPlayer, new S2C.SalvoStatus(weapon.id(), total, total));
                }
                if (failed > 0) refund(this, level.getServer().getPlayerList().getPlayers(), "пуски залпа не удались");
                return false;
            }
            if (!fire(level).ok()) failed++;
            remaining--;
            cooldown = weapon.salvoGap(level.random);
            if (ownerPlayer != null) PacketDistributor.sendToPlayer(ownerPlayer, new S2C.SalvoStatus(weapon.id(), total - remaining, total));
            return true;
        }

        /**
         * Каждый тик, не только к пуску: цель-сущность погибла — остаток залпа бьёт по её последней точке, а не по
         * возродившейся или новой сущности с тем же UUID. Сущность, сменившая измерение или пропавшая из мира, не
         * погибла: залп по-прежнему идёт за ней (а пока её нет — по последней точке).
         */
        private void watchCenter(ServerLevel level) {
            if (!(center instanceof Target.OfEntity target)) return;
            Entity last = followed == null ? null : followed.get();
            if (last != null && died(last)) {
                center = new Target.Point(lastCenter);
                followed = null;
                Airstrike.LOG.info("Залп: {} — цель {} погибла, остаток ({}) — по её последней точке {}", weapon.getSerializedName(),
                        target.uuid(), remaining, BlockPos.containing(lastCenter));
                return;
            }
            Entity now = level.getEntity(target.uuid());
            if (now == null) return;
            if (last != now) followed = new WeakReference<>(now);
            target.resolve(level, now).ifPresent(p -> lastCenter = p);
        }

        private static boolean died(Entity e) {
            return e instanceof LivingEntity l ? l.isDeadOrDying() : e.getRemovalReason() == Entity.RemovalReason.KILLED;
        }

        private StrikeService.Result fire(ServerLevel level) {
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
            } else if (weapon.spec().penetrates() || radius == 0) {
                // бомба — на глубине центра (найдёт пещеру под игроком)
                point = lastCenter.add(dx, 1, dz);
                shot = new Target.Point(point);
            } else if (inAir(level, lastCenter)) {
                point = lastCenter.add(dx, 1, dz);
                shot = new Target.Point(point);
            } else {
                int x = Mth.floor(lastCenter.x + dx), z = Mth.floor(lastCenter.z + dz);
                // высота земли — только из готового чанка (иначе по высоте центра): чанк ради пуска не грузим
                Terrain.Surface ground = Terrain.estimate(level, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z, Terrain.Allowed.CHUNK);
                double y = ground.known() ? ground.y() - 0.5 : lastCenter.y;
                point = new Vec3(lastCenter.x + dx, y, lastCenter.z + dz);
                shot = new Target.Point(point);
            }
            float shotYaw = yaw + (level.random.nextInt(7001) - 3500) / 100f;
            // сирена одна на залп: её включит первый снаряд, когда его «увидят» на подлёте
            return StrikeService.launch(level, weapon, shot, point, shotYaw, owner, remaining == total, nuke, via);
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
            if (!via.isEmpty()) Waypoints.CODEC.encodeStart(NbtOps.INSTANCE, via).resultOrPartial(Airstrike.LOG::error).ifPresent(r -> t.put("route", r));
            if (paid) t.putBoolean("paid", true);
            if (failed > 0) t.putInt("failed", failed);
            return t;
        }

        static Optional<Salvo> load(CompoundTag t) {
            WeaponType w = WeaponType.parse(t.getString("weapon"));
            if (w == null) return Optional.empty();
            Vec3 last = Nbt.getVec(t, "");
            if (last == null) last = Vec3.ZERO;
            Target c = Target.CODEC.parse(NbtOps.INSTANCE, t.get("center")).resultOrPartial(Airstrike.LOG::error).orElse(new Target.Point(last));
            Loadout.Nuke nuke = Loadout.Nuke.CODEC.parse(NbtOps.INSTANCE, t.get("nuke")).result().orElse(Loadout.Nuke.DEFAULT);
            // залп, сохранённый до маршрутов оператора, — без точек
            Waypoints via = t.contains("route") ? Waypoints.CODEC.parse(NbtOps.INSTANCE, t.get("route")).resultOrPartial(Airstrike.LOG::error)
                    .orElse(Waypoints.NONE) : Waypoints.NONE;
            // залп, сохранённый до боеприпасов, не оплачен: возвращать нечего
            return Optional.of(new Salvo(w, t.getInt("total"), t.getInt("remaining"), t.getInt("radius"), c, last, t.getFloat("yaw"),
                    t.hasUUID("owner") ? t.getUUID("owner") : null, t.getInt("cooldown"), nuke, via, t.getBoolean("paid"), t.getInt("failed")));
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
