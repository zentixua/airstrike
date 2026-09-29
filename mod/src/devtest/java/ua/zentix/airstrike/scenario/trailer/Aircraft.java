package ua.zentix.airstrike.scenario.trailer;

import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import dev.ryanhcode.sable.api.physics.PhysicsPipeline;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EndRodBlock;
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
    /** Угол постройки ({@code origin} в {@link #shape}) в координатах плота. */
    private final Vec3 originLocal;

    private Aircraft(ServerLevel level, ServerSubLevel sub, int blocks, Vec3 originLocal) {
        this.level = level;
        this.sub = sub;
        this.blocks = blocks;
        this.originLocal = originLocal;
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
        // точки постройки — в координатах плота (общих у сервера и клиента): клиент переводит их в мир своей позой
        // аппарата (факел форсажа, след с законцовок)
        Aircraft a = new Aircraft(level, sub, blocks.size(), sub.logicalPose().transformPositionInverse(Vec3.atLowerCornerOf(origin)));
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

    /**
     * Точка постройки (в осях {@link #shape}, от {@code origin}) в координатах плота — общих у сервера и клиента;
     * в мир её переводит поза аппарата ({@code pose.transformPosition}).
     */
    public Vec3 local(Vec3 inShape) {
        return originLocal.add(inShape);
    }

    /** Срезы сопел в координатах плота. */
    public List<Vec3> nozzles() {
        return NOZZLES.stream().map(this::local).toList();
    }

    /** Законцовки крыла (правая, левая) в координатах плота. */
    public List<Vec3> wingtips() {
        return List.of(local(new Vec3(-6.5, 0.5, 4.5)), local(new Vec3(7.5, 0.5, 4.5)));
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

    /** Центры срезов сопел в осях постройки ({@link #shape}): от них назад идёт факел форсажа. */
    static final List<Vec3> NOZZLES = List.of(new Vec3(1.5, 0.5, 0), new Vec3(-0.5, 0.5, 0));

    /**
     * Силуэт двухдвигательного истребителя носом на +Z (около 16 × 15 блоков, как F-22 в метрах): широкий
     * фюзеляж в светлых серых тонах, горб и фонарь, треугольное крыло из плит (тонкое), ракеты на законцовках,
     * стабилизаторы за крылом, два киля, два сопла. {@code origin} — хвост фюзеляжа по оси.
     */
    static Map<BlockPos, BlockState> shape(BlockPos origin) {
        Map<BlockPos, BlockState> m = new LinkedHashMap<>();
        // светлые серые: под шейдерами хоста самолёт в небе темнеет, и серый бетон читался чёрным
        BlockState hull = Blocks.POLISHED_DIORITE.defaultBlockState();
        BlockState panel = Blocks.SMOOTH_STONE_SLAB.defaultBlockState();
        BlockState dark = Blocks.LIGHT_GRAY_CONCRETE.defaultBlockState();
        BlockState nozzle = Blocks.POLISHED_BLACKSTONE.defaultBlockState();
        BlockState intake = Blocks.GRAY_CONCRETE.defaultBlockState();
        BlockState canopy = Blocks.TINTED_GLASS.defaultBlockState();
        BlockState missile = Blocks.WHITE_CONCRETE.defaultBlockState();
        // фюзеляж: ось с носом, бока с воздухозаборниками спереди, сопла сзади
        for (int z = 0; z <= 13; z++) m.put(origin.offset(0, 0, z), hull);
        for (int x : new int[]{-1, 1}) {
            m.put(origin.offset(x, 0, 0), nozzle);
            for (int z = 1; z <= 10; z++) m.put(origin.offset(x, 0, z), z < 4 ? dark : hull);
            m.put(origin.offset(x, 0, 11), intake);
        }
        m.put(origin.offset(0, 0, 14), panel);
        m.put(origin.offset(0, 0, 15), Blocks.END_ROD.defaultBlockState().setValue(EndRodBlock.FACING, Direction.SOUTH));
        // горб за кабиной и фонарь
        for (int z = 2; z <= 8; z++) m.put(origin.offset(0, 1, z), panel);
        for (int z = 9; z <= 11; z++) m.put(origin.offset(0, 1, z), canopy);
        m.put(origin.offset(0, 1, 12), panel);
        // треугольное крыло (плиты — тонкое): размах растёт к задней кромке
        for (int z = 3; z <= 8; z++) {
            int span = 10 - z;
            for (int x = 2; x <= span; x++) {
                m.put(origin.offset(x, 0, z), panel);
                m.put(origin.offset(-x, 0, z), panel);
            }
        }
        // ракеты на законцовках
        for (int x : new int[]{-7, 7}) for (int z = 4; z <= 5; z++) m.put(origin.offset(x, 0, z), missile);
        // стабилизаторы за крылом (щель на z = 2)
        for (int x = 2; x <= 4; x++) {
            m.put(origin.offset(x, 0, 0), panel);
            m.put(origin.offset(-x, 0, 0), panel);
        }
        for (int x = 2; x <= 3; x++) {
            m.put(origin.offset(x, 0, 1), panel);
            m.put(origin.offset(-x, 0, 1), panel);
        }
        // два киля над двигателями, со скосом передней кромки
        for (int x : new int[]{-1, 1}) {
            m.put(origin.offset(x, 1, 1), dark);
            m.put(origin.offset(x, 1, 2), dark);
            m.put(origin.offset(x, 2, 0), dark);
            m.put(origin.offset(x, 2, 1), dark);
            m.put(origin.offset(x, 3, 0), dark);
        }
        return m;
    }
}
