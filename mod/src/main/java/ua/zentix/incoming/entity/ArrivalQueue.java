package ua.zentix.incoming.entity;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;

/**
 * Места снаряда от сервера у клиента — по одному на тик клиента. Сервер шлёт место каждый свой тик, а пакеты тика сервера
 * приходят то в один тик клиента, то в соседний (сервер не успел и догоняет; повтор Flashback хранит их так же, по тикам
 * клиента): с местом «последний пакет — за тик» снаряд то стоит тик, то прыгает на два шага, и камера, которая его
 * ведёт, дёргается вместе с ним (трейлер: шахед «подтормаживает» в каждом плане погони).
 * <p>
 * Очередь отдаёт по месту на тик. Пусто — снаряд идёт прошлым шагом (тик в долг, не больше {@link #MAX_DEBT} без
 * пакетов: дальше стоит, как раньше), а места, пришедшие потом за тики в долг, пропускаются: их уже показали прогнозом. В очереди
 * больше {@link #MAX_QUEUE} — старые пропускаются: снаряд не отстаёт от сервера больше чем на тик. Место дальше
 * {@link #JUMP} от прошлого (возврат в мир, телепорт) — сразу, очередь и долг снимаются.
 */
public final class ArrivalQueue {
    static final int MAX_QUEUE = 2, MAX_DEBT = 2;
    static final double JUMP = 64;

    /** Место и поворот: x, y, z, курс, тангаж. */
    public record Sample(double x, double y, double z, float yRot, float xRot) {}

    private final ArrayDeque<Sample> queue = new ArrayDeque<>();
    private int debt;
    /** Шаг последнего тика, блоков: им снаряд идёт в долг. */
    private double stepX, stepY, stepZ;
    @Nullable
    private Sample last;

    /** Пакет места пришёл. */
    public void add(Sample s) {
        if (last != null && Math.abs(s.x() - last.x()) + Math.abs(s.y() - last.y()) + Math.abs(s.z() - last.z()) > JUMP) {
            queue.clear();
            debt = 0;
            stepX = stepY = stepZ = 0;
        } else if (debt > 0) {
            // это место уже показано прогнозом
            debt--;
            last = s;
            return;
        }
        queue.add(s);
        while (queue.size() > MAX_QUEUE) queue.poll();
    }

    /**
     * Куда снаряду в этот тик клиента из места {@code x, y, z} (поворот {@code yRot, xRot}): место из очереди, прошлый шаг
     * в долг (поворот прежний) или null — стоять.
     */
    @Nullable
    public Sample next(double x, double y, double z, float yRot, float xRot) {
        Sample s = queue.poll();
        if (s != null) {
            stepX = s.x() - x;
            stepY = s.y() - y;
            stepZ = s.z() - z;
            last = s;
            return s;
        }
        if (last == null || debt >= MAX_DEBT) return null;
        debt++;
        return new Sample(x + stepX, y + stepY, z + stepZ, yRot, xRot);
    }

    /** Последнее пришедшее место (куда снаряд идёт); null — ничего не приходило. */
    @Nullable
    public Sample latest() {
        return queue.isEmpty() ? last : queue.peekLast();
    }
}
