package ai.rrw.kine.compat.v26_2;

import ai.rrw.kine.compat.WorldDraw;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.entity.monster.cubemob.MagmaCube;
import net.minecraft.world.entity.monster.cubemob.Slime;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

/** Minecraft 26.2: the screen, the overlay and the HUD (now Hud) moved into Gui (mc.gui), the main camera's getter
 *  renamed, slimes and magma cubes moved to monster.cubemob. */
public final class EpochImpl extends ai.rrw.kine.compat.f26.Base26 {
    private WorldDraw world;   // made on first use, as the pipelines it registers were before

    @Override public Screen screen(Minecraft mc) { return mc.gui.screen(); }

    @Override public void setScreen(Minecraft mc, Screen screen) { mc.gui.setScreen(screen); }

    @Override public boolean overlayOpen(Minecraft mc) { return mc.gui.overlay() != null; }

    @Override public boolean hudHidden(Minecraft mc) { return mc.gui.hud.isHidden(); }

    @Override public void actionBar(Minecraft mc, Component text) { mc.gui.hud.setOverlayMessage(text, false); }

    @Override public Camera camera(Minecraft mc) { return mc.gameRenderer.mainCamera(); }

    @Override public boolean isKeyboard(InputConstants.Key key) { return key.getType() == InputConstants.Type.KEYSYM; }

    @Override public boolean keyDown(Minecraft mc, int code) { return InputConstants.isKeyDown(mc.getWindow(), code); }

    @Override
    public boolean mouseDown(Minecraft mc, int button) {
        return GLFW.glfwGetMouseButton(mc.getWindow().handle(), button) == GLFW.GLFW_PRESS;
    }

    @Override public boolean isEnderman(Entity e) { return e instanceof EnderMan; }

    @Override public boolean isSlime(Entity e) { return e instanceof Slime; }

    @Override public boolean isMagmaCube(Entity e) { return e instanceof MagmaCube; }

    @Override public int slimeSize(Entity e) { return e instanceof Slime s ? s.getSize() : 0; }

    @Override
    public Vec3 interpolationTarget(Entity e) {
        return e.getInterpolation() != null ? e.getInterpolation().position() : e.position();
    }

    @Override public boolean swinging(LivingEntity e) { return e.swinging; }

    @Override public int invulnerableTime(Entity e) { return e.invulnerableTime; }

    @Override public float bounceRestitution(BlockState state) { return state.getBlock().getBounceRestitution(); }

    @Override public boolean suppressesBounce(BlockState state) { return state.is(net.minecraft.tags.BlockTags.SUPPRESSES_BOUNCE); }

    @Override public boolean blocksMotion(BlockState state) { return state.blocksMotion(); }

    @Override public WorldDraw world() { if (world == null) world = new World(); return world; }
}
