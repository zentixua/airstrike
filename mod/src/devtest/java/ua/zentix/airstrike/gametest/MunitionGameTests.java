package ua.zentix.airstrike.gametest;

import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.jetbrains.annotations.Nullable;
import ua.zentix.airstrike.Airstrike;
import ua.zentix.airstrike.AirstrikeConfig;
import ua.zentix.airstrike.entity.LauncherEntity;
import ua.zentix.airstrike.entity.StrikeProjectile;
import ua.zentix.airstrike.nuclear.NuclearEvents;
import ua.zentix.airstrike.registry.ModDataComponents;
import ua.zentix.airstrike.registry.ModItems;
import ua.zentix.airstrike.strike.Loadout;
import ua.zentix.airstrike.strike.Munitions;
import ua.zentix.airstrike.strike.SalvoData;
import ua.zentix.airstrike.strike.ServerActions;
import ua.zentix.airstrike.strike.StrikeWorld;
import ua.zentix.airstrike.strike.TargetMode;
import ua.zentix.airstrike.strike.VirtualFlights;
import ua.zentix.airstrike.strike.Waypoints;
import ua.zentix.airstrike.strike.WeaponType;
import ua.zentix.airstrike.target.Target;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Боеприпасы из инвентаря ({@link Munitions}): приказ оплачивается целиком при отдаче, не хватает — отказ без пуска,
 * творческий режим и команда оператора не платят, неудачный пуск и невыпущенное из отменённого залпа возвращаются,
 * рецепты боеприпасов загружены. Стреляющий — {@code FakePlayer} NeoForge, который запоминает строки себе: в список
 * игроков он не входит, поэтому возврат получает список «в сети» сам ({@link SalvoData#cancel}).
 */
@GameTestHolder(Airstrike.MOD_ID)
@PrefixGameTestTemplate(false)
public final class MunitionGameTests {
    private static final BlockPos RANGE_CENTER = new BlockPos(32, 11, 32);

    private MunitionGameTests() {}

    /**
     * Залп оплачивается сразу и целиком; на второй не хватает — отказ, строка «нужно 3, есть 2», ничего не снято и залпа
     * нет. Пакет «Града» начинается (12 из 40 — остаток 28 в том же предмете). Ядерная БЧ на носителе — отдельный
     * предмет: без неё ракета не снимается (всё или ничего). Отбой залпов возвращает невыпущенное: шахеды — предметами,
     * снаряды «Града» — в начатый пакет, и он снова целый.
     */
    @GameTest(template = "range", batch = "munitions", skyAccess = true)
    public static void orderPaysUpFrontOrRefuses(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        boolean opsOnly = AirstrikeConfig.SERVER.nukeOpsOnly.get();
        StrikeGameTests.afterTest(h, () -> AirstrikeConfig.SERVER.nukeOpsOnly.set(opsOnly));
        Listener p = player(h, "munitions_buyer");
        // упавший тест не должен оставить залп, который выстрелит по площадке после него
        StrikeGameTests.afterTest(h, () -> SalvoData.get(level).cancel(level, p.getUUID(), List.of()));
        ServerActions.Aim aim = aim(h);
        Item shahed = ModItems.SHAHED.get();
        give(p, shahed, 5);

        h.assertTrue(ServerActions.strike(p, WeaponType.DRONE, 3, 20, aim, Loadout.Nuke.DEFAULT, Waypoints.NONE, true), "залп из 3 шахедов не принят");
        h.assertValueEqual(held(p, shahed), 2, "шахедов после залпа из 3");
        h.assertValueEqual(SalvoData.get(level).remaining(p.getUUID()), 3, "невыпущенных в залпе");

        h.assertFalse(ServerActions.strike(p, WeaponType.DRONE, 3, 20, aim, Loadout.Nuke.DEFAULT, Waypoints.NONE, true), "залп без боеприпасов принят");
        h.assertValueEqual(held(p, shahed), 2, "отказ снял шахеды");
        h.assertValueEqual(SalvoData.get(level).remaining(p.getUUID()), 3, "отказ всё же начал залп");
        h.assertValueEqual(args(p.last("airstrike.munitions.short")), List.of(shahed.getDescription(), 3, 2), "строка отказа");

        Item grad = ModItems.GRAD_ROCKETS.get();
        give(p, grad, 1);
        h.assertTrue(ServerActions.strike(p, WeaponType.ROCKET, 12, 15, aim, Loadout.Nuke.DEFAULT, Waypoints.NONE, true), "залп «Града» не принят");
        h.assertValueEqual(held(p, grad), 28, "снарядов в пакете после залпа из 12");
        h.assertValueEqual(count(p, grad), 1, "начатый пакет — не тот же предмет");

        // ядерная БЧ на ракете: без неё ракета не снимается
        AirstrikeConfig.SERVER.nukeOpsOnly.set(false);
        Item missile = ModItems.CRUISE_MISSILE.get(), warhead = Munitions.warhead();
        give(p, missile, 1);
        Loadout.Nuke onCarrier = new Loadout.Nuke(15, true, true);
        h.assertFalse(ServerActions.strike(p, WeaponType.MISSILE, 1, 0, aim, onCarrier, Waypoints.NONE, true), "ядерная ракета без БЧ принята");
        h.assertValueEqual(held(p, missile), 1, "без БЧ ракета снята");
        h.assertValueEqual(args(p.last("airstrike.munitions.short")), List.of(warhead.getDescription(), 1, 0), "строка отказа без БЧ");
        // с БЧ счёт — ракета и БЧ (сам пуск ядерной ракеты в тестовом мире не нужен)
        give(p, warhead, 1);
        Loadout nuclear = new Loadout(WeaponType.MISSILE, 1, 0, TargetMode.LOOK, "", onCarrier);
        h.assertTrue(Munitions.pay(p, Munitions.Bill.of(nuclear)), "ракета с БЧ не оплачена");
        h.assertTrue(held(p, missile) == 0 && held(p, warhead) == 0, "оплата ядерной ракеты: ракет " + held(p, missile) + ", БЧ " + held(p, warhead));

        // отбой залпов: невыпущенное — владельцу
        h.assertValueEqual(SalvoData.get(level).cancel(level, p.getUUID(), List.of(p)), 2, "отменено залпов");
        h.assertValueEqual(held(p, shahed), 5, "шахедов после отбоя");
        h.assertValueEqual(held(p, grad), 40, "снарядов «Града» после отбоя");
        ItemStack pack = find(p, grad);
        h.assertTrue(pack.getCount() == 1 && !pack.has(ModDataComponents.ROUNDS.get()), "пакет после отбоя не целый: " + pack + " " + pack.getComponents());
        h.assertTrue(p.last("airstrike.munitions.refunded") != null && p.last("airstrike.munitions.refunded.rounds") != null, "нет строки о возврате");
        h.succeed();
    }

    /**
     * Бесплатно: творческий режим (и с пульта) и команда оператора ({@code rules = false}); в выживании с пульта без
     * боеприпасов — отказ. Неоплаченный залп при отбое ничего не возвращает.
     */
    @GameTest(template = "range", batch = "munitions", skyAccess = true)
    public static void creativeAndOperatorCommandAreFree(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        ServerActions.Aim aim = aim(h);
        Listener creative = player(h, "munitions_creative");
        creative.setGameMode(GameType.CREATIVE);
        h.assertTrue(creative.hasInfiniteMaterials(), "творческий режим без бесконечных материалов");
        h.assertTrue(ServerActions.strike(creative, WeaponType.DRONE, 2, 10, aim, Loadout.Nuke.DEFAULT, Waypoints.NONE, true), "в творческом залп не принят");
        Listener op = player(h, "munitions_op");
        StrikeGameTests.afterTest(h, () -> {
            SalvoData.get(level).cancel(level, creative.getUUID(), List.of());
            SalvoData.get(level).cancel(level, op.getUUID(), List.of());
        });
        h.assertTrue(ServerActions.strike(op, WeaponType.DRONE, 2, 10, aim, Loadout.Nuke.DEFAULT, Waypoints.NONE, false), "команда оператора не принята");
        Listener survival = player(h, "munitions_survival");
        h.assertFalse(ServerActions.strike(survival, WeaponType.DRONE, 2, 10, aim, Loadout.Nuke.DEFAULT, Waypoints.NONE, true), "без боеприпасов залп принят");

        SalvoData salvos = SalvoData.get(level);
        h.assertTrue(salvos.cancel(level, creative.getUUID(), List.of(creative)) == 1 && salvos.cancel(level, op.getUUID(), List.of(op)) == 1, "залпов нет");
        h.assertTrue(creative.getInventory().isEmpty() && op.getInventory().isEmpty(), "неоплаченный залп вернул боеприпасы");
        h.assertTrue(creative.last("airstrike.munitions.refunded") == null && op.last("airstrike.munitions.refunded") == null, "строка о возврате без оплаты");
        h.succeed();
    }

    /**
     * Залп, отменённый на середине: выпущенные не возвращаются, невыпущенные — владельцу. Шахеды заходят издалека
     * (стреляющего нет в списке игроков — пусковой у него нет), и их убирают, не дав долететь.
     */
    @GameTest(template = "range", timeoutTicks = 300, batch = "munitions_salvo", skyAccess = true)
    public static void cancelledSalvoRefundsUnlaunched(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        Listener p = player(h, "munitions_salvo");
        UUID id = p.getUUID();
        StrikeGameTests.afterTest(h, () -> {
            SalvoData.get(level).cancel(level, id, List.of());
            removeFlights(level, id);
        });
        Item shahed = ModItems.SHAHED.get();
        give(p, shahed, 4);
        h.assertTrue(ServerActions.strike(p, WeaponType.DRONE, 4, 20, aim(h), Loadout.Nuke.DEFAULT, Waypoints.NONE, true), "залп не принят");
        h.assertValueEqual(held(p, shahed), 0, "залп из 4 не снял все 4");
        boolean[] done = {false};
        h.onEachTick(() -> {
            if (done[0] || SalvoData.get(level).remaining(id) > 2) return;
            done[0] = true;
            // вне мира и в мире: шахед, вошедший в чужие загруженные чанки, тоже выпущен
            int launched = (int) StrikeWorld.projectiles(level).stream().filter(f -> id.equals(f.ownerId())).count();
            int cancelled = SalvoData.get(level).cancel(level, id, List.of(p));
            removeFlights(level, id);
            if (launched != 2 || cancelled != 1) throw new GameTestAssertException("выпущено " + launched + ", отменено залпов " + cancelled);
            if (held(p, shahed) != 2) throw new GameTestAssertException("после отбоя шахедов " + held(p, shahed) + " вместо 2 невыпущенных");
            if (!List.of(shahed.getDescription(), 2).equals(args(p.last("airstrike.munitions.refunded")))) {
                throw new GameTestAssertException("строка о возврате: " + p.last("airstrike.munitions.refunded"));
            }
            h.succeed();
        });
    }

    /**
     * Владелец погиб и ещё не возродился (экран смерти: в списке игроков, но инвентарь без {@code keepInventory} при
     * возрождении не переносится) — отбой залпа кладёт невыпущенное не в инвентарь, а на место гибели.
     */
    @GameTest(template = "range", batch = "munitions", skyAccess = true)
    public static void deadOwnerRefundDropsAtDeathSpot(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        Listener p = player(h, "munitions_dead");
        StrikeGameTests.afterTest(h, () -> SalvoData.get(level).cancel(level, p.getUUID(), List.of()));
        Item shahed = ModItems.SHAHED.get();
        give(p, shahed, 3);
        h.assertTrue(ServerActions.strike(p, WeaponType.DRONE, 3, 20, aim(h), Loadout.Nuke.DEFAULT, Waypoints.NONE, true), "залп не принят");
        h.assertTrue(!level.getGameRules().getBoolean(GameRules.RULE_KEEPINVENTORY), "в мире GameTest keepInventory");
        p.setHealth(0);
        h.assertFalse(p.isAlive(), "владелец жив");
        h.assertValueEqual(SalvoData.get(level).cancel(level, p.getUUID(), List.of(p)), 1, "отменено залпов");
        h.assertValueEqual(count(p, shahed), 0, "погибшему боеприпасы положены в инвентарь");
        List<ItemEntity> dropped = level.getEntitiesOfClass(ItemEntity.class,
                p.getBoundingBox().inflate(4), e -> e.getItem().is(shahed));
        int n = dropped.stream().mapToInt(e -> e.getItem().getCount()).sum();
        dropped.forEach(Entity::discard);
        h.assertValueEqual(n, 3, "шахедов на месте гибели");
        h.assertTrue(p.last("airstrike.munitions.refunded.dead") != null, "нет строки о месте гибели");
        h.succeed();
    }

    /**
     * Неудачный пуск возвращает оплаченное: снаряд у пусковой стреляющего не вошёл в мир (событие входа отменено —
     * так делают моды защиты территорий) — шахед и МБР снова в инвентаре, ядерного удара нет. Пуск, упавший с ошибкой
     * (здесь — обработчик того же события), тоже неудачный: шахед возвращается.
     */
    @GameTest(template = "runway", timeoutTicks = 20, batch = "munitions_failed", skyAccess = true)
    public static void failedLaunchRefunds(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        boolean opsOnly = AirstrikeConfig.SERVER.nukeOpsOnly.get();
        Listener p = player(h, "munitions_failed");
        p.moveTo(Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(16, 4, 60))), 0, 0);
        int[] vetoed = {0};
        boolean[] crash = {false};
        Consumer<EntityJoinLevelEvent> veto = e -> {
            if (e.getEntity() instanceof StrikeProjectile proj && p.getUUID().equals(proj.ownerId())) {
                vetoed[0]++;
                if (crash[0]) throw new IllegalStateException("проверка: пуск падает с ошибкой");
                e.setCanceled(true);
            }
        };
        NeoForge.EVENT_BUS.addListener(veto);
        StrikeGameTests.afterTest(h, () -> {
            NeoForge.EVENT_BUS.unregister(veto);
            AirstrikeConfig.SERVER.nukeOpsOnly.set(opsOnly);
            removeFlights(level, p.getUUID());
            level.getEntitiesOfClass(LauncherEntity.class, h.getBounds().inflate(64)).forEach(Entity::discard);
            for (NuclearEvents.ScheduledStrike s : List.copyOf(NuclearEvents.get(level).scheduled())) {
                if (s.owner().map(p.getUUID()::equals).orElse(false)) NuclearEvents.get(level).unschedule(s);
            }
        });
        Vec3 point = Vec3.atCenterOf(h.absolutePos(new BlockPos(16, 3, 200))).add(0, 0.5, 0);
        ServerActions.Aim aim = new ServerActions.Aim(new Target.Point(point), point, null);

        Item shahed = ModItems.SHAHED.get();
        give(p, shahed, 1);
        h.assertFalse(ServerActions.strike(p, WeaponType.DRONE, 1, 0, aim, Loadout.Nuke.DEFAULT, Waypoints.NONE, true), "пуск без входа в мир удался");
        h.assertValueEqual(vetoed[0], 1, "шахед не пытался войти в мир у пусковой");
        h.assertValueEqual(held(p, shahed), 1, "шахед после неудачного пуска");
        h.assertTrue(p.last("airstrike.launch_failed") != null && p.last("airstrike.munitions.refunded") != null, "нет строк о неудаче и возврате");

        AirstrikeConfig.SERVER.nukeOpsOnly.set(false);
        Item icbm = ModItems.ICBM.get();
        give(p, icbm, 1);
        int scheduled = NuclearEvents.get(level).scheduled().size();
        h.assertFalse(ServerActions.strike(p, WeaponType.NUKE, 1, 0, aim, new Loadout.Nuke(15, true), Waypoints.NONE, true), "пуск МБР без входа в мир удался");
        h.assertValueEqual(vetoed[0], 2, "МБР не пыталась войти в мир");
        h.assertValueEqual(held(p, icbm), 1, "МБР после неудачного пуска");
        h.assertValueEqual(NuclearEvents.get(level).scheduled().size(), scheduled, "неудачный пуск записал ядерный удар");

        crash[0] = true;
        p.said.clear();
        h.assertFalse(ServerActions.strike(p, WeaponType.DRONE, 1, 0, aim, Loadout.Nuke.DEFAULT, Waypoints.NONE, true), "пуск с ошибкой удался");
        h.assertValueEqual(vetoed[0], 3, "шахед не пытался войти в мир");
        h.assertValueEqual(held(p, shahed), 1, "шахед после пуска с ошибкой");
        h.assertTrue(p.last("airstrike.munitions.refunded") != null, "нет строки о возврате после ошибки");
        h.succeed();
    }

    /**
     * Рецепты боеприпасов загружены — по одному на предмет: с Create — с его точным механизмом и винтом, без него —
     * ванильная замена (условия NeoForge {@code neoforge:mod_loaded}); каждый собирается из своих ингредиентов на верстаке.
     */
    @GameTest(template = "range", batch = "munitions")
    public static void munitionRecipesLoad(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        var recipes = level.getServer().getRecipeManager();
        boolean create = ModList.get().isLoaded("create");
        // предмет → есть ли у рецепта вариант без Create
        Map<Item, Boolean> munitions = Map.of(ModItems.SHAHED.get(), true, ModItems.LANCET.get(), true, ModItems.CRUISE_MISSILE.get(), true,
                ModItems.GRAD_ROCKETS.get(), false, ModItems.BUNKER_BUSTER.get(), false, ModItems.ICBM.get(), true, ModItems.NUCLEAR_WARHEAD.get(), false);
        for (WeaponType w : WeaponType.values()) h.assertTrue(munitions.containsKey(w.spec().munition().item().get()), "боеприпас " + w + " без проверки рецепта");
        for (Map.Entry<Item, Boolean> e : munitions.entrySet()) {
            Item item = e.getKey();
            List<RecipeHolder<CraftingRecipe>> found = new ArrayList<>();
            for (RecipeHolder<CraftingRecipe> r : recipes.getAllRecipesFor(RecipeType.CRAFTING)) {
                if (r.value().getResultItem(level.registryAccess()).is(item)) found.add(r);
            }
            String name = BuiltInRegistries.ITEM.getKey(item).getPath();
            ResourceLocation expected = Airstrike.id(e.getValue() && !create ? name + "_vanilla" : name);
            h.assertTrue(found.size() == 1 && found.getFirst().id().equals(expected), name + ": рецепты " + found.stream().map(RecipeHolder::id).toList()
                    + " вместо " + expected);
            if (!(found.getFirst().value() instanceof ShapedRecipe shaped)) throw new GameTestAssertException(name + ": рецепт не по форме");
            List<ItemStack> grid = new ArrayList<>();
            for (Ingredient i : shaped.getIngredients()) {
                if (!i.isEmpty() && i.hasNoItems()) throw new GameTestAssertException(name + ": ингредиент без предметов");
                grid.add(i.isEmpty() ? ItemStack.EMPTY : i.getItems()[0].copy());
            }
            var crafted = recipes.getRecipeFor(RecipeType.CRAFTING, CraftingInput.of(shaped.getWidth(), shaped.getHeight(), grid), level);
            h.assertTrue(crafted.isPresent() && crafted.get().id().equals(expected), name + ": верстак не собирает свой рецепт: " + crafted.map(RecipeHolder::id));
        }
        h.succeed();
    }

    // ---------------------------------------------------------------- помощники

    /** {@code FakePlayer}, который запоминает строки над хотбаром и в чате (NeoForge их глушит). */
    static final class Listener extends FakePlayer {
        final List<Component> said = new ArrayList<>();

        Listener(ServerLevel level, String name) {
            super(level, new GameProfile(UUID.randomUUID(), name));
        }

        @Override
        public void displayClientMessage(Component message, boolean actionBar) {
            said.add(message);
        }

        @Override
        public void sendSystemMessage(Component message, boolean bypassHiddenChat) {
            said.add(message);
        }

        /** Последняя строка с ключом перевода {@code key}, или null. */
        @Nullable
        TranslatableContents last(String key) {
            for (int i = said.size() - 1; i >= 0; i--) {
                if (said.get(i).getContents() instanceof TranslatableContents t && t.getKey().equals(key)) return t;
            }
            return null;
        }
    }

    static Listener player(GameTestHelper h, String name) {
        Listener p = new Listener(h.getLevel(), name);
        p.moveTo(Vec3.atBottomCenterOf(h.absolutePos(RANGE_CENTER.above())));
        return p;
    }

    /**
     * Цель — середина площадки (её район уже загружен: снаряды не грузят свежих районов); стреляющего нет в списке
     * игроков, поэтому выпущенные шахеды заходят издалека, в 2 км, и их убирают задолго до подлёта.
     */
    private static ServerActions.Aim aim(GameTestHelper h) {
        Vec3 point = Vec3.atCenterOf(h.absolutePos(RANGE_CENTER)).add(0, 0.5, 0);
        return new ServerActions.Aim(new Target.Point(point), point, null);
    }

    static void give(FakePlayer p, Item item, int n) {
        p.getInventory().placeItemBackInInventory(new ItemStack(item, n));
    }

    /** Снарядов в инвентаре (у пакета — по пакетам). */
    static int held(FakePlayer p, Item item) {
        return Munitions.held(Munitions.of(p).items(), item, Munitions.perItem(item));
    }

    private static int count(FakePlayer p, Item item) {
        return p.getInventory().countItem(item);
    }

    private static ItemStack find(FakePlayer p, Item item) {
        for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
            if (p.getInventory().getItem(i).is(item)) return p.getInventory().getItem(i);
        }
        return ItemStack.EMPTY;
    }

    /** Аргументы строки: компоненты — как есть, числа — числами. */
    @Nullable
    static List<Object> args(@Nullable TranslatableContents t) {
        return t == null ? null : List.of(t.getArgs());
    }

    /** Снаряды владельца — вне мира и в мире — убрать без взрыва. */
    static void removeFlights(ServerLevel level, UUID owner) {
        VirtualFlights.get(level).clear(level, f -> owner.equals(f.ownerId()));
        for (StrikeProjectile e : level.getEntities(EntityTypeTest.forClass(StrikeProjectile.class), f -> owner.equals(f.ownerId()))) {
            e.discard();
        }
    }
}
