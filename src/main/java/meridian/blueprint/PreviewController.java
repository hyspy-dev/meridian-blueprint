package meridian.blueprint;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ScheduledFuture;
import meridian.api.module.Scheduler;
import meridian.api.session.ProxySession;
import meridian.core.api.Block;
import meridian.core.api.DebugRender;
import meridian.core.api.Player;
import meridian.core.api.Vec3;
import meridian.core.api.World;
import meridian.protocol.packets.interface_.BlockChange;
import meridian.protocol.packets.player.HideTriggerVolumePastePrefabPreview;
import meridian.protocol.packets.player.ShowTriggerVolumePastePrefabPreview;
import org.joml.Vector3f;
import org.slf4j.Logger;

/**
 * Lifecycle for an active blueprint preview. Holds the {@link ProxySession}
 * captured from observed traffic, owns the scheduler handle for the diff
 * tick, and emits the textured paste-preview at start / on layer change / hide.
 *
 * <p>Everything visual is delegated:
 * <ul>
 *   <li>{@link BlueprintBuilder} turns inputs (sanity request, parsed
 *       prefab) into a {@link Blueprint}.</li>
 *   <li>{@link DiffPainter} runs the per-tick scan and the
 *       {@code DisplayDebug} overlay.</li>
 * </ul>
 *
 * <p>Layer slicing: the controller stores user-configured {@code currentLayer}
 * and {@code windowSize}, applies them to both the {@link DiffPainter}
 * (overlay) and the {@link ShowTriggerVolumePastePrefabPreview} payload
 * (textured base). Changing either re-sends paste-preview filtered to the
 * new window, so texture and overlay stay aligned.
 */
final class PreviewController {

    /**
     * Default biome tint applied to paste previews. Pulled from Hytale's own
     * builder-tool fallback ({@code PrefabEditSessionManager.DEFAULT_TINT} =
     * Color(91, 158, 40)). A zero tint makes the shader multiply each
     * block face by black — the cube renders as an opaque black brick.
     */
    private static final int DEFAULT_BIOME_TINT = 0x5B9E28;
    /** Neutral water tint — only matters for fluid cells in a preview. */
    private static final int DEFAULT_WATER_TINT = 0x3F76E4;

    /** Cadence at which the painter scans the world for changes. 1 Hz is
     *  plenty; players don't place blocks faster than the eye notices the
     *  next repaint, and idle ticks emit zero packets either way. */
    private static final Duration DIFF_TICK = Duration.ofMillis(1000);

    private final Logger log;
    private final World world;
    private final DebugRender debug;
    private final BlockNameResolver names;
    private volatile ProxySession session;
    /** True once we've already warned about a missing session — keeps the log quiet on repeat clicks. */
    private volatile boolean warnedNoSession;

    /** Currently-active painter, or {@code null} when nothing is shown. */
    private volatile DiffPainter painter;
    /** Backing blueprint of the active painter; null when idle. Cached so we
     *  can re-build paste-preview payloads on layer change without rebuilding
     *  the painter from scratch. */
    private volatile Blueprint activeBlueprint;
    /** Handle to the diff repaint task; cancelled on {@link #hide}. */
    private volatile ScheduledFuture<?> tickHandle;

    /** Lower index of the visible layer window, in dy units. */
    private volatile int currentLayer;
    /** Number of layers shown; 0 = no limit (show the whole stack). */
    private volatile int windowSize;

    PreviewController(Logger log, World world, DebugRender debug, BlockNameResolver names) {
        this.log = log;
        this.world = world;
        this.debug = debug;
        this.names = names;
    }

    void bindSession(ProxySession s) {
        if (this.session == null) {
            this.session = s;
            log.info("bound Default-channel session");
        }
    }

    // ------------------------------------------------------------------
    // Entry points — preview creation
    // ------------------------------------------------------------------

