package ai.rrw.kine.util;

import net.minecraft.world.item.ItemStack;

import java.util.function.IntFunction;

/** Hotbar slot choice for features that move an item into the hotbar. */
public final class Hotbar {
    private Hotbar() {}

    /** The hotbar slot to put an item into: the first empty slot, else the slot with the largest stack,
     *  taken as the least valuable to move out (RW's rule). `stackAt` gives the stack in slot 0 to 8. */
    public static int slotToDisplace(IntFunction<ItemStack> stackAt) {
        int target = 0, biggest = -1;
        for (int hb = 0; hb < 9; hb++) {
            ItemStack st = stackAt.apply(hb);
            if (st.isEmpty()) return hb;
            if (st.getCount() > biggest) { biggest = st.getCount(); target = hb; }
        }
        return target;
    }
}
