package ua.zentix.airstrike.nuclear;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.phys.Vec3;
import ua.zentix.airstrike.nuclear.model.ArrivalTable;
import ua.zentix.airstrike.nuclear.model.BlastModel;
import ua.zentix.airstrike.nuclear.model.FalloutModel;
import ua.zentix.airstrike.nuclear.model.FireballModel;
import ua.zentix.airstrike.nuclear.model.ThermalModel;

/**
 * Один ядерный подрыв — всё, из чего сервер и каждый клиент по одной и той же модели выводят волну, свет,
 * шар, гриб и осадки. Хранится в мире ({@link NuclearEvents}) и уходит клиентам одним пакетом.
 * <p>
 * Масштаб {@code scale}: 1 блок = 1/scale метра (1.0 — как в жизни). Расстояния модели делятся на него,
 * время фронта умножается — фронт в блоках идёт со скоростью звука при любом масштабе.
 *
 * @param id        номер подрыва в мире (растёт)
 * @param burst     точка подрыва
 * @param groundY   высота земли под эпицентром
 * @param yieldKt   мощность, кт
 * @param surface   наземный подрыв (шар касается земли): воронка и осадки
 * @param gameTime  игровое время подрыва, тики
 * @param windDir   направление ветра, рад (дует в сторону (cos, sin) в плоскости x–z)
 * @param windSpeed скорость ветра, м/с
 * @param visibility видимость, м (ясно 20 км, дождь 5, гроза 1) — ослабление света
 * @param seed      сид случайных деталей (одинаковый у всех клиентов)
 * @param fallout   есть радиоактивный след (наземный подрыв и включены осадки)
 */
