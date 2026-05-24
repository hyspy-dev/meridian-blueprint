package meridian.blueprint;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
 * Owns the on-screen preview state — both the textured "this is what the
 * blueprint looks like" overlay (a {@code ShowTriggerVolumePastePrefabPreview}
 * packet, drawn solid by the client) and the live diff overlay on top of it
 * (depth-tested debug cubes from {@link DebugRender#box}, repainted every
 * tick so the colour reflects the current world state).
 *
 * <p>The litematica-style diff feedback works like this — for every block in
 * the active blueprint we read what's actually at that world position via
 * {@link World#blockAt} and pick a colour:
 *
 * <ul>
 *   <li><b>Correct</b> (real block id matches expected) → no overlay; the
 *       paste-style ghost sits underneath it and reads the world block, the
 *       diff layer goes invisible.</li>
 *   <li><b>Wrong</b> (real block exists but its id doesn't match) → orange
 *       overlay tells the player to mine and replace.</li>
 *   <li><b>Missing</b> (real block is air / unloaded) → soft cyan overlay
 *       marks the cell the player still needs to fill in.</li>
 * </ul>
 */
final class PreviewController {

    /**
     * Default biome tint applied to ghost previews. Pulled from Hytale's own
     * builder-tool fallback ({@code PrefabEditSessionManager.DEFAULT_TINT} =
     * Color(91, 158, 40)). A zero tint makes the shader multiply each
     * block face by black — the cube renders as an opaque black brick.
     */
    private static final int DEFAULT_BIOME_TINT = 0x5B9E28;
    /** Neutral water tint — only matters for fluid cells in a preview. */
    private static final int DEFAULT_WATER_TINT = 0x3F76E4;

    /** Cadence at which we scan the world for changes. 1 Hz is plenty —
     *  players don't place blocks faster than the human eye notices the
     *  next repaint. Each tick only reads {@link World#blockAt} — it emits
     *  no packets unless a cell's state actually changed. */
    private static final long DIFF_TICK_MS = 1000;
    /** Lifetime baked into every {@code DisplayDebug} we emit. One hour is
     *  effectively persistent for our use case — way beyond any session
     *  the player keeps a single preview open for. Past this the box
     *  silently expires; calling {@link #hide} (or re-emitting on a real
     *  change) replaces it before then. */
    private static final float DEBUG_BOX_TTL_S = 3600f;
    /** Overlay cube edge — slightly inflated past the unit voxel to defeat z-fight. */
    private static final double OVERLAY_SIZE = 1.01;

    /** Per-cell colour state — what the diff loop computed last tick. */
    private enum CellState { CORRECT, WRONG, MISSING }

    /** Sentinel returned by {@link BlockNameResolver#resolve} for {@code "Empty"}. */
    private static final int AIR_ID = 0;
    /** Sentinel returned by {@link BlockNameResolver#resolve} for unknown names. */
    private static final int UNKNOWN_ID = -1;

    /** One blueprint cell — offset from anchor + expected block id + rotation. */
    private record Expected(int dx, int dy, int dz, int blockId, byte rotation) {}

    /** A blueprint locked to a world anchor — what the diff loop checks each tick. */
    private record Blueprint(int anchorX, int anchorY, int anchorZ, List<Expected> blocks) {}

    private final Logger log;
    private final World world;
    private final DebugRender debug;
    private final BlockNameResolver names;
    private volatile ProxySession session;

    /** Currently-active blueprint, or {@code null} when nothing is shown. */
    private volatile Blueprint active;
    /** Handle to the diff repaint task; cancelled on {@link #hide}. */
    private volatile ScheduledFuture<?> tickHandle;
    /**
     * Per-cell state seen on the last scan. {@code null} until the first
     * tick runs (and equally between {@link #hide} and the next
     * {@link #startBlueprint}). The tick loop compares each cell's freshly
     * computed state against this array; only when at least one cell
     * disagrees does it bother to push anything to the client.
     */
    private volatile CellState[] lastState;

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

        List<Expected> cells = new ArrayList<>(27);
        for (int dy = 0; dy < 3; dy++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int dx = -1; dx <= 1; dx++) {
                    cells.add(new Expected(dx, dy + 1, dz, blockId, (byte) 0));
                }
            }
        }
        startBlueprint(s, scheduler, new Blueprint(ox, oy, oz, cells),
                "sanity cube (blockId=" + blockId + ")");
    }

    /**
     * Loads a {@code .prefab.json} from disk, anchors it on the block under
     * the player, and starts the preview + diff loop.
     *
     * <p>The author's anchor inside the file is ignored on purpose — we want
     * a predictable "paste at my feet" UX. The block named {@code (0,0,0)}
     * in the file lands on the block the player is standing on.
     *
     * <p>Names that don't resolve against the live block catalog are skipped
     * with a single aggregated warn (one line per unique missing name), so
     * a stale or partial catalog doesn't spam the log. {@code "Empty"} cells
     * are dropped silently — they're the format's "no block" marker.
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

        List<Expected> cells = new ArrayList<>(prefab.blocks.size());
        Map<String, Integer> missing = new HashMap<>();
        int skippedEmpty = 0;
        for (PrefabFile.Block b : prefab.blocks) {
            int id = names.resolve(b.name);
            if (id == AIR_ID) {
                skippedEmpty++;
                continue;
            }
            if (id == UNKNOWN_ID) {
                missing.merge(b.name, 1, Integer::sum);
                continue;
            }
            cells.add(new Expected(b.x, b.y, b.z, id, (byte) b.rotation));
        }
        if (!missing.isEmpty()) {
            log.warn("skipped {} unique unknown block types: {}", missing.size(), missing);
        }
        if (cells.isEmpty()) {
            log.warn("prefab {} has no usable blocks after resolution", prefabPath);
            return;
        }

        int ox = (int) Math.floor(pos.get().x());
        int oy = (int) Math.floor(pos.get().y());
        int oz = (int) Math.floor(pos.get().z());
        Blueprint bp = new Blueprint(ox, oy, oz, cells);
        startBlueprint(s, scheduler, bp,
                prefabPath.getFileName() + " (" + cells.size() + " blocks, " + skippedEmpty + " Empty skipped)");
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
        boolean had = active != null;
        if (s != null && had) {
            s.sendToClient(new HideTriggerVolumePastePrefabPreview());
        }
        // Full debug-channel wipe is only needed when tearing the preview
        // down. The tick loop itself never calls clear() — that's what
        // produced the visible flicker on big prefabs.
        debug.clear();
        active = null;
        lastState = null;
        if (had) {
            log.info("preview cleared");
        }
    }

    boolean isActive() {
        return active != null;
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    /**
     * Common end-of-preview-construction path: tears down any previous
     * preview, publishes the new blueprint, pushes the textured base, and
     * starts the diff tick. Used by both {@link #startSanityPreview} and
     * {@link #loadAndStart}.
     */
    private void startBlueprint(ProxySession s, Scheduler scheduler, Blueprint bp, String label) {
        hide();
        active = bp;
        // Per-cell state cache sized to the new blueprint — left as nulls
        // so the first tick treats every cell as "changed" and pushes a
        // full repaint kicking the preview into a known state.
        lastState = new CellState[bp.blocks.size()];
        sendPastePreview(s, bp);
        tickDiff();                                              // first frame, no waiting
        tickHandle = scheduler.scheduleAtFixedRate(this::tickDiff,
                Duration.ofMillis(DIFF_TICK_MS), Duration.ofMillis(DIFF_TICK_MS));
        log.info("preview started: {} at {},{},{}",
                label, bp.anchorX, bp.anchorY, bp.anchorZ);
    }

    /** Sends the textured ghost layer — relative offsets from the anchor. */
    private void sendPastePreview(ProxySession s, Blueprint bp) {
        BlockChange[] blocks = new BlockChange[bp.blocks.size()];
        for (int i = 0; i < blocks.length; i++) {
            Expected e = bp.blocks.get(i);
            blocks[i] = new BlockChange(e.dx, e.dy, e.dz, e.blockId, e.rotation);
        }
        ShowTriggerVolumePastePrefabPreview pkt = new ShowTriggerVolumePastePrefabPreview();
        pkt.position = new Vector3f(bp.anchorX, bp.anchorY, bp.anchorZ);
        pkt.blocksChange = blocks;
        pkt.biomeTint = DEFAULT_BIOME_TINT;
        pkt.waterTint = DEFAULT_WATER_TINT;
        s.sendToClient(pkt);
    }

    /**
     * Scans the world for changes since the last tick. If nothing moved,
     * sends nothing; the {@code DisplayDebug} shapes already on the client
     * stay there for the next hour ({@link #DEBUG_BOX_TTL_S}) without us
     * touching the wire.
     *
     * <p>When any cell's state disagrees with the cached one we don't try
     * to be clever about deltas — {@code DisplayDebug} carries no shape id,
     * so there's no way to remove or replace a specific shape. Instead we
     * do one {@link DebugRender#clear} and re-emit every non-correct cell.
     * That's a single visible blink at the moment a block is placed or
     * mined, never on idle.
     */
    private void tickDiff() {
        Blueprint bp = active;
        CellState[] cached = lastState;
        if (bp == null || cached == null) return;

        // Pass 1 — sample world state, detect whether anything moved.
        int n = bp.blocks.size();
        CellState[] next = new CellState[n];
        boolean changed = false;
        int correct = 0, wrong = 0, missing = 0;
        for (int i = 0; i < n; i++) {
            Expected e = bp.blocks.get(i);
            Block actual = world.blockAt(bp.anchorX + e.dx, bp.anchorY + e.dy, bp.anchorZ + e.dz);
            int actualId = actual != null && actual.type() != null ? actual.type().id() : 0;
            CellState ns;
            if (actualId == e.blockId) {
                ns = CellState.CORRECT; correct++;
            } else if (actual == null || actual.isAir() || actualId <= 0) {
                ns = CellState.MISSING; missing++;
            } else {
                ns = CellState.WRONG; wrong++;
            }
            next[i] = ns;
            if (cached[i] != ns) changed = true;
        }
        if (!changed) return;                       // idle — wire stays silent.

        // Pass 2 — full repaint. One Clear, one DisplayDebug per non-correct
        // cell. Client sees a single blink, then the new state.
        debug.clear();
        int emitted = 0;
        for (int i = 0; i < n; i++) {
            if (next[i] == CellState.CORRECT) continue;
            Expected e = bp.blocks.get(i);
            float r, g, b;
            if (next[i] == CellState.MISSING) {
                r = 0.4f;  g = 0.85f; b = 1.0f;     // cyan
            } else {
                r = 1.0f;  g = 0.55f; b = 0.1f;     // orange
            }
            debug.box(bp.anchorX + e.dx + 0.5, bp.anchorY + e.dy + 0.5, bp.anchorZ + e.dz + 0.5,
                    OVERLAY_SIZE, OVERLAY_SIZE, OVERLAY_SIZE,
                    r, g, b, DEBUG_BOX_TTL_S);
            emitted++;
        }
        // Atomically publish the new state cache so the next tick compares
        // against what we just painted, not the previous snapshot.
        System.arraycopy(next, 0, cached, 0, n);
        log.debug("repaint — {} correct, {} wrong, {} missing, {} emitted",
                correct, wrong, missing, emitted);
    }

    private ProxySession requireSession() {
        ProxySession s = session;
        if (s == null) {
            log.warn("no session yet — waiting for first Default-channel packet");
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
