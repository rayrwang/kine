package ai.rrw.kine.autoflight;

/** 2-D surface heightmap around the aircraft, in world coordinates.
 *  In the mod this is backed by ClientLevel.getHeight(MOTION_BLOCKING, x, z) sampled over a patch;
 *  here it is whatever the caller supplies. Returns Double.NaN where the surface is unknown
 *  (unloaded chunk / beyond render distance), so the planner can treat "can't see it" distinctly
 *  from "it's low". */
@FunctionalInterface
public interface Terrain2D {
    double heightAt(double x, double z);
}
