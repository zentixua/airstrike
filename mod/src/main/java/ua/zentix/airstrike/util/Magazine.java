package ua.zentix.airstrike.util;

import net.minecraft.core.BlockPos;
import net.minecraft.world.Containers;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemHandlerHelper;
import net.neoforged.neoforge.items.ItemStackHandler;

import java.util.function.Predicate;

/**
 * Запас блока с боеприпасами (ЗРК, стационарная пусковая): ячейки под предметы, которые принимает {@code valid}.
 * Снаружи — {@link #loader} для {@link Capabilities.ItemHandler#BLOCK} с любой стороны (воронка, воронка, жёлоб и
 * механическая рука Create — через воронку): только загрузка, воронка под блоком запас не вытаскивает. Сломан блок —
 * запас выпадает ({@link #dropAll}); компаратор — по заполненности ({@link #comparator}).
 */
public class Magazine extends ItemStackHandler {
    private final Predicate<ItemStack> valid;
    private final Runnable changed;
    private final IItemHandler loader = new Loader();

    /**
     * @param valid   что можно положить
     * @param changed что делать при каждом изменении (обычно {@code BlockEntity::setChanged}: сохранить и обновить
     *                компаратор)
     */
    public Magazine(int slots, Predicate<ItemStack> valid, Runnable changed) {
        super(slots);
        this.valid = valid;
        this.changed = changed;
    }

    @Override
    public boolean isItemValid(int slot, ItemStack stack) {
        return valid.test(stack);
    }

    @Override
    protected void onContentsChanged(int slot) {
        changed.run();
    }

    /** Вид снаружи: только загрузка. */
    public IItemHandler loader() {
        return loader;
    }

    /** Положить стопку (сперва к таким же); возвращает то, что не влезло. */
    public ItemStack load(ItemStack stack) {
        return ItemHandlerHelper.insertItemStacked(this, stack, false);
    }

    /** Предметов в запасе. */
    public int count() {
        int n = 0;
        for (int i = 0; i < getSlots(); i++) n += getStackInSlot(i).getCount();
        return n;
    }

    /** Сигнал компаратора — по заполненности, как у сундука. */
    public int comparator() {
        return ItemHandlerHelper.calcRedstoneFromInventory(this);
    }

    /** Блок сломан или взорван: запас выпадает на его месте и уходит из ячеек. */
    public void dropAll(Level level, BlockPos pos) {
        for (int i = 0; i < getSlots(); i++) {
            ItemStack s = getStackInSlot(i);
            if (!s.isEmpty()) Containers.dropItemStack(level, pos.getX(), pos.getY(), pos.getZ(), s.copy());
            setStackInSlot(i, ItemStack.EMPTY);
        }
    }

    /** Запас снаружи: видно, что лежит, положить можно, вынуть — нет. */
    private final class Loader implements IItemHandler {
        @Override
        public int getSlots() {
            return Magazine.this.getSlots();
        }

        @Override
        public ItemStack getStackInSlot(int slot) {
            return Magazine.this.getStackInSlot(slot);
        }

        @Override
        public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
            return Magazine.this.insertItem(slot, stack, simulate);
        }

        @Override
        public ItemStack extractItem(int slot, int amount, boolean simulate) {
            return ItemStack.EMPTY;
        }

        @Override
        public int getSlotLimit(int slot) {
            return Magazine.this.getSlotLimit(slot);
        }

        @Override
        public boolean isItemValid(int slot, ItemStack stack) {
            return Magazine.this.isItemValid(slot, stack);
        }
    }
}
