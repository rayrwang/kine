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
 * The game code that differs between Minecraft releases, implemented once per epoch (versions/README.md):
 * ai.rrw.kine.compat.&lt;epoch&gt;.EpochImpl in versions/&lt;epoch&gt;/, compiled against that epoch's release. Feature code
 * calls these through {@link Compat}. A new method here must be implemented in every epoch folder; the build fails
 * until it is.
 */
public interface Epoch {
    // screens and the HUD

    /** The open screen, or null. */
    Screen screen(Minecraft mc);

    /** Open a screen (null closes the open one). */
    void setScreen(Minecraft mc, Screen screen);

    /** A loading overlay is up (the resource reload's). */
    boolean overlayOpen(Minecraft mc);

    /** The HUD is hidden (F1). */
    boolean hudHidden(Minecraft mc);

    /** Show a message above the hotbar (the action bar). */
    void actionBar(Minecraft mc, Component text);

    /** The camera the level is drawn from. */
    Camera camera(Minecraft mc);

    // input

    /** A keyboard key (not a mouse button). */
    boolean isKeyboard(InputConstants.Key key);

    /** This keyboard key is held down now (its code on this release). */
    boolean keyDown(Minecraft mc, int code);

    /** This mouse button is held down now. */
    boolean mouseDown(Minecraft mc, int button);

    // entities and blocks

    boolean isEnderman(Entity e);

    /** A slime or a magma cube. */
    boolean isSlime(Entity e);

    boolean isMagmaCube(Entity e);

    /** A slime's or magma cube's size (1, 2, 4); 0 for anything else. */
    int slimeSize(Entity e);

    /** Where the client is moving this entity to (its interpolation's target, the server's last position for it),
     *  else where it is. */
    Vec3 interpolationTarget(Entity e);

    /** Its arm is swinging (an attack or use animation). */
    boolean swinging(LivingEntity e);

    /** Its hurt cooldown, counting down from 20 after a hit. */
    int invulnerableTime(Entity e);

    /** The block's bounce restitution (26.2+: Block.getBounceRestitution, slime 1, beds 0.75); 0 before 26.2, whose
     *  bounces kine copies from the SLIME and BED flags (Rules.BOUNCE_RESTITUTION). */
    float bounceRestitution(BlockState state);

    /** The block stops bounces (26.2+: the suppresses_bounce tag, honey); false before 26.2. */
    boolean suppressesBounce(BlockState state);

    /** The block stops movement into it (BlockState.blocksMotion of 26.1). */
    boolean blocksMotion(BlockState state);

    // time, the camera

    /** The fraction of the tick between the last tick and the next, as the frame is drawn (the delta tracker's). */
    float partialTick(Minecraft mc, boolean ignoreFrozen);

    /** The overworld's time of day, ticks (ClientLevel.getOverworldClockTime of 26.1). */
    long overworldTime(ClientLevel level);

    /** Where the camera is. */
    Vec3 cameraPos(Minecraft mc);

    /** The camera's view rotation times its projection (positions relative to the camera to clip space). */
    Matrix4f viewProjection(Minecraft mc);

    /** The lowest block Y of a level, and its highest (inclusive). */
    int minY(Level level);

    int maxY(Level level);

    // keys and options

    /** A key mapping in kine's own category of the controls screen, registered; its default key by name, as
     *  Compat.keyCode takes it. */
    KeyMapping key(String name, String defaultKey);

    /** The "toggle" options for the attack and use keys (false where the game has none). */
    boolean toggleAttack(Options options);

    boolean toggleUse(Options options);

    /** The ticks within which a second press of forward starts a sprint. */
    int sprintWindow(Options options);

    /** What the game does to the keys when its inventory screen closes (after kine's clicks "close" it). */
    void keysAfterInventoryClose(Minecraft mc);

    /** The player's keys as the last tick read them (the input record of 26.1; on releases without it, built from
     *  the game's input). */
    Input lastKeys(LocalPlayer p);

    // the inventory

    /** The 36 slots that aren't equipment: the hotbar first. */
    NonNullList<ItemStack> mainItems(Inventory inv);

    int selectedSlot(Inventory inv);

    void setSelectedSlot(Inventory inv, int slot);

    ItemStack selectedItem(Inventory inv);

    // more entities, items, effects and the game's rules copied by kine

