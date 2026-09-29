package ua.zentix.airstrike.nuclear.radiation;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.entity.EntityTypeTest;
import org.jetbrains.annotations.Nullable;
import ua.zentix.airstrike.AirstrikeConfig;
import ua.zentix.airstrike.nuclear.Detonation;
import ua.zentix.airstrike.nuclear.world.WorkClock;
import ua.zentix.airstrike.util.Terrain;

import java.util.List;

/**
 * Осадки у мобов (DESIGN-nuke §4). У игроков доза идёт раз в секунду; мобов в загруженном мире тысячи, поэтому
 * раз в {@link #PERIOD} тиков — снимок мобов уровня, и каждому доза за прошедшее время по мощности осадков в его
 * точке с крышей над головой. И снимок, и каждый моб — единицы работы под общим бюджетом ядерной работы
 * ({@link WorkClock}), как свет и разрушения. Заражение одеждой и чёрный дождь — только у игроков.
 */
public final class MobFallout {
    /** Раз в сколько тиков мобы получают дозу осадков (10 с — 0.2 игрового часа). */
    public static final int PERIOD = 200;
    /** Мощность меньше этой (Р/ч) — фон, крышу над мобом не проверяем. */
    private static final double BACKGROUND = 0.01;

    @Nullable
    private List<? extends Mob> mobs;
    private int next;
    /** Когда сделан прошлый снимок: доза нового обхода — за время между снимками. */
    private long lastSnapshot = Long.MIN_VALUE / 2;
    private double hours;

    /** Сколько успеем за бюджет. */
    public void work(ServerLevel level, List<Detonation> detonations, WorkClock clock) {
        long now = level.getGameTime();
        if (mobs == null) {
            if (now - lastSnapshot < PERIOD || !enabled(detonations) || !clock.canStart()) return;
            long t0 = clock.begin();
            // снимок, а не живая карта: моб может умереть от дозы, и лут добавится в карту сущностей
            mobs = level.getEntities(EntityTypeTest.forClass(Mob.class), RadiationTicker::affectsMob);
            // после загрузки мира (или долгого простоя без осадков) — за один период, а не за всё время
            hours = Math.min(now - lastSnapshot, 2L * PERIOD) / 1000.0;
            lastSnapshot = now;
            next = 0;
            clock.end(t0);
        }
        while (next < mobs.size() && clock.canStart()) {
            long t0 = clock.begin();
            expose(level, detonations, mobs.get(next++), now);
            clock.end(t0);
        }
        if (next >= mobs.size()) mobs = null;
    }

    private void expose(ServerLevel level, List<Detonation> detonations, Mob mob, long now) {
        // снимок старше тика: чанк моба мог уйти из полной загрузки, а крыша читается блоками
        if (!mob.isAlive() || !Terrain.ready(level, mob.blockPosition())) return;
        double field = 0;
        for (Detonation d : detonations) {
            if (d.hasFallout()) field += d.falloutRate(mob.getX(), mob.getZ(), now - d.gameTime());
        }
        if (field < BACKGROUND) return;
        double rate = field * RadiationTicker.roofShielding(level, mob.blockPosition());
        RadiationTicker.addDose(mob, (float) (rate * RadiationTicker.GY_PER_R * hours));
    }

    private static boolean enabled(List<Detonation> detonations) {
        return AirstrikeConfig.SERVER.nukeMobRadiation.get() && detonations.stream().anyMatch(Detonation::hasFallout);
    }

    /** Отбой: текущий обход прерван. */
    public void clear() {
        mobs = null;
    }
}
