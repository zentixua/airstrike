package ua.zentix.airstrike.warhead;

/**
 * Где время первой единицы взрыва ({@link StagedExplosion}: {@code Explosion.explode}) — по отметкам миксина
 * {@code mixin/explosion/ExplosionTimingMixin} между шагами ванильного метода. Замер у хоста: одна такая единица —
 * 114 мс (в облаке без сборки ~5 мс), и чтобы решить, как её делить, нужно видеть, чья это цена. Отметки пишутся,
 * только пока идёт наш взрыв (поток сервера), чужие взрывы не трогаются.
 */
public final class ExplosionTimer {
    /** Шаг {@code Explosion.explode}. */
    public enum Stage {
        /** {@code gameEvent(EXPLODE)}: слушатели вибраций (скалк, моды). */
        GAME_EVENT("событие"),
        /** Лучи по блокам (с блоками аппаратов, которые добавляет миксин Sable). */
        RAYS("лучи"),
        /** Сбор сущностей в кубе досягаемости. */
        ENTITIES("сбор сущностей"),
        /** {@code ExplosionEvent.Detonate}: обработчики модов (и наши: блэкаут, запись для проверок). */
        DETONATE("Detonate"),
        /** Урон и отбрасывание: {@code getSeenPercent} (лучи к сущности), {@code hurt} — смерть, лут, обработчики. */
        DAMAGE("урон");

        public final String label;

        Stage(String label) {
            this.label = label;
        }
    }

    private static final Stage[] STAGES = Stage.values();
    private static long[] current;
    private static long last;
    private static int next;
    private static int lastMarks;
    /** Вложенность {@code Explosion.explode}: обработчик {@code Detonate} может взорвать своё — его шаги не наши. */
    private static int depth;

    private ExplosionTimer() {}

    /** Наш взрыв начинается: отметки пойдут в {@code into} (по шагам, нс; копятся). */
    static void begin(long[] into) {
        current = into;
        next = 0;
        depth = 0;
        last = System.nanoTime();
    }

    /** Наш взрыв кончился: отметки больше не пишутся. */
    static void end() {
        current = null;
        lastMarks = next;
    }

    /** Сколько шагов отметил миксин в последнем нашем взрыве: встал — все ({@link #stages()}), не встал — 0. */
    public static int lastMarks() {
        return lastMarks;
    }

    /** Миксин: начало {@code Explosion.explode}. */
    public static void enter() {
        depth++;
    }

    /** Миксин: конец {@code Explosion.explode} — последний шаг кончился. */
    public static void exit() {
        mark(Stage.DAMAGE.ordinal());
        depth--;
    }

    /** Миксин: кончился шаг {@code stage} (отметки идут по порядку; пропущенный шаг получает 0). */
    public static void mark(int stage) {
        long[] into = current;
        if (into == null || depth != 1 || stage < next) return;
        long now = System.nanoTime();
        into[stage] += now - last;
        last = now;
        next = stage + 1;
    }

    public static int stages() {
        return STAGES.length;
    }
}
