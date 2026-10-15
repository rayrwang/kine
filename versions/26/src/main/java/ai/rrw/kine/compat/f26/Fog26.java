package ai.rrw.kine.compat.f26;

import net.minecraft.client.renderer.fog.FogData;

/**
 * The Visibility settings: fog (water, lava, powder snow, the Nether, rain, a boss fight, the render-distance fade),
 * the Darkness and Blindness effects, and the fire, underwater, pumpkin, frost and portal overlays taken away. All
 * of it is the client's own drawing (the game's fog environments, lightmap, sky and screen overlays), so the server
 * sees nothing. The mixins read the settings: on 26.x WaterFogMixin, LavaFogMixin, PowderSnowFogMixin,
 * AtmosphericFogMixin, MobEffectFogMixin, LightmapMixin, CameraSkyMixin, FogRendererMixin and LevelRendererSkyMixin
 * in ai.rrw.kine.mixin.f26 (the fog ones call in here), ScreenEffectMixin and GuiOverlayMixin in each epoch's.
 */
public final class Fog26 {
    private Fog26() {}

    /** No environmental fog: the world fades only at the edge of the render distance, as in clear air. The same
     *  "no fog" values the game's own empty fog buffer uses (FogRenderer). */
    public static void clearFog(FogData fog, float renderDistance) {
        fog.environmentalStart = Float.MAX_VALUE;
        fog.environmentalEnd = Float.MAX_VALUE;
        fog.skyEnd = renderDistance;
        fog.cloudEnd = renderDistance;
    }
}
