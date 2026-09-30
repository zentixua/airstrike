package ua.zentix.airstrike.target;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import ua.zentix.airstrike.compat.SubLevels;
import ua.zentix.airstrike.util.Terrain;

import java.util.Optional;
import java.util.UUID;

/**
 * Цель удара. Движущиеся цели (сущность, аппарат) каждый тик пересчитываются в мировую точку;
 * если цель пропала (умерла, ушла в другой мир, аппарат разобран) — {@link #resolve} пуст,
 * и снаряд летит в последнюю известную точку.
 */
public sealed interface Target permits Target.Point, Target.Ground, Target.OfEntity, Target.OfSubLevel {
    Codec<Target> CODEC = Kind.CODEC.dispatch(Target::kind, Kind::codec);

    Optional<Vec3> resolve(ServerLevel level);

    /** Та же цель со сдвигом (разброс залпа). */
    Target offset(Vec3 delta);

    Kind kind();

    /** Неподвижная точка мира. */
    record Point(Vec3 pos) implements Target {
        static final MapCodec<Point> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                Vec3.CODEC.fieldOf("pos").forGetter(Point::pos)
        ).apply(i, Point::new));

        @Override
        public Optional<Vec3> resolve(ServerLevel level) {
            return Optional.of(pos);
        }

        @Override
        public Target offset(Vec3 delta) {
            return new Point(pos.add(delta));
        }

        @Override
        public Kind kind() {
            return Kind.POINT;
        }
    }

    /**
     * Место на земле по координатам x и z (точка с карты): высота — поверхность в этом месте, как только её чанк готов
     * (район цели грузится заранее), а до того — оценка {@code pos.y}. Цель не движется и не теряется.
     */
    record Ground(Vec3 pos) implements Target {
        static final MapCodec<Ground> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                Vec3.CODEC.fieldOf("pos").forGetter(Ground::pos)
        ).apply(i, Ground::new));

        /** Место без карты клиента (команда, сервер): оценка до загрузки чанка — рельеф генератора. */
        public static Ground at(ServerLevel level, double x, double z) {
            return at(level, x, z, Optional.empty());
        }

        /**
         * Место на карте (x, z): высоту знает только сервер. Чанк готов — верх, как его рисует карта (кроны деревьев,
         * крыши: снаряд, шедший к земле под кронами, взрывался в них, не долетев). Иначе оценка — верх по карте
         * клиента {@code mapSurface} (Distant Horizons или чанки клиента: карта, на которой выбрано место), а если карта
         * там пуста — рельеф, каким его строит генератор мира ({@code ChunkGenerator.getBaseHeight}: шум без загрузки
         * чанка, без деревьев и построек; у мира, построенного не генератором, — город с карты мира, — он с поверхностью
         * не совпадает: Greenfield, 30.09.2026 — 63 под крышей на 107). Когда чанк у цели загрузится, {@link #surface}
         * уточнит.
         *
         * @param mapSurface первый воздух над землёй по карте клиента; вне высот мира не в счёт
         */
        public static Ground at(ServerLevel level, double x, double z, Optional<Integer> mapSurface) {
            int estimate = mapSurface.filter(h -> !level.isOutsideBuildHeight(h - 1))
                    .orElseGet(() -> Terrain.surface(level, Heightmap.Types.MOTION_BLOCKING, Mth.floor(x), Mth.floor(z)));
            return new Ground(new Ground(new Vec3(x, estimate - 0.5, z)).surface(level));
        }

        @Override
        public Optional<Vec3> resolve(ServerLevel level) {
            return Optional.of(surface(level));
        }

        /** Середина верхнего блока (с листвой, как на карте); чанк не готов — оценка из {@link #at}. */
        public Vec3 surface(ServerLevel level) {
            BlockPos column = BlockPos.containing(pos.x, 0, pos.z);
            if (!Terrain.ready(level, column)) return pos;
            return new Vec3(pos.x, Terrain.height(level, Heightmap.Types.MOTION_BLOCKING, column.getX(), column.getZ()) - 0.5, pos.z);
        }

        @Override
        public Target offset(Vec3 delta) {
            return new Ground(pos.add(delta.x, 0, delta.z));
        }

        @Override
        public Kind kind() {
            return Kind.GROUND;
        }
    }

    /**
     * Сущность (игрок, моб, поезд, механизм Create) и точка попадания относительно её позиции. {@code spread} —
     * смещение снаряда залпа по горизонтали: у цели на земле его точка — на земле в этом месте (цель на краю
     * обрыва — точка внизу, а не в воздухе над обрывом, куда снаряд пикировал бы без конца), у цели в воздухе
     * (на аппарате, в полёте) — на её высоте, как у залпа по точке.
     */
    record OfEntity(UUID uuid, Vec3 offset, Vec3 spread) implements Target {
        static final MapCodec<OfEntity> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                UUIDUtil.CODEC.fieldOf("uuid").forGetter(OfEntity::uuid),
                Vec3.CODEC.fieldOf("offset").forGetter(OfEntity::offset),
                Vec3.CODEC.optionalFieldOf("spread", Vec3.ZERO).forGetter(OfEntity::spread)
        ).apply(i, OfEntity::new));

        public OfEntity(UUID uuid, Vec3 offset) {
            this(uuid, offset, Vec3.ZERO);
        }

        public static OfEntity of(Entity entity, Vec3 hit) {
            return new OfEntity(entity.getUUID(), hit.subtract(entity.position()));
        }

        /** Центр тела — для ударов по игроку по нику. */
        public static OfEntity center(Entity entity) {
            return new OfEntity(entity.getUUID(), new Vec3(0, entity.getBbHeight() * 0.5, 0));
        }

        @Override
        public Optional<Vec3> resolve(ServerLevel level) {
            return resolve(level, level.getEntity(uuid));
        }

        /** Точка цели у уже найденной сущности с её UUID ({@code null} — её нет в этом мире). */
        public Optional<Vec3> resolve(ServerLevel level, @Nullable Entity e) {
            if (e == null || !e.isAlive()) return Optional.empty();
            Vec3 at = e.position().add(offset);
            if (spread.equals(Vec3.ZERO)) return Optional.of(at);
            double x = at.x + spread.x, z = at.z + spread.z;
            BlockPos column = BlockPos.containing(x, 0, z);
            // высота земли — только из готового чанка (район цели грузится заранее); иначе — на высоте цели
            if (!e.onGround() || !Terrain.ready(level, column)) return Optional.of(new Vec3(x, at.y, z));
            return Optional.of(new Vec3(x, Terrain.height(level, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, column.getX(), column.getZ()) - 0.5, z));
        }

        @Override
        public Target offset(Vec3 delta) {
            return new OfEntity(uuid, offset, spread.add(delta));
        }

        @Override
        public Kind kind() {
            return Kind.ENTITY;
        }
    }

    /** Точка на летательном аппарате Sable в координатах его плота: следуем за аппаратом, как бы он ни летел. */
    record OfSubLevel(Vec3 plotPos) implements Target {
        static final MapCodec<OfSubLevel> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                Vec3.CODEC.fieldOf("plot_pos").forGetter(OfSubLevel::plotPos)
        ).apply(i, OfSubLevel::new));

        @Override
        public Optional<Vec3> resolve(ServerLevel level) {
            if (!SubLevels.isInPlot(level, plotPos)) return Optional.empty();
            return Optional.of(SubLevels.toWorld(level, plotPos));
        }

        @Override
        public Target offset(Vec3 delta) {
            return new OfSubLevel(plotPos.add(delta));
        }

        @Override
        public Kind kind() {
            return Kind.SUB_LEVEL;
        }
    }

    enum Kind implements StringRepresentable {
        POINT("point", Point.CODEC),
        GROUND("ground", Ground.CODEC),
        ENTITY("entity", OfEntity.CODEC),
        SUB_LEVEL("sub_level", OfSubLevel.CODEC);

        static final Codec<Kind> CODEC = StringRepresentable.fromEnum(Kind::values);

        private final String name;
        private final MapCodec<? extends Target> codec;

        Kind(String name, MapCodec<? extends Target> codec) {
            this.name = name;
            this.codec = codec;
        }

        MapCodec<? extends Target> codec() {
            return codec;
        }

        @Override
        public String getSerializedName() {
            return name;
        }
    }
}
