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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Ядерные события измерения (сохраняются в мире): случившиеся подрывы — по ним и сервер, и клиенты
 * считают разрушения, облако и осадки — и запланированные удары МБР (полёт — это таймер, а не сущность:
 * переживает перезапуск сервера и не держит чанков).
 */
public final class NuclearEvents extends SavedData {
    private static final String NAME = Airstrike.MOD_ID + "_nuclear";
    /**
     * Подрыв забывается через 7 игровых суток: осадки к тому времени спадают в сотни раз. Разрушения помнятся
     * дольше ({@link #past}): чанк, впервые загруженный через месяц, всё равно разрушен.
     */
    public static final long FORGET_AFTER = 7 * 24_000L;

    /**
     * Запланированный удар: цель, мощность, подрыв, когда и откуда стартовала ракета.
     *
     * @param surface цель — место на земле (с карты): подрыв на поверхности, какой бы ни была оценка высоты при пуске
     */
    public record ScheduledStrike(int id, Vec3 target, double yieldKt, boolean airBurst, long launchTime, long detonateTime,
                                  Vec3 launchPos, Optional<UUID> owner, boolean surface) {
        public static final Codec<ScheduledStrike> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.INT.fieldOf("id").forGetter(ScheduledStrike::id),
                Vec3.CODEC.fieldOf("target").forGetter(ScheduledStrike::target),
                Codec.DOUBLE.fieldOf("yield").forGetter(ScheduledStrike::yieldKt),
                Codec.BOOL.fieldOf("air_burst").forGetter(ScheduledStrike::airBurst),
                Codec.LONG.fieldOf("launch_time").forGetter(ScheduledStrike::launchTime),
                Codec.LONG.fieldOf("detonate_time").forGetter(ScheduledStrike::detonateTime),
                Vec3.CODEC.fieldOf("launch_pos").forGetter(ScheduledStrike::launchPos),
                UUIDUtil.CODEC.optionalFieldOf("owner").forGetter(ScheduledStrike::owner),
                Codec.BOOL.optionalFieldOf("surface", false).forGetter(ScheduledStrike::surface)
        ).apply(i, ScheduledStrike::new));
    }

    private final List<Detonation> detonations = new ArrayList<>();
    /** Забытые подрывы: ни осадков, ни картинки — только разрушения в чанках, которые загрузятся позже. */
    private final List<Detonation> past = new ArrayList<>();
    private final List<ScheduledStrike> scheduled = new ArrayList<>();
    /** Недорытые воронки: номер подрыва → сколько чанков уже вырыто. */
    private final Map<Integer, Integer> craters = new LinkedHashMap<>();
    private static final Codec<Map<Integer, Integer>> CRATERS_CODEC = Codec.unboundedMap(Codec.STRING.xmap(Integer::parseInt, String::valueOf), Codec.INT);
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

    /** Забытые подрывы, которые ещё разрушают загружающиеся чанки (см. {@link #prune}). */
    public List<Detonation> past() {
        return past;
    }

    public boolean isPast(int id) {
        return past.stream().anyMatch(d -> d.id() == id);
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

    public Map<Integer, Integer> craters() {
        return craters;
    }

    /** Ход воронки: сколько чанков вырыто; {@code done} — вырыта целиком. */
    public void craterProgress(int detonation, int chunks, boolean done) {
        Integer prev = done ? craters.remove(detonation) : craters.put(detonation, chunks);
        if (prev == null || prev != chunks || done) setDirty();
    }

    /**
     * Отбой: запланированные удары отменены, подрывы забыты — нет больше ни осадков, ни разрушений в чанках,
     * которые загрузятся потом. Разрушенное остаётся. Номера подрывов продолжают расти (отметки чанков верны).
     */
    public int clear() {
        int n = scheduled.size() + detonations.size();
        scheduled.clear();
        detonations.clear();
        past.clear();
        craters.clear();
        setDirty();
        return n;
    }

    /** Забыть старые подрывы: осадки и эффекты кончились, разрушения — остаются в {@link #past}. */
    public void prune(long now) {
        List<Detonation> faded = detonations.stream().filter(d -> now - d.gameTime() > FORGET_AFTER).toList();
        if (!faded.isEmpty()) {
            detonations.removeAll(faded);
            past.addAll(faded);
            setDirty();
        }
        if (craters.keySet().removeIf(id -> detonations.stream().noneMatch(d -> d.id() == id))) setDirty();
    }

    private static NuclearEvents load(CompoundTag tag, HolderLookup.Provider registries) {
        NuclearEvents e = new NuclearEvents();
        e.nextId = Math.max(1, tag.getInt("next_id"));
        Detonation.CODEC.listOf().parse(NbtOps.INSTANCE, tag.get("detonations")).resultOrPartial(Airstrike.LOG::error).ifPresent(e.detonations::addAll);
        if (tag.contains("past")) Detonation.CODEC.listOf().parse(NbtOps.INSTANCE, tag.get("past")).resultOrPartial(Airstrike.LOG::error).ifPresent(e.past::addAll);
        ScheduledStrike.CODEC.listOf().parse(NbtOps.INSTANCE, tag.get("scheduled")).resultOrPartial(Airstrike.LOG::error).ifPresent(e.scheduled::addAll);
        if (tag.contains("craters")) CRATERS_CODEC.parse(NbtOps.INSTANCE, tag.get("craters")).resultOrPartial(Airstrike.LOG::error).ifPresent(e.craters::putAll);
        return e;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt("next_id", nextId);
        Detonation.CODEC.listOf().encodeStart(NbtOps.INSTANCE, detonations).resultOrPartial(Airstrike.LOG::error).ifPresent(t -> tag.put("detonations", t));
        Detonation.CODEC.listOf().encodeStart(NbtOps.INSTANCE, past).resultOrPartial(Airstrike.LOG::error).ifPresent(t -> tag.put("past", t));
        ScheduledStrike.CODEC.listOf().encodeStart(NbtOps.INSTANCE, scheduled).resultOrPartial(Airstrike.LOG::error).ifPresent(t -> tag.put("scheduled", t));
        CRATERS_CODEC.encodeStart(NbtOps.INSTANCE, craters).resultOrPartial(Airstrike.LOG::error).ifPresent(t -> tag.put("craters", t));
        return tag;
    }
}
