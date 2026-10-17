package ua.zentix.incoming.client.nuclear;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.client.far.FarTerrain;
import ua.zentix.incoming.client.fx.layer.WaterDecal;
import ua.zentix.incoming.client.fx.particle.Fx;
import ua.zentix.incoming.client.fx.particle.FxBudget;
import ua.zentix.incoming.client.map.TerrainTiles;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.nuclear.model.BlastModel;
import ua.zentix.incoming.nuclear.model.WaterModel;

import java.util.Locale;
import java.util.OptionalInt;

/**
 * Ядерная волна на воде (DESIGN-nuke §6, «Вода»): тёмное кольцо фронта на воде — рябь под ветром за фронтом, с пеной
 * у эпицентра (наклейка на воду в слое эффектов, {@link WaterDecal}: профиль кольца по модели считается здесь, на кадр);
 * капли с гребней за фронтом у камеры (частицы, {@link FxBudget#SPRAY}; водяную пыль там рисует голова стены в пелене,
 * {@link #front}); подрыв на воде — столб воды с короной в объёме
 * гриба ({@link MushroomField}) и базисная волна в пелене ({@link PallField#water}). Пыль у земли над водой не встаёт:
 * голова стены там — брызги и водяная пыль ({@link BlastWall}, вода — по карте под пеленой, {@link PallGround}), частицы
 * пыли у камеры — только над сушей ({@link NukeDust#front}).
 * <p>
 * Вода ли где-то — только из того, что у клиента уже есть: чанки клиента, плитки рельефа карты ({@link FarTerrain#water}).
 * Вода под эпицентром далеко за прорисовкой (ни чанка, ни плитки) — по высоте: земля ровно на уровне моря — море.
 */
public final class NukeWater {
    private static final RandomSource RANDOM = RandomSource.create();
    /**
     * Полоса ряби у самого фронта, тиков: воздух за фронтом дует столько, сколько идёт положительная фаза (у 15 кт
     * в километрах — около секунды), дальше вода неспокойна ещё {@link #CALM} тиков и гладнеет.
     */
    static final double BAND = 25, CALM = 600;
    /** Шаг мест брызг вдоль дуги фронта у камеры, блоков; мест за тик — не больше. */
    private static final double STEP = 4;
    private static final int MAX_SPOTS = 64;
    /** Брызги у фронта не быстрее, блоков за тик (как пыль, {@code NukeDust}). */
    private static final double SPRAY_SPEED = 4;
    /**
     * Вода под эпицентром по догадке (ни чанка, ни плитки) решается заново, пока подрыв на этом клиенте моложе стольких
     * своих тиков ({@link ClientNuclear.Active#age()}: от прихода пакета или перемотки повтора, а не от самого подрыва):
     * у своего подрыва — полторы секунды, у пришедшего прошлым (вход в мир посреди подрыва, повтор) — пять: чанки вокруг
     * вошедшего игрока приходят за секунды.
     */
    static final long GUESS_TICKS = 30, GUESS_TICKS_PAST = 100;
    /** Цвет брызг: светлее у гребня, белее в воздухе. */
    private static final int SPRAY_DARK = 0xC9D2D8, SPRAY_LIGHT = 0xF2F5F7;

    /** Профиль кольца в кадре (поток отрисовки; {@link WaterDecal#ring} сразу копирует его в текстуру). */
    private static final float[] DARK = new float[WaterDecal.SAMPLES], FOAM = new float[WaterDecal.SAMPLES];
    /** Для строки сценария: мест брызг за последний тик. */
    private static int lastSpots;

    private NukeWater() {}

    /** Откуда известно, есть ли вода под эпицентром. */
    enum Source { CHUNK, TILES, SEA_LEVEL, NONE }

    /** Вода под эпицентром 0..1 и откуда это известно. */
    record Under(double water, Source source) {
        boolean sure() {
            return source == Source.CHUNK || source == Source.TILES;
        }
    }

