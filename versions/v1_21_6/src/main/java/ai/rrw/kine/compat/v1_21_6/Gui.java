package ai.rrw.kine.compat.v1_21_6;

import ai.rrw.kine.compat.Gui2D;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;

/** Gui2D on 1.21.6 to 1.21.8: GuiGraphics, with its older names (drawString for text and so on) and a 2-D transform
 *  stack (Matrix3x2fStack, new at 1.21.6). */
public final class Gui implements Gui2D {
    private final GuiGraphics g;

    public Gui(GuiGraphics g) { this.g = g; }

    @Override public void text(Font font, String text, int x, int y, int color, boolean shadow) { g.drawString(font, text, x, y, color, shadow); }

    @Override public void text(Font font, Component text, int x, int y, int color, boolean shadow) { g.drawString(font, text, x, y, color, shadow); }

    @Override public void text(Font font, FormattedCharSequence text, int x, int y, int color, boolean shadow) { g.drawString(font, text, x, y, color, shadow); }

    @Override public void centeredText(Font font, String text, int x, int y, int color) { g.drawCenteredString(font, text, x, y, color); }

    @Override public void centeredText(Font font, Component text, int x, int y, int color) { g.drawCenteredString(font, text, x, y, color); }

    @Override public void fill(int x0, int y0, int x1, int y1, int color) { g.fill(x0, y0, x1, y1, color); }

    @Override public void outline(int x, int y, int width, int height, int color) { g.renderOutline(x, y, width, height, color); }

    @Override public void fakeItem(ItemStack stack, int x, int y) { g.renderFakeItem(stack, x, y); }

    @Override public int guiWidth() { return g.guiWidth(); }

    @Override public int guiHeight() { return g.guiHeight(); }

    @Override public void enableScissor(int x0, int y0, int x1, int y1) { g.enableScissor(x0, y0, x1, y1); }

    @Override public void disableScissor() { g.disableScissor(); }

    @Override public void push() { g.pose().pushMatrix(); }

    @Override public void pop() { g.pose().popMatrix(); }

    @Override public void translate(float x, float y) { g.pose().translate(x, y); }

    @Override public void scale(float x, float y) { g.pose().scale(x, y); }

    @Override public void rotate(float radians) { g.pose().rotate(radians); }
}
