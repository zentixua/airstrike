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
import ua.zentix.incoming.client.far.FarView;
import ua.zentix.incoming.client.fx.Fragments;
import ua.zentix.incoming.client.fx.particle.Fx;
import ua.zentix.incoming.client.fx.particle.FxBudget;
import ua.zentix.incoming.net.S2C;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.nuclear.model.BlastModel;

import java.util.ArrayList;
import java.util.List;

/**
 * Стена пыли ударной волны вблизи. Пока голова стены ближе {@link #NEAR} к камере — по её дуге у камеры встают клубы
 * пыли во всю высоту стены и катятся от эпицентра, с ними летят клочья мусора и куски верха земли. Блоки, которые снесла
 * волна (сервер называет их, {@code S2C.NukeDebris}), разлетаются кусками, когда до них доходит фронт. В миг прихода
 * фронта к игроку — бурая пелена на весь экран (накрывает за несколько тиков, гаснет за несколько секунд), а пока стена
 * проходит мимо — клубы пыли и дыма и сорванное с земли, которые ветер несёт от эпицентра.
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
    /** На сколько пелена накрывает экран за тик. */
    private static final float VEIL_RISE = 0.12f;
    private static float veil, veilTarget;
    /** Цвет пелены: бурая пыль, над водой — серо-белые брызги. */
    private static final int VEIL_DUST = 0x6E5A44, VEIL_SPRAY = 0xAEB8BE;
    private static int veilRgb = VEIL_DUST;
    private static float veilDecay = 0.97f;
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

    /** Фронт пришёл: пелена по давлению (1 psi — лёгкая дымка, 5+ psi — почти ничего не видно). */
    static void arrive(double psi) {
        Minecraft mc = Minecraft.getInstance();
        Vec3 eye = mc.gameRenderer.getMainCamera().getPosition();
        veilRgb = mc.level != null && !mc.level.dimensionType().hasCeiling() && NukeWater.waterAround(mc.level, eye.x, eye.z) ? VEIL_SPRAY : VEIL_DUST;
        veilTarget = Math.max(veilTarget, (float) Mth.clamp(0.2 + psi / 7, 0.2, 0.85));
        veilDecay = psi >= 3 ? 0.975f : 0.96f;
    }

    /**
     * Тик: голова стены у камеры. Клубы встают по дуге фронта ближе {@link #NEAR} к камере — гуще у земли, выше — крупнее,
     * летят от эпицентра медленнее фронта и отстают от него шлейфом, а у земли поднимаются (вал); где волна сильнее,
     * с ними летят тёмные клочья мусора и куски верха земли (трава, песок, листва, снег). Рельеф — карта высот
     * загруженных чанков клиента (под потолком — высота эпицентра). Клубы — цветом стены на своей высоте
     * ({@link #wallColor}), а не по карте освещения.
     */
    static void front(ClientNuclear.Active a, Vec3 eye) {
        Detonation d = a.d;
        CloudPuffs.Wall w = a.puffs.wall(a.seconds(0));
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
        double t = a.seconds(0);
        float ambient = FarView.ambient(level, 0);
        float[] from = new float[3], to = new float[3];
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
            // гуще у земли; клуб выше — крупнее, у земли — поднимается, наверху — уходит назад со стеной
            double up = Math.pow(RANDOM.nextDouble(), 1.6), h = w.height() * 0.8 * up;
            double v = speed * (0.25 + 0.45 * RANDOM.nextDouble());
            boolean dark = RANDOM.nextFloat() < 0.35f;
            // цвет головы стены на высоте клуба (её клубы — в среднем на середине высоты); рассеиваясь, клуб светлеет,
            // как её верх
            double share = 0.3 + 0.6 * h / Math.max(1, w.height());
            if (!wallColor(a, t, rr, share, 1, dark ? DARK_FROM : 1, ambient, from)
                    || !wallColor(a, t, rr, share + 0.4, 1.05, dark ? DARK_TO : 1, ambient, to)) continue;
            Fx.smoke().vel(cos * v, (0.04 + 0.2 * RANDOM.nextDouble()) * (1 - up), sin * v)
                    .size((float) (1.5 + 0.06 * h), (float) (5 + Math.min(10, w.height() * 0.08) + 3 * RANDOM.nextDouble())).growFast()
                    .life(50 + RANDOM.nextInt(50)).color(from, to).prelit().alpha(0.8f).drag(0.95f)
                    .collide().wind(0).fadeIn(3).fadeFrom(0.45f).budget(FxBudget.GROUND).spawn(level, x, top + 0.5 + h, z);
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
     * с которой волна бросает обломки ({@link Detonation#throwSpeed}), и пыль из пролома, когда до места дошёл фронт.
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
            BREACHES.add(new Breach(a, c, ax, az, v, delay));
        }
    }

    /** Пыль из пролома: выбита напором вслед за обломками, цветом низа стены, которая проходит это место. */
    private static void breach(ClientLevel level, Breach b) {
        Detonation d = b.a.d;
        double t = b.a.seconds(0), rr = Math.hypot(b.at.x - d.burst().x, b.at.z - d.burst().z);
        float ambient = FarView.ambient(level, 0);
        float[] from = new float[3], to = new float[3];
        if (!wallColor(b.a, t, rr, 0.3, 1, 1, ambient, from) || !wallColor(b.a, t, rr, 0.7, 1.05, 1, ambient, to)) return;
        Fx.smoke().vel(b.ax * b.v * 0.3, 0.05, b.az * b.v * 0.3).size(0.8f, 3 + 2 * RANDOM.nextFloat()).growFast().life(40 + RANDOM.nextInt(30))
                .color(from, to).prelit().alpha(0.7f).drag(0.93f).collide().wind(0).fadeIn(3).fadeFrom(0.4f).budget(FxBudget.GROUND)
                .spawn(level, b.at);
    }

    /**
     * Цвет пыли у камеры: цвет стены пыли на {@code groundBlocks} от эпицентра и на доле её высоты {@code up}
     * ({@link CloudPuffs#wallColor} с тенью клуба {@code shade}) × {@code dark}; false — стены уже нет.
     */
    private static boolean wallColor(ClientNuclear.Active a, double t, double groundBlocks, double up, double shade, float dark, float ambient,
                                     float[] into) {
        if (!a.puffs.wallColor(t, groundBlocks, up, shade, ambient, into)) return false;
        for (int i = 0; i < 3; i++) into[i] *= dark;
        return true;
    }

    /** Один тик прохождения стены: клубы вокруг игрока (цветом стены у него) и сорванное с земли летят от эпицентра. */
    static void gust(ClientNuclear.Active a, Vec3 ear, double psi, int age) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) return;
        Detonation d = a.d;
        double t = a.seconds(0), l = Math.hypot(ear.x - d.burst().x, ear.z - d.burst().z);
        float ambient = FarView.ambient(level, 0);
        float[] dust0 = new float[3], dust1 = new float[3], smoke0 = new float[3], smoke1 = new float[3];
        if (!wallColor(a, t, l, 0.4, 1.05, 1, ambient, dust0) || !wallColor(a, t, l, 0.9, 1, 1, ambient, dust1)) return;
        grey(dust0, SMOKE_FROM, smoke0);
        grey(dust1, SMOKE_TO, smoke1);
        Vec3 away = new Vec3(ear.x - d.burst().x, 0, ear.z - d.burst().z);
        away = away.lengthSqr() < 1e-4 ? new Vec3(1, 0, 0) : away.normalize();
        double speed = Mth.clamp(0.4 + psi * 0.25, 0.4, 2.5) * (1 - age / 80.0);
        boolean ceiling = level.dimensionType().hasCeiling();
        // над водой — брызги и водяная пыль, сорванного с земли нет
        boolean wet = !ceiling && NukeWater.waterAround(level, ear.x, ear.z);
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
            } else {
                // бурая пыль и серый дым клубами — стена, которая проходит сквозь игрока (цветом стены, со светом); над
                // водой — брызги и водяная пыль
                boolean smoke = i % 5 == 1;
                Fx.Spec puff = Fx.smoke().vel(away.x * v, 0.01 + RANDOM.nextDouble() * 0.04, away.z * v).size(1.2f, 5 + RANDOM.nextFloat() * 3).growFast();
                if (wet) puff.life(40 + RANDOM.nextInt(40)).color(0xC4CDD3, 0xEEF2F4).alpha(0.55f).budget(FxBudget.SPRAY);
                else puff.life(70 + RANDOM.nextInt(90)).color(smoke ? smoke0 : dust0, smoke ? smoke1 : dust1).prelit().alpha(0.7f).budget(FxBudget.GROUND);
                puff.drag(0.97f).collide().wind(0).fadeIn(2).fadeFrom(0.35f).spawn(level, p);
            }
        }
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
        if (veil < veilTarget) {
            // стена накрывает за несколько тиков, а не вспышкой
            veil = Math.min(veilTarget, veil + VEIL_RISE);
        } else {
            veilTarget *= veilDecay;
            veil = veilTarget;
        }
        if (veil < 0.01f) veil = veilTarget = 0;
    }

    static void reset() {
        veil = veilTarget = 0;
        veilRgb = VEIL_DUST;
        BREACHES.clear();
    }

    /** Пелена поверх мира (под вспышкой): бурая, над водой — серо-белая. */
    static void render(GuiGraphics g) {
        if (veil <= 0) return;
        int a = (int) (veil * 255);
        g.fill(0, 0, g.guiWidth(), g.guiHeight(), (a << 24) | veilRgb);
    }
}
