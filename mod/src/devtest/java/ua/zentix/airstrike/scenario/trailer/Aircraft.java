package ua.zentix.airstrike.scenario.trailer;

import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import dev.ryanhcode.sable.api.physics.PhysicsPipeline;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Истребитель для трейлера — настоящий аппарат Sable (sub-level), собранный из блоков, который летит по сценарию.
 * Только devtest: мод с Sable работает через companion, а здесь нужен сам Sable (compileOnly, в игре он есть).
 * <p>
 * Сборка: блоки ставятся в мир носом на юг (+Z) и переносятся в плот {@link SubLevelAssemblyHelper#assembleBlocks} —
 * то же, что делает {@code /sable assemble}. Точка аппарата — его центр масс (вокруг него и поворот).
 * <p>
 * Полёт: у Sable нет «кинематического» тела для аппаратов, поэтому каждый тик {@link #fly} ставит аппарат в точку
 * ({@link PhysicsPipeline#teleport}, как {@code /sable sub_level teleport}). Задать скорость пути вместе с
 * телепортом нельзя: Rapier применяет телепорт в шаге физики и обнуляет скорость (проверено GameTest — аппарат
 * со скоростью 60 блоков/с оставался на месте), поэтому аппарат между вызовами стоит, а физика успевает лишь
 * уронить его гравитацией на 0.014 блока. Физика Sable тикает в начале тика мира и стоит при
 * {@code /tick freeze} — {@code fly} при заморозке просто переставляет аппарат.
 * <p>
 * Чанки на пути должны тикать блоки (их держит игрок или камера): вошедший в чанк без тика аппарат Sable
 * убирает в «выгруженные» ({@code PhysicsChunkTicketManager}), и {@link #alive()} становится false.
 */
public final class Aircraft {
    private final ServerLevel level;
    private final ServerSubLevel sub;
    private final int blocks;

    private Aircraft(ServerLevel level, ServerSubLevel sub, int blocks) {
        this.level = level;
        this.sub = sub;
        this.blocks = blocks;
    }

    /**
     * Собирает истребитель: блоки ставятся начиная с {@code origin} (там должен быть воздух: собираются только свои
     * блоки, но чужие на их месте затираются) и сразу уходят в аппарат, который поворачивается на курс {@code yaw}
     * (как у Minecraft: 0 — юг) вокруг центра масс.
     */
    public static Aircraft build(ServerLevel level, BlockPos origin, float yaw) {
        ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) throw new IllegalStateException("в мире нет Sable");
        Map<BlockPos, BlockState> shape = shape(origin);
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (Map.Entry<BlockPos, BlockState> e : shape.entrySet()) {
            BlockPos p = e.getKey();
            // без соседних обновлений: стекло и прочее не должны ничего пересчитывать до сборки
            level.setBlock(p, e.getValue(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
            minX = Math.min(minX, p.getX()); minY = Math.min(minY, p.getY()); minZ = Math.min(minZ, p.getZ());
            maxX = Math.max(maxX, p.getX()); maxY = Math.max(maxY, p.getY()); maxZ = Math.max(maxZ, p.getZ());
        }
        List<BlockPos> blocks = new ArrayList<>(shape.keySet());
        ServerSubLevel sub = SubLevelAssemblyHelper.assembleBlocks(level, origin, blocks,
                new BoundingBox3i(minX, minY, minZ, maxX, maxY, maxZ));
        Aircraft a = new Aircraft(level, sub, blocks.size());
        a.fly(a.position(), yaw, 0, 0);
        return a;
    }

    /**
     * Ставит аппарат центром масс в {@code pos} с курсом, тангажом (как у Minecraft: плюс — нос вниз) и креном
     * (плюс — правое крыло вниз). Звать раз в тик сервера: между вызовами аппарат стоит на месте (скорость гасится,
     * за тик гравитация роняет его на 0.014 блока — следующий вызов поднимает обратно), клиенты видят плавный путь
     * из поз по тикам, а скорость аппарата Sable сам считает по разности поз.
     */
    public void fly(Vec3 pos, float yaw, float pitch, float roll) {
        if (!alive()) return;
        PhysicsPipeline pipeline = pipeline();
        pipeline.teleport(sub, new Vector3d(pos.x, pos.y, pos.z), orientation(yaw, pitch, roll));
        pipeline.resetVelocity(sub);
    }

    /** Центр масс аппарата в мире (что Sable считает сейчас). */
    public Vec3 position() {
        Vector3d p = sub.logicalPose().position();
        return new Vec3(p.x, p.y, p.z);
    }

    /** Куда смотрит нос (единичный вектор в мире). */
    public Vec3 nose() {
        Vector3d n = sub.logicalPose().orientation().transform(new Vector3d(0, 0, 1));
        return new Vec3(n.x, n.y, n.z);
    }

    /** Где сейчас правое крыло относительно центра (единичный вектор; нос на юг — правое крыло на запад). */
    public Vec3 rightWing() {
        Vector3d n = sub.logicalPose().orientation().transform(new Vector3d(-1, 0, 0));
        return new Vec3(n.x, n.y, n.z);
    }

    public ServerSubLevel subLevel() {
        return sub;
    }

    /** Сколько блоков было в собранном аппарате. */
    public int builtBlocks() {
        return blocks;
    }

    /** Аппарат ещё в мире: не удалён и не выгружен Sable (выгруженный он удаляет из контейнера). */
    public boolean alive() {
        ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        return !sub.isRemoved() && container != null && container.getAllSubLevels().contains(sub);
    }

    /** Убирает аппарат вместе с его блоками. */
    public void remove() {
        ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container != null && !sub.isRemoved()) container.removeSubLevel(sub, SubLevelRemovalReason.REMOVED);
    }

    private PhysicsPipeline pipeline() {
        ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) throw new IllegalStateException("в мире нет Sable");
        return container.physicsSystem().getPipeline();
    }

    /**
     * Поворот из осей постройки (нос +Z, верх +Y) в мир: курс вокруг вертикали (Minecraft: 0 — юг, 90 — запад),
     * затем тангаж вокруг поперечной оси (плюс — нос вниз), затем крен вокруг продольной (плюс — правое крыло вниз).
     * Так же строит поворот {@code /sable physics rotation}.
     */
    public static Quaterniond orientation(float yaw, float pitch, float roll) {
        return new Quaterniond()
                .rotateY(-Math.toRadians(yaw))
                .rotateX(Math.toRadians(pitch))
                .rotateZ(Math.toRadians(roll));
    }

    /**
     * Силуэт истребителя носом на +Z: фюзеляж 12 блоков, стреловидное треугольное крыло размахом 11, фонарь
     * кабины, стабилизаторы и киль, сопло. {@code origin} — хвост фюзеляжа по оси.
     */
    static Map<BlockPos, BlockState> shape(BlockPos origin) {
        Map<BlockPos, BlockState> m = new LinkedHashMap<>();
        BlockState hull = Blocks.LIGHT_GRAY_CONCRETE.defaultBlockState();
        BlockState wing = Blocks.GRAY_CONCRETE.defaultBlockState();
        BlockState dark = Blocks.BLACK_CONCRETE.defaultBlockState();
        BlockState canopy = Blocks.TINTED_GLASS.defaultBlockState();
        // фюзеляж: сопло, корпус, нос
        m.put(origin, dark);
        for (int z = 1; z <= 10; z++) m.put(origin.offset(0, 0, z), hull);
        m.put(origin.offset(0, 0, 11), dark);
        // воздухозаборники по бокам корпуса
        for (int z = 5; z <= 7; z++) {
            m.put(origin.offset(1, 0, z), hull);
            m.put(origin.offset(-1, 0, z), hull);
        }
        // фонарь кабины
        m.put(origin.offset(0, 1, 7), canopy);
        m.put(origin.offset(0, 1, 8), canopy);
        // треугольное крыло: кромка уходит назад к законцовкам
        int[][] rows = {{2, 5}, {3, 4}, {4, 3}, {5, 2}};
        for (int[] r : rows) {
            for (int x = -r[1]; x <= r[1]; x++) {
                if (x != 0) m.putIfAbsent(origin.offset(x, 0, r[0]), wing);
            }
        }
        // стабилизаторы
        for (int x = -2; x <= 2; x++) if (x != 0) m.put(origin.offset(x, 0, 1), wing);
        // киль
        m.put(origin.offset(0, 1, 1), wing);
        m.put(origin.offset(0, 2, 1), wing);
        m.put(origin.offset(0, 1, 2), wing);
        m.put(origin.offset(0, 3, 0), wing);
        m.put(origin.offset(0, 2, 0), wing);
        m.put(origin.offset(0, 1, 0), wing);
        return m;
    }
}
