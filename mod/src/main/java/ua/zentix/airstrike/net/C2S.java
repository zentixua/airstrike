package ua.zentix.airstrike.net;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.Vec3;
import ua.zentix.airstrike.Airstrike;
import ua.zentix.airstrike.strike.Loadout;

import java.util.Optional;
import java.util.UUID;

/** Клиент → сервер. Сервер ничему не верит на слово: руку, предмет, дальность и цель проверяет сам. */
public final class C2S {
    private C2S() {}

    static final StreamCodec<ByteBuf, InteractionHand> HAND = ByteBufCodecs.BOOL.map(b -> b ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND, h -> h == InteractionHand.OFF_HAND);
    private static final StreamCodec<ByteBuf, Vec3> VEC3 = StreamCodec.composite(
            ByteBufCodecs.DOUBLE, Vec3::x, ByteBufCodecs.DOUBLE, Vec3::y, ByteBufCodecs.DOUBLE, Vec3::z, Vec3::new);

    /**
     * Что клиент видит под прицелом бинокля. Движущуюся цель клиент и сервер видят чуть по-разному,
     * поэтому клиент говорит, что именно выбрано, а сервер находит это у себя и проверяет.
     *
     * @param kind   0 — точка, 1 — сущность (entityId), 2 — аппарат Sable (plotPos — точка в плоте)
     */
    public record AimHint(int kind, Vec3 point, int entityId, Vec3 plotPos) {
        public static final int POINT = 0, ENTITY = 1, AIRCRAFT = 2;
        public static final StreamCodec<ByteBuf, AimHint> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, AimHint::kind, VEC3, AimHint::point, ByteBufCodecs.VAR_INT, AimHint::entityId, VEC3, AimHint::plotPos, AimHint::new);
    }

    /**
     * Пуск. Из бинокля — по {@code aim} (что под прицелом); с экрана пульта — по режиму цели из loadout
     * (куда смотрю / вокруг меня / игрок / аппарат из списка).
     */
    public record Fire(Loadout loadout, Optional<AimHint> aim, Optional<UUID> aircraft) implements CustomPacketPayload {
        public static final Type<Fire> TYPE = new Type<>(Airstrike.id("fire"));
        public static final StreamCodec<ByteBuf, Fire> CODEC = StreamCodec.composite(
                Loadout.STREAM_CODEC, Fire::loadout,
                ByteBufCodecs.optional(AimHint.CODEC), Fire::aim,
                ByteBufCodecs.optional(UUIDUtil.STREAM_CODEC), Fire::aircraft,
                Fire::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Сохранить настройки пульта в предмете в руке. */
    public record SetLoadout(InteractionHand hand, Loadout loadout) implements CustomPacketPayload {
        public static final Type<SetLoadout> TYPE = new Type<>(Airstrike.id("set_loadout"));
        public static final StreamCodec<ByteBuf, SetLoadout> CODEC = StreamCodec.composite(
                HAND, SetLoadout::hand, Loadout.STREAM_CODEC, SetLoadout::loadout, SetLoadout::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Отбой: убрать все летящие снаряды и залпы без взрыва. */
    public record Clear() implements CustomPacketPayload {
        public static final Type<Clear> TYPE = new Type<>(Airstrike.id("clear"));
        public static final StreamCodec<ByteBuf, Clear> CODEC = StreamCodec.unit(new Clear());

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
}
