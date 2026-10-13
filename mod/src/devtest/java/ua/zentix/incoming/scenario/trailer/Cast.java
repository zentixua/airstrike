package ua.zentix.incoming.scenario.trailer;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import ua.zentix.incoming.Incoming;

import java.nio.ByteBuffer;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Статисты трейлера — боты Almighty: настоящие игроки без сети, всё делают пакетами клиента. Встроенный сервер моста не
 * поднимает, поэтому методы Almighty зовутся в том же процессе (как его GameTest): {@code GmServer.of(server)} →
 * {@code Api.methods(gm)} → {@code Method.call(new Args(json))}, отражением — Almighty не зависимость мода, а мод на
 * копии игры для съёмки. Всё — в потоке сервера: метод там выполняется сразу, будущее не ждём (вход вдали ждёт тиков).
 * <p>
 * Оружие — Point Blank: сервер мода стреляет и перезаряжает по пакетам клиента, а другие игроки видят выстрелы по
 * пакету состояния (огонь очередью, перезарядка). Бот шлёт их шагом {@code payload} — байты как у клиента мода
 * ({@code GunStateRequestPacket}: id состояния оружия двумя long, ячейка int, дальше поля пакета).
 */
final class Cast {
    private final MinecraftServer server;
    private Object gm;
    private Map<String, Object> methods;
    private java.lang.reflect.Constructor<?> args;
    private java.lang.reflect.Method call;

    Cast(MinecraftServer server) {
        this.server = server;
    }

    /** Метод Almighty с параметрами JSON; ошибка — в лог (ответ не нужен: сценарий смотрит на сам мир). */
    CompletableFuture<?> call(String method, JsonObject params) {
        try {
            bind();
            Object m = methods.get(method);
            if (m == null) throw new IllegalArgumentException("нет метода Almighty " + method);
            CompletableFuture<?> f = (CompletableFuture<?>) call.invoke(m, args.newInstance(params));
            return f.whenComplete((r, e) -> {
                if (e != null) Incoming.LOG.warn("TRAILER almighty {} {}: {}", method, params, e.toString());
            });
        } catch (ReflectiveOperationException | RuntimeException e) {
            Incoming.LOG.warn("TRAILER almighty {} {}", method, params, e);
            return CompletableFuture.failedFuture(e);
        }
    }

    @SuppressWarnings("unchecked")
    private void bind() throws ReflectiveOperationException {
        Class<?> gmClass = Class.forName("ua.zentix.almighty.GmServer");
        Object now = gmClass.getMethod("of", MinecraftServer.class).invoke(null, server);
        if (now == null) throw new IllegalStateException("Almighty не запущен на этом сервере");
        if (now == gm) return;
        gm = now;
        methods = (Map<String, Object>) Class.forName("ua.zentix.almighty.Api").getMethod("methods", gmClass).invoke(null, gm);
        Class<?> argsClass = Class.forName("ua.zentix.almighty.bridge.Args");
        args = argsClass.getConstructor(JsonObject.class);
        call = Class.forName("ua.zentix.almighty.bridge.Method").getMethod("call", argsClass);
    }

    // ---------------------------------------------------------------- боты

    /** Боты, которые ещё входят: вход с местом ждёт чанков в фоне ({@code War.settle}). */
    static final java.util.Set<String> joining = java.util.concurrent.ConcurrentHashMap.newKeySet();

    void spawn(String name, Vec3 at, float yaw, String skin) {
        joining.add(name);
        JsonObject p = new JsonObject();
        p.addProperty("name", name);
        p.add("pos", vec(at));
        p.addProperty("yaw", yaw);
        p.addProperty("gamemode", "survival");
        p.addProperty("auto_respawn", false);
        if (skin != null) p.addProperty("skin", skin);
        // бот — часть плана: не вошёл (предел bots.max Almighty, имя занято) — строка в лог записи, вход не ждать
        call("bot.spawn", p).whenComplete((r, e) -> {
            if (e == null) return;
            joining.remove(name);
            Incoming.LOG.error("SCENARIO film cast failed: бот {} не вошёл: {}", name, e.toString());
        });
    }

    void remove(String name) {
        joining.remove(name);
        JsonObject p = new JsonObject();
        p.addProperty("name", name);
        call("bot.remove", p);
    }

