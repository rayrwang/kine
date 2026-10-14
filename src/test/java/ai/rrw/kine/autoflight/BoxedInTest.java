package ai.rrw.kine.autoflight;

import ai.rrw.kine.guard.GlideSim;
import ai.rrw.kine.guard.Guard;
import ai.rrw.kine.guard.Maneuver;
import ai.rrw.kine.guard.Pilot;
import ai.rrw.kine.guard.Track;
import ai.rrw.kine.move.BlockGrid;
import ai.rrw.kine.move.BlockInfo;
import ai.rrw.kine.move.Body;
import ai.rrw.kine.move.MoveState;
import ai.rrw.kine.move.Shape;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What terrain avoidance does when boxed in, now that its emergency reversal is purged (RW 2026-10-11) and the guardian
 * is the only emergency path: the autopilot (porpoise law, TurnController, the look placement) flown in the movement
 * copy over block terrain, the guardian watching with the autopilot's own commands as the do-nothing path and taking
 * over when that path hurts. Each scene flown both ways (TurnPlanner.boxedInTurns): boxed in, turn toward the heading
 * whose path meets terrain farthest away, or keep straight on. Printed: guardian fires, damage, ground covered toward
 * the destination.
 */
class BoxedInTest {
    static final BlockInfo STONE = new BlockInfo(Shape.BLOCK, 0.6F, 1.0F, 1.0F, 0);

    interface Solid { boolean at(int x, int y, int z); }

    static final class FnGrid extends BlockGrid {
        final Solid f;
        FnGrid(Solid f) { super(0, 0, 0, 0, 0, 0); this.f = f; }
        @Override public BlockInfo get(int x, int y, int z) { return f.at(x, y, z) ? STONE : BlockInfo.AIR; }
    }

    record Scene(String name, Solid solid, double y, double destX, double destZ) {}

    static Terrain2D heightmap(Solid s) {
        return (x, z) -> {
            int bx = (int) Math.floor(x), bz = (int) Math.floor(z);
            for (int y = 400; y > -64; y--) if (s.at(bx, y, bz)) return y + 1;
            return -64;
        };
    }

    record Out(int fires, double damage, double progress, int ticks, String why) {}

    /** Fly the scene for `ticks`: the autopilot steering by terrain avoidance, the guardian watching and rescuing; a
     *  hand-back leaves the look held (an AFK pilot), as in the game after AUTOPILOT OFF. */
    static Out fly(Scene sc, int ticks) {
        Solid solid = sc.solid();
        BlockGrid grid = new FnGrid(solid);
        Terrain2D terrain = TurnGuard.cached(heightmap(solid));
        GlideSim sim = new GlideSim(grid, new Body(), -64, 3.0);
        MoveState s = new MoveState();
        s.x = 0.5; s.y = sc.y(); s.z = 0.5; s.vz = 1.5; s.vy = -0.2; s.fallFlying = true; s.glidePose = true;
        PorpoiseLaw law = new PorpoiseLaw();
        law.restartHold();
        double floor = sc.y() - 20;
        TurnController ctl = new TurnController();
        Guard.Config cfg = new Guard.Config();
        cfg.variations = 60;
        Track.Ring ring = new Track.Ring();
        for (int k = 400; k >= 1; k--) ring.add(0.5, sc.y(), 0.5 - 1.5 * k);
        float yaw = 0f, pitch = 0f;
        float[] out = new float[2];
        Maneuver rescue = null;
        Track frozen = Track.NONE;
        boolean engaged = true;
        int fires = 0;
        double damage = 0;
        String why = "";
        int t;
        for (t = 0; t < ticks; t++) {
            // the autopilot's plan for this tick (FlightDirector: the planner one tick behind on the floor)
            FlightModel3D.State seed = new FlightModel3D.State(s.x, s.y, s.z, s.vx, s.vy, s.vz, floor, pitch, law.climbing);
            seed.copyLaw(law);
            double heading = ctl.update(seed, sc.destX(), sc.destZ(), terrain);
            double lawFloor = Math.max(floor, ctl.floor());
            Guard g = new Guard(sim, cfg, rescue != null ? frozen : ring.snapshot());
            if (engaged && rescue == null) {
                ApModel m = new ApModel();
                m.mode = ApModel.AVOID; m.avoidanceOn = true;
                m.law.copyLaw(law); m.floor = lawFloor;
                m.straight = ctl.action() == TurnPlanner.STRAIGHT; m.heading = heading;
                m.destX = sc.destX(); m.destZ = sc.destZ();
                Guard.Verdict v = g.watch(s, yaw, pitch, m, t);
                if (v.kind() == Guard.Kind.FIRE) {
                    rescue = v.rescue().plan.copy(); frozen = ring.snapshot(); fires++; why += " fire@" + t;
                    if (System.getenv("KINE_BOXED_DEBUG") != null)
                        System.out.printf(Locale.ROOT, "BOXDBG %s t%d at %.1f %.1f %.1f v %.2f %.2f %.2f action %d heading %.1f floor %.0f: %s -> %s%n",
                            sc.name(), t, s.x, s.y, s.z, s.vx, s.vy, s.vz, ctl.action(), heading, lawFloor, v.danger(), v.rescue());
                }
            } else if (rescue != null) {
                Maneuver cur = rescue.copy();
                Guard.Verdict v = g.steer(s, yaw, pitch, cur, t);
                if (v.kind() == Guard.Kind.HANDBACK) { rescue = null; engaged = false; why += " back@" + t; }
                else if (v.rescue().plan != cur) rescue = v.rescue().plan.copy();
            } else {
                Guard.Verdict v = g.watch(s, yaw, pitch, Pilot.HOLD, t);
                if (v.kind() == Guard.Kind.FIRE) { rescue = v.rescue().plan.copy(); frozen = ring.snapshot(); fires++; why += " fire@" + t; }
            }
            if (rescue != null) { rescue.look(s, yaw, pitch, out); yaw = out[0]; pitch = out[1]; }
            else if (engaged) {
                double velYaw = FlightModel3D.velYaw(s.vx, s.vz);
                yaw = (float) (velYaw + Math.max(-FlightModel3D.DELTA_MAX, Math.min(FlightModel3D.DELTA_MAX, FlightModel3D.wrap180(heading - velYaw))));
                pitch = (float) FlightModel3D.ease(pitch, law.cmd);
            }
            double d = sim.step(s, yaw, pitch, false);
            if (rescue != null) rescue.after(s);
            if (engaged && rescue == null) law.update(s.y, Math.sqrt(s.vx * s.vx + s.vz * s.vz) * 20.0, lawFloor);
            if (rescue == null && engaged) ring.add(s.x, s.y, s.z);
            damage += d;
            if (d > 0) { why += String.format(Locale.ROOT, " hurt@%d(%.1f)", t, d); break; }
            if (!s.fallFlying) { why += " landed@" + t; break; }
        }
        double dx = sc.destX() - 0.5, dz = sc.destZ() - 0.5, len = Math.hypot(dx, dz);
        double progress = ((s.x - 0.5) * dx + (s.z - 0.5) * dz) / len;
        return new Out(fires, damage, progress, t, why);
    }

