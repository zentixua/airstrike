package ua.zentix.airstrike.strike;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Район цели снаряда: чанки грузятся (и генерируются) в фоне, пока снаряд на подлёте, — к его прибытию там тикают
 * сущности, и он (в мире или вернувшись в мир из полёта вне его) бьёт как обычно. Грузится район сразу, а тикать
 * начинает по мере готовности ({@link AreaLoader}).
 * У каждого снаряда свой тикет (ключ — его UUID): залп по одной точке не снимает тикет друг у друга. Полосы подлёта
 * ракет — общие ({@link #holdApproach}).
 * Тикеты не сохраняются: после перезапуска снаряд возьмёт свой заново.
 */
public final class FlightTickets {
    private static final TicketType<UUID> TYPE = TicketType.create("airstrike_flight", Comparator.<UUID>naturalOrder());
    /**
     * Уровень тикета 33 − 4 = 29: сущности тикают в квадрате 5×5 чанков вокруг цели (±40 блоков), загружено 9×9 —
     * снаряд появляется в мире до цели, а соседние чанки готовы для взрыва и обломков.
     */
    public static final int DISTANCE = 4;
    /** Район цели «Ланцета» ({@code LoiterEntity.targetArea}): уровень 27, круг барража весь в тикающих чанках. */
    public static final int LOITER_DISTANCE = 6;
    /**
     * Полоса подлёта ({@link #approach}): районы уровня 33 − 3 = 30 — сущности тикают в квадрате 3×3 чанков вокруг
     * центра (ваниль пускает их, когда готов квадрат 5×5, загружено 7×7).
     */
    public static final int APPROACH_DISTANCE = 3;
    /** Шаг центров полосы подлёта, блоков: два чанка — квадраты 3×3 соседних центров перекрываются и на диагонали. */
    private static final double APPROACH_STEP = 32;
    /**
     * Районов полос подлёта в мире не больше: 4 полосы по 256 блоков (8 районов). Соседние районы перекрываются:
     * ~150 загруженных чанков на полосу, ~600 на все.
     */
    public static final int APPROACH_LIMIT = 32;
    /** Ключ районов полос: общий у всех снарядов, кто держит — {@link StrikeWorld#approach}. */
    private static final UUID APPROACH_KEY = UUID.nameUUIDFromBytes("airstrike:approach".getBytes(StandardCharsets.UTF_8));

    private FlightTickets() {}

    /**
     * Полоса подлёта: центры районов {@link #APPROACH_DISTANCE} от цели на {@code length} блоков назад по пути снаряда
     * ({@code path}: цель, потом точки маршрута от последней к ближайшей, потом сам снаряд) — сплошная полоса шириной
     * 3 чанка, где тикают сущности. Снаряд вне мира возвращается в мир на её краю, а не у района цели: подлёт видно
     * игроку у цели и там, где его дистанция симуляции меньше дальности прорисовки. Без самой цели: её держит район цели.
     */
    public static List<ChunkPos> approach(List<Vec3> path, double length) {
        if (path.size() < 2) return List.of();
        ChunkPos target = new ChunkPos(BlockPos.containing(path.getFirst()));
        Set<ChunkPos> centres = new LinkedHashSet<>();
        double s = APPROACH_STEP, walked = 0;
        for (int i = 1; i < path.size() && s <= length; i++) {
            Vec3 a = path.get(i - 1), b = path.get(i);
            double dx = b.x - a.x, dz = b.z - a.z, d = Math.sqrt(dx * dx + dz * dz);
            // центры на этом отрезке пути: по дуге пути от цели через каждые APPROACH_STEP
            for (; s <= length && s <= walked + d; s += APPROACH_STEP) {
                double t = (s - walked) / d;
                ChunkPos c = new ChunkPos(BlockPos.containing(a.x + dx * t, a.y, a.z + dz * t));
                if (!c.equals(target)) centres.add(c);
            }
            walked += d;
        }
        return List.copyOf(centres);
    }

    /**
     * Есть ли кому увидеть подлёт к {@code aim}: игрок этого мира ближе дальности прорисовки сервера и {@code leg}
     * по горизонтали. Нет — полоса не нужна: снаряд войдёт в мир у района цели, как остальные снаряды.
     */
    public static boolean watched(ServerLevel level, Vec3 aim, double leg) {
        double r = level.getServer().getPlayerList().getViewDistance() * 16.0 + leg;
        for (ServerPlayer p : level.players()) {
            double dx = p.getX() - aim.x, dz = p.getZ() - aim.z;
            if (dx * dx + dz * dz <= r * r) return true;
        }
        return false;
    }

    /**
     * Взять полосу подлёта снаряду {@code flight}. Районы полос общие для мира (один ключ): залп по одной точке с одного
     * направления держит одну полосу, а не по полосе на снаряд; район отпускается, когда его не держит ни один снаряд.
     * Районов полос в мире не больше {@link #APPROACH_LIMIT}: полоса, которой не хватает места, не берётся целиком
     * (false) — снаряд войдёт в мир у района цели. Каждый район полосы — 7×7 загруженных чанков, в несгенерированной
     * местности это очередь генерации, общая с игроками.
     */
    public static boolean holdApproach(ServerLevel level, List<ChunkPos> centres, UUID flight) {
        StrikeWorld world = StrikeWorld.get(level);
        Map<ChunkPos, Set<UUID>> refs = world.approach();
        long fresh = centres.stream().filter(c -> !refs.containsKey(c)).count();
        if (refs.size() + fresh > APPROACH_LIMIT) return false;
        for (ChunkPos c : centres) {
            Set<UUID> by = refs.computeIfAbsent(c, k -> new HashSet<>());
            if (by.isEmpty()) world.areas().hold(level, approachArea(c));
            by.add(flight);
        }
        return true;
    }

    /** Отпустить полосу подлёта снаряда {@code flight}: районы, которые больше никто не держит, отпускаются. */
    public static void releaseApproach(ServerLevel level, List<ChunkPos> centres, UUID flight) {
        StrikeWorld world = StrikeWorld.get(level);
        Map<ChunkPos, Set<UUID>> refs = world.approach();
        for (ChunkPos c : centres) {
            Set<UUID> by = refs.get(c);
            if (by == null || !by.remove(flight) || !by.isEmpty()) continue;
            refs.remove(c);
            world.areas().release(level, approachArea(c));
        }
    }

    /** Сколько районов полос подлёта взято в мире (проверки). */
    public static int approachAreas(ServerLevel level) {
        return StrikeWorld.get(level).areas().count(TYPE, APPROACH_KEY);
    }

    private static AreaLoader.Area approachArea(ChunkPos c) {
        return new AreaLoader.Area(TYPE, c, APPROACH_DISTANCE, APPROACH_KEY);
    }

    /** Сколько районов держит снаряд {@code flight}: район цели и районы полосы подлёта, общие с другими (проверки). */
    public static int held(ServerLevel level, UUID flight) {
        StrikeWorld world = StrikeWorld.get(level);
        int approach = (int) world.approach().values().stream().filter(by -> by.contains(flight)).count();
        return world.areas().count(TYPE, flight) + approach;
    }

    /** {@code distance} — уровень тикета: {@link #DISTANCE} по умолчанию, 6 — сущности тикают в квадрате 9×9 чанков. */
    public static void hold(ServerLevel level, ChunkPos pos, int distance, UUID flight, boolean hold) {
        AreaLoader.Area area = new AreaLoader.Area(TYPE, pos, distance, flight);
        if (hold) StrikeWorld.get(level).areas().hold(level, area);
        else StrikeWorld.get(level).areas().release(level, area);
    }
}
