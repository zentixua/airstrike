package ua.zentix.airstrike.defense;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;

/**
 * Огонь по целям радара ({@link Radar}): по одной ракете на цель — по цели, за которой уже идёт ракета (своя или
 * соседнего ЗРК), второй не пускают, пока та не разорвётся. Промах — цель снова свободна. Сколько ракет готово и как
 * часто пускать, решает пусковая ({@link SamBlockEntity}); этот выбор и пуск возьмёт и переносной ЗРК.
 */
public final class FireControl {
    private FireControl() {}

    /** Ближайшая цель, которую можно бить и по которой ещё никто не стреляет; null — таких нет. */
    @Nullable
    public static Radar.Track pick(List<Radar.Track> tracks, DefenseWorld world) {
        for (Radar.Track t : tracks) {
            if (t.engageable() && !world.engaged(t.id())) return t;
        }
        return null;
    }

    /**
     * Пустить ракету с направляющей {@code rail} по цели {@code track}.
     *
     * @param owner    чья ракета (тот, кто поставил ЗРК)
     * @param launcher откуда (для лога)
     */
    public static Interceptor launch(ServerLevel level, Vec3 rail, Radar.Track track, @Nullable UUID owner, String launcher, InterceptorSpec spec) {
        return DefenseWorld.get(level).launch(level, rail, track, owner, launcher, spec);
    }
}
