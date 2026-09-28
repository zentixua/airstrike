package ua.zentix.airstrike.client.fx.particle;

import net.minecraft.core.particles.ParticleGroup;

import java.util.Optional;

/**
 * Места для частиц эффектов в движке частиц. У каждого слоя ({@link FxRenderTypes}) одна очередь на
 * {@link #QUEUE} частиц, и переполненная очередь молча вытесняет самые старые — а старше всех долгие облака взрывов:
 * посреди большого залпа они пропадают и появляются. Поэтому каждая частица эффектов входит в группу — ванильный
 * {@link ParticleGroup}: движок не рождает частицу, если её группа полна. Сумма групп слоя меньше очереди, так что
 * вытеснения нет, а важное (облака, вспышки) не делит место с мелочью (шлейфы, хвосты осколков).
 */
public enum FxBudget {
    /** Облака и огонь взрывов, облака старта, ударные кольца. */
    CLOUD(false, 6144),
    /** Шлейфы снарядов и инверсионные следы. */
    TRAIL(false, 6144),
    /** Дымные хвосты осколков и обломков. */
    DEBRIS(false, 3840),
    /** Вспышки. */
    FLASH(true, 512),
    /** Искры. */
    SPARK(true, 15360);

    /** Очередь слоя в движке частиц ({@code ParticleEngine}: {@code EvictingQueue.create(16384)}). */
    public static final int QUEUE = 16384;

    /** Слой: свет складывается ({@link FxRenderTypes#GLOW}) или смешивается ({@link FxRenderTypes#BLEND}). */
    final boolean additive;
    final int limit;
    final Optional<ParticleGroup> group;

    FxBudget(boolean additive, int limit) {
        this.additive = additive;
        this.limit = limit;
        this.group = Optional.of(new ParticleGroup(limit));
    }

    /** Сколько частиц группы может жить одновременно. */
    public int limit() {
        return limit;
    }

    /** Сколько мест у групп слоя вместе. */
    static int layerTotal(boolean additive) {
        int sum = 0;
        for (FxBudget b : values()) {
            if (b.additive == additive) sum += b.limit;
        }
        return sum;
    }
}
