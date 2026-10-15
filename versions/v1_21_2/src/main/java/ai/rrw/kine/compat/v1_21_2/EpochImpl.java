package ai.rrw.kine.compat.v1_21_2;

import ai.rrw.kine.compat.Compat;
import ai.rrw.kine.compat.GameVersion;
import ai.rrw.kine.compat.HudLayer;
import ai.rrw.kine.compat.Mobs;
import ai.rrw.kine.compat.WorldDraw;
import ai.rrw.kine.compat.f121.Base121;
import ai.rrw.kine.mixin.v1_21_2.GameRendererFovInvoker;
import ai.rrw.kine.ui.KineNavScreen;
import ai.rrw.kine.ui.KineSettingsScreen;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.LayeredDraw;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.tags.EntityTypeTags;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.boss.EnderDragonPart;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.creaking.Creaking;
import net.minecraft.world.entity.monster.piglin.PiglinAi;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ThrownPotion;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ShieldItem;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * Minecraft 1.21.2 to 1.21.4: the game API of 1.21.4 (compiled against it), which 1.21.2 and 1.21.3 share for kine
 * except where a method says otherwise. The GUI is GuiGraphics with a 3-D pose stack, keys and screens take the
 * GLFW codes, the world is drawn with render types and shader programs (no render pipelines yet), and the HUD is the
 * game's LayeredDraw, which kine's elements join through GuiHudMixin.
 */
public final class EpochImpl extends Base121 {
    private WorldDraw world;   // made on first use, after the game's shaders exist

    // input

    @Override public boolean keyDown(Minecraft mc, int code) { return InputConstants.isKeyDown(mc.getWindow().getWindow(), code); }

    @Override
    public boolean mouseDown(Minecraft mc, int button) {
        return GLFW.glfwGetMouseButton(mc.getWindow().getWindow(), button) == GLFW.GLFW_PRESS;
    }

    // entities and blocks

    /** Where the client is moving the entity to: a living entity's (and a boat's or minecart's) lerp target while
     *  its lerp steps run, else where it is (Entity.lerpTargetX and so on). */
    @Override public Vec3 interpolationTarget(Entity e) { return new Vec3(e.lerpTargetX(), e.lerpTargetY(), e.lerpTargetZ()); }

    // time, the camera

    @Override public float partialTick(Minecraft mc, boolean ignoreFrozen) { return mc.getDeltaTracker().getGameTimeDeltaPartialTick(ignoreFrozen); }

    @Override public long overworldTime(ClientLevel level) { return level.getDayTime(); }

    @Override public Vec3 cameraPos(Minecraft mc) { return camera(mc).getPosition(); }

    /** The projection as the frame is drawn (GameRenderer's, at this frame's field of view) times the camera's view
     *  rotation, as 26.1's Camera.getViewRotationProjectionMatrix. */
    @Override
    public Matrix4f viewProjection(Minecraft mc) {
        float fov = ((GameRendererFovInvoker) mc.gameRenderer).kine$getFov(camera(mc), partialTick(mc, true), true);
        Matrix4f view = new Matrix4f().rotation(camera(mc).rotation().conjugate(new Quaternionf()));
        return mc.gameRenderer.getProjectionMatrix(fov).mul(view);
    }

    @Override public int minY(Level level) { return level.getMinY(); }

    @Override public int maxY(Level level) { return level.getMaxY(); }

    // keys and options

    /** Kine's section of the controls screen: before 1.21.9 a category is its translation key. */
    private static final String CATEGORY = "key.category.kine.keybinds";

    @Override
    public KeyMapping key(String name, String defaultKey) {
        return KeyBindingHelper.registerKeyBinding(new KeyMapping(name, Compat.keyCode(defaultKey), CATEGORY));
    }

    /** No toggle options for attack and use before 1.21.9. */
    @Override public boolean toggleAttack(Options options) { return false; }

    @Override public boolean toggleUse(Options options) { return false; }

    /** Fixed before 1.21.9: LocalPlayer.aiStep starts the window at 7 ticks on a press of forward. */
    @Override public int sprintWindow(Options options) { return 7; }

    /** MouseHandler.grabMouse: the held keys read again, except on macOS (no toggle keys to restore before 1.21.9). */
    @Override
    public void keysAfterInventoryClose(Minecraft mc) {
        if (!Minecraft.ON_OSX) KeyMapping.setAll();
    }

    @Override public ai.rrw.kine.compat.Input lastKeys(LocalPlayer p) { return kineKeys(p.input.keyPresses); }

    /** The game's key record as kine's, and back (ai.rrw.kine.compat.Input has the same parts). */
    public static ai.rrw.kine.compat.Input kineKeys(net.minecraft.world.entity.player.Input i) {
        return new ai.rrw.kine.compat.Input(i.forward(), i.backward(), i.left(), i.right(), i.jump(), i.shift(), i.sprint());
    }

