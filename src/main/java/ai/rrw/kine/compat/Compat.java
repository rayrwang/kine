package ai.rrw.kine.compat;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Camera;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.NonNullList;
import net.minecraft.network.chat.Component;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.boss.enderdragon.EnderDragonPart;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Matrix4f;

import java.util.List;

/**
 * Where feature code reaches the game code that differs between Minecraft releases (versions/README.md). Everything
 * else in kine is written once and runs on every release its jar covers; these go through the running release's
 * epoch ({@link Epoch}, chosen by {@link GameVersion} when kine starts).
 */
public final class Compat {
    private Compat() {}

    private static final Epoch E = load();

    private static Epoch load() {
        String cls = "ai.rrw.kine.compat." + GameVersion.epoch() + ".EpochImpl";
        try {
            return (Epoch) Class.forName(cls).getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("kine: no game code for Minecraft " + GameVersion.minecraft() + " (" + cls + ")", e);
        }
    }

    /** The running release's epoch, for code that calls several of its methods. */
    public static Epoch epoch() { return E; }

    // screens and the HUD

    public static Screen screen(Minecraft mc) { return E.screen(mc); }

    public static void setScreen(Minecraft mc, Screen screen) { E.setScreen(mc, screen); }

    /** No screen and no loading overlay is up. */
    public static boolean noScreen(Minecraft mc) { return E.screen(mc) == null && !E.overlayOpen(mc); }

    public static boolean overlayOpen(Minecraft mc) { return E.overlayOpen(mc); }

    public static boolean hudHidden(Minecraft mc) { return E.hudHidden(mc); }

    public static void actionBar(Minecraft mc, Component text) { E.actionBar(mc, text); }

    public static Camera camera(Minecraft mc) { return E.camera(mc); }

    // input

    /** The key a key mapping is bound to is held down now, whether or not the game has a screen up (KeyMapping.isDown
     *  stays false behind a screen). */
    public static boolean held(Minecraft mc, KeyMapping km) {
        InputConstants.Key key = ((ai.rrw.kine.mixin.KeyMappingAccessor) km).kine$getKey();
        if (E.isKeyboard(key)) return E.keyDown(mc, key.getValue());
        if (key.getType() == InputConstants.Type.MOUSE) return E.mouseDown(mc, key.getValue());
        return false;
    }

    /** The key mapping is bound to a keyboard key (not a mouse button). */
    public static boolean isKeyboard(KeyMapping km) {
        return E.isKeyboard(((ai.rrw.kine.mixin.KeyMappingAccessor) km).kine$getKey());
    }

    /** A keyboard key's code on the running release, by its name in options.txt without "key.keyboard." ("j",
     *  "enter", "keypad.enter", "f4"). The codes differ between releases (26.3 numbers keys by SDL scancodes), so
     *  feature code never writes a key's number. */
    public static int keyCode(String name) {
        return InputConstants.getKey("key.keyboard." + name).getValue();
    }

    // entities and blocks

    /** The game's Entity.calculateViewVector, here because 26.3 made it static (a call compiled against 26.1 fails on
     *  26.3): the unit vector a rotation looks along, through Mth's sine table. */
    public static Vec3 viewVector(float xRot, float yRot) {
        float realXRot = xRot * (float) (Math.PI / 180.0);
        float realYRot = -yRot * (float) (Math.PI / 180.0);
        float yCos = ai.rrw.kine.move.GameMath.cos(realYRot);   // Mth's table, as the running release indexes it
        float ySin = ai.rrw.kine.move.GameMath.sin(realYRot);
        float xCos = ai.rrw.kine.move.GameMath.cos(realXRot);
        float xSin = ai.rrw.kine.move.GameMath.sin(realXRot);
        return new Vec3(ySin * xCos, -xSin, yCos * xCos);
    }

    public static boolean isEnderman(Entity e) { return E.isEnderman(e); }

    public static boolean isSlime(Entity e) { return E.isSlime(e); }

    public static boolean isMagmaCube(Entity e) { return E.isMagmaCube(e); }

    public static int slimeSize(Entity e) { return E.slimeSize(e); }

    public static Vec3 interpolationTarget(Entity e) { return E.interpolationTarget(e); }

    public static boolean swinging(LivingEntity e) { return E.swinging(e); }

    public static int invulnerableTime(Entity e) { return E.invulnerableTime(e); }

    public static boolean blocksMotion(BlockState state) { return E.blocksMotion(state); }

    public static float bounceRestitution(BlockState state) { return E.bounceRestitution(state); }

    public static boolean suppressesBounce(BlockState state) { return E.suppressesBounce(state); }

    // time, the camera, keys, the inventory, more entities, items and rules (Epoch has what each is)

