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
        /** До лучей: {@code new Explosion} и {@code ExplosionEvent.Start} (обработчики модов). */
        START("Start"),
        /** {@code gameEvent(EXPLODE)}: слушатели вибраций (скалк, моды). */
        GAME_EVENT("событие"),
        /** Лучи по блокам (с блоками аппаратов, которые добавляет миксин Sable). */
        RAYS("лучи"),
        /** Сбор сущностей в кубе досягаемости. */
        ENTITIES("сбор сущностей"),
        /** {@code ExplosionEvent.Detonate}: обработчики модов (и наши: блэкаут, запись для проверок). */
        DETONATE("Detonate"),
        /** Урон и отбрасывание: {@code getSeenPercent} (лучи к сущности), {@code hurt} — смерть, лут, обработчики. */
        DAMAGE("урон"),
        /** После {@code explode()}: раздел выбранных лучами позиций на плоты аппаратов и мир, пакеты, блоки аппаратов. */
        SPLIT("раздел"),
        /** Снимок блоков мира для порций и сортировка от центра. */
        SNAPSHOT("снимок");

        public final String label;

        Stage(String label) {
            this.label = label;
        }
    }

    private static final Stage[] STAGES = Stage.values();
    private static long[] current;
    private static long last;
    private static int next;
    private static int marks;
    private static int lastMarks;
    /** Вложенность {@code Explosion.explode}: обработчик {@code Detonate} может взорвать своё — его шаги не наши. */
    private static int depth;

    private ExplosionTimer() {}

    /** Наш взрыв начинается: отметки пойдут в {@code into} (по шагам, нс; копятся). */
    static void begin(long[] into) {
        current = into;
        next = 0;
        marks = 0;
        depth = 0;
        last = System.nanoTime();
    }

    /** Наш взрыв кончился: отметки больше не пишутся. */
    static void end() {
        current = null;
        lastMarks = marks;
    }

    /** Сколько шагов отмечено в последнем нашем взрыве: миксин встал — все ({@link #stages()}), не встал — только свои (2–3). */
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

    /** Свой шаг взрыва (вне {@code explode()}): кончился {@code stage}. */
    static void markOwn(Stage stage) {
        if (depth == 0) put(stage.ordinal());
    }

    /** Миксин: кончился шаг {@code stage} (отметки идут по порядку; пропущенный шаг получает 0). */
    public static void mark(int stage) {
        if (depth == 1) put(stage);
    }

    private static void put(int stage) {
        long[] into = current;
        if (into == null || stage < next) return;
        long now = System.nanoTime();
        into[stage] += now - last;
        last = now;
        next = stage + 1;
        marks++;
    }

    public static int stages() {
        return STAGES.length;
    }
}
