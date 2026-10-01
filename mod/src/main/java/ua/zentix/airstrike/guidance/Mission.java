package ua.zentix.airstrike.guidance;

import net.minecraft.nbt.CompoundTag;

/**
 * Запас хода снаряда в блоках — вместо срока жизни в тиках: полёт кончается, когда пройден весь запас, а не когда
 * вышло время. Расход — путь, который снаряд на деле прошёл ({@link #spend}), поэтому стоянка на пусковой, ожидание
 * района цели и растянутый полёт РСЗО вне мира запаса почти не тратят сами собой, а скорость участка (медленный
 * набор, быстрое пике) на него не влияет: блоки есть блоки. У ожидания района свой предел
 * ({@code StrikeProjectile.AREA_WAIT_LIMIT}). Чистый код без мира Minecraft.
 * <p>
 * Запас = путь по плану × {@link #PATH_MARGIN} + резерв. Резерв задаётся в тиках на маршевой скорости
 * ({@link #RESERVE_TICKS}, {@link #LOST_RESERVE_TICKS}): на маршевой скорости полёт кончается в тот же тик, что и
 * по прежнему сроку жизни. Участок, который план считает в тиках и который быстрее маршевой (пике «Ланцета»),
 * переводится в блоки по своей скорости.
 */
public final class Mission {
    /** Запас на путь по плану: извилины маршрута, повторный заход, горка. */
    public static final double PATH_MARGIN = 1.5;
    /** Резерв сверх плана полёта, тиков на маршевой скорости. */
    public static final int RESERVE_TICKS = 600;
    /** Резерв после потери цели сверх пути до её последней точки, тиков на маршевой скорости ({@link #lose}). */
    public static final int LOST_RESERVE_TICKS = 200;

    /** Сколько блоков ещё можно пролететь. */
    private double range;
    /** Запас уже урезан до полёта в последнюю точку потерянной цели ({@link #lose}). */
    private boolean lostCapped;

    private Mission(double range, boolean lostCapped) {
        this.range = range;
        this.lostCapped = lostCapped;
    }

    /** Запас {@code range} блоков без плана (снаряд, которому путь не задан: паспортный запас). */
    public static Mission of(double range) {
        return new Mission(range, false);
    }

    /**
     * План полёта: путь {@code path} блоков с запасом {@link #PATH_MARGIN} и резерв {@link #RESERVE_TICKS} на маршевой
     * скорости {@code cruise}.
     */
    public static Mission plan(double path, double cruise) {
        return new Mission(path * PATH_MARGIN + RESERVE_TICKS * cruise, false);
    }

    /** Добавить к запасу участок, которого нет в пути по плану (круг барража и пике «Ланцета»), блоков. */
    public Mission extend(double blocks) {
        range += blocks;
        return this;
    }

    /** Сколько блоков ещё можно пролететь (≤ 0 — запас кончился). */
    public double range() {
        return range;
    }

    /** Запас кончился: снаряд так и не дошёл до цели. */
    public boolean exhausted() {
        return range <= 0;
    }

    /** Снаряд пролетел {@code moved} блоков. */
    public void spend(double moved) {
        range -= moved;
    }

    /**
     * Цель сдвинулась на {@code moved} блоков (ушла, телепортировалась): запас растёт на пролёт этого сдвига с тем же
     * запасом, что у плана. Сдвиг уже ограничен запасом на погоню ({@code TargetTracker.CHASE_BUDGET}): цель, которая
     * всё время уходит, не держит снаряд вечно.
     */
    public void chase(double moved) {
        if (moved > 0) range += moved * PATH_MARGIN;
    }

    /** Перенацелили: сдвиг точки — как у погони ({@link #chase}), и новая цель ещё не потеряна. */
    public void retarget(double moved) {
        chase(moved);
        lostCapped = false;
    }

    /**
     * Цель потеряна: запас — только на путь {@code path} до её последней точки (с тем же запасом, что у плана) и
     * {@link #LOST_RESERVE_TICKS} на маршевой скорости {@code cruise}, один раз. Запас, набранный погоней за целью, пока
     * она уходила, снаряду больше не нужен: с ним шахеды, чья цель умерла, кружили над точкой её смерти минутами.
     *
     * @return запас урезан сейчас (false — уже был урезан раньше)
     */
    public boolean lose(double path, double cruise) {
        if (lostCapped) return false;
        lostCapped = true;
        range = Math.min(range, path * PATH_MARGIN + LOST_RESERVE_TICKS * cruise);
        return true;
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putDouble("range", range);
        tag.putBoolean("lost_capped", lostCapped);
        return tag;
    }

    public static Mission load(CompoundTag tag) {
        return new Mission(tag.getDouble("range"), tag.getBoolean("lost_capped"));
    }

    /**
     * Снаряд, сохранённый со сроком жизни (2.3.0 и раньше): остаток срока {@code ticksLeft} и дробные тики погони
     * {@code credit} — полёт на маршевой скорости {@code cruise}, с которой его сохранили (ракета 2.3.0 летела 11.5
     * блока/тик: её остаток пути в блоках от новой скорости не зависит). Флаг «урезан после потери цели» переносится.
     */
    public static Mission fromLifetime(double ticksLeft, double credit, boolean lostCapped, double cruise) {
        return new Mission((ticksLeft + credit) * cruise, lostCapped);
    }
}
