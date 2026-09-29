package ua.zentix.airstrike.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import ua.zentix.airstrike.AirstrikeConfig;
import ua.zentix.airstrike.grid.BlackoutWorld;
import ua.zentix.airstrike.grid.Blackouts;
import ua.zentix.airstrike.grid.Node;
import ua.zentix.airstrike.grid.Outage;
import ua.zentix.airstrike.grid.PowerGrid;
import ua.zentix.airstrike.nuclear.world.WorkClock;
import ua.zentix.airstrike.registry.ModAttachments;

import java.util.Locale;

/**
 * /airstrike grid — сеть и блэкаут (операторы):
 * <pre>
 *   /airstrike grid status                                отключения, узлы, очередь (все измерения)
 *   /airstrike grid node add [x y z] [радиус]             узел сети (подстанция на карте) — без блока
 *   /airstrike grid node remove &lt;номер&gt; | node list
 *   /airstrike grid node knockout &lt;номер&gt;              вывести узел из строя, как удар по нему
 *   /airstrike grid blackout [x y z] [радиус]             обесточить район (без узла)
 *   /airstrike grid restore                               вернуть свет везде (все измерения)
 *   /airstrike grid restore at x y z &lt;радиус&gt;            вернуть свет в районе
 * </pre>
 */
final class GridCommand {
    private static final DynamicCommandExceptionType NO_NODE = new DynamicCommandExceptionType(id -> Component.translatable("airstrike.grid.no_node", id));

    private GridCommand() {}

