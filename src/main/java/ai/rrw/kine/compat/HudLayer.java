package ai.rrw.kine.compat;

import net.minecraft.client.DeltaTracker;

/** One of kine's HUD elements: drawn every frame the HUD shows ({@link Compat#hud}). */
@FunctionalInterface
public interface HudLayer {
    void draw(Gui2D g, DeltaTracker delta);
}
