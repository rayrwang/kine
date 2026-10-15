package ai.rrw.kine.compat.v1_21_6;

import ai.rrw.kine.compat.Compat;
import ai.rrw.kine.compat.HudLayer;
import ai.rrw.kine.compat.WorldDraw;
import ai.rrw.kine.compat.f121.Base121;
import ai.rrw.kine.mixin.v1_21_6.GameRendererFovInvoker;
import ai.rrw.kine.ui.KineNavScreen;
import ai.rrw.kine.ui.KineSettingsScreen;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
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
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.EntityTypeTags;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityReference;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.boss.EnderDragonPart;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.creaking.Creaking;
import net.minecraft.world.entity.monster.piglin.PiglinAi;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.AbstractThrownPotion;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.BlocksAttacks;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.lwjgl.glfw.GLFW;

import java.util.List;

/**
 * Minecraft 1.21.6 to 1.21.8 (compiled against 1.21.8; 1.21.6 and 1.21.7 have the same API for kine): GuiGraphics
 * with a 2-D transform stack, Fabric's HUD element registry and WorldRenderEvents, the screens' input methods with
 * plain key codes and mouse positions, the window handle as a long, key categories as strings, and no toggle-attack,
 * toggle-use or sprint-window options.
 */
public final class EpochImpl extends Base121 {
    private WorldDraw world;   // made on first use, as the pipelines it registers were before

    // input

    @Override public boolean keyDown(Minecraft mc, int code) { return InputConstants.isKeyDown(mc.getWindow().getWindow(), code); }

    @Override
    public boolean mouseDown(Minecraft mc, int button) {
        return GLFW.glfwGetMouseButton(mc.getWindow().getWindow(), button) == GLFW.GLFW_PRESS;
    }

    // entities and blocks

    @Override
    public Vec3 interpolationTarget(Entity e) {
        return e.getInterpolation() != null ? e.getInterpolation().position() : e.position();
    }

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

    /** Kine's section of the controls screen, a translation key before 1.21.9 (the lang file names it "Kine"). */
    private static final String CATEGORY = "key.category.kine.keybinds";

    @Override
    public KeyMapping key(String name, String defaultKey) {
        return KeyBindingHelper.registerKeyBinding(new KeyMapping(name, Compat.keyCode(defaultKey), CATEGORY));
    }

    /** No toggle options for attack and use before 1.21.9: each acts only while its key is held. */
    @Override public boolean toggleAttack(Options options) { return false; }

    @Override public boolean toggleUse(Options options) { return false; }

    /** The double-tap window for a sprint is a fixed 7 ticks before 1.21.9 (LocalPlayer.aiStep sets sprintTriggerTime
     *  to 7), the default of the option that replaced it. */
    @Override public int sprintWindow(Options options) { return 7; }

    /** MouseHandler.grabMouse, as setScreen(null) calls it: the held keys read again, except on macOS. No toggle keys
     *  are restored before 1.21.9 (setScreen had no restoreToggleStatesOnScreenClosed). */
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

    // the inventory

    @Override public NonNullList<ItemStack> mainItems(Inventory inv) { return inv.getNonEquipmentItems(); }

    @Override public int selectedSlot(Inventory inv) { return inv.getSelectedSlot(); }

    @Override public void setSelectedSlot(Inventory inv, int slot) { inv.setSelectedSlot(slot); }

    @Override public ItemStack selectedItem(Inventory inv) { return inv.getSelectedItem(); }

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

    @Override public double fallDistance(Entity e) { return e.fallDistance; }

    @Override public boolean creeperPowered(Creeper c) { return c.isPowered(); }

    @Override
    public boolean ownedBy(TamableAnimal animal, Entity self) {
        EntityReference<LivingEntity> owner = animal.getOwnerReference();
        return owner != null && owner.getUUID().equals(self.getUUID());
    }

    @Override public List<EnderDragonPart> dragonParts(ClientLevel level) { return level.dragonParts(); }

    @Override public boolean deflectsProjectiles(Entity e) { return e.getType().is(EntityTypeTags.DEFLECTS_PROJECTILES); }

    @Override public boolean heartBoundCreaking(Entity e) { return e instanceof Creaking c && c.isHeartBound(); }

    @Override public ItemStack thrownPotion(Entity e) { return e instanceof AbstractThrownPotion pot ? pot.getItem() : null; }

    // LivingEntity.applyItemBlocking: a raised shield, past its block delay, stops an unpiercing arrow coming from
    // within its blocking angle of where the holder's head faces. The game measures that angle to the arrow's
    // position as it hits; the shooter's position stands in for it.
    @Override
    public boolean shieldStops(LivingEntity le, Vec3 from, Holder<DamageType> type) {
        ItemStack blocking = le.getItemBlockingWith();
        if (blocking == null) return false;
        BlocksAttacks ba = blocking.get(DataComponents.BLOCKS_ATTACKS);
        if (ba == null || ba.bypassedBy().map(type::is).orElse(false)) return false;   // a tag here (26.1: a holder set)
        Vec3 view = Compat.viewVector(0.0F, le.getYHeadRot());
        Vec3 to = from.subtract(le.position());
        to = new Vec3(to.x, 0.0, to.z).normalize();
        double angle = Math.acos(Math.max(-1.0, Math.min(1.0, to.dot(view))));
        // BlocksAttacks.DamageReduction.resolve: a reduction blocks base + factor * damage, so factor 1 stops all
        for (BlocksAttacks.DamageReduction r : ba.damageReductions())
            if (angle <= Math.toRadians(r.horizontalBlockingAngle()) && r.factor() >= 1.0F
                && r.type().map(t -> t.contains(type)).orElse(true)) return true;
        return false;
    }

    /** BucketItem.emptyContents: water poured in an ultra-warm dimension (the Nether) evaporates; before 1.21.11 a
     *  flag of the dimension type rather than an environment attribute, so the same everywhere in it. */
    @Override public boolean waterEvaporates(Level level, BlockPos pos) { return level.dimensionType().ultraWarm(); }

    /** LocalPlayer.hasEnoughFoodToSprint's hunger part (26.1's FoodData.hasEnoughFood): more than 6 food. */
    @Override public boolean enoughFoodToSprint(Player p) { return p.getFoodData().getFoodLevel() > 6.0F; }

    /** MobEffectUtil.hasWaterBreathing: no Breath of the Nautilus before 1.21.11. */
    @Override
    public List<Holder<MobEffect>> breathingEffects() {
        return List.of(MobEffects.WATER_BREATHING, MobEffects.CONDUIT_POWER);
    }

    /** No spear (the piercing weapon component) before 1.21.11. */
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

    @Override
    public void hud(String name, HudLayer layer) {
        HudElementRegistry.attachElementAfter(VanillaHudElements.MISC_OVERLAYS, id(name),
            (g, delta) -> layer.draw(new Gui(g), delta));
    }

    @Override
    public void hudAfter(String after, String name, HudLayer layer) {
        HudElementRegistry.attachElementAfter(id(after), id(name), (g, delta) -> layer.draw(new Gui(g), delta));
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

    /** The input methods before 1.21.9 take the key code and the mouse position and button as plain values. */
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
