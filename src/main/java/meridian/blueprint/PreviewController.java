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
import meridian.core.api.BlockPos;
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

    /** Cap on captured selection volume — guards against accidental
     *  whole-world drags. 5 000 000 blocks ≈ a 171×171×171 room; the
     *  cap is on the raw AABB volume (air included), so a hollow shell
     *  this size still passes through. */
    private static final int MAX_SELECTION_BLOCKS = 5_000_000;

    /** Through-wall {@code worldBox} ids for the selection visualisation.
     *  Stable names so {@code addOrUpdate} replaces the previous shape on
     *  every refresh instead of stacking new ones. */
    private static final String VIEW_ID_AABB = "bp_selection_aabb";
    private static final String VIEW_ID_P1   = "bp_selection_p1";
    private static final String VIEW_ID_P2   = "bp_selection_p2";
    /** Stable id for the live aim-debug overlay (one cube on the block the
     *  player is currently looking at). Lets the toggle clear it cleanly. */
    private static final String VIEW_ID_AIM_DEBUG = "bp_aim_debug";
    /** Cadence of the aim-debug overlay refresh. 300 ms feels responsive
     *  while keeping the wire load to ~3 packets/sec when enabled. */
    private static final Duration AIM_DEBUG_TICK = Duration.ofMillis(300);
    /** Default raycast reach for the corner-picker. The settings slider
     *  overrides this — pick something further when fitting buildings,
     *  closer when the camera keeps catching unintended blocks behind the
     *  one you actually want. */
    private static final double DEFAULT_PICK_RANGE = 7.0;

    /** User-tunable raycast reach passed to {@link Player#lookedAtBlock(double)}.
     *  Volatile because the settings callback runs on the EDT while the
     *  corner-pick reads from the scheduler thread. */
    private volatile double pickRange = DEFAULT_PICK_RANGE;

    private final Logger log;
    private final World world;
    private final DebugRender debug;
    private final BlockNameResolver names;
    private final SelectionStore selection = new SelectionStore();
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
    // Selection — crosshair-driven, no spoof
    // ------------------------------------------------------------------

    /**
     * Records the block the player is currently looking at as corner 1.
     * Uses the proxy's own voxel raycast — {@link InteractionControl#targetedBlock}
     * only updates when the player actually clicks the mouse, which would
     * require breaking / using the block to set a corner. Raycasting from
     * the player's eye along their look vector finds the first solid block
     * without any interaction, just like the in-game crosshair would.
     */
    void setCornerOneFromCrosshair() {
        Optional<BlockPos> hit = lookedAtBlock();
        if (hit.isEmpty()) {
            log.warn("no block under crosshair (no player / no orientation / out of range)");
            return;
        }
        selection.setP1(hit.get());
        log.info("corner 1 = ({},{},{})", hit.get().x(), hit.get().y(), hit.get().z());
        refreshSelectionView();
    }

    /** Same as {@link #setCornerOneFromCrosshair} but for the second corner. */
    void setCornerTwoFromCrosshair() {
        Optional<BlockPos> hit = lookedAtBlock();
        if (hit.isEmpty()) {
            log.warn("no block under crosshair (no player / no orientation / out of range)");
            return;
        }
        selection.setP2(hit.get());
        log.info("corner 2 = ({},{},{})", hit.get().x(), hit.get().y(), hit.get().z());
        refreshSelectionView();
    }

    /** Sets the raycast reach used by the corner picker and the aim-debug
     *  overlay. Clamped to {@code >= 1} to keep zero-range probes from
     *  silently failing. */
    void setPickRange(int blocks) {
        pickRange = Math.max(1, blocks);
    }

    /** Helper — fetches the looked-at block through the core service.
     *  Centralises the "no player yet" guard so the corner setters and
     *  the debug overlay share the same null-checks. */
    private Optional<BlockPos> lookedAtBlock() {
        return world.player().flatMap(p -> p.lookedAtBlock(pickRange));
    }

    /**
     * DDA voxel traversal from the player's eye along their look direction.
     * Returns the first non-air block hit, or empty if nothing solid sits
     * inside {@link #RAY_MAX_DIST}.
     *
     * <p>The DDA is the standard "Amanatides &amp; Woo" algorithm — at every
     * step we advance to the next voxel boundary along whichever axis has
     * the smallest {@code tMax}, and check whether the new cell is solid.
     */
    // ------------------------------------------------------------------
    // Live aim-debug overlay — toggled from settings; emits a worldBox at
    // the currently-looked-at block every 300 ms so the player sees the
    // raycast result in real time and can verify it points where they
    // expect (great for diagnosing axis-sign / eye-height issues).
    // ------------------------------------------------------------------

    /** Handle to the 300 ms aim-debug repaint task; null when toggled off. */
    private volatile ScheduledFuture<?> aimDebugHandle;

    /**
     * Turns the aim-debug overlay on or off. When on, a scheduled tick
     * every {@link #AIM_DEBUG_TICK} reads {@code Player.lookedAtBlock} and
     * re-emits a stable-id {@code worldBox} on the result. When off, the
     * task is cancelled and the box cleared.
     */
    void setAimDebug(boolean enabled, Scheduler scheduler) {
        ScheduledFuture<?> h = aimDebugHandle;
        if (enabled) {
            if (h != null) return;                     // already on
            aimDebugHandle = scheduler.scheduleAtFixedRate(this::tickAimDebug,
                    AIM_DEBUG_TICK, AIM_DEBUG_TICK);
            log.info("aim-debug overlay ON");
        } else {
            if (h != null) {
                h.cancel(false);
                aimDebugHandle = null;
            }
            debug.clearWorldBox(VIEW_ID_AIM_DEBUG);
            log.info("aim-debug overlay OFF");
        }
    }

    private void tickAimDebug() {
        Optional<BlockPos> hit = lookedAtBlock();
        if (hit.isEmpty()) {
            // Don't clear here — we'd flicker every time the raycast misses
            // air between solids. The shape's id stability keeps the last
            // known target visible until a new one is found.
            return;
        }
        BlockPos p = hit.get();
        // 1.02 inflate to overpower the world block's faces visibly; bright
        // green so it stands out from the magenta / yellow corner markers.
        debug.worldBox(VIEW_ID_AIM_DEBUG,
                p.x() + 0.5, p.y() + 0.5, p.z() + 0.5,
                1.02, 1.02, 1.02,
                0.2f, 1.0f, 0.4f, 0.55f);
    }

    /**
     * Writes the current selection as a {@code .prefab.json} into
     * {@code prefabsDir}. Air voxels are skipped; the file is named
     * {@code <name>.prefab.json} after sanitisation. The library picks
     * the new file up on its next scan and it appears in the LiveList.
     */
    void saveSelectionAs(Path prefabsDir, String name) {
        Optional<SelectionStore.Box> bOpt = selection.box();
        if (bOpt.isEmpty()) {
            log.warn("save aborted — selection incomplete, set both corners first");
            return;
        }
        if (name == null || name.isBlank()) {
            log.warn("save aborted — prefab name is empty");
            return;
        }
        SelectionStore.Box b = bOpt.get();
        if (b.blockCount() > MAX_SELECTION_BLOCKS) {
            log.warn("save aborted — selection too large ({} blocks, cap {})",
                    b.blockCount(), MAX_SELECTION_BLOCKS);
            return;
        }
        try {
            int blocks = PrefabSaver.save(world, b, prefabsDir, name.trim());
            log.info("saved {} blocks → {}/{}.prefab.json", blocks, prefabsDir, name.trim());
        } catch (IOException e) {
            log.warn("save failed", e);
        }
    }

    /** Wipes both corners and the on-screen visualisation. */
    void clearSelection() {
        selection.clear();
        debug.clearWorldBox(VIEW_ID_AABB);
        debug.clearWorldBox(VIEW_ID_P1);
        debug.clearWorldBox(VIEW_ID_P2);
        log.info("selection cleared");
    }

    /**
     * Re-emits the through-wall selection visualisation. Three shapes:
     * a translucent cyan AABB spanning whatever corners are set, plus a
     * pair of bright opaque cubes pinpointing P1 (magenta) and P2 (yellow).
     * The AABB stays low-opacity so the blocks inside still read; corner
     * markers are saturated so the player sees exactly which voxel each
     * pick landed on.
     *
     * <p>Each shape has a stable id so successive calls replace the
     * previous shape in place — no flicker, no de-duping concerns.
     */
    private void refreshSelectionView() {
        Optional<BlockPos> a = selection.p1(), c = selection.p2();
        if (a.isPresent()) {
            BlockPos p = a.get();
            // 1.01 inflate over the unit cube so the marker edges don't
            // z-fight the world block we just picked.
            debug.worldBox(VIEW_ID_P1,
                    p.x() + 0.5, p.y() + 0.5, p.z() + 0.5,
                    1.01, 1.01, 1.01,
                    1.0f, 0.2f, 1.0f, 0.6f);   // magenta, fairly opaque
        }
        if (c.isPresent()) {
            BlockPos p = c.get();
            debug.worldBox(VIEW_ID_P2,
                    p.x() + 0.5, p.y() + 0.5, p.z() + 0.5,
                    1.01, 1.01, 1.01,
                    1.0f, 0.9f, 0.2f, 0.6f);   // yellow, fairly opaque
        }
        selection.box().ifPresent(b -> {
            // Centre on the AABB; full dimensions are the box edge lengths.
            double cx = (b.xMin() + b.xMax() + 1) / 2.0;
            double cy = (b.yMin() + b.yMax() + 1) / 2.0;
            double cz = (b.zMin() + b.zMax() + 1) / 2.0;
            debug.worldBox(VIEW_ID_AABB, cx, cy, cz,
                    b.sizeX(), b.sizeY(), b.sizeZ(),
                    0.3f, 0.85f, 1.0f, 0.18f);  // soft cyan, low opacity
        });
    }

    /**
     * Scans the world inside the selection box, builds a blueprint, and
     * starts a preview anchored on the box's min corner. Behaviour mirrors
     * {@link #loadAndStart} once the blueprint exists — same paste-style
     * base + diff overlay.
     */
    void captureSelection(Scheduler scheduler) {
        ProxySession s = requireSession();
        if (s == null) return;
        Optional<SelectionStore.Box> bOpt = selection.box();
        if (bOpt.isEmpty()) {
            log.warn("selection incomplete — set both corners first");
            return;
        }
        SelectionStore.Box b = bOpt.get();
        int volume = b.blockCount();
        if (volume > MAX_SELECTION_BLOCKS) {
            log.warn("selection too large ({} blocks, cap {})", volume, MAX_SELECTION_BLOCKS);
            return;
        }

        int[] dx = new int[volume], dy = new int[volume], dz = new int[volume], ids = new int[volume];
        byte[] rot = new byte[volume];
        int i = 0;
        for (int y = b.yMin(); y <= b.yMax(); y++) {
            for (int z = b.zMin(); z <= b.zMax(); z++) {
                for (int x = b.xMin(); x <= b.xMax(); x++) {
                    Block blk = world.blockAt(x, y, z);
                    int id = blk != null && blk.type() != null ? blk.type().id() : 0;
                    if (id <= 0) continue;             // skip air
                    dx[i] = x - b.xMin();
                    dy[i] = y - b.yMin();
                    dz[i] = z - b.zMin();
                    ids[i] = id;
                    rot[i] = 0;
                    i++;
                }
            }
        }
        if (i == 0) {
            log.warn("selection contains no solid blocks");
            return;
        }
        Blueprint bp = new Blueprint(b.xMin(), b.yMin(), b.zMin(),
                trim(dx, i), trim(dy, i), trim(dz, i), trim(ids, i), trim(rot, i));
        startBlueprint(s, scheduler, bp,
                "selection (" + i + "/" + volume + " blocks, "
                        + b.sizeX() + "x" + b.sizeY() + "x" + b.sizeZ() + ")");
    }

    /** Status string for the settings liveText. */
    String selectionStatus() {
        Optional<BlockPos> a = selection.p1(), c = selection.p2();
        if (a.isEmpty() && c.isEmpty()) return "no corners set";
        if (a.isEmpty()) return "corner 1 not set";
        if (c.isEmpty()) return "corner 2 not set (P1 = " + fmt(a.get()) + ")";
        return selection.box().map(b -> b.sizeX() + "x" + b.sizeY() + "x" + b.sizeZ()
                + " (" + b.blockCount() + " blocks)").orElse("");
    }

    private static String fmt(BlockPos p) {
        return p.x() + "," + p.y() + "," + p.z();
    }

    private static int[] trim(int[] src, int len) {
        if (len == src.length) return src;
        int[] out = new int[len];
        System.arraycopy(src, 0, out, 0, len);
        return out;
    }

    private static byte[] trim(byte[] src, int len) {
        if (len == src.length) return src;
        byte[] out = new byte[len];
        System.arraycopy(src, 0, out, 0, len);
        return out;
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

    /**
     * Translates the active preview by {@code (dx,dy,dz)} world blocks.
     * Rebuilds the blueprint with a shifted anchor (cell arrays are shared by
     * reference — same memory, just a new anchor) and swaps in a fresh
     * painter wired to it. The layer window is preserved.
     *
     * <p>No-op when nothing is showing.
     */
    void shiftPreview(int dx, int dy, int dz) {
        Blueprint bp = activeBlueprint;
        if (bp == null) {
            log.warn("move ignored — no active preview");
            return;
        }
        if (dx == 0 && dy == 0 && dz == 0) return;

        swapInBlueprint(new Blueprint(
                bp.anchorX + dx, bp.anchorY + dy, bp.anchorZ + dz,
                bp.dx, bp.dy, bp.dz, bp.blockId, bp.rotation));
    }

    /**
     * Rotates the active preview 90° CCW around the given axis (right-hand
     * rule: thumb points along the axis, fingers curl in the rotation
     * direction). {@code axis} — 0 = X (pitch), 1 = Y (yaw), 2 = Z (roll).
     * Geometry only — per-block rotation states (stairs, doors) are not
     * adjusted, so a rotated stair will still face its original direction.
     * Click four times to come back to where you started.
     *
     * <p>Cells are re-normalised to non-negative offsets after the rotation,
     * with the difference folded into the anchor so world positions are
     * unchanged at the moment of transform.
     */
    void rotatePreview(int axis) {
        transformPreview(axis, false);
    }

    /**
     * Mirrors the active preview across the plane perpendicular to the given
     * axis. {@code axis} — 0 = X, 1 = Y, 2 = Z. Geometry only (per-block
     * rotation states not adjusted).
     */
    void mirrorPreview(int axis) {
        transformPreview(axis, true);
    }

    private void transformPreview(int axis, boolean mirror) {
        Blueprint bp = activeBlueprint;
        if (bp == null) {
            log.warn("transform ignored — no active preview");
            return;
        }
        if (axis < 0 || axis > 2) return;

        int n = bp.size;
        int[] nx = new int[n], ny = new int[n], nz = new int[n];
        for (int i = 0; i < n; i++) {
            int x = bp.dx[i], y = bp.dy[i], z = bp.dz[i];
            if (mirror) {
                nx[i] = (axis == 0) ? -x : x;
                ny[i] = (axis == 1) ? -y : y;
                nz[i] = (axis == 2) ? -z : z;
            } else {
                switch (axis) {
                    case 0 -> { nx[i] = x;  ny[i] = -z; nz[i] =  y; }   // around X
                    case 1 -> { nx[i] = z;  ny[i] =  y; nz[i] = -x; }   // around Y
                    case 2 -> { nx[i] = -y; ny[i] =  x; nz[i] =  z; }   // around Z
                }
            }
        }

        int minX = 0, minY = 0, minZ = 0;
        if (n > 0) {
            minX = nx[0]; minY = ny[0]; minZ = nz[0];
            for (int i = 1; i < n; i++) {
                if (nx[i] < minX) minX = nx[i];
                if (ny[i] < minY) minY = ny[i];
                if (nz[i] < minZ) minZ = nz[i];
            }
            for (int i = 0; i < n; i++) {
                nx[i] -= minX;
                ny[i] -= minY;
                nz[i] -= minZ;
            }
        }

        swapInBlueprint(new Blueprint(
                bp.anchorX + minX, bp.anchorY + minY, bp.anchorZ + minZ,
                nx, ny, nz, bp.blockId, bp.rotation));
    }

    /** Replaces the active blueprint with {@code newBp}, rebuilds the
     *  painter, preserves the layer window, and re-issues the textured
     *  preview. Shared tail for shift / rotate / mirror. */
    private void swapInBlueprint(Blueprint newBp) {
        ProxySession s = session;
        DiffPainter oldPainter = painter;
        if (s == null || oldPainter == null) return;

        DiffPainter p = new DiffPainter(log, world, debug, newBp);
        if (windowSize > 0) {
            currentLayer = clampLayer(currentLayer, p);
            p.setWindow(currentLayer, windowSize);
        }
        // Wipe the old painter's debug cubes; selection markers (worldBox)
        // live on a different packet path and survive this call.
        oldPainter.clearShapes();
        painter = p;
        activeBlueprint = newBp;
        s.sendToClient(new HideTriggerVolumePastePrefabPreview());
        sendPastePreview(s, newBp, p.windowLo(), p.windowHi());
        p.tick();
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