    /**
     * Drops a 3×3×3 sanity cube of whatever block the player is standing on.
     * Used as a self-test for the preview pipeline — no disk, no catalog
     * lookup, only needs an active session and a known player position.
     */
    void startSanityPreview(Scheduler scheduler) {
        ProxySession s = requireSession();
        if (s == null) return;
        Optional<Vec3> pos = playerPosition();
        if (pos.isEmpty()) return;
        int ox = (int) Math.floor(pos.get().x());
        int oy = (int) Math.floor(pos.get().y());
        int oz = (int) Math.floor(pos.get().z());
        int blockId = sampleStandingBlockId(ox, oy - 1, oz);

        Blueprint bp = BlueprintBuilder.sanityCube(ox, oy, oz, blockId);
        startBlueprint(s, scheduler, bp, "sanity cube (blockId=" + blockId + ")");
    }

    /**
     * Loads a {@code .prefab.json} from disk and starts a preview anchored on
     * the block under the player. The author's anchor inside the file is
     * ignored on purpose — preview UX is "paste at my feet" regardless of
     * authoring offsets.
     */
    void loadAndStart(Scheduler scheduler, Path prefabPath) {
        ProxySession s = requireSession();
        if (s == null) return;
        Optional<Vec3> pos = playerPosition();
        if (pos.isEmpty()) return;

        PrefabFile prefab;
        try {
            prefab = PrefabLoader.load(prefabPath);
        } catch (IOException e) {
            log.warn("failed to read prefab {}", prefabPath, e);
            return;
        }
        if (prefab.version != 8) {
            log.warn("prefab version {} not the expected 8 — trying anyway", prefab.version);
        }

        int ox = (int) Math.floor(pos.get().x());
        int oy = (int) Math.floor(pos.get().y());
        int oz = (int) Math.floor(pos.get().z());
        BlueprintBuilder.Result result = BlueprintBuilder.fromPrefab(prefab, names, ox, oy, oz);
        if (!result.unknownNames().isEmpty()) {
            log.warn("skipped {} unique unknown block types: {}",
                    result.unknownNames().size(), result.unknownNames());
        }
        if (result.blueprint().size == 0) {
            log.warn("prefab {} has no usable blocks after resolution", prefabPath);
            return;
        }
        startBlueprint(s, scheduler, result.blueprint(),
                prefabPath.getFileName() + " (" + result.blueprint().size + " blocks, "
                        + result.skippedEmpty() + " Empty skipped)");
    }

    /**
     * Tears down everything: cancels the diff loop, hides the textured
     * paste preview, clears the debug overlay. Safe to call repeatedly.
     */
    void hide() {
        ScheduledFuture<?> h = tickHandle;
        if (h != null) {
            h.cancel(false);
            tickHandle = null;
        }
        ProxySession s = session;
        DiffPainter p = painter;
        if (s != null && p != null) {
            s.sendToClient(new HideTriggerVolumePastePrefabPreview());
            p.clearShapes();
        }
        painter = null;
        activeBlueprint = null;
        if (p != null) {
            log.info("preview cleared");
        }
    }

    boolean isActive() {
        return painter != null;
    }

    // ------------------------------------------------------------------
    // Entry points — layer slicing
    // ------------------------------------------------------------------

    /** Replaces window size and re-renders. {@code 0} means show everything. */
    void setWindow(int size) {
        windowSize = Math.max(0, size);
        applyLayerWindow();
    }

    /** Sets the bottom of the layer window to {@code layer}; clamps + repaints. */
    void setLayer(int layer) {
        currentLayer = layer;
        applyLayerWindow();
    }

    /**
     * Nudges the window up or down by {@code delta} layers. Returns the
     * clamped current layer so callers (Prev / Next buttons) can push the
     * new value back into the bound widget.
     */
    int shiftLayer(int delta) {
        DiffPainter p = painter;
        if (p == null) return currentLayer;
        currentLayer = clampLayer(currentLayer + delta, p);
        applyLayerWindow();
        return currentLayer;
    }

    // ------------------------------------------------------------------
    // Status (for liveText)
    // ------------------------------------------------------------------

    String statusLine() {
        DiffPainter p = painter;
        if (p == null) return "idle";
        return p.lastCorrect() + " correct / " + p.lastMissing() + " missing / "
                + p.lastWrong() + " wrong (of " + p.totalCells() + ")";
    }

