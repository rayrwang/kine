package ai.rrw.kine.walk;

import ai.rrw.kine.move.MoveState;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A walk up a plain vine wall recorded in a lockstep client (ParkourTest's KINETEST_VINE_WALLS: each tick the rotation
 * sent and the keys, and where the player ended up) replayed through the movement copy, the wall rebuilt the same in a
 * test world: whether the copy climbs a vine as the game does (RW 2026-10-14 18:49:02, "maybe the layer 2 model of it
 * is flawe d"). KINE_VINE_REPLAY: the run's results.txt and the scene ("results.txt:vine5"). Prints the first tick the
 * copy and the game part (more than 1e-4 apart) and the largest gap.
 */
public class VineReplayTest {
    static final int FLOOR = 20;
    static final Pattern TR = Pattern.compile("KINETEST vineTrace (\\S+) t=(\\d+) pos=([-\\d.]+),([-\\d.]+),([-\\d.]+) v=([-\\d.]+),([-\\d.]+),([-\\d.]+) "
        + "ground=(\\w+) view=([-\\d.]+) sent=([-\\d.]+) keys=Input\\[forward=(\\w+), backward=(\\w+), left=(\\w+), right=(\\w+), jump=(\\w+), shift=(\\w+), sprint=(\\w+)\\]");

    /** The scene as ParkourTest.vineWall builds it, moved to x = 0 at the vines and y = FLOOR at the feet. */
    static TestWorld wall(int h, boolean wide) {
        TestWorld w = TestWorld.blocks(-16, -16, 15, 15);
        w.fill(-12, FLOOR - 1, -6, 8, FLOOR - 1, 6, TestWorld.STONE);
        w.fill(1, FLOOR, -4, 3, FLOOR - 1 + h, 4, TestWorld.STONE);
        Kind vine = Layer2StationTest.kindOf("minecraft:vine[east=true]");
        for (int dz = wide ? -1 : 0; dz <= (wide ? 1 : 0); dz++)
            for (int y = FLOOR; y <= FLOOR - 1 + h; y++) w.set(0, y, dz, vine);
        return w;
    }

    @Test
    void replay() throws Exception {
        String spec = System.getenv("KINE_VINE_REPLAY");
        if (spec == null) return;
        String file = spec.substring(0, spec.lastIndexOf(':')), scene = spec.substring(spec.lastIndexOf(':') + 1);
        int h = Integer.parseInt(scene.replaceAll("[^0-9]", ""));
        boolean off = scene.contains("off"), wide = scene.contains("wide");
        int x0 = 6000 + 40 * h + (off ? 400 : 0) + (wide ? 800 : 0), y0 = 100;
        List<double[]> pos = new ArrayList<>();
        List<Integer> keys = new ArrayList<>();
        List<Float> yaw = new ArrayList<>();
        for (String line : Files.readAllLines(Path.of(file))) {
            Matcher m = TR.matcher(line);
            if (!m.find() || !m.group(1).equals(scene)) continue;
            pos.add(new double[]{Double.parseDouble(m.group(3)) - x0, Double.parseDouble(m.group(4)) - y0 - 1 + FLOOR, Double.parseDouble(m.group(5))});
            yaw.add(Float.parseFloat(m.group(11)));
            int k = 0;
            String[] names = {"forward", "backward", "left", "right", "jump", "shift", "sprint"};
            int[] bits = {MoveState.FORWARD, MoveState.BACKWARD, MoveState.LEFT, MoveState.RIGHT, MoveState.JUMP, MoveState.SHIFT, MoveState.SPRINT};
            for (int i = 0; i < 7; i++) if (m.group(12 + i).equals("true")) k |= bits[i];
            keys.add(k);
        }
        Layer2Sim sim = new Layer2Sim(wall(h, wide));
        // the walk starts standing where the scene puts the player (5.5 before the vines' block, its middle in z)
        MoveState s = Layer2Sim.standing(-5.5, FLOOR, 0.5);
        double worst = 0;
        int first = -1;
        for (int t = 0; t < pos.size(); t++) {
            sim.phys.step(s, keys.get(t), yaw.get(t), 0f);
            double[] q = pos.get(t);
            double d = Math.abs(s.x - q[0]) + Math.abs(s.y - q[1]) + Math.abs(s.z - q[2]);
            if (d > 1e-4 && first < 0) {
                first = t;
                System.out.printf(Locale.ROOT, "vine replay %s: parts at tick %d keys %d yaw %.2f: game %.5f,%.5f,%.5f copy %.5f,%.5f,%.5f climbable %b%n",
                    scene, t, keys.get(t), yaw.get(t), q[0], q[1], q[2], s.x, s.y, s.z, sim.phys.onClimbable(s));
                // from here on, follow the game's state (a gap in one tick, not carried on)
            }
            worst = Math.max(worst, d);
            if (d > 1e-4) { s.x = q[0]; s.y = q[1]; s.z = q[2]; }
        }
        System.out.printf(Locale.ROOT, "vine replay %s: %d ticks, first apart at %d, largest gap %.5f%n", scene, pos.size(), first, worst);
    }
}
