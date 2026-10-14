package ua.zentix.incoming.scenario.trailer;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import ua.zentix.incoming.Incoming;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Миры сервера повтора уходят без {@code LevelEvent.Unload}: Flashback ({@code ReplayServer.stopServer}) закрывает их
 * и очищает список миров сам, до {@code MinecraftServer.stopServer}, а событие NeoForge шлёт только цикл ванили по
 * {@code getAllLevels()} в конце {@code stopServer} — он видит пустой список (так же и смена измерения в повторе:
 * {@code ReplayServer.closeLevel} шлёт только событие Fabric). Всё, что держит свои данные по миру сервера до этого
 * события (NeoForge — игроки-заглушки {@code FakePlayerFactory}, моды — свои карты по миру), держит так каждый закрытый
 * повтор целиком, с сервером и чанками, а вывод трейлера открывает десятки повторов в одном клиенте (netcup: живая
 * куча +0,6–0,7 ГБ на повтор, OutOfMemoryError при открытии тринадцатого).
 * <p>
 * Поэтому миры сервера, которые встали ({@code LevelEvent.Load}) или были у сервера к остановке
 * ({@code ServerStoppingEvent} идёт до {@code stopServer}), но не получили {@code Unload} к её концу, получают его в
 * {@code ServerStoppedEvent} — первым из его обработчиков: у ванили {@code Unload} идёт до него. Мир к этому времени уже
 * закрыт ({@code ServerLevel.close}), у ванили событие — до закрытия; обработчикам выгрузки нужен сам мир, а не его чанки.
 * Обычный сервер (запись) свои миры выгружает сам — для него здесь ничего не остаётся.
 */
final class ReplayLevelUnload {
    /** Миры сервера, которые встали и ещё не получили {@code Unload}. */
    private static final Set<ServerLevel> open = ConcurrentHashMap.newKeySet();

    private ReplayLevelUnload() {
    }

    static void register() {
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, LevelEvent.Load.class, e -> {
            if (e.getLevel() instanceof ServerLevel l) open.add(l);
        });
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, LevelEvent.Unload.class, e -> {
            if (e.getLevel() instanceof ServerLevel l) open.remove(l);
        });
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, ServerStoppingEvent.class, e -> e.getServer().getAllLevels().forEach(open::add));
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, ServerStoppedEvent.class, e -> unloadLeft(e.getServer()));
    }

    private static void unloadLeft(MinecraftServer server) {
        List<ServerLevel> left = open.stream().filter(l -> l.getServer() == server).toList();
        if (left.isEmpty()) return;
        int failed = 0;
        for (ServerLevel l : left) {
            open.remove(l);
            try {
                NeoForge.EVENT_BUS.post(new LevelEvent.Unload(l));
            } catch (RuntimeException ex) {
                failed++;
                Incoming.LOG.warn("SCENARIO film: LevelEvent.Unload мира {} сервера повтора упал", l.dimension().location(), ex);
            }
        }
        Incoming.LOG.info("SCENARIO film: сервер {} остановлен, миров без LevelEvent.Unload — {} ({}): событие отдано{}", server.getClass().getSimpleName(),
                left.size(), left.stream().map(l -> l.dimension().location().toString()).toList(), failed > 0 ? ", с ошибкой у " + failed : "");
    }
}
