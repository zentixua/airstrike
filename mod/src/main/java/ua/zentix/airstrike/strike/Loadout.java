package ua.zentix.airstrike.strike;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.util.Mth;

/**
 * Настройки пульта (компонент предмета): оружие, количество, разброс, режим цели и выбранный игрок.
 * Значения зажимаются при каждом создании, поэтому из пакета или NBT невалидное не приходит.
 */
public record Loadout(WeaponType weapon, int count, int spread, TargetMode mode, String player) {
    public static final int MAX_COUNT = 100;
    public static final int MAX_SPREAD = 500;
    public static final Loadout DEFAULT = new Loadout(WeaponType.MISSILE, 1, 25, TargetMode.LOOK, "");

    public static final Codec<Loadout> CODEC = RecordCodecBuilder.create(i -> i.group(
            WeaponType.CODEC.optionalFieldOf("weapon", DEFAULT.weapon).forGetter(Loadout::weapon),
            Codec.INT.optionalFieldOf("count", DEFAULT.count).forGetter(Loadout::count),
            Codec.INT.optionalFieldOf("spread", DEFAULT.spread).forGetter(Loadout::spread),
            TargetMode.CODEC.optionalFieldOf("mode", DEFAULT.mode).forGetter(Loadout::mode),
            Codec.STRING.optionalFieldOf("player", "").forGetter(Loadout::player)
    ).apply(i, Loadout::new));

    public static final StreamCodec<ByteBuf, Loadout> STREAM_CODEC = StreamCodec.composite(
            WeaponType.STREAM_CODEC, Loadout::weapon,
            ByteBufCodecs.VAR_INT, Loadout::count,
            ByteBufCodecs.VAR_INT, Loadout::spread,
            TargetMode.STREAM_CODEC, Loadout::mode,
            ByteBufCodecs.stringUtf8(16), Loadout::player,
            Loadout::new);

    public Loadout {
        count = Mth.clamp(count, 1, MAX_COUNT);
        spread = Mth.clamp(spread, 0, MAX_SPREAD);
        player = player == null ? "" : player.length() > 16 ? player.substring(0, 16) : player;
    }

    public Loadout withWeapon(WeaponType w) {
        return new Loadout(w, count, spread, mode, player);
    }

    public Loadout withCount(int c) {
        return new Loadout(weapon, c, spread, mode, player);
    }

    public Loadout withSpread(int s) {
        return new Loadout(weapon, count, s, mode, player);
    }

    public Loadout withMode(TargetMode m) {
        return new Loadout(weapon, count, spread, m, player);
    }

    public Loadout withPlayer(String p) {
        return new Loadout(weapon, count, spread, mode, p);
    }

    public boolean isSalvo() {
        return count > 1;
    }
}
