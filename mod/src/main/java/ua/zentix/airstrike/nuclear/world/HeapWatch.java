package ua.zentix.airstrike.nuclear.world;

import com.sun.management.GarbageCollectionNotificationInfo;

import javax.management.Notification;
import javax.management.NotificationEmitter;
import javax.management.openmbean.CompositeData;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.lang.management.MemoryUsage;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Живой объём кучи для руин заранее ({@link NuclearPrep}): старое поколение после сборки, которая его освободила.
 * Старое поколение — пулы кучи с порогом использования ({@link MemoryPoolMXBean#isUsageThresholdSupported}): «G1 Old
 * Gen», «ZGC Old Generation», у сборщиков с одним поколением — вся куча; молодые пулы порога не имеют (JDK 21). Замер —
 * из уведомления сборщика: сборка, после которой старое поколение меньше, чем до неё (полная, смешанная, большой цикл
 * ZGC), — после неё в нём живое и немного несобранного. После малой сборки старое поколение только растёт продвижением,
 * а молодой пул «после сборки» у ZGC — занятое в конце цикла вместе с выделенным за цикл: в игре Артёма 01.10.2026
 * 1,2–1,4 ГБ при живых 56–86 МБ, и сумма пулов показывала 9,3–10,1 ГБ при живых 6,4–6,8 (лог сборщика).
 * Один на JVM: память у неё одна на все миры.
 */
final class HeapWatch {
    private static final HeapWatch INSTANCE = new HeapWatch();

    private final Set<String> oldPools = new HashSet<>();
    /** Последний замер, байты; −1 — замера ещё не было. Пишет поток уведомлений JMX, читает поток сервера. */
    private volatile long live = -1;
    /** Номер замера: пишется после {@link #live}, поэтому прочитанный номер не новее значения. */
    private volatile long measured;

    private HeapWatch() {
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            if (pool.getType() == MemoryType.HEAP && pool.isUsageThresholdSupported()) oldPools.add(pool.getName());
        }
        if (oldPools.isEmpty()) return;
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            if (gc instanceof NotificationEmitter emitter && collects(gc)) emitter.addNotificationListener(this::onGc, null, null);
        }
    }

    static HeapWatch get() {
        return INSTANCE;
    }

    /**
     * Сборка, после которой пулы знают объём: молодая, смешанная, полная, цикл ZGC или Shenandoah. Не паузы внутри
     * цикла: у G1 в JDK 21 «G1 Concurrent GC» — это Remark и Cleanup, у ZGC и Shenandoah «… Pauses» повторяют «… Cycles».
     */
    private static boolean collects(GarbageCollectorMXBean gc) {
        String name = gc.getName();
        return !name.contains("Concurrent") && !name.endsWith("Pauses");
    }

    private void onGc(Notification n, Object handback) {
        if (!GarbageCollectionNotificationInfo.GARBAGE_COLLECTION_NOTIFICATION.equals(n.getType())) return;
        var info = GarbageCollectionNotificationInfo.from((CompositeData) n.getUserData()).getGcInfo();
        long after = freedOld(info.getMemoryUsageBeforeGc(), info.getMemoryUsageAfterGc(), oldPools);
        if (after < 0) return;
        live = after;
        measured++;
    }

    /**
     * Старое поколение после сборки, если она его освободила (после меньше, чем до, хоть у одного пула), иначе −1.
     */
    static long freedOld(Map<String, MemoryUsage> before, Map<String, MemoryUsage> after, Set<String> oldPools) {
        long sum = 0;
        boolean freed = false;
        for (String pool : oldPools) {
            MemoryUsage b = before.get(pool), a = after.get(pool);
            if (b == null || a == null) return -1;
            sum += a.getUsed();
            freed |= a.getUsed() < b.getUsed();
        }
        return freed ? sum : -1;
    }

    /** Есть ли замеры: без старого поколения у пулов (чужая JVM) — нет, и руины заранее по куче не останавливаются. */
    boolean available() {
        return !oldPools.isEmpty();
    }

    /** Номер последнего замера (0 — замеров не было). */
    long measured() {
        return measured;
    }

    /** Живой объём по последнему замеру, байты; −1 — замера ещё не было. */
    long live() {
        return live;
    }

    /** Подряд ли замеры выше предела: {@code strikes} — сколько их было до этого, возвращает новое число. */
    static int strikes(int strikes, long live, long limit) {
        return live > limit ? strikes + 1 : 0;
    }
}
