package ua.zentix.airstrike.entity;

import net.minecraft.world.phys.Vec3;
import ua.zentix.airstrike.strike.WeaponType;
import ua.zentix.airstrike.util.Local;

/**
 * На чём стоит пакет пусковой ({@link Launcher}): где его ось качания относительно места пусковой. Пакет одного оружия
 * один и тот же ({@link LauncherRack}), меняется только опора — от неё считаются ячейки, сектор пуска и картинка.
 */
public enum LauncherMount {
    /** Прицеп мобильной пусковой: ось на 1.6 над землёй и 2.2 позади центра прицепа. */
    TRAILER(1.6, 2.2),
    /** Стационарная пусковая: ось на поворотном круге над блоком, над серединой его верха; место — середина низа блока. */
    PAD(1.25, 0);

    /** Ось качания выше места пусковой и позади него по курсу, блоков. */
    public final double up, back;

    LauncherMount(double up, double back) {
        this.up = up;
        this.back = back;
    }

    /** Ось качания пакета у пусковой в {@code pos} с курсом {@code yaw}. */
    public Vec3 pivot(Vec3 pos, float yaw) {
        return Local.at(pos, yaw, 0, 0, up, -back);
    }

    /** Центр снаряда на направляющей {@code slot} пакета {@code weapon} при полном подъёме, у пусковой в {@code pos} с курсом {@code yaw}. */
    public Vec3 railPoint(Vec3 pos, float yaw, WeaponType weapon, int slot) {
        LauncherRack rack = LauncherRack.of(weapon);
        double[] at = rack.slotOffset(slot);
        return Local.at(pivot(pos, yaw), yaw, -rack.elevation(), at[0], at[1], at[2]);
    }
}