    static LiteralArgumentBuilder<CommandSourceStack> build() {
        return Commands.literal("grid").requires(s -> s.hasPermission(2))
                .then(Commands.literal("status").executes(GridCommand::status))
                .then(Commands.literal("node")
                        .then(Commands.literal("add")
                                .executes(ctx -> addNode(ctx, BlockPos.containing(ctx.getSource().getPosition()), radius()))
                                .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                        .executes(ctx -> addNode(ctx, BlockPosArgument.getBlockPos(ctx, "pos"), radius()))
                                        .then(Commands.argument("radius", IntegerArgumentType.integer(16, 16_384))
                                                .executes(ctx -> addNode(ctx, BlockPosArgument.getBlockPos(ctx, "pos"), IntegerArgumentType.getInteger(ctx, "radius"))))))
                        .then(Commands.literal("remove").then(Commands.argument("id", IntegerArgumentType.integer(1))
                                .executes(GridCommand::removeNode)))
                        .then(Commands.literal("knockout").then(Commands.argument("id", IntegerArgumentType.integer(1))
                                .executes(GridCommand::knockOut)))
                        .then(Commands.literal("list").executes(GridCommand::listNodes)))
                .then(Commands.literal("blackout")
                        .executes(ctx -> blackout(ctx, ctx.getSource().getPosition(), radius()))
                        .then(Commands.argument("pos", Vec3Argument.vec3())
                                .executes(ctx -> blackout(ctx, Vec3Argument.getVec3(ctx, "pos"), radius()))
                                .then(Commands.argument("radius", IntegerArgumentType.integer(16, 16_384))
                                        .executes(ctx -> blackout(ctx, Vec3Argument.getVec3(ctx, "pos"), IntegerArgumentType.getInteger(ctx, "radius"))))))
                .then(Commands.literal("restore")
                        .executes(ctx -> restore(ctx, null, 0))
                        .then(Commands.literal("at").then(Commands.argument("pos", Vec3Argument.vec3())
                                .then(Commands.argument("radius", IntegerArgumentType.integer(1, 1_000_000))
                                        .executes(ctx -> restore(ctx, Vec3Argument.getVec3(ctx, "pos"), IntegerArgumentType.getInteger(ctx, "radius")))))));
    }

    private static int radius() {
        return AirstrikeConfig.SERVER.gridNodeRadius.get();
    }

    /** Все измерения: ядерный удар в Незере гасит Незер, а оператор стоит в верхнем мире. */
    private static int status(CommandContext<CommandSourceStack> ctx) {
        int outages = 0, nodes = 0, queued = 0, lamps = 0;
        for (ServerLevel level : ctx.getSource().getServer().getAllLevels()) {
            PowerGrid grid = PowerGrid.get(level);
            outages += grid.outages().size();
            for (Node ignored : grid.nodes()) nodes++;
            // мир, где блэкаута не было, — без очередей (и без лишнего состояния)
            if (!level.hasData(ModAttachments.BLACKOUT_WORLD)) continue;
            int[] backlog = BlackoutWorld.get(level).backlog();
            queued += backlog[0];
            lamps += backlog[1];
        }
        int o = outages, n = nodes, q = queued, l = lamps;
        ctx.getSource().sendSuccess(() -> Component.translatable("airstrike.grid.status", o, n, q, l), false);
        // почему очередь идёт с такой скоростью: единиц за тик при бюджете, оценка следующей и самая долгая
        WorkClock clock = Blackouts.clock(ctx.getSource().getServer());
        String estimate = String.format(Locale.ROOT, "%.2f", clock.estimateNanos() / 1e6), largest = String.format(Locale.ROOT, "%.2f", clock.largestRecentNanos() / 1e6);
        int units = clock.unitsLastTick(), budget = AirstrikeConfig.SERVER.gridTimeBudgetMs.get();
        ctx.getSource().sendSuccess(() -> Component.translatable("airstrike.grid.status_clock", units, budget, estimate, largest), false);
        for (ServerLevel level : ctx.getSource().getServer().getAllLevels()) {
            long now = level.getGameTime();
            String dimension = level.dimension().location().toString();
            for (Outage outage : PowerGrid.get(level).outages()) {
                long restore = outage.restoreAt();
                Component when = restore == Outage.NEVER ? Component.translatable("airstrike.grid.restore.never")
                        : restore <= now ? Component.translatable("airstrike.grid.restore.now")
                        : Component.translatable("airstrike.grid.restore.in", (restore - now + 1199) / 1200);
                ctx.getSource().sendSuccess(() -> Component.translatable("airstrike.grid.outage", outage.id(), dimension,
                        Math.round(outage.x()), Math.round(outage.z()), Math.round(outage.radius()), when), false);
            }
        }
        return o;
    }

    private static int addNode(CommandContext<CommandSourceStack> ctx, BlockPos pos, int requested) {
        int radius = Math.min(requested, AirstrikeConfig.SERVER.gridMaxRadius.get());
        Node n = PowerGrid.get(ctx.getSource().getLevel()).addNode(pos, radius, false);
        ctx.getSource().sendSuccess(() -> Component.translatable("airstrike.grid.node.added", n.id(), pos.getX(), pos.getY(), pos.getZ(), radius), true);
        return n.id();
    }

    private static int removeNode(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        int id = IntegerArgumentType.getInteger(ctx, "id");
        if (PowerGrid.get(ctx.getSource().getLevel()).removeNode(id) == null) throw NO_NODE.create(id);
        ctx.getSource().sendSuccess(() -> Component.translatable("airstrike.grid.node.removed", id), true);
        return 1;
    }

    private static int knockOut(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        int id = IntegerArgumentType.getInteger(ctx, "id");
        ServerLevel level = ctx.getSource().getLevel();
        Node n = PowerGrid.get(level).node(id);
        if (n == null) throw NO_NODE.create(id);
        Blackouts.knockOut(level, n);
        ctx.getSource().sendSuccess(() -> Component.translatable("airstrike.grid.node.knocked_out", id), true);
        return 1;
    }

    private static int listNodes(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getLevel();
        PowerGrid grid = PowerGrid.get(level);
        int count = 0;
        for (Node n : grid.nodes()) {
            count++;
            boolean down = grid.downOutage(n.id(), level.getGameTime()).isPresent();
            ctx.getSource().sendSuccess(() -> Component.translatable(down ? "airstrike.grid.node.down" : "airstrike.grid.node.up", n.id(),
                    n.pos().getX(), n.pos().getY(), n.pos().getZ(), Math.round(n.radius()),
                    Component.translatable(n.block() ? "airstrike.grid.node.block" : "airstrike.grid.node.marker")), false);
        }
        if (count == 0) ctx.getSource().sendSuccess(() -> Component.translatable("airstrike.grid.node.none"), false);
        return count;
    }

    private static int blackout(CommandContext<CommandSourceStack> ctx, Vec3 at, int radius) {
        Outage o = Blackouts.blackout(ctx.getSource().getLevel(), at, radius, AirstrikeConfig.SERVER.gridCascadeSpeed.get() / 20, -1);
        ctx.getSource().sendSuccess(() -> Component.translatable("airstrike.grid.blackout", o.id(), Math.round(at.x), Math.round(at.z), Math.round(o.radius())), true);
        return o.id();
    }

    /** Без места — во всех измерениях; с местом — в измерении того, кто вызвал. */
    private static int restore(CommandContext<CommandSourceStack> ctx, @Nullable Vec3 at, int radius) {
        int restored = 0;
        if (at == null) {
            for (ServerLevel level : ctx.getSource().getServer().getAllLevels()) restored += Blackouts.restore(level, null, 0);
        } else {
            restored = Blackouts.restore(ctx.getSource().getLevel(), at, radius);
        }
        int n = restored;
        ctx.getSource().sendSuccess(() -> Component.translatable("airstrike.grid.restored", n), true);
        return n;
    }
}
