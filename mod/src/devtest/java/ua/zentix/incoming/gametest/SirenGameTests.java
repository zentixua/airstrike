package ua.zentix.incoming.gametest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.entity.CruiseMissileEntity;
import ua.zentix.incoming.entity.StrikeProjectile;
import ua.zentix.incoming.registry.ModEntities;
import ua.zentix.incoming.strike.StrikeWorld;
import ua.zentix.incoming.strike.WeaponType;
import ua.zentix.incoming.target.Target;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Тревога у цели от снаряда ({@code StrikeProjectile.checkSiren}): поле {@code sirenLead} читается отражением из
 * другого пакета (как его читает журнал звуков мира {@code scenario.trailer.WorldSounds}) и переход «вооружена →
 * сработала» виден подряд идущими тиками до удара — по нему журнал пишет строку триггера, а проверка дубля и монтаж
 * узнают, что камера слышала сирену.
 */
@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
public final class SirenGameTests {

    @GameTest(template = "runway", timeoutTicks = 400, batch = "siren", skyAccess = true)
    public static void sirenLeadTransitionIsVisible(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        Field leadField;
        try {
            leadField = StrikeProjectile.class.getDeclaredField("sirenLead");
            leadField.setAccessible(true);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("нет поля sirenLead", e);
        }
        int lead = WeaponType.MISSILE.spec().sirenLead();
        Vec3 aim = top(h, new BlockPos(16, 3, 200));
        Vec3 start = Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(16, 5, 1)));
        CruiseMissileEntity missile = ModEntities.CRUISE_MISSILE.get().create(level);
        missile.launch(start, new Target.Point(aim), aim, null);
        missile.armSiren(lead);
        level.addFreshEntity(missile);
        UUID id = missile.getUUID();

        // как журнал WorldSounds.triggers: карта по UUID, переход ловится на следующем тике после срабатывания
        Map<UUID, Integer> seen = new HashMap<>();
        boolean[] armed = {false};
        long[] transitionAt = {-1};
        long[] gameTick = {0};
        h.onEachTick(() -> {
            gameTick[0]++;
            for (StrikeProjectile p : StrikeWorld.projectiles(level)) {
                int v;
                try {
                    v = leadField.getInt(p);
                } catch (IllegalAccessException e) {
                    throw new IllegalStateException("поле sirenLead не читается", e);
                }
                Integer was = seen.put(p.getUUID(), v);
                if (v >= 0) armed[0] = true;
                if (was != null && was >= 0 && v < 0) transitionAt[0] = gameTick[0];
            }
        });
        h.succeedWhen(() -> {
            h.assertTrue(armed[0], "сирена не вооружена: поле sirenLead отражением не видно");
            h.assertTrue(transitionAt[0] >= 0, "переход сирены не пойман за полёт (полёт " + gameTick[0] + " тиков)");
            StrikeProjectile p = StrikeWorld.projectiles(level).stream()
                    .filter(f -> f.getUUID().equals(id)).findFirst().orElse(null);
            h.assertTrue(p == null || p.isRemoved() || gameTick[0] - transitionAt[0] >= 0,
                    "переход после конца полёта");
        });
    }

    private static Vec3 top(GameTestHelper h, BlockPos pos) {
        BlockPos at = h.absolutePos(pos);
        return new Vec3(at.getX() + 0.5, at.getY() + 1, at.getZ() + 0.5);
    }
}
