package ua.zentix.airstrike.grid;

import com.mojang.serialization.Codec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.Nullable;
import ua.zentix.airstrike.Airstrike;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Сеть измерения (сохраняется в мире): узлы и отключения. Какой квартал тёмен — функция отключений и времени
 * ({@link #dark}); чанки приводятся к ней, когда они загружены ({@link BlackoutWorld}, {@link ChunkSaves}).
 */
public final class PowerGrid extends SavedData {
    private static final String NAME = Airstrike.MOD_ID + "_grid";
    private static final Codec<Map<Integer, Long>> PROGRESS_CODEC =
            Codec.unboundedMap(Codec.STRING.xmap(Integer::parseInt, String::valueOf), Codec.LONG);

    private final Map<Integer, Node> nodes = new LinkedHashMap<>();
    private final List<Outage> outages = new ArrayList<>();
    /**
     * Докуда (игровое время) каскад отключения уже выдан; {@code Long.MAX_VALUE} — пройден весь район и после
     * перезапуска не повторяется.
     */
    private final Map<Integer, Long> darkSwept = new LinkedHashMap<>();
    /** То же для возврата света. */
    private final Map<Integer, Long> lightSwept = new LinkedHashMap<>();
    private int nextId = 1;

    public static PowerGrid get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(new Factory<>(PowerGrid::new, PowerGrid::load, null), NAME);
    }

    // ---------------------------------------------------------------- узлы

    public Iterable<Node> nodes() {
        return Collections.unmodifiableCollection(nodes.values());
    }

    @Nullable
    public Node node(int id) {
        return nodes.get(id);
    }

    @Nullable
    public Node nodeAt(BlockPos pos) {
        for (Node n : nodes.values()) {
            if (n.pos().equals(pos)) return n;
        }
        return null;
    }

    public Node addNode(BlockPos pos, double radius, boolean block) {
        Node n = new Node(nextId++, pos.immutable(), radius, block);
        nodes.put(n.id(), n);
        setDirty();
        return n;
    }

    @Nullable
    public Node removeNode(int id) {
        Node n = nodes.remove(id);
        if (n != null) setDirty();
        return n;
    }

    /** Отключение, которое вызвал выход узла из строя и в которое свет ещё не начали возвращать. */
    public Optional<Outage> downOutage(int node, long now) {
        return outages.stream().filter(o -> o.node() == node && now < o.restoreAt()).findFirst();
    }

    // ---------------------------------------------------------------- отключения

    public List<Outage> outages() {
        return Collections.unmodifiableList(outages);
    }

    public Outage addOutage(double x, double z, double radius, long start, double speed, long restoreAt, int restoreSpread, int node) {
        Outage o = new Outage(nextId++, x, z, radius, start, speed, restoreAt, restoreSpread, node);
        outages.add(o);
        setDirty();
        return o;
    }

    /** Заменить отключение (тот же номер) — например, назначить возврат света. */
    public void replace(Outage o) {
        for (int i = 0; i < outages.size(); i++) {
            if (outages.get(i).id() == o.id()) {
                outages.set(i, o);
                lightSwept.remove(o.id());
                setDirty();
                return;
            }
        }
    }

    /** Квартал чанка должен быть тёмным в момент {@code now}. */
    public boolean dark(int chunkX, int chunkZ, long now) {
        if (outages.isEmpty()) return false;
        long district = Districts.of(chunkX, chunkZ);
        for (Outage o : outages) {
            if (o.dark(district, now)) return true;
        }
        return false;
    }

    /** Квартал чанка задевает хоть одно отключение (тёмен сейчас, погаснет или ещё возвращается). */
    public boolean covered(int chunkX, int chunkZ) {
        if (outages.isEmpty()) return false;
        long district = Districts.of(chunkX, chunkZ);
        for (Outage o : outages) {
            if (o.covers(district)) return true;
        }
        return false;
    }

    /**
     * Забыть отключения, в которые свет вернулся везде: срок вышел и каскад возврата прошёл весь район (иначе
     * загруженные чанки, до которых он не дошёл, остались бы тёмными).
     */
    public void prune(long now) {
        List<Outage> over = outages.stream().filter(o -> o.over(now) && swept(o.id(), true) == Long.MAX_VALUE).toList();
        if (!over.isEmpty()) {
            outages.removeAll(over);
            over.forEach(o -> {
                darkSwept.remove(o.id());
                lightSwept.remove(o.id());
            });
            setDirty();
        }
    }

    public long swept(int outage, boolean restore) {
        return (restore ? lightSwept : darkSwept).getOrDefault(outage, Long.MIN_VALUE);
    }

    public void swept(int outage, boolean restore, long upTo) {
        Long prev = (restore ? lightSwept : darkSwept).put(outage, upTo);
        if (prev == null || prev != upTo) setDirty();
    }

    // ---------------------------------------------------------------- сохранение

    private static PowerGrid load(CompoundTag tag, HolderLookup.Provider registries) {
        PowerGrid g = new PowerGrid();
        g.nextId = Math.max(1, tag.getInt("next_id"));
        Node.CODEC.listOf().parse(NbtOps.INSTANCE, tag.get("nodes")).resultOrPartial(Airstrike.LOG::error)
                .ifPresent(l -> l.forEach(n -> g.nodes.put(n.id(), n)));
        Outage.CODEC.listOf().parse(NbtOps.INSTANCE, tag.get("outages")).resultOrPartial(Airstrike.LOG::error).ifPresent(g.outages::addAll);
        if (tag.contains("dark_swept")) PROGRESS_CODEC.parse(NbtOps.INSTANCE, tag.get("dark_swept")).resultOrPartial(Airstrike.LOG::error).ifPresent(g.darkSwept::putAll);
        if (tag.contains("light_swept")) PROGRESS_CODEC.parse(NbtOps.INSTANCE, tag.get("light_swept")).resultOrPartial(Airstrike.LOG::error).ifPresent(g.lightSwept::putAll);
        return g;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt("next_id", nextId);
        Node.CODEC.listOf().encodeStart(NbtOps.INSTANCE, List.copyOf(nodes.values())).resultOrPartial(Airstrike.LOG::error).ifPresent(t -> tag.put("nodes", t));
        Outage.CODEC.listOf().encodeStart(NbtOps.INSTANCE, outages).resultOrPartial(Airstrike.LOG::error).ifPresent(t -> tag.put("outages", t));
        PROGRESS_CODEC.encodeStart(NbtOps.INSTANCE, darkSwept).resultOrPartial(Airstrike.LOG::error).ifPresent(t -> tag.put("dark_swept", t));
        PROGRESS_CODEC.encodeStart(NbtOps.INSTANCE, lightSwept).resultOrPartial(Airstrike.LOG::error).ifPresent(t -> tag.put("light_swept", t));
        return tag;
    }
}
