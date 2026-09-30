package ua.zentix.airstrike.grid;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;

/**
 * Узел сети — подстанция, от которой питается район радиусом {@code radius}. Удар рядом выводит его из строя
 * ({@link Blackouts#onExplosion}): район гаснет каскадом от него.
 *
 * @param block узел — поставленный блок подстанции (иначе — отмечен командой на карте)
 */
public record Node(int id, BlockPos pos, double radius, boolean block) {
    public static final Codec<Node> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.INT.fieldOf("id").forGetter(Node::id),
            BlockPos.CODEC.fieldOf("pos").forGetter(Node::pos),
            Codec.DOUBLE.fieldOf("radius").forGetter(Node::radius),
            Codec.BOOL.fieldOf("block").forGetter(Node::block)
    ).apply(i, Node::new));
}
