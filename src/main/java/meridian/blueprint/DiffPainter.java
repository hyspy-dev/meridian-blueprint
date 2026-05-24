package meridian.blueprint;

import java.util.Arrays;
import meridian.core.api.Block;
import meridian.core.api.DebugRender;
import meridian.core.api.World;
import org.slf4j.Logger;

/**
 * The diff scan-and-repaint loop, isolated from session/lifecycle. Owns the
 * cached state arrays plus the per-tick scratch buffer.
 *
 * <p>Layer-window filtering: cells whose {@code dy} falls outside the
 * {@code [layerLo, layerHi)} window are treated as {@link CellState#CORRECT}.
 * That folds the slicing logic into the existing change-detection — flipping
 * the window invalidates exactly the cells that crossed it, which triggers
 * the same one-blink repaint as a normal world change.
 */
final class DiffPainter {

    /** Overlay cube edge — slightly inflated past the unit voxel to defeat z-fight. */
    private static final double OVERLAY_SIZE = 1.01;
    /** Effectively persistent — see {@code DEBUG_BOX_TTL_S} discussion in PreviewController. */
    private static final float DEBUG_BOX_TTL_S = 3600f;
    /**
     * Overlay opacity. Picked low so the textured paste-preview underneath
     * still reads clearly — at 0.5 the highlight buried the block colour;
     * at 0.2 it tints without hiding it.
     */
    private static final float OPACITY = 0.2f;

    private final Logger log;
    private final World world;
    private final DebugRender debug;
    private final Blueprint bp;

    /** Last-tick scan results; index aligned with {@link Blueprint#dx}. */
    private final CellState[] cached;
    /** Scratch buffer for this tick's scan; same shape as {@link #cached}, reused. */
    private final CellState[] next;

    /** Min / max dy across all cells — defines the layer index range. */
    private final int minLayer;
    private final int maxLayer;

    /** Inclusive lower / exclusive upper bound on the visible layer window. */
    private volatile int layerLo;
    private volatile int layerHi;

    /** Latest counters from the previous tick — exposed for liveText / logging. */
    private volatile int lastCorrect, lastWrong, lastMissing;

    DiffPainter(Logger log, World world, DebugRender debug, Blueprint bp) {
        this.log = log;
        this.world = world;
        this.debug = debug;
        this.bp = bp;
        this.cached = new CellState[bp.size];
        this.next = new CellState[bp.size];

        int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
        for (int i = 0; i < bp.size; i++) {
            if (bp.dy[i] < lo) lo = bp.dy[i];
            if (bp.dy[i] > hi) hi = bp.dy[i];
        }
        this.minLayer = bp.size == 0 ? 0 : lo;
        this.maxLayer = bp.size == 0 ? 0 : hi;
        // Default: window covers the whole stack.
        this.layerLo = minLayer;
        this.layerHi = maxLayer + 1;
    }

    int minLayer()      { return minLayer; }
    int maxLayer()      { return maxLayer; }
    int totalLayers()   { return maxLayer - minLayer + 1; }
    int currentLayer()  { return layerLo; }
    int windowSize()    { return layerHi - layerLo; }

    int lastCorrect()   { return lastCorrect; }
    int lastWrong()     { return lastWrong; }
    int lastMissing()   { return lastMissing; }
    int totalCells()    { return bp.size; }

    /**
     * Configures the visible layer window. {@code currentLayer} is the bottom
     * (inclusive) of the window; {@code windowSize <= 0} or {@code >= total}
     * resets the window to the full blueprint. The clamp keeps the window
     * inside the blueprint's actual range.
     *
     * <p>The cached state is wiped so the next {@link #tick} sees every
     * boundary-crossing cell as "changed" and emits a fresh repaint.
     */
    void setWindow(int currentLayer, int windowSize) {
        if (windowSize <= 0 || windowSize >= totalLayers()) {
            layerLo = minLayer;
            layerHi = maxLayer + 1;
        } else {
            int lo = Math.max(minLayer, Math.min(maxLayer, currentLayer));
            int hi = Math.min(maxLayer + 1, lo + windowSize);
            layerLo = lo;
            layerHi = hi;
        }
        Arrays.fill(cached, null);
    }

    int windowLo() { return layerLo; }
    int windowHi() { return layerHi; }

    /**
     * Runs one scan tick. Returns {@code true} if the painter emitted a
     * repaint, {@code false} if nothing changed.
     */
    boolean tick() {
        int n = bp.size;
        int correct = 0, wrong = 0, missing = 0;
        boolean changed = false;
        int lo = layerLo, hi = layerHi;

        // Pass 1 — sample world state into the scratch buffer. Cells outside
        // the layer window are treated as CORRECT — they're invisible to
        // the overlay logic without a second filter branch.
        for (int i = 0; i < n; i++) {
            int dy = bp.dy[i];
            CellState ns;
            if (dy < lo || dy >= hi) {
                ns = CellState.CORRECT;
                correct++;
            } else {
                Block actual = world.blockAt(bp.anchorX + bp.dx[i],
                        bp.anchorY + dy,
                        bp.anchorZ + bp.dz[i]);
                int actualId = actual != null && actual.type() != null ? actual.type().id() : 0;
                if (actualId == bp.blockId[i]) {
                    ns = CellState.CORRECT; correct++;
                } else if (actual == null || actual.isAir() || actualId <= 0) {
                    ns = CellState.MISSING; missing++;
                } else {
                    ns = CellState.WRONG; wrong++;
                }
            }
            next[i] = ns;
            if (cached[i] != ns) changed = true;
        }
        lastCorrect = correct;
        lastWrong = wrong;
        lastMissing = missing;
        if (!changed) return false;

        // Pass 2 — full repaint. One Clear, one DisplayDebug per visible cell.
        debug.clear();
        int emitted = 0;
        for (int i = 0; i < n; i++) {
            CellState s = next[i];
            if (!s.visible) continue;
            debug.box(bp.anchorX + bp.dx[i] + 0.5,
                    bp.anchorY + bp.dy[i] + 0.5,
                    bp.anchorZ + bp.dz[i] + 0.5,
                    OVERLAY_SIZE, OVERLAY_SIZE, OVERLAY_SIZE,
                    s.r, s.g, s.b, OPACITY, DEBUG_BOX_TTL_S);
            emitted++;
        }
        System.arraycopy(next, 0, cached, 0, n);
        log.debug("repaint — {} correct, {} wrong, {} missing, {} emitted",
                correct, wrong, missing, emitted);
        return true;
    }

    /** External hook for the controller's {@code hide} path. */
    void clearShapes() {
        debug.clear();
    }
}
