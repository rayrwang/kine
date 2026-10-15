package ai.rrw.kine.compat.v1_21_2;

import ai.rrw.kine.compat.GameVersion;
import ai.rrw.kine.compat.Gui2D;
import com.mojang.math.Axis;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/** Gui2D on 1.21.2 to 1.21.4: GuiGraphics with its older names (drawString for text and so on) and a 3-D pose stack,
 *  of which kine uses the plane z = 0. */
public final class Gui implements Gui2D {
    /** 1.21.4's GuiGraphics.enableScissor moves the rectangle with the pose, as 26.1's does; 1.21.2's and 1.21.3's take
     *  it as given, so it is moved here first. */
    private static final boolean SCISSOR_FOLLOWS_POSE = GameVersion.atLeast("1.21.4");

    private final GuiGraphics g;

    public Gui(GuiGraphics g) { this.g = g; }

    // Text: 26.1 draws a colour's alpha as it is and nothing at alpha 0, while these releases' Font draws a colour whose
    // alpha is under 4 fully opaque (Font.adjustColor, for colours given without alpha). Text that faint is left out.

    private static boolean shows(int color) { return (color & 0xFC000000) != 0; }

    @Override
    public void text(Font font, String text, int x, int y, int color, boolean shadow) {
        if (shows(color)) g.drawString(font, text, x, y, color, shadow);
    }

    @Override
    public void text(Font font, Component text, int x, int y, int color, boolean shadow) {
        if (shows(color)) g.drawString(font, text, x, y, color, shadow);
    }

    @Override
    public void text(Font font, FormattedCharSequence text, int x, int y, int color, boolean shadow) {
        if (shows(color)) g.drawString(font, text, x, y, color, shadow);
    }

    @Override
    public void centeredText(Font font, String text, int x, int y, int color) {
        if (shows(color)) g.drawCenteredString(font, text, x, y, color);
    }

    @Override
    public void centeredText(Font font, Component text, int x, int y, int color) {
        if (shows(color)) g.drawCenteredString(font, text, x, y, color);
    }

    @Override public void fill(int x0, int y0, int x1, int y1, int color) { g.fill(x0, y0, x1, y1, color); }

    @Override public void outline(int x, int y, int width, int height, int color) { g.renderOutline(x, y, width, height, color); }

    @Override public void fakeItem(ItemStack stack, int x, int y) { g.renderFakeItem(stack, x, y); }

    @Override public int guiWidth() { return g.guiWidth(); }

    @Override public int guiHeight() { return g.guiHeight(); }

    @Override
    public void enableScissor(int x0, int y0, int x1, int y1) {
        if (SCISSOR_FOLLOWS_POSE) { g.enableScissor(x0, y0, x1, y1); return; }
        // ScreenRectangle.transformAxisAligned of 1.21.4: the corners through the pose, rounded down
        Matrix4f m = g.pose().last().pose();
        Vector3f a = m.transformPosition(x0, y0, 0.0F, new Vector3f());
        Vector3f b = m.transformPosition(x1, y1, 0.0F, new Vector3f());
        int left = Mth.floor(a.x), top = Mth.floor(a.y);
        g.enableScissor(left, top, left + Mth.floor(b.x - a.x), top + Mth.floor(b.y - a.y));
    }

    @Override public void disableScissor() { g.disableScissor(); }

    @Override public void push() { g.pose().pushPose(); }

    @Override public void pop() { g.pose().popPose(); }

    @Override public void translate(float x, float y) { g.pose().translate(x, y, 0.0F); }

    @Override public void scale(float x, float y) { g.pose().scale(x, y, 1.0F); }

    /** About the screen's z axis: with y pointing down the screen, a positive angle turns clockwise, as 26.1's 2-D
     *  rotate does. */
    @Override public void rotate(float radians) { g.pose().mulPose(Axis.ZP.rotation(radians)); }
}
