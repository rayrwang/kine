package ai.rrw.kine.combat;

import ai.rrw.kine.Kine;
import ai.rrw.kine.Settings;
import ai.rrw.kine.autoflight.Takeoff;
import ai.rrw.kine.fight.CombatMovement;
import ai.rrw.kine.mixin.KeyMappingAccessor;
import ai.rrw.kine.util.GameModes;
import ai.rrw.kine.util.Hotbar;
import ai.rrw.kine.util.InventoryClicks;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.HitResult;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Auto eat (RW, 2026-10-12): one switch, the rest decided here.
 *
 * <p>When. With every heart there: as soon as the best food carried fits whole, its hunger and its saturation both
 * taken in with none wasted (hunger + nutrition at most 20, and saturation + the food's saturation at most the new
 * hunger, the game's cap in FoodData.eat). With any heart missing: whenever the game lets you eat (hunger under 20),
 * since healing runs on a full bar and on saturation. Too hungry to sprint (hunger 6 or less, FoodData.hasEnoughFood):
 * at once, waste or not.
 *
 * <p>What. The best food first, by hunger plus saturation; with hearts missing (or too hungry to sprint), the one that
 * adds the most hunger and saturation as things stand, so a small top-up takes the most saturation. Raw chicken,
 * rotten flesh (their effect: Hunger) only when nothing else is carried and you are too hungry to sprint; poisonous
 * potatoes, spider eyes (Poison) and suspicious stew (a random effect, Wither among them) only at hunger 0, where
 * starvation hurts, the stew only above 4 hearts; the least harmful first; with hearts missing and no
 * good food left, raw chicken and rotten flesh (Hunger only, about 0.75 hunger lost over its 30 s) under hunger 18, since
 * healing needs 18 (FoodData.tick), but never the poisons or the stew, which cost hearts (Poison I: 1 damage every 25
 * ticks, 2 hearts over a spider eye's 5 s) (RW's "ok", 2026-10-12). A golden apple only with
 * hearts missing (RW: "yes orndiary eaten"): among the rest by what it adds, and at a full bar, where nothing else can
 * be eaten, on its own; not while a regeneration effect is still running. Never pufferfish (poison, hunger, nausea),
 * chorus fruit (a random teleport) or an enchanted golden apple (RW: "encharted use by hand dont auto").
 *
 * <p>How, as a player would: a food in the hotbar is selected (a number key); one in the main inventory is first
 * swapped into the hotbar through an inventory opening (InventoryClicks: a tick with every key up, the number-key swap,
 * the close), into an empty slot, else the largest stack (RW's rule). Sneak is pressed the tick before and while the
 * eating starts, so a block under the crosshair isn't used instead (a chest opened, a door, food put in a composter:
 * MultiPlayerGameMode.performUseItemOn skips the block while sneaking); a mob under the crosshair waits (the food could
 * feed it). Then the use key is held until the food is eaten, and the slot you had is selected again. Gliding is no
 * obstacle (eating doesn't touch the glide; RW asked why it was left out, 2026-10-12). It waits while you fight (the
 * kill aura has a target near, or combat movement moves you), take off (the rockets are in your hand), use an item,
 * have a screen open, or click attack or use yourself, and lets go if you do any of that or pick another slot meanwhile.
 * Survival and adventure only.
 */
public final class AutoEat {
    private AutoEat() {}

    private enum Phase { IDLE, FETCH, SELECT, SNEAK, EAT, RESTORE }

    static final int SPRINT_FOOD = 6;          // hunger at or below this: no sprinting (FoodData.hasEnoughFood)
    static final int HEAL_FOOD = 18;           // hunger from which the player heals (FoodData.tick)
    static final int HUNGER_ONLY = 2;          // the first BAD entries, whose only effect is Hunger
    static final float STEW_HEALTH = 8f;       // suspicious stew only above 4 hearts (its Wither can kill)
    private static final int COOLDOWN = 10;    // ticks after a meal or a give-up before the next try
    // Your clicks come first (RW: "the eating shoudl be overidalbe with mouse clicik input ... it shoudl backoff"):
    // nothing starts within QUIET of your last attack or use click (mining, building and fighting by hand click more
    // often than that), and a meal you break off (a click, a slot picked, a screen opened) waits BACKOFF_START before the
    // next try, doubling with each break-off in a row up to BACKOFF_MAX, back to the start once a meal is finished.
    static final int QUIET = 40, BACKOFF_START = 100, BACKOFF_MAX = 600;   // ticks: 2 s; 5 s doubling to 30 s
    private static final int START_TICKS = 40; // the eating must have started within this
    private static final int EAT_TICKS = 140;  // and ended within this (the slowest food, honey, takes 40)
    private static final double FIGHT_RANGE = 8.0;

    /** Never eaten: pufferfish (poison, hunger, nausea), chorus fruit (a random teleport), enchanted golden apples (by
     *  hand only, RW). */
    static final Set<Item> NEVER = Set.of(Items.PUFFERFISH, Items.CHORUS_FRUIT, Items.ENCHANTED_GOLDEN_APPLE);
    /** Last resort, least harmful first: raw chicken (30% hunger), rotten flesh (80% hunger), poisonous potato (60%
     *  poison), spider eye (poison), suspicious stew (a random effect, maybe poison or wither). */
    static final List<Item> BAD = List.of(Items.CHICKEN, Items.ROTTEN_FLESH, Items.POISONOUS_POTATO, Items.SPIDER_EYE,
                                          Items.SUSPICIOUS_STEW);

    /** One food carried: its slot in the player's inventory (0 to 8 hotbar, 9 to 35 main), its numbers, and its
     *  place among the bad foods (-1 for a good one); a golden apple is eaten only with hearts missing. */
    record Food(int slot, int nutrition, float saturation, int bad, boolean golden) {
        Food(int slot, int nutrition, float saturation, int bad) { this(slot, nutrition, saturation, bad, false); }
        double goodness() { return nutrition + saturation; }
    }

    private static Phase phase = Phase.IDLE;
    private static int foodSlot = -1, restoreSlot = -1, fetchFrom = -1, ticks = 0, cooldown = 0;
    private static Item foodItem = null;
    private static boolean sawUsing = false, holding = false, badRun = false;
    private static long now = 0, lastClick = -QUIET;
    private static int backoff = BACKOFF_START;

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(AutoEat::endTick);
    }

    /** Whether it is holding the use key to eat (the kill aura lets that use through while its rotation is turned). */
    public static boolean holdingUse() { return holding; }

    /** Whether a meal is under way, from fetching the food to putting your slot back (the bow aura waits). */
    public static boolean busy() { return phase != Phase.IDLE; }

    // ---------------------------------------------------------------- the choice (pure, tested)

    /** Hunger and saturation a food would add now, after the game's caps. */
    static double gain(Food f, int food, float sat) {
        int after = Math.min(20, food + f.nutrition());
        return (after - food) + (Math.min(sat + f.saturation(), after) - sat);
    }

    /** Whether a food fits whole now: none of its hunger or saturation wasted. */
    static boolean fits(Food f, int food, float sat) {
        return food + f.nutrition() <= 20 && sat + f.saturation() <= food + f.nutrition() + 1e-6;
    }

    static Food choose(List<Food> carried, int food, float sat, boolean hurt) {
        return choose(carried, food, sat, hurt, false, false);
    }

    /** The food to eat now, or null. `regen`: a regeneration effect is running (a golden apple would be wasted).
     *  `badRun`: the last meal, just now, was a bad food. A bad food's hunger and saturation add up, but its effect
     *  doesn't: eaten again while it runs, the effect starts its full time over rather than adding (MobEffectInstance
     *  .update keeps the longer of the two at one strength), so bad foods are eaten one after another while their
     *  hunger still fits, sharing one effect (RW). A rotten flesh's Hunger costs about 0.75 hunger over its 30 s. */
    static Food choose(List<Food> carried, int food, float sat, boolean hurt, boolean regen, boolean badRun) {
        return choose(carried, food, sat, hurt, regen, badRun, 20f);
    }

    /** The same, with your health: at hunger 0 (starvation damage, deadly on Hard) the poison foods are allowed too,
     *  since poison stops at half a heart, and suspicious stew only above STEW_HEALTH, so a Wither from it can't finish
     *  you; above 0 only the Hunger ones (RW: "ok", 2026-10-12). */
    static Food choose(List<Food> carried, int food, float sat, boolean hurt, boolean regen, boolean badRun, float health) {
        boolean apples = hurt && !regen;
        if (food >= 20) {   // only a golden apple can be eaten at a full bar
            if (apples) for (Food f : carried) if (f.golden()) return f;
            return null;
        }
        boolean starving = food <= SPRINT_FOOD;
        Food bestGood = null, mostGain = null, leastBad = null, leastHunger = null;
        int stew = BAD.indexOf(Items.SUSPICIOUS_STEW);
        for (Food f : carried) {
            if (f.golden()) {
                if (apples && (mostGain == null || gain(f, food, sat) > gain(mostGain, food, sat))) mostGain = f;
                continue;
            }
            if (f.bad() >= 0) {
                if (f.bad() == stew && health <= STEW_HEALTH) continue;
                if (leastBad == null || f.bad() < leastBad.bad()
                    || f.bad() == leastBad.bad() && gain(f, food, sat) > gain(leastBad, food, sat)) leastBad = f;
                if (f.bad() < HUNGER_ONLY && (leastHunger == null || f.bad() < leastHunger.bad())) leastHunger = f;
                continue;
            }
            if (bestGood == null || f.goodness() > bestGood.goodness()
                || f.goodness() == bestGood.goodness() && f.saturation() > bestGood.saturation()) bestGood = f;
            if (mostGain == null || gain(f, food, sat) > gain(mostGain, food, sat)) mostGain = f;
        }
        if (hurt && mostGain != null) return mostGain;
        if (bestGood == null) {
            if (food == 0) return leastBad;   // starving: anything, the least harmful first
            boolean want = starving || hurt && food < HEAL_FOOD || badRun;
            return want && leastHunger != null && food + leastHunger.nutrition() <= 20 ? leastHunger : null;
        }
        if (starving) return mostGain;
        return fits(bestGood, food, sat) ? bestGood : null;
    }

    /** The foods carried in the hotbar and main inventory (not the offhand), each kind once, a hotbar copy preferred
     *  (the selected slot first). */
    static List<Food> carried(Inventory inv) {
        List<Food> out = new ArrayList<>();
        List<Item> seen = new ArrayList<>();
        int sel = inv.getSelectedSlot();
        int[] order = new int[36];
        order[0] = sel;
        for (int i = 0, k = 1; i < 36; i++) if (i != sel) order[k++] = i;
        for (int i : order) {
            ItemStack st = inv.getNonEquipmentItems().get(i);
            if (st.isEmpty() || NEVER.contains(st.getItem()) || seen.contains(st.getItem())) continue;
            FoodProperties fp = st.get(DataComponents.FOOD);
            if (fp == null) continue;
            seen.add(st.getItem());
            out.add(new Food(i, fp.nutrition(), fp.saturation(), BAD.indexOf(st.getItem()), st.is(Items.GOLDEN_APPLE)));
        }
        return out;
    }

    // ---------------------------------------------------------------- the ticks

    private static void endTick(Minecraft mc) {
        LocalPlayer p = mc.player;
        if (p == null || mc.gameMode == null) { reset(mc); return; }
        if (!Settings.autoEat || !GameModes.survivalLike(mc) || !p.isAlive()) { if (phase != Phase.IDLE) giveUp(mc, "off"); return; }
        Inventory inv = p.getInventory();
        now++;
        if (yourClick(mc)) lastClick = now;
        switch (phase) {
            case IDLE -> {
                if (cooldown > 0) { cooldown--; return; }
                if (!free(mc, p)) return;
                Food f = choose(carried(inv), p.getFoodData().getFoodLevel(), p.getFoodData().getSaturationLevel(),
                                p.getHealth() < p.getMaxHealth(), p.hasEffect(MobEffects.REGENERATION), badRun, p.getHealth());
                badRun = false;
                if (f == null) return;
                foodItem = inv.getNonEquipmentItems().get(f.slot()).getItem();
                restoreSlot = inv.getSelectedSlot();
                if (f.slot() < 9) {
                    foodSlot = f.slot();
                    if (inv.getSelectedSlot() != foodSlot) inv.setSelectedSlot(foodSlot);   // a number key
                    begin(Phase.SNEAK);
                } else {
                    if (!InventoryClicks.available(mc, p) || InventoryClicks.isOpenTick()) return;
                    fetchFrom = f.slot();
                    InventoryClicks.requestOpen();
                    begin(Phase.FETCH);
                }
            }
            case FETCH -> {
                if (!InventoryClicks.isOpenTick()) { if (++ticks > 2) giveUp(mc, "the inventory didn't open"); return; }
                AbstractContainerMenu menu = p.inventoryMenu;
                ItemStack st = inv.getNonEquipmentItems().get(fetchFrom);
                if (!InventoryClicks.available(mc, p) || st.getItem() != foodItem || !InventoryClicks.claimBatch()) {
                    giveUp(mc, "the food moved");
                    return;
                }
                int to = Hotbar.slotToDisplace(inv.getNonEquipmentItems()::get);
                mc.gameMode.handleContainerInput(menu.containerId, fetchFrom, to, ContainerInput.SWAP, p);   // number-key swap
                InventoryClicks.closeAsVanilla(mc, p);
                foodSlot = to;
                begin(Phase.SELECT);
            }
            case SELECT -> {   // the tick after the swap, as WeaponSelect does
                if (inv.getNonEquipmentItems().get(foodSlot).getItem() != foodItem) { giveUp(mc, "the food moved"); return; }
                inv.setSelectedSlot(foodSlot);
                begin(Phase.SNEAK);
            }
            case SNEAK -> {    // sneak goes out in this tick's input, before the use
                if (interrupted(mc, p, inv)) return;
                begin(Phase.EAT);
            }
            case EAT -> {
                if (p.isUsingItem()) sawUsing = true;
                ticks++;
                if (sawUsing && !p.isUsingItem()) {   // eaten (or stopped): let go before the game starts another
                    release(mc);
                    Kine.LOGGER.info("Kine auto eat: {} at hunger {}, saturation {}", foodItem,
                                     p.getFoodData().getFoodLevel(), String.format("%.1f", p.getFoodData().getSaturationLevel()));
                    begin(Phase.RESTORE);
                    return;
                }
                if (interrupted(mc, p, inv)) return;
                if (!sawUsing && ticks > START_TICKS || ticks > EAT_TICKS) giveUp(mc, "the eating didn't start or end");
            }
            case RESTORE -> {
                boolean bad = BAD.contains(foodItem);
                if (restoreSlot >= 0 && restoreSlot != foodSlot && inv.getSelectedSlot() == foodSlot) inv.setSelectedSlot(restoreSlot);
                reset(mc);
                badRun = bad;                     // another bad food straight after, while its effect still runs
                cooldown = bad ? 0 : COOLDOWN;
                backoff = BACKOFF_START;
            }
        }
    }

    /** Just before the game's keybind step, after the kill aura has turned and picked (VanillaActions): hold the use
     *  key while eating, unless a mob is under the crosshair (the use would feed it). */
    public static void beforeKeybinds(Minecraft mc) {
        if (phase != Phase.EAT || mc.player == null) return;
        if (sawUsing && !mc.player.isUsingItem()) { release(mc); return; }
        boolean onMob = !sawUsing && mc.hitResult != null && mc.hitResult.getType() == HitResult.Type.ENTITY;
        mc.options.keyUse.setDown(!onMob);
        holding = !onMob;
    }

    /** Sneak the tick before the eating starts and until it has. */
    public static Input adjustInput(LocalPlayer p, Input keys) {
        if (!(phase == Phase.SNEAK || phase == Phase.EAT && !sawUsing) || keys.shift()) return keys;
        return new Input(keys.forward(), keys.backward(), keys.left(), keys.right(), keys.jump(), true, keys.sprint());
    }

    // ---------------------------------------------------------------- conditions

    /** Nothing in the way of starting: no screen, nothing in use, not taking off, no fight, no click of yours. */
    private static boolean free(Minecraft mc, LocalPlayer p) {
        if (mc.screen != null || p.isUsingItem() || Takeoff.active() || p.getAbilities().flying) return false;
        if (InventoryClicks.isOpenTick() || now - lastClick < QUIET) return false;
        if (CombatMovement.controlling() || BowAura.busy()) return false;
        if (Settings.killAura && mc.level != null && !mc.level.getEntitiesOfClass(LivingEntity.class,
                p.getBoundingBox().inflate(FIGHT_RANGE), e -> e != p && KillAura.targets(e, p)).isEmpty()) return false;
        return true;
    }

    /** You took over or something came between: let go, and leave the slot as you set it. */
    private static boolean interrupted(Minecraft mc, LocalPlayer p, Inventory inv) {
        String why = mc.screen != null ? "a screen opened" : Takeoff.active() ? "a takeoff"
            : inv.getSelectedSlot() != foodSlot ? "you picked another slot"
            : inv.getNonEquipmentItems().get(foodSlot).getItem() != foodItem ? "the food moved"
            : yourClick(mc) ? "you clicked" : null;
        if (why == null) return false;
        if (inv.getSelectedSlot() != foodSlot) restoreSlot = -1;
        boolean byYou = !"a takeoff".equals(why) && !"the food moved".equals(why);
        giveUp(mc, why);
        if (byYou) {
            cooldown = backoff;
            Kine.LOGGER.info("Kine auto eat: next try in {}s", backoff / 20);
            backoff = Math.min(backoff * 2, BACKOFF_MAX);
        }
        return true;
    }

    /** Your own attack or use, read from the mouse or keyboard itself (the use key may be held by this feature). */
    private static boolean yourClick(Minecraft mc) {
        return physical(mc, mc.options.keyAttack) || physical(mc, mc.options.keyUse);
    }

    private static boolean physical(Minecraft mc, KeyMapping km) {
        InputConstants.Key key = ((KeyMappingAccessor) km).kine$getKey();
        if (key.getType() == InputConstants.Type.KEYSYM) return InputConstants.isKeyDown(mc.getWindow(), key.getValue());
        if (key.getType() == InputConstants.Type.MOUSE)
            return GLFW.glfwGetMouseButton(mc.getWindow().handle(), key.getValue()) == GLFW.GLFW_PRESS;
        return false;
    }

    // ---------------------------------------------------------------- state

    private static void begin(Phase next) { phase = next; ticks = 0; sawUsing = false; }

    private static void release(Minecraft mc) {
        if (holding) { mc.options.keyUse.setDown(physical(mc, mc.options.keyUse)); holding = false; }
    }

    private static void giveUp(Minecraft mc, String why) {
        LocalPlayer p = mc.player;
        if (p != null && restoreSlot >= 0 && foodSlot >= 0 && restoreSlot != foodSlot && phase.ordinal() >= Phase.SNEAK.ordinal()
            && p.getInventory().getSelectedSlot() == foodSlot) p.getInventory().setSelectedSlot(restoreSlot);
        Kine.LOGGER.info("Kine auto eat: stopped ({})", why);
        reset(mc);
        cooldown = COOLDOWN;
    }

    private static void reset(Minecraft mc) {
        release(mc);
        phase = Phase.IDLE; ticks = 0; sawUsing = false;
        foodSlot = restoreSlot = fetchFrom = -1; foodItem = null;
    }
}