    public static float partialTick(Minecraft mc, boolean ignoreFrozen) { return E.partialTick(mc, ignoreFrozen); }
    public static long overworldTime(ClientLevel level) { return E.overworldTime(level); }
    public static Vec3 cameraPos(Minecraft mc) { return E.cameraPos(mc); }
    public static Matrix4f viewProjection(Minecraft mc) { return E.viewProjection(mc); }
    public static int minY(Level level) { return E.minY(level); }
    public static int maxY(Level level) { return E.maxY(level); }
    public static KeyMapping key(String name, String defaultKey) { return E.key(name, defaultKey); }
    public static boolean toggleAttack(Options options) { return E.toggleAttack(options); }
    public static boolean toggleUse(Options options) { return E.toggleUse(options); }
    public static int sprintWindow(Options options) { return E.sprintWindow(options); }
    public static void keysAfterInventoryClose(Minecraft mc) { E.keysAfterInventoryClose(mc); }
    public static Input lastKeys(LocalPlayer p) { return E.lastKeys(p); }
    public static NonNullList<ItemStack> mainItems(Inventory inv) { return E.mainItems(inv); }
    public static int selectedSlot(Inventory inv) { return E.selectedSlot(inv); }
    public static void setSelectedSlot(Inventory inv, int slot) { E.setSelectedSlot(inv, slot); }
    public static ItemStack selectedItem(Inventory inv) { return E.selectedItem(inv); }
    public static Level level(Entity e) { return E.level(e); }
    public static Vec3 position(Entity e) { return E.position(e); }
    public static long gameTime(Level level) { return E.gameTime(level); }
    public static int skyDarken(Level level) { return E.skyDarken(level); }
    public static boolean fixedTime(Level level) { return E.fixedTime(level); }
    public static boolean isCreative(Player p) { return E.isCreative(p); }
    public static <T> T component(ItemStack stack, DataComponentType<T> type) { return E.component(stack, type); }
    public static <T> T componentOr(ItemStack stack, DataComponentType<T> type, T def) { return E.componentOr(stack, type, def); }
    public static Holder<DamageType> damageType(Level level, ResourceKey<DamageType> key) { return E.damageType(level, key); }
    public static Direction horizontalFacing(BlockState state) { return E.horizontalFacing(state); }
    public static double fallDistance(Entity e) { return E.fallDistance(e); }
    public static boolean creeperPowered(Creeper c) { return E.creeperPowered(c); }
    public static void pick(Minecraft mc, float partialTick) { E.pick(mc, partialTick); }
    public static boolean ownedBy(TamableAnimal animal, Entity self) { return E.ownedBy(animal, self); }
    public static List<EnderDragonPart> dragonParts(ClientLevel level) { return E.dragonParts(level); }
    public static boolean deflectsProjectiles(Entity e) { return E.deflectsProjectiles(e); }
    public static boolean heartBoundCreaking(Entity e) { return E.heartBoundCreaking(e); }
    public static ItemStack thrownPotion(Entity e) { return E.thrownPotion(e); }
    public static boolean shieldStops(LivingEntity target, Vec3 from, Holder<DamageType> type) { return E.shieldStops(target, from, type); }
    public static boolean waterEvaporates(Level level, BlockPos pos) { return E.waterEvaporates(level, pos); }
    public static boolean enoughFoodToSprint(Player p) { return E.enoughFoodToSprint(p); }
    public static List<Holder<MobEffect>> breathingEffects() { return E.breathingEffects(); }
    public static boolean piercingWeapon(ItemStack stack) { return E.piercingWeapon(stack); }
    public static boolean piglinSafeArmor(LivingEntity e) { return E.piglinSafeArmor(e); }
    public static boolean nextDamageWillBreak(ItemStack stack) { return E.nextDamageWillBreak(stack); }
    public static boolean isGlider(ItemStack stack) { return E.isGlider(stack); }
    public static boolean glidesWith(ItemStack stack, EquipmentSlot slot) { return E.glidesWith(stack, slot); }
    public static VoxelShape collisionShape(CollisionContext ctx, BlockState state, Level level, BlockPos pos) { return E.collisionShape(ctx, state, level, pos); }
    public static String builtinPack(String name, String title, boolean onByDefault) { return E.builtinPack(name, title, onByDefault); }

    // drawing

    public static WorldDraw world() { return E.world(); }

    /** Add a HUD element (at start-up): drawn after the game's misc overlays; each added goes before those added
     *  earlier. */
    public static void hud(String name, HudLayer layer) { E.hud(name, layer); }

    /** Add a HUD element right after another of kine's. */
    public static void hudAfter(String after, String name, HudLayer layer) { E.hudAfter(after, name, layer); }

    // screens

    public static Screen settingsScreen(Screen parent) { return E.settingsScreen(parent); }

    public static Screen navScreen() { return E.navScreen(); }
}
