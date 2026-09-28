package ua.zentix.airstrike.client.render;

import net.minecraft.world.level.block.Blocks;

/**
 * Модели пусковой — из блоков ({@link PartModel}): размеры в блоках,
 * нос (направление пуска) по +Z, +X — левый борт. Прицеп и пакет рисуются отдельно: пакет качается вокруг
 * поперечной оси (см. {@link ua.zentix.airstrike.entity.LauncherEntity#PIVOT_UP}) и в своей системе растёт вдоль +Z.
 */
public final class LaunchModels {
    private LaunchModels() {}

    /** Прицеп: платформа, рама, три оси колёс, аутригеры и дышло. Начало — центр прицепа на земле. */
    public static final PartModel TRAILER = trailer();

    private static PartModel trailer() {
        PartModel.Builder b = PartModel.builder()
                .block(Blocks.GREEN_TERRACOTTA, -1.4f, 0.75f, -3.8f, 2.8f, 0.35f, 7.6f)
                .block(Blocks.GRAY_CONCRETE, -1.1f, 0.5f, -3.6f, 0.25f, 0.25f, 7.2f)
                .block(Blocks.GRAY_CONCRETE, 0.85f, 0.5f, -3.6f, 0.25f, 0.25f, 7.2f)
                .block(Blocks.GRAY_CONCRETE, -0.1f, 0.55f, 3.8f, 0.2f, 0.15f, 1.9f)
                .block(Blocks.BLACK_CONCRETE, -0.25f, 0.45f, 5.6f, 0.5f, 0.25f, 0.3f)
                // опора пакета у оси качания
                .block(Blocks.GRAY_CONCRETE, -1.2f, 1.1f, -2.6f, 0.3f, 0.55f, 0.8f)
                .block(Blocks.GRAY_CONCRETE, 0.9f, 1.1f, -2.6f, 0.3f, 0.55f, 0.8f)
                // гидроцилиндр подъёма
                .block(Blocks.IRON_BLOCK, -0.15f, 1.1f, -0.6f, 0.3f, 0.3f, 1.4f);
        for (float z : new float[]{-3.1f, -2.0f, 2.2f}) {
            b.block(Blocks.BLACK_CONCRETE, 1.4f, 0f, z, 0.35f, 0.9f, 0.9f);
            b.block(Blocks.BLACK_CONCRETE, -1.75f, 0f, z, 0.35f, 0.9f, 0.9f);
            b.block(Blocks.GRAY_CONCRETE, -1.4f, 0.35f, z + 0.35f, 2.8f, 0.2f, 0.2f);
        }
        for (float z : new float[]{-3.7f, 3.3f}) {
            b.block(Blocks.IRON_BLOCK, 1.45f, 0f, z, 0.25f, 0.8f, 0.25f);
            b.block(Blocks.IRON_BLOCK, -1.7f, 0f, z, 0.25f, 0.8f, 0.25f);
        }
        return b.build();
    }

    /** Пакет на пять шахедов: боковые стенки, дно, крыша, задняя стенка — открытая коробка. */
    public static final PartModel DRONE_RACK = PartModel.builder()
            .block(Blocks.GREEN_TERRACOTTA, -2.85f, -0.15f, -0.2f, 5.7f, 0.15f, 6.9f)
            .block(Blocks.GREEN_TERRACOTTA, -2.85f, 4.9f, -0.2f, 5.7f, 0.12f, 6.9f)
            .block(Blocks.GREEN_TERRACOTTA, 2.75f, -0.15f, -0.2f, 0.12f, 5.17f, 6.9f)
            .block(Blocks.GREEN_TERRACOTTA, -2.87f, -0.15f, -0.2f, 0.12f, 5.17f, 6.9f)
            .block(Blocks.GRAY_CONCRETE, -2.85f, -0.15f, -0.45f, 5.7f, 5.17f, 0.25f)
            // направляющие под фюзеляжем каждой ячейки
            .block(Blocks.IRON_BLOCK, -0.4f, -0.02f, 0f, 0.08f, 0.08f, 6.6f)
            .block(Blocks.IRON_BLOCK, 0.32f, -0.02f, 0f, 0.08f, 0.08f, 6.6f)
            .block(Blocks.IRON_BLOCK, -0.4f, 0.93f, 0f, 0.08f, 0.08f, 6.6f)
            .block(Blocks.IRON_BLOCK, 0.32f, 0.93f, 0f, 0.08f, 0.08f, 6.6f)
            .block(Blocks.IRON_BLOCK, -0.4f, 1.88f, 0f, 0.08f, 0.08f, 6.6f)
            .block(Blocks.IRON_BLOCK, 0.32f, 1.88f, 0f, 0.08f, 0.08f, 6.6f)
            .block(Blocks.IRON_BLOCK, -0.4f, 2.83f, 0f, 0.08f, 0.08f, 6.6f)
            .block(Blocks.IRON_BLOCK, 0.32f, 2.83f, 0f, 0.08f, 0.08f, 6.6f)
            .block(Blocks.IRON_BLOCK, -0.4f, 3.78f, 0f, 0.08f, 0.08f, 6.6f)
            .block(Blocks.IRON_BLOCK, 0.32f, 3.78f, 0f, 0.08f, 0.08f, 6.6f)
            .build();

    /** Два транспортно-пусковых контейнера для крылатых ракет: квадратные трубы с открытым торцом. */
    public static final PartModel MISSILE_RACK = missileRack();

    private static PartModel missileRack() {
        PartModel.Builder b = PartModel.builder();
        for (float cx : new float[]{0.85f, -0.85f}) {
            float x0 = cx - 0.78f, w = 1.56f, y0 = 0.07f, h = 1.56f, z0 = -0.5f, len = 11.8f, t = 0.1f;
            b.block(Blocks.GREEN_TERRACOTTA, x0, y0, z0, w, t, len);
            b.block(Blocks.GREEN_TERRACOTTA, x0, y0 + h - t, z0, w, t, len);
            b.block(Blocks.GREEN_TERRACOTTA, x0, y0, z0, t, h, len);
            b.block(Blocks.GREEN_TERRACOTTA, x0 + w - t, y0, z0, t, h, len);
            b.block(Blocks.GRAY_CONCRETE, x0, y0, z0 - 0.2f, w, h, 0.2f);
            // бандажи
            for (float z : new float[]{1.5f, 5.5f, 9.5f}) b.block(Blocks.BLACK_CONCRETE, x0 - 0.04f, y0 - 0.04f, z, w + 0.08f, h + 0.08f, 0.25f);
        }
        return b.build();
    }
}
