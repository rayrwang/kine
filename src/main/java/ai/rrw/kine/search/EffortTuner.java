package ai.rrw.kine.search;

/**
 * Sets the search's effort from the time its replans really take on this machine (RW: "it shodulb e dyancima ly
 * asjusted base don how powerful the paleyrs cpu is"): the beam widens while replans use well under the budget and
 * narrows when they run over it, then the draws follow, within fixed bounds. Measured, not guessed from core counts.
 */
public final class EffortTuner {
    private final RecedingSearch.Effort effort;
    private final int minBeam, maxBeam, minDraws, maxDraws;
    private double avgNanos = -1;
    private int calm = 0;

    public EffortTuner(RecedingSearch.Effort start, int minBeam, int maxBeam, int minDraws, int maxDraws) {
        this.effort = start;
        this.minBeam = minBeam; this.maxBeam = maxBeam;
        this.minDraws = minDraws; this.maxDraws = maxDraws;
    }

    public RecedingSearch.Effort effort() {
        return effort;
    }

    /** The smoothed time per replan, in nanoseconds (-1 before the first). */
    public double averageNanos() {
        return avgNanos;
    }

    /** After each replan. */
    public void record(RecedingSearch.Stats s) {
        avgNanos = avgNanos < 0 ? s.nanos() : 0.8 * avgNanos + 0.2 * s.nanos();
        double use = avgNanos / effort.budgetNanos;
        if (s.timedOut() || use > 0.8) {
            calm = 0;
            if (effort.beam > minBeam) effort.beam--;
            else if (effort.draws > minDraws) effort.draws--;
            avgNanos *= 0.85;   // expect the smaller effort to be quicker; don't shrink again at once
        } else if (use < 0.35 && ++calm >= 20) {
            calm = 0;
            if (effort.draws < maxDraws && effort.beam >= maxBeam / 2) effort.draws++;
            else if (effort.beam < maxBeam) effort.beam++;
        }
    }
}