    /**
     * Вода под эпицентром: загруженный чанк клиента (вода в блоке поверхности, {@link #surfaceBlock}), иначе
     * плитка рельефа карты, иначе догадка по высоте: у ровной земли на уровне моря ({@code groundY} = уровень моря —
     * первый воздух над водой моря) — море. Под потолком (Незер) воды нет.
     */
    static Under under(@Nullable ClientLevel level, Detonation d) {
        if (level == null || level.dimensionType().hasCeiling()) return new Under(0, Source.NONE);
        int x = Mth.floor(d.burst().x), z = Mth.floor(d.burst().z), y = surfaceBlock(d.groundY());
        LevelChunk chunk = level.getChunkSource().getChunk(x >> 4, z >> 4, false);
        if (chunk != null) return new Under(chunk.getFluidState(new BlockPos(x, y, z)).is(FluidTags.WATER) ? 1 : 0, Source.CHUNK);
        OptionalInt depth = TerrainTiles.waterDepth(x, z);
        if (depth.isPresent()) return new Under(depth.getAsInt() > 0 ? 1 : 0, Source.TILES);
        return new Under(guess(d.groundY(), WaterDecal.SEA_LEVEL), Source.SEA_LEVEL);
    }

    /**
     * Догадка без данных: первый воздух над землёй ровно на уровне моря — над морем (берег на этой высоте — узкая полоса).
     * Высота земли — как у {@link #surfaceBlock}: у моря с прицела — 62,889.
     */
    static double guess(double groundY, int seaLevel) {
        return Mth.ceil(groundY) == seaLevel ? 1 : 0;
    }

    /**
     * Блок поверхности под эпицентром по {@code groundY}: у суши и карты высот это первый воздух над землёй (целое), а
     * у прицела, бинокля, ракеты и B-2 по лучу с жидкостью — сама гладь: верх стоячей воды на 8/9 блока, 62,889 у моря.
     * Блок, в котором эта высота (верхний, если она на его грани), — вода или земля.
     */
    static int surfaceBlock(double groundY) {
        return Mth.ceil(groundY) - 1;
    }

    /**
     * Вода под эпицентром решена и дальше не меняется: известна наверняка (чанк, плитка) или догадка держится дольше
     * срока — {@code age} своих тиков подрыва на этом клиенте ({@link ClientNuclear.Active#age()}), {@code live} — свой
     * подрыв, не пришедший прошлым.
     */
    static boolean settles(Under u, long age, boolean live) {
        return u.sure() || age > (live ? GUESS_TICKS : GUESS_TICKS_PAST);
    }

    /** Тик подрыва после {@link #resolve}: брызги у камеры. */
    static void tick(ClientNuclear.Active a, ClientLevel level, Vec3 eye) {
        front(a, level, eye);
    }

    /**
     * Вода под эпицентром, пока не известна наверняка и подрыв молод, — заново: пелене (базисная волна, вода там, где её карта
     * ничего не знает), грибу (пар и брызги) и брызгам. Зовётся и при создании подрыва до его карты под пеленой: иначе вошедший в разгар подрыва в море первые
     * тики видел бы над морем бурую пыль (до решения под эпицентром — суша).
     */
    static void resolve(ClientNuclear.Active a, @Nullable ClientLevel level) {
        BurstWater w = a.water;
        if (w.settled()) return;
        Under u = under(level, a.d);
        w.set(u.water(), u.water() * WaterModel.wetBurst(a.d.hobMetres(), a.d.yieldKt()));
        a.pall.water(u.water(), w.wet());
        if (settles(u, a.age(), a.live)) w.settle(u.source());
    }

