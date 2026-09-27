package ua.zentix.airstrike.target;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.UUIDUtil;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import ua.zentix.airstrike.compat.SubLevels;

import java.util.Optional;
import java.util.UUID;

/**
 * Цель удара. Движущиеся цели (сущность, аппарат) каждый тик пересчитываются в мировую точку;
 * если цель пропала (умерла, ушла в другой мир, аппарат разобран) — {@link #resolve} пуст,
 * и снаряд летит в последнюю известную точку.
 */
public sealed interface Target permits Target.Point, Target.OfEntity, Target.OfSubLevel {
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

    /** Сущность (игрок, моб, поезд, механизм Create) и точка попадания относительно её позиции. */
    record OfEntity(UUID uuid, Vec3 offset) implements Target {
        static final MapCodec<OfEntity> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                UUIDUtil.CODEC.fieldOf("uuid").forGetter(OfEntity::uuid),
                Vec3.CODEC.fieldOf("offset").forGetter(OfEntity::offset)
        ).apply(i, OfEntity::new));

        public static OfEntity of(Entity entity, Vec3 hit) {
            return new OfEntity(entity.getUUID(), hit.subtract(entity.position()));
        }

        /** Центр тела — для ударов по игроку по нику. */
        public static OfEntity center(Entity entity) {
            return new OfEntity(entity.getUUID(), new Vec3(0, entity.getBbHeight() * 0.5, 0));
        }

        @Override
        public Optional<Vec3> resolve(ServerLevel level) {
            Entity e = level.getEntity(uuid);
            if (e == null || !e.isAlive()) return Optional.empty();
            return Optional.of(e.position().add(offset));
        }

        @Override
        public Target offset(Vec3 delta) {
            return new OfEntity(uuid, offset.add(delta));
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
