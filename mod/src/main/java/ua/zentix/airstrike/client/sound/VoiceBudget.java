package ua.zentix.airstrike.client.sound;

import java.util.Arrays;
import java.util.Comparator;
import java.util.stream.IntStream;

/**
 * Какие слои моторов снарядов держат канал OpenAL (без Minecraft, проверяется юнит-тестом).
 * <p>
 * Канал статических звуков — общий ресурс: у OpenAL Soft их ~250 (без сведений от устройства — 22), и когда он
 * кончается, движок молча не играет новый звук, в том числе взрыв. Поэтому слой звучит, только пока его слышно,
 * а всех слоёв моторов разом — не больше {@link #CAP}, самые громкие; остальные каналы — разовым звукам.
 * Чтобы слой не щёлкал и не дёргал движок, выбор с запасом: смолкший слой держит канал ещё {@link #HOLD} тиков,
 * а уже звучащий уступает место новому, только если тот громче в {@link #KEEP} раза.
 */
final class VoiceBudget {
    /** Тише — слой не слышно: не запускать. */
    static final float AUDIBLE = 0.005f;
    /** Столько тиков подряд слой не слышно — отпустить канал. */
    static final int HOLD = 20;
    /** Слоёв моторов, звучащих одновременно. */
    static final int CAP = 48;
    /** Во столько раз новый слой должен быть громче звучащего, чтобы занять его место. */
    static final float KEEP = 2;

    private VoiceBudget() {}

    /**
     * Какие слои должны звучать в этот тик.
     *
     * @param gain  громкость каждого слоя в этот тик (цель, до сглаживания)
     * @param live  слой сейчас звучит (держит канал)
     * @param quiet сколько тиков подряд слой не слышно ({@code gain < AUDIBLE})
     * @param cap   сколько слоёв может звучать
     */
    static boolean[] select(float[] gain, boolean[] live, int[] quiet, int cap) {
        int n = gain.length;
        boolean[] keep = new boolean[n];
        int[] wanted = IntStream.range(0, n).filter(i -> gain[i] >= AUDIBLE || live[i] && quiet[i] < HOLD).toArray();
        if (wanted.length > cap) {
            Integer[] order = Arrays.stream(wanted).boxed().toArray(Integer[]::new);
            Arrays.sort(order, Comparator.comparingDouble((Integer i) -> gain[i] * (live[i] ? KEEP : 1)).reversed());
            wanted = Arrays.stream(order, 0, cap).mapToInt(Integer::intValue).toArray();
        }
        for (int i : wanted) keep[i] = true;
        return keep;
    }
}
