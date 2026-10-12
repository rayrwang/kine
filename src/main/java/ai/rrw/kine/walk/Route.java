package ai.rrw.kine.walk;

/**
 * A path to walk: waypoints (feet positions) and the move into each, the first being where it starts. Between two
 * waypoints joined by WALK the player walks straight; the other moves are the grid's own (a jump up, a ladder, water).
 * Also the ground's height sampled densely along it (for the rails), the lengths and times the report gives, and the
 * straight line on to a goal beyond the loaded area.
 */
public final class Route {
    public final double[] x, y, z;
    public final byte[] move;
    public final int n;
    /** The route stops at the loaded area's edge; the goal is straight on from its end. */
    public final boolean viaExit;
    public final int goalX, goalZ;

    /** Ground under the route, every ~0.2 blocks (x, y, z), for drawing. */
    public float[] gx = new float[0], gy = new float[0], gz = new float[0];
    public int gn;

    /** Horizontal lengths, blocks: of the grid path, of this route, of the straight line from start to end. */
    public double gridLength, length, straightLength;
    /** The search's cost of the grid path and the time this route should take, from the blocks underfoot (ticks). */
    public double gridCost, time;
    /** How the tightening went: straight-walk checks made and the time they took. */
    public int checks;
    public long tightenNanos;

    Route(double[] x, double[] y, double[] z, byte[] move, int n, boolean viaExit, int goalX, int goalZ) {
        this.x = x; this.y = y; this.z = z; this.move = move; this.n = n;
        this.viaExit = viaExit; this.goalX = goalX; this.goalZ = goalZ;
    }

    public String moveName(int k) {
        return Graph.NAMES[move[k]];
    }

    public static boolean straight(int move) {
        return move == Graph.WALK;
    }

    public static final int WALK = Graph.WALK, WADE = Graph.WADE, DROP = Graph.DROP, JUMP = Graph.JUMP,
        WATER_IN = Graph.WATER_IN, SWIM = Graph.SWIM, WATER_OUT = Graph.WATER_OUT, CLIMB_ON = Graph.CLIMB_ON,
        CLIMB_UP = Graph.CLIMB_UP, CLIMB_DOWN = Graph.CLIMB_DOWN, CLIMB_OFF = Graph.CLIMB_OFF, START = Graph.START;
}
