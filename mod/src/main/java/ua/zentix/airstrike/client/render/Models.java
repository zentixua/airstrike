package ua.zentix.airstrike.client.render;

import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Модели снарядов — один в один детали датапака (drone/build, missile/build, bunker/build_bomber, bunker/build_bomb):
 * размеры в блоках, нос по +Z. Шахед (×1.35): фюзеляж, треугольное крыло, кили на концах, толкающий винт;
 * ракета (×1.25): корпус, крылья, киль, крестообразное оперение и светящееся сопло; B-2 — «летающее крыло»;
 * бомба — корпус защитного цвета с жёлтой полосой и решётчатыми стабилизаторами.
 */
public final class Models {
    private static final Vector3f Y = new Vector3f(0, 1, 0);
    private static final Vector3f Z = new Vector3f(0, 0, 1);
    private static final Quaternionf QUARTER_Z = new Quaternionf(0.0f, 0.0f, 0.7071f, 0.7071f);

    private Models() {}

    public static final PartModel DRONE = PartModel.builder()
            .block(Blocks.GRAY_CONCRETE, -0.3375f, -0.27f, -3.105f, 0.675f, 0.594f, 5.805f)
            .block(Blocks.GRAY_CONCRETE, -0.2565f, -0.351f, -2.97f, 0.513f, 0.756f, 5.535f)
            .block(Blocks.GRAY_CONCRETE, -0.27f, -0.216f, 2.7f, 0.54f, 0.486f, 0.405f)
            .block(Blocks.BLACK_CONCRETE, -0.1755f, -0.135f, 3.105f, 0.351f, 0.324f, 0.3375f)
            .block(Blocks.BLACK_CONCRETE, -0.0945f, -0.0675f, 3.4425f, 0.189f, 0.189f, 0.2025f)
            .block(Blocks.GRAY_CONCRETE, -0.2025f, -0.162f, -3.4425f, 0.405f, 0.405f, 0.3375f)
            .block(Blocks.BLACK_CONCRETE, -0.108f, -0.0675f, -3.672f, 0.216f, 0.216f, 0.2295f)
            .block(Blocks.LIGHT_GRAY_CONCRETE, -2.5312f, -0.189f, -3.105f, 5.0625f, 0.108f, 1.08f)
            .block(Blocks.LIGHT_GRAY_CONCRETE, -2.4093f, -0.189f, -2.025f, 4.8187f, 0.108f, 0.42f)
            .block(Blocks.LIGHT_GRAY_CONCRETE, -2.1657f, -0.189f, -1.605f, 4.3312f, 0.108f, 0.42f)
            .block(Blocks.LIGHT_GRAY_CONCRETE, -1.9219f, -0.189f, -1.185f, 3.8437f, 0.108f, 0.42f)
            .block(Blocks.LIGHT_GRAY_CONCRETE, -1.6782f, -0.189f, -0.765f, 3.3562f, 0.108f, 0.42f)
            .block(Blocks.LIGHT_GRAY_CONCRETE, -1.4344f, -0.189f, -0.3451f, 2.8688f, 0.108f, 0.42f)
            .block(Blocks.LIGHT_GRAY_CONCRETE, -1.1906f, -0.189f, 0.0751f, 2.3813f, 0.108f, 0.42f)
            .block(Blocks.LIGHT_GRAY_CONCRETE, -0.9469f, -0.189f, 0.495f, 1.8938f, 0.108f, 0.42f)
            .block(Blocks.LIGHT_GRAY_CONCRETE, -0.7031f, -0.189f, 0.915f, 1.4063f, 0.108f, 0.42f)
            .block(Blocks.LIGHT_GRAY_CONCRETE, -0.4594f, -0.189f, 1.335f, 0.9188f, 0.108f, 0.42f)
            .block(Blocks.GRAY_CONCRETE, 2.5245f, -0.567f, -3.105f, 0.081f, 1.35f, 1.0125f)
            .block(Blocks.GRAY_CONCRETE, -2.6055f, -0.567f, -3.105f, 0.081f, 1.35f, 1.0125f)
            .propeller().item(Blocks.GRAY_STAINED_GLASS, 0.0f, Z, 0f, 0.0405f, -3.591f, 1.6875f, 0.135f, 0.0405f)
            .propeller().item(Blocks.GRAY_STAINED_GLASS, 1.0472f, Z, 0f, 0.0405f, -3.591f, 1.6875f, 0.135f, 0.0405f)
            .propeller().item(Blocks.GRAY_STAINED_GLASS, 2.0944f, Z, 0f, 0.0405f, -3.591f, 1.6875f, 0.135f, 0.0405f)
            .build();