    public static net.minecraft.world.entity.player.Input gameKeys(ai.rrw.kine.compat.Input i) {
        return new net.minecraft.world.entity.player.Input(i.forward(), i.backward(), i.left(), i.right(), i.jump(), i.shift(), i.sprint());
    }

    /** 1.21.2 and 1.21.3 slow a sneaking or crawling player's impulses inside the input's tick (it takes the factor);
     *  1.21.4 does it after, in LocalPlayer.aiStep (KeyboardInputMixin). */
    public static final boolean SLOWDOWN_IN_INPUT_TICK = !GameVersion.atLeast("1.21.4");

    // the inventory: before 1.21.5 the fields themselves

    @Override public NonNullList<ItemStack> mainItems(Inventory inv) { return inv.items; }

    @Override public int selectedSlot(Inventory inv) { return inv.selected; }

    @Override public void setSelectedSlot(Inventory inv, int slot) { inv.selected = slot; }

    @Override public ItemStack selectedItem(Inventory inv) { return inv.getSelected(); }

    // entities, items, effects, rules

    @Override public Level level(Entity e) { return e.level(); }

    @Override public Vec3 position(Entity e) { return e.position(); }

    @Override public long gameTime(Level level) { return level.getGameTime(); }

    @Override public boolean fixedTime(Level level) { return level.dimensionType().hasFixedTime(); }

    @Override public boolean isCreative(Player p) { return p.isCreative(); }

    @Override public <T> T component(ItemStack stack, DataComponentType<T> type) { return stack.get(type); }

    @Override public <T> T componentOr(ItemStack stack, DataComponentType<T> type, T def) { return stack.getOrDefault(type, def); }

    @Override
    public Holder<DamageType> damageType(Level level, ResourceKey<DamageType> key) {
        return level.registryAccess().lookupOrThrow(Registries.DAMAGE_TYPE).getOrThrow(key);
    }

    @Override public Direction horizontalFacing(BlockState state) { return state.getValue(BlockStateProperties.HORIZONTAL_FACING); }

    /** A float before 1.21.5. On 1.21.4 the client doesn't count its own player's fall (its fallDistance stays 0), so
     *  for the local player this is the server's count as kine follows it (ServerFall). */
    @Override
    public double fallDistance(Entity e) {
        return ServerFall.ACTIVE && e instanceof LocalPlayer p ? ServerFall.of(p) : e.fallDistance;
    }

    @Override public boolean creeperPowered(Creeper c) { return c.isPowered(); }

    @Override
    public boolean ownedBy(TamableAnimal animal, Entity self) {
        UUID owner = animal.getOwnerUUID();
        return owner != null && owner.equals(self.getUUID());
    }

    /** The parts of every dragon the client has. 1.21.4 also keeps them in a list (ClientLevel.dragonParts), which
     *  1.21.2 and 1.21.3 lack; the dragons' own parts are the same entities on all three. */
    @Override
    public List<EnderDragonPart> dragonParts(ClientLevel level) {
        List<EnderDragonPart> parts = new ArrayList<>();
        for (Entity e : level.entitiesForRendering())
            if (e instanceof EnderDragon dragon) parts.addAll(Arrays.asList(dragon.getSubEntities()));
        return parts;
    }

    @Override public boolean deflectsProjectiles(Entity e) { return e.getType().is(EntityTypeTags.DEFLECTS_PROJECTILES); }

    /** 1.21.4: a creaking with a home (Creaking.isHeartBound). 1.21.2 and 1.21.3: a heart's creakings are a kind of
     *  their own, creaking_transient (CreakingTransient, whose hurt only flinches), and Creaking has no home. */
    @Override
    public boolean heartBoundCreaking(Entity e) {
        return Mobs.is(e, "creaking_transient") || HEART_BOUND_CREAKING && e instanceof Creaking c && c.isHeartBound();
    }

    private static final boolean HEART_BOUND_CREAKING = GameVersion.atLeast("1.21.4");

    /** A splash or lingering potion: one class, ThrownPotion, before 1.21.5. */
    @Override public ItemStack thrownPotion(Entity e) { return e instanceof ThrownPotion pot ? pot.getItem() : null; }

    // LivingEntity.isDamageSourceBlocked: a raised shield, past its block delay, stops an unpiercing arrow (piercing
    // ones the caller leaves out) of a damage type not in bypasses_shield, coming from anywhere in front of where the
    // holder's head faces: the source's flat direction to the holder is against the view. The game takes the source's
    // position from the arrow as it hits; the shooter's position stands in for it.
    @Override
    public boolean shieldStops(LivingEntity le, Vec3 from, Holder<DamageType> type) {
        ItemStack blocking = le.getItemBlockingWith();
        if (blocking == null || !(blocking.getItem() instanceof ShieldItem) || type.is(DamageTypeTags.BYPASSES_SHIELD)) return false;
        Vec3 view = Compat.viewVector(0.0F, le.getYHeadRot());
        Vec3 to = le.position().subtract(from);
        to = new Vec3(to.x, 0.0, to.z).normalize();
        return to.dot(view) < 0.0;
    }

