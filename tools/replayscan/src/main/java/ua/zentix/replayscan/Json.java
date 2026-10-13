package ua.zentix.replayscan;

import com.google.gson.stream.JsonWriter;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.Vec3i;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3fc;

import java.io.IOException;
import java.io.StringWriter;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Значения пакетов и пакетов модов — в JSON, без знания их классов: записи — по компонентам, прочие объекты — по
 * полям экземпляра; числа, строки, перечисления, UUID, id реестров, места ({@code [x, y, z]}), блоки (строкой, как
 * в {@code /setblock}), предметы, тексты, NBT (SNBT). Списки и строки обрезаются ({@link #LIST}, {@link #TEXT}),
 * глубина — не больше {@link #DEPTH}: пакет мода может нести что угодно, а строка ленты должна оставаться строкой.
 */
public final class Json {
    static final int LIST = 64, TEXT = 2000, DEPTH = 6;
    private static final Map<Class<?>, List<Field>> FIELDS = new ConcurrentHashMap<>();

    private Json() {}

    public static String of(Object value) {
        StringWriter s = new StringWriter();
        try (JsonWriter w = new JsonWriter(s)) {
            w.setSerializeNulls(true);
            write(w, value, 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return s.toString();
    }

    static void write(JsonWriter w, Object v, int depth) throws IOException {
        if (v == null) {
            w.nullValue();
        } else if (v instanceof Boolean b) {
            w.value(b);
        } else if (v instanceof Double d) {
            number(w, d);
        } else if (v instanceof Float f) {
            number(w, f);
        } else if (v instanceof Number n) {
            w.value(n);
        } else if (v instanceof CharSequence || v instanceof Character || v instanceof UUID) {
            w.value(cut(v.toString()));
        } else if (v instanceof Enum<?> e) {
            w.value(e.name().toLowerCase(java.util.Locale.ROOT));
        } else if (v instanceof ResourceLocation r) {
            w.value(r.toString());
        } else if (v instanceof ResourceKey<?> k) {
            w.value(k.location().toString());
        } else if (v instanceof Holder<?> h) {
            Optional<? extends ResourceKey<?>> key = h.unwrapKey();
            if (key.isPresent()) w.value(key.get().location().toString());
            else write(w, h.value(), depth + 1);
        } else if (v instanceof Vec3 p) {
            w.beginArray();
            number(w, p.x);
            number(w, p.y);
            number(w, p.z);
            w.endArray();
        } else if (v instanceof Vec3i p) {
            w.beginArray().value(p.getX()).value(p.getY()).value(p.getZ()).endArray();
        } else if (v instanceof ChunkPos c) {
            w.beginArray().value(c.x).value(c.z).endArray();
        } else if (v instanceof Vector3fc p) {
            w.beginArray();
            number(w, p.x());
            number(w, p.y());
            number(w, p.z());
            w.endArray();
        } else if (v instanceof BlockState s) {
            w.value(BlockStateParser.serialize(s));
        } else if (v instanceof Block b) {
            w.value(id(BuiltInRegistries.BLOCK, b));
        } else if (v instanceof Item i) {
            w.value(id(BuiltInRegistries.ITEM, i));
        } else if (v instanceof EntityType<?> t) {
            w.value(EntityType.getKey(t).toString());
        } else if (v instanceof ItemStack s) {
            w.beginObject().name("item").value(id(BuiltInRegistries.ITEM, s.getItem())).name("count").value(s.getCount()).endObject();
        } else if (v instanceof ParticleOptions p) {
            w.value(id(BuiltInRegistries.PARTICLE_TYPE, p.getType()));
        } else if (v instanceof Component c) {
            w.value(cut(c.getString()));
        } else if (v instanceof Tag t) {
            w.value(cut(t.toString()));
        } else if (v instanceof Optional<?> o) {
            write(w, o.orElse(null), depth);
        } else if (v instanceof OptionalInt o) {
            if (o.isPresent()) w.value(o.getAsInt());
            else w.nullValue();
        } else if (v instanceof OptionalDouble o) {
            if (o.isPresent()) number(w, o.getAsDouble());
            else w.nullValue();
        } else if (v instanceof byte[] b) {
            w.beginObject().name("bytes").value(b.length).endObject();
        } else if (depth >= DEPTH) {
            w.value(cut(v.getClass().getSimpleName()));
        } else if (v instanceof Collection<?> c) {
            list(w, c, c.size(), depth);
        } else if (v instanceof Map<?, ?> m) {
            w.beginObject();
            int n = 0;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (n++ >= LIST) break;
                w.name(cut(String.valueOf(e.getKey())));
                write(w, e.getValue(), depth + 1);
            }
            w.endObject();
        } else if (v.getClass().isArray()) {
            int len = Array.getLength(v);
            List<Object> items = new ArrayList<>(Math.min(len, LIST));
            for (int i = 0; i < Math.min(len, LIST); i++) items.add(Array.get(v, i));
            list(w, items, len, depth);
        } else if (v instanceof Record r) {
            w.beginObject();
            for (RecordComponent c : r.getClass().getRecordComponents()) {
                w.name(c.getName());
                write(w, component(r, c), depth + 1);
            }
            w.endObject();
        } else {
            fields(w, v, depth);
        }
    }

    /** Значение компонента записи (отражением; закрытые записи модов — через {@code setAccessible}). */
    public static Object component(Record r, RecordComponent c) {
        try {
            var m = c.getAccessor();
            if (!m.canAccess(r)) m.setAccessible(true);
            return m.invoke(r);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return "<" + e.getClass().getSimpleName() + ">";
        }
    }

    private static void list(JsonWriter w, Iterable<?> items, int size, int depth) throws IOException {
        w.beginArray();
        int n = 0;
        for (Object o : items) {
            if (n++ >= LIST) break;
            write(w, o, depth + 1);
        }
        if (size > LIST) w.value("+" + (size - LIST));
        w.endArray();
    }

    private static void fields(JsonWriter w, Object v, int depth) throws IOException {
        List<Field> fields = FIELDS.computeIfAbsent(v.getClass(), Json::fieldsOf);
        if (fields.isEmpty()) {
            w.value(cut(v.toString()));
            return;
        }
        w.beginObject();
        for (Field f : fields) {
            w.name(f.getName());
            Object value;
            try {
                value = f.get(v);
            } catch (ReflectiveOperationException | RuntimeException e) {
                value = "<" + e.getClass().getSimpleName() + ">";
            }
            write(w, value, depth + 1);
        }
        w.endObject();
    }

    private static List<Field> fieldsOf(Class<?> c) {
        List<Field> out = new ArrayList<>();
        for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
            for (Field f : k.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers()) || f.isSynthetic()) continue;
                try {
                    f.setAccessible(true);
                    out.add(f);
                } catch (RuntimeException e) {
                    // модуль не открыт: поле пропускается
                }
            }
        }
        return out;
    }

    private static void number(JsonWriter w, double d) throws IOException {
        if (Double.isFinite(d)) w.value(Math.rint(d * 1000) / 1000);
        else w.value(Double.toString(d));
    }

    private static <T> String id(Registry<T> registry, T value) {
        ResourceLocation key = registry.getKey(value);
        return key == null ? value.getClass().getSimpleName() : key.toString();
    }

    private static String cut(String s) {
        return s.length() <= TEXT ? s : s.substring(0, TEXT) + "…";
    }
}
