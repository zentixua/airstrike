package ua.zentix.airstrike.client.sound;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import ua.zentix.airstrike.net.S2C;
import ua.zentix.airstrike.registry.ModSounds;
import ua.zentix.airstrike.warhead.GroundMaterial;

/**
 * Что слышно от взрыва на разном расстоянии — картинку и тряску рисует {@code client.fx.BlastEffects}, звук весь здесь.
 * Всё приходит со фронтом (band — пояс фронта: 1 — ближе 17 блоков, 2 — 17..34 …), громкость задаём сами, а не
 * ванильным затуханием. У каждого события по несколько записей — каждый взрыв звучит по-своему.
 * <ul>
 * <li>вблизи (до ~50 блоков) — хлёсткий удар с огненным шаром и «удар в грудь» снизу, потом сыплются обломки;</li>
 * <li>на средней дистанции — резкий хлопок тише, основное — тяжёлый раскат с эхом;</li>
 * <li>вдали — только дальний раскат, тише с расстоянием; воздух и холмы глушат верха ({@link SoundFilters});</li>
 * <li>дальше {@link Outdoor#NEAR} блоков — тот же раскат по модели распространения {@link Outdoor} ({@link #far}).</li>
 * </ul>
 */
public final class BlastSounds {
    /**
     * Громкость дальнего раската ближней модели на её краю ({@link Outdoor#NEAR}): шахед и ракета, РСЗО, бомба. С неё
     * продолжается дальняя модель ({@link #far}) — одни и те же числа, поэтому на 640 блоках нет ступеньки.
     */
    static final float FAR_FLOOR = 0.35f, ROCKET_FAR_FLOOR = 0.2f, BUNKER_FAR_FLOOR = 1;

    private BlastSounds() {}

    /**
     * Взрыв дальше {@link Outdoor#NEAR}: тот же дальний раскат, что у ближней модели на её краю (у ракеты — и второй,
     * ниже; у РСЗО — выше тоном; у бомбы — глухой удар из-под земли). Громкость и верха — {@link Outdoor}: что между
     * (кромка по лучу рельефа {@code z}), земля у взрыва и у слушателя, день или ночь, дождь.
     *
     * @param kind   вид пакета {@code S2C.Blast}
     * @param r0     докуда этот взрыв слышно в обычных условиях, блоков
     * @param hs     высота источника над землёй, блоков
     * @param ground грунт у взрыва
     * @param d      до уха, блоков
     * @param z      разность хода через кромку рельефа, блоков (0 — прямая видимость)
     * @return что дошло (для лога); {@code null} — мира нет
     */
    public static Outdoor.@Nullable Heard far(int kind, Vec3 pos, double r0, double hs, GroundMaterial ground, double d, double z) {
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
        float v640 = switch (kind) {
            case S2C.Blast.ROCKET -> ROCKET_FAR_FLOOR;
            case S2C.Blast.BUNKER -> BUNKER_FAR_FLOOR;
            default -> FAR_FLOOR;
        };
        Outdoor.Heard heard = Outdoor.hear(r0, v640, path);
        float v = heard.volume();
        if (v < 0.005f) return heard;
        float highs = SoundFilters.pathEffects() ? heard.highs() * ClientSounds.farAir(d) : 1;
        switch (kind) {
            case S2C.Blast.MISSILE -> {
                ClientSounds.atEar(ModSounds.BLAST_FAR.get(), pos, v, 0.88f, 1, highs);
                ClientSounds.atEar(ModSounds.BLAST_FAR.get(), pos, v * 0.6f, 0.8f, 1, highs);
            }
            case S2C.Blast.ROCKET -> ClientSounds.atEar(ModSounds.BLAST_FAR.get(), pos, v, (0.93f + (float) Math.random() * 0.14f) * 1.15f, 1, highs);
            case S2C.Blast.BUNKER -> ClientSounds.atEar(ModSounds.BOMB_DEEP.get(), pos, v, 1, 1, highs);
            default -> ClientSounds.atEar(ModSounds.BLAST_FAR.get(), pos, v, 1, 1, highs);
        }
        return heard;
    }

    /** Взрыв на поверхности: шахед (big = false) или крылатая ракета — у неё тон ниже и раскат длиннее. */
    public static void surface(Vec3 pos, int band, boolean big) {
        float p = big ? 0.88f : 1;
        if (band <= 3) {
            ClientSounds.atEar(ModSounds.BLAST_NEAR.get(), pos, 1, p);
            ClientSounds.atEar(ModSounds.BLAST_SUB.get(), pos, 1, p * 0.95f);
            if (big) ClientSounds.atEar(ModSounds.BLAST_FAR.get(), pos, 0.7f, 0.85f);
        } else if (band <= (big ? 11 : 9)) {
            float near = Math.max(0.3f, 0.85f - (band - 4) * 0.08f);
            ClientSounds.atEar(ModSounds.BLAST_NEAR.get(), pos, near, p * 0.95f);
            ClientSounds.atEar(ModSounds.BLAST_FAR.get(), pos, 1, p);
            if (band <= (big ? 8 : 6)) ClientSounds.atEar(ModSounds.BLAST_SUB.get(), pos, 0.8f, p * 0.9f);
        } else {
            float far = Math.max(FAR_FLOOR, 1 - (band - 10) / 30f);
            ClientSounds.atEar(ModSounds.BLAST_FAR.get(), pos, far, p);
            if (big) ClientSounds.atEar(ModSounds.BLAST_FAR.get(), pos, far * 0.6f, 0.8f);
        }
    }

    /**
     * Снаряд РСЗО (~20 кг ВВ): вблизи — сухой жёсткий разрыв, вдали — короткий раскат. Залп ложится очередью,
     * поэтому тон каждого разрыва чуть свой — цепочка не звучит одним и тем же звуком.
     */
    public static void rocket(Vec3 pos, int band) {
        float p = 0.93f + (float) Math.random() * 0.14f;
        if (band <= 3) {
            ClientSounds.atEar(ModSounds.ROCKET_BLAST.get(), pos, 1, p);
            ClientSounds.atEar(ModSounds.BLAST_NEAR.get(), pos, 0.45f, p * 1.2f);
            ClientSounds.atEar(ModSounds.BLAST_SUB.get(), pos, 0.5f, p * 1.15f);
        } else if (band <= 9) {
            ClientSounds.atEar(ModSounds.ROCKET_BLAST.get(), pos, Math.max(0.35f, 1 - (band - 3) * 0.1f), p);
            if (band <= 6) ClientSounds.atEar(ModSounds.BLAST_SUB.get(), pos, 0.35f, p * 1.1f);
        } else {
            ClientSounds.atEar(ModSounds.BLAST_FAR.get(), pos, Math.max(ROCKET_FAR_FLOOR, 0.8f - (band - 10) / 30f), p * 1.15f);
        }
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
