package ua.zentix.airstrike.scenario;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.CraftingScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.StructureTags;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.ChatVisiblity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL11;
import ua.zentix.airstrike.Airstrike;
import ua.zentix.airstrike.client.aim.Designator;
import ua.zentix.airstrike.client.cam.ProjectileCamera;
import ua.zentix.airstrike.client.hud.ClientFlights;
import ua.zentix.airstrike.client.map.MapProjection;
import ua.zentix.airstrike.client.map.MapTarget;
import ua.zentix.airstrike.client.map.TerrainTiles;
import ua.zentix.airstrike.client.screen.MapScreen;
import ua.zentix.airstrike.client.screen.RemoteScreen;
import ua.zentix.airstrike.defense.SamBlockEntity;
import ua.zentix.airstrike.entity.FlightPhase;
import ua.zentix.airstrike.strike.TargetMode;
import ua.zentix.airstrike.strike.WeaponType;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.Supplier;

/**
 * Кадры руководства игрока ({@code docs/guide}): {@code tools/client_scenario.sh guide}, потом
 * {@code tools/guide_images.sh} режет их и собирает GIF в {@code docs/guide/img}. Деревня у спавна мира сценария
 * (середина — колокол на площади), зритель на возвышении в 90–130 блоках от неё; по очереди: рецепты в верстаке
 * (картинки), остальное — GIF: бинокль, экран пульта, карта наведения, снаряды на экране, камера ракеты, «Ланцет»,
 * ЗРК, стационарная пусковая. В GIF экрана каждый кадр — шаг, мышь и подпись клавиши нарисованы поверх (настоящую
 * клавишу без окна не нажать); GIF мира идёт с заданным темпом ({@link #startGif}). {@code AIRSTRIKE_GUIDE} — только
 * названные разделы ({@link #only}). Кадры — {@code run/scenario/screenshots/guide-*.png}, кадры GIF —
 * {@code guide-gif-<имя>-NNNN.png}. С Distant Horizons ({@code dh}) карта показала бы рельеф вдали, как у игроков сборки,
 * но в облаке он почти ничего не строит: там карта — по загруженным чанкам.
 */
final class GuideShots {
    private static final String[] RECIPES = {"strike_designator", "shahed", "lancet", "cruise_missile", "grad_rockets", "bunker_buster",
            "nuclear_warhead", "icbm", "sam", "interceptor", "substation", "fixed_launcher"};
    /** Кадров GIF в секунду: шаг сценария идёт по кадрам, поэтому GIF ровный при любом fps клиента без окна. */
    private static final int FPS = 12;
    /** Сколько ждать, пока Distant Horizons (если стоит) посчитает рельеф вокруг (с входа в мир), тиков. */
    private static final int DH_WARMUP = 4800;
    /** Прорисовка на время карты, чанков: сервер грузит рельеф вокруг шире обычного. */
    private static final int MAP_RENDER_DISTANCE = 20;
    /** Сколько чанков вокруг ждать перед картой: в облаке за 5 минут их грузится около 14 (генерация медленная). */
    private static final int MAP_CHUNKS = 13;
    /** Цель на карте — в стольких блоках от зрителя, внутри круга загруженных чанков; точки маршрута — по ней. */
    private static final int MAP_TARGET = 180;
    /** Карта отдаляется колесом на столько щелчков (×1,25 каждый). */
    private static final int ZOOM_OUT = 2;

    private final Minecraft mc = Minecraft.getInstance();

    // ---------------------------------------------------------------- шаги по тикам

    private interface Step {
        boolean done(int t);
    }

    private final ArrayDeque<Step> steps = new ArrayDeque<>();
    private int stepTick, tick;
    /** Нарисованных кадров с запуска. */
    private int frames;

    // ---------------------------------------------------------------- место съёмки (ставит поток сервера)

    private volatile Vec3 village, view;
    /** Направление от зрителя на деревню и поперёк него (по горизонтали). */
    private Vec3 along, across;
    /** Карта: цель за деревней, две точки маршрута сбоку, середина вида. */
    private Vec3 target, w1, w2, mid;

    // ---------------------------------------------------------------- GIF

    private record Seg(int frames, IntConsumer frame) {}

    private final List<Seg> building = new ArrayList<>();
    private List<Seg> gif;
    private String gifName;
    private int segIndex, segFrame, gifFrame;
    /** Скорость мира в GIF: 1 — как в жизни, 2 — вдвое быстрее; 0 — GIF экрана, темп мира не трогается. */
    private double pace;
    private long paceNanos;
    private double tickRate = 20;
    /** Мышь в координатах GUI, видна ли, подпись у неё и до какого кадра, круг щелчка. */
    private double mx, my;
    private boolean cursor;
    private String hint;
    private int hintUntil, ringAt = -100;
    private double ringX, ringY;

    GuideShots() {
        NeoForge.EVENT_BUS.addListener(this::onTick);
        NeoForge.EVENT_BUS.addListener(this::onFramePre);
        NeoForge.EVENT_BUS.addListener(this::onFramePost);
        NeoForge.EVENT_BUS.addListener(this::onScreenRender);
        NeoForge.EVENT_BUS.addListener(this::onGuiRender);
        plan();
    }

