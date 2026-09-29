package ua.zentix.airstrike.strike;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.saveddata.SavedData;
import ua.zentix.airstrike.Airstrike;
import ua.zentix.airstrike.entity.StrikeProjectile;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Снаряды, летящие вне загруженного мира: живые объекты сущностей, не добавленные в мир. Каждый тик —
 * {@link StrikeProjectile#virtualTick}; вошёл в тикающие чанки — снова добавляется в мир с тем же UUID
 * (клиенты видят его как новую сущность, камера и HUD узнают по UUID). Хранится в мире: полёт переживает
 * перезапуск сервера.
 */
public final class VirtualFlights extends SavedData {
    private static final String NAME = Airstrike.MOD_ID + "_virtual_flights";
    private static final Factory<VirtualFlights> FACTORY = new Factory<>(VirtualFlights::new, VirtualFlights::load, null);

    private final List<StrikeProjectile> flights = new ArrayList<>();
    /** Прочитанные с диска и ещё не созданные (сущность создаётся в первом тике мира). */
    private final List<CompoundTag> pending = new ArrayList<>();

    public static VirtualFlights get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(FACTORY, NAME);
    }

    /**
     * Снаряд уходит из тикающих чанков: сущность удаляется из мира, а её копия продолжает полёт здесь.
     *
     * @return копия, летящая вне мира, или null, если сохранить снаряд не вышло (тогда он просто убран)
     */
    public static StrikeProjectile park(ServerLevel level, StrikeProjectile p) {
        CompoundTag tag = new CompoundTag();
        boolean saved = p.save(tag);
        p.discard();
        if (!saved) return null;
        StrikeProjectile copy = create(level, tag);
        if (copy != null) get(level).add(copy);
        return copy;
    }

    /** Начать полёт сразу вне мира (заход издалека: B-2, пуск без пусковой рядом). */
    public static void launch(ServerLevel level, StrikeProjectile p) {
        get(level).add(p);
    }

    private void add(StrikeProjectile p) {
        p.markVirtual();
        flights.add(p);
        setDirty();
    }

    public List<StrikeProjectile> flights() {
        return flights;
    }

    /** Сколько снарядов летит вне мира (с прочитанными с диска, но ещё не созданными). */
    public int size() {
        return flights.size() + pending.size();
    }

    /**
     * Отбой: убрать без взрыва снаряды, подходящие под {@code which}.
     *
     * @return UUID убранных
     */
    public List<UUID> clear(ServerLevel level, Predicate<StrikeProjectile> which) {
        createPending(level);
        List<UUID> removed = new ArrayList<>();
        for (Iterator<StrikeProjectile> it = flights.iterator(); it.hasNext(); ) {
            StrikeProjectile p = it.next();
            if (!which.test(p)) continue;
            p.discard();
            it.remove();
            removed.add(p.getUUID());
        }
        if (!removed.isEmpty()) setDirty();
        return removed;
    }

    /** Создать сущности прочитанных с диска полётов. */
    private void createPending(ServerLevel level) {
        if (pending.isEmpty()) return;
        for (CompoundTag t : pending) {
            StrikeProjectile p = create(level, t);
            if (p != null) {
                p.markVirtual();
                flights.add(p);
            }
        }
        pending.clear();
    }

    void tick(ServerLevel level) {
        createPending(level);
        if (flights.isEmpty()) return;
        // снимок: вернувшийся в мир снаряд может в том же тике снова уйти (и добавиться сюда)
        List<StrikeProjectile> now = new ArrayList<>(flights);
        flights.clear();
        for (StrikeProjectile p : now) {
            p.virtualTick(level);
            if (p.isRemoved()) continue;
            if (p.canMaterialize(level)) {
                p.materialize(level);
                if (level.addFreshEntity(p)) continue;
                Airstrike.LOG.warn("Снаряд {} не вернулся в мир у {}", p.getType().getDescriptionId(), p.blockPosition());
                p.discard(); // снять тикеты района цели и своих чанков: в мире его нет, сам он их уже не отпустит
                continue;
            }
            flights.add(p);
        }
        setDirty();
    }

    private static StrikeProjectile create(ServerLevel level, CompoundTag tag) {
        return EntityType.create(tag, level).filter(e -> e instanceof StrikeProjectile).map(e -> (StrikeProjectile) e).orElse(null);
    }

    private static VirtualFlights load(CompoundTag tag, HolderLookup.Provider registries) {
        VirtualFlights v = new VirtualFlights();
        for (Tag t : tag.getList("flights", Tag.TAG_COMPOUND)) v.pending.add((CompoundTag) t);
        return v;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (StrikeProjectile p : flights) {
            CompoundTag t = new CompoundTag();
            if (p.save(t)) list.add(t);
        }
        list.addAll(pending);
        tag.put("flights", list);
        return tag;
    }
}
