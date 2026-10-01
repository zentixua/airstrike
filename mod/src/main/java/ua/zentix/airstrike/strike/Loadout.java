package ua.zentix.airstrike.strike;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.util.Mth;

/**
 * Настройки пульта (компонент предмета): оружие, количество, разброс, режим цели, выбранный игрок
 * и — для ядерной боеголовки — мощность и вид подрыва. Значения зажимаются при каждом создании,
 * поэтому из пакета или NBT невалидное не приходит.
 */
public record Loadout(WeaponType weapon, int count, int spread, TargetMode mode, String player, Nuke nuke) {
    public static final int MAX_COUNT = 100;
    public static final int MAX_SPREAD = 500;
    public static final Loadout DEFAULT = new Loadout(WeaponType.MISSILE, 1, 0, TargetMode.LOOK, "", Nuke.DEFAULT);

    /**
     * Ядерная боеголовка: мощность, кт, воздушный (true) или наземный подрыв и — для крылатой ракеты и B-2 —
     * нести ли её вместо обычной ({@code onCarrier}; у МБР она всегда ядерная).
     */
    public record Nuke(int yieldKt, boolean airBurst, boolean onCarrier) {
        /**
         * Наибольшая мощность, кт. Больше — тяжёлая зона (от 5 psi у земли) не помещается в память и бюджет разрушений:
         * руины отстали бы от фронта волны. Старые пульты и сохранения с большей мощностью урезаются до неё.
         */
        public static final int MAX_YIELD = 15;
        public static final Nuke DEFAULT = new Nuke(15, true, false);
        public static final Codec<Nuke> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.INT.optionalFieldOf("yield", DEFAULT.yieldKt).forGetter(Nuke::yieldKt),
                Codec.BOOL.optionalFieldOf("air_burst", DEFAULT.airBurst).forGetter(Nuke::airBurst),
                Codec.BOOL.optionalFieldOf("on_carrier", false).forGetter(Nuke::onCarrier)
        ).apply(i, Nuke::new));
        public static final StreamCodec<ByteBuf, Nuke> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Nuke::yieldKt, ByteBufCodecs.BOOL, Nuke::airBurst, ByteBufCodecs.BOOL, Nuke::onCarrier, Nuke::new);

        public Nuke {
            yieldKt = Mth.clamp(yieldKt, 1, MAX_YIELD);
        }

        public Nuke(int yieldKt, boolean airBurst) {
            this(yieldKt, airBurst, false);
        }

        public Nuke withOnCarrier(boolean on) {
            return new Nuke(yieldKt, airBurst, on);
        }
    }

    /** Эта настройка пульта несёт ядерную БЧ: МБР или ракета/B-2 с ядерной БЧ. */
    public boolean nuclear() {
        return weapon.spec().warhead().always() || nuke.onCarrier() && carriesNuke(weapon);
    }

    /** Кто может нести ядерную БЧ, кроме МБР (паспорт). */
    public static boolean carriesNuke(WeaponType w) {
        return w.spec().warhead().optional();
    }

    public static final Codec<Loadout> CODEC = RecordCodecBuilder.create(i -> i.group(
            WeaponType.CODEC.optionalFieldOf("weapon", DEFAULT.weapon).forGetter(Loadout::weapon),
            Codec.INT.optionalFieldOf("count", DEFAULT.count).forGetter(Loadout::count),
            Codec.INT.optionalFieldOf("spread", DEFAULT.spread).forGetter(Loadout::spread),
            TargetMode.CODEC.optionalFieldOf("mode", DEFAULT.mode).forGetter(Loadout::mode),
            Codec.STRING.optionalFieldOf("player", "").forGetter(Loadout::player),
            Nuke.CODEC.optionalFieldOf("nuke", Nuke.DEFAULT).forGetter(Loadout::nuke)
    ).apply(i, Loadout::new));

    public static final StreamCodec<ByteBuf, Loadout> STREAM_CODEC = StreamCodec.composite(
            WeaponType.STREAM_CODEC, Loadout::weapon,
            ByteBufCodecs.VAR_INT, Loadout::count,
            ByteBufCodecs.VAR_INT, Loadout::spread,
            TargetMode.STREAM_CODEC, Loadout::mode,
            ByteBufCodecs.stringUtf8(16), Loadout::player,
            Nuke.STREAM_CODEC, Loadout::nuke,
            Loadout::new);

    public Loadout {
        count = Mth.clamp(count, 1, MAX_COUNT);
        spread = Mth.clamp(spread, 0, MAX_SPREAD);
        player = player == null ? "" : player.length() > 16 ? player.substring(0, 16) : player;
        nuke = nuke == null ? Nuke.DEFAULT : nuke;
    }

    public Loadout withWeapon(WeaponType w) {
        // оружие, которое одиночным не стреляет (РСЗО): по умолчанию — очередь из паспорта
        WeaponSpec.Salvo salvo = w.spec().salvo();
        if (salvo.count() > 1 && weapon != w && count == 1) return new Loadout(w, salvo.count(), Math.max(spread, salvo.spread()), mode, player, nuke);
        return new Loadout(w, count, spread, mode, player, nuke);
    }

    public Loadout withCount(int c) {
        return new Loadout(weapon, c, spread, mode, player, nuke);
    }

    public Loadout withSpread(int s) {
        return new Loadout(weapon, count, s, mode, player, nuke);
    }

    public Loadout withMode(TargetMode m) {
        return new Loadout(weapon, count, spread, m, player, nuke);
    }

    public Loadout withPlayer(String p) {
        return new Loadout(weapon, count, spread, mode, p, nuke);
    }

    public Loadout withNuke(Nuke n) {
        return new Loadout(weapon, count, spread, mode, player, n);
    }
}
