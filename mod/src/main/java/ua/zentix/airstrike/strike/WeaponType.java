package ua.zentix.airstrike.strike;

import com.mojang.serialization.Codec;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.util.ByIdMap;
import net.minecraft.util.StringRepresentable;
import net.minecraft.util.RandomSource;

import java.util.Locale;
import java.util.function.IntFunction;

/** Виды оружия. Параметры полёта — в самих сущностях, здесь то, что нужно пуску и интерфейсу. */
public enum WeaponType implements StringRepresentable {
    /** Дрон-камикадзе в духе Shahed-136: ≈150 км/ч, крейсер над рельефом, пикирование на цель. */
    DRONE(0, "drone", 20, 40, SirenKind.AIR_RAID),
    /** Крылатая ракета: бреющий полёт, горка, пикирование; 230 м/с. */
    MISSILE(1, "missile", 15, 30, SirenKind.MISSILE),
    /** B-2 и бетонобойная бомба: пробивает грунт и взрывается под землёй. */
    BUNKER(2, "bunker", 60, 80, SirenKind.AIR_RAID),
    /** Межконтинентальная баллистическая ракета с ядерной боеголовкой (см. пакет nuclear). */
    NUKE(3, "nuke", 200, 300, SirenKind.NUCLEAR);

    public static final Codec<WeaponType> CODEC = StringRepresentable.fromEnum(WeaponType::values);
    private static final IntFunction<WeaponType> BY_ID = ByIdMap.continuous(WeaponType::id, values(), ByIdMap.OutOfBoundsStrategy.CLAMP);
    public static final StreamCodec<ByteBuf, WeaponType> STREAM_CODEC = ByteBufCodecs.idMapper(BY_ID, WeaponType::id);

    private final int id;
    private final String name;
    private final int salvoGapMin;
    private final int salvoGapMax;
    private final SirenKind siren;

    WeaponType(int id, String name, int salvoGapMin, int salvoGapMax, SirenKind siren) {
        this.id = id;
        this.name = name;
        this.salvoGapMin = salvoGapMin;
        this.salvoGapMax = salvoGapMax;
        this.siren = siren;
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
        return Component.translatable("airstrike.weapon." + name + ".desc");
    }

    public SirenKind siren() {
        return siren;
    }

    /** Пауза между пусками в залпе, тиков. */
    public int salvoGap(RandomSource random) {
        return salvoGapMin + random.nextInt(salvoGapMax - salvoGapMin + 1);
    }

    public WeaponType next() {
        return values()[(ordinal() + 1) % values().length];
    }

    public WeaponType previous() {
        return values()[(ordinal() + values().length - 1) % values().length];
    }

    /** Имена в командах: английские и русские синонимы (как в датапаке, «shahed» — это дрон). */
    public static WeaponType parse(String s) {
        return switch (s.toLowerCase(Locale.ROOT)) {
            case "drone", "shahed", "шахед", "дрон" -> DRONE;
            case "missile", "ракета" -> MISSILE;
            case "bunker", "bomb", "бомба" -> BUNKER;
            case "nuke", "icbm", "ядерка", "ядерная" -> NUKE;
            default -> null;
        };
    }

    public enum SirenKind {
        AIR_RAID, MISSILE, NUCLEAR
    }
}
