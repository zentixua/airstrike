package ua.zentix.airstrike.net;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.codec.NeoForgeStreamCodecs;
import ua.zentix.airstrike.Airstrike;
import ua.zentix.airstrike.nuclear.Detonation;
import ua.zentix.airstrike.nuclear.model.Yield;
import ua.zentix.airstrike.target.Sightings;
import ua.zentix.airstrike.util.StreamCodecs;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Сервер → клиент: только события; всё, что видно и слышно, клиент строит сам (частицы, звук с задержкой, тряска). */
public final class S2C {
    private S2C() {}

    /**
     * Взрыв: вид (0 — шахед, 1 — ракета, 2 — бомба под землёй, 3 — снаряд РСЗО), грунт, высота поверхности над точкой, сид
     * и снаряд, который взорвался: его путь у клиента кончается в этот тик, и мотор звучит, пока до уха не дойдёт фронт
     * взрыва, а не стихает раньше.
     */
    public record Blast(int kind, Vec3 pos, int material, float surfaceY, long seed, Optional<UUID> projectile) implements CustomPacketPayload {
        public static final int DRONE = 0, MISSILE = 1, BUNKER = 2, ROCKET = 3;
        public static final Type<Blast> TYPE = new Type<>(Airstrike.id("blast"));
        public static final StreamCodec<ByteBuf, Blast> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Blast::kind, StreamCodecs.VEC3, Blast::pos, ByteBufCodecs.VAR_INT, Blast::material,
                ByteBufCodecs.FLOAT, Blast::surfaceY, ByteBufCodecs.VAR_LONG, Blast::seed,
                ByteBufCodecs.optional(UUIDUtil.STREAM_CODEC), Blast::projectile, Blast::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Подстанция выбита: хлопок, дуга и искры, затихающий гул трансформатора. */
    public record GridFailure(Vec3 pos, long seed) implements CustomPacketPayload {
        public static final Type<GridFailure> TYPE = new Type<>(Airstrike.id("grid_failure"));
        public static final StreamCodec<ByteBuf, GridFailure> CODEC = StreamCodec.composite(
                StreamCodecs.VEC3, GridFailure::pos, ByteBufCodecs.VAR_LONG, GridFailure::seed, GridFailure::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Квартал рядом погас (щелчок реле, гул обрывается) или в него вернулся свет ({@code on}). */
    public record GridDistrict(Vec3 pos, boolean on) implements CustomPacketPayload {
        public static final Type<GridDistrict> TYPE = new Type<>(Airstrike.id("grid_district"));
        public static final StreamCodec<ByteBuf, GridDistrict> CODEC = StreamCodec.composite(
                StreamCodecs.VEC3, GridDistrict::pos, ByteBufCodecs.BOOL, GridDistrict::on, GridDistrict::new);

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
     * уже может слышать ({@link ua.zentix.airstrike.strike.Hearing}) или видеть ({@code visibility.far_range}): по ним
     * клиент ведёт тот же звук с задержкой и Доплером, что и по сущности, и рисует снаряд вдали. Слышимые — раз
     * в {@link #PERIOD} тика (звук тянется по прямой между пакетами), только видимые — раз в {@link #FAR_PERIOD}: вдали
     * снаряд за тик сдвигается на экране на доли пикселя, а залп в 40 снарядов — это 40 записей в каждом пакете.
     */
    public record FarFlights(List<FarFlight> flights) implements CustomPacketPayload {
        public static final int PERIOD = 2, FAR_PERIOD = 4;
        public static final Type<FarFlights> TYPE = new Type<>(Airstrike.id("far_flights"));
        public static final StreamCodec<ByteBuf, FarFlights> CODEC = FarFlight.CODEC.apply(ByteBufCodecs.list()).map(FarFlights::new, FarFlights::flights);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * Один снаряд вдали: UUID (тот же у сущности, когда она появится у клиента), оружие, B-2 ли это (у бомбы то же
     * оружие), бурит ли бомба, слышно ли его (тогда пакеты чаще), где он, сдвиг за последний тик
     * ({@code StrikeProjectile.velocity}; float — точнее миллиметра), куда смотрит нос и крен (модель вдали кренится
     * в развороте, как вблизи), фаза полёта и сколько она идёт,
     * куда он летит (точка цели).
     */
    public record FarFlight(UUID id, int weapon, boolean bomber, boolean drilling, boolean audible, Vec3 pos, Vec3 velocity, float yaw,
                            float pitch, float roll, int phase, int phaseAge, Vec3 aim) {
        private static final int BOMBER = 1, DRILLING = 2, AUDIBLE = 4;

        public static final StreamCodec<ByteBuf, FarFlight> CODEC = new StreamCodec<>() {
            @Override
            public FarFlight decode(ByteBuf b) {
                UUID id = UUIDUtil.STREAM_CODEC.decode(b);
                int weapon = ByteBufCodecs.VAR_INT.decode(b);
                int flags = b.readByte();
                Vec3 pos = StreamCodecs.VEC3.decode(b);
                Vec3 velocity = new Vec3(b.readFloat(), b.readFloat(), b.readFloat());
                return new FarFlight(id, weapon, (flags & BOMBER) != 0, (flags & DRILLING) != 0, (flags & AUDIBLE) != 0, pos, velocity,
                        b.readFloat(), b.readFloat(), b.readFloat(), ByteBufCodecs.VAR_INT.decode(b), ByteBufCodecs.VAR_INT.decode(b),
                        StreamCodecs.VEC3.decode(b));
            }

            @Override
            public void encode(ByteBuf b, FarFlight f) {
                UUIDUtil.STREAM_CODEC.encode(b, f.id);
                ByteBufCodecs.VAR_INT.encode(b, f.weapon);
                b.writeByte((f.bomber ? BOMBER : 0) | (f.drilling ? DRILLING : 0) | (f.audible ? AUDIBLE : 0));
                StreamCodecs.VEC3.encode(b, f.pos);
                b.writeFloat((float) f.velocity.x);
                b.writeFloat((float) f.velocity.y);
                b.writeFloat((float) f.velocity.z);
                b.writeFloat(f.yaw);
                b.writeFloat(f.pitch);
                b.writeFloat(f.roll);
                ByteBufCodecs.VAR_INT.encode(b, f.phase);
                ByteBufCodecs.VAR_INT.encode(b, f.phaseAge);
                StreamCodecs.VEC3.encode(b, f.aim);
            }
        };

        /** Через сколько тиков придёт следующий пакет об этом снаряде. */
        public int period() {
            return audible ? FarFlights.PERIOD : FarFlights.FAR_PERIOD;
        }
    }

    /**
     * Метки для карты наведения и целей пульта — ответ на {@link C2S.MapPlayers}: свои игроки и замеченное стороной
     * спросившего в его измерении и не дальше {@code map_range} (кто попадает в список — {@code ServerActions.mapPlayers});
     * {@code shown} — карта их рисует ({@code map_players}), иначе они только цели пульта.
     */
    public record MapPlayers(List<MapPlayer> players, boolean shown) implements CustomPacketPayload {
        public static final Type<MapPlayers> TYPE = new Type<>(Airstrike.id("map_players"));
        public static final StreamCodec<ByteBuf, MapPlayers> CODEC = StreamCodec.composite(
                MapPlayer.CODEC.apply(ByteBufCodecs.list()), MapPlayers::players, ByteBufCodecs.BOOL, MapPlayers::shown, MapPlayers::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * Метка на карте: UUID, имя (у игрока — ник, им же пульт целится), что это, где оно (у чужого — где его видели
     * последний раз), свой ли по команде и сколько секунд назад видели (0 — видят сейчас).
     */
    public record MapPlayer(UUID id, String name, double x, double z, Sightings.Kind kind, boolean friendly, int age) {
        /** Имя игрока в Minecraft — до 16 знаков; запас на имена модов. */
        public static final int MAX_NAME = 64;
        private static final StreamCodec<ByteBuf, Sightings.Kind> KIND = ByteBufCodecs.idMapper(
                i -> Sightings.Kind.values()[Math.floorMod(i, Sightings.Kind.values().length)], Sightings.Kind::ordinal);
        public static final StreamCodec<ByteBuf, MapPlayer> CODEC = NeoForgeStreamCodecs.composite(
                UUIDUtil.STREAM_CODEC, MapPlayer::id, ByteBufCodecs.stringUtf8(MAX_NAME), MapPlayer::name,
                ByteBufCodecs.DOUBLE, MapPlayer::x, ByteBufCodecs.DOUBLE, MapPlayer::z, KIND, MapPlayer::kind,
                ByteBufCodecs.BOOL, MapPlayer::friendly, ByteBufCodecs.VAR_INT, MapPlayer::age, MapPlayer::new);
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
     * Отбой: заглушить моторы и камеру снарядов, которых больше нет ({@code projectiles}). {@code owner} — свой отбой этого
     * игрока (у него гаснет строка залпа, у остальных тревога остаётся: чужие удары летят дальше); пусто — отбой всего,
     * гаснет и тревога. {@code nuclear} — отменены и ядерные удары (иначе ядерные снаряды летят дальше, их звук и камера
     * остаются).
     */
    public record Cleared(Optional<UUID> owner, boolean nuclear, List<UUID> projectiles) implements CustomPacketPayload {
        public static final Type<Cleared> TYPE = new Type<>(Airstrike.id("cleared"));
        public static final StreamCodec<ByteBuf, Cleared> CODEC = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC.apply(ByteBufCodecs::optional), Cleared::owner, ByteBufCodecs.BOOL, Cleared::nuclear,
                UUIDUtil.STREAM_CODEC.apply(ByteBufCodecs.list()), Cleared::projectiles, Cleared::new);

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

    /**
     * Конец тика сервера повтора Flashback ({@link ua.zentix.airstrike.compat.FlashbackReplay}): место в записи и идёт ли
     * перемотка. Пакеты, пришедшие до метки, прочитаны в этом тике; только повтор, в игре её нет.
     */
    public record ReplayTick(int place, boolean seeking) implements CustomPacketPayload {
        public static final Type<ReplayTick> TYPE = new Type<>(Airstrike.id("replay_tick"));
        public static final StreamCodec<ByteBuf, ReplayTick> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, ReplayTick::place, ByteBufCodecs.BOOL, ReplayTick::seeking, ReplayTick::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * Зенитная ракета разорвалась в воздухе ({@code defense.Interceptor}): у цели или сама (самоликвидация, земля).
     * kill — цель сбита: её путь у клиента кончается в этот тик ({@code target}), обломки летят сущностями. Наземного
     * взрыва нет — только вспышка, облачко и хлопок; сид — вариант звука.
     */
    public record Intercept(Vec3 pos, boolean kill, Optional<UUID> target, long seed) implements CustomPacketPayload {
        public static final Type<Intercept> TYPE = new Type<>(Airstrike.id("intercept"));
        public static final StreamCodec<ByteBuf, Intercept> CODEC = StreamCodec.composite(
                StreamCodecs.VEC3, Intercept::pos, ByteBufCodecs.BOOL, Intercept::kill, ByteBufCodecs.optional(UUIDUtil.STREAM_CODEC), Intercept::target,
                ByteBufCodecs.VAR_LONG, Intercept::seed, Intercept::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * Экран радара ЗРК своим рядом с ним ({@code defense.SamBlockEntity}, раз в осмотр): где антенна, дальности радара
     * и огня, ракеты на направляющих и в запасе, цели. Перестал приходить — экран гаснет.
     */
    public record RadarScope(Vec3 radar, int range, int engageRange, int ready, int stock, List<Blip> blips) implements CustomPacketPayload {
        /** Больше целей экран не покажет (ближние — первыми). */
        public static final int MAX_BLIPS = 64;
        public static final Type<RadarScope> TYPE = new Type<>(Airstrike.id("radar_scope"));
        public static final StreamCodec<ByteBuf, RadarScope> CODEC = StreamCodec.composite(
                StreamCodecs.VEC3, RadarScope::radar, ByteBufCodecs.VAR_INT, RadarScope::range, ByteBufCodecs.VAR_INT, RadarScope::engageRange,
                ByteBufCodecs.VAR_INT, RadarScope::ready, ByteBufCodecs.VAR_INT, RadarScope::stock,
                Blip.CODEC.apply(ByteBufCodecs.list(MAX_BLIPS)), RadarScope::blips, RadarScope::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * Цель на экране радара: сдвиг от антенны по x и z, блоков, курс (градусы, как у сущности), вид оружия
     * ({@link ua.zentix.airstrike.strike.WeaponType#id()}: крупный корпус — крупнее отметка) и признаки {@link #HOSTILE},
     * {@link #ENGAGEABLE}, {@link #ENGAGED}.
     */
    public record Blip(float dx, float dz, float yaw, int weapon, int flags) {
        /** Чужая. */
        public static final int HOSTILE = 1;
        /** В дальности огня и стоит ракеты. */
        public static final int ENGAGEABLE = 2;
        /** За ней идёт ракета. */
        public static final int ENGAGED = 4;
        public static final StreamCodec<ByteBuf, Blip> CODEC = StreamCodec.composite(
                ByteBufCodecs.FLOAT, Blip::dx, ByteBufCodecs.FLOAT, Blip::dz, ByteBufCodecs.FLOAT, Blip::yaw,
                ByteBufCodecs.VAR_INT, Blip::weapon, ByteBufCodecs.VAR_INT, Blip::flags, Blip::new);

        public boolean is(int flag) {
            return (flags & flag) != 0;
        }
    }
}
