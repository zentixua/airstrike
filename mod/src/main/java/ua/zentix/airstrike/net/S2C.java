package ua.zentix.airstrike.net;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.phys.Vec3;
import ua.zentix.airstrike.Airstrike;

/** Сервер → клиент: только события; всё, что видно и слышно, клиент строит сам (частицы, звук с задержкой, тряска). */
public final class S2C {
    private S2C() {}

    private static final StreamCodec<ByteBuf, Vec3> VEC3 = StreamCodec.composite(
            ByteBufCodecs.DOUBLE, Vec3::x, ByteBufCodecs.DOUBLE, Vec3::y, ByteBufCodecs.DOUBLE, Vec3::z, Vec3::new);

    /** Взрыв: вид (0 — шахед, 1 — ракета, 2 — бомба под землёй), грунт, высота поверхности над точкой, сид. */
    public record Blast(int kind, Vec3 pos, int material, float surfaceY, long seed) implements CustomPacketPayload {
        public static final int DRONE = 0, MISSILE = 1, BUNKER = 2;
        public static final Type<Blast> TYPE = new Type<>(Airstrike.id("blast"));
        public static final StreamCodec<ByteBuf, Blast> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Blast::kind, VEC3, Blast::pos, ByteBufCodecs.VAR_INT, Blast::material,
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
                VEC3, BunkerImpact::pos, ByteBufCodecs.VAR_INT, BunkerImpact::material, BunkerImpact::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Газы подземного взрыва вырвались из скважины. */
    public record Vent(Vec3 pos, int material) implements CustomPacketPayload {
        public static final Type<Vent> TYPE = new Type<>(Airstrike.id("vent"));
        public static final StreamCodec<ByteBuf, Vent> CODEC = StreamCodec.composite(
                VEC3, Vent::pos, ByteBufCodecs.VAR_INT, Vent::material, Vent::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Свод над полостью обрушился: провал на поверхности. */
    public record Collapse(Vec3 pos, int material) implements CustomPacketPayload {
        public static final Type<Collapse> TYPE = new Type<>(Airstrike.id("collapse"));
        public static final StreamCodec<ByteBuf, Collapse> CODEC = StreamCodec.composite(
                VEC3, Collapse::pos, ByteBufCodecs.VAR_INT, Collapse::material, Collapse::new);

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
                VEC3, Siren::pos, ByteBufCodecs.VAR_INT, Siren::kind, Siren::new);

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

    /** Открыть экран пульта (команда /airstrike menu). */
    public record OpenRemote() implements CustomPacketPayload {
        public static final Type<OpenRemote> TYPE = new Type<>(Airstrike.id("open_remote"));
        public static final StreamCodec<ByteBuf, OpenRemote> CODEC = StreamCodec.unit(new OpenRemote());

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Отбой: всё убрано без взрыва — заглушить моторы и тревогу. */
    public record Cleared() implements CustomPacketPayload {
        public static final Type<Cleared> TYPE = new Type<>(Airstrike.id("cleared"));
        public static final StreamCodec<ByteBuf, Cleared> CODEC = StreamCodec.unit(new Cleared());

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
}
