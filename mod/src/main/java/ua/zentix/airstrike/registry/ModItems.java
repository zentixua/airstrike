package ua.zentix.airstrike.registry;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.Rarity;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;
import ua.zentix.airstrike.Airstrike;
import ua.zentix.airstrike.item.DesignatorItem;

public final class ModItems {
    public static final DeferredRegister.Items REGISTER = DeferredRegister.createItems(Airstrike.MOD_ID);

    public static final DeferredItem<DesignatorItem> DESIGNATOR = REGISTER.registerItem("strike_designator",
            DesignatorItem::new, new Item.Properties().rarity(Rarity.EPIC));

    private ModItems() {}
}
