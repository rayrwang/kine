package ai.rrw.kine.compat;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;

/** A mob's kind by its id ("happy_ghast", "parched"), for the kinds some releases lack: the class can't be named in
 *  code that runs on a release without it, but the id simply never matches there. */
public final class Mobs {
    private Mobs() {}

    public static boolean is(Entity e, String id) {
        return BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath().equals(id);
    }
}
