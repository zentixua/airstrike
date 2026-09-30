package ua.zentix.airstrike.strike;

import com.mojang.serialization.Codec;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.util.ByIdMap;
import net.minecraft.util.StringRepresentable;
import net.minecraft.util.RandomSource;
import ua.zentix.airstrike.Airstrike;

import java.util.List;
import java.util.Locale;
import java.util.function.IntFunction;

/** Виды оружия: номер, имя и паспорт ({@link WeaponSpec}) — всё, чем оружие отличается от другого. */
public enum WeaponType implements StringRepresentable {
    /** Дрон-камикадзе в духе Shahed-136: ≈150 км/ч, крейсер над рельефом, пикирование на цель. */
    DRONE(0, "drone", WeaponSpec.DRONE),
    /** Крылатая ракета: бреющий полёт, горка, пикирование; 80 м/с — медленнее настоящей, чтобы подлёт было видно. */
    MISSILE(1, "missile", WeaponSpec.MISSILE),
    /** B-2 и бетонобойная бомба: пробивает грунт и взрывается под землёй. */
    BUNKER(2, "bunker", WeaponSpec.BUNKER),
    /** Межконтинентальная баллистическая ракета с ядерной боеголовкой (см. пакет nuclear). */
    NUKE(3, "nuke", WeaponSpec.NUKE),
    /**
     * РСЗО в духе БМ-21 «Град»: неуправляемые реактивные снаряды по баллистике с пакета из 40 труб, залп очередью
     * по полсекунды. Номер в {@link #id} — порядок в перечислении (индексы {@code values()} совпадают с id).
     */
    ROCKET(4, "rocket", WeaponSpec.ROCKET),
    /** Барражирующий боеприпас в духе «Ланцета»: кружит над целью, пикирует по команде или по истечении барража. */
    LOITER(5, "loiter", WeaponSpec.LOITER);

    public static final Codec<WeaponType> CODEC = StringRepresentable.fromEnum(WeaponType::values);
    private static final IntFunction<WeaponType> BY_ID = ByIdMap.continuous(WeaponType::id, values(), ByIdMap.OutOfBoundsStrategy.CLAMP);
    /** По номеру; чужой номер (старый мир, другая версия) — ближайшее оружие, а не ошибка. */
    public static WeaponType byId(int id) {
        return BY_ID.apply(id);
    }

    public static final StreamCodec<ByteBuf, WeaponType> STREAM_CODEC = ByteBufCodecs.idMapper(BY_ID, WeaponType::id);

    private final int id;
    private final String name;
    private final WeaponSpec spec;

    WeaponType(int id, String name, WeaponSpec spec) {
        this.id = id;
        this.name = name;
        this.spec = spec;
    }

    /** Паспорт оружия. */
    public WeaponSpec spec() {
        return spec;
    }

    public int id() {
        return id;
    }

    @Override
    public String getSerializedName() {
        return name;
    }

    public Component displayName() {
        return Component.translatable("airstrike.weapon." + name);
    }

    public Component description() {
        return Component.translatable("airstrike.weapon." + name + ".desc", Component.keybind(Airstrike.CAMERA_KEY));
    }

    public SirenKind siren() {
        return spec.siren();
    }

    /** Пауза между пусками в залпе, тиков. */
    public int salvoGap(RandomSource random) {
        return spec.salvoGap(random);
    }

    /** Порядок в пульте и при прокрутке в бинокле: от лёгкого к ядерному. */
    public static List<WeaponType> menu() {
        return List.of(DRONE, LOITER, MISSILE, ROCKET, BUNKER, NUKE);
    }

    public WeaponType next() {
        List<WeaponType> m = menu();
        return m.get((m.indexOf(this) + 1) % m.size());
    }

    public WeaponType previous() {
        List<WeaponType> m = menu();
        return m.get((m.indexOf(this) + m.size() - 1) % m.size());
    }

    /** Имена в командах: английские и русские синонимы (как в датапаке, «shahed» — это дрон). */
    public static WeaponType parse(String s) {
        return switch (s.toLowerCase(Locale.ROOT)) {
            case "drone", "shahed", "шахед", "дрон" -> DRONE;
            case "missile", "ракета" -> MISSILE;
            case "bunker", "bomb", "бомба" -> BUNKER;
            case "nuke", "icbm", "ядерка", "ядерная" -> NUKE;
            case "rocket", "grad", "mlrs", "град", "рсзо" -> ROCKET;
            case "loiter", "lancet", "ланцет", "барраж" -> LOITER;
            default -> null;
        };
    }

    public enum SirenKind {
        AIR_RAID, MISSILE, NUCLEAR
    }
}
