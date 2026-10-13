package ua.zentix.replayscan;

import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.zip.GZIPOutputStream;

/**
 * Таблица CSV в gzip: заголовок, строки по полям ({@link #add} по порядку, {@link #end} — конец строки). Числа —
 * с точкой ({@code Locale.ROOT}), дробные — до тысячных; текст — в кавычках, если в нём есть запятая, кавычка или
 * перевод строки. Пишет поток сервера повтора, буфер — 1 МБ.
 */
public final class Csv implements Closeable {
    private final Writer out;
    private final StringBuilder row = new StringBuilder(256);
    private boolean first = true;
    private long rows;

    public Csv(Path file, String... header) {
        try {
            Files.createDirectories(file.getParent());
            out = new BufferedWriter(new OutputStreamWriter(new GZIPOutputStream(Files.newOutputStream(file), 1 << 16), StandardCharsets.UTF_8), 1 << 20);
            out.write(String.join(",", header));
            out.write('\n');
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public long rows() {
        return rows;
    }

    public Csv add(long v) {
        sep();
        row.append(v);
        return this;
    }

    public Csv add(double v) {
        sep();
        if (Double.isNaN(v)) return this;
        if (Double.isInfinite(v) || Math.abs(v) >= 1e15) {
            row.append(String.format(Locale.ROOT, "%.3f", v));
            return this;
        }
        // до тысячных без String.format: строк — миллионы
        long m = Math.round(v * 1000);
        if (m < 0) {
            row.append('-');
            m = -m;
        }
        row.append(m / 1000);
        int frac = (int) (m % 1000);
        if (frac != 0) {
            row.append('.');
            if (frac < 100) row.append('0');
            if (frac < 10) row.append('0');
            while (frac % 10 == 0) frac /= 10;
            row.append(frac);
        }
        return this;
    }

    public Csv add(boolean v) {
        sep();
        row.append(v ? '1' : '0');
        return this;
    }

    /** Пустое поле ({@code null} в DuckDB). */
    public Csv empty() {
        sep();
        return this;
    }

    public Csv add(String v) {
        sep();
        if (v == null) return this;
        if (v.indexOf(',') < 0 && v.indexOf('"') < 0 && v.indexOf('\n') < 0 && v.indexOf('\r') < 0) {
            row.append(v);
        } else {
            row.append('"').append(v.replace("\"", "\"\"")).append('"');
        }
        return this;
    }

    public void end() {
        row.append('\n');
        try {
            out.append(row);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        row.setLength(0);
        first = true;
        rows++;
    }

    private void sep() {
        if (!first) row.append(',');
        first = false;
    }

    @Override
    public void close() throws IOException {
        out.close();
    }
}
