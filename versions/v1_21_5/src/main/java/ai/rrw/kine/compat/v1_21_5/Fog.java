package ai.rrw.kine.compat.v1_21_5;

import ai.rrw.kine.Settings;
import com.mojang.blaze3d.shaders.FogShape;
import net.minecraft.client.Camera;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.FogParameters;
import net.minecraft.client.renderer.FogRenderer;
import net.minecraft.tags.BiomeTags;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.material.FogType;

/**
 * The Visibility settings: fog (water, lava, powder snow, the Nether, a boss fight, the render-distance fade), the
 * Darkness and Blindness effects, and the fire, underwater, pumpkin, frost and portal overlays taken away. All of it
 * is the client's own drawing (the game's fog, lightmap, sky and screen overlays), so the server sees nothing.
 *
 * <p>1.21.5 has no fog environments: FogRenderer.setupFog picks one fog per frame and mode (the terrain's, the sky's)
 * in this order: lava, powder snow, the first effect of Blindness and Darkness the camera's entity has, water, a
 * foggy place (the Nether's dimension effects, or a boss bar's world fog: LevelRenderer.renderLevel works that out
 * and also leaves the sky out for it), else the open air's. 26.x's environments come in the same order. The open air's
 * is the fade at the edge of the render distance alone (cylindrical, over the last tenth of it, 4 to 64 blocks), and
 * for the sky a fade from the camera out to the render distance; there is no separate fade at the render distance
 * under the others, and no rain fog (rain only darkens the fog colour, as on 26.x). So here clearing a fog gives the
 * open air's fog in its place, with the fog's own colour, as 26.x's clearing leaves the render-distance fade and the
 * colour. The mixins in ai.rrw.kine.mixin.v1_21_5 read the settings: FogRendererMixin (calls in here),
 * MobEffectFogMixin, LevelRendererFogMixin, CloudFogMixin, LightmapMixin, CameraSkyMixin, LevelRendererSkyMixin,
 * ScreenEffectMixin and GuiOverlayMixin.
 */
public final class Fog {
    private Fog() {}

    /** The terrain fog of this frame with kine's clearing but before No distance fog, for the clouds (CloudFogMixin):
     *  1.21.5 draws the clouds with the terrain fog, and 26.x keeps their own fade under No distance fog. */
    public static FogParameters cloudFog;

    /** The effect whose fog applies to this entity, after No blindness and No darkness: Blindness before Darkness, as
     *  the game's list (FogRenderer.MOB_EFFECT_FOG); 0 for Blindness, 1 for Darkness, -1 for none. */
    public static int effectFog(Entity entity) {
        if (!(entity instanceof LivingEntity living)) return -1;
        if (!Settings.noBlindness && living.hasEffect(MobEffects.BLINDNESS)) return 0;
        if (!Settings.noDarkness && living.hasEffect(MobEffects.DARKNESS)) return 1;
        return -1;
    }

    /** FogRenderer.setupFog's result, the settings applied (FogRendererMixin); foggy is its own flag for a foggy place
     *  (after LevelRendererFogMixin). */
    public static FogParameters setupFog(FogParameters fog, Camera camera, FogRenderer.FogMode mode, float renderDistance, boolean foggy) {
        FogType type = camera.getFluidInCamera();
        // which of setupFog's cases made this fog
        boolean lava = type == FogType.LAVA, snow = !lava && type == FogType.POWDER_SNOW;
        boolean effect = !lava && !snow && effectFog(camera.getEntity()) >= 0;
        boolean water = !lava && !snow && !effect && type == FogType.WATER;
        boolean openAir = !lava && !snow && !effect && !water && !foggy;
        boolean cleared = lava && Settings.noLavaFog || snow && Settings.noPowderSnowFog || water && Settings.noWaterFog;
        if (cleared) fog = openAir(fog, mode, renderDistance, false);
        if (mode != FogRenderer.FogMode.FOG_TERRAIN) return fog;   // the sky's fade stays, as 26.x's sky fog end
        cloudFog = fog;
        if (!Settings.noDistanceFog) return fog;
        // No distance fog: the fade at the edge of the render distance taken away (26.1: renderDistanceStart and End)
        if (cleared || openAir) return openAir(fog, mode, renderDistance, true);   // the open air's fog is that fade alone
        if (water && fog.shape() == FogShape.CYLINDER) {
            // the water fog cut at the render distance (setupFog does that when it ends past it): its own end again
            return new FogParameters(fog.start(), waterFogEnd(camera.getEntity()), FogShape.SPHERE, fog.red(), fog.green(), fog.blue(), fog.alpha());
        }
        return fog;
    }

    /** The open air's fog in this mode with this colour (setupFog's last two cases); none at all for the terrain under
     *  No distance fog. */
    private static FogParameters openAir(FogParameters fog, FogRenderer.FogMode mode, float renderDistance, boolean noDistanceFog) {
        float start, end;
        if (mode == FogRenderer.FogMode.FOG_SKY) {
            start = 0.0F;
            end = renderDistance;
        } else if (noDistanceFog) {
            start = Float.MAX_VALUE;
            end = Float.MAX_VALUE;
        } else {
            start = renderDistance - Mth.clamp(renderDistance / 10.0F, 4.0F, 64.0F);
            end = renderDistance;
        }
        return new FogParameters(start, end, FogShape.CYLINDER, fog.red(), fog.green(), fog.blue(), fog.alpha());
    }

    /** FogRenderer.setupFog's end of the water fog, before the render distance cuts it. */
    private static float waterFogEnd(Entity entity) {
        float end = 96.0F;
        if (entity instanceof LocalPlayer player) {
            end *= Math.max(0.25F, player.getWaterVision());
            if (player.level().getBiome(player.blockPosition()).is(BiomeTags.HAS_CLOSER_WATER_FOG)) end *= 0.85F;
        }
        return end;
    }
}
