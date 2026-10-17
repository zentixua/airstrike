package ua.zentix.incoming.client.nuclear;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import ua.zentix.incoming.client.fx.Fragments;
import ua.zentix.incoming.client.fx.particle.Fx;
import ua.zentix.incoming.client.fx.particle.FxBudget;
import ua.zentix.incoming.net.S2C;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.nuclear.model.BlastModel;

import java.util.ArrayList;
import java.util.List;

/**
 * Стена пыли ударной волны вблизи. Пока голова стены ближе {@link #NEAR} к камере — по её дуге у камеры летят клочья
 * мусора и куски верха земли. Блоки, которые снесла волна (сервер называет их, {@code S2C.NukeDebris}), разлетаются
 * кусками, когда до них доходит фронт; пока стена проходит мимо игрока — сорванное с земли, которое ветер несёт от
 * эпицентра. Саму пыль у камеры рисует пелена объёмом ({@link NukePall#drawsHead}), как голову стены вдали: одна среда —
 * один рисунок. Квадраты клубов в шаге-двух от глаза и шире кадра ложились бы на неё второй пылью — пиксели картинки
 * лестницей во весь экран, тёмные шары над верхом стены. Только когда пелена не рисуется (качество OFF, сломался шум),
 * пыль у камеры — запасная: клубы по дуге головы во всю её высоту, пыль из проломов, клубы вокруг игрока, пока голова
 * стены у него (не выше её верха), и пелена на весь экран (накрывает за несколько тиков, гаснет за несколько секунд).
 */
public final class NukeDust {
    private static final RandomSource RANDOM = RandomSource.create();
    /** Ближе стольких блоков к камере голова стены рождает свою пыль и обломки (дальше частицы не видны за ней самой). */
    static final double NEAR = 160;
    /** Шаг мест пыли вдоль дуги фронта у камеры, блоков. */
    private static final double STEP = 9;
    /** Мест пыли за тик — не больше. */
    private static final int MAX_SPOTS = 40;
    /** Сорванное с земли — где давление на фронте не меньше (скоростной напор ~0,1 psi), psi. */
    private static final double TEARS_PSI = 1.5;
    /** Пыль у фронта не быстрее, блоков за тик: у эпицентра фронт идёт десятками блоков за тик. */
    private static final double DUST_SPEED = 4;
    /**
     * Обломки снесённого: ближе стольких блоков к камере — все, дальше — реже, по квадрату расстояния (дальние чанки
     * иначе занимают все места кусков раньше, чем фронт дойдёт до ближних).
     */
    private static final double DEBRIS_FULL = 48;
    /** Руины чанка встали позже фронта больше чем на столько тиков — его обломки не летят: волна уже прошла. */
    private static final int DEBRIS_LATE = 20;
    /**
     * Тёмные клубы пыли (с землёй и сором) — доля цвета стены в начале и в конце жизни; дым при прохождении стены —
     * вполовину серее её и темнее.
     */
    private static final float DARK_FROM = 0.67f, DARK_TO = 0.74f, SMOKE_GREY = 0.5f, SMOKE_FROM = 0.55f, SMOKE_TO = 0.75f;
    /**
     * Пелена у глаза ({@link #veil}): на сколько пелена накрывает экран за тик и гаснет за тик (доля), её непрозрачность —
     * пыль на VEIL_DEPTH блоков перед глазом, не больше VEIL_MAX.
     */
    private static final float VEIL_RISE = 0.12f, VEIL_DECAY = 0.96f, VEIL_MAX = 0.85f;
    private static final double VEIL_DEPTH = 6;
    /** Пелена на экране, к чему она идёт (гаснет за тик на VEIL_DECAY) и какой её хочет голова у глаза в прошлом тике. */
    private static float veil, veilTarget, veilWanted;
    /** Цвет пелены над водой — серо-белые брызги; над сушей — цвет стены у глаза. */
    private static final float[] VEIL_SPRAY = {0xAE / 255f, 0xB8 / 255f, 0xBE / 255f};
    private static final float[] veilRgb = new float[3];
    /** Числа слота стены для запасных клубов вокруг игрока ({@link #gust}). */
    private static final float[] GUST_WALL = new float[BlastWall.SLOT];
    /** Проломы, до которых фронт ещё не дошёл: пыль из них встаёт в его тик ({@link #tick}). */
    private static final List<Breach> BREACHES = new ArrayList<>();

    /** Пролом: подрыв, место, направление от эпицентра, скорость напора (блоков за тик) и сколько тиков до фронта. */
    private static final class Breach {
        final ClientNuclear.Active a;
        final Vec3 at;
        final double ax, az, v;
        int wait;

