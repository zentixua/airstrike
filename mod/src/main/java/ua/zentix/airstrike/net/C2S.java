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
import ua.zentix.airstrike.strike.Waypoints;
import ua.zentix.airstrike.util.StreamCodecs;

import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;

/** Клиент → сервер. Сервер ничему не верит на слово: руку, предмет, дальность и цель проверяет сам. */
public final class C2S {
    private C2S() {}

    static final StreamCodec<ByteBuf, InteractionHand> HAND = ByteBufCodecs.BOOL.map(b -> b ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND, h -> h == InteractionHand.OFF_HAND);

    /**
     * Что клиент видит под прицелом бинокля. Движущуюся цель клиент и сервер видят чуть по-разному,
     * поэтому клиент говорит, что именно выбрано, а сервер находит это у себя и проверяет.
     *
     * @param kind       0 — точка, 1 — сущность (entityId), 2 — аппарат Sable (plotPos — точка в плоте), 3 — место на земле
     *                   с карты (x и z; высоту земли там находит сервер)
     * @param mapSurface у места с карты — верх земли там по карте клиента (первый воздух над землёй, как карта высот),
     *                   если карта его знает: оценка сервера, пока чанк места у него не готов
     */
    public record AimHint(int kind, Vec3 point, int entityId, Vec3 plotPos, Optional<Integer> mapSurface) {
        public static final int POINT = 0, ENTITY = 1, AIRCRAFT = 2, GROUND = 3;

        public AimHint(int kind, Vec3 point, int entityId, Vec3 plotPos) {
            this(kind, point, entityId, plotPos, Optional.empty());
        }

        /** Место на земле, выбранное на карте: x и z и верх земли по карте клиента, если он там известен. */
        public static AimHint ground(double x, double z, OptionalInt mapSurface) {
            return new AimHint(GROUND, new Vec3(x, 0, z), 0, Vec3.ZERO,
                    mapSurface.isPresent() ? Optional.of(mapSurface.getAsInt()) : Optional.empty());
        }
        public static final StreamCodec<ByteBuf, AimHint> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, AimHint::kind, StreamCodecs.VEC3, AimHint::point, ByteBufCodecs.VAR_INT, AimHint::entityId,
                StreamCodecs.VEC3, AimHint::plotPos, ByteBufCodecs.optional(ByteBufCodecs.VAR_INT), AimHint::mapSurface, AimHint::new);
    }

    /**
     * Пуск. Из бинокля — по {@code aim} (что под прицелом); с экрана пульта — по режиму цели из loadout
     * (куда смотрю / вокруг меня / игрок / аппарат из списка). {@code via} — точки маршрута с карты наведения
     * (пусто — путь строит пуск).
     */
    public record Fire(Loadout loadout, Optional<AimHint> aim, Optional<UUID> aircraft, Waypoints via) implements CustomPacketPayload {
        public static final Type<Fire> TYPE = new Type<>(Airstrike.id("fire"));
        public static final StreamCodec<ByteBuf, Fire> CODEC = StreamCodec.composite(
                Loadout.STREAM_CODEC, Fire::loadout,
                ByteBufCodecs.optional(AimHint.CODEC), Fire::aim,
                ByteBufCodecs.optional(UUIDUtil.STREAM_CODEC), Fire::aircraft,
                Waypoints.STREAM_CODEC, Fire::via,
                Fire::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Перенацелить свой снаряд на то, что под прицелом его камеры. */
    public record Retarget(UUID projectile, AimHint aim) implements CustomPacketPayload {
        public static final Type<Retarget> TYPE = new Type<>(Airstrike.id("retarget"));
        public static final StreamCodec<ByteBuf, Retarget> CODEC = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC, Retarget::projectile, AimHint.CODEC, Retarget::aim, Retarget::new);

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

    /**
     * Место выбрано на карте (клик), приказа ещё нет: сервер начинает грузить район заранее ({@code PickHints}).
     * Только x и z; проверки те же, что у приказа по карте.
     */
    public record Pick(double x, double z) implements CustomPacketPayload {
        public static final Type<Pick> TYPE = new Type<>(Airstrike.id("pick"));
        public static final StreamCodec<ByteBuf, Pick> CODEC = StreamCodec.composite(
                ByteBufCodecs.DOUBLE, Pick::x, ByteBufCodecs.DOUBLE, Pick::z, Pick::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Карта наведения открыта: где сейчас игроки (ответ — {@link S2C.MapPlayers}); клиент спрашивает раз в полсекунды. */
    public record MapPlayers() implements CustomPacketPayload {
        public static final Type<MapPlayers> TYPE = new Type<>(Airstrike.id("map_players_query"));
        public static final StreamCodec<ByteBuf, MapPlayers> CODEC = StreamCodec.unit(new MapPlayers());

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * Игрок смотрит глазами своего снаряда {@code projectile} (пусто — вернулся к себе): его камера замечает чужих
     * ({@code Sightings}). Клиент шлёт при каждой смене.
     */
    public record Watch(Optional<UUID> projectile) implements CustomPacketPayload {
        public static final Type<Watch> TYPE = new Type<>(Airstrike.id("watch"));
        public static final StreamCodec<ByteBuf, Watch> CODEC = UUIDUtil.STREAM_CODEC.apply(ByteBufCodecs::optional).map(Watch::new, Watch::projectile);

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
