package ua.zentix.airstrike.client.sound;

import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import ua.zentix.airstrike.registry.ModSounds;

/**
 * Что слышно от взрыва на разном расстоянии — картинку и тряску рисует {@code client.fx.BlastEffects}, звук весь здесь.
 * Всё приходит со фронтом (band — пояс фронта: 1 — ближе 17 блоков, 2 — 17..34 …), громкость задаём сами, а не
 * ванильным затуханием. У каждого события по несколько записей — каждый взрыв звучит по-своему.
 * <ul>
 * <li>вблизи (до ~50 блоков) — хлёсткий удар с огненным шаром и «удар в грудь» снизу, потом сыплются обломки;</li>
 * <li>на средней дистанции — резкий хлопок тише, основное — тяжёлый раскат с эхом;</li>
 * <li>вдали — только дальний раскат, тише с расстоянием; воздух и холмы глушат верха ({@link SoundFilters}).</li>
 * </ul>
 */
public final class BlastSounds {
    private BlastSounds() {}

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
            float far = Math.max(0.35f, 1 - (band - 10) / 30f);
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
            ClientSounds.atEar(ModSounds.BLAST_FAR.get(), pos, Math.max(0.2f, 0.8f - (band - 10) / 30f), p * 1.15f);
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
            ClientSounds.atEar(ModSounds.BOMB_DEEP.get(), pos, 1, 1);
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
