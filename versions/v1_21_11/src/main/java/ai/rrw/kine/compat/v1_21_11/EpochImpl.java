package ai.rrw.kine.compat.v1_21_11;

import ai.rrw.kine.compat.Compat;
import ai.rrw.kine.compat.HudLayer;
import ai.rrw.kine.compat.WorldDraw;
import ai.rrw.kine.compat.f121.Base121;
import ai.rrw.kine.mixin.v1_21_11.GameRendererFovInvoker;
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
import net.minecraft.client.input.InputQuirks;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
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
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityReference;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.boss.enderdragon.EnderDragonPart;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.creaking.Creaking;
import net.minecraft.world.entity.monster.piglin.PiglinAi;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.throwableitemprojectile.AbstractThrownPotion;
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

/** Minecraft 1.21.11: close to 26.1 (its GUI is GuiGraphics, its HUD, keys and world drawing Fabric's older APIs). */
public final class EpochImpl extends Base121 {
    private WorldDraw world;   // made on first use, as the pipelines it registers were before

    // input

    @Override public boolean keyDown(Minecraft mc, int code) { return InputConstants.isKeyDown(mc.getWindow(), code); }

    @Override
    public boolean mouseDown(Minecraft mc, int button) {
        return GLFW.glfwGetMouseButton(mc.getWindow().handle(), button) == GLFW.GLFW_PRESS;
    }

    // entities and blocks

    @Override
    public Vec3 interpolationTarget(Entity e) {
        return e.getInterpolation() != null ? e.getInterpolation().position() : e.position();
    }

    // time, the camera

    @Override public float partialTick(Minecraft mc, boolean ignoreFrozen) { return mc.getDeltaTracker().getGameTimeDeltaPartialTick(ignoreFrozen); }

    @Override public long overworldTime(ClientLevel level) { return level.getDayTime(); }

    @Override public Vec3 cameraPos(Minecraft mc) { return camera(mc).position(); }

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

    /** Kine's section of the controls screen ("key.category.kine.keybinds" in the lang file), made with the first key. */
    private static KeyMapping.Category category;

    @Override
    public KeyMapping key(String name, String defaultKey) {
        if (category == null) category = KeyMapping.Category.register(id("keybinds"));
        return KeyBindingHelper.registerKeyBinding(new KeyMapping(name, Compat.keyCode(defaultKey), category));
    }

    @Override public boolean toggleAttack(Options options) { return options.toggleAttack().get(); }

    @Override public boolean toggleUse(Options options) { return options.toggleUse().get(); }

    @Override public int sprintWindow(Options options) { return options.sprintWindow().get(); }

    @Override
    public void keysAfterInventoryClose(Minecraft mc) {
        if (InputQuirks.RESTORE_KEY_STATE_AFTER_MOUSE_GRAB) KeyMapping.setAll();
        KeyMapping.restoreToggleStatesOnScreenClosed();
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

    @Override
    public boolean waterEvaporates(Level level, BlockPos pos) {
        Boolean e = (Boolean) level.environmentAttributes().getValue(EnvironmentAttributes.WATER_EVAPORATES, pos);
        return e != null && e;
    }

    @Override public boolean enoughFoodToSprint(Player p) { return p.getFoodData().hasEnoughFood(); }

    @Override
    public List<Holder<MobEffect>> breathingEffects() {
        return List.of(MobEffects.WATER_BREATHING, MobEffects.CONDUIT_POWER, MobEffects.BREATH_OF_THE_NAUTILUS);
    }

    @Override public boolean piercingWeapon(ItemStack stack) { return stack.has(DataComponents.PIERCING_WEAPON); }

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

    private static final class Nav extends KineNavScreen {
        @Override
        public void render(GuiGraphics g, int mouseX, int mouseY, float partial) {
            renderScreen(new Gui(g), mouseX, mouseY, partial, () -> super.render(g, mouseX, mouseY, partial));
        }

        @Override
        public boolean keyPressed(KeyEvent event) {
            return keyDown(event.key()) || super.keyPressed(event);
        }

        @Override
        public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
            return mouseDown(event.x(), event.y(), event.button()) || super.mouseClicked(event, doubleClick);
        }

        @Override
        public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
            return mouseDrag(event.x(), event.y()) || super.mouseDragged(event, dragX, dragY);
        }

        @Override
        public boolean mouseReleased(MouseButtonEvent event) {
            mouseUp();
            return super.mouseReleased(event);
        }
    }
}
