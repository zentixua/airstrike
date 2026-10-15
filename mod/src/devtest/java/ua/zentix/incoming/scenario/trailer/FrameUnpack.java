package ua.zentix.incoming.scenario.trailer;

import ua.zentix.incoming.Incoming;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Кадры вывода через видео без потерь. PNG кадров Flashback пишет stb_image_write в одном потоке — вывод 1440p стоит на
 * нём (ночь 15.10: 0,46 с на кадр при простаивающей видеокарте), а QuickTime RLE (qtrle в MOV) его же ffmpeg пишет в
 * ~11 раз быстрее и без потерь: RGB тот же, альфа у кадров вывода и так 255. Монтаж и проверки дубля берут PNG
 * {@code 00000.png…}: ffmpeg хоста раскладывает MOV в них в фоне на половине ядер (zlib 1, фильтр up — на 37 % меньше
 * места, чем у PNG Flashback), пока идёт следующий кандидат; клиент перед выходом ждёт все раскладки ({@link #awaitAll}).
 * Нет ffmpeg или он не раскладывает qtrle — вывод PNG, как раньше; {@code INCOMING_FILM_CODEC=png} — PNG всегда,
 * ffmpeg — {@code INCOMING_FFMPEG} или из PATH.
 */
final class FrameUnpack {
    private static final String FFMPEG = System.getenv().getOrDefault("INCOMING_FFMPEG", "ffmpeg");
    private static final String FFPROBE = FFMPEG.endsWith("ffmpeg") ? FFMPEG.substring(0, FFMPEG.length() - 6) + "ffprobe" : "ffprobe";
    private static final int THREADS = Math.max(2, Runtime.getRuntime().availableProcessors() / 2);
    /** Форматы MOV без потерь против RGBA кадра (у qtrle Flashback берёт rgb24). */
    private static final Set<String> LOSSLESS = Set.of("rgb24", "argb", "rgba", "bgra", "abgr");
    private static final long WAIT_SECONDS = 1800;

    /** Раскладки по одной: каждая сама на {@link #THREADS} потоках. */
    private static final ExecutorService POOL = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "incoming film unpack");
        t.setDaemon(true);
        return t;
    });
    private static final List<Future<?>> pending = new ArrayList<>();
    /** null — ещё не проверяли; причина, почему MOV не годится, — в {@link #off}. */
    private static Boolean on;
    private static String off = "";

    private FrameUnpack() {}

    /** Выводить через MOV: ffmpeg хоста на месте и раскладывает qtrle в PNG (проверка — один раз, в папке {@code work}). */
    static synchronized boolean enabled(Path work) {
        if (on != null) return on;
        if ("png".equalsIgnoreCase(System.getenv().getOrDefault("INCOMING_FILM_CODEC", ""))) {
            return disable("INCOMING_FILM_CODEC=png");
        }
        Path dir = work.resolve("unpack-probe");
        try {
            Files.createDirectories(dir);
            Path mov = dir.resolve("probe.mov");
            String err = run(List.of(FFMPEG, "-v", "error", "-nostdin", "-y", "-f", "lavfi", "-i", "testsrc=size=64x36:rate=10",
                    "-frames:v", "2", "-c:v", "qtrle", mov.toString()), 60);
            if (err == null) err = run(unpackArgs(mov, dir), 60);
            if (err == null && !Files.exists(dir.resolve("00001.png"))) err = "нет кадров";
            if (err != null) return disable(FFMPEG + ": " + err);
        } catch (IOException e) {
            return disable(FFMPEG + ": " + e.getMessage());
        } finally {
            deleteQuietly(dir);
        }
        if (!FlashbackApi.qtrle()) return disable("в ffmpeg Flashback нет кодировщика qtrle");
        on = true;
        Incoming.LOG.info("SCENARIO film codec: вывод — MOV qtrle без потерь, PNG раскладывает {} в {} потоков", FFMPEG, THREADS);
        return true;
    }

    /** Дальше — PNG Flashback (MOV не вышел или вышел с потерями). */
    static synchronized boolean disable(String why) {
        on = false;
        off = why;
        Incoming.LOG.warn("SCENARIO film codec: вывод — PNG Flashback ({})", why);
        return false;
    }

    /**
     * Разложить {@code mov} в {@code dir/00000.png…} в фоне; кадров должно выйти {@code frames} (меньше нуля — не
     * проверять). Вышло — MOV удаляется; нет — остаётся рядом, строка ERROR.
     */
    static synchronized void submit(Path mov, Path dir, int frames, String what) {
        pending.add(POOL.submit(() -> {
            long t0 = System.nanoTime();
            try {
                String probe = output(List.of(FFPROBE, "-v", "error", "-select_streams", "v:0", "-show_entries", "stream=pix_fmt",
                        "-of", "default=nw=1:nk=1", mov.toString()));
                List<String> lines = probe == null ? List.of() : probe.lines().map(String::trim).filter(s -> !s.isEmpty()).toList();
                if (lines.size() >= 1 && !LOSSLESS.contains(lines.get(0))) {
                    disable("MOV " + what + " в формате " + lines.get(0) + " — с потерями");
                }
                String err = run(unpackArgs(mov, dir), WAIT_SECONDS);
                int got = count(dir);
                if (err == null && frames >= 0 && got != frames) err = "кадров " + got + " из " + frames;
                if (err != null) {
                    Incoming.LOG.error("SCENARIO film unpack {}: {} — MOV оставлен: {}", what, err, mov);
                    return;
                }
                Files.deleteIfExists(mov);
                Incoming.LOG.info(String.format(Locale.ROOT, "SCENARIO film unpack %s: %d кадров за %.1f с (%s)", what, got,
                        (System.nanoTime() - t0) / 1e9, lines.isEmpty() ? "?" : lines.get(0)));
            } catch (IOException | RuntimeException e) {
                Incoming.LOG.error("SCENARIO film unpack {}: MOV оставлен: {}", what, mov, e);
            }
        }));
    }

    /** Дождаться всех раскладок (перед выходом клиента: монтаж и проверки берут PNG). */
    static void awaitAll() {
        List<Future<?>> all;
        synchronized (FrameUnpack.class) {
            all = new ArrayList<>(pending);
            pending.clear();
        }
        if (all.isEmpty()) return;
        long t0 = System.nanoTime();
        for (Future<?> f : all) {
            try {
                f.get(WAIT_SECONDS, TimeUnit.SECONDS);
            } catch (ExecutionException | java.util.concurrent.TimeoutException e) {
                Incoming.LOG.error("SCENARIO film unpack: раскладка не кончилась", e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        Incoming.LOG.info(String.format(Locale.ROOT, "SCENARIO film unpack: ждал %d раскладок %.1f с", all.size(), (System.nanoTime() - t0) / 1e9));
    }

    private static List<String> unpackArgs(Path mov, Path dir) {
        return List.of(FFMPEG, "-v", "error", "-nostdin", "-y", "-i", mov.toString(), "-map", "0:v:0", "-fps_mode", "passthrough",
                "-c:v", "png", "-pix_fmt", "rgb24", "-compression_level", "1", "-pred", "up", "-threads", String.valueOf(THREADS),
                "-start_number", "0", dir.resolve("%05d.png").toString());
    }

    private static int count(Path dir) throws IOException {
        try (Stream<Path> s = Files.list(dir)) {
            return (int) s.filter(p -> p.getFileName().toString().endsWith(".png")).count();
        }
    }

    /** Выполнить; null — вышло, иначе — причина (код и вывод ошибок). */
    private static String run(List<String> cmd, long seconds) throws IOException {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        // вывод читается рядом: полный канал остановил бы ffmpeg до срока
        var out = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            try {
                return new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                return "";
            }
        });
        try {
            if (!p.waitFor(seconds, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return "дольше " + seconds + " с";
            }
            String text = out.join().strip();
            return p.exitValue() == 0 ? null : "код " + p.exitValue() + (text.isEmpty() ? "" : ": " + text.lines().limit(3).toList());
        } catch (InterruptedException e) {
            p.destroyForcibly();
            Thread.currentThread().interrupt();
            return "прервано";
        }
    }

    /** Вывод команды или null. */
    private static String output(List<String> cmd) {
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            String text = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            return p.waitFor() == 0 ? text : null;
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    private static void deleteQuietly(Path dir) {
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        } catch (IOException ignored) {
        }
    }
}
