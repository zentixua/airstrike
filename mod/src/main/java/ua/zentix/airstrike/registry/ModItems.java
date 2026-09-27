package ua.zentix.airstrike.registry;

import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Rarity;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;
import ua.zentix.airstrike.Airstrike;
import ua.zentix.airstrike.item.DesignatorItem;
import ua.zentix.airstrike.item.GeigerCounterItem;

public final class ModItems {
    public static final DeferredRegister.Items REGISTER = DeferredRegister.createItems(Airstrike.MOD_ID);

    public static final DeferredItem<DesignatorItem> DESIGNATOR = REGISTER.registerItem("strike_designator",
            DesignatorItem::new, new Item.Properties().rarity(Rarity.EPIC));
    public static final DeferredItem<GeigerCounterItem> GEIGER_COUNTER = REGISTER.registerItem("geiger_counter",
            GeigerCounterItem::new, new Item.Properties().stacksTo(1));
    public static final DeferredItem<BlockItem> TRINITITE = REGISTER.registerSimpleBlockItem(ModBlocks.TRINITITE);

    private ModItems() {}
}
