package ai.rrw.kine.view;

import net.minecraft.client.renderer.fog.FogData;

/**
 * The Visibility settings: fog (water, lava, powder snow, the Nether, rain, a boss fight, the render-distance fade),
 * the Darkness and Blindness effects, and the fire, underwater, pumpkin, frost and portal overlays taken away. All
 * of it is the client's own drawing (the game's fog environments, lightmap, sky and screen overlays), so the server
 * sees nothing. The mixins (WaterFogMixin, LavaFogMixin, PowderSnowFogMixin, AtmosphericFogMixin, MobEffectFogMixin,
 * LightmapMixin, CameraSkyMixin, FogRendererMixin, LevelRendererSkyMixin, ScreenEffectMixin, GuiOverlayMixin) read
 * the settings and call in here.
 */
public final class Visibility {
    private Visibility() {}

    /** No environmental fog: the world fades only at the edge of the render distance, as in clear air. The same
     *  "no fog" values the game's own empty fog buffer uses (FogRenderer). */
    public static void clearFog(FogData fog, float renderDistance) {
        fog.environmentalStart = Float.MAX_VALUE;
        fog.environmentalEnd = Float.MAX_VALUE;
        fog.skyEnd = renderDistance;
        fog.cloudEnd = renderDistance;
    }
}