public record Detonation(int id, Vec3 burst, double groundY, double yieldKt, boolean surface, long gameTime,
                         float windDir, float windSpeed, float visibility, long seed, float scale, boolean fallout) {

    public static final Codec<Detonation> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.INT.fieldOf("id").forGetter(Detonation::id),
            Vec3.CODEC.fieldOf("burst").forGetter(Detonation::burst),
            Codec.DOUBLE.fieldOf("ground_y").forGetter(Detonation::groundY),
            Codec.DOUBLE.fieldOf("yield").forGetter(Detonation::yieldKt),
            Codec.BOOL.fieldOf("surface").forGetter(Detonation::surface),
            Codec.LONG.fieldOf("game_time").forGetter(Detonation::gameTime),
            Codec.FLOAT.fieldOf("wind_dir").forGetter(Detonation::windDir),
            Codec.FLOAT.fieldOf("wind_speed").forGetter(Detonation::windSpeed),
            Codec.FLOAT.fieldOf("visibility").forGetter(Detonation::visibility),
            Codec.LONG.fieldOf("seed").forGetter(Detonation::seed),
            Codec.FLOAT.fieldOf("scale").forGetter(Detonation::scale),
            Codec.BOOL.fieldOf("fallout").forGetter(Detonation::fallout)
    ).apply(i, Detonation::new));

    private static final StreamCodec<ByteBuf, Vec3> VEC3 = StreamCodec.composite(
            ByteBufCodecs.DOUBLE, Vec3::x, ByteBufCodecs.DOUBLE, Vec3::y, ByteBufCodecs.DOUBLE, Vec3::z, Vec3::new);

    public static final StreamCodec<ByteBuf, Detonation> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public Detonation decode(ByteBuf b) {
            return new Detonation(ByteBufCodecs.VAR_INT.decode(b), VEC3.decode(b), b.readDouble(), b.readDouble(), b.readBoolean(),
                    b.readLong(), b.readFloat(), b.readFloat(), b.readFloat(), b.readLong(), b.readFloat(), b.readBoolean());
        }

        @Override
        public void encode(ByteBuf b, Detonation d) {
            ByteBufCodecs.VAR_INT.encode(b, d.id);
            VEC3.encode(b, d.burst);
            b.writeDouble(d.groundY);
            b.writeDouble(d.yieldKt);
            b.writeBoolean(d.surface);
            b.writeLong(d.gameTime);
            b.writeFloat(d.windDir);
            b.writeFloat(d.windSpeed);
            b.writeFloat(d.visibility);
            b.writeLong(d.seed);
            b.writeFloat(d.scale);
            b.writeBoolean(d.fallout);
        }
    };

    /** Порог дальней зоны: 0.5 psi (стёкла). */
    public static final double FAR_KPA = BlastModel.kpa(0.5);

    // ---------------------------------------------------------------- масштаб

    /** Метры модели по блокам мира. */
    public double metres(double blocks) {
        return blocks / scale;
    }

    public double blocks(double metres) {
        return metres * scale;
    }

    /** Высота подрыва над землёй, м. */
    public double hobMetres() {
        return metres(burst.y - groundY);
    }

    // ---------------------------------------------------------------- волна

    public double overpressureKpa(Vec3 p) {
        return BlastModel.overpressureKpa(Math.max(1, metres(p.distanceTo(burst))), yieldKt);
    }

    public double psi(Vec3 p) {
        return BlastModel.psi(overpressureKpa(p));
    }

    /** Таблица прихода фронта (кэш на подрыв). */
    public ArrivalTable arrival() {
        return Caches.arrival(this);
    }

    /** Через сколько тиков после подрыва фронт дойдёт до точки на расстоянии {@code blocks}. */
    public double arrivalTicks(double blocks) {
        return arrival().arrivalSeconds(metres(blocks)) * 20 * scale;
    }

    /** Радиус фронта (блоки) через {@code ticks} после подрыва. */
    public double frontRadius(double ticks) {
        return blocks(arrival().radiusAt(ticks / (20.0 * scale)));
    }

    /** Докуда что-то вообще меняется (блоки): стёкла или ожоги 1-й степени — что дальше. */
    public double radiusMax() {
        return Caches.radiusMax(this);
    }

    // ---------------------------------------------------------------- свет и шар

    /** Световой импульс в точке, кал/см² (без учёта тени — её проверяет вызывающий). */
    public double fluence(Vec3 p) {
        return ThermalModel.fluenceCalPerCm2(Math.max(1, metres(p.distanceTo(burst))), yieldKt, surface, visibility);
    }

    /** Наибольший радиус огненного шара, блоки. */
    public double fireballRadius() {
        return blocks(FireballModel.maxRadius(yieldKt, surface));
    }

    // ---------------------------------------------------------------- осадки

    public FalloutModel falloutModel() {
        return new FalloutModel(yieldKt, hobMetres(), windDir, windSpeed);
    }

    /** Есть ли у подрыва радиоактивный след. */
    public boolean hasFallout() {
        return fallout && falloutModel().surfaceFraction() > 0;
    }

    /**
     * Мощность дозы осадков в точке, Р/ч.
     *
     * @param ticksSince игровые тики после подрыва (и время прихода осадков, и спад: 1000 тиков = 1 игровой час)
     */
    public double falloutRate(double x, double z, long ticksSince) {
        if (!hasFallout()) return 0;
        double seconds = ticksSince / (20.0 * scale);
        double hours = ticksSince / 1000.0;
        return falloutModel().doseRate(metres(x - burst.x), metres(z - burst.z), seconds, hours);
    }

    /** Сколько игровых часов после прихода осадков в следе идёт чёрный дождь. */
    public static final double BLACK_RAIN_HOURS = 2.5;
    /** Чёрный дождь там, где мощность дозы в следе от 1 Р/ч. */
    public static final double BLACK_RAIN_RATE = 1;

    /** Идёт ли в точке чёрный дождь: след осадков уже дошёл и ещё не выпал (одинаково на сервере и клиенте). */
    public boolean blackRain(double x, double z, long ticksSince) {
        if (!hasFallout() || falloutRate(x, z, ticksSince) < BLACK_RAIN_RATE) return false;
        double arrival = falloutModel().arrivalSeconds(metres(x - burst.x), metres(z - burst.z)) * 20 * scale;
        double hours = (ticksSince - arrival) / 1000.0;
        return hours >= 0 && hours < BLACK_RAIN_HOURS;
    }

    /**
     * Под открытым небом ли точка (на неё падает дождь): над ней нет ничего, что держит движение, — ни крыши,
     * ни листвы, ни воды. Карта высот есть и на сервере, и на клиенте, поэтому ответ у них одинаковый.
     */
    public static boolean underOpenSky(net.minecraft.world.level.Level level, Vec3 pos) {
        return level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING,
                net.minecraft.util.Mth.floor(pos.x), net.minecraft.util.Mth.floor(pos.z)) <= pos.y;
    }

    /** Кэши, которые дорого считать на каждый вызов (таблица прихода, наибольший радиус). */
    private static final class Caches {
        private static final java.util.Map<Detonation, ArrivalTable> ARRIVAL = java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());
        private static final java.util.Map<Detonation, Double> RADIUS = java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

        static ArrivalTable arrival(Detonation d) {
            return ARRIVAL.computeIfAbsent(d, x -> ArrivalTable.of(x.yieldKt, Math.max(1000, x.metres(x.radiusMax()) * 1.5)));
        }

        static double radiusMax(Detonation d) {
            return RADIUS.computeIfAbsent(d, x -> {
                double blast = BlastModel.rangeForOverpressure(FAR_KPA, x.yieldKt);
                double light = ThermalModel.rangeForFluence(ThermalModel.BURN_1, x.yieldKt, x.surface, x.visibility);
                return x.blocks(Math.max(blast, light));
            });
        }
    }
}
