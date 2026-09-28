package ua.zentix.airstrike.client.cam;

import net.minecraft.ChatFormatting;
import net.minecraft.client.CameraType;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Marker;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.MovementInputUpdateEvent;
import net.neoforged.neoforge.client.event.RenderHandEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;
import ua.zentix.airstrike.client.hud.ClientFlights;
import ua.zentix.airstrike.client.hud.StrikesHud;
import ua.zentix.airstrike.client.nuclear.NukeView;
import ua.zentix.airstrike.entity.FlightPhase;
import ua.zentix.airstrike.entity.StrikeProjectile;
import ua.zentix.airstrike.net.C2S;
import ua.zentix.airstrike.target.Target;
import ua.zentix.airstrike.target.TargetPicker;
import ua.zentix.airstrike.util.Local;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Камера снаряда: картинка с его стабилизированной камеры (как у барражирующих боеприпасов). Клавиша камеры —
 * к ближайшему по времени снаряду, ещё раз — к следующему, после последнего — обратно к себе; Shift — выход.
 * Смотреть можно мышью (камера на подвесе), ЛКМ — перенацелить снаряд на то, что в перекрестии.
 * <p>Как в кино, три плана. <b>Пуск</b>: пока снаряд на пусковой и на ускорителе, камера стоит сбоку от пусковой
 * и ведёт его длинным фокусом. <b>Борт</b>: после отделения ускорителя — вид с борта. <b>Попадание</b>: в момент
 * удара — полсекунды помех, затем камера со стороны захода медленно облетает взрыв несколько секунд; дальше
 * следующий снаряд залпа или возврат к себе. Пока снаряда нет на клиенте (дальше прорисовки или вне загруженного
 * мира) — {@link TacticalMap}: карта по телеметрии сервера; видео включается само, как только сервер пришлёт
 * сущность. F5 на борту — вид со стороны. Игрок в это время стоит на месте.
 * Съёмочная камера — клиентская сущность-маркер, её нет в мире.
 */
public final class ProjectileCamera {
    /** Сколько тиков держатся помехи после потери связи. */
    private static final int LOST_TICKS = 30;
    /** Попадание: помехи, потом облёт взрыва (тиков). */
    private static final int IMPACT_STATIC = 8, IMPACT_TICKS = 110;
    /** Снаряд пропал ближе этого к цели или в пике — это попадание, а не уход из зоны связи. */
    private static final double IMPACT_RANGE = 40;

    private enum Shot { ONBOARD, LAUNCH, IMPACT, WAIT }
    private static final RandomSource NOISE = RandomSource.create();

    @Nullable
    private static UUID following;
    private static boolean active;
    private static int lostTicks;
    private static float savedYaw, savedPitch;
    private static CameraType savedCamera = CameraType.FIRST_PERSON;
    private static boolean shiftWasDown;
    /** Куда перенацелили (для метки), тиков показа. */
    @Nullable
    private static Vec3 newTarget;
    private static int newTargetTicks;

    private static Shot shot = Shot.WAIT;
    /** Съёмочная камера (пуск и попадание). */
    @Nullable
    private static Marker rig;
    /** Где снаряд стоял на пусковой — отсюда снимаем пуск. */
    @Nullable
    private static Vec3 pad;
    private static float padYaw;
    /** Последнее, что видели: положение, курс, фаза — чтобы понять, что это было попадание, и снять его. */
    @Nullable
    private static Vec3 lastPos, lastDir;
    private static boolean lastTerminal;
    private static int impactTick;
    @Nullable
    private static Vec3 impactAt;
    private static float orbit;
    /** Снаряд, который сейчас на карте (видео нет): если он пропадёт, карта покажет, где и чем кончилось. */
    @Nullable
    private static ClientFlights.Tracked mapped;
    /** Снаряды, уже виденные (для автокамеры: новый снаряд — сразу на пуск). */
    private static final java.util.Set<UUID> SEEN = new java.util.HashSet<>();

