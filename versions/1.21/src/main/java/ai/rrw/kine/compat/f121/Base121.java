package ai.rrw.kine.compat.f121;

import ai.rrw.kine.Kine;
import ai.rrw.kine.compat.Epoch;
import ai.rrw.kine.compat.Packs;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.ResourcePackActivationType;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.entity.monster.MagmaCube;
import net.minecraft.world.entity.monster.Slime;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * What every 1.21.x release does the same way (versions/README.md, the family folder): the Epoch methods whose code
 * compiles and links identically on 1.21 to 1.21.11, which the version check proves, as it does for the shared code.
 * Each 1.21.x epoch's EpochImpl extends this and adds the rest. Written with the newest names, like the shared code
 * (versions/renames.txt translates them).
 */
public abstract class Base121 implements Epoch {
    // screens and the HUD

    @Override public Screen screen(Minecraft mc) { return mc.screen; }

    @Override public void setScreen(Minecraft mc, Screen screen) { mc.setScreen(screen); }

    @Override public boolean overlayOpen(Minecraft mc) { return mc.getOverlay() != null; }

    @Override public boolean hudHidden(Minecraft mc) { return mc.options.hideGui; }

    @Override public void actionBar(Minecraft mc, Component text) { mc.gui.setOverlayMessage(text, false); }

    @Override public Camera camera(Minecraft mc) { return mc.gameRenderer.getMainCamera(); }

    // input

    @Override public boolean isKeyboard(InputConstants.Key key) { return key.getType() == InputConstants.Type.KEYSYM; }

    // entities and blocks

    @Override public boolean isEnderman(Entity e) { return e instanceof EnderMan; }

    @Override public boolean isSlime(Entity e) { return e instanceof Slime; }

    @Override public boolean isMagmaCube(Entity e) { return e instanceof MagmaCube; }

    @Override public int slimeSize(Entity e) { return e instanceof Slime s ? s.getSize() : 0; }

    @Override public boolean swinging(LivingEntity e) { return e.swinging; }

    @Override public int invulnerableTime(Entity e) { return e.invulnerableTime; }

    /** No bounce restitution before 26.2 (Rules.BOUNCE_RESTITUTION). */
    @Override public float bounceRestitution(BlockState state) { return 0.0F; }

    @Override public boolean suppressesBounce(BlockState state) { return false; }

    @Override public boolean blocksMotion(BlockState state) { return state.blocksMotion(); }

    // time, the camera


    // more entities, items, effects


    /** 1.21 and 1.21.1 work it out themselves (their client doesn't update it). */
    @Override public int skyDarken(Level level) { return level.getSkyDarken(); }

    @Override public void pick(Minecraft mc, float partialTick) { mc.gameRenderer.pick(partialTick); }

    @Override
    public String builtinPack(String name, String title, boolean onByDefault) {
        ModContainer c = FabricLoader.getInstance().getModContainer(Kine.MOD_ID).orElseThrow();
        Identifier id = id(Packs.folder(c, name));
        ResourceManagerHelper.registerBuiltinResourcePack(id, c, Component.literal(title),
            onByDefault ? ResourcePackActivationType.DEFAULT_ENABLED : ResourcePackActivationType.NORMAL);
        return id.toString();
    }

    /** One of kine's ids. */
    public static Identifier id(String name) { return Identifier.fromNamespaceAndPath(Kine.MOD_ID, name); }
}
