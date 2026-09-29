package ua.zentix.airstrike.util;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.phys.Vec3;

/** Потоковые кодеки, которых нет в ванили 1.21.1 (пакеты мода и данные подрыва). */
public final class StreamCodecs {
    /** Вектор тремя double: у {@code Vec3} в 1.21.1 есть только {@code Codec}. */
    public static final StreamCodec<ByteBuf, Vec3> VEC3 = StreamCodec.composite(
            ByteBufCodecs.DOUBLE, Vec3::x, ByteBufCodecs.DOUBLE, Vec3::y, ByteBufCodecs.DOUBLE, Vec3::z, Vec3::new);

    private StreamCodecs() {}
}
