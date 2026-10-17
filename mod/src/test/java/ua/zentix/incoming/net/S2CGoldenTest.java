package ua.zentix.incoming.net;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.loading.LoadingModList;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.nuclear.flash.FlashMarks;
import ua.zentix.incoming.target.Sightings;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Пакеты сервер → клиент в байтах (replay.md, «Формат пакетов»): повтор Flashback хранит каждый пакет мода байтами и
 * через версии читает их нынешним кодеком. У каждого пакета — образцы байтов каждого его формата
 * ({@code s2c-golden/<id>.<n>.hex}, n — номер формата, последний — нынешний), и нынешний кодек обязан прочесть каждый в
 * ожидаемое значение: старый формат — с полями, которых в нём ещё не было, «как до них». Нынешний кодек пишет ровно
 * байты последнего образца.
 * <p>
 * Поменяли кодек — тест падает. Дописали поле в конец пакета ({@code StreamCodecs.trailing}) — новый формат: значение в
 * конец списка формата пакета, файла нет — тест его пишет (и падает: проверить и закоммитить), прежние образцы
 * остаются и читаются со значением по умолчанию. Всё прочее (убрали поле, сменили тип или порядок, дописали внутрь
 * вложенной записи, за которой идут другие поля) старые образцы не прочтут: такой пакет — с новым id.
 */
class S2CGoldenTest {
    private static final String DIR = "s2c-golden/";
    private static final Path SOURCE = Path.of("src/test/resources", DIR);

    @BeforeAll
    static void bootstrap() {
        // состояния блоков в пакете обломков — номерами реестра игры. FML в юнит-тесте нет: загрузке флагов NeoForge —
        // пустой список модов, а номера состояний раздают, как игра при заморозке реестров (у NeoForge — там, а не в Blocks)
        SharedConstants.tryDetectVersion();
        if (LoadingModList.get() == null) LoadingModList.of(List.of(), List.of(), List.of(), List.of(), Map.of());
        Bootstrap.bootStrap();
        if (Block.BLOCK_STATE_REGISTRY.size() == 0) {
            for (Block block : BuiltInRegistries.BLOCK) block.getStateDefinition().getPossibleStates().forEach(Block.BLOCK_STATE_REGISTRY::add);
        }
    }

    /** Пакет и его форматы по порядку: значение, которое нынешний кодек читает из образца формата n (с 1). */
    private record Golden<T extends CustomPacketPayload>(CustomPacketPayload.Type<T> type, StreamCodec<ByteBuf, T> codec, List<T> formats) {
        String file(int n) {
            return type.id().getPath() + "." + n + ".hex";
        }
    }

    @SafeVarargs
    private static <T extends CustomPacketPayload> Golden<T> golden(CustomPacketPayload.Type<T> type, StreamCodec<ByteBuf, T> codec, T... formats) {
        List<T> list = new ArrayList<>();
        for (T f : formats) list.add(f);
        return new Golden<>(type, codec, List.copyOf(list));
    }

    private static final UUID A = new UUID(0x0123456789abcdefL, 0xfedcba9876543210L), B = new UUID(-2, 42);
    private static final Vec3 P = new Vec3(-1234.5, 70.25, 98765.125), Q = new Vec3(0.5, -63, -4096);
    /** Подрыв со всеми полями (время суток — вечер шестых суток). */
    private static final Detonation D = new Detonation(4, new Vec3(-120.5, 640, 2048.25), 64, 15, true, 123_456, 0.5f, 8, 20_000, 7, 2f,
            true, 0.42f, 5 * 24000 + 13000);
    private static final Detonation D2 = new Detonation(5, Q, -12.5, 1000, false, 200_000, -2.25f, 12.5f, 5000, -77, 0.5f, false, 1f, 18000);
    private static final S2C.NukeWarning W = new S2C.NukeWarning(9, P, Q, 100, 2500, 300, true, true, false, 2f);