    private void plan() {
        await(60);
        run(() -> {
            mc.options.chatVisibility().set(ChatVisiblity.HIDDEN);
            cmd("gamerule sendCommandFeedback false");
            cmd("time set 5000");
            cmd("weather clear");
            onServer(this::findPlace);
        });
        until("места съёмки", 6000, () -> view != null);
        run(() -> {
            along = new Vec3(village.x - view.x, 0, village.z - view.z).normalize();
            across = new Vec3(-along.z, 0, along.x);
            Airstrike.LOG.info("SCENARIO guide: деревня {}, зритель {}", xyz(village), xyz(view));
            stand(view, village);
            give("missile", 6, 20, "map");
        });
        await(200);
        // сервер догружает место и считает рельеф: верстак и пуски ждут, пока его тик не станет обычным
        until("тика сервера меньше 60 мс", 2400, () -> {
            MinecraftServer server = mc.getSingleplayerServer();
            return server != null && server.getAverageTickTimeNanos() < 60_000_000L;
        });
        // секции чанков вокруг собраны: на llvmpipe кадр сразу после входа — одно небо
        until("прорисовки вокруг", 2400, this::worldReady);
        if (only("recipes")) recipes();
        if (only("scope")) scope();
        if (only("remote")) remote();
        if (only("map")) {
            // без Distant Horizons карта рисует загруженные чанки: прорисовка шире — рельеф до цели и маршрута
            run(() -> mc.options.renderDistance().set(MAP_RENDER_DISTANCE));
            until("чанков вокруг", 6000, () -> mc.level.getChunkSource().getLoadedChunksCount() >= (2 * MAP_CHUNKS + 1) * (2 * MAP_CHUNKS + 1)
                    && (tick >= DH_WARMUP || !net.neoforged.fml.ModList.get().isLoaded("distanthorizons")));
            mapGif();
            run(() -> mc.options.renderDistance().set(12));
            until("попаданий шахедов с карты", 2400, () -> ClientFlights.all().isEmpty());
        }
        if (only("hud")) {
            hud();
            until("попаданий залпа", 2400, () -> ClientFlights.all().isEmpty());
        }
        if (only("camera")) camera();
        if (only("loiter")) loiter();
        if (only("sam")) sam();
        if (only("launcher")) launcher();
        run(() -> {
            Airstrike.LOG.info("SCENARIO done");
            mc.stop();
        });
    }

    /** Разделы съёмки из {@code airstrike.guide} (через запятую: recipes, scope, remote, map, hud, camera, loiter, sam, launcher); без него — все. */
    private static boolean only(String section) {
        String list = System.getProperty("airstrike.guide");
        return list == null || List.of(list.split(",")).contains(section);
    }

    // ---------------------------------------------------------------- разделы

