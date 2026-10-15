package ai.rrw.kine.compat.v1_21_5;

import ai.rrw.kine.compat.Gui2D;
import com.mojang.math.Axis;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;

/**
 * Gui2D on 1.21.5: GuiGraphics, with its older names (drawString for text and so on) and a 3-D pose stack, which
 * kine uses in the screen's plane (z untouched). Text: the Font of 1.21.5 draws a colour whose alpha is below 4 as
 * opaque (Font.adjustColor), where 26.1 draws nothing for alpha 0 and a faint trace below 4; so such text is skipped,
 * and a fade ends in nothing as on 26.1, not in a flash of opaque text.
 */
public final class Gui implements Gui2D {
    private final GuiGraphics g;

    public Gui(GuiGraphics g) { this.g = g; }

    /** Text with this colour shows (see the class comment). */
    private static boolean visible(int color) { return (color & 0xFC000000) != 0; }

    @Override
    public void text(Font font, String text, int x, int y, int color, boolean shadow) {
        if (visible(color)) g.drawString(font, text, x, y, color, shadow);
    }

    @Override
    public void text(Font font, Component text, int x, int y, int color, boolean shadow) {
        if (visible(color)) g.drawString(font, text, x, y, color, shadow);
    }

    @Override
    public void text(Font font, FormattedCharSequence text, int x, int y, int color, boolean shadow) {
        if (visible(color)) g.drawString(font, text, x, y, color, shadow);
    }

    @Override
    public void centeredText(Font font, String text, int x, int y, int color) {
        if (visible(color)) g.drawCenteredString(font, text, x, y, color);
    }

    @Override
    public void centeredText(Font font, Component text, int x, int y, int color) {
        if (visible(color)) g.drawCenteredString(font, text, x, y, color);
    }

    @Override public void fill(int x0, int y0, int x1, int y1, int color) { g.fill(x0, y0, x1, y1, color); }

    @Override public void outline(int x, int y, int width, int height, int color) { g.renderOutline(x, y, width, height, color); }

    @Override public void fakeItem(ItemStack stack, int x, int y) { g.renderFakeItem(stack, x, y); }

    @Override public int guiWidth() { return g.guiWidth(); }

    @Override public int guiHeight() { return g.guiHeight(); }

    @Override public void enableScissor(int x0, int y0, int x1, int y1) { g.enableScissor(x0, y0, x1, y1); }

    @Override public void disableScissor() { g.disableScissor(); }

    @Override public void push() { g.pose().pushPose(); }

    @Override public void pop() { g.pose().popPose(); }

    @Override public void translate(float x, float y) { g.pose().translate(x, y, 0.0F); }

    @Override public void scale(float x, float y) { g.pose().scale(x, y, 1.0F); }

    /** About the screen's normal, as the 2-D stack of 26.1 turns (x toward y). */
    @Override public void rotate(float radians) { g.pose().mulPose(Axis.ZP.rotation(radians)); }
}