    private static List<Golden<?>> cases() {
        return List.of(
                golden(S2C.Blast.TYPE, S2C.Blast.CODEC,
                        new S2C.Blast(S2C.Blast.ROCKET, P, 3, 70.5f, -42L, Optional.of(A))),
                golden(S2C.BunkerImpact.TYPE, S2C.BunkerImpact.CODEC, new S2C.BunkerImpact(P, 2)),
                golden(S2C.Vent.TYPE, S2C.Vent.CODEC, new S2C.Vent(Q, 1)),
                golden(S2C.Collapse.TYPE, S2C.Collapse.CODEC, new S2C.Collapse(P, 4)),
                golden(S2C.GridFailure.TYPE, S2C.GridFailure.CODEC, new S2C.GridFailure(Q, Long.MIN_VALUE + 7)),
                golden(S2C.GridDistrict.TYPE, S2C.GridDistrict.CODEC, new S2C.GridDistrict(P, true)),
                golden(S2C.Quake.TYPE, S2C.Quake.CODEC, new S2C.Quake(300, true)),
                golden(S2C.Siren.TYPE, S2C.Siren.CODEC, new S2C.Siren(Q, S2C.Siren.MISSILE)),
                golden(S2C.SalvoStatus.TYPE, S2C.SalvoStatus.CODEC, new S2C.SalvoStatus(6, 17, 40)),
                golden(S2C.Cleared.TYPE, S2C.Cleared.CODEC, new S2C.Cleared(Optional.of(B), true, List.of(A, B))),
                golden(S2C.NukeWarning.TYPE, S2C.NukeWarning.CODEC, W),
                // 1 — выпуск 3.1.0; 2 — wave (скорость стены); 3 — blackout (ЭМИ); 4 — время суток подрыва
                golden(S2C.NukeDetonation.TYPE, S2C.NukeDetonation.CODEC,
                        new S2C.NukeDetonation(D.withWave(Detonation.OLD_WAVE).withDayTime(Detonation.NO_DAY_TIME), 0f),
                        new S2C.NukeDetonation(D.withDayTime(Detonation.NO_DAY_TIME), 0f),
                        new S2C.NukeDetonation(D.withDayTime(Detonation.NO_DAY_TIME), 242f),
                        new S2C.NukeDetonation(D, 242f)),
                golden(S2C.NukeDebris.TYPE, S2C.NukeDebris.CODEC, new S2C.NukeDebris(4, List.of(
                        new S2C.NukeDebris.Piece(new BlockPos(-100, 70, 2000), Blocks.STONE.defaultBlockState()),
                        new S2C.NukeDebris.Piece(new BlockPos(1, -60, -1), Blocks.OAK_PLANKS.defaultBlockState())))),
                golden(S2C.NukeFlashMarks.TYPE, S2C.NukeFlashMarks.CODEC, new S2C.NukeFlashMarks(-123_456_789_012L, new FlashMarks(List.of(
                        new FlashMarks.Layer(3, 100, new int[]{0x12345678, -5}, new int[0], new long[0]),
                        new FlashMarks.Layer(4, 123_456, new int[0], new int[]{0x0abcdef0},
                                new long[]{1, 2, 3, 4, 5, 6, 7, Long.MIN_VALUE + 8}))))),
                // 1 — выпуск 3.1.0; 2 — скорости стены и время суток подрывов списками в конце
                golden(S2C.NukeSync.TYPE, S2C.NukeSync.CODEC,
                        new S2C.NukeSync(List.of(D.withWave(Detonation.OLD_WAVE).withDayTime(Detonation.NO_DAY_TIME),
                                D2.withWave(Detonation.OLD_WAVE).withDayTime(Detonation.NO_DAY_TIME)), List.of(W)),
                        new S2C.NukeSync(List.of(D, D2), List.of(W))),
                golden(S2C.Radiation.TYPE, S2C.Radiation.CODEC, new S2C.Radiation(1.5f, 250f, 0.125f, 2)),
                golden(S2C.Flights.TYPE, S2C.Flights.CODEC, new S2C.Flights(List.of(
                        new S2C.Flight(A, 2, 1, 340, P, Q, false, S2C.Flight.TARGET_NAMED, "ZentixUA", false),
                        new S2C.Flight(B, 6, 3, 0, Q, P, true, S2C.Flight.TARGET_AIRCRAFT, "", true)))),
                golden(S2C.FarFlights.TYPE, S2C.FarFlights.CODEC, new S2C.FarFlights(List.of(
                        new S2C.FarFlight(A, 4, true, true, false, P, new Vec3(0.5, -1.25, 12), 90.5f, -30f, 15.25f, 2, 77, Q)))),
                golden(S2C.MapPlayers.TYPE, S2C.MapPlayers.CODEC, new S2C.MapPlayers(List.of(
                        new S2C.MapPlayer(A, "ENOTzRPG", -100.5, 2000.25, Sightings.Kind.PLAYER, true, 0),
                        new S2C.MapPlayer(B, "", 7, -8, Sightings.Kind.AIRCRAFT, false, 12)), true)),
                golden(S2C.OpenRemote.TYPE, S2C.OpenRemote.CODEC, new S2C.OpenRemote()),
                golden(S2C.ReplayTick.TYPE, S2C.ReplayTick.CODEC, new S2C.ReplayTick(123_456, true)),
                golden(S2C.Intercept.TYPE, S2C.Intercept.CODEC, new S2C.Intercept(P, true, Optional.of(B), 99)),
                golden(S2C.RadarScope.TYPE, S2C.RadarScope.CODEC, new S2C.RadarScope(Q, 2000, 1200, 2, 6, List.of(
                        new S2C.Blip(-150.5f, 300.25f, 45f, 1, S2C.Blip.HOSTILE | S2C.Blip.ENGAGED))))
        );
    }

