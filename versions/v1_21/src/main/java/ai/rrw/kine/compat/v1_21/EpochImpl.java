package ai.rrw.kine.compat.v1_21;

import ai.rrw.kine.compat.Compat;
import ai.rrw.kine.compat.HudLayer;
import ai.rrw.kine.compat.WorldDraw;
import ai.rrw.kine.compat.f121.Base121;
import ai.rrw.kine.mixin.v1_21.GameRendererFovInvoker;
import ai.rrw.kine.ui.KineNavScreen;
import ai.rrw.kine.ui.KineSettingsScreen;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.tags.EntityTypeTags;
import net.minecraft.util.Mth;
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
import net.minecraft.world.entity.monster.piglin.PiglinAi;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ThrownPotion;
import net.minecraft.world.item.ElytraItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
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
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Minecraft 1.21 and 1.21.1, the oldest releases kine runs on: no key record in the client's input (its keys are
 * fields, sprint is read from the sprint key itself), the fog set up in FogRenderer with RenderSystem's fog state, a
 * lightmap filled on the CPU, the world drawn with render types and shader instances, and a Fabric API whose HUD has
 * only HudRenderCallback. Compiled against 1.21.1; 1.21 has the same game code for all of it.
 */
public final class EpochImpl extends Base121 {
    private WorldDraw world;   // made on first use, as on the other releases

    // input

    @Override public boolean keyDown(Minecraft mc, int code) { return InputConstants.isKeyDown(mc.getWindow().getWindow(), code); }

    @Override
    public boolean mouseDown(Minecraft mc, int button) {
        return GLFW.glfwGetMouseButton(mc.getWindow().getWindow(), button) == GLFW.GLFW_PRESS;
    }

    // entities and blocks

    /** Where the client is moving the entity to: Entity.lerpTarget (a living entity's lerpX/Y/Z while lerpSteps
     *  lasts; boats and minecarts keep their own), as InterpolationHandler.position on 26.1. */
    @Override
    public Vec3 interpolationTarget(Entity e) {
        return new Vec3(e.lerpTargetX(), e.lerpTargetY(), e.lerpTargetZ());
    }

    // time, the camera

    @Override public float partialTick(Minecraft mc, boolean ignoreFrozen) { return mc.getTimer().getGameTimeDeltaPartialTick(ignoreFrozen); }

    @Override public long overworldTime(ClientLevel level) { return level.getDayTime(); }

    @Override public Vec3 cameraPos(Minecraft mc) { return camera(mc).getPosition(); }

    /** The projection as the frame is drawn (GameRenderer's, at this frame's field of view) times the camera's view
     *  rotation, as 26.1's Camera.getViewRotationProjectionMatrix. */
    @Override
    public Matrix4f viewProjection(Minecraft mc) {
        double fov = ((GameRendererFovInvoker) mc.gameRenderer).kine$getFov(camera(mc), partialTick(mc, true), true);
        Matrix4f view = new Matrix4f().rotation(camera(mc).rotation().conjugate(new Quaternionf()));
        return mc.gameRenderer.getProjectionMatrix(fov).mul(view);
    }

    @Override public int minY(Level level) { return level.getMinBuildHeight(); }

    /** The highest block Y: getMaxBuildHeight is the first Y above the level. */
    @Override public int maxY(Level level) { return level.getMaxBuildHeight() - 1; }

    // keys and options

    /** Kine's section of the controls screen: on these releases a category is its translation key. */
    private static final String CATEGORY = "key.category.kine.keybinds";

    @Override
    public KeyMapping key(String name, String defaultKey) {
        return KeyBindingHelper.registerKeyBinding(new KeyMapping(name, Compat.keyCode(defaultKey), CATEGORY));
    }

    /** No toggle options for attack and use before 1.21.9. */
    @Override public boolean toggleAttack(Options options) { return false; }

    @Override public boolean toggleUse(Options options) { return false; }

    /** No sprint window option: LocalPlayer.aiStep sets sprintTriggerTime to 7. */
    @Override public int sprintWindow(Options options) { return 7; }

    /** MouseHandler.grabMouse, as the inventory screen closes: the keys held are set again, except on macOS. These
     *  releases have no toggle keys to restore. */
    @Override
    public void keysAfterInventoryClose(Minecraft mc) {
        if (!Minecraft.ON_OSX) KeyMapping.setAll();
    }

    /** The keys of the input's fields, as the last tick read them and kine pressed them (KeyboardInputMixin), and the
     *  sprint key as LocalPlayer.aiStep saw it (SprintKeyMixin); the sprint key itself for an input kine didn't read. */
    @Override
    public ai.rrw.kine.compat.Input lastKeys(LocalPlayer p) {
        net.minecraft.client.player.Input i = p.input;
        boolean sprint = i instanceof InputSprint s ? s.kine$sprint() : Minecraft.getInstance().options.keySprint.isDown();
        return new ai.rrw.kine.compat.Input(i.up, i.down, i.left, i.right, i.jumping, i.shiftKeyDown, sprint);
    }

    // the inventory

    @Override public NonNullList<ItemStack> mainItems(Inventory inv) { return inv.items; }

    @Override public int selectedSlot(Inventory inv) { return inv.selected; }

    @Override public void setSelectedSlot(Inventory inv, int slot) { inv.selected = slot; }

    @Override public ItemStack selectedItem(Inventory inv) { return inv.getSelected(); }

    // entities, items, effects, rules

    @Override public Level level(Entity e) { return e.level(); }

    @Override public Vec3 position(Entity e) { return e.position(); }

    @Override public long gameTime(Level level) { return level.getGameTime(); }

    @Override public boolean fixedTime(Level level) { return level.dimensionType().hasFixedTime(); }

