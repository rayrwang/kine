package ai.rrw.kine.compat.v1_21_2;

import ai.rrw.kine.Settings;
import com.mojang.blaze3d.shaders.FogShape;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.FogParameters;
import net.minecraft.client.renderer.FogRenderer;
import net.minecraft.tags.BiomeTags;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.FogType;
import org.joml.Vector4f;

/**
 * The Visibility settings: fog (water, lava, powder snow, the Nether, a boss fight, the render-distance fade), the
 * Darkness and Blindness effects, and the fire, underwater, pumpkin, frost and portal overlays taken away. All of it
 * is the client's own drawing (the game's fog, lightmap, sky and screen overlays), so the server sees nothing.
 *
 * <p>Before 1.21.6 the game has no fog environments: FogRenderer.setupFog makes one fog (a start, an end and a shape)
 * per frame for the terrain and one for the sky, from the first case that holds of lava, powder snow, Blindness or
 * Darkness, water, a foggy place (the Nether, or a boss bar's world fog), and clear air (the render-distance fade).
 * 26.1 picks its environment in the same order, and keeps the render-distance fade apart from the environment's fog;
 * kine clears an environment there by leaving only that fade. Here FogRendererMixin asks {@link #fog} first, which
 * gives the same result in this release's terms; the mixins in ai.rrw.kine.mixin.v1_21_2 do the rest (the effects'
 * fog functions, the foggy flag, the lightmap, the sky). No rain fog has nothing to take away: rain pulls the fog in
 * only from 1.21.6.
 */
public final class Fog {
    private Fog() {}

    /** The fog for this frame with kine's settings, or null where the game's own is the same (FogRenderer.setupFog's
     *  arguments; the foggy flag is already without what kine clears, LevelRendererFogMixin). With the game's fog
     *  switched off (its debug toggle) the game's no fog stays. */
    public static FogParameters fog(boolean fogEnabled, Camera camera, FogRenderer.FogMode mode, Vector4f color, float renderDistance,
                                    boolean foggy) {
        boolean terrain = mode == FogRenderer.FogMode.FOG_TERRAIN;
        if (terrain) cloudFog = null;
        if (!fogEnabled) return null;
        FogType type = camera.getFluidInCamera();
        Entity entity = camera.getEntity();
        if (type == FogType.LAVA) return Settings.noLavaFog ? clear(terrain, renderDistance, color) : null;
        if (type == FogType.POWDER_SNOW) return Settings.noPowderSnowFog ? clear(terrain, renderDistance, color) : null;
        if (effectFog(entity)) return null;
        if (type == FogType.WATER) {
            if (Settings.noWaterFog) return clear(terrain, renderDistance, color);
            if (Settings.noDistanceFog && terrain) return waterWithoutDistanceFade(entity, renderDistance, color);
            return null;
        }
        if (foggy || !terrain || !Settings.noDistanceFog) return null;
        cloudFog = distanceFade(renderDistance, color);
        return new FogParameters(Float.MAX_VALUE, Float.MAX_VALUE, FogShape.CYLINDER, color.x, color.y, color.z, color.w);
    }

    /** Blindness or Darkness sets the fog (FogRenderer.getPriorityFogFunction finds one), unless kine takes it away
     *  (FogRendererMixin, the same rule). */
    public static boolean effectFog(Entity entity) {
        return entity instanceof LivingEntity living && (blindnessFog(living) || darknessFog(living));
    }

    public static boolean blindnessFog(LivingEntity living) { return !Settings.noBlindness && living.hasEffect(MobEffects.BLINDNESS); }

    public static boolean darknessFog(LivingEntity living) { return !Settings.noDarkness && living.hasEffect(MobEffects.DARKNESS); }

    /** No Nether fog clears the Nether's open-air fog, the boss bar's world fog with it (26.1 clears its atmospheric
     *  environment whole there). */
    public static boolean netherFogCleared() {
        Level level = Minecraft.getInstance().level;
        return Settings.noNetherFog && level != null && level.dimension() == Level.NETHER;
    }

    /** No environmental fog: the world fades only at the edge of the render distance, as in clear air (the sky's fog
     *  out to the render distance; with No distance fog, no fade either). */
    private static FogParameters clear(boolean terrain, float renderDistance, Vector4f color) {
        if (!terrain) return new FogParameters(0.0F, renderDistance, FogShape.CYLINDER, color.x, color.y, color.z, color.w);
        if (!Settings.noDistanceFog) return distanceFade(renderDistance, color);
        cloudFog = distanceFade(renderDistance, color);
        return new FogParameters(Float.MAX_VALUE, Float.MAX_VALUE, FogShape.CYLINDER, color.x, color.y, color.z, color.w);
    }

    /** The terrain's fade at the edge of the render distance in clear air (FogRenderer.setupFog, FOG_TERRAIN). */
    private static FogParameters distanceFade(float renderDistance, Vector4f color) {
        float span = Mth.clamp(renderDistance / 10.0F, 4.0F, 64.0F);
        return new FogParameters(renderDistance - span, renderDistance, FogShape.CYLINDER, color.x, color.y, color.z, color.w);
    }

    /** The water fog without the render distance capping it (FogRenderer.setupFog: it ends at 96 blocks times the
     *  player's water vision, at least a quarter, 0.85 of that in a biome with closer water fog, and where it would
     *  end past the render distance the game ends it there, in a cylinder: the render-distance fade, which No distance
     *  fog takes away). */
    private static FogParameters waterWithoutDistanceFade(Entity entity, float renderDistance, Vector4f color) {
        float end = 96.0F;
        if (entity instanceof LocalPlayer player) {
            end *= Math.max(0.25F, player.getWaterVision());
            if (player.level().getBiome(player.blockPosition()).is(BiomeTags.HAS_CLOSER_WATER_FOG)) end *= 0.85F;
        }
        if (end > renderDistance)
            cloudFog = new FogParameters(-8.0F, renderDistance, FogShape.CYLINDER, color.x, color.y, color.z, color.w);
        return new FogParameters(-8.0F, end, FogShape.SPHERE, color.x, color.y, color.z, color.w);
    }

    /** The terrain fog the game would have had this frame where No distance fog took its fade away, else null. The
     *  clouds keep it (CloudFogMixin): 26.1 fades clouds out at their own distance, which No distance fog leaves, while
     *  these releases draw them with the terrain's fog. */
    public static FogParameters cloudFog() { return cloudFog; }

    private static FogParameters cloudFog;
}