    @TestFactory
    List<DynamicTest> everyFormatDecodes() {
        List<DynamicTest> tests = new ArrayList<>();
        for (Golden<?> g : cases()) {
            for (int n = 1; n <= g.formats.size(); n++) {
                int format = n;
                tests.add(DynamicTest.dynamicTest(g.file(n), () -> check(g, format)));
            }
        }
        return tests;
    }

    private static <T extends CustomPacketPayload> void check(Golden<T> g, int n) throws IOException {
        T expected = g.formats.get(n - 1);
        boolean current = n == g.formats.size();
        byte[] bytes = read(g.file(n));
        if (bytes == null) {
            if (!current) fail("нет образца прежнего формата " + DIR + g.file(n));
            Path file = SOURCE.resolve(g.file(n));
            assertTrue(Files.isDirectory(SOURCE), "запуск не из каталога mod: " + SOURCE.toAbsolutePath());
            Files.writeString(file, hex(encode(g.codec, expected)), StandardCharsets.US_ASCII);
            fail("новый формат: образец записан в " + file + " — проверить и закоммитить");
        }
        ByteBuf buf = Unpooled.wrappedBuffer(bytes);
        T decoded = g.codec.decode(buf);
        assertFalse(buf.isReadable(), "лишние байты после разбора: " + buf.readableBytes());
        assertTrue(same(expected, decoded), "прочитано не то:\n  ждали    " + expected + "\n  прочитано " + decoded);
        if (current) {
            assertArrayEquals(bytes, encode(g.codec, expected), "кодек пишет не те байты, что образец " + g.file(n)
                    + ": дописали поле в конец пакета — новый формат в конец списка (образец запишет этот тест), прежние"
                    + " образцы остаются; иначе — пакет с новым id (replay.md)");
        }
    }

    /** Каждый пакет клиенту, который регистрирует {@link IncomingNetwork}, — с образцами. */
    @Test
    void everyClientboundPayloadHasGoldenBytes() throws IOException {
        String source = Files.readString(Path.of("src/main/java/ua/zentix/incoming/net/IncomingNetwork.java"));
        Matcher m = Pattern.compile("playToClient\\(\\s*S2C\\.(\\w+)\\.TYPE").matcher(source);
        Set<String> covered = new HashSet<>();
        for (Golden<?> g : cases()) covered.add(g.type.id().toString());
        int registered = 0;
        while (m.find()) {
            registered++;
            String id = typeOf(m.group(1)).id().toString();
            assertTrue(covered.contains(id), "нет образцов байтов пакета " + id);
        }
        assertEquals(registered, source.split("playToClient\\(", -1).length - 1, "пакет клиенту не из S2C — добавить сюда");
        assertEquals(registered, cases().size(), "образцы пакета, которого IncomingNetwork не регистрирует");
    }

    private static CustomPacketPayload.Type<?> typeOf(String name) {
        try {
            return (CustomPacketPayload.Type<?>) Class.forName(S2C.class.getName() + "$" + name).getField("TYPE").get(null);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("S2C." + name + ".TYPE", e);
        }
    }

    private static <T> byte[] encode(StreamCodec<ByteBuf, T> codec, T value) {
        ByteBuf buf = Unpooled.buffer();
        try {
            codec.encode(buf, value);
            return ByteBufUtil.getBytes(buf);
        } finally {
            buf.release();
        }
    }

    private static byte[] read(String name) throws IOException {
        try (InputStream in = S2CGoldenTest.class.getClassLoader().getResourceAsStream(DIR + name)) {
            if (in == null) return null;
            return ByteBufUtil.decodeHexDump(new String(in.readAllBytes(), StandardCharsets.US_ASCII).replaceAll("\\s+", ""));
        }
    }

    /** Шестнадцатеричный текст по 32 байта в строке: в диффе видно, где формат вырос. */
    private static String hex(byte[] bytes) {
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < bytes.length; i += 32) {
            s.append(ByteBufUtil.hexDump(bytes, i, Math.min(32, bytes.length - i))).append('\n');
        }
        return s.toString();
    }

    /** Равенство значений пакета: записи — по полям, массивы и списки — по элементам (у записи с массивом equals — ссылки). */
    private static boolean same(Object a, Object b) {
        if (a instanceof List<?> x && b instanceof List<?> y) {
            if (x.size() != y.size()) return false;
            for (int i = 0; i < x.size(); i++) if (!same(x.get(i), y.get(i))) return false;
            return true;
        }
        if (a instanceof Optional<?> x && b instanceof Optional<?> y) {
            return x.isPresent() == y.isPresent() && (x.isEmpty() || same(x.get(), y.get()));
        }
        if (a != null && b != null && a.getClass().isRecord() && a.getClass() == b.getClass()) {
            for (RecordComponent c : a.getClass().getRecordComponents()) {
                try {
                    if (!same(c.getAccessor().invoke(a), c.getAccessor().invoke(b))) return false;
                } catch (ReflectiveOperationException e) {
                    throw new AssertionError(c.getName(), e);
                }
            }
            return true;
        }
        return Objects.deepEquals(a, b);
    }
}
