package ua.zentix.airstrike.gametest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.jetbrains.annotations.Nullable;
import ua.zentix.airstrike.Airstrike;
import ua.zentix.airstrike.entity.BomberEntity;
import ua.zentix.airstrike.entity.CruiseMissileEntity;
import ua.zentix.airstrike.entity.LauncherEntity;
import ua.zentix.airstrike.entity.StrikeProjectile;
import ua.zentix.airstrike.guidance.Route;
import ua.zentix.airstrike.registry.ModEntities;
import ua.zentix.airstrike.strike.Loadout;
import ua.zentix.airstrike.strike.SalvoData;
import ua.zentix.airstrike.strike.ServerActions;
import ua.zentix.airstrike.strike.VirtualFlights;
import ua.zentix.airstrike.strike.WeaponType;
import ua.zentix.airstrike.strike.Waypoints;
import ua.zentix.airstrike.target.Target;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/** Свой отбой ({@link ServerActions#recall}): только свои удары, самоликвидацией; ядерный и неуправляемые — не отзываются. */
@GameTestHolder(Airstrike.MOD_ID)
@PrefixGameTestTemplate(false)
public final class RecallGameTests {
    private static final BlockPos CENTER = new BlockPos(32, 11, 32);

    private RecallGameTests() {}

    /**
     * Отбой игрока A: его ракета в полёте самоликвидируется, B-2 до сброса отворачивает, ракета на направляющей снята
     * вместе с его пусковой, ракета вне мира пропадает, его залп отменён. Ракета с ядерной БЧ того же игрока, удары
     * игрока B и его пусковая — как были; ракете РСЗО и сброшенной бомбе отзываться нечем.
     */
    @GameTest(template = "range", timeoutTicks = 20, batch = "recall", skyAccess = true)
    public static void recallStopsOnlyOwnStrikes(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        Vec3 c = Vec3.atCenterOf(h.absolutePos(CENTER));
        Vec3 far = c.add(0, 0, 3000);
        CruiseMissileEntity mine = flying(level, c.add(0, 40, 0), far, a, null);
        CruiseMissileEntity theirs = flying(level, c.add(12, 40, 0), far, b, null);
        CruiseMissileEntity nuclear = flying(level, c.add(-12, 40, 0), far, a, new Loadout.Nuke(15, true, true));
        Vec3 rail = c.add(0, 1, 0);
        CruiseMissileEntity onRail = ModEntities.CRUISE_MISSILE.get().create(level);
        onRail.placeOnLauncher(rail, 0, 40, 1000, 0, new Target.Point(far), far, a);
        onRail.setRoute(Route.direct());
        level.addFreshEntity(onRail);
        LauncherEntity launcher = LauncherEntity.create(level, rail.add(6, -1, 0), 0, WeaponType.MISSILE, a, false);
        LauncherEntity otherLauncher = LauncherEntity.create(level, rail.add(-6, -1, 0), 0, WeaponType.MISSILE, b, false);
        level.addFreshEntity(launcher);
        level.addFreshEntity(otherLauncher);
        CruiseMissileEntity outside = ModEntities.CRUISE_MISSILE.get().create(level);
        outside.launch(far.add(0, 60, 2000), new Target.Point(far), far, a);
        outside.setRoute(Route.direct());
        VirtualFlights.launch(level, outside);
        BomberEntity bomber = ModEntities.BOMBER.get().create(level);
        bomber.launch(c.add(0, 0, -20), far, null, a);
        level.addFreshEntity(bomber);
        SalvoData.start(level, WeaponType.DRONE, 5, 10, new Target.Point(far), far, 0, a, Loadout.Nuke.DEFAULT, Waypoints.NONE, null, false);
        SalvoData.start(level, WeaponType.DRONE, 5, 10, new Target.Point(far), far, 0, b, Loadout.Nuke.DEFAULT, Waypoints.NONE, null, false);
        StrikeGameTests.afterTest(h, () -> {
            SalvoData.get(level).cancel(level, a, List.of());
            SalvoData.get(level).cancel(level, b, List.of());
            VirtualFlights.get(level).clear(level, p -> a.equals(p.ownerId()) || b.equals(p.ownerId()));
            for (var e : List.of(theirs, nuclear, bomber, otherLauncher, launcher, onRail, mine)) e.discard();
        });
        List<String> lines = new CopyOnWriteArrayList<>();
        StrikeGameTests.afterTest(h, NuclearGameTests.watchLog(line -> {
            if (line.startsWith("Отбой своих")) lines.add(line);
        }));

        ServerActions.Recalled r = ServerActions.recall(level.getServer(), a, "проверка");
        h.assertTrue(mine.isRemoved(), "своя ракета в полёте не самоликвидировалась");
        h.assertTrue(!bomber.isRemoved() && bomber.hasReleased(), "B-2 до сброса не отвернул: убран " + bomber.isRemoved());
        h.assertTrue(onRail.isRemoved() && launcher.isRemoved(), "своя ракета на направляющей или пусковая остались");
        h.assertTrue(outside.isRemoved() && !VirtualFlights.get(level).flights().contains(outside), "своя ракета вне мира осталась");
        h.assertTrue(SalvoData.get(level).remaining(a) == 0, "свой залп не отменён");
        h.assertFalse(nuclear.isRemoved(), "свой отбой отозвал ядерный удар");
        h.assertTrue(!theirs.isRemoved() && !otherLauncher.isRemoved() && SalvoData.get(level).remaining(b) == 5, "свой отбой задел чужие удары");
        h.assertTrue(r.projectiles() == 4 && r.salvos() == 1, "итог: " + r);
        h.assertValueEqual(lines, List.of("Отбой своих — проверка: самоликвидировались 1, отвернули 1, сняты с пусковых 1 (пусковых 1), "
                + "вне мира 1, залпов 1"), "строка в лог");

        // неуправляемым свернуть нечем: летят дальше
        for (StrikeProjectile p : List.of(ModEntities.ROCKET.get().create(level), ModEntities.BUNKER_BUSTER.get().create(level))) {
            h.assertTrue(!p.recallable() && !p.recall(level) && !p.isRemoved(), "отбой остановил " + p.getType().getDescriptionId());
        }
        h.succeed();
    }

    /** Крылатая ракета в полёте в мире. */
    private static CruiseMissileEntity flying(ServerLevel level, Vec3 at, Vec3 target, UUID owner, @Nullable Loadout.Nuke warhead) {
        CruiseMissileEntity m = ModEntities.CRUISE_MISSILE.get().create(level);
        m.launch(at, new Target.Point(target), target, owner);
        m.setRoute(Route.direct());
        m.setNuclear(warhead);
        level.addFreshEntity(m);
        return m;
    }
}
