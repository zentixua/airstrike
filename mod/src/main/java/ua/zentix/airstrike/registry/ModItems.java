package ua.zentix.airstrike.registry;

import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Rarity;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;
import ua.zentix.airstrike.Airstrike;
import ua.zentix.airstrike.item.DesignatorItem;
import ua.zentix.airstrike.item.GeigerCounterItem;
import ua.zentix.airstrike.item.InterceptorItem;
import ua.zentix.airstrike.item.MunitionItem;

public final class ModItems {
    public static final DeferredRegister.Items REGISTER = DeferredRegister.createItems(Airstrike.MOD_ID);

    public static final DeferredItem<DesignatorItem> DESIGNATOR = REGISTER.registerItem("strike_designator",
            DesignatorItem::new, new Item.Properties().rarity(Rarity.EPIC));
    public static final DeferredItem<GeigerCounterItem> GEIGER_COUNTER = REGISTER.registerItem("geiger_counter",
            GeigerCounterItem::new, new Item.Properties().stacksTo(1));
    public static final DeferredItem<BlockItem> TRINITITE = REGISTER.registerSimpleBlockItem(ModBlocks.TRINITITE);
    public static final DeferredItem<BlockItem> SUBSTATION = REGISTER.registerSimpleBlockItem(ModBlocks.SUBSTATION);
    /** ЗРК ({@link ua.zentix.airstrike.defense.SamBlock}). */
    public static final DeferredItem<BlockItem> SAM = REGISTER.registerSimpleBlockItem(ModBlocks.SAM);
    /** Стационарная пусковая ({@link ua.zentix.airstrike.launcher.FixedLauncherBlock}). */
    public static final DeferredItem<BlockItem> FIXED_LAUNCHER = REGISTER.registerSimpleBlockItem(ModBlocks.FIXED_LAUNCHER);
    /** Зенитная ракета: запас ЗРК (кладётся рукой, воронкой, воронкой Create). */
    public static final DeferredItem<InterceptorItem> INTERCEPTOR = REGISTER.registerItem("interceptor",
            InterceptorItem::new, new Item.Properties().stacksTo(16));

    // Боеприпасы: пуск снимает их из инвентаря стреляющего (strike.Munitions); какой у оружия — его паспорт (WeaponSpec.munition)
    public static final DeferredItem<MunitionItem> SHAHED = REGISTER.registerItem("shahed",
            MunitionItem::new, new Item.Properties().stacksTo(16));
    public static final DeferredItem<MunitionItem> LANCET = REGISTER.registerItem("lancet",
            MunitionItem::new, new Item.Properties().stacksTo(16));
    public static final DeferredItem<MunitionItem> CRUISE_MISSILE = REGISTER.registerItem("cruise_missile",
            MunitionItem::new, new Item.Properties().stacksTo(16).rarity(Rarity.UNCOMMON));
    public static final DeferredItem<MunitionItem> GRAD_ROCKETS = REGISTER.registerItem("grad_rockets",
            MunitionItem::new, new Item.Properties().stacksTo(16));
    public static final DeferredItem<MunitionItem> BUNKER_BUSTER = REGISTER.registerItem("bunker_buster",
            MunitionItem::new, new Item.Properties().stacksTo(16).rarity(Rarity.RARE));
    public static final DeferredItem<MunitionItem> ICBM = REGISTER.registerItem("icbm",
            MunitionItem::new, new Item.Properties().stacksTo(1).rarity(Rarity.EPIC));
    /** Ядерная боевая часть на крылатую ракету или бомбу B-2 (у МБР она в самом боеприпасе). */
    public static final DeferredItem<MunitionItem> NUCLEAR_WARHEAD = REGISTER.registerItem("nuclear_warhead",
            MunitionItem::new, new Item.Properties().stacksTo(1).rarity(Rarity.EPIC));

    private ModItems() {}
}
