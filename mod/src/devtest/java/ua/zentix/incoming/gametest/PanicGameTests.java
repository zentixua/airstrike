package ua.zentix.incoming.gametest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.nuclear.MobPanic;
import ua.zentix.incoming.nuclear.NuclearEvents;
import ua.zentix.incoming.nuclear.NuclearStrikes;
import ua.zentix.incoming.nuclear.NuclearWarhead;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Паника мобов после ядерного подрыва: бегут от эпицентра, потом успокаиваются. */
@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
public final class PanicGameTests {
    private PanicGameTests() {}

    /** Давление волны у мобов: бросок слабый, урона нет (урон — от 0,7 psi). */
    private static final double PSI = 0.45;

    /**
     * Подрыв 0,1 кт высоко над землёй к западу от площадки (видно над стеной из барьеров), масштаб — такой, что у мобов
     * {@link #PSI}. Вспышку (свет по одной сущности, как в {@code PulseJob}) видят коровы, кадавр и житель — все
     * паникуют; волна продлевает панику. За 10 с каждый уходит на восток, дальше от эпицентра, а когда срок вышел, цель
     * бегства у всех снята.
     */
    @GameTest(template = "range", timeoutTicks = 1400, batch = "nuke_panic", skyAccess = true)
    public static void mobsFleeTheBlastAndCalmDown(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        List<Mob> mobs = new ArrayList<>();
        mobs.add(h.spawn(EntityType.COW, new BlockPos(30, 12, 26)));
        mobs.add(h.spawn(EntityType.COW, new BlockPos(30, 12, 32)));
        mobs.add(h.spawn(EntityType.COW, new BlockPos(30, 12, 38)));
        mobs.add(h.spawn(EntityType.HUSK, new BlockPos(26, 12, 32)));
        mobs.add(h.spawn(EntityType.VILLAGER, new BlockPos(34, 12, 30)));
        for (Mob m : mobs) m.setPersistenceRequired();
        BlockPos g = h.absolutePos(NuclearGameTests.CENTER.west(60));
        Vec3 burst = new Vec3(g.getX() + 0.5, g.getY() + 120, g.getZ() + 0.5);
        Vec3 probe = h.absoluteVec(new Vec3(30.5, 12, 32.5));
        // масштаб, при котором у мобов PSI: дальше в метрах — меньше давление
        float lo = 0.001f, hi = 1;
        for (int i = 0; i < 40; i++) {
            float mid = (float) Math.sqrt(lo * hi);
            if (make(level, burst, g.getY(), mid, 0).psi(probe) > PSI) hi = mid;
            else lo = mid;
        }
        Detonation d = make(level, burst, g.getY(), (float) Math.sqrt(lo * hi), NuclearGameTests.IDS.incrementAndGet());
        for (Mob m : mobs) {
            double psi = d.psi(m.position());
            h.assertTrue(psi > 0.3 && psi < 0.7, String.format(Locale.ROOT, "давление у моба %.2f psi", psi));
        }
        // вспышка: свет по каждому, как у импульса
        for (Mob m : mobs) NuclearWarhead.expose(level, d, m, null);
        for (Mob m : mobs) h.assertTrue(MobPanic.panicking(m), m.getType().toShortString() + " не испугался вспышки");
        h.assertTrue(mobs.stream().allMatch(Mob::isAlive), "вспышка убила");
        double[] start = new double[mobs.size()];
        for (int i = 0; i < mobs.size(); i++) start[i] = away(mobs.get(i), burst);
        NuclearEvents.get(level).add(d);
        long t0 = level.getGameTime();
        h.startSequence()
                .thenExecuteAfter(200, () -> {
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < mobs.size(); i++) sb.append(String.format(Locale.ROOT, " %s +%.1f", mobs.get(i).getType().toShortString(), away(mobs.get(i), burst) - start[i]));
                    Incoming.LOG.info("GameTest panic: за 10 с ушли от эпицентра:{}", sb);
                    for (int i = 0; i < mobs.size(); i++) {
                        Mob m = mobs.get(i);
                        double gone = away(m, burst) - start[i];
                        h.assertTrue(gone >= 4, String.format(Locale.ROOT, "%s ушёл от эпицентра на %.1f блока", m.getType().toShortString(), gone));
                        h.assertTrue(MobPanic.panicking(m), m.getType().toShortString() + " уже успокоился через 10 с");
                    }
                })
                .thenWaitUntil(() -> {
                    for (Mob m : mobs) {
                        h.assertFalse(MobPanic.panicking(m), m.getType().toShortString() + " ещё паникует через " + (level.getGameTime() - t0) + " тиков");
                        h.assertFalse(MobPanic.fleeing(m), m.getType().toShortString() + " ещё бежит");
                    }
                })
                .thenExecute(() -> {
                    // волна дошла и продлила панику: успокоились не раньше её прихода и срока волны
                    long calm = level.getGameTime() - t0;
                    Incoming.LOG.info("GameTest panic: успокоились через {} тиков", calm);
                    h.assertTrue(calm >= MobPanic.WAVE, "успокоились через " + calm + " тиков — раньше срока волны");
                    for (Mob m : mobs) m.discard();
                    NuclearStrikes.clear(level);
                })
                .thenSucceed();
    }

    private static Detonation make(ServerLevel level, Vec3 burst, double groundY, float scale, int id) {
        return new Detonation(id, burst, groundY, 0.1, false, level.getGameTime(), 0, 0, 20_000, 7, scale, false,
                Detonation.OLD_WAVE);
    }

    /** Насколько моб дальше от эпицентра по горизонтали. */
    private static double away(Mob m, Vec3 burst) {
        return Math.hypot(m.getX() - burst.x, m.getZ() - burst.z);
    }
}