    private void recipes() {
        BlockPos[] table = new BlockPos[1];
        run(() -> {
            table[0] = BlockPos.containing(view.add(along.scale(2)));
            setblock(table[0], "minecraft:crafting_table");
            cmd("recipe give @s *");
        });
        await(10);
        for (String id : RECIPES) {
            run(() -> mc.player.closeContainer());
            await(5);
            run(() -> onServer(server -> ingredients(server, id)));
            await(8);
            run(() -> mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND,
                    new BlockHitResult(Vec3.atCenterOf(table[0]).add(0, 0.5, 0), Direction.UP, table[0], false)));
            until("верстака", 400, () -> mc.screen instanceof CraftingScreen && mc.player.containerMenu instanceof CraftingMenu);
            run(() -> mc.level.getRecipeManager().byKey(ResourceLocation.fromNamespaceAndPath(Airstrike.MOD_ID, id)).ifPresentOrElse(
                    r -> mc.gameMode.handlePlaceRecipe(mc.player.containerMenu.containerId, r, false),
                    () -> Airstrike.LOG.warn("SCENARIO guide: рецепта {} нет", id)));
            until("рецепта " + id + " в верстаке", 400, () -> mc.player.containerMenu instanceof CraftingMenu m && m.getSlot(0).hasItem());
            run(() -> mouse(2, 2));
            settle();
            shot("recipe-" + id);
        }
        run(() -> mc.player.closeContainer());
        await(5);
        // сетка верстака при закрытии возвращается в инвентарь: закрыть и очистить — одним шагом на сервере
        run(() -> onServer(server -> {
            ServerPlayer p = player(server);
            if (p != null) {
                p.closeContainer();
                p.getInventory().clearContent();
            }
            server.overworld().removeBlock(table[0], false);
        }));
        await(5);
        run(() -> give("missile", 6, 20, "map"));
        await(10);
    }

    /** Бинокль (GIF): ПКМ — бинокль, колесо — оружие туда и обратно, ЛКМ — пуск ракеты по деревне. */
    private void scope() {
        run(() -> {
            give("missile", 1, 0, "look");
            look(village.add(0, 0.5, 0));
        });
        await(20);
        gHold(0.8);
        gDo(() -> mc.options.keyUse.setDown(true));
        gHint("ПКМ (держать)", 2.0);
        gHold(1.6);
        gHint("колесо мыши", 2.8);
        for (double d : new double[]{1, 1, -1, -1}) {
            gDo(() -> Designator.scroll(d));
            gHold(0.65);
        }
        gHold(0.3);
        gDo(Designator::fire);
        gHint("ЛКМ — пуск", 1.8);
        gHold(2.4);
        gDo(() -> mc.options.keyUse.setDown(false));
        gHold(0.8);
        startGif("scope", 1);
        await(10);
    }

    /** Экран пульта (GIF): шахед, число и разброс, режим цели, огонь — шахеды туда, куда смотрит зритель. */
    private void remote() {
        String count = "airstrike.remote.count", spread = "airstrike.remote.spread";
        run(() -> {
            give("missile", 6, 20, "look");
            look(village);
        });
        await(10);
        run(() -> {
            mc.setScreen(new RemoteScreen());
            double[] c = center();
            mouse(c[0] + 40, c[1] + 70);
            cursor = true;
        });
        await(10);
        gHold(0.6);
        gMove(() -> widgetWith(WeaponType.DRONE.displayName().getString()), 0.8);
        gHold(0.2);
        gClick(0);
        gHold(0.7);
        gMove(() -> sliderAt(count, 6, 1, 30), 0.8);
        gDrag(() -> sliderAt(count, 3, 1, 30), 0.7);
        gHold(0.5);
        gMove(() -> sliderAt(spread, 20, 0, 150), 0.6);
        gDrag(() -> sliderAt(spread, 10, 0, 150), 0.6);
        gHold(0.5);
        gMove(() -> widgetWith(TargetMode.LOOK.displayName().getString()), 0.8);
        gHold(0.2);
        gClick(0);
        gHold(1.0);
        gMove(() -> widget("airstrike.remote.fire"), 0.8);
        gHold(0.4);
        // огонь закрывает экран: кадр после щелчка — уже мир, guide_images.sh его отрезает
        gClick(0);
        startGif("remote", 0);
        run(() -> {
            cursor = false;
            if (mc.screen != null) mc.setScreen(null);
        });
        await(10);
    }

    /**
     * Карта: с экрана пульта — «Открыть карту наведения…», отдалить, сдвинуть, выбрать место за деревней, две точки
     * маршрута (Shift+ЛКМ), перетащить вторую, огонь; потом карта ещё раз (клавиша «,») — шахеды на ней.
     */
    private void mapGif() {
        run(() -> {
            target = view.add(along.scale(MAP_TARGET));
            w1 = view.add(along.scale(MAP_TARGET * 0.3)).add(across.scale(MAP_TARGET * 0.35));
            w2 = view.add(along.scale(MAP_TARGET * 0.7)).add(across.scale(MAP_TARGET * 0.45));
            mid = view.add(target).scale(0.5).add(across.scale(MAP_TARGET * 0.15));
            give("drone", 3, 20, "map");
            look(village);
            MapTarget.clearRoute();
        });
        await(10);
        // рельеф того же вида заранее: карта в GIF не достраивается на глазах
        run(() -> {
            mc.setScreen(new MapScreen(new RemoteScreen()));
            mouse(center()[0], center()[1]);
        });
        await(5);
        run(() -> {
            for (int i = 0; i < ZOOM_OUT; i++) mc.screen.mouseScrolled(mx, my, 0, -1);
            pan(mid);
        });
        until("рельефа карты", 2400, () -> {
            TerrainTiles.Progress p = TerrainTiles.progress();
            if (tick % 100 == 0) Airstrike.LOG.info("SCENARIO guide: карта {} из {} ({}), {}", p.ready(), p.visible(), p.layers(), TerrainTiles.stats());
            return p.visible() > 0 && p.ready() >= p.visible();
        });
        run(() -> {
            mc.screen.keyPressed(GLFW.GLFW_KEY_SPACE, 0, 0);
            for (int i = 0; i < ZOOM_OUT; i++) mc.screen.mouseScrolled(mx, my, 0, 1);
            mc.setScreen(new RemoteScreen());
        });
        await(10);
        run(() -> {
            double[] c = center();
            mx = c[0] + 40;
            my = c[1] + 60;
            cursor = true;
        });
        // GIF
        gMove(() -> widget("airstrike.remote.open_map"), 1.0);
        gHold(0.3);
        gClick(0);
        gHold(0.6);
        gMove(this::center, 0.6);
        for (int i = 0; i < ZOOM_OUT; i++) {
            gScroll(-1, "колесо");
            gHold(0.35);
        }
        gDrag(() -> {
            MapProjection p = projection();
            double[] c = center();
            return new double[]{c[0] + (p.cx() - mid.x) * p.k(), c[1] + (p.cz() - mid.z) * p.k()};
        }, 1.2);
        gHold(0.4);
        gMove(() -> at(target), 1.0);
        gHold(0.2);
        gClick(0);
        gHold(1.2);
        gMove(() -> at(w1), 0.9);
        gHint("Shift + ЛКМ", 1.0);
        gDo(() -> addWaypoint(w1));
        gHold(0.7);
        gMove(() -> at(w2), 0.9);
        gHint("Shift + ЛКМ", 1.0);
        gDo(() -> addWaypoint(w2));
        gHold(0.7);
        gDrag(() -> at(w2.add(across.scale(MAP_TARGET * 0.2)).add(along.scale(-MAP_TARGET * 0.07))), 1.0);
        gHold(1.0);
        gMove(() -> widget("airstrike.remote.fire"), 1.0);
        gHold(0.3);
        gClick(0);
        // без окна кадр — около секунды игры: шахеды на пусковой ещё несколько секунд, полёт на карте идёт быстрее жизни
        gHold(0.5);
        gDo(() -> {
            mc.setScreen(new MapScreen(new RemoteScreen()));
            double[] c = center();
            mx = c[0] + 150;
            my = c[1] + 70;
            hint = "клавиша «,»";
            hintUntil = gifFrame + FPS * 3 / 2;
        });
        gHold(3.0);
        startGif("map", 0);
        run(() -> {
            cursor = false;
            if (mc.screen != null) mc.setScreen(null);
        });
        await(10);
    }

    /**
     * Снаряды на экране (GIF, вдвое быстрее жизни): залп шахедов и ракет к деревне. GIF — с подлёта ракет: метки
     * снарядов идут к деревне, список справа считает время, ракеты бьют; зритель с пустой рукой смотрит на деревню
     * со стороны, откуда они летят.
     */
    private void hud() {
        run(() -> {
            give("missile", 1, 0, "look");
            look(village);
            mc.gameRenderer.setRenderHand(false); // дальше рука только закрывает кадр
            cmd(String.format(Locale.ROOT, "airstrike salvo drone 3 8 at %.1f %.1f %.1f", village.x, village.y, village.z));
            cmd(String.format(Locale.ROOT, "airstrike salvo missile 2 10 at %.1f %.1f %.1f", village.x, village.y, village.z));
        });
        until("подлёта ракет", 2400, () -> ClientFlights.all().stream()
                .anyMatch(f -> f.weapon() == WeaponType.MISSILE && f.etaSeconds(0) <= 10));
        run(() -> {
            mc.player.getInventory().selected = 8;
            ClientFlights.all().stream().filter(f -> f.weapon() == WeaponType.MISSILE).findFirst().ifPresent(f -> {
                Vec3 from = f.position(0).subtract(village);
                look(village.add(new Vec3(from.x, 0, from.z).normalize().scale(40)).add(0, 12, 0));
            });
        });
        await(5);
        gHold(7.0);
        startGif("hud", 2);
    }

    /**
     * Камера ракеты (GIF): вдали — карта с её путём, ближе 160 м к игроку — видео с борта, пике и попадание. Путь прямой
     * (from и via на одной линии с деревней) и заходит с той стороны деревни, где рельеф ниже: атака ракеты не видит
     * склона между ней и целью, а с холма зрителя она цепляла деревья на его вершине. Зритель — на пути у деревни:
     * видео — весь подлёт.
     */
    private void camera() {
        until("пустого неба", 2400, () -> ClientFlights.all().isEmpty());
        Vec3[] side = new Vec3[1];
        run(() -> onServer(server -> {
            ServerLevel level = server.overworld();
            int best = Integer.MAX_VALUE;
            Vec3 pick = along;
            for (Vec3 d : new Vec3[]{along, along.reverse(), across, across.reverse()}) {
                int top = Integer.MIN_VALUE;
                for (int t = 16; t <= 200; t += 8) {
                    int x = (int) Math.floor(village.x + d.x * t), z = (int) Math.floor(village.z + d.z * t);
                    level.getChunk(x >> 4, z >> 4);
                    top = Math.max(top, level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z));
                }
                if (top < best) {
                    best = top;
                    pick = d;
                }
            }
            Vec3 e = village.add(pick.scale(40));
            stand(new Vec3(e.x, surface(level, (int) Math.floor(e.x), (int) Math.floor(e.z)), e.z), village);
            Airstrike.LOG.info("SCENARIO guide: заход ракеты с {}, рельеф на подлёте до y {}", xyz(village.add(pick.scale(200))), best);
            side[0] = pick;
        }));
        until("места зрителя", 200, () -> side[0] != null);
        await(40);
        until("прорисовки у деревни", 1200, this::worldReady);
        run(() -> {
            Vec3 from = village.add(side[0].scale(400)), via = village.add(side[0].scale(150));
            cmd(String.format(Locale.ROOT, "airstrike salvo missile 1 0 at %.1f %.1f %.1f from %.1f %.1f via %.1f %.1f",
                    village.x, village.y, village.z, from.x, from.z, via.x, via.z));
        });
        until("пуска", 200, () -> !ClientFlights.all().isEmpty());
        run(ProjectileCamera::cycle);
        // ~2–3 с карты, видео с ~200 м до деревни, пике и попадание
        until("подлёта ракеты", 2400, () -> ClientFlights.all().stream().anyMatch(f -> f.etaSeconds(0) <= 5));
        gHold(6.5);
        startGif("camera", 1);
        run(() -> {
            if (ProjectileCamera.isActive()) ProjectileCamera.exit();
        });
        until("попадания ракеты", 1200, () -> ClientFlights.all().isEmpty());
    }

    /** «Ланцет» (GIF, в полтора раза быстрее жизни): камера на круге, ЛКМ по деревне — новая цель, пике и попадание. */
    private void loiter() {
        // камера показывает видео ближе 160 м от игрока, дальше — карту: зритель у деревни, весь круг «Ланцета» рядом
        run(() -> onServer(server -> {
            ServerLevel level = server.overworld();
            Vec3 e = village.subtract(along.scale(50));
            stand(new Vec3(e.x, surface(level, (int) Math.floor(e.x), (int) Math.floor(e.z)), e.z), village);
        }));
        await(40);
        until("прорисовки у деревни", 1200, this::worldReady);
        // пусковая за деревней, с другой стороны от места зрителя: путь к деревне не идёт через его холм
        run(() -> {
            Vec3 site = village.add(along.scale(250));
            cmd(String.format(Locale.ROOT, "airstrike salvo loiter 1 0 at %.1f %.1f %.1f from %.1f %.1f", village.x, village.y, village.z, site.x, site.z));
        });
        until("пуска", 200, () -> !ClientFlights.all().isEmpty());
        run(ProjectileCamera::cycle);
        until("круга «Ланцета»", 2400, () -> ClientFlights.all().stream().anyMatch(f -> f.phase() == FlightPhase.LOITER));
        // камера «Ланцета» смотрит, куда смотрит игрок: прицел держится на деревне
        Supplier<Vec3> aim = () -> village.add(across.scale(12));
        gEach(3.0, () -> look(village));
        gEach(0.6, () -> look(aim.get()));
        gDo(this::retarget);
        gHint("ЛКМ — новая цель", 1.8);
        gEach(6.0, () -> look(aim.get()));
        startGif("loiter", 1.5);
        run(() -> {
            if (ProjectileCamera.isActive()) ProjectileCamera.exit();
        });
        until("попадания «Ланцета»", 1200, () -> ClientFlights.all().isEmpty());
    }

    /**
     * ЗРК (GIF): ЗРК на поляне зрителя, ракеты — из воронки сбоку. Шахеды летят с пусковой за деревней («from») к деревне,
     * ЗРК бьёт их на подлёте. Зритель — за ЗРК и в стороне от него: дым пуска уходит вбок, а не в кадр. Шахедов пускают,
     * когда на направляющих все четыре ракеты: в GIF — залп ЗРК, а не перезарядка.
     */
    private void sam() {
        BlockPos[] sam = new BlockPos[1];
        int[] ready = {0};
        run(() -> onServer(server -> {
            Vec3 at = view.add(across.scale(10));
            sam[0] = BlockPos.containing(at.x, surface(server.overworld(), (int) Math.floor(at.x), (int) Math.floor(at.z)), at.z);
        }));
        until("места ЗРК", 100, () -> sam[0] != null);
        // поляна 25×25 у ЗРК и зрителя: под листвой ЗРК не стреляет (нет открытого неба), а зритель смотрел сквозь неё;
        // ровная — на высоте ЗРК, вперёд от зрителя склон не закрывает (fill — до 32768 блоков за раз)
        run(() -> {
            BlockPos s = sam[0];
            Vec3 m = Vec3.atBottomCenterOf(s).subtract(along.scale(5));
            int x = (int) Math.floor(m.x), y = s.getY(), z = (int) Math.floor(m.z);
            cmd(String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:air", x - 12, y, z - 12, x + 12, y + 30, z + 12));
            cmd(String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:grass_block", x - 12, y - 1, z - 12, x + 12, y - 1, z + 12));
        });
        run(() -> {
            BlockPos s = sam[0];
            setblock(s, "airstrike:sam");
            setblock(s.east(), "minecraft:hopper[facing=west]{Items:[{Slot:0b,id:\"airstrike:interceptor\",count:12}]}");
            Vec3 c = Vec3.atBottomCenterOf(s);
            stand(c.subtract(along.scale(10)).add(across.scale(-6)), c.add(along.scale(80)).add(0, 20, 0));
            mc.player.getInventory().selected = 8;
            mc.gameRenderer.setRenderHand(false); // рука и пульт в руке закрывали полкадра
        });
        until("ракет ЗРК на направляющих", 1200, () -> {
            onServer(server -> {
                if (server.overworld().getBlockEntity(sam[0]) instanceof SamBlockEntity be) ready[0] = be.ready();
            });
            return ready[0] >= 4;
        });
        run(() -> {
            Vec3 site = village.add(along.scale(300));
            cmd(String.format(Locale.ROOT, "airstrike salvo drone 4 20 at %.1f %.1f %.1f from %.1f %.1f", village.x, village.y, village.z, site.x, site.z));
        });
        // пусковая шахедов встаёт и пускает; ЗРК бьёт их, как только они в воздухе
        await(40);
        gHold(14.0);
        startGif("sam", 1);
        until("попаданий", 1200, () -> ClientFlights.all().isEmpty());
        // ЗРК без хозяина бьёт любые снаряды: ракету стационарной пусковой он сбил бы на пуске
        run(() -> {
            setblock(sam[0].east(), "minecraft:air");
            setblock(sam[0], "minecraft:air");
        });
    }

    /**
     * Стационарная пусковая (GIF): площадка, пусковая с задачей «крылатая ракета по деревне» через точку маршрута впереди —
     * пакет сразу доворачивается на неё, и курс пуска известен без пробного пуска (его дым стоял бы в кадре). Зритель —
     * сбоку от курса, рядом с пусковой рычаг, пуск — сигналом рычага, как у игрока.
     */
    private void launcher() {
        BlockPos[] pad = new BlockPos[1];
        BlockPos[] lever = new BlockPos[1];
        String[] wall = new String[1];
        run(() -> onServer(server -> {
            Vec3 at = view.add(across.scale(-12));
            pad[0] = BlockPos.containing(at.x, surface(server.overworld(), (int) Math.floor(at.x), (int) Math.floor(at.z)), at.z);
        }));
        until("места пусковой", 100, () -> pad[0] != null);
        // площадка 33×33 (fill — не больше 32768 блоков за раз)
        run(() -> {
            BlockPos s = pad[0];
            cmd(String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:air", s.getX() - 16, s.getY(), s.getZ() - 16, s.getX(), s.getY() + 30, s.getZ() + 16));
            cmd(String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:air", s.getX() + 1, s.getY(), s.getZ() - 16, s.getX() + 16, s.getY() + 30, s.getZ() + 16));
            cmd(String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:stone_bricks", s.getX() - 16, s.getY() - 1, s.getZ() - 16, s.getX() + 16, s.getY() - 1, s.getZ() + 16));
            setblock(s, "airstrike:fixed_launcher");
            Vec3 c = Vec3.atBottomCenterOf(s), via = c.add(along.scale(120));
            cmd(String.format(Locale.ROOT, "airstrike launcher %d %d %d mission missile 1 0 %.1f %.1f %.1f via %.1f %.1f",
                    s.getX(), s.getY(), s.getZ(), village.x, village.y, village.z, via.x, via.z));
            cmd(String.format(Locale.ROOT, "airstrike launcher %d %d %d load 4", s.getX(), s.getY(), s.getZ()));
            Vec3 forward = new Vec3(via.x - c.x, 0, via.z - c.z).normalize(), side = new Vec3(-forward.z, 0, forward.x);
            if (side.dot(across) < 0) side = side.scale(-1); // к поляне зрителя
            Direction d = Direction.getNearest(side.x, 0, side.z);
            lever[0] = s.relative(d);
            wall[0] = "minecraft:lever[face=wall,facing=" + d.getSerializedName();
            setblock(lever[0], wall[0] + ",powered=false]");
            stand(c.add(side.scale(6)).subtract(forward.scale(4)), c.add(forward.scale(4)).add(0, 3, 0));
            mc.player.getInventory().selected = 8;
            mc.gameRenderer.setRenderHand(false);
        });
        // пакет поднимается и доворачивает на курс
        await(200);
        until("прорисовки у пусковой", 600, this::worldReady);
        gHold(1.2);
        gDo(() -> setblock(lever[0], wall[0] + ",powered=true]"));
        gHint("рычаг — сигнал", 1.8);
        gHold(7.0);
        startGif("launcher", 1);
    }

    // ---------------------------------------------------------------- место съёмки

    /**
     * Поток сервера: деревня у спавна и место зрителя в 90–130 блоках от неё — на земле (не в воде), откуда середину
     * деревни видно поверх листвы; из таких самое высокое. Если такого нет — самое высокое место, а зритель встаёт
     * на столб, пока деревню не станет видно.
     */
    private void findPlace(MinecraftServer server) {
        ServerLevel level = server.overworld();
        BlockPos v = level.findNearestMapStructure(StructureTags.VILLAGE, level.getSharedSpawnPos(), 64, false);
        if (v == null) {
            Airstrike.LOG.warn("SCENARIO guide: деревни нет — снимаю у спавна");
            v = level.getSharedSpawnPos();
        }
        // середина деревни — колокол на площади, а не начало постройки (оно бывает на краю, в лесу)
        level.getPoiManager().ensureLoadedAndValid(level, v, 80);
        v = level.getPoiManager().findClosest(t -> t.is(PoiTypes.MEETING), v, 80, PoiManager.Occupancy.ANY).orElse(v);
        Vec3 center = new Vec3(v.getX() + 0.5, surface(level, v.getX(), v.getZ()), v.getZ() + 0.5);
        Vec3 best = null, highest = null;
        for (int d = 90; d <= 130; d += 20) {
            for (int i = 0; i < 16; i++) {
                double a = i * Math.PI / 8;
                int x = (int) Math.round(center.x + d * Math.cos(a)), z = (int) Math.round(center.z + d * Math.sin(a));
                int y = surface(level, x, z);
                if (!level.getFluidState(new BlockPos(x, y - 1, z)).isEmpty()) continue;
                Vec3 at = new Vec3(x + 0.5, y, z + 0.5);
                if (highest == null || y > highest.y) highest = at;
                if (sees(level, at, center) && (best == null || y > best.y)) best = at;
            }
        }
        if (best == null && highest != null) {
            for (int up = 4; up <= 32 && best == null; up += 4) {
                Vec3 at = highest.add(0, up, 0);
                if (sees(level, at, center)) {
                    BlockPos base = BlockPos.containing(highest);
                    for (int k = 0; k < up; k++) level.setBlock(base.above(k), net.minecraft.world.level.block.Blocks.STONE_BRICKS.defaultBlockState(), 3);
                    best = at;
                }
            }
        }
        if (best == null) best = highest != null ? highest : center.add(110, 20, 0);
        village = center;
        view = best;
    }

    /** От глаз зрителя в {@code at} видно середину деревни (листва — преграда). */
    private static boolean sees(ServerLevel level, Vec3 at, Vec3 village) {
        Vec3 eye = at.add(0, 1.62, 0), aim = village.add(0, 3, 0);
        var hit = level.clip(new net.minecraft.world.level.ClipContext(eye, aim, net.minecraft.world.level.ClipContext.Block.OUTLINE,
                net.minecraft.world.level.ClipContext.Fluid.NONE, net.minecraft.world.phys.shapes.CollisionContext.empty()));
        return hit.getType() == net.minecraft.world.phys.HitResult.Type.MISS || hit.getLocation().distanceTo(aim) < 12;
    }

    /** Верх земли в колонке; чанк грузится сразу (сценарий: ждать можно). */
    private static int surface(ServerLevel level, int x, int z) {
        level.getChunk(x >> 4, z >> 4);
        return level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
    }

    /** Чанки в 8 вокруг камеры получены и собраны для отрисовки. */
    private boolean worldReady() {
        Vec3 cam = mc.gameRenderer.getMainCamera().getPosition();
        int cx = (int) Math.floor(cam.x) >> 4, cz = (int) Math.floor(cam.z) >> 4;
        for (int dx = -8; dx <= 8; dx++) {
            for (int dz = -8; dz <= 8; dz++) {
                if (!mc.level.getChunkSource().hasChunk(cx + dx, cz + dz)) return false;
            }
        }
        return mc.levelRenderer.hasRenderedAllSections();
    }

    /** Игрок стоит в {@code at} (на земле там) и смотрит на {@code toward}. */
    private void stand(Vec3 at, Vec3 toward) {
        float[] r = angles(at.add(0, 1.62, 0), toward);
        onServer(server -> {
            ServerPlayer p = player(server);
            if (p == null) return;
            p.getAbilities().flying = false;
            p.onUpdateAbilities();
            p.teleportTo(server.overworld(), at.x, at.y, at.z, r[0], r[1]);
        });
    }

    private void look(Vec3 toward) {
        float[] r = angles(mc.gameRenderer.getMainCamera().getPosition(), toward);
        mc.player.setYRot(r[0]);
        mc.player.setXRot(r[1]);
    }

    private static float[] angles(Vec3 from, Vec3 to) {
        Vec3 d = to.subtract(from);
        return new float[]{(float) Math.toDegrees(Math.atan2(-d.x, d.z)), (float) -Math.toDegrees(Math.atan2(d.y, Math.hypot(d.x, d.z)))};
    }

    /** Пульт в руку с настройкой. */
    private void give(String weapon, int count, int spread, String mode) {
        cmd(String.format(Locale.ROOT, "item replace entity @s weapon.mainhand with airstrike:strike_designator[airstrike:loadout={weapon:\"%s\",count:%d,spread:%d,mode:\"%s\"}]",
                weapon, count, spread, mode));
    }

    /** Поток сервера: инвентарь — ровно ингредиенты рецепта (по одному предмету на клетку), чтобы верстак их разложил. */
    private void ingredients(MinecraftServer server, String id) {
        ServerPlayer p = player(server);
        var recipe = server.getRecipeManager().byKey(ResourceLocation.fromNamespaceAndPath(Airstrike.MOD_ID, id));
        if (p == null || recipe.isEmpty()) return;
        p.getInventory().clearContent();
        for (Ingredient in : recipe.get().value().getIngredients()) {
            if (in.isEmpty()) continue;
            ItemStack[] items = in.getItems();
            if (items.length > 0) p.getInventory().add(items[0].copyWithCount(1));
        }
        p.inventoryMenu.broadcastChanges();
    }

    // ---------------------------------------------------------------- карта

    private double[] center() {
        Screen s = mc.screen;
        return s == null ? new double[]{mx, my} : new double[]{s.width / 2.0, s instanceof MapScreen ? 22 + (s.height - 22 - 56) / 2.0 : s.height / 2.0};
    }

    /** Середина кнопки экрана по ключу её подписи. */
    private double[] widget(String key) {
        return widgetWith(Component.translatable(key).getString());
    }

    /** Середина кнопки экрана, чья подпись начинается с {@code text}. */
    private double[] widgetWith(String text) {
        AbstractWidget w = findWidget(text);
        return w == null ? new double[]{mx, my} : new double[]{w.getX() + w.getWidth() / 2.0, w.getY() + w.getHeight() / 2.0};
    }

    /**
     * Точка ползунка экрана пульта для значения {@code v} из {@code min…max} (как {@code setValueFromMouse}); ползунок —
     * по подписи ключа {@code key} до двоеточия (язык известен только в игре, не при планировании шагов).
     */
    private double[] sliderAt(String key, int v, int min, int max) {
        AbstractWidget w = findWidget(Component.translatable(key, "").getString().split(":")[0]);
        if (w == null) return new double[]{mx, my};
        return new double[]{w.getX() + 4 + (w.getWidth() - 8) * (v - min) / (double) (max - min), w.getY() + w.getHeight() / 2.0};
    }

    private AbstractWidget findWidget(String text) {
        if (mc.screen != null) {
            for (var c : mc.screen.children()) {
                if (c instanceof AbstractWidget w && w.getMessage().getString().startsWith(text)) return w;
            }
        }
        Airstrike.LOG.warn("SCENARIO guide: кнопки «{}» нет", text);
        return null;
    }

    /** ЛКМ в камере снаряда: новая цель там, куда смотрит прицел (закрытый метод камеры: только для сценария). */
    private void retarget() {
        try {
            Method m = ProjectileCamera.class.getDeclaredMethod("retarget");
            m.setAccessible(true);
            m.invoke(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Проекция карты (закрытый метод экрана: только для сценария). */
    private MapProjection projection() {
        try {
            var m = MapScreen.class.getDeclaredMethod("projection");
            m.setAccessible(true);
            return (MapProjection) m.invoke(mc.screen);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private double[] at(Vec3 world) {
        if (!(mc.screen instanceof MapScreen)) return new double[]{mx, my};
        MapProjection p = projection();
        return new double[]{p.ox() + (world.x - p.cx()) * p.k(), p.oy() + (world.z - p.cz()) * p.k()};
    }

    /** Сдвиг карты так, чтобы {@code world} встал в середину (как перетаскиванием). */
    private void pan(Vec3 world) {
        double[] c = center();
        MapProjection p = projection();
        double dx = (p.cx() - world.x) * p.k(), dy = (p.cz() - world.z) * p.k();
        mc.screen.mouseClicked(c[0], c[1], 0);
        mc.screen.mouseDragged(c[0] + dx, c[1] + dy, 0, dx, dy);
        mc.screen.mouseReleased(c[0] + dx, c[1] + dy, 0);
    }

    /** Точка маршрута под курсором: Shift+ЛКМ (настоящий Shift без окна не нажать — точка ставится тем же вызовом, что у экрана). */
    private void addWaypoint(Vec3 world) {
        ring();
        MapTarget.add(mc.level, new MapTarget.Place(world.x, world.z));
    }

    // ---------------------------------------------------------------- GIF: сборка

    private static int frames(double seconds) {
        return Math.max(1, (int) Math.round(seconds * FPS));
    }

    private void gMove(Supplier<double[]> to, double seconds) {
        int n = frames(seconds);
        double[] from = new double[2], dest = new double[2];
        building.add(new Seg(n, i -> {
            if (i == 0) {
                from[0] = mx;
                from[1] = my;
                double[] d = to.get();
                dest[0] = d[0];
                dest[1] = d[1];
            }
            double k = ease((i + 1) / (double) n);
            mx = from[0] + (dest[0] - from[0]) * k;
            my = from[1] + (dest[1] - from[1]) * k;
        }));
    }

    /** ЛКМ зажата там, где курсор, протянута до {@code to} и отпущена (сдвиг карты или точки маршрута). */
    private void gDrag(Supplier<double[]> to, double seconds) {
        int n = frames(seconds);
        double[] from = new double[2], dest = new double[2];
        building.add(new Seg(n, i -> {
            Screen s = mc.screen;
            if (s == null) return;
            if (i == 0) {
                from[0] = mx;
                from[1] = my;
                double[] d = to.get();
                dest[0] = d[0];
                dest[1] = d[1];
                s.mouseClicked(mx, my, 0);
            }
            double k = ease((i + 1) / (double) n);
            double x = from[0] + (dest[0] - from[0]) * k, y = from[1] + (dest[1] - from[1]) * k;
            s.mouseDragged(x, y, 0, x - mx, y - my);
            mx = x;
            my = y;
            if (i == n - 1) s.mouseReleased(x, y, 0);
        }));
    }

    private void gClick(int button) {
        building.add(new Seg(1, i -> {
            ring();
            Screen s = mc.screen;
            if (s == null) return;
            s.mouseClicked(mx, my, button);
            if (mc.screen != null) mc.screen.mouseReleased(mx, my, button);
        }));
    }

    private void gScroll(double amount, String label) {
        building.add(new Seg(1, i -> {
            if (mc.screen != null) mc.screen.mouseScrolled(mx, my, 0, amount);
            hint = label;
            hintUntil = gifFrame + frames(0.4);
        }));
    }

    private void gHint(String text, double seconds) {
        building.add(new Seg(1, i -> {
            hint = text;
            hintUntil = gifFrame + frames(seconds);
        }));
    }

    private void gDo(Runnable r) {
        building.add(new Seg(1, i -> r.run()));
    }

    private void gHold(double seconds) {
        building.add(new Seg(frames(seconds), i -> {}));
    }

    /** Каждый кадр {@code seconds} секунд — {@code r} (прицел камеры «Ланцета» за точкой). */
    private void gEach(double seconds, Runnable r) {
        building.add(new Seg(frames(seconds), i -> r.run()));
    }

    /**
     * GIF из шагов {@code building}: каждый нарисованный кадр — кадр GIF. GIF мира ({@code pace} > 0) идёт с темпом
     * {@code pace} от жизни: без окна клиент рисует несколько кадров в секунду, поэтому мир замедляется ({@code /tick
     * rate}) так, чтобы на кадр приходилось 20 · pace / FPS тиков, — по fps последних кадров.
     */
    private void startGif(String name, double pace) {
        List<Seg> segs = List.copyOf(building);
        building.clear();
        run(() -> {
            gif = segs;
            gifName = name;
            segIndex = segFrame = gifFrame = 0;
            hint = null; // подпись прошлой GIF считала кадры по её счётчику
            hintUntil = 0;
            ringAt = -100;
            this.pace = pace;
            paceNanos = 0;
            if (pace > 0) tickRate(20 * pace * 5 / FPS); // без окна — около 5 кадров в секунду, дальше по замеру
            Airstrike.LOG.info("SCENARIO guide: GIF {} — {} кадров", name, segs.stream().mapToInt(Seg::frames).sum());
        });
        // тики ждут, пока GIF идёт по кадрам
        steps.add(t -> gif == null);
    }

    /** Темп мира, тиков в секунду; команда — только при перемене больше чем на десятую. */
    private void tickRate(double rate) {
        double r = Math.max(1, Math.min(60, rate));
        if (Math.abs(r - tickRate) <= tickRate * 0.1) return;
        tickRate = r;
        cmd(String.format(Locale.ROOT, "tick rate %.1f", r));
    }

    private static double ease(double t) {
        return t < 0.5 ? 2 * t * t : 1 - Math.pow(-2 * t + 2, 2) / 2;
    }

    private void ring() {
        ringAt = gifFrame;
        ringX = mx;
        ringY = my;
    }

    // ---------------------------------------------------------------- GIF: кадры

    private void onFramePre(RenderFrameEvent.Pre e) {
        frames++;
        if (gif == null || mc.player == null) return;
        if (pace > 0 && gifFrame % 6 == 0) {
            long now = System.nanoTime();
            if (paceNanos != 0) tickRate(6e9 / (now - paceNanos) * 20 * pace / FPS);
            paceNanos = now;
        }
        Seg s = gif.get(segIndex);
        s.frame().accept(segFrame);
        if (++segFrame >= s.frames()) {
            segIndex++;
            segFrame = 0;
        }
        mouse(mx, my);
    }

    private void onFramePost(RenderFrameEvent.Post e) {
        if (gif == null || mc.player == null) return;
        grab(String.format(Locale.ROOT, "gif-%s-%04d", gifName, gifFrame++));
        if (segIndex >= gif.size()) {
            Airstrike.LOG.info("SCENARIO guide: GIF {} снят, {} кадров", gifName, gifFrame);
            gif = null;
            if (pace > 0) tickRate(20);
            pace = 0;
        }
    }

    /** Подпись клавиши в GIF мира (без экрана) — внизу слева, крупнее текста игры. */
    private void onGuiRender(RenderGuiEvent.Post e) {
        if (gif == null || mc.screen != null || hint == null || gifFrame >= hintUntil) return;
        GuiGraphics g = e.getGuiGraphics();
        // слои HUD уходят вверх по z, а прямоугольники и текст GUI рисуются с проверкой глубины (её включает сам тип
        // отрисовки): подпись под биноклем не видна. Глубину — сбросить, тогда подпись поверх всего
        g.flush();
        RenderSystem.clear(GL11.GL_DEPTH_BUFFER_BIT, Minecraft.ON_OSX);
        g.pose().pushPose();
        g.pose().translate(10, g.guiHeight() - 44, 0);
        g.pose().scale(1.5f, 1.5f, 1);
        int w = mc.font.width(hint);
        g.fill(-4, -4, w + 4, 12, 0xD0101010);
        g.drawString(mc.font, hint, 0, 0, 0xFFFFE070, false);
        g.pose().popPose();
        g.flush();
    }

    /** Курсор, подпись клавиши и круг щелчка — поверх экрана. */
    private void onScreenRender(ScreenEvent.Render.Post e) {
        if (gif == null || !cursor) return;
        GuiGraphics g = e.getGuiGraphics();
        g.pose().pushPose();
        g.pose().translate(0, 0, 500);
        int age = gifFrame - ringAt;
        if (age >= 0 && age < 6) {
            double r = 3 + age * 1.6;
            int alpha = 255 - age * 40;
            for (int k = 0; k < 28; k++) {
                double a = k * Math.PI * 2 / 28;
                int x = (int) Math.round(ringX + r * Math.cos(a)), y = (int) Math.round(ringY + r * Math.sin(a));
                g.fill(x, y, x + 1, y + 1, alpha << 24 | 0xFFD040);
            }
        }
        if (hint != null && gifFrame < hintUntil) {
            int w = mc.font.width(hint);
            int x = (int) mx + 12, y = (int) my + 14;
            g.fill(x - 3, y - 3, x + w + 3, y + 11, 0xD0101010);
            g.drawString(mc.font, hint, x, y, 0xFFFFE070, false);
        }
        drawCursor(g, (int) Math.round(mx), (int) Math.round(my));
        g.pose().popPose();
    }

    /** Стрелка мыши: «X» — контур, «.» — заливка. */
    private static final String[] ARROW = {
            "X", "XX", "X.X", "X..X", "X...X", "X....X", "X.....X", "X......X", "X.......X", "X........X",
            "X.....XXXXX", "X..X..X", "X.X X..X", "XX  X..X", "X    X..X", "     X..X", "      XX"};

    private static void drawCursor(GuiGraphics g, int x, int y) {
        for (int row = 0; row < ARROW.length; row++) {
            String line = ARROW[row];
            for (int col = 0; col < line.length(); col++) {
                char c = line.charAt(col);
                if (c == 'X') g.fill(x + col, y + row, x + col + 1, y + row + 1, 0xFF000000);
                else if (c == '.') g.fill(x + col, y + row, x + col + 1, y + row + 1, 0xFFFFFFFF);
            }
        }
    }

    /** Мышь игры в точку GUI: экран рисует наведение по ней. Без окна событий мыши нет — поле ставится прямо. */
    private void mouse(double guiX, double guiY) {
        mx = guiX;
        my = guiY;
        var w = mc.getWindow();
        try {
            Field x = MouseHandler.class.getDeclaredField("xpos"), y = MouseHandler.class.getDeclaredField("ypos");
            x.setAccessible(true);
            y.setAccessible(true);
            x.setDouble(mc.mouseHandler, guiX * w.getScreenWidth() / w.getGuiScaledWidth());
            y.setDouble(mc.mouseHandler, guiY * w.getScreenHeight() / w.getGuiScaledHeight());
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
    }

    // ---------------------------------------------------------------- шаги

    private void onTick(ClientTickEvent.Post e) {
        if (mc.player == null || mc.level == null) return;
        tick++;
        while (!steps.isEmpty()) {
            if (!steps.peek().done(stepTick++)) return;
            steps.poll();
            stepTick = 0;
        }
    }

    private void run(Runnable r) {
        steps.add(t -> {
            r.run();
            return true;
        });
    }

    private void await(int ticks) {
        steps.add(t -> t >= ticks);
    }

    private void until(String what, int max, BooleanSupplier ok) {
        steps.add(t -> {
            if (ok.getAsBoolean()) return true;
            if (t < max) return false;
            Airstrike.LOG.warn("SCENARIO guide: не дождался {} за {} тиков", what, max);
            return true;
        });
    }

    /**
     * Кадр после перемены: без окна клиент рисует ~5 кадров в секунду, тики идут пачками между кадрами — снимок сразу
     * после перемены берёт кадр до неё. Ждать, пока выйдут три кадра.
     */
    private void settle() {
        int[] start = new int[1];
        run(() -> start[0] = frames);
        steps.add(t -> frames >= start[0] + 3);
    }

    private void shot(String name) {
        run(() -> grab(name));
    }

    private void grab(String name) {
        mc.getToasts().clear(); // «новые рецепты», достижения — не в кадр
        Screenshot.grab(mc.gameDirectory, "guide-" + name + ".png", mc.getMainRenderTarget(), c -> {});
    }

    private void onServer(Consumer<MinecraftServer> r) {
        MinecraftServer server = mc.getSingleplayerServer();
        server.execute(() -> r.accept(server));
    }

    private ServerPlayer player(MinecraftServer server) {
        return server.getPlayerList().getPlayer(mc.player.getUUID());
    }

    private static void setblock(BlockPos p, String block) {
        cmd(String.format(Locale.ROOT, "setblock %d %d %d %s", p.getX(), p.getY(), p.getZ(), block));
    }

    private static void cmd(String c) {
        Minecraft.getInstance().player.connection.sendCommand(c);
        Airstrike.LOG.info("SCENARIO /{}", c);
    }

    private static String xyz(Vec3 v) {
        return String.format(Locale.ROOT, "%.0f %.0f %.0f", v.x, v.y, v.z);
    }
}