    /** Level.updateSkyBrightness of 1.21.1, worked out here: its client sets skyDarken only when the level loads. */
    @Override
    public int skyDarken(Level level) {
        double d = 1.0 - level.getRainLevel(1.0F) * 5.0F / 16.0;
        double e = 1.0 - level.getThunderLevel(1.0F) * 5.0F / 16.0;
        double f = 0.5 + 2.0 * Mth.clamp((double) Mth.cos(level.getTimeOfDay(1.0F) * (float) (Math.PI * 2)), -0.25, 0.25);
        return (int) ((1.0 - f * d * e) * 11.0);
    }

    @Override public boolean isCreative(Player p) { return p.isCreative(); }

    @Override public <T> T component(ItemStack stack, DataComponentType<T> type) { return stack.get(type); }

    @Override public <T> T componentOr(ItemStack stack, DataComponentType<T> type, T def) { return stack.getOrDefault(type, def); }

    @Override
    public Holder<DamageType> damageType(Level level, ResourceKey<DamageType> key) {
        return level.registryAccess().registryOrThrow(Registries.DAMAGE_TYPE).getHolderOrThrow(key);
    }

    @Override public Direction horizontalFacing(BlockState state) { return state.getValue(BlockStateProperties.HORIZONTAL_FACING); }

    @Override public double fallDistance(Entity e) { return e.fallDistance; }

    @Override public boolean creeperPowered(Creeper c) { return c.isPowered(); }

    @Override
    public boolean ownedBy(TamableAnimal animal, Entity self) {
        UUID owner = animal.getOwnerUUID();
        return owner != null && owner.equals(self.getUUID());
    }

    /** The parts of every dragon the client has. These releases keep no list of them: Level.getEntities adds a
     *  dragon's parts as its box query meets the dragon. */
    @Override
    public List<EnderDragonPart> dragonParts(ClientLevel level) {
        List<EnderDragonPart> parts = new ArrayList<>();
        for (Entity e : level.entitiesForRendering())
            if (e instanceof EnderDragon dragon) Collections.addAll(parts, dragon.getSubEntities());
        return parts;
    }

    @Override public boolean deflectsProjectiles(Entity e) { return e.getType().is(EntityTypeTags.DEFLECTS_PROJECTILES); }

    /** No creaking before 1.21.2. */
    @Override public boolean heartBoundCreaking(Entity e) { return false; }

    /** Splash and lingering potions are one class here (the item says which). */
    @Override public ItemStack thrownPotion(Entity e) { return e instanceof ThrownPotion pot ? pot.getItem() : null; }

    // LivingEntity.isDamageSourceBlocked: a shield raised for 5 ticks (isBlocking) stops an unpiercing arrow whose
    // damage type doesn't bypass shields, coming from anywhere in front of where the holder's head faces (the arrow's
    // position as it hits, horizontally; the shooter's position stands in for it).
    @Override
    public boolean shieldStops(LivingEntity le, Vec3 from, Holder<DamageType> type) {
        if (type.is(DamageTypeTags.BYPASSES_SHIELD) || !le.isBlocking()) return false;
        Vec3 view = Compat.viewVector(0.0F, le.getYHeadRot());
        Vec3 to = from.vectorTo(le.position());
        to = new Vec3(to.x, 0.0, to.z).normalize();
        return to.dot(view) < 0.0;
    }

    /** BucketItem.emptyContents: water evaporates in an ultrawarm dimension (the Nether). */
    @Override public boolean waterEvaporates(Level level, BlockPos pos) { return level.dimensionType().ultraWarm(); }

    /** LocalPlayer.hasEnoughFoodToStartSprinting's food part (FoodData.hasEnoughFood of 26.1). */
    @Override public boolean enoughFoodToSprint(Player p) { return p.getFoodData().getFoodLevel() > 6.0F; }

    /** MobEffectUtil.hasWaterBreathing. */
    @Override
    public List<Holder<MobEffect>> breathingEffects() {
        return List.of(MobEffects.WATER_BREATHING, MobEffects.CONDUIT_POWER);
    }

    /** No spears before 1.21.11. */
    @Override public boolean piercingWeapon(ItemStack stack) { return false; }

    @Override public boolean piglinSafeArmor(LivingEntity e) { return PiglinAi.isWearingGold(e); }

    /** ItemStack.nextDamageWillBreak of later releases, which these lack. */
    @Override
    public boolean nextDamageWillBreak(ItemStack stack) {
        return stack.isDamageableItem() && stack.getDamageValue() >= stack.getMaxDamage() - 1;
    }

    /** Only the elytra glides here (Player.tryToStartFallFlying, LivingEntity.updateFallFlying). */
    @Override public boolean isGlider(ItemStack stack) { return stack.is(Items.ELYTRA); }

    /** An elytra worn on the chest with more than one point of durability left (ElytraItem.isFlyEnabled). */
    @Override
    public boolean glidesWith(ItemStack stack, EquipmentSlot slot) {
        return slot == EquipmentSlot.CHEST && stack.is(Items.ELYTRA) && ElytraItem.isFlyEnabled(stack);
    }

    /** What 26.1's EntityCollisionContext.getCollisionShape does. */
    @Override
    public VoxelShape collisionShape(CollisionContext ctx, BlockState state, Level level, BlockPos pos) {
        return state.getCollisionShape(level, pos, ctx);
    }

    // drawing

    @Override public WorldDraw world() { if (world == null) world = new World(); return world; }

    @Override public void hud(String name, HudLayer layer) { Hud.add(name, layer); }

    @Override public void hudAfter(String after, String name, HudLayer layer) { Hud.addAfter(after, name, layer); }

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
