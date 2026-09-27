package ua.zentix.airstrike.nuclear;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.Vec3;
import ua.zentix.airstrike.Airstrike;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Ядерные события измерения (сохраняются в мире): случившиеся подрывы — по ним и сервер, и клиенты
 * считают разрушения, облако и осадки — и запланированные удары МБР (полёт — это таймер, а не сущность:
 * переживает перезапуск сервера и не держит чанков).
 */
public final class NuclearEvents extends SavedData {
    private static final String NAME = Airstrike.MOD_ID + "_nuclear";
    /** Подрыв забывается через 7 игровых суток: осадки к тому времени спадают в сотни раз. */
    public static final long FORGET_AFTER = 7 * 24_000L;

    /** Запланированный удар: цель, мощность, подрыв, когда и откуда стартовала ракета. */
    public record ScheduledStrike(int id, Vec3 target, double yieldKt, boolean airBurst, long launchTime, long detonateTime,
                                  Vec3 launchPos, Optional<UUID> owner) {
        public static final Codec<ScheduledStrike> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.INT.fieldOf("id").forGetter(ScheduledStrike::id),
                Vec3.CODEC.fieldOf("target").forGetter(ScheduledStrike::target),
                Codec.DOUBLE.fieldOf("yield").forGetter(ScheduledStrike::yieldKt),
                Codec.BOOL.fieldOf("air_burst").forGetter(ScheduledStrike::airBurst),
                Codec.LONG.fieldOf("launch_time").forGetter(ScheduledStrike::launchTime),
                Codec.LONG.fieldOf("detonate_time").forGetter(ScheduledStrike::detonateTime),
                Vec3.CODEC.fieldOf("launch_pos").forGetter(ScheduledStrike::launchPos),
                UUIDUtil.CODEC.optionalFieldOf("owner").forGetter(ScheduledStrike::owner)
        ).apply(i, ScheduledStrike::new));
    }

    private final List<Detonation> detonations = new ArrayList<>();
    private final List<ScheduledStrike> scheduled = new ArrayList<>();
    private int nextId = 1;

    public static NuclearEvents get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(new Factory<>(NuclearEvents::new, NuclearEvents::load, null), NAME);
    }

    public int nextId() {
        setDirty();
        return nextId++;
    }

    public List<Detonation> detonations() {
        return detonations;
    }

    public List<ScheduledStrike> scheduled() {
        return scheduled;
    }

    public void add(Detonation d) {
        detonations.add(d);
        setDirty();
    }

    public void schedule(ScheduledStrike s) {
        scheduled.add(s);
        setDirty();
    }

    public void unschedule(ScheduledStrike s) {
        if (scheduled.remove(s)) setDirty();
    }

    /** Отбой: запланированные удары отменены, радиоактивные следы убраны. Разрушенное остаётся. */
    public int clear() {
        int n = scheduled.size();
        scheduled.clear();
        detonations.replaceAll(Detonation::withoutFallout);
        setDirty();
        return n;
    }

    /** Забыть старые подрывы. */
    public void prune(long now) {
        if (detonations.removeIf(d -> now - d.gameTime() > FORGET_AFTER)) setDirty();
    }

    private static NuclearEvents load(CompoundTag tag, HolderLookup.Provider registries) {
        NuclearEvents e = new NuclearEvents();
        e.nextId = Math.max(1, tag.getInt("next_id"));
        Detonation.CODEC.listOf().parse(NbtOps.INSTANCE, tag.get("detonations")).resultOrPartial(Airstrike.LOG::error).ifPresent(e.detonations::addAll);
        ScheduledStrike.CODEC.listOf().parse(NbtOps.INSTANCE, tag.get("scheduled")).resultOrPartial(Airstrike.LOG::error).ifPresent(e.scheduled::addAll);
        return e;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt("next_id", nextId);
        Detonation.CODEC.listOf().encodeStart(NbtOps.INSTANCE, detonations).resultOrPartial(Airstrike.LOG::error).ifPresent(t -> tag.put("detonations", t));
        ScheduledStrike.CODEC.listOf().encodeStart(NbtOps.INSTANCE, scheduled).resultOrPartial(Airstrike.LOG::error).ifPresent(t -> tag.put("scheduled", t));
        return tag;
    }
}
