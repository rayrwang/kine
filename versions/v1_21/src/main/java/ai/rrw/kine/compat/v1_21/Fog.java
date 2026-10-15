package ai.rrw.kine.compat.v1_21;

import ai.rrw.kine.Settings;
import com.mojang.blaze3d.shaders.FogShape;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Camera;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.FogRenderer;
import net.minecraft.core.Holder;
import net.minecraft.tags.BiomeTags;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.material.FogType;

/**
 * The Visibility settings: fog (water, lava, powder snow, the Nether, a boss fight, the render-distance fade), the
 * Darkness and Blindness effects, and the fire, underwater, pumpkin, frost and portal overlays taken away. All of it
 * is the client's own drawing, so the server sees nothing.
 *
 * <p>On 1.21 and 1.21.1 one fog does it all: FogRenderer.setupFog picks the first of lava, powder snow, Blindness or
 * Darkness, water, a foggy place (the Nether, or a boss bar's world fog) and the render-distance fade, and sets its
 * start, end and shape in RenderSystem, once for the sky and once for the terrain. 26.1 keeps an environment's fog
 * apart from the render-distance fade; here a fog switched off falls back to the fade of clear air, as there.
 * FogRendererMixin calls {@link #afterSetupFog}; MobEffectFogMixin leaves a switched-off effect out of the game's
 * pick, AtmosphericFogMixin the Nether's and the boss bar's fog, LightmapMixin and CameraSkyMixin the rest of
 * Darkness and Blindness. Rain only darkens the fog's colour on these releases, so No rain fog has nothing to do.
 */
public final class Fog {
    private Fog() {}

    /** The render distance of the terrain's fade that No distance fog took away this frame, or NaN: the clouds get it
     *  back (cloudsFade). */
    private static float removedFade = Float.NaN;

    /** After FogRenderer.setupFog: the fog the game just set, changed for the settings that are on. */
    public static void afterSetupFog(Camera camera, FogRenderer.FogMode mode, float renderDistance, boolean foggy) {
        FogType type = camera.getFluidInCamera();
        Entity entity = camera.getEntity();
        if (mode == FogRenderer.FogMode.FOG_TERRAIN) removedFade = Float.NaN;
        if (type == FogType.LAVA) {
            if (Settings.noLavaFog) clearFog(mode, renderDistance);
        } else if (type == FogType.POWDER_SNOW) {
            if (Settings.noPowderSnowFog) clearFog(mode, renderDistance);
        } else if (effectFog(entity)) {
            // the effect's own fog, as the game set it
        } else if (type == FogType.WATER) {
            if (Settings.noWaterFog) clearFog(mode, renderDistance);
            else if (Settings.noDistanceFog) waterFogUncapped(entity);
        } else if (!foggy && mode == FogRenderer.FogMode.FOG_TERRAIN && Settings.noDistanceFog) {
            noFog();
            removedFade = renderDistance;
        }
    }

    /** Before the clouds, which the game draws with the terrain's fog: the render-distance fade No distance fog took
     *  from the terrain, back for them (26.1 fades clouds by their own distance, which that setting leaves). Returns
     *  whether it did, so {@link #afterClouds} takes it away again. */
    public static boolean cloudsFade() {
        if (Float.isNaN(removedFade)) return false;
        fade(removedFade);
        return true;
    }

    public static void afterClouds() { noFog(); }

    /** The render-distance fade of clear air (setupFog's last case for the terrain). */
    private static void fade(float renderDistance) {
        float fade = Mth.clamp(renderDistance / 10.0F, 4.0F, 64.0F);
        set(renderDistance - fade, renderDistance, FogShape.CYLINDER);
    }

    /** Blindness or Darkness sets the fog: the game's pick (the first effect the entity has, Blindness before
     *  Darkness) without the ones switched off (MobEffectFogMixin). */
    private static boolean effectFog(Entity entity) {
        return entity instanceof LivingEntity le
            && (!Settings.noBlindness && le.hasEffect(MobEffects.BLINDNESS) || !Settings.noDarkness && le.hasEffect(MobEffects.DARKNESS));
    }

    /** No environmental fog: the world fades only at the edge of the render distance, as in clear air (setupFog's
     *  last two cases); with No distance fog on, the terrain not even there. */
    private static void clearFog(FogRenderer.FogMode mode, float renderDistance) {
        if (mode == FogRenderer.FogMode.FOG_SKY) {
            set(0.0F, renderDistance, FogShape.CYLINDER);
        } else if (Settings.noDistanceFog) {
            noFog();
            removedFade = renderDistance;
        } else {
            fade(renderDistance);
        }
    }

    /** The water fog without the game's cap at the render distance (which is the render-distance fade's part of it;
     *  26.1 keeps them apart, so No distance fog leaves the water fog its full reach). */
    private static void waterFogUncapped(Entity entity) {
        float end = 96.0F;
        if (entity instanceof LocalPlayer player) {
            end *= Math.max(0.25F, player.getWaterVision());
            Holder<Biome> biome = player.level().getBiome(player.blockPosition());
            if (biome.is(BiomeTags.HAS_CLOSER_WATER_FOG)) end *= 0.85F;
        }
        set(-8.0F, end, FogShape.SPHERE);
    }

    /** No fog at all (FogRenderer.setupNoFog's start, and the end with it). */
    private static void noFog() {
        set(Float.MAX_VALUE, Float.MAX_VALUE, RenderSystem.getShaderFogShape());
    }

    private static void set(float start, float end, FogShape shape) {
        RenderSystem.setShaderFogStart(start);
        RenderSystem.setShaderFogEnd(end);
        RenderSystem.setShaderFogShape(shape);
    }
}
