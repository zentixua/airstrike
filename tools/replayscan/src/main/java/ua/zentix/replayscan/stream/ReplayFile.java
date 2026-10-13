package ua.zentix.replayscan.stream;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedInputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Повтор Flashback (0.39) как файл: zip с {@code metadata.json}, кусками записи {@code cN.flashback} и кэшем чанков
 * {@code level_chunk_caches/N}. Без Minecraft: только байты и тики (юнит-тесты — {@code TimelineTest}).
 * <p>
 * Кусок: {@code int} {@link #MAGIC}, varint числа имён действий, имена (строки varint-длины), {@code int} длина снимка,
 * снимок (действия), затем действия до конца. Действие — varint номер имени, {@code int} длина тела, тело. Куски идут
 * в порядке {@code chunks} метаданных, начало куска — сумма длительностей прежних (так считает {@code ReplayServer}).
 */
public final class ReplayFile implements Closeable {
    public static final int MAGIC = 0xD780E884;
    /** Записей в одном файле кэша чанков ({@code ReplayChunkCache.CHUNK_CACHE_SIZE}). */
    public static final int CHUNK_CACHE_SIZE = 10000;

    /** Кусок записи: имя файла в zip, первый тик, длительность в тиках, ставится ли его снимок при входе в кусок. */
    public record Segment(String file, int start, int duration, boolean forceSnapshot) {}

    private final ZipFile zip;
    private final JsonObject meta;
    private final List<Segment> segments;
    /** Номер файла кэша → места чанков в нём (x, z — {@link #pack}). */
    private final Map<Integer, long[]> chunkCaches = new HashMap<>();

    private ReplayFile(ZipFile zip, JsonObject meta, List<Segment> segments) {
        this.zip = zip;
        this.meta = meta;
        this.segments = segments;
    }

    public static ReplayFile open(Path path) throws IOException {
        ZipFile zip = new ZipFile(path.toFile());
        try {
            byte[] raw = read(zip, "metadata.json");
            if (raw == null) throw new IOException("no metadata.json in " + path);
            JsonObject meta = JsonParser.parseString(new String(raw, StandardCharsets.UTF_8)).getAsJsonObject();
            List<Segment> segments = new ArrayList<>();
            int start = 0;
            for (Entry<String, com.google.gson.JsonElement> e : meta.getAsJsonObject("chunks").entrySet()) {
                JsonObject c = e.getValue().getAsJsonObject();
                int duration = c.get("duration").getAsInt();
                boolean force = c.has("forcePlaySnapshot") && c.get("forcePlaySnapshot").getAsBoolean();
                segments.add(new Segment(e.getKey(), start, duration, force));
                start += duration;
            }
            return new ReplayFile(zip, meta, Collections.unmodifiableList(segments));
        } catch (IOException | RuntimeException e) {
            zip.close();
            throw e;
        }
    }

    public JsonObject meta() {
        return meta;
    }

    public List<Segment> segments() {
        return segments;
    }

    /** Тиков в записи: {@code total_ticks} метаданных, без него — сумма кусков. */
    public int totalTicks() {
        if (meta.has("total_ticks")) return meta.get("total_ticks").getAsInt();
        Segment last = segments.isEmpty() ? null : segments.getLast();
        return last == null ? 0 : last.start + last.duration;
    }

    public SegmentData load(Segment s) throws IOException {
        byte[] data = read(zip, s.file);
        if (data == null) throw new IOException("no " + s.file + " in replay");
        return new SegmentData(s, data);
    }

    /**
     * Место чанка {@code index} кэша чанков ({@link #pack}) или {@link Long#MIN_VALUE}, если его нет. Запись кэша —
     * {@code int} длина и пакет {@code ClientboundLevelChunkWithLightPacket}: varint id пакета, {@code int x}, {@code int z}.
     */
    public long cachedChunk(int index) throws IOException {
        int file = index / CHUNK_CACHE_SIZE;
        long[] places = chunkCaches.get(file);
        if (places == null) {
            ZipEntry e = zip.getEntry("level_chunk_caches/" + file);
            if (e == null) places = new long[0];
            else try (InputStream in = zip.getInputStream(e)) {
                places = chunkPlaces(in);
            }
            chunkCaches.put(file, places);
        }
        int i = index - file * CHUNK_CACHE_SIZE;
        return i < places.length ? places[i] : Long.MIN_VALUE;
    }

    /** Места чанков файла кэша потоком: из записи нужны только x и z, сами пакеты (сотни МБ на файл) не читаются. */
    static long[] chunkPlaces(InputStream raw) throws IOException {
        DataInputStream in = new DataInputStream(new BufferedInputStream(raw, 1 << 16));
        List<Long> out = new ArrayList<>();
        try {
            while (true) {
                int size = in.readInt();
                if (size < 0) break;
                // varint id пакета, потом int x, int z
                int read = 0;
                boolean more = true;
                while (more && read < size && read < 5) {
                    more = (in.readUnsignedByte() & 0x80) != 0;
                    read++;
                }
                long place = Long.MIN_VALUE;
                if (!more && size - read >= 8) {
                    place = pack(in.readInt(), in.readInt());
                    read += 8;
                }
                in.skipNBytes(size - read);
                out.add(place);
            }
        } catch (EOFException end) {
            // конец файла (или обрезанная последняя запись — без места)
        }
        long[] places = new long[out.size()];
        for (int i = 0; i < places.length; i++) places[i] = out.get(i);
        return places;
    }

    /** x и z чанка в одном {@code long}, как {@code ChunkPos.asLong}. */
    public static long pack(int x, int z) {
        return (x & 0xFFFFFFFFL) | (long) z << 32;
    }

    @Override
    public void close() throws IOException {
        zip.close();
    }

    private static byte[] read(ZipFile zip, String name) throws IOException {
        ZipEntry e = zip.getEntry(name);
        if (e == null) return null;
        try (InputStream in = zip.getInputStream(e)) {
            return in.readAllBytes();
        }
    }

    /** Кусок в памяти: имена действий, снимок и действия — отрезками массива. */
    public static final class SegmentData {
        public final Segment segment;
        public final byte[] data;
        public final List<String> names;
        public final int snapshotStart, actionsStart;

        SegmentData(Segment segment, byte[] data) throws IOException {
            this.segment = segment;
            this.data = data;
            Bytes b = new Bytes(data, 0, data.length);
            if (b.readInt() != MAGIC) throw new IOException(segment.file + ": not a Flashback segment");
            int count = b.readVarInt();
            List<String> names = new ArrayList<>(count);
            for (int i = 0; i < count; i++) names.add(b.readString());
            this.names = Collections.unmodifiableList(names);
            int snapshot = b.readInt();
            if (snapshot < 0 || snapshot > b.remaining()) throw new IOException(segment.file + ": bad snapshot size " + snapshot);
            this.snapshotStart = b.pos;
            this.actionsStart = b.pos + snapshot;
        }
    }

    /** Чтение big-endian и varint, как {@code FriendlyByteBuf}. */
    public static final class Bytes {
        public final byte[] data;
        public int pos;
        public final int end;

        public Bytes(byte[] data, int pos, int end) {
            this.data = data;
            this.pos = pos;
            this.end = end;
        }

        public int remaining() {
            return end - pos;
        }

        public int readInt() {
            if (remaining() < 4) throw new IllegalStateException("read past end");
            int v = (data[pos] & 0xFF) << 24 | (data[pos + 1] & 0xFF) << 16 | (data[pos + 2] & 0xFF) << 8 | data[pos + 3] & 0xFF;
            pos += 4;
            return v;
        }

        public int readVarInt() {
            int value = 0;
            for (int shift = 0; shift < 35; shift += 7) {
                if (pos >= end) throw new IllegalStateException("read past end");
                byte b = data[pos++];
                value |= (b & 0x7F) << shift;
                if ((b & 0x80) == 0) return value;
            }
            throw new IllegalStateException("varint too long");
        }

        public String readString() {
            int len = readVarInt();
            if (len < 0 || len > remaining()) throw new IllegalStateException("bad string length " + len);
            String s = new String(data, pos, len, StandardCharsets.UTF_8);
            pos += len;
            return s;
        }
    }
}
