package ua.zentix.replayscan.film;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundEventListener;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.DoubleSupplier;
import java.util.regex.Pattern;

/**
 * Звуки вывода: каждый начатый звук — строкой {@code sounds.jsonl} (тик, id, место, громкость у камеры, в кадре ли его
 * источник); громкие ({@link Plan#loud}) считаются в кадре и за кадром — громкого за кадром не должно быть больше, чем
 * в кадре. Моторы ({@link Plan#engine}) запоминаются: {@link #engine} — громкость мотора у объекта в этом кадре.
 * Громкость у камеры — как у игры: при линейном затухании {@code громкость × (1 − расстояние / дальность)}, без
 * затухания — громкость.
 */
final class Sounds implements SoundEventListener {
    /** Громче этого у камеры громкий звук считается (дальние раскаты тише). */
    static final double LOUD = 0.1;

    private final Pattern loud, engine;
    private final DoubleSupplier tick;
    private final Writer out;
    private final List<SoundInstance> engines = new ArrayList<>();
    /** Камера последнего кадра: глаз, оси (вперёд, вправо, вверх), тангенсы полукадра. */
    @Nullable
    private Vec3 eye, fwd, right, up;
    private double tanX, tanY;
    int loudOn, loudOff;

    Sounds(Path file, Pattern loud, Pattern engine, DoubleSupplier tick) throws IOException {
        this.loud = loud;
        this.engine = engine;
        this.tick = tick;
        out = Files.newBufferedWriter(file, StandardCharsets.UTF_8);
    }

    /** Камера кадра — по ней звук в кадре или нет. */
    void camera(Vec3 eye, Vec3 fwd, Vec3 right, Vec3 up, double tanX, double tanY) {
        this.eye = eye;
        this.fwd = fwd;
        this.right = right;
        this.up = up;
        this.tanX = tanX;
        this.tanY = tanY;
    }

    @Override
    public void onPlaySound(SoundInstance sound, WeighedSoundEvents events, float range) {
        double t = tick.getAsDouble();
        if (Double.isNaN(t) || eye == null) return;
        String id = sound.getLocation().toString();
        if (engine.matcher(id).find()) engines.add(sound);
        boolean relative = sound.isRelative();
        Vec3 at = new Vec3(sound.getX(), sound.getY(), sound.getZ());
        double gain = relative ? sound.getVolume() : gain(sound, at, range);
        Boolean on = relative ? null : onScreen(at);
        if (!relative && gain >= LOUD && loud.matcher(id).find() && !engine.matcher(id).find()) {
            if (Boolean.TRUE.equals(on)) loudOn++;
            else loudOff++;
        }
        try {
            out.write(String.format(Locale.ROOT, "{\"t\":%.2f,\"id\":\"%s\",\"x\":%.1f,\"y\":%.1f,\"z\":%.1f,\"gain\":%.3f,\"on\":%s}\n", t, id, at.x,
                    at.y, at.z, gain, on));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Громкость звука у камеры; {@code range} — дальность, которую ему дала игра. */
    private double gain(SoundInstance s, Vec3 at, double range) {
        double v = s.getVolume();
        if (s.getAttenuation() == SoundInstance.Attenuation.NONE) return v;
        return v * Math.max(0, 1 - at.distanceTo(eye) / Math.max(1e-3, range));
    }

    /** Источник в кадре (с полями в десятую долю полукадра) — по камере последнего кадра. */
    private boolean onScreen(Vec3 at) {
        Vec3 d = at.subtract(eye);
        double z = d.dot(fwd);
        if (z <= 0.1) return false;
        return Math.abs(d.dot(right) / z) <= tanX * 1.1 && Math.abs(d.dot(up) / z) <= tanY * 1.1;
    }

    /** Самый громкий у камеры мотор, чей источник ближе {@code radius} к объекту; 0 — не слышен. */
    double engine(@Nullable Vec3 subject, double radius) {
        var manager = Minecraft.getInstance().getSoundManager();
        engines.removeIf(s -> !manager.isActive(s));
        if (subject == null || eye == null) return 0;
        double best = 0;
        for (SoundInstance s : engines) {
            Vec3 at = new Vec3(s.getX(), s.getY(), s.getZ());
            if (s.isRelative() || s.getSound() == null || at.distanceTo(subject) > radius) continue;
            double range = Math.max(s.getVolume(), 1) * s.getSound().getAttenuationDistance();
            best = Math.max(best, gain(s, at, range));
        }
        return best;
    }

    void close() {
        try {
            out.close();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