    public static final PartModel MISSILE = PartModel.builder()
            .block(Blocks.LIGHT_GRAY_CONCRETE, -0.4688f, -0.4688f, -5.375f, 0.9375f, 0.9375f, 9.5f)
            .block(Blocks.LIGHT_GRAY_CONCRETE, -0.5625f, -0.325f, -5.25f, 1.125f, 0.65f, 9.25f)
            .block(Blocks.LIGHT_GRAY_CONCRETE, -0.325f, -0.5625f, -5.25f, 0.65f, 1.125f, 9.25f)
            .block(Blocks.LIGHT_GRAY_CONCRETE, -0.3875f, -0.3875f, 4.125f, 0.775f, 0.775f, 0.75f)
            .block(Blocks.GRAY_CONCRETE, -0.275f, -0.275f, 4.875f, 0.55f, 0.55f, 0.5625f)
            .block(Blocks.BLACK_CONCRETE, -0.15f, -0.15f, 5.4375f, 0.3f, 0.3f, 0.4375f)
            .block(Blocks.BLACK_CONCRETE, -0.475f, -0.475f, 3.125f, 0.95f, 0.95f, 0.25f)
            .block(Blocks.GRAY_CONCRETE, -0.3375f, -0.3375f, -5.8125f, 0.675f, 0.675f, 0.4375f)
            .glowing().block(Blocks.ORANGE_CONCRETE, -0.2f, -0.2f, -5.9625f, 0.4f, 0.4f, 0.15f)
            .block(Blocks.GRAY_CONCRETE, -2.5f, -0.0375f, 0.375f, 5f, 0.075f, 0.9375f)
            .block(Blocks.GRAY_CONCRETE, -0.275f, -0.8f, -2.625f, 0.55f, 0.3375f, 1.625f)
            .item(Blocks.GRAY_CONCRETE, 0.7854f, Z, 0f, 0f, -5f, 2.625f, 0.075f, 0.75f)
            .item(Blocks.GRAY_CONCRETE, -0.7854f, Z, 0f, 0f, -5f, 2.625f, 0.075f, 0.75f)
            .build();

    public static final PartModel BOMBER = PartModel.builder()
            .item(Blocks.BLACK_CONCRETE, 0.0f, Y, 0f, 0f, 0f, 8f, 1.6f, 17f)
            .item(Blocks.GRAY_CONCRETE, 0.0f, Y, 0f, 1.1f, 4f, 3.5f, 1.2f, 5f)
            .item(Blocks.BLACK_CONCRETE, -0.576f, Y, -9.5f, 0f, -1.8f, 12f, 1f, 11f)
            .item(Blocks.BLACK_CONCRETE, 0.576f, Y, 9.5f, 0f, -1.8f, 12f, 1f, 11f)
            .item(Blocks.BLACK_CONCRETE, -0.576f, Y, -20f, 0f, -8.5f, 13f, 0.6f, 5f)
            .item(Blocks.BLACK_CONCRETE, 0.576f, Y, 20f, 0f, -8.5f, 13f, 0.6f, 5f)
            .item(Blocks.BLACK_CONCRETE, 0.6981f, Y, -6.5f, 0f, -8.8f, 7f, 0.8f, 4f)
            .item(Blocks.BLACK_CONCRETE, -0.6981f, Y, 6.5f, 0f, -8.8f, 7f, 0.8f, 4f)
            .item(Blocks.GRAY_CONCRETE, 0.0f, Y, -3f, 0.9f, 1f, 2f, 0.8f, 5f)
            .item(Blocks.GRAY_CONCRETE, 0.0f, Y, 3f, 0.9f, 1f, 2f, 0.8f, 5f)
            .build();

    private static BlockState bars() {
        return Blocks.IRON_BARS.defaultBlockState().setValue(BlockStateProperties.EAST, true).setValue(BlockStateProperties.WEST, true);
    }