    String layerLine() {
        DiffPainter p = painter;
        if (p == null) return "idle";
        int win = p.windowSize();
        if (win == p.totalLayers()) {
            return "all " + p.totalLayers() + " layers (" + p.minLayer() + " — " + p.maxLayer() + ")";
        }
        return "layers " + p.windowLo() + " — " + (p.windowHi() - 1)
                + " of " + p.minLayer() + " — " + p.maxLayer();
    }

    int currentLayerForBinding() {
        return currentLayer;
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    private void startBlueprint(ProxySession s, Scheduler scheduler, Blueprint bp, String label) {
        hide();
        DiffPainter p = new DiffPainter(log, world, debug, bp);
        // Start with the previously-chosen layer/window if they still make
        // sense; otherwise default to "show everything from the bottom".
        if (windowSize <= 0) {
            currentLayer = p.minLayer();
        } else {
            currentLayer = clampLayer(currentLayer, p);
            p.setWindow(currentLayer, windowSize);
        }
        painter = p;
        activeBlueprint = bp;
        sendPastePreview(s, bp, p.windowLo(), p.windowHi());
        p.tick();                                          // first frame, no waiting
        tickHandle = scheduler.scheduleAtFixedRate(this::tickIfActive,
                DIFF_TICK, DIFF_TICK);
        log.info("preview started: {} at {},{},{}",
                label, bp.anchorX, bp.anchorY, bp.anchorZ);
    }

    private void applyLayerWindow() {
        DiffPainter p = painter;
        Blueprint bp = activeBlueprint;
        ProxySession s = session;
        if (p == null || bp == null || s == null) return;
        currentLayer = clampLayer(currentLayer, p);
        p.setWindow(currentLayer, windowSize);
        // Re-issue the textured base filtered to the active window. The
        // client doesn't merge successive Show packets, so we hide first
        // and re-issue to avoid the old layers lingering underneath.
        s.sendToClient(new HideTriggerVolumePastePrefabPreview());
        sendPastePreview(s, bp, p.windowLo(), p.windowHi());
        p.tick();    // immediate refresh, don't wait for the next scheduled scan
    }

    /** Defensive: the painter reference can be nulled between schedule and tick. */
    private void tickIfActive() {
        DiffPainter p = painter;
        if (p != null) p.tick();
    }

    /**
     * Sends the textured ghost layer — relative offsets from the anchor.
     * Filters to cells with {@code lo <= dy < hi} so the texture stays in
     * sync with the overlay window.
     */
    private void sendPastePreview(ProxySession s, Blueprint bp, int lo, int hi) {
        List<BlockChange> filtered = new ArrayList<>(bp.size);
        for (int i = 0; i < bp.size; i++) {
            int dy = bp.dy[i];
            if (dy < lo || dy >= hi) continue;
            filtered.add(new BlockChange(bp.dx[i], dy, bp.dz[i], bp.blockId[i], bp.rotation[i]));
        }
        ShowTriggerVolumePastePrefabPreview pkt = new ShowTriggerVolumePastePrefabPreview();
        pkt.position = new Vector3f(bp.anchorX, bp.anchorY, bp.anchorZ);
        pkt.blocksChange = filtered.toArray(new BlockChange[0]);
        pkt.biomeTint = DEFAULT_BIOME_TINT;
        pkt.waterTint = DEFAULT_WATER_TINT;
        s.sendToClient(pkt);
    }

    private static int clampLayer(int layer, DiffPainter p) {
        if (layer < p.minLayer()) return p.minLayer();
        if (layer > p.maxLayer()) return p.maxLayer();
        return layer;
    }

    private ProxySession requireSession() {
        ProxySession s = session;
        if (s == null && !warnedNoSession) {
            log.warn("no session yet — waiting for first Default-channel packet");
            warnedNoSession = true;
        }
        return s;
    }

    private Optional<Vec3> playerPosition() {
        Optional<Player> p = world.player();
        if (p.isEmpty()) {
            log.warn("no local player position yet");
        }
        return p.map(Player::position);
    }

    private int sampleStandingBlockId(int x, int y, int z) {
        Block under = world.blockAt(x, y, z);
        if (under != null && under.type() != null) {
            return under.type().id();
        }
        // Fallback — id 1 is the first registered non-air block on every
        // Hytale catalog, so we always end up with *some* visible ghost.
        return 1;
    }
}
