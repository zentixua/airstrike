package ua.zentix.airstrike.nuclear.radiation;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * Облучение игрока (data attachment): накопленная доза, Гр; заражение одежды и кожи радиоактивной пылью
 * (облучает, пока не смыть водой), Р/ч; момент, когда доза перевалила 1 Гр (от него идут стадии болезни),
 * и текущая мощность дозы для счётчика Гейгера.
 */
public record RadiationDose(float doseGy, float contamination, long exposedAt, float rate) {
    public static final RadiationDose NONE = new RadiationDose(0, 0, -1, 0);

    public static final Codec<RadiationDose> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.FLOAT.fieldOf("dose").forGetter(RadiationDose::doseGy),
            Codec.FLOAT.optionalFieldOf("contamination", 0f).forGetter(RadiationDose::contamination),
            Codec.LONG.optionalFieldOf("exposed_at", -1L).forGetter(RadiationDose::exposedAt),
            Codec.FLOAT.optionalFieldOf("rate", 0f).forGetter(RadiationDose::rate)
    ).apply(i, RadiationDose::new));

    public boolean isExposed() {
        return exposedAt >= 0;
    }
}