    /** The level an entity is in (its level(), whose intermediary changed at 1.21.9). */
    Level level(Entity e);

    /** An entity's position (its position(), likewise). */
    Vec3 position(Entity e);

    /** The level's game time, ticks. */
    long gameTime(Level level);

    /** The sky darkening now, 0 to 11 (Level.getSkyDarken, from the time of day and the weather). The client works it
     *  out every tick from 1.21.2; 1.21 and 1.21.1 set it only when the level loads, so there it is worked out here
     *  as Level.updateSkyBrightness does. */
    int skyDarken(Level level);

    /** The level's dimension has a fixed time of day (the Nether, the End). */
    boolean fixedTime(Level level);

    /** A player in creative mode. */
    boolean isCreative(Player p);

    /** An item's data component, or null. */
    <T> T component(ItemStack stack, DataComponentType<T> type);

    /** An item's data component, or a default. */
    <T> T componentOr(ItemStack stack, DataComponentType<T> type, T def);

    /** A damage type of the level's registry. */
    Holder<DamageType> damageType(Level level, ResourceKey<DamageType> key);

    /** A block's horizontal facing (ladders, trapdoors). */
    Direction horizontalFacing(BlockState state);

    /** An entity's fall distance. */
    double fallDistance(Entity e);

    /** A charged creeper (its explosion is twice as big). */
    boolean creeperPowered(Creeper c);

    /** Pick what the crosshair is on again, as the game does each frame (after a turn). */
    void pick(Minecraft mc, float partialTick);

    /** A tamed animal's owner is this entity. */
    boolean ownedBy(TamableAnimal animal, Entity self);

    /** The ender dragon parts the client keeps apart from its entities. */
    List<EnderDragonPart> dragonParts(ClientLevel level);

    /** In the deflects_projectiles tag (the breeze): projectiles bounce off it. */
    boolean deflectsProjectiles(Entity e);

    /** A creaking bound to its heart (it only flinches when hit); false where the game has none. */
    boolean heartBoundCreaking(Entity e);

    /** The item of a thrown splash or lingering potion, or null for anything else. */
    ItemStack thrownPotion(Entity e);

    /** A raised shield stops an unpiercing arrow of this damage type coming from that place (the game's blocking
     *  rule on that release). */
    boolean shieldStops(LivingEntity target, Vec3 from, Holder<DamageType> type);

    /** Water placed here evaporates (the nether). */
    boolean waterEvaporates(Level level, BlockPos pos);

    /** Food enough to sprint (the hunger rule). */
    boolean enoughFoodToSprint(Player p);

    /** The effects that keep a player breathing under water. */
    List<Holder<MobEffect>> breathingEffects();

    /** A weapon that pierces rather than swings (26.1's spear); false where the game has none. */
    boolean piercingWeapon(ItemStack stack);

    /** Gold armour a piglin leaves you alone for. */
    boolean piglinSafeArmor(LivingEntity e);

    /** The item's next point of damage breaks it. */
    boolean nextDamageWillBreak(ItemStack stack);

    /** An item that glides when worn (an elytra; 26.1's glider component). */
    boolean isGlider(ItemStack stack);

    /** This item worn in this slot lets the wearer glide. */
    boolean glidesWith(ItemStack stack, EquipmentSlot slot);

    /** A block's collision shape as this context sees it (scaffolding's depends on who asks). */
    VoxelShape collisionShape(CollisionContext ctx, BlockState state, Level level, BlockPos pos);

    /** Register kine's built-in resource pack (assets under resourcepacks/<name> in the jar, or the folder Packs.folder
     *  picks for this release), on by default or not; the pack's id. */
    String builtinPack(String name, String title, boolean onByDefault);

    // drawing

    /** Drawing in the world. */
    WorldDraw world();

    /** Add a HUD element, drawn after the game's own overlays (the misc ones: vignette, spyglass, portal). Each one
     *  added goes right after them, so before the ones added earlier, as Fabric's attachElementAfter does. */
    void hud(String name, HudLayer layer);

    /** Add a HUD element right after another of kine's. */
    void hudAfter(String after, String name, HudLayer layer);

    // screens (each family's subclasses of kine's abstract screens, with that release's render and input overrides)

    Screen settingsScreen(Screen parent);

    Screen navScreen();
}
