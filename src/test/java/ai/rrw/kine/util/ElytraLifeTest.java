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
    void anElytraAsAWing() {
        var lookup = registries.lookupOrThrow(Registries.ENCHANTMENT);
        ItemStack wing = new ItemStack(Items.ELYTRA);
        assertEquals(432, ElytraLife.wing(wing).left());          // the tooltip's number
        assertEquals(429, ElytraLife.wing(wing).usable());        // above the 3-point floor
        wing.setDamageValue(400);
        assertEquals(32, ElytraLife.wing(wing).left());
        assertEquals(29.0, ElytraLife.wing(wing).seconds(), 0.0);
        wing.enchant(lookup.getOrThrow(Enchantments.UNBREAKING), 3);
        assertEquals(3, ElytraLife.wing(wing).unbreaking());
        assertEquals(116.0, ElytraLife.wing(wing).seconds(), 0.0);   // the tool curve: (3 + 1) x
        wing.setDamageValue(429);
        assertTrue(ElytraLife.wing(wing).spent());
        assertNull(ElytraLife.wing(new ItemStack(Items.DIAMOND_CHESTPLATE)));
        assertTrue(ElytraLife.isSpare(wing));
        assertFalse(ElytraLife.isSpare(new ItemStack(Items.DIAMOND_CHESTPLATE)));
    }

    @Test
    void anUnbreakableElytraGlidesForEver() {
        // it can't take damage, so the game never stops its glide; as a spare the swap never takes it
        ItemStack wing = new ItemStack(Items.ELYTRA);
        wing.set(DataComponents.UNBREAKABLE, Unit.INSTANCE);
        assertFalse(wing.nextDamageWillBreak());
        assertTrue(ElytraLife.wing(wing).unbreakable());
        assertEquals(Double.POSITIVE_INFINITY, ElytraLife.wing(wing).seconds(), 0.0);
        assertFalse(ElytraLife.isSpare(wing));
    }
}
