package ua.zentix.airstrike.client.sound;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.util.Mth;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import ua.zentix.airstrike.registry.ModSounds;
import ua.zentix.airstrike.warhead.GroundMaterial;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Что слышно от взрыва на разном расстоянии — картинку и тряску рисует {@code client.fx.BlastEffects}, звук весь здесь.
 * Всё приходит со фронтом, громкость задаём сами, а не ванильным затуханием.
 * <ul>
 * <li>ближе {@link Outdoor#NEAR} блоков — ракурсы одной записи по расстоянию ({@link BlastMix}): вблизи удар и тело
 * взрыва с «ударом в грудь» снизу, дальше — запись с эхом от склонов и домов, вдали — раскат без верхов; эхо вокруг —
 * стерео без места в мире; воздух и преграды глушат верха ({@link SoundFilters});</li>
 * <li>дальше — дальний ракурс по модели распространения {@link Outdoor} ({@link #far}).</li>
 * </ul>
 * Вариант записи выбирает зерно взрыва (пакет {@code S2C.Blast}): каждый взрыв звучит по-своему, а его ракурсы — одна
 * запись. Моторы снарядов под громкий взрыв уходят вниз ({@link Ducking}).
 */
public final class BlastSounds {
    /** Громкость глухого удара бомбы из-под земли — и на краю ближней модели, с неё продолжается {@link #far}. */
    static final float BUNKER_FAR_FLOOR = 1;
    /** Соль зерна «удара в грудь» и эха: их варианты (их меньше) не привязаны к варианту ракурсов. */
    private static final long SUB_SALT = 0x5DEECE66DL, ECHO_SALT = 0x9E3779B97F4A7C15L;
    /** Эха звучат не больше стольких сразу: залп РСЗО — десятки разрывов, а эхо — длинное стерео на канал. */
    static final int ECHO_CAP = 4;
    private static final Deque<SoundInstance> ECHOES = new ArrayDeque<>();
    /** Тише — слой не запускать. */
    private static final float AUDIBLE = 0.005f;

    private BlastSounds() {}

    /**
     * Взрыв дальше {@link Outdoor#NEAR}: дальний ракурс, с той громкостью, что у ближней модели на её краю
     * ({@link BlastMix#v640}), у бомбы — глухой удар из-под земли. Громкость и верха — {@link Outdoor}: что между
     * (кромка по лучу рельефа {@code z}), земля у взрыва и у слушателя, день или ночь, дождь.
     *
     * @param kind   вид пакета {@code S2C.Blast}
     * @param r0     докуда этот взрыв слышно в обычных условиях, блоков
     * @param hs     высота источника над землёй, блоков
     * @param ground грунт у взрыва
     * @param d      до уха, блоков
     * @param z      разность хода через кромку рельефа, блоков (0 — прямая видимость)
     * @param seed   зерно взрыва
     * @return что дошло (для лога); {@code null} — мира нет
     */
    public static Outdoor.@Nullable Heard far(int kind, Vec3 pos, double r0, double hs, GroundMaterial ground, double d, double z, long seed) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) return null;
        Vec3 ear = mc.gameRenderer.getMainCamera().getPosition();
        // земля у слушателя: верх колонки под ухом (чанк под камерой у клиента всегда есть)
        int x = Mth.floor(ear.x), zz = Mth.floor(ear.z);
        int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, zz);
        GroundMaterial under = GroundMaterial.of(level.getBlockState(new BlockPos(x, top - 1, zz)));
        DimensionType type = level.dimensionType();
        // день: земля греет воздух, звук загибает вверх; нет солнца (Незер, Энд) — как ночью
        double day = type.hasSkyLight() && !type.hasFixedTime() ? Outdoor.day(Mth.cos(level.getTimeOfDay(1) * Mth.TWO_PI)) : 0;
        Outdoor.Path path = new Outdoor.Path(d, z, hs, Math.max(1.5, ear.y - top), ground.porosity, under == null ? 0.5 : under.porosity,
                day, level.getRainLevel(1), level.getThunderLevel(1));
        BlastMix.Profile mix = BlastMix.of(kind);
        Outdoor.Heard heard = Outdoor.hear(r0, mix == null ? BUNKER_FAR_FLOOR : BlastMix.v640(mix), path);
        float v = heard.volume();
        if (v < 0.005f) return heard;
        float highs = SoundFilters.pathEffects() ? heard.highs() * ClientSounds.farAir(d) : 1;
        if (mix == null) ClientSounds.atEar(ModSounds.BOMB_DEEP.get(), pos, v, 1, seed, 1, highs);
        else ClientSounds.atEar(ModSounds.BLAST_FAR.get(), pos, v, BlastMix.pitch(mix, seed), seed, 1, highs);
        return heard;
    }

    /** Взрыв на поверхности — фронт дошёл до уха: шахед (big = false) или крылатая ракета. */
    public static void surface(Vec3 pos, boolean big, long seed) {
        play(big ? BlastMix.MISSILE : BlastMix.DRONE, ModSounds.BLAST_NEAR.get(), pos, seed);
    }

    /**
     * Снаряд РСЗО (~20 кг ВВ): вблизи — сухой жёсткий разрыв, вдали — короткий раскат. Залп ложится очередью,
     * поэтому тон каждого разрыва чуть свой — цепочка не звучит одним и тем же звуком.
     */
    public static void rocket(Vec3 pos, long seed) {
        play(BlastMix.ROCKET, ModSounds.ROCKET_BLAST.get(), pos, seed);
    }

    /** Слои взрыва по расстоянию до уха ({@link BlastMix}); near — ближний ракурс. */
    private static void play(BlastMix.Profile mix, SoundEvent near, Vec3 pos, long seed) {
        double d = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition().distanceTo(pos);
        BlastMix.Layers l = BlastMix.at(mix, d);
        float pitch = BlastMix.pitch(mix, seed);
        ClientSounds.ducked(BlastMix.loudness(mix, d));
        if (l.near() >= AUDIBLE) ClientSounds.atEar(near, pos, l.near(), pitch, seed);
        if (l.mid() >= AUDIBLE) ClientSounds.atEar(ModSounds.BLAST_MID.get(), pos, l.mid(), pitch, seed);
        if (l.far() >= AUDIBLE) ClientSounds.atEar(ModSounds.BLAST_FAR.get(), pos, l.far(), pitch, seed);
        if (l.sub() >= AUDIBLE) ClientSounds.atEar(ModSounds.BLAST_SUB.get(), pos, l.sub(), pitch, seed ^ SUB_SALT);
        if (l.echo() >= AUDIBLE && echoFree())
            ECHOES.add(ClientSounds.around(ModSounds.BLAST_TAIL.get(), l.echo(), pitch, seed ^ ECHO_SALT, ClientSounds.farAir(d)));
    }

    /**
     * Есть место ещё одному эху: смолкшие и не запущенные (движку не хватило канала) забываются. Звучит — значит держит
     * канал: {@code SoundManager.isActive} остаётся true у звука, которого движок лишился при громкости категории 0.
     */
    private static boolean echoFree() {
        var channels = Minecraft.getInstance().getSoundManager().soundEngine.instanceToChannel;
        ECHOES.removeIf(s -> !channels.containsKey(s));
        return ECHOES.size() < ECHO_CAP;
    }

    /** Бетонобойная бомба взорвалась под землёй. underground — слушатель сам под землёй (в той же толще). */
    public static void bunker(Vec3 pos, int band, boolean underground) {
        if (underground && band <= 4) {
            // взрыв в замкнутом пространстве: жёстко и с долгим эхом, сверху сыплется порода
            ClientSounds.atEar(ModSounds.BOMB_CAVE.get(), pos, 1, 1);
            ClientSounds.atEar(ModSounds.BLAST_SUB.get(), pos, 1, 0.8f);
            ClientSounds.atEar(ModSounds.BLAST_NEAR.get(), pos, 0.7f, 0.75f);
            ClientSounds.atEar(ModSounds.DEBRIS_FALL.get(), pos, 1, 0.8f);
        } else {
            // на поверхности — глухой удар из-под земли, земля дрожит
            ClientSounds.atEar(ModSounds.BOMB_DEEP.get(), pos, BUNKER_FAR_FLOOR, 1);
            if (band <= 6) ClientSounds.atEar(ModSounds.BLAST_SUB.get(), pos, 0.7f, 0.6f);
        }
    }

    /** Бомба вошла в грунт: хлопок и тупой удар. */
    public static void bunkerImpact(Vec3 pos, int band) {
        if (band > 18) return;
        float v = Math.max(0.3f, 1 - band / 18f);
        ClientSounds.atEar(ModSounds.BOMB_CRACK.get(), pos, v, 1);
        ClientSounds.atEar(ModSounds.BOMB_IMPACT.get(), pos, v, 1);
    }

    /** Газы взрыва вырвались по скважине. */
    public static void vent(Vec3 pos, int band) {
        if (band <= 10) ClientSounds.atEar(ModSounds.BOMB_VENT.get(), pos, Math.max(0.3f, 1 - band / 10f), 1);
    }

    /** Свод над полостью обрушился. */
    public static void collapse(Vec3 pos, int band) {
        if (band <= 7) ClientSounds.atEar(ModSounds.BOMB_QUAKE.get(), pos, Math.max(0.4f, 1 - band / 8f), 0.8f);
    }

    /** Толчок земли у слушателя (сервер шлёт тем, кто стоит над взрывом). */
    public static void quake() {
        Vec3 ear = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        ClientSounds.atEar(ModSounds.BOMB_QUAKE.get(), ear.add(0, -4, 0), 1, 1);
    }

    /** Обломки и земля падают обратно — слышно тем, кто ближе radius блоков. */
    public static void debris(Vec3 pos, double radius, float pitch) {
        if (near(pos, radius)) ClientSounds.at(ModSounds.DEBRIS_FALL.get(), pos, 3, pitch);
    }

    /** Пожар в воронке гудит и трещит (слышно в пределах ~80 блоков). */
    public static void fire(Vec3 pos, float pitch) {
        if (near(pos, 90)) ClientSounds.at(ModSounds.BLAST_FIRE.get(), pos, 5, pitch);
    }

    /** Вторичный подрыв в воронке: небольшой взрыв, выше тоном. */
    public static void cookoff(Vec3 pos) {
        ClientSounds.at(ModSounds.BLAST_NEAR.get(), pos, 6, 1.35f);
    }

    private static boolean near(Vec3 pos, double radius) {
        return Minecraft.getInstance().gameRenderer.getMainCamera().getPosition().distanceTo(pos) <= radius;
    }
}