    /** Вода ли сверху колонки загруженного чанка (верхний блок карты высот {@code MOTION_BLOCKING} — вода). */
    static boolean water(LevelChunk chunk, int x, int z, BlockPos.MutableBlockPos pos) {
        int top = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, x & 15, z & 15) - 1;
        return top >= chunk.getMinBuildHeight() && chunk.getFluidState(pos.set(x, top, z)).is(FluidTags.WATER);
    }

    /**
     * Вокруг места больше воды, чем суши (середина и четыре точки в 8 блоках; загруженные чанки): игрок на площадке
     * или в лодке посреди озера — над водой.
     */
    static boolean waterAround(ClientLevel level, double x, double z) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int wet = 0;
        for (int k = 0; k < 5; k++) {
            int bx = Mth.floor(x) + (k == 1 ? 8 : k == 2 ? -8 : 0), bz = Mth.floor(z) + (k == 3 ? 8 : k == 4 ? -8 : 0);
            LevelChunk chunk = level.getChunkSource().getChunk(bx >> 4, bz >> 4, false);
            if (chunk != null && water(chunk, bx, bz, pos)) wet++;
        }
        return wet >= 3;
    }

    /** Ветер за фронтом на наклонной дальности {@code slant} блоков, м/с. */
    private static double wind(Detonation d, double slant) {
        return BlastModel.windSpeed(BlastModel.overpressureKpa(d.metres(slant), d.yieldKt()));
    }

    /**
     * Брызги у камеры: пока фронт по воде ближе {@link NukeDust#NEAR} к камере — по его дуге над водой (загруженные
     * чанки) с гребней летят капли; гуще, где ветер сильнее. Саму водяную пыль на высоту, на которую ветер за фронтом
     * срывает гребни ({@link WaterModel#sprayHeight}), рисует пелена объёмом — голова стены над водой
     * ({@link NukePall#drawsHead}), как и пыль у камеры над сушей ({@link NukeDust}); клубы — только запасные, когда
     * пелена её не рисует, и гладкие: клуб шире экрана у глаза ближайшим пикселем — лесенка квадратов на весь кадр.
     */
    private static void front(ClientNuclear.Active a, ClientLevel level, Vec3 eye) {
        lastSpots = 0;
        if (level.dimensionType().hasCeiling()) return;
        Detonation d = a.d;
        double ticks = a.ticks(0), slant = d.frontRadius(ticks), hob = Math.max(0, d.burst().y - d.groundY());
        if (slant <= hob) return;
        double r = Math.sqrt(slant * slant - hob * hob);
        double ex = eye.x - d.burst().x, ez = eye.z - d.burst().z, l = Math.sqrt(ex * ex + ez * ez);
        if (Math.abs(l - r) > NukeDust.NEAR) return;
        double wind = wind(d, slant), spray = WaterModel.spray(wind);
        if (spray < 0.02) return;
        double height = d.blocks(WaterModel.sprayHeight(wind));
        double half = l < 1 ? Math.PI : Math.acos(Mth.clamp((l * l + r * r - NukeDust.NEAR * NukeDust.NEAR) / (2 * l * r), -1, 1));
        double toward = Math.atan2(ez, ex);
        double speed = Math.min(SPRAY_SPEED, d.frontRadius(ticks + 1) - slant);
        // брызги встают позади тёмного кольца: на путь фронта за время подъёма брызг (WaterModel.SPRAY_LAG)
        double lag = Math.max(0, slant - d.frontRadius(Math.max(0, ticks - WaterModel.SPRAY_LAG * 20 * d.scale())));
        int n = Math.min(MAX_SPOTS, (int) Math.round(2 * half * r / STEP * spray * Fx.density(eye)));
        boolean puffs = !NukePall.drawsHead(a, eye);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int i = 0; i < n; i++) {
            double ang = toward + (RANDOM.nextDouble() * 2 - 1) * half;
            double cos = Math.cos(ang), sin = Math.sin(ang);
            double rr = r - lag - RANDOM.nextDouble() * Math.min(10, height * 0.4);
            double x = d.burst().x + cos * rr, z = d.burst().z + sin * rr;
            if ((x - eye.x) * (x - eye.x) + (z - eye.z) * (z - eye.z) > NukeDust.NEAR * NukeDust.NEAR) continue;
            int bx = Mth.floor(x), bz = Mth.floor(z);
            LevelChunk chunk = level.getChunkSource().getChunk(bx >> 4, bz >> 4, false);
            if (chunk == null || !water(chunk, bx, bz, pos)) continue;
            double surface = pos.getY() + 0.9;
            lastSpots++;
            // водяная пыль: пелена у самой воды (клубы прозрачные и крупные — сливаются, а не висят шарами), выше —
            // реже и крупнее, катится с фронтом медленнее его
            double up = Math.pow(RANDOM.nextDouble(), 2.2), h = height * 0.85 * up;
            if (puffs) {
                double v = speed * (0.35 + 0.45 * RANDOM.nextDouble());
                Fx.smoke().vel(cos * v, (0.03 + 0.12 * RANDOM.nextDouble()) * (1 - up), sin * v)
                        .size((float) (2 + 0.1 * h), (float) (6 + Math.min(14, height * 0.3) + 4 * RANDOM.nextDouble())).growFast()
                        .life(40 + RANDOM.nextInt(50)).color(SPRAY_DARK, SPRAY_LIGHT).alpha((float) (0.22 + 0.25 * spray)).drag(0.94f)
                        .smooth().wind(0).fadeIn(6).fadeFrom(0.3f).budget(FxBudget.SPRAY).spawn(level, x, surface + 0.3 + h, z);
            }
            // капли с гребней: летят с ветром и падают
            if (RANDOM.nextDouble() < 0.4 + 0.6 * spray) {
                for (int k = 0; k < 2; k++) {
                    double u = Math.min(SPRAY_SPEED, 0.6 + speed) * (0.4 + 0.6 * RANDOM.nextDouble());
                    Fx.smoke().vel(cos * u, 0.15 + 0.45 * spray * RANDOM.nextDouble(), sin * u).size(0.1f, 0.16f).life(18 + RANDOM.nextInt(20))
                            .color(0xDCE6EC, SPRAY_LIGHT).alpha(0.9f).drag(0.98f).gravity(0.06f).collide().wind(0).fadeIn(0).fadeFrom(0.7f)
                            .budget(FxBudget.SPRAY).spawn(level, x, surface + 0.2 + RANDOM.nextDouble() * Math.min(4, h + 1), z);
                }
            }
        }
    }

    /** Кольца всех подрывов на воде в кадр ({@link #decal}); после {@code WaterDecal.begin}. */
    public static void decals(float partial) {
        for (ClientNuclear.Active a : ClientNuclear.detonations()) decal(a, partial);
    }

    /**
     * Кольцо подрыва на воде в кадр ({@link WaterDecal}): радиус фронта по воде и профиль за ним — темнота ряби
     * ({@link WaterModel#darkness}) и пена ({@link WaterModel#whitecaps}) по тому, сколько тиков назад фронт прошёл
     * это место и какой ветер был за ним.
     */
    static void decal(ClientNuclear.Active a, float partial) {
        if (!WaterDecal.wanted()) return;
        Detonation d = a.d;
        double ticks = a.ticks(partial), slant = d.frontRadius(ticks), hob = Math.max(0, d.burst().y - d.groundY());
        if (slant <= hob) return;
        double r = Math.sqrt(slant * slant - hob * hob);
        if (!profile(d, ticks, r, hob, DARK, FOAM)) return;
        WaterDecal.ring(d.burst().x, d.burst().z, r, r, DARK, FOAM);
    }

    /**
     * Профиль кольца радиуса {@code r} блоков по воде в момент {@code ticks}: выборки за фронтом на
     * {@link WaterDecal#sampleAt} блоков. False — кольца не видно (вода всюду спокойна).
     */
    static boolean profile(Detonation d, double ticks, double r, double hob, float[] dark, float[] foam) {
        float most = 0;
        for (int i = 0; i < dark.length; i++) {
            double behind = WaterDecal.sampleAt(i, r), ground = Math.max(0, r - behind), slant = Math.hypot(ground, hob);
            // выборки — за фронтом или на нём: округление таблицы прихода не выносит их вперёд
            double since = Math.max(0, ticks - d.arrivalTicks(slant)), wind = wind(d, slant);
            dark[i] = (float) WaterModel.darkness(wind, since, BAND, CALM);
            foam[i] = (float) WaterModel.whitecaps(wind, since, CALM);
            most = Math.max(most, Math.max(dark[i], foam[i]));
        }
        return most > 0.004f;
    }

    /**
     * Строка сценария: наклейка на воду, мест брызг за тик, частиц брызг и пыли у земли; по подрывам — вода под ним, столб
     * (верх, наружный радиус, доля воды, что ещё не упала; м модели), базисная волна, голова стены.
     */
    public static String describe() {
        StringBuilder sb = new StringBuilder();
        for (ClientNuclear.Active a : ClientNuclear.detonations()) {
            double t = a.seconds(0);
            WaterModel.Column c = a.water.wet() > 0 ? WaterModel.column(t, a.d.yieldKt()) : null;
            String column = c == null ? "none" : String.format(Locale.ROOT, "top %.0f m, r %.0f m, water %.2f", c.top(), c.outer(), c.water());
            BlastWall.Wall w = a.wall.wall(t);
            sb.append(String.format(Locale.ROOT, " [#%d t=%.1fs wet=%.2f (%s) column %s, surge r %.0f m, wall=%.0f]", a.d.id(),
                    t, a.water.wet(), a.water.source(), column, WaterModel.surgeRadius(t, a.d.yieldKt()) * (a.water.wet() > 0 ? 1 : 0),
                    w.head()));
        }
        Minecraft mc = Minecraft.getInstance();
        int live = mc.level == null ? 0 : ua.zentix.incoming.client.fx.particle.FxPool.INSTANCE.live(FxBudget.SPRAY);
        int dust = mc.level == null ? 0 : ua.zentix.incoming.client.fx.particle.FxPool.INSTANCE.live(FxBudget.GROUND);
        return String.format(Locale.ROOT, "decal %s, spray spots %d, spray particles %d, ground dust particles %d%s", WaterDecal.describe(), lastSpots,
                live, dust, sb);
    }
}
