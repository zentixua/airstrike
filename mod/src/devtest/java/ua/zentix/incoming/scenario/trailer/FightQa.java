package ua.zentix.incoming.scenario.trailer;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundEventListener;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.joml.Vector3f;
import ua.zentix.incoming.Incoming;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Проверка плана «твоими глазами» в бою по данным игры (план 27): каждый тик — оружие {@code item} в руке игрока съёмки,
 * сколько бойцов противника в кадре и не за блоками (луч по видимой форме из камеры к груди), звуки Point Blank у самого
 * игрока, пока нажата атака (выстрелы его автомата: звук пишет клиент того, кто стреляет). Итог — строка
 * {@code SCENARIO film fight} и {@code <папка записи>/fight.jsonl}: прошёл, если оружие в руке весь план, противников
 * в кадре не меньше {@link #MIN_FOES} в доле {@link #FOES_SHARE} тиков и выстрелов на тик атаки не меньше
 * {@link #SHOTS_PER_TICK}. Названия звуков — в строку итога: щелчок пустого магазина — тоже звук мода.
 */
final class FightQa implements SoundEventListener {
    static final int MIN_FOES = 3;
    static final double FOES_SHARE = 0.9;
    /** АК — 600 выстрелов в минуту, полвыстрела за тик; первый тик очереди и перезарядка — с запасом. */
    static final double SHOTS_PER_TICK = 0.25;
    /** Звук ближе стольких блоков к глазам игрока — его собственный. */
    private static final double OWN = 3;

    private final String shot, item;
    private final List<String> foes;
    private final double fov;
    private final Map<String, Integer> sounds = new TreeMap<>();
    private int ticks, armed, foesOk, attack, shots, minFoes = Integer.MAX_VALUE, maxFoes;

    FightQa(String shot, String item, List<String> foes, double fov) {
        this.shot = shot;
        this.item = item;
        this.foes = foes;
        this.fov = fov;
        Minecraft.getInstance().getSoundManager().addListener(this);
    }

    @Override
    public void onPlaySound(SoundInstance sound, WeighedSoundEvents accessor, float range) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || !"pointblank".equals(sound.getLocation().getNamespace())) return;
        if (new Vec3(sound.getX(), sound.getY(), sound.getZ()).distanceTo(mc.player.getEyePosition()) > OWN && !sound.isRelative()) return;
        sounds.merge(sound.getLocation().getPath(), 1, Integer::sum);
        if (mc.options.keyAttack.isDown()) shots++;
    }

    void tick(Film film) {
        Minecraft mc = film.mc;
        if (mc.player == null || mc.level == null) return;
        ticks++;
        if (BuiltInRegistries.ITEM.getKey(mc.player.getMainHandItem().getItem()).toString().equals(item)) armed++;
        if (mc.options.keyAttack.isDown()) attack++;
        Camera cam = mc.gameRenderer.getMainCamera();
        Vec3 eye = cam.getPosition();
        Vector3f f = cam.getLookVector(), u = cam.getUpVector(), l = cam.getLeftVector();
        double ty = Math.tan(Math.toRadians(fov / 2)), tx = ty * mc.getWindow().getWidth() / mc.getWindow().getHeight();
        int seen = 0;
        for (String name : foes) {
            Entity e = film.bot(name);
            if (e == null || !e.isAlive()) continue;
            Vec3 chest = e.position().add(0, 1.2, 0), d = chest.subtract(eye);
            double z = d.x * f.x() + d.y * f.y() + d.z * f.z();
            if (z <= 0.5) continue;
            double x = (d.x * l.x() + d.y * l.y() + d.z * l.z()) / z, y = (d.x * u.x() + d.y * u.y() + d.z * u.z()) / z;
            if (Math.abs(x) > tx * 0.95 || Math.abs(y) > ty * 0.95) continue;
            var hit = mc.level.clip(new ClipContext(eye, chest, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, CollisionContext.empty()));
            if (hit.getType() == HitResult.Type.MISS || hit.getLocation().distanceTo(chest) < 0.6) seen++;
        }
        minFoes = Math.min(minFoes, seen);
        maxFoes = Math.max(maxFoes, seen);
        if (seen >= MIN_FOES) foesOk++;
    }

    void close(Path dir) {
        Minecraft.getInstance().getSoundManager().removeListener(this);
        double foeShare = ticks == 0 ? 0 : (double) foesOk / ticks, rate = attack == 0 ? 0 : (double) shots / attack;
        StringBuilder why = new StringBuilder();
        if (ticks == 0 || armed < ticks) why.append(String.format(Locale.ROOT, "%s в руке %d из %d тиков; ", item, armed, ticks));
        if (foeShare < FOES_SHARE) why.append(String.format(Locale.ROOT, "противников в кадре ≥%d — %.0f%% тиков (нужно %.0f%%); ",
                MIN_FOES, 100 * foeShare, 100 * FOES_SHARE));
        if (rate < SHOTS_PER_TICK) why.append(String.format(Locale.ROOT, "выстрелов %d за %d тиков атаки (нужно %.2f на тик); ",
                shots, attack, SHOTS_PER_TICK));
        boolean pass = why.isEmpty();
        String names = sounds.entrySet().stream().map(e -> e.getKey() + "×" + e.getValue()).collect(Collectors.joining(", "));
        Incoming.LOG.info("SCENARIO film fight {}: {} — оружие {}/{}, противников в кадре {}–{} (≥{} в {}% тиков), выстрелов {} за {} тиков атаки; звуки: {}{}",
                shot, pass ? "PASS" : "FAIL", armed, ticks, minFoes == Integer.MAX_VALUE ? 0 : minFoes, maxFoes, MIN_FOES,
                String.format(Locale.ROOT, "%.0f", 100 * foeShare), shots, attack, names, pass ? "" : "; " + why);
        String row = String.format(Locale.ROOT,
                "{\"shot\":\"%s\",\"verdict\":\"%s\",\"why\":\"%s\",\"armed\":%d,\"ticks\":%d,\"foes_share\":%.3f,\"foes_min\":%d,\"foes_max\":%d,"
                        + "\"shots\":%d,\"attack\":%d,\"sounds\":\"%s\"}\n",
                shot, pass ? "PASS" : "FAIL", why.toString().trim(), armed, ticks, foeShare, minFoes == Integer.MAX_VALUE ? 0 : minFoes, maxFoes,
                shots, attack, names);
        try {
            Files.writeString(dir.resolve("fight.jsonl"), row, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
