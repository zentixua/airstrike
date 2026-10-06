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
 * <p>
 * Район берётся сначала без тика — только загрузка: тикать ему нужно, лишь чтобы снаряд вернулся в мир и взорвался,
 * а тикающий район — это его мобы, блок-сущности и спавн. Залп по всей карте держал десятки районов тикающими по 15 с
 * подлёта каждого снаряда (Zearth 06.10.2026). Тикающим район берётся, когда снаряд скажет ({@code ticks}: последние
 * секунды подлёта), и с тех пор остаётся таким; район без тика отпускается после — чанки не проседают ни на тик.
 */
public final class TargetAreaHold {
    /** Чанк, вокруг которого держится район цели (null — не держится). */
    @Nullable
    private ChunkPos area;
    /** Размер района, взятого в {@link #area}: уровень тикета {@link FlightTickets}. */
    private int size;
    /** Район взят тикающим, а не только загружается. */
    private boolean ticking;
    /** Полоса подлёта, которую держит снаряд; пустая — не держит. */
    private List<ChunkPos> approach = List.of();

    /**
     * Держать район цели {@code aim}, когда до неё не дальше {@code preload}: цель ушла на 2 чанка и дальше — район
     * идёт за ней. Тикающим — с тех пор, как {@code ticks}. Полоса подлёта длиной {@code visibleLeg} (0 — без неё) —
     * когда район взят и раз в секунду.
     *
     * @param size  размер района: уровень тикета {@link FlightTickets}
     * @param ticks району пора тикать: снаряд на последних секундах подлёта
     * @param route маршрут снаряда (полоса ложится назад по нему от цели) или null
     * @param age   возраст снаряда, тиков: по нему — раз в секунду
     */
    public void hold(ServerLevel level, UUID owner, Vec3 pos, Vec3 aim, double preload, int size, boolean ticks, double visibleLeg,
                     @Nullable Route route, int age) {
        if (area != null && area.getChessboardDistance(new ChunkPos(BlockPos.containing(aim))) >= 2) release(level, owner);
        boolean taken = false;
        if (area == null && pos.distanceTo(aim) <= preload) {
            area = new ChunkPos(BlockPos.containing(aim));
            this.size = size;
            ticking = ticks;
            FlightTickets.hold(level, area, size, owner, ticking, true);
            taken = true;
        } else if (area != null && ticks && !ticking) {
            // сначала тикающий, потом без тика: уровни чанков не проседают ни на тик
            FlightTickets.hold(level, area, this.size, owner, true, true);
            FlightTickets.hold(level, area, this.size, owner, false, false);
            ticking = true;
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

    /** Район цели взят тикающим: снаряд на последних секундах подлёта. */
    public boolean ticking() {
        return area != null && ticking;
    }

    /** Отпустить район и полосу (снаряд убран или перенацелен — новый район возьмётся на подлёте). */
    public void release(ServerLevel level, UUID owner) {
        if (area != null) FlightTickets.hold(level, area, size, owner, ticking, false);
        FlightTickets.releaseApproach(level, approach, owner);
        area = null;
        approach = List.of();
    }
}