        Breach(ClientNuclear.Active a, Vec3 at, double ax, double az, double v, int wait) {
            this.a = a;
            this.at = at;
            this.ax = ax;
            this.az = az;
            this.v = v;
            this.wait = wait;
        }
    }

    private NukeDust() {}

    /**
     * Голова стены у глаза (тик, {@link NukePall#tick}): средняя голова там с ослаблением {@code sigma} (1/блок) — пелена на
     * весь экран цветом стены у глаза ({@link NukePall#dustColor}), над водой — брызги. Только пока пелена выключена
     * (качество OFF) или не рисуется (сломался её шум): иначе голову у глаза рисует сама пелена, и экран темнел бы дважды. Где голова не дошла или ветер уже
     * не поднимает пыль (дальше ~0,3 psi до глаза доходит только звук), — ничего.
     */
    static void veil(ClientNuclear.Active a, Vec3 eye, double sigma, boolean spray) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null || sigma <= 0) return;
        if (spray) {
            System.arraycopy(VEIL_SPRAY, 0, veilRgb, 0, 3);
        } else {
            NukePall.Sky sky = new NukePall.Sky();
            NukePall.sky(level, eye, 0, sky);
            if (!NukePall.dustColor(a, sky, eye.x, eye.y, eye.z, veilRgb)) return;
        }
        veilWanted = Math.max(veilWanted, (float) Math.min(VEIL_MAX, 1 - Math.exp(-sigma * VEIL_DEPTH)));
    }

    /**
     * Тик: голова стены у камеры. Клубы встают по дуге фронта ближе {@link #NEAR} к камере — гуще у земли, выше — крупнее,
     * летят от эпицентра медленнее фронта и отстают от него шлейфом, а у земли поднимаются (вал); где волна сильнее,
     * с ними летят тёмные клочья мусора и куски верха земли (трава, песок, листва, снег). Рельеф — карта высот
     * загруженных чанков клиента (под потолком — высота эпицентра). Клубы — цветом стены в своём месте
     * ({@link #wallColor}: свет стены в кадре), а не по карте освещения.
     */
    static void front(ClientNuclear.Active a, Vec3 eye) {
        Detonation d = a.d;
        BlastWall.Wall w = a.wall.wall(a.seconds(0));
        if (w.density() < 0.05) return;
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) return;
        double ex = eye.x - d.burst().x, ez = eye.z - d.burst().z, l = Math.sqrt(ex * ex + ez * ez), r = w.head();
        if (Math.abs(l - r) > NEAR) return;
        // дуга фронта ближе NEAR к камере: её половина — угол от направления на камеру
        double half = l < 1 ? Math.PI : Math.acos(Mth.clamp((l * l + r * r - NEAR * NEAR) / (2 * l * r), -1, 1));
        double toward = Math.atan2(ez, ex);
        double ticks = a.ticks(0);
        double speed = Math.min(DUST_SPEED, d.frontRadius(ticks + 1) - d.frontRadius(ticks));
        // под потолком (Незер) верх карты высот — потолок: пыль — на высоте эпицентра, без кусков поверхности
        boolean ceiling = level.dimensionType().hasCeiling();
        int floor = level.getMinBuildHeight() + 1;
        int n = Math.min(MAX_SPOTS, (int) Math.round(2 * half * r / STEP * w.density() * Fx.density(eye)));
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        NukePall.Sky sky = new NukePall.Sky();
        NukePall.sky(level, eye, 0, sky);
        float[] from = new float[3], to = new float[3];
        boolean puffs = !NukePall.drawsHead();
        for (int i = 0; i < n; i++) {
            double ang = toward + (RANDOM.nextDouble() * 2 - 1) * half;
            double cos = Math.cos(ang), sin = Math.sin(ang);
            double rr = r - RANDOM.nextDouble() * Math.min(12, w.height() * 0.3);
            double x = d.burst().x + cos * rr, z = d.burst().z + sin * rr;
            if ((x - eye.x) * (x - eye.x) + (z - eye.z) * (z - eye.z) > NEAR * NEAR) continue;
            int bx = Mth.floor(x), bz = Mth.floor(z);
            LevelChunk chunk = level.getChunkSource().getChunk(bx >> 4, bz >> 4, false);
            if (chunk == null) continue;
            // над водой пыли нет: там брызги (NukeWater)
            if (!ceiling && NukeWater.water(chunk, bx, bz, m)) continue;
            int top = ceiling ? floor : chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, bx & 15, bz & 15) + 1;
            boolean surface = top > floor;
            if (!surface) top = Mth.floor(d.groundY());
            if (puffs) {
                // гуще у земли; клуб выше — крупнее, у земли — поднимается, наверху — уходит назад со стеной
                double up = Math.pow(RANDOM.nextDouble(), 1.6), h = w.height() * 0.8 * up;
                double v = speed * (0.25 + 0.45 * RANDOM.nextDouble());
                boolean dark = RANDOM.nextFloat() < 0.35f;
                // цвет головы стены в месте клуба; рассеиваясь, клуб светлеет, как она выше
                double y = top + 0.5 + h;
                if (!wallColor(a, sky, x, y, z, dark ? DARK_FROM : 1, from)
                        || !wallColor(a, sky, x, y + 0.4 * w.height(), z, 1.05f * (dark ? DARK_TO : 1), to)) continue;
                Fx.smoke().vel(cos * v, (0.04 + 0.2 * RANDOM.nextDouble()) * (1 - up), sin * v)
                        .size((float) (1.5 + 0.06 * h), (float) (5 + Math.min(10, w.height() * 0.08) + 3 * RANDOM.nextDouble())).growFast()
                        .life(50 + RANDOM.nextInt(50)).color(from, to).prelit().alpha(0.8f).drag(0.95f).smooth()
                        .collide().wind(0).fadeIn(3).fadeFrom(0.45f).budget(FxBudget.GROUND).spawn(level, x, y, z);
            }
            double kpa = d.overpressureKpa(new Vec3(x, top, z));
            double psi = BlastModel.psi(kpa);
            if (psi < 1) continue;
            double thrown = Detonation.throwSpeed(kpa);
            if (RANDOM.nextFloat() < 0.6f) {
                // клочья мусора: мелкие, тёмные, тяжёлые — летят дальше пыли и падают
                double u = thrown * (0.4 + 0.7 * RANDOM.nextDouble());
                Fx.smoke().vel(cos * u, u * (0.2 + 0.4 * RANDOM.nextDouble()), sin * u).size(0.12f, 0.2f).life(30 + RANDOM.nextInt(30))
                        .color(0x2A2420, 0x3A322C).alpha(0.95f).drag(0.97f).gravity(0.05f).collide().wind(0).fadeIn(0).fadeFrom(0.8f).spin(0.3f)
                        .budget(FxBudget.DEBRIS).spawn(level, x, top + 0.5 + RANDOM.nextDouble() * 2, z);
            }
            if (surface && psi >= TEARS_PSI && RANDOM.nextFloat() < 0.5f) {
                m.set(bx, top - 1, bz);
                double u = thrown * (0.4 + 0.6 * RANDOM.nextDouble());
                Fragments.launch(level, chunk.getBlockState(m), m, x, top + 0.3, z, cos * u, u * (0.25 + 0.4 * RANDOM.nextDouble()) + 0.1, sin * u,
                        0.15f + 0.3f * RANDOM.nextFloat(), 0);
            }
        }
    }

    /**
     * Блоки, которые снесла волна (сервер — тем, кто видит чанк): каждый — один-два куска от эпицентра со скоростью,
     * с которой волна бросает обломки ({@link Detonation#throwSpeed}), и пыль из пролома, когда до места дошёл фронт (только
     * запасная, {@link NukePall#drawsHead}).
     */
    static void debris(ClientNuclear.Active a, List<S2C.NukeDebris.Piece> pieces) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) return;
        Detonation d = a.d;
        double now = a.ticks(0);
        Vec3 eye = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        for (S2C.NukeDebris.Piece p : pieces) {
            Vec3 c = Vec3.atCenterOf(p.pos());
            double wait = d.arrivalTicks(c.distanceTo(d.burst())) - now;
            if (wait < -DEBRIS_LATE) continue;
            // вдали мелочь не видна: реже
            double dist = Math.max(DEBRIS_FULL, c.distanceTo(eye));
            if (RANDOM.nextFloat() > Fx.density(c) * DEBRIS_FULL * DEBRIS_FULL / (dist * dist)) continue;
            int delay = Mth.clamp((int) Math.ceil(wait), 0, Fragments.DELAY_MAX);
            double kpa = d.overpressureKpa(c), v = Detonation.throwSpeed(kpa);
            double ax = c.x - d.burst().x, az = c.z - d.burst().z, len = Math.sqrt(ax * ax + az * az);
            if (len < 1e-3) {
                ax = 1;
                az = 0;
            } else {
                ax /= len;
                az /= len;
            }
            int n = 1 + RANDOM.nextInt(2);
            for (int i = 0; i < n; i++) {
                double u = v * (0.5 + 0.6 * RANDOM.nextDouble());
                Fragments.launch(level, p.state(), p.pos(), c.x + (RANDOM.nextDouble() - 0.5) * 0.6, c.y + (RANDOM.nextDouble() - 0.5) * 0.6,
                        c.z + (RANDOM.nextDouble() - 0.5) * 0.6, ax * u, u * (0.15 + 0.35 * RANDOM.nextDouble()) + 0.1, az * u,
                        0.25f + 0.35f * RANDOM.nextFloat(), delay);
            }
            // пыль из пролома — запасная: пелена рисует её в голове стены
            if (!NukePall.drawsHead()) BREACHES.add(new Breach(a, c, ax, az, v, delay));
        }
    }

    /** Пыль из пролома: выбита напором вслед за обломками, цветом низа стены, которая проходит это место. */
    private static void breach(ClientLevel level, Breach b) {
        NukePall.Sky sky = new NukePall.Sky();
        NukePall.sky(level, b.at, 0, sky);
        double up = 0.4 * b.a.wall.wall(b.a.seconds(0)).height();
        float[] from = new float[3], to = new float[3];
        if (!wallColor(b.a, sky, b.at.x, b.at.y, b.at.z, 1, from) || !wallColor(b.a, sky, b.at.x, b.at.y + up, b.at.z, 1.05f, to)) return;
        Fx.smoke().vel(b.ax * b.v * 0.3, 0.05, b.az * b.v * 0.3).size(0.8f, 3 + 2 * RANDOM.nextFloat()).growFast().life(40 + RANDOM.nextInt(30))
                .color(from, to).prelit().alpha(0.7f).drag(0.93f).smooth().collide().wind(0).fadeIn(3).fadeFrom(0.4f).budget(FxBudget.GROUND)
                .spawn(level, b.at);
    }

    /**
     * Цвет пыли у камеры: цвет стены пыли в точке (x, y, z) мира ({@link NukePall#dustColor}) × {@code dark}; false — стены
     * уже нет.
     */
    private static boolean wallColor(ClientNuclear.Active a, NukePall.Sky sky, double x, double y, double z, float dark, float[] into) {
        if (!NukePall.dustColor(a, sky, x, y, z, into)) return false;
        for (int i = 0; i < 3; i++) into[i] = Math.min(1, into[i] * dark);
        return true;
    }

    /**
     * Один тик прохождения стены: сорванное с земли летит от эпицентра; запасные клубы пыли и дыма (цветом стены у игрока)
     * — только в самой голове стены, по её средней густоте в месте клуба: выше её верха пыли нет, туда доходит только ветер.
     */
    static void gust(ClientNuclear.Active a, Vec3 ear, double psi, int age) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) return;
        Detonation d = a.d;
        boolean ceiling = level.dimensionType().hasCeiling();
        // над водой — брызги и водяная пыль, сорванного с земли нет
        boolean wet = !ceiling && NukeWater.waterAround(level, ear.x, ear.z);
        boolean puffs = !NukePall.drawsHead();
        if (!puffs && (ceiling || wet)) return;
        float[] dust0 = new float[3], dust1 = new float[3], smoke0 = new float[3], smoke1 = new float[3];
        if (puffs) {
            if (!a.wall.slot(a.seconds(0), GUST_WALL)) return;
            NukePall.Sky sky = new NukePall.Sky();
            NukePall.sky(level, ear, 0, sky);
            double higher = 0.5 * GUST_WALL[2];
            if (!wallColor(a, sky, ear.x, ear.y, ear.z, 1.05f, dust0) || !wallColor(a, sky, ear.x, ear.y + higher, ear.z, 1, dust1)) return;
            grey(dust0, SMOKE_FROM, smoke0);
            grey(dust1, SMOKE_TO, smoke1);
        }
        Vec3 away = new Vec3(ear.x - d.burst().x, 0, ear.z - d.burst().z);
        away = away.lengthSqr() < 1e-4 ? new Vec3(1, 0, 0) : away.normalize();
        double speed = Mth.clamp(0.4 + psi * 0.25, 0.4, 2.5) * (1 - age / 80.0);
        int n = (int) (Mth.clamp(psi * 12, 12, 90) * (1 - age / 60.0));
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int i = 0; i < n; i++) {
            // появляются с наветренной стороны и по бокам, пролетают мимо
            double along = -14 + RANDOM.nextDouble() * 20, side = (RANDOM.nextDouble() - 0.5) * 30, up = -2 + RANDOM.nextDouble() * 8;
            Vec3 p = ear.add(away.scale(along)).add(-away.z * side, up, away.x * side);
            double v = speed * (0.7 + RANDOM.nextDouble() * 0.6);
            if (i % 5 == 0 && !ceiling && !wet) {
                // сорванное с земли — трава, листва, песок, снег — летит и кувыркается
                int bx = Mth.floor(p.x), bz = Mth.floor(p.z);
                LevelChunk chunk = level.getChunkSource().getChunk(bx >> 4, bz >> 4, false);
                if (chunk == null) continue;
                m.set(bx, chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, bx & 15, bz & 15), bz);
                Fragments.launch(level, chunk.getBlockState(m), m, p.x, Math.max(p.y, m.getY() + 1.2), p.z, away.x * v * 1.3, 0.1 + RANDOM.nextDouble() * 0.2,
                        away.z * v * 1.3, 0.1f + 0.2f * RANDOM.nextFloat(), 0);
            } else if (puffs && RANDOM.nextDouble() < inHead(a, GUST_WALL, p)) {
                // бурая пыль и серый дым клубами — стена, которая проходит сквозь игрока (цветом стены, со светом); над
                // водой — брызги и водяная пыль
                boolean smoke = i % 5 == 1;
                Fx.Spec puff = Fx.smoke().vel(away.x * v, 0.01 + RANDOM.nextDouble() * 0.04, away.z * v).size(1.2f, 5 + RANDOM.nextFloat() * 3).growFast();
                if (wet) puff.life(40 + RANDOM.nextInt(40)).color(0xC4CDD3, 0xEEF2F4).alpha(0.55f).budget(FxBudget.SPRAY);
                else puff.life(70 + RANDOM.nextInt(90)).color(smoke ? smoke0 : dust0, smoke ? smoke1 : dust1).prelit().alpha(0.7f).budget(FxBudget.GROUND);
                puff.drag(0.97f).smooth().collide().wind(0).fadeIn(2).fadeFrom(0.35f).spawn(level, p);
            }
        }
    }

    /**
     * Доля средней головы стены в точке {@code p} мира, 0..1 ({@link BlastWall#sigma} к её густоте там): {@code slot} —
     * числа слота стены ({@link BlastWall#slot}), рельеф и вода под точкой — по карте пелены.
     */
    private static double inHead(ClientNuclear.Active a, float[] slot, Vec3 p) {
        double wet = a.ground.hasWater() ? a.ground.water(p.x, p.z) : 0;
        double k = slot[3] + (slot[7] - slot[3]) * wet;
        if (k <= 0) return 0;
        Detonation d = a.d;
        return BlastWall.sigma(slot, p.x - d.burst().x, p.z - d.burst().z, p.y - d.groundY() - a.ground.at(p.x, p.z), wet) / k;
    }

    /** Дым в стене: её цвет вполовину к серому той же яркости ({@link #SMOKE_GREY}) и темнее в {@code k} раз. */
    private static void grey(float[] c, float k, float[] into) {
        float luma = 0.2126f * c[0] + 0.7152f * c[1] + 0.0722f * c[2];
        for (int i = 0; i < 3; i++) into[i] = Mth.lerp(SMOKE_GREY, c[i], luma) * k;
    }

    static void tick() {
        ClientLevel level = Minecraft.getInstance().level;
        BREACHES.removeIf(b -> {
            if (b.wait-- > 0) return false;
            if (level != null) breach(level, b);
            return true;
        });
        // голова у глаза держит пелену, ушла — пелена гаснет (гасить её и тогда, когда голова её держит, — мигание через тик)
        veilTarget = Math.max(veilTarget * VEIL_DECAY, veilWanted);
        veilWanted = 0;
        // стена накрывает за несколько тиков, а не вспышкой
        veil = veil < veilTarget ? Math.min(veilTarget, veil + VEIL_RISE) : veilTarget;
        if (veil < 0.01f) veil = veilTarget = 0;
    }

    static void reset() {
        veil = veilTarget = veilWanted = 0;
        BREACHES.clear();
    }

    /** Пелена поверх мира (под вспышкой): цветом стены у глаза, над водой — серо-белая. */
    static void render(GuiGraphics g) {
        if (veil <= 0) return;
        int a = (int) (veil * 255), rgb = 0;
        for (int i = 0; i < 3; i++) rgb = rgb << 8 | Mth.clamp(Math.round(veilRgb[i] * 255), 0, 255);
        g.fill(0, 0, g.guiWidth(), g.guiHeight(), (a << 24) | rgb);
    }
}
