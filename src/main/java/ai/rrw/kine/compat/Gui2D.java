package ai.rrw.kine.compat;

import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;

/**
 * Drawing on the screen, the same on every release (versions/README.md): the HUD's elements and kine's screens draw
 * through this, and each family's adapter draws with that release's own GUI graphics (26.x: GuiGraphicsExtractor;
 * 1.21.x: GuiGraphics, whose method names and transform stack changed several times). The method names are 26.1's.
 */
public interface Gui2D {
    void text(Font font, String text, int x, int y, int color, boolean shadow);

    void text(Font font, Component text, int x, int y, int color, boolean shadow);

    void text(Font font, FormattedCharSequence text, int x, int y, int color, boolean shadow);

    /** With a shadow. */
    default void text(Font font, String text, int x, int y, int color) { text(font, text, x, y, color, true); }

    default void text(Font font, Component text, int x, int y, int color) { text(font, text, x, y, color, true); }

    default void text(Font font, FormattedCharSequence text, int x, int y, int color) { text(font, text, x, y, color, true); }

    /** Centred on x, with a shadow. */
    void centeredText(Font font, String text, int x, int y, int color);

    void centeredText(Font font, Component text, int x, int y, int color);

    /** A filled rectangle, corners (x0, y0) and (x1, y1). */
    void fill(int x0, int y0, int x1, int y1, int color);

    /** A one-pixel rectangle outline. */
    void outline(int x, int y, int width, int height, int color);

    /** An item's icon, as in the inventory. */
    void fakeItem(ItemStack stack, int x, int y);

    int guiWidth();

    int guiHeight();

    void enableScissor(int x0, int y0, int x1, int y1);

    void disableScissor();

    // the transform (2-D: a translate, a scale, a rotation in radians), saved and restored in pairs

    void push();

    void pop();

    void translate(float x, float y);

    void scale(float x, float y);

    void rotate(float radians);
}
