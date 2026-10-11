package ai.rrw.kine.util;

import net.minecraft.SharedConstants;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentInitializers;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.Unit;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Gliding time left on an elytra, on real item stacks (the game's registries are booted for them). */
class ElytraLifeTest {
    static HolderLookup.Provider registries;

    @BeforeAll
    static void boot() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        // an item's default components (the elytra's durability among them) are bound when the data-driven
        // registries load, which a game does with its world; here from the built-in vanilla data
        registries = VanillaRegistries.createLookup();
        BuiltInRegistries.DATA_COMPONENT_INITIALIZERS.build(registries).forEach(DataComponentInitializers.PendingComponents::apply);
    }

    @Test
    void secondsLeft() {
        ItemStack wing = new ItemStack(Items.ELYTRA);
        assertEquals(431.0, ElytraLife.flightSeconds(wing), 0.0);   // 432 points, the last one doesn't glide
        wing.setDamageValue(400);
        assertEquals(31.0, ElytraLife.flightSeconds(wing), 0.0);
        wing.enchant(registries.lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.UNBREAKING), 3);
        assertEquals(124.0, ElytraLife.flightSeconds(wing), 0.0);   // the tool curve: (3 + 1) x
        assertEquals(0.0, ElytraLife.flightSeconds(new ItemStack(Items.DIAMOND_CHESTPLATE)), 0.0);
    }

    @Test
    void anUnbreakableElytraGlidesForEver() {
        // it can't take damage, so the game never stops its glide (it read 0 s)
        ItemStack wing = new ItemStack(Items.ELYTRA);
        wing.set(DataComponents.UNBREAKABLE, Unit.INSTANCE);
        assertFalse(wing.nextDamageWillBreak());
        assertEquals(Double.POSITIVE_INFINITY, ElytraLife.flightSeconds(wing), 0.0);
    }
}
