package ua.zentix.incoming.util;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.phys.Vec3;

/** Потоковые кодеки, которых нет в ванили 1.21.1 (пакеты мода и данные подрыва). */
public final class StreamCodecs {
    /** Вектор тремя double: у {@code Vec3} в 1.21.1 есть только {@code Codec}. */
    public static final StreamCodec<ByteBuf, Vec3> VEC3 = StreamCodec.composite(
            ByteBufCodecs.DOUBLE, Vec3::x, ByteBufCodecs.DOUBLE, Vec3::y, ByteBufCodecs.DOUBLE, Vec3::z, Vec3::new);

    /**
     * Поле, дописанное в конец пакета, когда прежний формат уже ушёл в игру (повторы Flashback хранят пакеты байтами
     * и читаются нынешним кодеком, replay.md): пишется всегда, а читается, только если в пакете остались байты, — иначе
     * {@code absent}, «как до этого поля». Только в самом конце пакета (за ним — лишь такие же поля): пакет мода
     * кодек получает до конца кадра пакета, а середину так не прочесть — за полем шли бы байты следующих полей.
     */
    public static <T> StreamCodec<ByteBuf, T> trailing(StreamCodec<? super ByteBuf, T> codec, T absent) {
        return new StreamCodec<>() {
            @Override
            public T decode(ByteBuf b) {
                return b.isReadable() ? codec.decode(b) : absent;
            }

            @Override
            public void encode(ByteBuf b, T value) {
                codec.encode(b, value);
            }
        };
    }

    private StreamCodecs() {}
}
