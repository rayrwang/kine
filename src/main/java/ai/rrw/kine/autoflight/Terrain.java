package ai.rrw.kine.autoflight;

/** 1-D along-track terrain profile seen ahead of the aircraft.
 *  In the mod this is backed by ClientLevel.getHeight(MOTION_BLOCKING, ...) sampled along the
 *  heading; here it is whatever the caller supplies. Returns Double.NaN where the surface is
 *  unknown (unloaded chunk) so the planner can treat "can't see it" distinctly from "it's low". */
@FunctionalInterface
public interface Terrain {
    double heightAt(double x);
}
