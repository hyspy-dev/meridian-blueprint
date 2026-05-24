package meridian.blueprint;

/**
 * The three states a blueprint cell can be in relative to the live world,
 * plus the colour the diff overlay uses to paint each one.
 *
 * <p>Colours map to litematica's convention:
 * <ul>
 *   <li>{@link #CORRECT} — invisible. The expected block is already there;
 *       the textured paste-preview underneath reads through.</li>
 *   <li>{@link #MISSING} — cyan. Air or unloaded; the player still has to
 *       place this one.</li>
 *   <li>{@link #WRONG} — orange. Something is there but the wrong type;
 *       the player has to mine and replace.</li>
 * </ul>
 *
 * <p>The {@code visible} flag is a fast-path the painter checks before
 * doing any work — avoids per-cell {@code if (state == CORRECT)} branching.
 */
enum CellState {
    CORRECT(0f, 0f, 0f, false),
    MISSING(0.4f, 0.85f, 1.0f, true),
    WRONG  (1.0f, 0.55f, 0.1f, true);

    final float r, g, b;
    final boolean visible;

    CellState(float r, float g, float b, boolean visible) {
        this.r = r;
        this.g = g;
        this.b = b;
        this.visible = visible;
    }
}
