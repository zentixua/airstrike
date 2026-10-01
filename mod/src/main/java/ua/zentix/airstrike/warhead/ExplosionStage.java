package ua.zentix.airstrike.warhead;

/**
 * Шаг взрыва боевой части ({@link StagedExplosion}) для замера: где время единиц взрыва. Замер у хоста: единица
 * «лучи и урон» стоила до 150 мс, и чтобы решить, как её делить, нужно видеть, чья это цена.
 */
public enum ExplosionStage {
    /** До лучей: {@code new Explosion} и {@code ExplosionEvent.Start} (обработчики модов). */
    START("Start"),
    /** Ванильный {@code Explosion.explode} целиком (аппарат Sable рядом): лучи, сущности, {@code Detonate}, урон. */
    VANILLA("ванильный взрыв"),
    /** {@code gameEvent(EXPLODE)}: слушатели вибраций (скалк, моды). */
    GAME_EVENT("событие"),
    /** Лучи по блокам. */
    RAYS("лучи"),
    /** Сбор сущностей в кубе досягаемости. */
    ENTITIES("сбор сущностей"),
    /** {@code ExplosionEvent.Detonate}: обработчики модов (и наши: блэкаут, запись для проверок). */
    DETONATE("Detonate"),
    /** Урон и отбрасывание: {@code getSeenPercent} (лучи к сущности), {@code hurt} — смерть, лут, обработчики. */
    DAMAGE("урон"),
    /** Раздел выбранных лучами позиций на плоты аппаратов и мир, чтение блоков мира (воздух без огня — прочь). */
    SPLIT("раздел"),
    /** Снятие блоков аппаратов (плоты Sable) — одним разом после урона, со сверкой аппарата и блока. */
    CRAFTS("блоки аппаратов"),
    /** Снимок блоков мира для порций и сортировка от центра. */
    SNAPSHOT("снимок");

    public final String label;

    ExplosionStage(String label) {
        this.label = label;
    }
}
