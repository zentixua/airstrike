package ua.zentix.airstrike.strike;

import com.mojang.serialization.Codec;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.util.ByIdMap;
import net.minecraft.util.StringRepresentable;

import java.util.function.IntFunction;

/** Куда бьёт пуск с экрана пульта. Из бинокля — всегда в точку под прицелом. Новый режим — только в конец (id в пакетах). */
public enum TargetMode implements StringRepresentable {
    LOOK(0, "look"),
    AROUND_ME(1, "around_me"),
    PLAYER(2, "player"),
    AIRCRAFT(3, "aircraft"),
    /** Место, выбранное на карте наведения. */
    MAP(4, "map");

    public static final Codec<TargetMode> CODEC = StringRepresentable.fromEnum(TargetMode::values);
    private static final IntFunction<TargetMode> BY_ID = ByIdMap.continuous(TargetMode::id, values(), ByIdMap.OutOfBoundsStrategy.CLAMP);
    public static final StreamCodec<ByteBuf, TargetMode> STREAM_CODEC = ByteBufCodecs.idMapper(BY_ID, TargetMode::id);

    private final int id;
    private final String name;

    TargetMode(int id, String name) {
        this.id = id;
        this.name = name;
    }

    public int id() {
        return id;
    }

    @Override
    public String getSerializedName() {
        return name;
    }

    public Component displayName() {
        return Component.translatable("airstrike.target_mode." + name);
    }
}