    /** BucketItem.emptyContents: water boils away in an ultrawarm dimension (the Nether). */
    @Override public boolean waterEvaporates(Level level, BlockPos pos) { return level.dimensionType().ultraWarm(); }

    /** LocalPlayer.hasEnoughFoodToStartSprinting's hunger part. */
    @Override public boolean enoughFoodToSprint(Player p) { return p.getFoodData().getFoodLevel() > 6.0F; }

    /** MobEffectUtil.hasWaterBreathing (no Breath of the Nautilus before 1.21.11). */
    @Override public List<Holder<MobEffect>> breathingEffects() { return List.of(MobEffects.WATER_BREATHING, MobEffects.CONDUIT_POWER); }

    /** No spear before 1.21.11. */
    @Override public boolean piercingWeapon(ItemStack stack) { return false; }

    @Override public boolean piglinSafeArmor(LivingEntity e) { return PiglinAi.isWearingSafeArmor(e); }

    @Override public boolean nextDamageWillBreak(ItemStack stack) { return stack.nextDamageWillBreak(); }

    @Override public boolean isGlider(ItemStack stack) { return stack.has(DataComponents.GLIDER); }

    @Override public boolean glidesWith(ItemStack stack, EquipmentSlot slot) { return LivingEntity.canGlideUsing(stack, slot); }

    @Override
    public VoxelShape collisionShape(CollisionContext ctx, BlockState state, Level level, BlockPos pos) {
        return ctx.getCollisionShape(state, level, pos);
    }

    // drawing

    @Override public WorldDraw world() { if (world == null) world = new World(); return world; }

    /** Kine's HUD elements in drawing order: GuiHudMixin draws them right after the game's camera overlays (the misc
     *  ones: vignette, spyglass, pumpkin, frost, portal), where 26.1's Fabric HUD registry attaches them. Fabric's
     *  layer registry for these releases (HudLayerRegistrationCallback) exists for 1.21.4 only. */
    private static final List<Element> HUD = new ArrayList<>();

    private record Element(String name, HudLayer layer) {}

    @Override public void hud(String name, HudLayer layer) { HUD.add(0, new Element(name, layer)); }

    @Override
    public void hudAfter(String after, String name, HudLayer layer) {
        for (int i = 0; i < HUD.size(); i++)
            if (HUD.get(i).name().equals(after)) { HUD.add(i + 1, new Element(name, layer)); return; }
        throw new IllegalStateException("kine: no HUD element " + after + " to put " + name + " after");
    }

    /** Draw kine's HUD elements (GuiHudMixin): each a layer of the game's LayeredDraw, which lifts each layer it draws
     *  above the one before by Z_SEPARATION, so items and text of one stay under the next, as Fabric's layers do. */
    public static void drawHud(GuiGraphics g, DeltaTracker delta) {
        for (Element e : HUD) {
            g.pose().translate(0.0F, 0.0F, LayeredDraw.Z_SEPARATION);
            e.layer().draw(new Gui(g), delta);
        }
    }

    // screens

    @Override public Screen settingsScreen(Screen parent) { return new Settings(parent); }

    @Override public Screen navScreen() { return new Nav(); }

    private static final class Settings extends KineSettingsScreen {
        Settings(Screen parent) { super(parent); }

        @Override
        public void render(GuiGraphics g, int mouseX, int mouseY, float a) {
            renderScreen(new Gui(g), mouseX, mouseY, a, () -> super.render(g, mouseX, mouseY, a));
        }
    }

    private static final class Nav extends KineNavScreen {
        @Override
        public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
            renderScreen(new Gui(g), mouseX, mouseY, partial, () -> super.render(g, mouseX, mouseY, partial));
        }

        @Override
        public boolean keyPressed(int key, int scanCode, int modifiers) {
            return keyDown(key) || super.keyPressed(key, scanCode, modifiers);
        }

        @Override
        public boolean mouseClicked(double x, double y, int button) {
            return mouseDown(x, y, button) || super.mouseClicked(x, y, button);
        }

        @Override
        public boolean mouseDragged(double x, double y, int button, double dragX, double dragY) {
            return mouseDrag(x, y) || super.mouseDragged(x, y, button, dragX, dragY);
        }

        @Override
        public boolean mouseReleased(double x, double y, int button) {
            mouseUp();
            return super.mouseReleased(x, y, button);
        }
    }
}
