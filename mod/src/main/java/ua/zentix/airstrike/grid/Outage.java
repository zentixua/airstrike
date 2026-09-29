package ua.zentix.airstrike.grid;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * Отключение района: кварталы, чьи центры ближе {@code radius} к центру, гаснут по очереди от него наружу
 * (каскад со скоростью {@code speed} блоков за тик, у каждого квартала свой разброс ±25 %), а после
 * {@code restoreAt} включаются вразнобой за {@code restoreSpread} тиков. Всё — функции квартала и времени:
 * и сервер, и чанк, загруженный через час, знают, должен ли он быть тёмным.
 *
 * @param node      узел сети, чей выход из строя его вызвал; -1 — не узел (ядерный удар, команда)
 * @param restoreAt когда начать возвращать свет; {@link #NEVER} — пока не вернут командой
 */
public record Outage(int id, double x, double z, double radius, long start, double speed, long restoreAt, int restoreSpread, int node) {
    public static final long NEVER = Long.MAX_VALUE;
    /** Разброс момента, когда гаснет квартал: ±25 % от прихода каскада. */
    private static final double SPREAD = 0.5;

    public static final Codec<Outage> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.INT.fieldOf("id").forGetter(Outage::id),
            Codec.DOUBLE.fieldOf("x").forGetter(Outage::x),
            Codec.DOUBLE.fieldOf("z").forGetter(Outage::z),
            Codec.DOUBLE.fieldOf("radius").forGetter(Outage::radius),
            Codec.LONG.fieldOf("start").forGetter(Outage::start),
            Codec.DOUBLE.fieldOf("speed").forGetter(Outage::speed),
            Codec.LONG.fieldOf("restore_at").forGetter(Outage::restoreAt),
            Codec.INT.fieldOf("restore_spread").forGetter(Outage::restoreSpread),
            Codec.INT.optionalFieldOf("node", -1).forGetter(Outage::node)
    ).apply(i, Outage::new));

    /** Квартал внутри района отключения. */
    public boolean covers(long district) {
        double dx = Districts.seedX(district) - x, dz = Districts.seedZ(district) - z;
        return dx * dx + dz * dz <= radius * radius;
    }

    /** Когда гаснет квартал (игровое время). */
    public long darkAt(long district) {
        double d = Math.hypot(Districts.seedX(district) - x, Districts.seedZ(district) - z);
        return start + Math.round(d / speed * (1 - SPREAD / 2 + SPREAD * Districts.unit(district, 3)));
    }

    /** Когда в квартал возвращается свет; {@link #NEVER} — не возвращается. */
    public long lightAt(long district) {
        return restoreAt == NEVER ? NEVER : restoreAt + Math.round(restoreSpread * Districts.unit(district, 4));
    }

    /** Квартал тёмен от этого отключения в момент {@code now}. */
    public boolean dark(long district, long now) {
        return covers(district) && darkAt(district) <= now && now < lightAt(district);
    }

    /** Свет вернулся во все кварталы: отключение можно забыть. */
    public boolean over(long now) {
        return restoreAt != NEVER && now >= restoreAt + restoreSpread;
    }

    /** То же отключение со светом, который начнут возвращать в {@code at} за {@code spread} тиков. */
    public Outage restoring(long at, int spread) {
        return new Outage(id, x, z, radius, start, speed, at, spread, node);
    }

    /** Самое позднее время, когда гаснет квартал этого района (каскад дошёл до края). */
    public long lastDark() {
        return start + Math.round(radius / speed * (1 + SPREAD / 2)) + 1;
    }
}
