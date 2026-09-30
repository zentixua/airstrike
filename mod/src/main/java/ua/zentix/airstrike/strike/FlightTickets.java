package ua.zentix.airstrike.strike;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Район цели снаряда: чанки грузятся (и генерируются) в фоне, пока снаряд на подлёте, — к его прибытию там тикают
 * сущности, и он (в мире или вернувшись в мир из полёта вне его) бьёт как обычно. Грузится район сразу, а тикать
 * начинает по мере готовности ({@link AreaLoader}).
 * У каждого снаряда свой тикет (ключ — его UUID): залп по одной точке не снимает тикет друг у друга.
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

    private FlightTickets() {}

    /**
     * Полоса подлёта: центры районов {@link #APPROACH_DISTANCE} от цели на {@code length} блоков в сторону {@code from}
     * (откуда снаряд придёт) — сплошная полоса шириной 3 чанка, где тикают сущности. Снаряд вне мира возвращается
     * в мир на её краю, а не у района цели: подлёт видно игроку у цели и там, где его дистанция симуляции меньше
     * дальности прорисовки. Без самой цели: её держит район цели.
     */
    public static List<ChunkPos> approach(Vec3 aim, Vec3 from, double length) {
        double dx = from.x - aim.x, dz = from.z - aim.z, d = Math.sqrt(dx * dx + dz * dz);
        if (d < 1) return List.of();
        ChunkPos target = new ChunkPos(BlockPos.containing(aim));
        Set<ChunkPos> centres = new LinkedHashSet<>();
        for (double s = APPROACH_STEP; s <= length; s += APPROACH_STEP) {
            ChunkPos c = new ChunkPos(BlockPos.containing(aim.x + dx / d * s, aim.y, aim.z + dz / d * s));
            if (!c.equals(target)) centres.add(c);
        }
        return List.copyOf(centres);
    }

    /** Сколько районов (район цели и полоса подлёта) держит снаряд {@code flight} (проверки). */
    public static int held(ServerLevel level, UUID flight) {
        return StrikeWorld.get(level).areas().count(TYPE, flight);
    }

    /** {@code distance} — уровень тикета: {@link #DISTANCE} по умолчанию, 6 — сущности тикают в квадрате 9×9 чанков. */
    public static void hold(ServerLevel level, ChunkPos pos, int distance, UUID flight, boolean hold) {
        AreaLoader.Area area = new AreaLoader.Area(TYPE, pos, distance, flight);
        if (hold) StrikeWorld.get(level).areas().hold(level, area);
        else StrikeWorld.get(level).areas().release(level, area);
    }
}
