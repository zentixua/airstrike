package ua.zentix.replayscan.stream;

import ua.zentix.replayscan.stream.ReplayFile.Bytes;
import ua.zentix.replayscan.stream.ReplayFile.Segment;
import ua.zentix.replayscan.stream.ReplayFile.SegmentData;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Действия повтора по порядку с тиком, в котором их применяет сервер повтора. Тик сервера повтора t читает действия,
 * пока не встретит t-е {@code next_tick} ({@code ReplayServer.handleActions}): действие после (t−1)-го {@code next_tick}
 * применено в тике t, и в конце этого тика ({@code ServerTickEvent.Post}) мир повтора — уже с ним. Снимок первого
 * куска сервер ставит до первого тика (тик 0); снимок следующего — только с {@code forcePlaySnapshot}, когда чтение
 * доходит до этого куска (действия того же тика). Остальные снимки повторяют то, что у сервера уже есть, и не читаются.
 */
public final class Timeline {
    public static final String NEXT_TICK = "flashback:action/next_tick";

    /** Действие: тик, имя, тело — отрезком массива куска; {@code snapshot} — из снимка (состояние, а не событие). */
    public record Action(int tick, String name, byte[] data, int off, int len, boolean snapshot) {
        public Bytes body() {
            return new Bytes(data, off, off + len);
        }
    }

    private final ReplayFile file;
    private final List<String> warnings = new ArrayList<>();
    private int segment = -1;
    private SegmentData data;
    private Bytes in;
    /** Читается снимок куска: до {@code data.actionsStart}. */
    private boolean snapshot;
    /** Сколько {@code next_tick} прочитано. */
    private int ticks;

    public Timeline(ReplayFile file) {
        this.file = file;
    }

    /** Тиков пройдено: последнее прочитанное {@code next_tick}. */
    public int ticks() {
        return ticks;
    }

    /** Что в записи не сошлось (куски не той длины): Flashback на таком повторе останавливается. */
    public List<String> warnings() {
        return warnings;
    }

    /** Следующее действие или {@code null} в конце записи. */
    public Action next() throws IOException {
        while (true) {
            if (in != null && in.remaining() > 0) return read();
            if (in != null && snapshot) {
                snapshot = false;
                in = new Bytes(data.data, data.actionsStart, data.data.length);
                continue;
            }
            if (!enter(segment + 1)) return null;
        }
    }

    private boolean enter(int index) throws IOException {
        data = null;
        in = null;
        List<Segment> segments = file.segments();
        if (index >= segments.size()) return false;
        segment = index;
        Segment s = segments.get(index);
        if (ticks != s.start()) warnings.add(s.file() + " starts at tick " + s.start() + " but the stream before it has " + ticks + " ticks");
        data = file.load(s);
        snapshot = index == 0 || s.forceSnapshot();
        in = snapshot ? new Bytes(data.data, data.snapshotStart, data.actionsStart) : new Bytes(data.data, data.actionsStart, data.data.length);
        return true;
    }

    private Action read() throws IOException {
        int id = in.readVarInt();
        int size = in.readInt();
        if (id < 0 || id >= data.names.size()) throw new IOException(data.segment.file() + ": unknown action id " + id);
        if (size < 0 || size > in.remaining()) throw new IOException(data.segment.file() + ": bad action size " + size);
        String name = data.names.get(id);
        int off = in.pos;
        in.pos += size;
        boolean fromSnapshot = snapshot;
        int tick;
        if (fromSnapshot) {
            tick = segment == 0 ? 0 : ticks + 1;
        } else if (name.equals(NEXT_TICK)) {
            tick = ++ticks;
        } else {
            tick = ticks + 1;
        }
        return new Action(tick, name, data.data, off, size, fromSnapshot);
    }
}