    /**
     * МБР (как Minuteman III): три ступени Ø 1.8 → 1.4, чёрные пояса стыков, конический обтекатель
     * боевой части, сопло с раскалённым срезом. Длина 18 блоков, нос по +Z.
     */
    public static final PartModel ICBM = PartModel.builder()
            .block(Blocks.WHITE_CONCRETE, -0.9f, -0.9f, -9f, 1.8f, 1.8f, 7.5f)
            .block(Blocks.BLACK_CONCRETE, -0.92f, -0.92f, -1.5f, 1.84f, 1.84f, 0.25f)
            .block(Blocks.WHITE_CONCRETE, -0.8f, -0.8f, -1.25f, 1.6f, 1.6f, 4.75f)
            .block(Blocks.BLACK_CONCRETE, -0.82f, -0.82f, 3.5f, 1.64f, 1.64f, 0.2f)
            .block(Blocks.LIGHT_GRAY_CONCRETE, -0.7f, -0.7f, 3.7f, 1.4f, 1.4f, 2.3f)
            .block(Blocks.LIGHT_GRAY_CONCRETE, -0.55f, -0.55f, 6f, 1.1f, 1.1f, 1.2f)
            .block(Blocks.GRAY_CONCRETE, -0.375f, -0.375f, 7.2f, 0.75f, 0.75f, 1f)
            .block(Blocks.GRAY_CONCRETE, -0.2f, -0.2f, 8.2f, 0.4f, 0.4f, 0.7f)
            .block(Blocks.BLACK_CONCRETE, -0.075f, -0.075f, 8.9f, 0.15f, 0.15f, 0.3f)
            .block(Blocks.BLACK_CONCRETE, -0.95f, -0.95f, -8.2f, 1.9f, 1.9f, 0.15f)
            .block(Blocks.GRAY_CONCRETE, -0.6f, -0.6f, -9.6f, 1.2f, 1.2f, 0.6f)
            .glowing().block(Blocks.ORANGE_CONCRETE, -0.5f, -0.5f, -9.8f, 1f, 1f, 0.2f)
            .build();

    public static final PartModel BOMB = PartModel.builder()
            .block(Blocks.GREEN_TERRACOTTA, -0.6f, -0.6f, -3.6f, 1.2f, 1.2f, 6.5f)
            .block(Blocks.GREEN_TERRACOTTA, -0.72f, -0.42f, -3.5f, 1.44f, 0.84f, 6.3f)
            .block(Blocks.GREEN_TERRACOTTA, -0.42f, -0.72f, -3.5f, 0.84f, 1.44f, 6.3f)
            .block(Blocks.GRAY_CONCRETE, -0.5f, -0.5f, 2.9f, 1f, 1f, 0.7f)
            .block(Blocks.GRAY_CONCRETE, -0.36f, -0.36f, 3.6f, 0.72f, 0.72f, 0.6f)
            .block(Blocks.BLACK_CONCRETE, -0.2f, -0.2f, 4.2f, 0.4f, 0.4f, 0.45f)
            .block(Blocks.YELLOW_CONCRETE, -0.74f, -0.74f, 2.35f, 1.48f, 1.48f, 0.3f)
            .block(Blocks.GRAY_CONCRETE, -0.45f, -0.45f, -4.2f, 0.9f, 0.9f, 0.6f)
            .block(Blocks.GRAY_CONCRETE, -0.3f, -0.3f, -4.65f, 0.6f, 0.6f, 0.45f)
            .block(Blocks.GREEN_TERRACOTTA, -0.03f, 0.6f, -1f, 0.06f, 0.35f, 1.6f)
            .block(Blocks.GREEN_TERRACOTTA, -0.03f, -0.95f, -1f, 0.06f, 0.35f, 1.6f)
            .block(Blocks.GREEN_TERRACOTTA, 0.6f, -0.03f, -1f, 0.35f, 0.06f, 1.6f)
            .block(Blocks.GREEN_TERRACOTTA, -0.95f, -0.03f, -1f, 0.35f, 0.06f, 1.6f)
            .block(bars(), -0.55f, 0.72f, -4.45f, 1.1f, 1.13f, 0.5f, new Quaternionf())
            .block(bars(), 0.55f, 0.72f, -4.45f, 1.13f, 1.1f, 0.5f, QUARTER_Z)
            .block(bars(), -0.55f, -1.85f, -4.45f, 1.1f, 1.13f, 0.5f, new Quaternionf())
            .block(bars(), 0.55f, -1.85f, -4.45f, 1.13f, 1.1f, 0.5f, QUARTER_Z)
            .block(bars(), 0.72f, -0.55f, -4.45f, 1.13f, 1.1f, 0.5f, new Quaternionf())
            .block(bars(), 1.85f, -0.55f, -4.45f, 1.1f, 1.13f, 0.5f, QUARTER_Z)
            .block(bars(), -1.85f, -0.55f, -4.45f, 1.13f, 1.1f, 0.5f, new Quaternionf())
            .block(bars(), -0.72f, -0.55f, -4.45f, 1.1f, 1.13f, 0.5f, QUARTER_Z)
            .build();
}
