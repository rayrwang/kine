package ai.rrw.kine.compat.v26_3;

import ai.rrw.kine.compat.WorldDraw;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.PositionAndRotation;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enderman;
import net.minecraft.world.entity.monster.cubemob.AbstractCubeMob;
import net.minecraft.world.entity.monster.cubemob.MagmaCube;
import net.minecraft.world.entity.monster.cubemob.Slime;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.sdl.SDLMouse;
import org.lwjgl.system.MemoryStack;

/** Minecraft 26.3: as 26.2, and SDL input (keys numbered by scancode, the KEYBOARD key type), EnderMan renamed
 *  Enderman, an entity's interpolation target behind a getter, a living entity's hurt cooldown in a field of its own
 *  (LivingEntity.damageCooldownTime), the swing state behind isSwinging, and BlockState.blocksMotion gone (the
 *  heightmap's motion test is now the blocks_motion_in_heightmap tag). */
public final class EpochImpl extends ai.rrw.kine.compat.f26.Base26 {
    private WorldDraw world;   // made on first use, as the pipelines it registers were before

    @Override public Screen screen(Minecraft mc) { return mc.gui.screen(); }

    @Override public void setScreen(Minecraft mc, Screen screen) { mc.gui.setScreen(screen); }

    @Override public boolean overlayOpen(Minecraft mc) { return mc.gui.overlay() != null; }

    @Override public boolean hudHidden(Minecraft mc) { return mc.gui.hud.isHidden(); }

    @Override public void actionBar(Minecraft mc, Component text) { mc.gui.hud.setOverlayMessage(text, false); }

    @Override public Camera camera(Minecraft mc) { return mc.gameRenderer.mainCamera(); }

    @Override public boolean isKeyboard(InputConstants.Key key) { return key.getType() == InputConstants.Type.KEYBOARD; }

    @Override public boolean keyDown(Minecraft mc, int code) { return InputConstants.isKeyDown(code); }

    @Override
    public boolean mouseDown(Minecraft mc, int button) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            int buttons = SDLMouse.SDL_GetMouseState(stack.mallocFloat(1), stack.mallocFloat(1));
            return button >= 1 && button <= 32 && (buttons & (1 << (button - 1))) != 0;   // SDL_BUTTON_MASK
        }
    }

    @Override public boolean isEnderman(Entity e) { return e instanceof Enderman; }

    /** A slime or a magma cube: from 26.2 each is its own AbstractCubeMob, and a magma cube is no longer a Slime (the
     *  third, the sulfur cube, hurts nothing and is left out). */
    @Override public boolean isSlime(Entity e) { return e instanceof Slime || e instanceof MagmaCube; }

    @Override public boolean isMagmaCube(Entity e) { return e instanceof MagmaCube; }

    @Override public int slimeSize(Entity e) { return isSlime(e) ? ((AbstractCubeMob) e).getSize() : 0; }

    @Override
    public Vec3 interpolationTarget(Entity e) {
        PositionAndRotation t = e.getInterpolation() != null ? e.getInterpolation().target() : null;
        return t != null ? t.position() : e.position();
    }

    @Override public boolean swinging(LivingEntity e) { return e.isSwinging(); }

    /** A living entity's hurt cooldown is its own field from 26.3, LivingEntity.damageCooldownTime (set to 20 by
     *  hurtServer, and on the client by handleDamageEvent); Entity's invulnerableTime, now private, is only a
     *  temporary invulnerability that a few things set (a converted mob keeps its old one, a skeleton trap's horse). */
    @Override
    public int invulnerableTime(Entity e) {
        return e instanceof LivingEntity l ? l.damageCooldownTime : e.getInvulnerableTime();
    }

    @Override public float bounceRestitution(BlockState state) { return state.getBlock().getBounceRestitution(); }

    @Override public boolean suppressesBounce(BlockState state) { return state.is(BlockTags.SUPPRESSES_BOUNCE); }

    @Override public boolean blocksMotion(BlockState state) { return state.is(BlockTags.BLOCKS_MOTION_IN_HEIGHTMAP); }

    @Override public WorldDraw world() { if (world == null) world = new World(); return world; }
}
