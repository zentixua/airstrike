package ua.zentix.airstrike.client.far;

import ua.zentix.airstrike.client.ClientWeaponSpec.FarTrail;

import java.util.Arrays;

/**
 * Точки шлейфов вдали — один круговой буфер на все снаряды: когда он полон, новая точка затирает самую старую.
 * У точки — место, тик рождения, вид шлейфа ({@link FarTrail}), чей это шлейф и предыдущая точка той же ленты — по
 * номеру записи за всё время: связь верна, пока та не затёрта и не растаяла ({@link #holds}). Кадр обходит живые точки
 * один раз от старых к новым и кладёт отрезок от предыдущей к каждой: O(точек), без выделений.
 */
final class TrailPoints {
    /** Точек на все шлейфы, не больше: пакет РСЗО из 40 снарядов — 900, шлейф МБР — 270. */
    static final int CAPACITY = 4096;
    /** Нет точки. */
    static final long NONE = -1;

    final double[] x = new double[CAPACITY], y = new double[CAPACITY], z = new double[CAPACITY];
    final long[] birth = new long[CAPACITY];
    /** Вид шлейфа; null — точки нет (растаяла или снята отбоем). */
    final FarTrail[] style = new FarTrail[CAPACITY];
    final int[] owner = new int[CAPACITY];
    /** Номер предыдущей точки той же ленты или {@link #NONE}. */
    final long[] prev = new long[CAPACITY];
    /** Номер следующей записи. */
    private long next;
    /** Точки с номером меньше — растаяли или затёрты. */
    private long first;

    /** Место номера записи в массивах. */
    static int index(long serial) {
        return (int) (serial % CAPACITY);
    }

    /**
     * Новая точка ленты: prev — предыдущая точка той же ленты ({@link #NONE} — лента начинается здесь).
     *
     * @return номер записи
     */
    long add(double x, double y, double z, long birth, FarTrail style, int owner, long prev) {
        int i = index(next);
        this.x[i] = x;
        this.y[i] = y;
        this.z[i] = z;
        this.birth[i] = birth;
        this.style[i] = style;
        this.owner[i] = owner;
        this.prev[i] = prev;
        next++;
        first = Math.max(first, next - CAPACITY);
        return next - 1;
    }

    /** Самая старая из возможно живых точек (номер). */
    long first() {
        return first;
    }

    /** За последней записанной (номер). */
    long end() {
        return next;
    }

    /** Точка с этим номером ещё в буфере: не затёрта и не растаяла с хвоста. */
    boolean holds(long serial) {
        return serial >= first && serial < next;
    }

    boolean isEmpty() {
        return first == next;
    }

    /** Растаявшие к моменту now — с хвоста буфера (дальше по нему живут и долгие, и короткие шлейфы — их отсеет кадр). */
    void expire(double now) {
        while (first < next) {
            int i = index(first);
            if (style[i] != null && !style[i].expired(now - birth[i])) return;
            style[i] = null;
            first++;
        }
    }

    /** Шлейф снаряда пропадает сразу (отбой: полёт кончился без взрыва). */
    void kill(int owner) {
        for (long s = first; s < next; s++) {
            int i = index(s);
            if (this.owner[i] == owner) style[i] = null;
        }
    }

    void clear() {
        Arrays.fill(style, null);
        first = next;
    }
}
