package ai.rrw.kine.compat.v1_21;

import ai.rrw.kine.compat.HudLayer;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphics;

import java.util.ArrayList;
import java.util.List;

/**
 * Kine's HUD elements on 1.21 and 1.21.1. Their Fabric API has no HUD element registry (only HudRenderCallback, which
 * draws over the whole HUD, chat and debug screen included), so kine keeps its elements here, in the order Fabric's
 * HudElementRegistry.attachElementAfter gives them on later releases, and HudMixin draws them right after the game's
 * camera overlays (Gui.renderCameraOverlays: vignette, spyglass, pumpkin, frost, portal; Fabric's MISC_OVERLAYS),
 * before the crosshair, the hotbar and the rest. That layer is drawn only while the HUD shows (not with F1).
 */
public final class Hud {
    private Hud() {}

    private record Element(String name, HudLayer layer) {}

    private static final List<Element> ELEMENTS = new ArrayList<>();

    /** Right after the camera overlays, so before the elements added earlier. */
    public static void add(String name, HudLayer layer) {
        unique(name);
        ELEMENTS.add(0, new Element(name, layer));
    }

    /** Right after another of kine's elements. */
    public static void addAfter(String after, String name, HudLayer layer) {
        unique(name);
        for (int i = 0; i < ELEMENTS.size(); i++) {
            if (ELEMENTS.get(i).name().equals(after)) {
                ELEMENTS.add(i + 1, new Element(name, layer));
                return;
            }
        }
        throw new IllegalArgumentException("kine: no HUD element " + after + " to add " + name + " after");
    }

    private static void unique(String name) {
        for (Element e : ELEMENTS)
            if (e.name().equals(name)) throw new IllegalArgumentException("kine: HUD element " + name + " already exists");
    }

    /** Every element, in order (HudMixin, each frame the HUD shows). */
    public static void draw(GuiGraphics g, DeltaTracker delta) {
        if (ELEMENTS.isEmpty()) return;
        Gui gui = new Gui(g);
        for (Element e : ELEMENTS) e.layer().draw(gui, delta);
    }
}
