package ua.zentix.airstrike.entity;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import ua.zentix.airstrike.strike.WeaponType;

/**
 * Пусковая установка: пакет оружия ({@link LauncherRack}) на опоре ({@link LauncherMount}) с курсом и очередью пусков по
 * ячейкам. Мобильная — прицеп, который ставится у стреляющего ({@link LauncherEntity}); стационарная — блок, который
 * поставил игрок ({@code launcher.FixedLauncherBlockEntity}). Пуск ставит снаряд в ячейку любой из них одинаково.
 */
public interface Launcher {
    /** Повернуть пакет можно, если новый курс отличается больше чем на столько, °. */
    float TURN_THRESHOLD = 15;

    WeaponType weapon();

    /** Место пусковой: у прицепа — его центр на земле, у блока — середина его низа. */
    Vec3 position();

    /** Курс пакета, °. */
    float yaw();

    LauncherMount mount();

    /** Тик, когда пакет начал подниматься на угол возвышения: поставлен или довёрнут. */
    long deployedAt();

    /** Очередь пусков (только сервер). */
    LaunchQueue queue();

    /**
     * Довернуть пакет на курс {@code yaw} (РСЗО, барражирующие, стационарная — на любую цель), если нужно и можно
     * ({@link #turnedYaw}): пакет опускается, поворачивается и поднимается снова, пуски ждут подъёма.
     */
    void turnTo(float yaw, long now);

    default LauncherRack rack() {
        return LauncherRack.of(weapon());
    }

    /** Угол возвышения направляющей (паспорт): шахеды 15°, катапульта барражирующих 20°, ракеты 40°, трубы РСЗО 50°. */
    default float elevation() {
        return rack().elevation();
    }

    /** Центр снаряда на направляющей {@code slot} при полном подъёме пакета. */
    default Vec3 railPoint(int slot) {
        return mount().railPoint(position(), yaw(), weapon(), slot);
    }

    /** Сколько тиков ещё поднимается пакет (0 — поднят). */
    default int raisingTicks(long now) {
        return (int) Math.max(0, deployedAt() + LauncherEntity.DEPLOY_TICKS - now);
    }

    /** Текущий угол пакета с учётом подъёма (клиент рисует по нему). */
    default float deployedElevation(long gameTime, float partialTick) {
        float t = Mth.clamp((gameTime - deployedAt() + partialTick) / LauncherEntity.DEPLOY_TICKS, 0, 1);
        return elevation() * t * t * (3 - 2 * t);
    }

    /**
     * Занять ячейку под пуск ({@link LaunchQueue#reserve}): не раньше, чем пакет поднимется.
     *
     * @return [ячейка, через сколько тиков поджиг]
     */
    default int[] reserve(long now, int minReady, int busyTicks) {
        return queue().reserve(rack(), now, deployedAt() + LauncherEntity.DEPLOY_TICKS + 5, minReady, busyTicks);
    }

    /** Курс пакета после {@link #turnTo}{@code (yaw, now)}: прежний, если доворачивать не нужно или установка не молчит. */
    default float turnedYaw(float yaw, long now) {
        return Math.abs(Mth.wrapDegrees(yaw - yaw())) <= TURN_THRESHOLD || !queue().silent(now) ? yaw() : yaw;
    }
}