    private ProjectileCamera() {}

    public static boolean isActive() {
        return active;
    }

    /** Камера сейчас смотрит глазами снаряда (а не ждёт связи и не снимает со стороны). */
    public static boolean isViewing() {
        Minecraft mc = Minecraft.getInstance();
        return active && mc.getCameraEntity() instanceof StrikeProjectile;
    }

    /** Камера снимает со стороны: пуск или попадание. */
    public static boolean isFilming() {
        return active && (shot == Shot.LAUNCH || shot == Shot.IMPACT && impactTick >= IMPACT_STATIC);
    }

    /** Клавиша камеры: к ближайшему снаряду, к следующему, после последнего — к себе. */
    public static void cycle() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) return;
        List<ClientFlights.Tracked> flights = ClientFlights.all();
        if (flights.isEmpty()) {
            if (active) exit();
            else player.displayClientMessage(Component.translatable("airstrike.camera.none").withStyle(ChatFormatting.GRAY), true);
            return;
        }
        int idx = -1;
        if (active && following != null) {
            for (int i = 0; i < flights.size(); i++) {
                if (flights.get(i).id.equals(following)) idx = i;
            }
            if (idx == flights.size() - 1) {
                exit();
                return;
            }
        }
        follow(flights.get(idx + 1));
    }

    private static void follow(ClientFlights.Tracked f) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) return;
        if (!active) {
            savedYaw = player.getYRot();
            savedPitch = player.getXRot();
            savedCamera = mc.options.getCameraType();
            mc.options.setCameraType(CameraType.FIRST_PERSON);
        }
        active = true;
        following = f.id;
        lostTicks = 0;
        newTarget = null;
        shot = Shot.WAIT;
        pad = null;
        lastPos = lastDir = null;
        lastTerminal = false;
        mapped = null;
        TacticalMap.reset();
        StrikeProjectile p = f.entity();
        if (p != null) aimAlong(player, p);
        mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK.value(), 1.6f, 0.4f));
    }

    /** Взгляд — по курсу снаряда, дальше мышь водит камеру на подвесе. */
    private static void aimAlong(LocalPlayer player, StrikeProjectile p) {
        player.setYRot(p.getYRot());
        player.setXRot(Mth.clamp(p.getXRot() + 8, -89, 89));
        player.yRotO = player.getYRot();
        player.xRotO = player.getXRot();
    }

    public static void exit() {
        Minecraft mc = Minecraft.getInstance();
        if (!active) return;
        active = false;
        following = null;
        mapped = null;
        lostTicks = 0;
        shot = Shot.WAIT;
        rig = null;
        if (mc.player != null) {
            mc.setCameraEntity(mc.player);
            mc.player.setYRot(savedYaw);
            mc.player.setXRot(savedPitch);
        }
        mc.options.setCameraType(savedCamera);
    }

    public static void tick() {
        Minecraft mc = Minecraft.getInstance();
        autoFollow(mc);
        if (!active) return;
        if (mc.player == null || mc.level == null) {
            active = false;
            following = null;
            return;
        }
        boolean shift = mc.options.keyShift.isDown();
        if (shift && !shiftWasDown) {
            shiftWasDown = true;
            exit();
            return;
        }
        shiftWasDown = shift;
        if (newTargetTicks > 0) newTargetTicks--;

        if (shot == Shot.IMPACT) {
            impactTick++;
            if (impactTick == IMPACT_STATIC) film(mc, impactAt, 0);
            if (impactTick >= IMPACT_STATIC) orbitImpact(mc);
            if (impactTick >= IMPACT_STATIC + IMPACT_TICKS) next(mc);
            return;
        }

        ClientFlights.Tracked f = following == null ? null : ClientFlights.find(following);
        StrikeProjectile p = f == null ? null : f.entity();
        if (p == null && lastPos != null && (lastTerminal || f == null || lastPos.distanceTo(f.target()) < IMPACT_RANGE)) {
            // снаряд пропал у цели или в пике: попадание
            startImpact(mc, f == null ? lastPos : nearer(lastPos, f.target()));
            return;
        }
        if (f == null) {
            // ушёл без попадания (сбит, отбой): помехи, потом следующий снаряд залпа или к себе
            if (lostTicks == 0) mc.setCameraEntity(mc.player);
            shot = Shot.WAIT;
            if (++lostTicks >= LOST_TICKS) next(mc);
            return;
        }
        // на пусковой снаряд ещё спрятан в контейнере, пока её разворачивают, — но пусковую уже снимаем
        if (p != null && (p.isActive() || p.flightPhase().onLauncher())) {
            mapped = null;
            if (p.isActive()) {
                lastPos = p.position();
                Vec3 v = p.position().subtract(p.xo, p.yo, p.zo);
                if (v.lengthSqr() > 1.0e-4) lastDir = v.normalize();
                lastTerminal = p.flightPhase() == FlightPhase.TERMINAL || p.flightPhase() == FlightPhase.DRILL;
            }
            if (pad == null && p.flightPhase().onLauncher()) {
                pad = p.position();
                padYaw = p.getYRot();
            }
            boolean launch = pad != null && (p.flightPhase().launching() || p.flightPhase() == FlightPhase.CLIMB && p.phaseAge() < 25);
            if (launch) {
                if (shot != Shot.LAUNCH) {
                    shot = Shot.LAUNCH;
                    // сбоку от пусковой, чуть впереди и выше: пакет, факел и сход — в одном кадре, пакет не заслоняет
                    film(mc, Local.at(pad, padYaw, 0, 24, 5, 2), 0);
                    lookAt(pad, 1);
                }
                trackRig(p.getPosition(1));
            } else {
                if (shot != Shot.ONBOARD) {
                    if (shot == Shot.LAUNCH) aimAlong(mc.player, p);
                    shot = Shot.ONBOARD;
                }
                if (mc.getCameraEntity() != p) mc.setCameraEntity(p);
            }
        } else {
            // вне зоны видео: что видели раньше, уже не место попадания; пока — карта
            shot = Shot.WAIT;
            lastPos = null;
            if (mc.getCameraEntity() != mc.player) mc.setCameraEntity(mc.player);
            mapped = f;
            TacticalMap.tick(f);
        }
    }

    /** Автокамера (настройка клиента): новый свой снаряд — сразу к нему, начиная с пуска. */
    private static void autoFollow(Minecraft mc) {
        List<ClientFlights.Tracked> all = ClientFlights.all();
        if (all.isEmpty()) {
            SEEN.clear();
            return;
        }
        ClientFlights.Tracked fresh = null;
        for (ClientFlights.Tracked t : all) {
            if (SEEN.add(t.id) && fresh == null) fresh = t;
        }
        if (fresh != null && !active && mc.screen == null && mc.player != null
                && ua.zentix.airstrike.AirstrikeConfig.CLIENT.autoCamera.get()) {
            follow(fresh);
        }
    }

    /** Следующий снаряд залпа или к себе. */
    private static void next(Minecraft mc) {
        List<ClientFlights.Tracked> rest = ClientFlights.all();
        if (rest.isEmpty()) {
            exit();
            return;
        }
        follow(rest.get(0));
        if (mc.player != null && mc.getCameraEntity() != mc.player) mc.setCameraEntity(mc.player);
    }

    private static Vec3 nearer(Vec3 last, Vec3 target) {
        // снаряд виден с опозданием на тик-два: если цель рядом, взрыв — у неё
        return last.distanceTo(target) < IMPACT_RANGE ? last.lerp(target, 0.5) : last;
    }

    /** Попадание: помехи, потом облёт со стороны захода. */
    private static void startImpact(Minecraft mc, Vec3 at) {
        shot = Shot.IMPACT;
        impactTick = 0;
        impactAt = at;
        Vec3 d = lastDir == null ? new Vec3(0, 0, 1) : lastDir;
        orbit = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
        if (mc.player != null) mc.setCameraEntity(mc.player);
    }

    /** Облёт взрыва: радиус 42→32 блока, 14–18 над точкой (не ниже рельефа), медленно наезжая. */
    private static void orbitImpact(Minecraft mc) {
        if (rig == null || impactAt == null || mc.level == null) return;
        float t = (impactTick - IMPACT_STATIC) / (float) IMPACT_TICKS;
        orbit += 0.35f;
        double r = 42 - 10 * t, rad = Math.toRadians(orbit);
        Vec3 behind = new Vec3(Math.sin(rad) * r, 0, -Math.cos(rad) * r);
        Vec3 at = impactAt.add(behind).add(0, 14 + 4 * t, 0);
        int x = Mth.floor(at.x), z = Mth.floor(at.z);
        if (mc.level.hasChunk(x >> 4, z >> 4)) {
            double ground = mc.level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, x, z);
            at = new Vec3(at.x, Math.max(at.y, ground + 4), at.z);
        }
        moveRig(at);
        lookAt(impactAt.add(0, 6, 0), 0.5f);
    }

    /** Поставить съёмочную камеру и сделать её камерой клиента. */
    private static void film(Minecraft mc, @Nullable Vec3 at, float yaw) {
        if (mc.level == null || mc.player == null || at == null) return;
        if (rig == null || rig.level() != mc.level) rig = EntityType.MARKER.create(mc.level);
        if (rig == null) return;
        rig.moveTo(at.x, at.y, at.z, yaw, 0);
        rig.setOldPosAndRot();
        mc.setCameraEntity(rig);
    }

    private static void moveRig(Vec3 at) {
        if (rig == null) return;
        rig.setOldPosAndRot();
        rig.setPos(at);
    }

    /** Съёмочная камера смотрит на точку (поворот плавный: камера на штативе, а не прибита к цели). */
    private static void trackRig(Vec3 target) {
        if (rig == null) return;
        rig.setOldPosAndRot();
        // жёстко: ракета после ускорителя проходит по 10 блоков за тик
        lookAt(target, 0.8f);
    }

    /** Повернуть камеру к точке, за тик — долю {@code k} оставшегося угла. */
    private static void lookAt(Vec3 target, float k) {
        if (rig == null) return;
        Vec3 d = target.subtract(rig.position());
        float yaw = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
        float pitch = (float) -Math.toDegrees(Math.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z)));
        rig.setYRot(rig.getYRot() + Mth.wrapDegrees(yaw - rig.getYRot()) * k);
        rig.setXRot(Mth.lerp(k, rig.getXRot(), pitch));
    }

    /** Камера на подвесе: направление — взгляд игрока, крен — половина крена снаряда; съёмочная — куда смотрит она. */
    public static void angles(ViewportEvent.ComputeCameraAngles e) {
        Minecraft mc = Minecraft.getInstance();
        if (!active || mc.player == null) return;
        if (rig != null && mc.getCameraEntity() == rig) {
            float pt = (float) e.getPartialTick();
            e.setYaw(rig.getViewYRot(pt));
            e.setPitch(rig.getViewXRot(pt));
            e.setRoll(0);
            return;
        }
        if (!(mc.getCameraEntity() instanceof StrikeProjectile p)) return;
        float pt = (float) e.getPartialTick();
        e.setYaw(mc.player.getViewYRot(pt));
        e.setPitch(mc.player.getViewXRot(pt));
        e.setRoll(p.roll() * 0.5f);
    }

    /** Узкий угол зрения — как у камеры с зумом; пуск — длинный фокус, облёт взрыва — наезд. */
    public static void fov(ViewportEvent.ComputeFov e) {
        if (isViewing()) e.setFOV(e.getFOV() * 0.75);
        else if (active && shot == Shot.LAUNCH) e.setFOV(e.getFOV() * 0.7);
        else if (isFilming()) e.setFOV(e.getFOV() * (0.8 - 0.15 * Math.min(1, (impactTick - IMPACT_STATIC) / (double) IMPACT_TICKS)));
    }

    /** Игрок стоит, пока смотрит в камеру. */
    public static void movement(MovementInputUpdateEvent e) {
        if (!active) return;
        var in = e.getInput();
        in.forwardImpulse = 0;
        in.leftImpulse = 0;
        in.up = in.down = in.left = in.right = in.jumping = in.shiftKeyDown = false;
    }

    public static void hideHand(RenderHandEvent e) {
        if (active) e.setCanceled(true);
    }

    /** ЛКМ в камере — новая цель; прочие действия руками отключены. */
    public static void click(InputEvent.InteractionKeyMappingTriggered e) {
        if (!active) return;
        e.setCanceled(true);
        e.setSwingHand(false);
        if (e.isAttack()) retarget();
    }

    private static void retarget() {
        Minecraft mc = Minecraft.getInstance();
        if (!(mc.getCameraEntity() instanceof StrikeProjectile p) || mc.level == null || mc.player == null) return;
        Vec3 eye = mc.gameRenderer.getMainCamera().getPosition();
        Vec3 look = Vec3.directionFromRotation(mc.player.getXRot(), mc.player.getYRot());
        TargetPicker.Pick pick = TargetPicker.pick(mc.level, p, eye, look, 1024);
        if (pick == null) {
            mc.player.displayClientMessage(Component.translatable("airstrike.target_not_found").withStyle(ChatFormatting.RED), true);
            return;
        }
        C2S.AimHint hint;
        if (pick.entity() != null) {
            hint = new C2S.AimHint(C2S.AimHint.ENTITY, pick.point(), pick.entity().getId(), Vec3.ZERO);
        } else if (pick.target() instanceof Target.OfSubLevel s) {
            hint = new C2S.AimHint(C2S.AimHint.AIRCRAFT, pick.point(), 0, s.plotPos());
        } else {
            hint = new C2S.AimHint(C2S.AimHint.POINT, pick.point(), 0, Vec3.ZERO);
        }
        PacketDistributor.sendToServer(new C2S.Retarget(p.getUUID(), hint));
        newTarget = pick.point();
        newTargetTicks = 40;
        mc.getSoundManager().play(SimpleSoundInstance.forUI(ua.zentix.airstrike.registry.ModSounds.DESIGNATOR_LOCK.get(), 1.0f, 0.8f));
    }

    public static void reset() {
        if (active) exit();
        active = false;
        following = null;
        mapped = null;
        rig = null;
        SEEN.clear();
    }

    // ---------------------------------------------------------------- картинка

    /** Видеоканал: рамка, перекрестие, телеметрия, шум; нет видео — карта, удар — помехи на весь экран. */
    public static void render(GuiGraphics g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        if (!active || mc.player == null) return;
        int w = g.guiWidth(), h = g.guiHeight(), cx = w / 2, cy = h / 2;
        Font font = mc.font;
        float pt = delta.getGameTimeDeltaPartialTick(false);
        ClientFlights.Tracked f = following == null ? null : ClientFlights.find(following);

        if (shot == Shot.IMPACT) {
            if (impactTick < IMPACT_STATIC) {
                // удар: сигнал срывается
                staticNoise(g, w, h, 1.0f);
                big(g, font, Component.translatable("airstrike.camera.lost").withStyle(ChatFormatting.RED, ChatFormatting.BOLD), cx, cy - 6, 2f);
            } else {
                letterbox(g, w, h);
                caption(g, font, Component.translatable("airstrike.camera.impact").withStyle(ChatFormatting.RED, ChatFormatting.BOLD), w, h);
            }
            return;
        }
        if (shot == Shot.LAUNCH && f != null) {
            letterbox(g, w, h);
            caption(g, font, Component.translatable("airstrike.camera.launch", f.weapon().displayName(), f.number,
                    Component.translatable("airstrike.phase." + f.phase().getSerializedName())).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD), w, h);
            hint(g, font, w, h);
            return;
        }
        if (f == null && mapped != null) {
            // пропал с карты: у цели — попадание, иначе (сбит, отбой) — связь потеряна
            TacticalMap.render(g, font, mapped, pt);
            boolean hit = mapped.phase() == FlightPhase.TERMINAL || mapped.phase() == FlightPhase.DRILL
                    || mapped.position(pt).distanceTo(mapped.target()) < IMPACT_RANGE;
            Component end = Component.translatable(hit ? "airstrike.map.hit" : "airstrike.camera.lost").withStyle(ChatFormatting.RED, ChatFormatting.BOLD);
            big(g, font, end, cx, cy - 30, 2f);
            return;
        }
        if (f == null) {
            staticNoise(g, w, h, 1.0f);
            Component lost = Component.translatable("airstrike.camera.lost").withStyle(ChatFormatting.RED, ChatFormatting.BOLD);
            big(g, font, lost, cx, cy - 6, 2f);
            return;
        }
        if (!(mc.getCameraEntity() instanceof StrikeProjectile p)) {
            // снаряда нет на клиенте (дальше прорисовки или вне загруженного мира): карта по телеметрии сервера
            TacticalMap.render(g, font, f, pt);
            hint(g, font, w, h);
            return;
        }

        int c = 0xE0E8E8E8;
        int m = 16, l = 18;
        // F5 — вид со стороны: без «видеоканала», только телеметрия и прицел
        boolean feed = mc.options.getCameraType().isFirstPerson();
        if (feed) {
            // лёгкие помехи и строчная развёртка, как у аналогового видеоканала
            staticNoise(g, w, h, 0.05f);
            for (int y = 0; y < h; y += 3) g.fill(0, y, w, y + 1, 0x14000000);
        }
        // уголки кадра
        g.fill(m, m, m + l, m + 1, c);
        g.fill(m, m, m + 1, m + l, c);
        g.fill(w - m - l, m, w - m, m + 1, c);
        g.fill(w - m - 1, m, w - m, m + l, c);
        g.fill(m, h - m - 1, m + l, h - m, c);
        g.fill(m, h - m - l, m + 1, h - m, c);
        g.fill(w - m - l, h - m - 1, w - m, h - m, c);
        g.fill(w - m - 1, h - m - l, w - m, h - m, c);
        // перекрестие с разрывом
        g.fill(cx - 22, cy, cx - 5, cy + 1, c);
        g.fill(cx + 5, cy, cx + 22, cy + 1, c);
        g.fill(cx, cy - 22, cx + 1, cy - 5, c);
        g.fill(cx, cy + 5, cx + 1, cy + 22, c);
        g.fill(cx - 1, cy - 1, cx + 2, cy + 2, 0xFFFF4030);

        // метка цели
        target(g, f.target(), w, h, 0xFFFF3030);
        if (newTarget != null && newTargetTicks > 0 && (newTargetTicks / 4) % 2 == 0) target(g, newTarget, w, h, 0xFF30FF60);

        // телеметрия
        double alt = p.getY() - ua.zentix.airstrike.entity.StrikeProjectile.surfaceY(mc.level, p.getX(), p.getZ());
        double kmh = p.speed() * 20 * 3.6;
        double dist = p.position().distanceTo(f.target());
        boolean rec = (mc.level.getGameTime() / 10) % 2 == 0;
        String left1 = (rec ? "● " : "  ") + "REC  " + f.weapon().displayName().getString().toUpperCase(Locale.ROOT) + " №" + f.number;
        g.drawString(font, left1, m + 6, m + 6, rec ? 0xFFFF4040 : 0xFFE8E8E8);
        g.drawString(font, Component.translatable("airstrike.phase." + f.phase().getSerializedName()).getString().toUpperCase(Locale.ROOT),
                m + 6, m + 18, f.phase() == FlightPhase.TERMINAL ? 0xFFFF4040 : 0xFFE8E8E8);
        Component[] right = {
                Component.translatable("airstrike.camera.alt", String.format(Locale.ROOT, "%.0f", alt)),
                Component.translatable("airstrike.camera.speed", String.format(Locale.ROOT, "%.0f", kmh)),
                Component.translatable("airstrike.camera.range", String.format(Locale.ROOT, "%.0f", dist)),
                Component.literal("T-" + StrikesHud.clock(f.etaSeconds(pt)))
        };
        for (int i = 0; i < right.length; i++) {
            g.drawString(font, right[i], w - m - 6 - font.width(right[i]), m + 6 + 12 * i, i == 3 ? 0xFFFFC040 : 0xFFE8E8E8);
        }
        if (f.nuclear()) g.drawString(font, "☢", cx - font.width("☢") / 2, m + 6, 0xFFFFD020);
        if (f.phase() == FlightPhase.LOITER && rec) {
            // барражирующий кружит: оператор ищет цель в кадре
            Component loiter = Component.translatable("airstrike.camera.loiter").withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD);
            g.drawString(font, loiter, cx - font.width(loiter) / 2, h - 48, 0xFFFFFFFF);
        }
        hint(g, font, w, h);
    }

    /** Кинокадр 2.39:1 — чёрные полосы сверху и снизу. */
    private static void letterbox(GuiGraphics g, int w, int h) {
        int bar = Math.max(0, (h - (int) (w / 2.39)) / 2);
        g.fill(0, 0, w, bar, 0xFF000000);
        g.fill(0, h - bar, w, h, 0xFF000000);
    }

    private static void caption(GuiGraphics g, Font font, Component text, int w, int h) {
        int bar = Math.max(0, (h - (int) (w / 2.39)) / 2);
        g.drawString(font, text, 20, Math.max(6, bar / 2 - 4), 0xFFFFFFFF);
    }

    private static void hint(GuiGraphics g, Font font, int w, int h) {
        Component hint = Component.translatable("airstrike.camera.hint", ua.zentix.airstrike.client.Keys.CAMERA.getTranslatedKeyMessage())
                .withStyle(ChatFormatting.GRAY);
        g.drawString(font, hint, w / 2 - font.width(hint) / 2, h - 34, 0xFFFFFFFF);
    }

    private static void target(GuiGraphics g, Vec3 at, int w, int h, int color) {
        float[] s = NukeView.project(at);
        if (s == null) return;
        int x = (int) (s[0] * w), y = (int) (s[1] * h);
        if (x < 0 || x > w || y < 0 || y > h) return;
        ua.zentix.airstrike.client.hud.HudDraw.box(g, x, y, 7, color);
    }

    private static void big(GuiGraphics g, Font font, Component text, int cx, int y, float scale) {
        g.pose().pushPose();
        g.pose().translate(cx, y, 0);
        g.pose().scale(scale, scale, 1);
        g.drawString(font, text, -font.width(text) / 2, 0, 0xFFFFFFFF);
        g.pose().popPose();
    }

    /** Помехи: случайные серые штрихи, {@code amount} — доля экрана. */
    private static void staticNoise(GuiGraphics g, int w, int h, float amount) {
        int n = (int) (w * h / 40 * amount);
        for (int i = 0; i < n; i++) {
            int x = NOISE.nextInt(w), y = NOISE.nextInt(h);
            int v = NOISE.nextInt(200) + 55;
            int len = 1 + NOISE.nextInt(6);
            g.fill(x, y, x + len, y + 1, 0xFF000000 | v << 16 | v << 8 | v);
        }
    }
}