    /** Программа шагов бота ({@code bot.act}); {@code replace} — снять идущую. */
    void act(String name, JsonArray steps, boolean replace) {
        JsonObject p = new JsonObject();
        p.addProperty("name", name);
        p.add("actions", steps);
        p.addProperty("replace", replace);
        p.addProperty("wait", 0);
        call("bot.act", p);
    }

    ServerPlayer player(String name) {
        return server.getPlayerList().getPlayerByName(name);
    }

    void command(String c) {
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), c);
    }

    // ---------------------------------------------------------------- шаги

    static JsonArray steps() {
        return new JsonArray();
    }

    static JsonObject step(String key, JsonElement value) {
        JsonObject o = new JsonObject();
        o.add(key, value);
        return o;
    }

    static JsonObject step(String key, Number value) {
        JsonObject o = new JsonObject();
        o.addProperty(key, value);
        return o;
    }

    static JsonObject step(String key, String value) {
        JsonObject o = new JsonObject();
        o.addProperty(key, value);
        return o;
    }

    static JsonObject lookAt(Vec3 p) {
        return step("look_at", vec(p));
    }

    static JsonObject walkTo(double x, double z, boolean sprint) {
        JsonArray a = new JsonArray();
        a.add(x);
        a.add(z);
        JsonObject o = step("walk_to", a);
        o.addProperty("sprint", sprint);
        o.addProperty("within", 0.8);
        return o;
    }

    static JsonObject hold(String key, int ticks) {
        JsonObject o = step("hold", key);
        if (ticks > 0) o.addProperty("ticks", ticks);
        return o;
    }

    static JsonArray vec(Vec3 p) {
        JsonArray a = new JsonArray();
        a.add(p.x);
        a.add(p.y);
        a.add(p.z);
        return a;
    }

    // ---------------------------------------------------------------- Point Blank

    /** Состояния оружия для других игроков ({@code GunClientState.FireState}, номер в перечислении). */
    static final int IDLE = 1, FIRE_AUTO = 15, FIRE_SINGLE = 13, RELOAD = 27;

    /** Оружие в руке бота: id состояния и режима огня (из данных предмета мода); нет — null (мод ещё не дал id). */
    record Gun(long mid, long lid, long fmMost, long fmLeast, int slot) {
        JsonObject fire(long seed, boolean aiming) {
            ByteBuffer b = header(8 + 8 + 1 + 1 + 8);
            b.putLong(fmMost).putLong(fmLeast).put((byte) (aiming ? 1 : 0)).put((byte) 0).putLong(seed);
            return payload("hitscanfirerequestpacket", b);
        }

        JsonObject reload() {
            ByteBuffer b = header(16);
            b.putLong(fmMost).putLong(fmLeast);
            return payload("reloadrequestpacket", b);
        }

        /** Что видят другие игроки: огонь, перезарядка, покой. */
        JsonObject state(int fireState) {
            ByteBuffer b = header(4);
            b.putInt(fireState);
            return payload("mainheldsimplifiedstatesyncrequest", b);
        }

        JsonObject aim(boolean on) {
            ByteBuffer b = header(1);
            b.put((byte) (on ? 1 : 0));
            return payload("aimingchangerequestpacket", b);
        }

        private ByteBuffer header(int rest) {
            return ByteBuffer.allocate(8 + 8 + 4 + rest).putLong(mid).putLong(lid).putInt(slot);
        }

        private static JsonObject payload(String id, ByteBuffer b) {
            JsonObject o = step("payload", "pointblank:" + id);
            o.addProperty("data", Base64.getEncoder().encodeToString(b.array()));
            return o;
        }
    }

    static Gun gun(ServerPlayer p) {
        ItemStack stack = p.getMainHandItem();
        DataComponentType<?> type = BuiltInRegistries.DATA_COMPONENT_TYPE.get(ResourceLocation.fromNamespaceAndPath("pointblank", "custom_tag"));
        if (type == null || !(stack.get(type) instanceof CompoundTag tag) || !tag.contains("mid") || !tag.hasUUID("fmid")) return null;
        UUID fm = tag.getUUID("fmid");
        return new Gun(tag.getLong("mid"), tag.getLong("lid"), fm.getMostSignificantBits(), fm.getLeastSignificantBits(),
                p.getInventory().selected);
    }

    static String fmt(String f, Object... a) {
        return String.format(Locale.ROOT, f, a);
    }
}
