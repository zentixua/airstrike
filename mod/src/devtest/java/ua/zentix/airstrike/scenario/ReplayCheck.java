package ua.zentix.airstrike.scenario;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.GenericMessageScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import ua.zentix.airstrike.Airstrike;
import ua.zentix.airstrike.client.fx.particle.FxBudget;
import ua.zentix.airstrike.client.fx.particle.FxPool;
import ua.zentix.airstrike.entity.StrikeProjectile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Повтор Flashback (сценарий {@code replay}: пуск, как в {@code launch}, с модами записи сборки pack/ и
 * {@code recordingControls.quicksave} в настройках Flashback): клиент выходит из мира, как кнопка «Сохранить и выйти»,
 * Flashback на выходе дописывает запись в zip в своей папке повторов; сценарий открывает этот повтор, переходит
 * к моменту до первого пуска и проигрывает запись до конца. Камера (свой игрок повтора — зритель) идёт сбоку от снаряда;
 * кадры {@code replay_<имя>.png}: снаряд в полёте (через {@link #FLIGHT} тиков после появления) и место, где он пропал
 * (через {@link #GONE} тиков): взрыв или уход из того, что видел записывавший игрок. Flashback — мод Fabric (через
 * Sinytra Connector), в сборке мода его нет: его методы — отражением. Строки лога: {@code SCENARIO replay
 * saved|opened|play|frame|failed}, в конце {@code SCENARIO done}; в строках play и frame — блок пола площадки сценария
 * {@code launch} ({@link #FLOOR}, гладкий камень): повтор показывает те же блоки, что были в игре.
 */
final class ReplayCheck {
    /** Столько тиков ждать файла повтора после выхода и мира повтора после открытия. */
    private static final int SAVE_WAIT = 1200, OPEN_WAIT = 2400;
    /**
     * Проигрывание — с этого места до конца записи (тиков до конца): запись кончается выходом на тике 1520 сценария
     * {@code launch}, первый пуск — на тике 230. Тиков после открытия до перехода и после перехода до пуска проигрывания.
     */
    private static final int PLAY_BACK = 1320, SEEK_AT = 20, PLAY_AT = 60;
    /** Кадры: снаряд в полёте — через столько тиков после его появления, место пропажи — после неё. */
    private static final int[] FLIGHT = {60, 160}, GONE = {4, 30};
    private static final int MAX_FRAMES = 14;
    /** Пол площадки сценария {@code launch} (fill … smooth_stone на y 199) под серединой пути к цели. */
    private static final BlockPos FLOOR = new BlockPos(0, 199, 60);

    private Class<?> flashback;
    private Object server;
    private int ticks, openedAt = -1, frames, total, playFrom, playTicks = -1;
    private Path saved;
    private boolean finished;
    /** Снаряды повтора, видные в прошлом тике; номера и последние места всех, что появлялись. */
    private final Set<UUID> live = new HashSet<>();
    private final Map<UUID, Integer> number = new HashMap<>();
    private final Map<UUID, Vec3> last = new HashMap<>();
    /** Кадры в очереди: тик проигрывания, имя, снаряд — камера сбоку от него, пока он есть, потом у его последнего места. */
    private final List<Shot> shots = new ArrayList<>();

    private record Shot(int due, String name, UUID of, boolean flight) {}

    ReplayCheck() {
        try {
            flashback = Class.forName("com.moulberry.flashback.Flashback");
        } catch (ClassNotFoundException e) {
            fail("Flashback не загружен");
            return;
        }
        NeoForge.EVENT_BUS.addListener(this::onTick);
        // вне тика: выход из мира посреди события тика оставил бы остальным обработчикам мир null
        Minecraft.getInstance().tell(ReplayCheck::leave);
    }

    /** Как {@code PauseScreen.onDisconnect} у одиночной игры: на выходе Flashback кончает запись (quicksave — в zip сразу). */
    private static void leave() {
        Minecraft mc = Minecraft.getInstance();
        mc.level.disconnect();
        mc.disconnect(new GenericMessageScreen(Component.translatable("menu.savingLevel")));
        mc.setScreen(new TitleScreen());
        Airstrike.LOG.info("SCENARIO replay left world");
    }

    private void onTick(ClientTickEvent.Post e) {
        if (finished) return;
        Minecraft mc = Minecraft.getInstance();
        ticks++;
        try {
            if (saved == null) {
                if (mc.level != null) return;
                saved = newestReplay();
                if (saved != null) {
                    Airstrike.LOG.info("SCENARIO replay saved {} {} bytes after {} ticks", saved.getFileName(), Files.size(saved), ticks);
                    ticks = 0;
                    Path open = saved;
                    mc.tell(() -> {
                        try {
                            flashback.getMethod("openReplayWorld", Path.class).invoke(null, open);
                        } catch (ReflectiveOperationException ex) {
                            Airstrike.LOG.error("SCENARIO replay failed: openReplayWorld", ex);
                            finish();
                        }
                    });
                } else if (ticks > SAVE_WAIT) {
                    fail("повтора нет в " + replayFolder() + " через " + SAVE_WAIT + " тиков после выхода: " + list(replayFolder().getParent()));
                }
                return;
            }
            if (openedAt < 0) {
                if (mc.level != null && mc.player != null && (boolean) flashback.getMethod("isInReplay").invoke(null)) {
                    openedAt = ticks;
                    int all = 0, ours = 0;
                    for (var en : mc.level.entitiesForRendering()) {
                        all++;
                        if (BuiltInRegistries.ENTITY_TYPE.getKey(en.getType()).getNamespace().equals(Airstrike.MOD_ID)) ours++;
                    }
                    Airstrike.LOG.info("SCENARIO replay opened after {} ticks: {} at {}, entities {} (airstrike {})", ticks,
                            mc.level.dimension().location(), mc.player.blockPosition().toShortString(), all, ours);
                } else if (ticks > OPEN_WAIT) {
                    fail("мир повтора не открылся за " + OPEN_WAIT + " тиков, экран " + (mc.screen == null ? "-" : mc.screen.getClass().getName()));
                }
                return;
            }
            int since = ticks - openedAt;
            if (since == SEEK_AT) {
                server = flashback.getMethod("getReplayServer").invoke(null);
                if (server == null) {
                    fail("нет сервера повтора (Flashback.getReplayServer)");
                    return;
                }
                total = (int) server.getClass().getMethod("getTotalReplayTicks").invoke(server);
                playFrom = Math.max(0, total - PLAY_BACK);
                server.getClass().getMethod("goToReplayTick", int.class).invoke(server, playFrom);
                mc.options.hideGui = true;
                Airstrike.LOG.info("SCENARIO replay play from {} of {} ticks", playFrom, total);
            } else if (since == PLAY_AT) {
                server.getClass().getField("replayPaused").setBoolean(server, false);
                playTicks = 0;
            } else if (playTicks >= 0) {
                if (mc.level == null || mc.player == null) {
                    fail("мир повтора закрылся посреди проигрывания");
                    return;
                }
                play(mc);
                playTicks++;
            }
        } catch (ReflectiveOperationException | IOException ex) {
            Airstrike.LOG.error("SCENARIO replay failed", ex);
            finish();
        }
    }

    /** Тик проигрывания: снаряды появились и пропали — кадры в очередь; камера — сбоку от снаряда; кадры по сроку. */
    private void play(Minecraft mc) throws ReflectiveOperationException {
        int tick = (int) server.getClass().getMethod("getReplayTick").invoke(server);
        Map<UUID, Vec3> now = new HashMap<>();
        Map<String, Integer> ours = new TreeMap<>();
        for (Entity en : mc.level.entitiesForRendering()) {
            var key = BuiltInRegistries.ENTITY_TYPE.getKey(en.getType());
            if (key.getNamespace().equals(Airstrike.MOD_ID)) ours.merge(key.getPath(), 1, Integer::sum);
            if (en instanceof StrikeProjectile) now.put(en.getUUID(), en.position());
        }
        last.putAll(now);
        for (UUID id : now.keySet()) {
            if (number.putIfAbsent(id, number.size() + 1) == null) {
                for (int d : FLIGHT) shots.add(new Shot(playTicks + d, "flight" + number.get(id) + "_" + d, id, true));
            }
        }
        for (UUID id : live) {
            if (!now.containsKey(id)) {
                for (int d : GONE) shots.add(new Shot(playTicks + d, "gone" + number.get(id) + "_" + d, id, false));
            }
        }
        live.clear();
        live.addAll(now.keySet());
        int fx = 0;
        for (FxBudget b : FxBudget.values()) fx += FxPool.INSTANCE.live(b);
        String floor = BuiltInRegistries.BLOCK.getKey(mc.level.getBlockState(FLOOR).getBlock()).toString();
        if (playTicks % 20 == 0) {
            Airstrike.LOG.info("SCENARIO replay play tick={} airstrike={} fx={} floor={}", tick, ours, fx, floor);
        }
        shots.sort(Comparator.comparingInt(Shot::due));
        if (!shots.isEmpty()) {
            Shot next = shots.get(0);
            Vec3 at = last.get(next.of);
            // снаряд — в 16 блоках сбоку и чуть сзади, место пропажи — в 40: виден огненный шар и дым, если это взрыв
            look(mc, at, next.flight && now.containsKey(next.of) ? new Vec3(14, 4, -8) : new Vec3(32, 14, -22));
            if (playTicks >= next.due) {
                shots.remove(0);
                Screenshot.grab(mc.gameDirectory, "replay_" + next.name + ".png", mc.getMainRenderTarget(), c -> {});
                var cam = mc.getCameraEntity();
                BlockPos under = BlockPos.containing(at.x, FLOOR.getY(), at.z);
                Airstrike.LOG.info("SCENARIO replay frame {} tick={} at {} camera {} {} airstrike={} fx={} fps={} floor={} under={}", next.name, tick,
                        BlockPos.containing(at).toShortString(), cam == null ? "-" : cam.getClass().getSimpleName(),
                        cam == null ? "-" : cam.blockPosition().toShortString(), ours, fx, mc.getFps(), floor,
                        BuiltInRegistries.BLOCK.getKey(mc.level.getBlockState(under).getBlock()));
                if (++frames == MAX_FRAMES) {
                    finish();
                    return;
                }
            }
        }
        if (shots.isEmpty() && (tick >= total - 2 || playTicks > total - playFrom + 400)) {
            Airstrike.LOG.info("SCENARIO replay played to tick {} of {}: projectiles {}, frames {}", tick, total, number.size(), frames);
            finish();
        }
    }

    /** Зритель повтора — в {@code offset} от точки, лицом к ней. */
    private static void look(Minecraft mc, Vec3 at, Vec3 offset) {
        Vec3 eye = at.add(offset);
        Vec3 d = at.subtract(eye);
        float yaw = (float) (Mth.atan2(d.z, d.x) * Mth.RAD_TO_DEG) - 90f;
        float pitch = (float) -(Mth.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z)) * Mth.RAD_TO_DEG);
        mc.player.moveTo(eye.x, eye.y - mc.player.getEyeHeight(), eye.z, yaw, pitch);
        mc.player.setDeltaMovement(Vec3.ZERO);
        if (mc.getCameraEntity() != mc.player) mc.setCameraEntity(mc.player);
    }

    private Path replayFolder() throws ReflectiveOperationException {
        return (Path) flashback.getMethod("getReplayFolder").invoke(null);
    }

    private Path newestReplay() throws ReflectiveOperationException, IOException {
        Path dir = replayFolder();
        if (!Files.isDirectory(dir)) return null;
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(p -> p.getFileName().toString().endsWith(".zip")).max(Comparator.comparing(p -> p.toFile().lastModified())).orElse(null);
        }
    }

    /** Что лежит в папке данных Flashback (два уровня): чем кончилась запись, если повтора нет. */
    private static String list(Path dir) throws IOException {
        if (dir == null || !Files.isDirectory(dir)) return "папки нет";
        try (Stream<Path> s = Files.walk(dir, 2)) {
            List<String> names = s.map(p -> dir.relativize(p).toString()).filter(n -> !n.isEmpty()).sorted().toList();
            return names.isEmpty() ? "пусто" : String.join(", ", names);
        }
    }

    private void fail(String why) {
        Airstrike.LOG.error("SCENARIO replay failed: {}", why);
        finish();
    }

    private void finish() {
        finished = true;
        Airstrike.LOG.info("SCENARIO done");
        Minecraft.getInstance().stop();
    }
}
