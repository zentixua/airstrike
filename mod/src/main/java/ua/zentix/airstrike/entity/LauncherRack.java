package ua.zentix.airstrike.entity;

import ua.zentix.airstrike.strike.WeaponType;

/**
 * Пакет мобильной пусковой ({@link LauncherEntity}) — часть паспорта оружия ({@code WeaponSpec#rack}): угол
 * возвышения, число ячеек, темп пусков, сколько снаряд ждёт и занимает ячейку, где ячейки на пакете.
 *
 * @param elevation угол возвышения направляющей, °
 * @param slots     ячеек в пакете
 * @param spacing   наименьший интервал между пусками с одной установки, тиков
 * @param minReady  через сколько тиков после приказа поджиг, не раньше
 * @param busyTicks сколько тиков снаряд занимает ячейку после готовности (поджиг и сход; труба РСЗО — перезарядка)
 */
public enum LauncherRack {
    /** Шахеды одна над другой (с него же катапультой стартуют барражирующие — у тех свой пакет {@link #LOITER}). */
    DRONE(15f, 5, 16, 12, 24),
    /** Два наклонных контейнера крылатых ракет. */
    MISSILE(40f, 2, 16, 12, 24),
    /** Пакет РСЗО: 4 ряда по 10 труб, очередь по полсекунды; труба после пуска перезаряжается минуту. */
    ROCKET(50f, LauncherEntity.ROCKET_COLUMNS * LauncherEntity.ROCKET_ROWS, 8, 10, 1200),
    /** Катапульта барражирующих: три направляющие внизу, две сверху. */
    LOITER(20f, 5, 16, 12, 24);

    private final float elevation;
    private final int slots;
    private final int spacing;
    private final int minReady;
    private final int busyTicks;

    LauncherRack(float elevation, int slots, int spacing, int minReady, int busyTicks) {
        this.elevation = elevation;
        this.slots = slots;
        this.spacing = spacing;
        this.minReady = minReady;
        this.busyTicks = busyTicks;
    }

    /**
     * Пакет оружия {@code weapon} (паспорт, {@code WeaponSpec#rack}); у оружия без пакета (B-2, МБР — такой пусковой не
     * бывает, разве что из чужого сохранения) — пакет шахедов.
     */
    public static LauncherRack of(WeaponType weapon) {
        LauncherRack rack = weapon.spec().rack();
        return rack != null ? rack : DRONE;
    }

    public float elevation() {
        return elevation;
    }

    public int slots() {
        return slots;
    }

    public int spacing() {
        return spacing;
    }

    public int minReady() {
        return minReady;
    }

    public int busyTicks() {
        return busyTicks;
    }

    /**
     * Ячейка на пакете в его осях от оси качания (её место — у опоры, {@link LauncherMount}):
     * {влево, вверх, вперёд}, блоков — центр снаряда на направляющей.
     */
    double[] slotOffset(int slot) {
        return switch (this) {
            case MISSILE -> new double[]{slot == 0 ? 0.62 : -0.62, 0.64, 3.3};
            case ROCKET -> {
                // очередь идёт по рядам слева направо, начиная с верхнего — как на «Граде»
                int col = slot % LauncherEntity.ROCKET_COLUMNS, row = LauncherEntity.ROCKET_ROWS - 1 - slot / LauncherEntity.ROCKET_COLUMNS;
                yield new double[]{(LauncherEntity.ROCKET_COLUMNS - 1) * LauncherEntity.TUBE_PITCH / 2 - col * LauncherEntity.TUBE_PITCH,
                        0.3 + row * LauncherEntity.TUBE_PITCH, LauncherEntity.TUBE_LENGTH / 2};
            }
            case LOITER -> new double[]{LauncherEntity.loiterLeft(slot), LauncherEntity.loiterUp(slot), 1.35};
            // шахеды одна над другой: центр ячейки, 2 м от оси (хвост с ускорителем — у задней стенки)
            case DRONE -> new double[]{0, 0.45 + 0.8 * slot, 2.0};
        };
    }
}
