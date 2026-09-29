package ua.zentix.airstrike.warhead;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;
import ua.zentix.airstrike.strike.AreaLoader;
import ua.zentix.airstrike.strike.StrikeWorld;
import ua.zentix.airstrike.util.Terrain;

import java.util.Comparator;
import java.util.UUID;

/**
 * Чанки, до которых достаёт взрыв (или весь таймлайн взрыва): ванильный тикет региона ({@link AreaLoader}: тикать
 * начинают, только когда готовы соседи) держит их загруженными целиком, а неготовые грузит в фоне. Нужен потому, что снаряд, взорвавшись, отпускает свои тикеты и тикет района цели:
 * соседние чанки сразу опускаются ниже «готового», и вторичный подрыв через несколько тиков читал бы их синхронно,
 * ожидая загрузку прямо в тике. Тикет не сохраняется в мире; ключ — свой у каждого района, соседние взрывы залпа
 * не снимают его друг у друга.
 */
final class BlastArea {
    private static final TicketType<UUID> TYPE = TicketType.create("airstrike_blast", Comparator.<UUID>naturalOrder());

    private final Vec3 centre;
    private final double reach;
    private final ChunkPos chunk;
    /** Уровень тикета 33 − distance: полностью загружен квадрат ±distance чанков вокруг {@link #chunk}. */
    private final int distance;
    private final UUID key = UUID.randomUUID();

    private BlastArea(Vec3 centre, double reach) {
        this.centre = centre;
        this.reach = reach;
        this.chunk = new ChunkPos(Mth.floor(centre.x) >> 4, Mth.floor(centre.z) >> 4);
        int west = chunk.x - (Mth.floor(centre.x - reach) >> 4);
        int east = (Mth.floor(centre.x + reach) >> 4) - chunk.x;
        int north = chunk.z - (Mth.floor(centre.z - reach) >> 4);
        int south = (Mth.floor(centre.z + reach) >> 4) - chunk.z;
        this.distance = Math.max(Math.max(west, east), Math.max(north, south));
    }

    /** Взять район: всё в {@code reach} блоков от {@code centre} грузится и остаётся готовым до {@link #release}. */
    static BlastArea hold(ServerLevel level, Vec3 centre, double reach) {
        BlastArea area = new BlastArea(centre, reach);
        StrikeWorld.get(level).areas().hold(level, area.area());
        return area;
    }

    boolean ready(ServerLevel level) {
        return Terrain.readyAround(level, centre, reach);
    }

    void release(ServerLevel level) {
        StrikeWorld.get(level).areas().release(level, area());
    }

    private AreaLoader.Area area() {
        return new AreaLoader.Area(TYPE, chunk, distance, key);
    }

    Vec3 centre() {
        return centre;
    }
}