    static Scene[] scenes() {
        return new Scene[]{
            // an endless wall 40 ahead, far above the floor's reach: nothing clears straight or turning
            new Scene("endlessWall40", (x, y, z) -> y < 64 || z >= 40 && z < 48 && y < 400, 200, 0.5, 3000),
            // a box canyon: walls 25 either side and across the end 60 ahead, all to 400
            new Scene("boxCanyon", (x, y, z) -> y < 64 || (Math.abs(x) > 25 || z >= 60) && y < 400, 200, 0.5, 3000),
            // a cove: walls left, right and ahead, but open behind, from 50 ahead
            new Scene("cove", (x, y, z) -> y < 64 || (Math.abs(x) > 40 && z > -30 || z >= 50 && z < 58) && y < 400, 200, 0.5, 3000),
            // an endless wall met at 30 degrees, 50 ahead
            new Scene("slantWall", (x, y, z) -> y < 64 || z + 0.58 * x >= 50 && z + 0.58 * x < 58 && y < 400, 200, -1500, 2600),
            // control: a finite wall the planner turns round in time
            new Scene("finiteWall", (x, y, z) -> y < 64 || z >= 150 && z < 158 && Math.abs(x) < 60 && y < 400, 200, 0.5, 3000),
        };
    }

    @AfterEach void restore() { TurnPlanner.boxedInTurns = true; TurnPlanner.setRenderRange(TurnPlanner.SCAN_DIST); }

    @Test
    void boxedInPolicies() {
        TurnPlanner.setRenderRange(TurnPlanner.SCAN_DIST);
        double hurtTurn = 0, hurtStraight = 0;
        for (Scene sc : scenes()) {
            TurnPlanner.boxedInTurns = true;
            Out a = fly(sc, 1200);
            TurnPlanner.boxedInTurns = false;
            Out b = fly(sc, 1200);
            System.out.printf(Locale.ROOT, "BOXED %-13s turn: fires %d damage %.1f progress %.0f ticks %d%s | straight: fires %d damage %.1f progress %.0f ticks %d%s%n",
                sc.name(), a.fires(), a.damage(), a.progress(), a.ticks(), a.why(), b.fires(), b.damage(), b.progress(), b.ticks(), b.why());
            hurtTurn += a.damage(); hurtStraight += b.damage();
        }
        assertEquals(0.0, hurtTurn, "boxed in and turning, the guardian keeps every scene damage-free");
    }
}
