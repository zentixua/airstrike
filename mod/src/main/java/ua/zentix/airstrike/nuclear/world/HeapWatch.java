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
 * Живой объём кучи для руин заранее ({@link NuclearPrep}): старое поколение после сборки, которая видит его живой
 * объём. Старое поколение — пулы кучи с порогом использования ({@link MemoryPoolMXBean#isUsageThresholdSupported}):
 * «G1 Old Gen», «ZGC Old Generation», у сборщиков с одним поколением — вся куча; молодые пулы порога не имеют (JDK 21).
 * Замер — из уведомления сборщика ({@link #reading}): полная или большая сборка, сборка, после которой старое поколение
 * меньше, чем до неё (смешанная у G1), — после них в нём живое и немного несобранного; и любая сборка, после которой
 * старое поколение выше предела. После малой сборки ниже предела замера нет: старое поколение растёт продвижением, а
 * молодой пул «после сборки» у ZGC — занятое в конце цикла вместе с выделенным за цикл: в игре Артёма 01.10.2026
 * 1,2–1,4 ГБ при живых 56–86 МБ, и сумма пулов показывала 9,3–10,1 ГБ при живых 6,4–6,8 (лог сборщика).
 * Без старого поколения у пулов (чужая JVM) — прежний замер: занято после последней сборки по всем пулам кучи
 * ({@link #fallbackLive}). Один на JVM: память у неё одна на все миры.
 */
final class HeapWatch {
    private static final HeapWatch INSTANCE = new HeapWatch();
    /** Доля кучи (живой объём), выше которой руины заранее больше не строятся. */
    static final double LIMIT = 0.75;
    /**
     * Сборщики, которые собирают всё старое поколение (полная сборка, большой цикл, цикл сборщика с одним поколением):
     * после них замер, даже если старое поколение выросло (полная сборка G1 переносит туда живое из молодого).
     */
    static final Set<String> MAJOR = Set.of("G1 Old Generation", "PS MarkSweep", "MarkSweepCompact", "ZGC Major Cycles", "ZGC Cycles", "Shenandoah Cycles");

    private final Set<String> oldPools = new HashSet<>();
    private final long limit = (long) (Runtime.getRuntime().maxMemory() * LIMIT);
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
        var gc = GarbageCollectionNotificationInfo.from((CompositeData) n.getUserData());
        var info = gc.getGcInfo();
        long after = reading(gc.getGcName(), info.getMemoryUsageBeforeGc(), info.getMemoryUsageAfterGc(), oldPools, limit);
        if (after < 0) return;
        live = after;
        measured++;
    }

    /**
     * Старое поколение после сборки {@code gc}, если по ней виден его живой объём, иначе −1: сборка из {@link #MAJOR};
     * сборка, после которой старое поколение меньше, чем до неё, хоть у одного пула; старое поколение после неё выше
     * {@code limit} — там уже не важно, сколько в нём несобранного: без полной сборки замера не было бы вовсе, и куча
     * дошла бы до нехватки.
     */
    static long reading(String gc, Map<String, MemoryUsage> before, Map<String, MemoryUsage> after, Set<String> oldPools, long limit) {
        long sum = 0;
        boolean freed = false;
        for (String pool : oldPools) {
            MemoryUsage b = before.get(pool), a = after.get(pool);
            if (b == null || a == null) return -1;
            sum += a.getUsed();
            freed |= a.getUsed() < b.getUsed();
        }
        return freed || MAJOR.contains(gc) || sum > limit ? sum : -1;
    }

    /** Замер по старому поколению: у пулов есть старое поколение (иначе — {@link #fallbackLive}). */
    boolean available() {
        return !oldPools.isEmpty();
    }

    /** Предел живого объёма ({@link #LIMIT} кучи), байты. */
    long limit() {
        return limit;
    }

    /** Номер последнего замера (0 — замеров не было); без старого поколения у пулов — число сборок. */
    long measured() {
        if (available()) return measured;
        long collections = 0;
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            if (collects(gc)) collections += Math.max(0, gc.getCollectionCount());
        }
        return collections;
    }

    /** Живой объём по последнему замеру, байты; −1 — замера ещё не было. */
    long live() {
        return available() ? live : fallbackLive();
    }

    /**
     * Без старого поколения у пулов: занято после последней сборки по пулам кучи, у пулов без этих данных — занято
     * сейчас (с мусором, то есть с запасом).
     */
    private static long fallbackLive() {
        long sum = 0;
        boolean any = false;
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            if (pool.getType() != MemoryType.HEAP) continue;
            MemoryUsage after = pool.getCollectionUsage();
            if (after == null) continue;
            sum += after.getUsed();
            any = true;
        }
        if (any) return sum;
        Runtime rt = Runtime.getRuntime();
        return rt.totalMemory() - rt.freeMemory();
    }

    /** Подряд ли замеры выше предела: {@code strikes} — сколько их было до этого, возвращает новое число. */
    static int strikes(int strikes, long live, long limit) {
        return live > limit ? strikes + 1 : 0;
    }
}
