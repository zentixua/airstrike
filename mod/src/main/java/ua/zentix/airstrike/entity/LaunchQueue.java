package ua.zentix.airstrike.entity;

/**
 * Очередь пусков с пакета пусковой ({@link Launcher}): когда освободится каждая ячейка и когда назначен последний
 * поджиг. Только сервер и не сохраняется: снаряды, уже стоящие на направляющих, сохраняются сами и после загрузки
 * стартуют в свой тик, а новые встают в очередь заново.
 */
public final class LaunchQueue {
    /** После последнего поджига пакет столько тиков не молчит: доворачивать его нельзя ({@link Launcher#turnedYaw}). */
    static final int SETTLE_TICKS = 20;

    private long[] slotFreeAt = new long[0];
    private long lastStart = Long.MIN_VALUE / 2;

    /**
     * Занять ячейку под пуск.
     *
     * @param raised    когда пакет поднят и готов к первому пуску
     * @param minReady  через сколько тиков после приказа поджиг, не раньше
     * @param busyTicks сколько тиков снаряд простоит в ячейке после готовности (поджиг и сход)
     * @return [ячейка, через сколько тиков поджиг] — не раньше, чем пакет поднимется и освободится ячейка
     */
    public int[] reserve(LauncherRack rack, long now, long raised, int minReady, int busyTicks) {
        if (slotFreeAt.length != rack.slots()) slotFreeAt = new long[rack.slots()];
        int best = 0;
        for (int i = 1; i < slotFreeAt.length; i++) {
            if (slotFreeAt[i] < slotFreeAt[best]) best = i;
        }
        long start = Math.max(now + minReady, Math.max(slotFreeAt[best], raised));
        // пуски, заказанные, пока пакет поднимался, не срываются разом: очередь с интервалом
        start = Math.max(start, lastStart + rack.spacing());
        lastStart = start;
        slotFreeAt[best] = start + busyTicks;
        return new int[]{best, (int) (start - now)};
    }

    /** Пакет молчит: последний поджиг был не меньше {@link #SETTLE_TICKS} тиков назад (и впереди поджигов нет). */
    public boolean silent(long now) {
        return now >= lastStart + SETTLE_TICKS;
    }
}
