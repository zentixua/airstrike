package ua.zentix.airstrike.net;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.phys.Vec3;
import ua.zentix.airstrike.Airstrike;
import ua.zentix.airstrike.nuclear.Detonation;
import ua.zentix.airstrike.nuclear.model.Yield;
import ua.zentix.airstrike.util.StreamCodecs;

import java.util.List;
import java.util.UUID;

/** Сервер → клиент: только события; всё, что видно и слышно, клиент строит сам (частицы, звук с задержкой, тряска). */
public final class S2C {
    private S2C() {}

    /** Взрыв: вид (0 — шахед, 1 — ракета, 2 — бомба под землёй, 3 — снаряд РСЗО), грунт, высота поверхности над точкой, сид. */
    public record Blast(int kind, Vec3 pos, int material, float surfaceY, long seed) implements CustomPacketPayload {
        public static final int DRONE = 0, MISSILE = 1, BUNKER = 2, ROCKET = 3;
        public static final Type<Blast> TYPE = new Type<>(Airstrike.id("blast"));
        public static final StreamCodec<ByteBuf, Blast> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Blast::kind, StreamCodecs.VEC3, Blast::pos, ByteBufCodecs.VAR_INT, Blast::material,
                ByteBufCodecs.FLOAT, Blast::surfaceY, ByteBufCodecs.VAR_LONG, Blast::seed, Blast::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Бомба вошла в грунт: звуковой удар, удар о землю, фонтан у входного отверстия. */
    public record BunkerImpact(Vec3 pos, int material) implements CustomPacketPayload {
        public static final Type<BunkerImpact> TYPE = new Type<>(Airstrike.id("bunker_impact"));
        public static final StreamCodec<ByteBuf, BunkerImpact> CODEC = StreamCodec.composite(
                StreamCodecs.VEC3, BunkerImpact::pos, ByteBufCodecs.VAR_INT, BunkerImpact::material, BunkerImpact::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Газы подземного взрыва вырвались из скважины. */
    public record Vent(Vec3 pos, int material) implements CustomPacketPayload {
        public static final Type<Vent> TYPE = new Type<>(Airstrike.id("vent"));
        public static final StreamCodec<ByteBuf, Vent> CODEC = StreamCodec.composite(
                StreamCodecs.VEC3, Vent::pos, ByteBufCodecs.VAR_INT, Vent::material, Vent::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Свод над полостью обрушился: провал на поверхности. */
    public record Collapse(Vec3 pos, int material) implements CustomPacketPayload {
        public static final Type<Collapse> TYPE = new Type<>(Airstrike.id("collapse"));
        public static final StreamCodec<ByteBuf, Collapse> CODEC = StreamCodec.composite(
                StreamCodecs.VEC3, Collapse::pos, ByteBufCodecs.VAR_INT, Collapse::material, Collapse::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Сейсмическая волна (в породе быстрее звука): толчки прямо сейчас, сила — длительность в тиках. */
    public record Quake(int ticks, boolean rumble) implements CustomPacketPayload {
        public static final Type<Quake> TYPE = new Type<>(Airstrike.id("quake"));
        public static final StreamCodec<ByteBuf, Quake> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Quake::ticks, ByteBufCodecs.BOOL, Quake::rumble, Quake::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Воздушная тревога у цели: 0 — воздушная, 1 — ракетная опасность. */
    public record Siren(Vec3 pos, int kind) implements CustomPacketPayload {
        public static final int AIR_RAID = 0, MISSILE = 1;
        public static final Type<Siren> TYPE = new Type<>(Airstrike.id("siren"));
        public static final StreamCodec<ByteBuf, Siren> CODEC = StreamCodec.composite(
                StreamCodecs.VEC3, Siren::pos, ByteBufCodecs.VAR_INT, Siren::kind, Siren::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Ход залпа у того, кто его запустил. */
    public record SalvoStatus(int weapon, int fired, int total) implements CustomPacketPayload {
        public static final Type<SalvoStatus> TYPE = new Type<>(Airstrike.id("salvo_status"));
        public static final StreamCodec<ByteBuf, SalvoStatus> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, SalvoStatus::weapon, ByteBufCodecs.VAR_INT, SalvoStatus::fired, ByteBufCodecs.VAR_INT, SalvoStatus::total, SalvoStatus::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * Свои снаряды в полёте — тому, кто их пустил, раз в 5 тиков (и пустой список, когда все долетели): для HUD
     * с временем до удара, меток на экране и камеры снаряда. Сервер знает и тех, что летят вне загруженного мира.
     */
    public record Flights(List<Flight> flights) implements CustomPacketPayload {
        public static final Type<Flights> TYPE = new Type<>(Airstrike.id("flights"));
        public static final StreamCodec<ByteBuf, Flights> CODEC = Flight.CODEC.apply(ByteBufCodecs.list()).map(Flights::new, Flights::flights);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * Один снаряд: UUID (тот же после возвращения в мир), оружие, фаза полёта, тиков до удара, где он и куда летит,
     * ядерная ли БЧ.
     */
    public record Flight(UUID id, int weapon, int phase, int eta, Vec3 pos, Vec3 target, boolean nuclear,
                         int targetKind, String targetName, boolean targetLost) {
        /** Цель — точка (подписи нет). */
        public static final int TARGET_POINT = 0;
        /** Игрок или сущность с именем: {@code targetName} — само имя. */
        public static final int TARGET_NAMED = 1;
        /** Сущность без имени: {@code targetName} — ключ перевода её типа. */
        public static final int TARGET_TYPE = 2;
        /** Аппарат Sable: {@code targetName} — его имя или пусто. */
        public static final int TARGET_AIRCRAFT = 3;
        /** Длина {@code targetName}, дальше сервер обрезает. */
        public static final int MAX_NAME = 64;
        private static final StreamCodec<ByteBuf, String> NAME = ByteBufCodecs.stringUtf8(MAX_NAME);

        public static final StreamCodec<ByteBuf, Flight> CODEC = new StreamCodec<>() {
            @Override
            public Flight decode(ByteBuf b) {
                return new Flight(UUIDUtil.STREAM_CODEC.decode(b), ByteBufCodecs.VAR_INT.decode(b), ByteBufCodecs.VAR_INT.decode(b),
                        ByteBufCodecs.VAR_INT.decode(b), StreamCodecs.VEC3.decode(b), StreamCodecs.VEC3.decode(b), b.readBoolean(),
                        ByteBufCodecs.VAR_INT.decode(b), NAME.decode(b), b.readBoolean());
            }

            @Override
            public void encode(ByteBuf b, Flight f) {
                UUIDUtil.STREAM_CODEC.encode(b, f.id);
                ByteBufCodecs.VAR_INT.encode(b, f.weapon);
                ByteBufCodecs.VAR_INT.encode(b, f.phase);
                ByteBufCodecs.VAR_INT.encode(b, f.eta);
                StreamCodecs.VEC3.encode(b, f.pos);
                StreamCodecs.VEC3.encode(b, f.target);
                b.writeBoolean(f.nuclear);
                ByteBufCodecs.VAR_INT.encode(b, f.targetKind);
                NAME.encode(b, f.targetName);
                b.writeBoolean(f.targetLost);
            }
        };
    }

    /**
     * Снаряды, которых у клиента нет (дальше, чем ему выдаёт сущности ваниль, или в полёте вне мира), но которые он
     * уже может слышать ({@link ua.zentix.airstrike.strike.Hearing}): раз в {@link #PERIOD} тиков, по ним клиент ведёт
     * тот же звук с задержкой и Доплером, что и по сущности.
     */
    public record Heard(List<HeardFlight> flights) implements CustomPacketPayload {
        public static final int PERIOD = 2;
        public static final Type<Heard> TYPE = new Type<>(Airstrike.id("heard"));
        public static final StreamCodec<ByteBuf, Heard> CODEC = HeardFlight.CODEC.apply(ByteBufCodecs.list()).map(Heard::new, Heard::flights);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * Один слышимый снаряд: UUID (тот же у сущности, когда она появится у клиента), оружие, B-2 ли это (у бомбы то же
     * оружие), бурит ли бомба, где он, сдвиг за последний тик ({@code StrikeProjectile.velocity}) и куда смотрит нос,
     * фаза полёта и сколько она идёт, куда он летит (точка цели).
     */
    public record HeardFlight(UUID id, int weapon, boolean bomber, boolean drilling, Vec3 pos, Vec3 velocity, float yaw, float pitch,
                              int phase, int phaseAge, Vec3 aim) {
        public static final StreamCodec<ByteBuf, HeardFlight> CODEC = new StreamCodec<>() {
            @Override
            public HeardFlight decode(ByteBuf b) {
                return new HeardFlight(UUIDUtil.STREAM_CODEC.decode(b), ByteBufCodecs.VAR_INT.decode(b), b.readBoolean(), b.readBoolean(),
                        StreamCodecs.VEC3.decode(b), StreamCodecs.VEC3.decode(b), b.readFloat(), b.readFloat(), ByteBufCodecs.VAR_INT.decode(b), ByteBufCodecs.VAR_INT.decode(b),
                        StreamCodecs.VEC3.decode(b));
            }

            @Override
            public void encode(ByteBuf b, HeardFlight f) {
                UUIDUtil.STREAM_CODEC.encode(b, f.id);
                ByteBufCodecs.VAR_INT.encode(b, f.weapon);
                b.writeBoolean(f.bomber);
                b.writeBoolean(f.drilling);
                StreamCodecs.VEC3.encode(b, f.pos);
                StreamCodecs.VEC3.encode(b, f.velocity);
                b.writeFloat(f.yaw);
                b.writeFloat(f.pitch);
                ByteBufCodecs.VAR_INT.encode(b, f.phase);
                ByteBufCodecs.VAR_INT.encode(b, f.phaseAge);
                StreamCodecs.VEC3.encode(b, f.aim);
            }
        };
    }

    /** Открыть экран пульта (команда /airstrike menu). */
    public record OpenRemote() implements CustomPacketPayload {
        public static final Type<OpenRemote> TYPE = new Type<>(Airstrike.id("open_remote"));
        public static final StreamCodec<ByteBuf, OpenRemote> CODEC = StreamCodec.unit(new OpenRemote());

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * Отбой: убрано без взрыва — заглушить моторы отменённых снарядов ({@code projectiles}) и тревогу;
     * {@code nuclear} — отменены и ядерные удары (иначе ядерные снаряды летят дальше, их звук и камера остаются).
     */
    public record Cleared(boolean nuclear, List<UUID> projectiles) implements CustomPacketPayload {
        public static final Type<Cleared> TYPE = new Type<>(Airstrike.id("cleared"));
        public static final StreamCodec<ByteBuf, Cleared> CODEC = StreamCodec.composite(
                ByteBufCodecs.BOOL, Cleared::nuclear, UUIDUtil.STREAM_CODEC.apply(ByteBufCodecs.list()), Cleared::projectiles, Cleared::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * Запущена МБР: след разгона и вход боеголовки — клиенту для картинки.
     *
     * @param alarm игрок в радиусе тревоги (сирена гражданской обороны и отсчёт)
     * @param mine  запустил этот игрок (отсчёт до подрыва)
     * @param scale масштаб ядерных эффектов мира (effects_scale): высота подрыва и путь боеголовки в блоках
     */
    public record NukeWarning(int strikeId, Vec3 target, Vec3 launchPos, long launchTime, long detonateTime, double yieldKt,
                              boolean airBurst, boolean alarm, boolean mine, float scale) implements CustomPacketPayload {
        /** Высота подрыва над целью, блоки. */
        public double burstHeight() {
            return airBurst ? Yield.optimalBurstHeight(yieldKt) * scale : 0;
        }

        public static final Type<NukeWarning> TYPE = new Type<>(Airstrike.id("nuke_warning"));
        public static final StreamCodec<ByteBuf, NukeWarning> CODEC = new StreamCodec<>() {
            @Override
            public NukeWarning decode(ByteBuf b) {
                return new NukeWarning(ByteBufCodecs.VAR_INT.decode(b), StreamCodecs.VEC3.decode(b), StreamCodecs.VEC3.decode(b), b.readLong(), b.readLong(), b.readDouble(), b.readBoolean(),
                        b.readBoolean(), b.readBoolean(), b.readFloat());
            }

            @Override
            public void encode(ByteBuf b, NukeWarning w) {
                ByteBufCodecs.VAR_INT.encode(b, w.strikeId);
                StreamCodecs.VEC3.encode(b, w.target);
                StreamCodecs.VEC3.encode(b, w.launchPos);
                b.writeLong(w.launchTime);
                b.writeLong(w.detonateTime);
                b.writeDouble(w.yieldKt);
                b.writeBoolean(w.airBurst);
                b.writeBoolean(w.alarm);
                b.writeBoolean(w.mine);
                b.writeFloat(w.scale);
            }
        };

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Ядерный подрыв: всё остальное (вспышку, шар, гриб, волну, звук, дождь) клиент считает сам по модели. */
    public record NukeDetonation(Detonation detonation) implements CustomPacketPayload {
        public static final Type<NukeDetonation> TYPE = new Type<>(Airstrike.id("nuke_detonation"));
        public static final StreamCodec<ByteBuf, NukeDetonation> CODEC = Detonation.STREAM_CODEC
                .map(NukeDetonation::new, NukeDetonation::detonation);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Вход в мир и смена измерения: действующие подрывы и летящие МБР этого измерения. */
    public record NukeSync(List<Detonation> detonations, List<NukeWarning> warnings)
            implements CustomPacketPayload {
        public static final Type<NukeSync> TYPE = new Type<>(Airstrike.id("nuke_sync"));
        public static final StreamCodec<ByteBuf, NukeSync> CODEC = StreamCodec.composite(
                Detonation.STREAM_CODEC.apply(ByteBufCodecs.list()), NukeSync::detonations,
                NukeWarning.CODEC.apply(ByteBufCodecs.list()), NukeSync::warnings,
                NukeSync::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Облучение игрока: доза (Гр), мощность дозы (Р/ч, для счётчика Гейгера), заражение, стадия болезни. */
    public record Radiation(float doseGy, float rate, float contamination, int stage) implements CustomPacketPayload {
        public static final Type<Radiation> TYPE = new Type<>(Airstrike.id("radiation"));
        public static final StreamCodec<ByteBuf, Radiation> CODEC = StreamCodec.composite(
                ByteBufCodecs.FLOAT, Radiation::doseGy, ByteBufCodecs.FLOAT, Radiation::rate,
                ByteBufCodecs.FLOAT, Radiation::contamination, ByteBufCodecs.VAR_INT, Radiation::stage, Radiation::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
}
