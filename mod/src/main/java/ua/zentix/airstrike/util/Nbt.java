package ua.zentix.airstrike.util;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/** Вектор в NBT тремя числами: {@code <prefix>_x/_y/_z}, без префикса — {@code x/y/z} (формат уже сохранённых миров). */
public final class Nbt {
    private Nbt() {}

    public static void putVec(CompoundTag tag, String prefix, Vec3 v) {
        tag.putDouble(key(prefix, "x"), v.x);
        tag.putDouble(key(prefix, "y"), v.y);
        tag.putDouble(key(prefix, "z"), v.z);
    }

    /** Вектор или {@code null}, если его нет в теге. */
    @Nullable
    public static Vec3 getVec(CompoundTag tag, String prefix) {
        if (!tag.contains(key(prefix, "x"))) return null;
        return new Vec3(tag.getDouble(key(prefix, "x")), tag.getDouble(key(prefix, "y")), tag.getDouble(key(prefix, "z")));
    }

    private static String key(String prefix, String axis) {
        return prefix.isEmpty() ? axis : prefix + "_" + axis;
    }
}
