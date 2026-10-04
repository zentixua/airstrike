package ua.zentix.airstrike.strike;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import ua.zentix.airstrike.launcher.FixedLauncherBlockEntity;
import ua.zentix.airstrike.util.Nbt;
import ua.zentix.airstrike.util.Terrain;

/**
 * Откуда приказ пускает снаряды, если не от стреляющего: место пуска ({@link Place}, {@code /airstrike salvo … from x z}
 * — пусковая встаёт на нём, {@link LaunchSite.Post}) или стационарная пусковая ({@link Fixed}: снаряды — с её пакета
 * и из её запаса). От точки пуска {@link #at} считаются дальность маршрута ({@link ServerActions#routeProblem}) и курс
 * захода; залп хранит её у себя ({@link SalvoData}).
 */
public sealed interface LaunchOrigin {
    /** Точка пуска (по горизонтали; высоту берёт пуск). */
    Vec3 at();

    /** Место пуска из приказа. */
    record Place(Vec3 at) implements LaunchOrigin {}

    /** Стационарная пусковая в {@code pos} ({@link FixedLauncherBlockEntity}). */
    record Fixed(BlockPos pos) implements LaunchOrigin {
        public Fixed {
            pos = pos.immutable();
        }

        @Override
        public Vec3 at() {
            return Vec3.atBottomCenterOf(pos);
        }

        /** Пусковая на месте; null — её чанк не готов (ради пуска его не грузим) или блока там больше нет. */
        @Nullable
        public FixedLauncherBlockEntity launcher(ServerLevel level) {
            if (!Terrain.ready(level, pos)) return null;
            return level.getBlockEntity(pos) instanceof FixedLauncherBlockEntity be ? be : null;
        }
    }

    /** Записать {@code origin} в {@code tag} (null — ничего): место — прежним ключом {@code from}, пусковую — {@code from_launcher}. */
    static void save(CompoundTag tag, @Nullable LaunchOrigin origin) {
        switch (origin) {
            case Place p -> Nbt.putVec(tag, "from", p.at());
            case Fixed f -> tag.putLong("from_launcher", f.pos().asLong());
            case null -> {}
        }
    }

    /** Прочитать то, что записал {@link #save}; null — не было. */
    @Nullable
    static LaunchOrigin load(CompoundTag tag) {
        if (tag.contains("from_launcher")) return new Fixed(BlockPos.of(tag.getLong("from_launcher")));
        Vec3 from = Nbt.getVec(tag, "from");
        return from == null ? null : new Place(from);
    }
}
