package ai.rrw.kine.compat.v1_21;

import ai.rrw.kine.compat.Gui2D;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** Gui2D on 1.21 and 1.21.1: GuiGraphics with its older names (drawString for text and so on), whose transform is a
 *  3-D PoseStack: the 2-D moves are made in its x-y plane. As on 1.21.2 to 1.21.5: text whose alpha is under 4 is left
 *  out (Font.adjustColor draws it opaque, where 26.1 draws nothing at alpha 0), and a scissor rectangle is moved with
 *  the pose here (26.1's and 1.21.4's enableScissor move it; 1.21.1's takes it as given). */
public final class Gui implements Gui2D {
    private final GuiGraphics g;

    public Gui(GuiGraphics g) { this.g = g; }

    private static boolean shows(int color) { return (color & 0xFC000000) != 0; }

    @Override public void text(Font font, String text, int x, int y, int color, boolean shadow) { if (shows(color)) g.drawString(font, text, x, y, color, shadow); }

    @Override public void text(Font font, Component text, int x, int y, int color, boolean shadow) { if (shows(color)) g.drawString(font, text, x, y, color, shadow); }

    @Override public void text(Font font, FormattedCharSequence text, int x, int y, int color, boolean shadow) { if (shows(color)) g.drawString(font, text, x, y, color, shadow); }

    @Override public void centeredText(Font font, String text, int x, int y, int color) { if (shows(color)) g.drawCenteredString(font, text, x, y, color); }

    @Override public void centeredText(Font font, Component text, int x, int y, int color) { if (shows(color)) g.drawCenteredString(font, text, x, y, color); }

    @Override public void fill(int x0, int y0, int x1, int y1, int color) { g.fill(x0, y0, x1, y1, color); }

    @Override public void outline(int x, int y, int width, int height, int color) { g.renderOutline(x, y, width, height, color); }

    @Override public void fakeItem(ItemStack stack, int x, int y) { g.renderFakeItem(stack, x, y); }

    @Override public int guiWidth() { return g.guiWidth(); }

    @Override public int guiHeight() { return g.guiHeight(); }

    @Override
    public void enableScissor(int x0, int y0, int x1, int y1) {
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

    /** About the screen's z axis, which turns x toward y as Matrix3x2f.rotate does on later releases. */
    @Override public void rotate(float radians) { g.pose().mulPose(new Quaternionf().rotationZ(radians)); }
}
