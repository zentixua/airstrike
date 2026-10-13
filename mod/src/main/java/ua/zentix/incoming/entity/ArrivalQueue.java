package ua.zentix.incoming.entity;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;

/**
 * Места снаряда от сервера у клиента — по одному на тик клиента. Сервер шлёт место каждый свой тик, а пакеты тика сервера
 * приходят то в один тик клиента, то в соседний (сервер не успел и догоняет; повтор Flashback хранит их так же, по тикам
 * клиента): с местом «последний пакет — за тик» снаряд то стоит тик, то прыгает на два шага, и камера, которая его
 * ведёт, дёргается вместе с ним (трейлер: шахед «подтормаживает» в каждом плане погони).
 * <p>
 * Очередь отдаёт по месту на тик. Пусто — снаряд идёт прогнозом от последнего пришедшего места его шагом за тик сервера
 * (тик в долг, не больше {@link #MAX_DEBT} тиков вперёд: дальше стоит, как раньше), а места, пришедшие потом за тики в
 * долг, в очередь не встают — их уже показали прогнозом, — но прогноз считается от них: снаряд не дальше
 * {@link #MAX_DEBT} шагов от сервера и при сервере медленнее 20 тиков в секунду (тогда пакетов меньше, чем тиков
 * клиента, и долг не гасится) идёт его путём, а не по прямой. В очереди больше {@link #MAX_QUEUE} — старые
 * пропускаются: снаряд не отстаёт от сервера больше чем на тик. Место дальше {@link #JUMP} от прошлого (возврат в мир,
 * телепорт) — сразу, очередь и долг снимаются.
 */
public final class ArrivalQueue {
    static final int MAX_QUEUE = 2, MAX_DEBT = 2;
    static final double JUMP = 64;

    /** Место и поворот: x, y, z, курс, тангаж. */
    public record Sample(double x, double y, double z, float yRot, float xRot) {}

    private final ArrayDeque<Sample> queue = new ArrayDeque<>();
    /** Тиков, показанных прогнозом сверх последнего пришедшего места. */
    private int debt;
    /** Последнее пришедшее место и место перед ним: шаг прогноза — между ними. */
    @Nullable
    private Sample last, before;
    /** Шаг последнего тика, блоков: прогноз, пока пришло одно место. */
    private double stepX, stepY, stepZ;

    /** Пакет места пришёл. */
    public void add(Sample s) {
        if (last != null && Math.abs(s.x() - last.x()) + Math.abs(s.y() - last.y()) + Math.abs(s.z() - last.z()) > JUMP) {
            queue.clear();
            debt = 0;
            stepX = stepY = stepZ = 0;
            last = null;
        }
        before = last;
        last = s;
        if (debt > 0) {
            // это место уже показано прогнозом
            debt--;
            return;
        }
        queue.add(s);
        while (queue.size() > MAX_QUEUE) queue.poll();
    }

    /**
     * Куда снаряду в этот тик клиента из места {@code x, y, z}: место из очереди, прогноз от последнего пришедшего места
     * (тик в долг) или null — стоять.
     */
    @Nullable
    public Sample next(double x, double y, double z, float yRot, float xRot) {
        Sample s = queue.poll();
        if (s != null) {
            // после скачка (возврат в мир) прогноз — стоять, пока не придёт второе место
            boolean jump = Math.abs(s.x() - x) + Math.abs(s.y() - y) + Math.abs(s.z() - z) > JUMP;
            stepX = jump ? 0 : s.x() - x;
            stepY = jump ? 0 : s.y() - y;
            stepZ = jump ? 0 : s.z() - z;
            return s;
        }
        if (last == null || debt >= MAX_DEBT) return null;
        debt++;
        if (before != null) {
            stepX = last.x() - before.x();
            stepY = last.y() - before.y();
            stepZ = last.z() - before.z();
        }
        return new Sample(last.x() + stepX * debt, last.y() + stepY * debt, last.z() + stepZ * debt, last.yRot(), last.xRot());
    }

    /** Последнее пришедшее место (куда снаряд идёт); null — ничего не приходило. */
    @Nullable
    public Sample latest() {
        return last;
    }
}
