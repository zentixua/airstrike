package ua.zentix.airstrike.entity.flight;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import ua.zentix.airstrike.guidance.Route;
import ua.zentix.airstrike.strike.FlightTickets;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Район цели, который снаряд догружает заранее ({@link FlightTickets}), и полоса подлёта к ней
 * ({@link FlightTickets#approach}). Не сохраняется, как и тикеты: после загрузки снаряд берёт район заново на подлёте.
 */
public final class TargetAreaHold {
    /** Чанк, вокруг которого держится район цели (null — не держится). */
    @Nullable
    private ChunkPos area;
    /** Размер района, взятого в {@link #area}: уровень тикета {@link FlightTickets}. */
    private int size;
    /** Полоса подлёта, которую держит снаряд; пустая — не держит. */
    private List<ChunkPos> approach = List.of();

    /**
     * Держать район цели {@code aim}, когда до неё не дальше {@code preload}: цель ушла на 2 чанка и дальше — район
     * идёт за ней. Полоса подлёта длиной {@code visibleLeg} (0 — без неё) — когда район взят и раз в секунду.
     *
     * @param size  размер района: уровень тикета {@link FlightTickets}
     * @param route маршрут снаряда (полоса ложится назад по нему от цели) или null
     * @param age   возраст снаряда, тиков: по нему — раз в секунду
     */
    public void hold(ServerLevel level, UUID owner, Vec3 pos, Vec3 aim, double preload, int size, double visibleLeg,
                     @Nullable Route route, int age) {
        if (area != null && area.getChessboardDistance(new ChunkPos(BlockPos.containing(aim))) >= 2) release(level, owner);
        boolean taken = false;
        if (area == null && pos.distanceTo(aim) <= preload) {
            area = new ChunkPos(BlockPos.containing(aim));
            this.size = size;
            FlightTickets.hold(level, area, size, owner, true);
            taken = true;
        }
        if (area != null && visibleLeg > 0 && (taken || age % 20 == 0)) updateApproach(level, owner, pos, aim, visibleLeg, route);
    }

    /**
     * Полоса подлёта, пока держится район цели: берётся, когда у цели есть кому смотреть ({@link FlightTickets#watched}),
     * и отпускается, когда смотреть больше некому. Не хватило места в мире ({@link FlightTickets#APPROACH_LIMIT}) —
     * попробует через секунду.
     */
    private void updateApproach(ServerLevel level, UUID owner, Vec3 pos, Vec3 aim, double visibleLeg, @Nullable Route route) {
        if (!FlightTickets.watched(level, aim, visibleLeg)) {
            FlightTickets.releaseApproach(level, approach, owner);
            approach = List.of();
            return;
        }
        if (!approach.isEmpty()) return;
        // назад по пути снаряда: цель, точки маршрута от последней (точка входа) к ближайшей, сам снаряд
        List<Vec3> path = new ArrayList<>();
        path.add(aim);
        if (route != null) {
            List<Vec3> pts = route.points();
            for (int i = pts.size() - 1; i >= route.index(); i--) path.add(pts.get(i));
        }
        path.add(pos);
        List<ChunkPos> centres = FlightTickets.approach(path, visibleLeg);
        if (FlightTickets.holdApproach(level, centres, owner)) approach = centres;
    }

    /** Отпустить район и полосу (снаряд убран или перенацелен — новый район возьмётся на подлёте). */
    public void release(ServerLevel level, UUID owner) {
        if (area != null) FlightTickets.hold(level, area, size, owner, false);
        FlightTickets.releaseApproach(level, approach, owner);
        area = null;
        approach = List.of();
    }
}
