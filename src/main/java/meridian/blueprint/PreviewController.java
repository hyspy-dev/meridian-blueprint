package meridian.blueprint;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.ScheduledFuture;
import meridian.api.module.Scheduler;
import meridian.core.api.Block;
import meridian.core.api.BlockPos;
import meridian.core.api.BlockView;
import meridian.core.api.BuilderSelection;
import meridian.core.api.Clipboard;
import meridian.core.api.DebugRender;
import meridian.core.api.PastePreview;
import meridian.core.api.Player;
import meridian.core.api.Prefabs;
import meridian.core.api.Rotation;
import meridian.core.api.Vec3;
import meridian.core.api.World;
import meridian.core.api.WorldState;
import org.slf4j.Logger;

/**
 * Lifecycle for an active blueprint preview. Owns the scheduler handle for the diff tick, and
 * emits the textured paste-preview at start / on layer change / hide through the core
 * {@link PastePreview} service - core owns the wire, this owns what is drawn.
 *
 * <p>Everything visual is delegated:
 * <ul>
 *   <li>Core's {@link Prefabs} reads, writes and captures prefabs, and resolves one into
 *       {@link Clipboard} blocks; {@link Blueprint#of} lays those out for the painter.</li>
 *   <li>{@link DiffPainter} runs the per-tick scan and the
 *       {@code DisplayDebug} overlay.</li>
 * </ul>
 *
 * <p>Layer slicing: the controller stores user-configured {@code currentLayer}
 * and {@code windowSize}, applies them to both the {@link DiffPainter}
 * (overlay) and the {@link PastePreview} payload (textured base). Changing either re-sends
 * paste-preview filtered to the new window, so texture and overlay stay aligned.
 */
final class PreviewController {


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
    /** The block catalog: each block's flip type, for a mirror. */
    private final WorldState worldState;
    /** The server's prefab files, and the world taken into one. */
    private final Prefabs prefabs;
    private final SelectionStore selection = new SelectionStore();
    /** Core owns the wire; this draws the ghost. Null-safe: no client, no-op. */
    private final PastePreview pastePreview;
    /** The native area-selection tool, when the line has it; null on 0.5.9, where only the
     *  crosshair corner-pick exists. */
    private final BuilderSelection builderSelection;
    private volatile boolean toolsWired;

    // Our own tools. The client only knows the items its server sent it, so each is declared to core
    // as a copy of a native builder tool - that gives it an icon and working behaviour - carrying our
    // id, name and description.
    private static final String TOOL_LAYERS = "meridian_bp_layers";

    /** What our tool is called, looks like and does. */
    private static final List<BuilderSelection.ToolSpec> TOOL_SPECS = List.of(
            new BuilderSelection.ToolSpec(TOOL_LAYERS, BuilderSelection.LAYERS_TOOL,
                    "Blueprint", "Left click: next layer. Right click: previous layer. F: blueprint panel"));

    /** The blueprint tool's F: layers and preview, as in the module's settings. */
    private volatile BlueprintPanelWindow panelWindow;
    /** The selection tool's Use: save the selection as a prefab. */
    private volatile SavePrefabWindow saveWindow;

    void setWindows(BlueprintPanelWindow panel, SavePrefabWindow save) {
        panelWindow = panel;
        saveWindow = save;
    }

    /** Says something to the player in chat; a no-op until the module wires it. */
    private volatile java.util.function.Consumer<String> chat = text -> { };

    void setChat(java.util.function.Consumer<String> chat) {
        this.chat = chat == null ? text -> { } : chat;
    }

    private void tell(String text) {
        log.info("tell player: {}", text);
        chat.accept(text);
    }

    /** How far a preview moves per step, shared by the settings panel and the in-game one. */
    private volatile int moveStep = 1;

    int moveStep() {
        return moveStep;
    }

    void setMoveStep(int step) {
        moveStep = Math.max(1, Math.min(256, step));
    }

    int windowSize() {
        return windowSize;
    }
    /**
     * What the game's paste tool holds: the blueprint last chosen or selected, its cells relative
     * to its anchor and unturned - core turns it as the tool is turned. Null until something is chosen.
     */
    private volatile Blueprint clipboard;
    /** Whether our tools are up - the paste tool holds our clipboard only while they are. */
    private volatile boolean blueprinting;
    /** For the projection a paste puts down; handed over when blueprinting is switched on. */
    private volatile Scheduler pasteScheduler;

    /** True once we've warned about no client yet - keeps the log quiet on repeat clicks. */
    private volatile boolean warnedNoClient;

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

    /** The module's prefabs, for the paste tool's prefab list (its E key). */
    private volatile PrefabLibrary library;

    void setLibrary(PrefabLibrary library) {
        this.library = library;
    }

    PreviewController(Logger log, World world, WorldState worldState, Prefabs prefabs, DebugRender debug,
                      PastePreview pastePreview, BuilderSelection builderSelection) {
        this.log = log;
        this.world = world;
        this.worldState = worldState;
        this.prefabs = prefabs;
        this.debug = debug;
        this.pastePreview = pastePreview;
        this.builderSelection = builderSelection;
    }

    /** Whether the native in-game selection tool can be offered - the 0.6 line and later. */
    boolean hasBuilderTools() {
        return builderSelection != null;
    }

    /**
     * Shows or hides the native in-game selection tool.
     *
     * <p>When on, the player drags a box with the real tool and it fills both corners at once -
     * no crosshair picking. The box the client reports is read by core and dropped before the
     * server sees it, so it is only ever a selection, never an edit. The crosshair pick still
     * works alongside, for anyone who prefers it.
     */
    void setBlueprinting(boolean on, Scheduler scheduler) {
        if (builderSelection == null) {
            log.warn("builder tools are not available on this server line");
            return;
        }
        pasteScheduler = scheduler;
        blueprinting = on;
        if (on && !toolsWired) {
            // The selection tool's drag fills the box, as before.
            builderSelection.onSelection(box -> {
                // Just remember it - the client already draws the selection box itself, so we do
                // not paint our own.
                selection.setP1(new BlockPos(box.xMin(), box.yMin(), box.zMin()));
                selection.setP2(new BlockPos(box.xMax(), box.yMax(), box.zMax()));
                log.info("selection = ({},{},{}) .. ({},{},{})",
                        box.xMin(), box.yMin(), box.zMin(), box.xMax(), box.yMax(), box.zMax());
            });
            // F on any of our tools comes back here. For now we only say which - the popups that
            // each opens are the next step; this is the round-trip we are proving first.
            builderSelection.onTool(this::onTool);
            // The game's own paste tool, holding our blueprint: it follows the cursor and turns on
            // its usual keys by itself, and core answers the turns and mirrors as the server would;
            // we hear where it was pasted, and the clipboard as the tool held it then.
            builderSelection.onPaste(new BuilderSelection.PasteListener() {
                @Override public void turned(BuilderSelection.Axis axis, int degrees) {
                    log.info("paste tool turned {} by {}", axis, degrees);
                }
                @Override public void flipped(BuilderSelection.Axis axis) {
                    log.info("paste tool flipped across {}", axis);
                }
                @Override public void copyAsked() {
                    copySelection();
                }
                @Override public void listAsked() {
                    openPrefabList();
                }
                @Override public void reset() {
                    log.info("paste tool turns reset");
                }
                @Override public void pasted(int x, int y, int z, Clipboard turned) {
                    placeClipboard(x, y, z, turned);
                }
            });
            builderSelection.defineTools(TOOL_SPECS);   // teach the client our items
            toolsWired = true;
        }
        builderSelection.setClipboard(on ? clipboardHeld() : null);
        builderSelection.forceTools(on
                ? List.of(
                        BuilderSelection.SELECTION_TOOL,   // slot 0: outline a selection
                        BuilderSelection.PASTE_TOOL,       // slot 1: place a projection
                        TOOL_LAYERS)                       // slot 2: layers, and the panel on F
                : List.of());
        log.info("blueprinting {}", on ? "on - tools forced into the toolbar" : "off - tools cleared");
    }

    /**
     * The player acted on a tool: the blueprint tool steps layers on its clicks and opens the
     * panel on F; Use on the selection tool offers to save what is selected.
     */
    private void onTool(String toolId, BuilderSelection.Interaction kind) {
        String name = BuilderSelection.SELECTION_TOOL.equals(toolId) ? "SELECTION"
                : BuilderSelection.PASTE_TOOL.equals(toolId) ? "PASTE"
                : TOOL_LAYERS.equals(toolId) ? "BLUEPRINT" : toolId;
        log.info("{} on {} tool", kind, name);
        if (TOOL_LAYERS.equals(toolId)) {
            switch (kind) {
                case PRIMARY -> stepLayer(+1);
                case SECONDARY -> stepLayer(-1);
                case USE -> {
                    BlueprintPanelWindow w = panelWindow;
                    if (w != null) {
                        w.open();
                    }
                }
                default -> { }
            }
        } else if (BuilderSelection.SELECTION_TOOL.equals(toolId) && kind == BuilderSelection.Interaction.USE) {
            SavePrefabWindow w = saveWindow;
            if (w != null) {
                w.open();
            }
        }
    }

    /**
     * One layer up or down, wrapping past either end - with a window of one layer when none was
     * set, since stepping through "all of them" would show nothing change.
     *
     * @return the layer now at the bottom of the window, or null with no projection up
     */
    Integer stepLayer(int direction) {
        DiffPainter p = painter;
        if (p == null) {
            return null;
        }
        if (windowSize <= 0) {
            setWindow(1);
        }
        int next = currentLayer + direction;
        if (next > p.maxLayer()) {
            next = p.minLayer();
        } else if (next < p.minLayer()) {
            next = p.maxLayer();
        }
        shiftLayer(next - currentLayer);
        log.info("layer {} of {}..{}", currentLayer, p.minLayer(), p.maxLayer());
        return currentLayer;
    }

    /**
     * A ghost straight from the selection, nothing saved: the selection goes into the paste tool -
     * the copy key, and the selection window's "Copy to buffer". Off the calling thread, since a
     * big selection is many block reads.
     */
    void copySelection() {
        Scheduler s = pasteScheduler;
        if (!hasSelection()) {
            tell("Select an area first.");
        } else if (s != null) {
            s.schedule(() -> captureSelection(s), Duration.ZERO);
        }
    }

    /** Whether both corners of a selection are set. */
    boolean hasSelection() {
        return selection.box().isPresent();
    }

    // ------------------------------------------------------------------
    // Entry points — preview creation
    // ------------------------------------------------------------------

    /**
     * Drops a 3×3×3 sanity cube of whatever block the player is standing on.
     * Used as a self-test for the preview pipeline — no disk, no catalog
     * lookup, only needs a client and a known player position.
     */
    void startSanityPreview(Scheduler scheduler) {
        if (!requireReady()) return;
        Optional<Vec3> pos = playerPosition();
        if (pos.isEmpty()) return;
        int ox = (int) Math.floor(pos.get().x());
        int oy = (int) Math.floor(pos.get().y());
        int oz = (int) Math.floor(pos.get().z());
        OptionalInt standing = sampleStandingBlockId(ox, oy - 1, oz);
        if (standing.isEmpty()) {
            log.warn("no block to build the sanity cube of - the catalog has not arrived");
            return;
        }
        int blockId = standing.getAsInt();

        Blueprint bp = BlueprintBuilder.sanityCube(ox, oy, oz, blockId);
        startBlueprint(scheduler, bp, "sanity cube (blockId=" + blockId + ")");
    }

    /**
     * Loads a {@code .prefab.json} from disk and starts a preview anchored on
     * the block under the player. The author's anchor inside the file is
     * ignored on purpose — preview UX is "paste at my feet" regardless of
     * authoring offsets.
     */
    void loadAndStart(Scheduler scheduler, Path prefabPath) {
        loadAndStartAt(scheduler, prefabPath);
    }

    /**
     * A {@code .prefab.json} as a blueprint anchored at the origin - its blocks relative to the
     * file's anchor, unknown block names left out. Reads the whole file: keep it off the network
     * thread.
     */
    Optional<Blueprint> readPrefab(Path prefabPath) {
        return resolvePrefab(prefabPath, 0, 0, 0).map(Loaded::blueprint);
    }

    /** A prefab as a blueprint, and what was left out of it. */
    private record Loaded(Blueprint blueprint, int skippedEmpty) {}

    /** A prefab file, resolved to this connection's blocks and anchored at {@code (x, y, z)}. */
    private Optional<Loaded> resolvePrefab(Path prefabPath, int x, int y, int z) {
        Prefabs.Resolved resolved;
        Prefabs.Prefab prefab;
        try {
            prefab = prefabs.read(prefabPath);
            resolved = prefabs.resolve(prefab);
        } catch (IOException e) {
            log.warn("failed to read prefab {}", prefabPath, e);
            return Optional.empty();
        }
        if (!resolved.unknown().isEmpty()) {
            log.warn("skipped {} unique unknown block types: {}", resolved.unknown().size(), resolved.unknown());
        }
        Blueprint bp = Blueprint.of(x, y, z, withAir(prefab, resolved.clipboard()));
        return bp.size == 0 ? Optional.empty() : Optional.of(new Loaded(bp, resolved.empty()));
    }

    /**
     * The prefab's empty cells kept as cells of block 0 - air, where the blueprint wants nothing: the
     * overlay paints a block there as wrong, and building breaks it. They go through the paste tool's
     * turns with the rest, and are never drawn as a ghost.
     */
    private static Clipboard withAir(Prefabs.Prefab prefab, Clipboard clipboard) {
        List<PastePreview.Change> blocks = new ArrayList<>(clipboard.blocks());
        boolean any = false;
        for (Prefabs.Cell c : prefab.blocks()) {
            if (c.filler() == 0 && meridian.core.api.BlockView.EMPTY.equals(c.name())) {
                blocks.add(new PastePreview.Change(c.x() - prefab.anchorX(), c.y() - prefab.anchorY(),
                        c.z() - prefab.anchorZ(), 0, 0));
                any = true;
            }
        }
        return any ? new Clipboard(blocks, clipboard.fluids()) : clipboard;
    }

    /** Puts a prefab file into the paste tool - the prefab list's Load. */
    void loadPrefabIntoPasteTool(Path prefabPath) {
        Optional<Blueprint> bp = readPrefab(prefabPath);
        if (bp.isEmpty()) {
            log.warn("prefab {} has no usable blocks", prefabPath);
            return;
        }
        String label = prefabPath.getFileName() + " (" + bp.get().size + " blocks)";
        if (!loadIntoPasteTool(bp.get(), label)) {
            log.info("{} not loaded - blueprinting is off", label);
        }
    }

    private void loadAndStartAt(Scheduler scheduler, Path prefabPath) {
        if (!requireReady()) return;
        Optional<Vec3> pos = playerPosition();
        if (pos.isEmpty()) return;

        int ox = (int) Math.floor(pos.get().x());
        int oy = (int) Math.floor(pos.get().y());
        int oz = (int) Math.floor(pos.get().z());
        Optional<Loaded> loaded = resolvePrefab(prefabPath, ox, oy, oz);
        if (loaded.isEmpty()) {
            log.warn("prefab {} has no usable blocks after resolution", prefabPath);
            return;
        }
        Blueprint bp = loaded.get().blueprint();
        String label = prefabPath.getFileName() + " (" + bp.size + " blocks, "
                + loaded.get().skippedEmpty() + " Empty skipped)";
        if (loadIntoPasteTool(bp, label)) {
            return;
        }
        startBlueprint(scheduler, bp, label);
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
        DiffPainter p = painter;
        if (p != null) {
            pastePreview.hide();
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
        saveSelection(prefabsDir, name);
    }

    /**
     * {@link #saveSelectionAs}, saying how it went.
     *
     * @return the blocks written, or a sentence why nothing was
     */
    Object saveSelection(Path prefabsDir, String name) {
        Optional<SelectionStore.Box> bOpt = selection.box();
        if (bOpt.isEmpty()) {
            log.warn("save aborted — selection incomplete, set both corners first");
            return "Select an area first";
        }
        if (name == null || name.isBlank()) {
            log.warn("save aborted — prefab name is empty");
            return "The name is empty";
        }
        SelectionStore.Box b = bOpt.get();
        if (b.blockCount() > MAX_SELECTION_BLOCKS) {
            log.warn("save aborted — selection too large ({} blocks, cap {})",
                    b.blockCount(), MAX_SELECTION_BLOCKS);
            return "The selection is too large (" + b.blockCount() + " blocks, at most "
                    + MAX_SELECTION_BLOCKS + ")";
        }
        try {
            int blocks = PrefabSaver.save(prefabs, b, prefabsDir, name.trim());
            log.info("saved {} blocks → {}/{}.prefab.json", blocks, prefabsDir, name.trim());
            return blocks;
        } catch (IOException e) {
            log.warn("save failed", e);
            return "Saving failed: " + e.getMessage();
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
        if (!requireReady()) return;
        Optional<SelectionStore.Box> bOpt = selection.box();
        if (bOpt.isEmpty()) {
            log.warn("selection incomplete — set both corners first");
            tell("Select an area first.");
            return;
        }
        SelectionStore.Box b = bOpt.get();
        int volume = b.blockCount();
        if (volume > MAX_SELECTION_BLOCKS) {
            log.warn("selection too large ({} blocks, cap {})", volume, MAX_SELECTION_BLOCKS);
            tell("The selection is too large (" + volume + " blocks, at most " + MAX_SELECTION_BLOCKS + ").");
            return;
        }

        // The same capture a save makes, put down as a paste would: the cells a door or a bed only
        // fills are left out - placing it on its own cell brings them back.
        Prefabs.Resolved resolved = prefabs.resolve(prefabs.capture(
                new BlockPos(b.xMin(), b.yMin(), b.zMin()), new BlockPos(b.xMax(), b.yMax(), b.zMax())));
        Blueprint bp = Blueprint.of(b.xMin(), b.yMin(), b.zMin(), resolved.clipboard());
        int i = bp.size;
        if (i == 0) {
            log.warn("selection contains no solid blocks");
            return;
        }
        String label = "selection (" + i + "/" + volume + " blocks, "
                + b.sizeX() + "x" + b.sizeY() + "x" + b.sizeZ() + ")";
        if (loadIntoPasteTool(bp, label)) {
            return;
        }
        startBlueprint(scheduler, bp, label);
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
                bp.dx, bp.dy, bp.dz, bp.blockId, bp.rotation, bp.fluids));
    }

    /**
     * Rotates the active preview 90° CCW around the given axis (right-hand
     * rule: thumb points along the axis, fingers curl in the rotation
     * direction). {@code axis} — 0 = X (pitch), 1 = Y (yaw), 2 = Z (roll).
     * Every block turns with it - a stair keeps facing the same way relative
     * to the building, the way the server turns a pasted selection.
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
     * axis. {@code axis} — 0 = X, 1 = Y, 2 = Z. Every block is mirrored with
     * it, each the way the server mirrors it - by its own flip type ({@link Clipboard#flipped}).
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
        if (axis < Rotation.X || axis > Rotation.Z) return;

        Clipboard c = bp.toClipboard();
        c = mirror ? c.flipped(axis, flipTypes()) : c.turned(axis, 1);
        // Back to non-negative offsets, the difference folded into the anchor, so nothing moves in
        // the world at the moment of the transform but the cells about the anchor.
        int minX = 0, minY = 0, minZ = 0;
        if (!c.blocks().isEmpty() || !c.fluids().isEmpty()) {
            minX = minY = minZ = Integer.MAX_VALUE;
            for (PastePreview.Change ch : c.blocks()) {
                minX = Math.min(minX, ch.dx()); minY = Math.min(minY, ch.dy()); minZ = Math.min(minZ, ch.dz());
            }
            for (PastePreview.FluidChange f : c.fluids()) {
                minX = Math.min(minX, f.dx()); minY = Math.min(minY, f.dy()); minZ = Math.min(minZ, f.dz());
            }
        }
        List<PastePreview.Change> blocks = new ArrayList<>(c.blocks().size());
        for (PastePreview.Change ch : c.blocks()) {
            blocks.add(new PastePreview.Change(ch.dx() - minX, ch.dy() - minY, ch.dz() - minZ, ch.blockId(),
                    ch.rotation()));
        }
        List<PastePreview.FluidChange> fluids = new ArrayList<>(c.fluids().size());
        for (PastePreview.FluidChange f : c.fluids()) {
            fluids.add(new PastePreview.FluidChange(f.dx() - minX, f.dy() - minY, f.dz() - minZ, f.fluidId(),
                    f.level()));
        }
        swapInBlueprint(Blueprint.of(bp.anchorX + minX, bp.anchorY + minY, bp.anchorZ + minZ,
                new Clipboard(blocks, fluids)));
    }

    /** Each block's flip type by id, as core read it from the game's files. */
    private java.util.function.IntFunction<Rotation.Flip> flipTypes() {
        Map<Integer, Rotation.Flip> byId = new HashMap<>();
        for (BlockView t : worldState.allBlockTypes()) {
            byId.put(t.id(), t.flip());
        }
        return byId::get;
    }

    /** Replaces the active blueprint with {@code newBp}, rebuilds the
     *  painter, preserves the layer window, and re-issues the textured
     *  preview. Shared tail for shift / rotate / mirror. */
    private void swapInBlueprint(Blueprint newBp) {
        DiffPainter oldPainter = painter;
        if (oldPainter == null) return;

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
        pastePreview.hide();
        sendPastePreview(newBp, p.windowLo(), p.windowHi());
        p.tick();
    }

    // ------------------------------------------------------------------
    // Status (for liveText)
    // ------------------------------------------------------------------

    String statusLine() {
        DiffPainter p = painter;
        if (p == null) return "idle";
        return p.lastCorrect() + " correct / " + p.lastMissing() + " missing / "
                + p.lastWrong() + " wrong / " + p.lastRotated() + " turned wrong (of "
                + p.totalCells() + ")";
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

    private void startBlueprint(Scheduler scheduler, Blueprint bp, String label) {
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
        sendPastePreview(bp, p.windowLo(), p.windowHi());
        p.tick();                                          // first frame, no waiting
        tickHandle = scheduler.scheduleAtFixedRate(this::tickIfActive,
                DIFF_TICK, DIFF_TICK);
        log.info("preview started: {} at {},{},{}",
                label, bp.anchorX, bp.anchorY, bp.anchorZ);
    }

    private void applyLayerWindow() {
        DiffPainter p = painter;
        Blueprint bp = activeBlueprint;
        if (p == null || bp == null) return;
        currentLayer = clampLayer(currentLayer, p);
        p.setWindow(currentLayer, windowSize);
        // Re-issue the textured base filtered to the active window. The
        // client doesn't merge successive Show packets, so we hide first
        // and re-issue to avoid the old layers lingering underneath.
        pastePreview.hide();
        sendPastePreview(bp, p.windowLo(), p.windowHi());
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
    /**
     * With the tools out, a chosen blueprint goes into the game's paste tool and nowhere else: the
     * client draws it under the cursor itself, and the projection appears only where it is pasted.
     * Without the tools - switched off, or a server line that has none - it is drawn at the
     * player's feet as before.
     *
     * @return whether the paste tool took it
     */
    private boolean loadIntoPasteTool(Blueprint bp, String label) {
        loadClipboard(bp);
        if (builderSelection == null || !blueprinting) {
            return false;
        }
        log.info("{} loaded into the paste tool", label);
        tell("In the paste tool: " + label);
        return true;
    }

    /** Makes {@code bp} what the paste tool holds, unturned, and hands it over if the tools are up. */
    private void loadClipboard(Blueprint bp) {
        clipboard = bp;
        if (builderSelection != null && blueprinting) {
            builderSelection.setClipboard(clipboardHeld());
        }
    }

    /** The blueprint placed in the world now - the one the overlay paints; null when none is. */
    Blueprint active() {
        return activeBlueprint;
    }

    /** The paste tool's clipboard, relative to its anchor and unturned; null when nothing is chosen. */
    private Clipboard clipboardHeld() {
        Blueprint bp = clipboard;
        return bp == null ? null : bp.toClipboard();
    }

    /**
     * Puts the projection down where the paste tool pasted: the clipboard as the tool held it -
     * turned and mirrored by core the way the player turned it - with its anchor at the pasted
     * block: what the server would have built there. Nothing reaches the world; the projection is
     * what moves.
     */
    private void placeClipboard(int x, int y, int z, Clipboard turned) {
        Scheduler scheduler = pasteScheduler;
        if (clipboard == null || turned == null || turned.isEmpty() || scheduler == null) {
            log.warn("paste ignored - no blueprint chosen yet");
            return;
        }
        Blueprint placed = Blueprint.of(x, y, z, turned);
        log.info("paste tool pasted at {},{},{}", x, y, z);
        // Off the network thread: the first scan of a big blueprint reads every one of its blocks.
        scheduler.schedule(() -> startBlueprint(scheduler, placed, "pasted at " + x + "," + y + "," + z),
                Duration.ZERO);
    }

    /**
     * The paste tool's prefab list - the game's own window, which core opens and drives - over the
     * module's prefabs.
     */
    private void openPrefabList() {
        PrefabLibrary lib = library;
        if (lib == null || builderSelection == null) {
            log.info("paste tool asked for the prefab list - no prefabs here");
            return;
        }
        boolean opened = builderSelection.openPrefabList("Blueprint prefabs", new BuilderSelection.PrefabSource() {
            @Override public List<String> names() {
                lib.refresh();
                return lib.names();
            }
            @Override public Optional<Clipboard> preview(String name) {
                return lib.pathOf(name).flatMap(PreviewController.this::readPrefab).map(Blueprint::toClipboard);
            }
            @Override public void load(String name) {
                lib.pathOf(name).ifPresent(PreviewController.this::loadPrefabIntoPasteTool);
            }
            @Override public String emptyText() {
                return "No prefabs yet - save a selection from the Blueprint panel";
            }
        });
        if (!opened) {
            log.info("paste tool asked for the prefab list - no window on this core");
        }
    }

    private void sendPastePreview(Blueprint bp, int lo, int hi) {
        List<PastePreview.Change> filtered = new ArrayList<>(bp.size);
        for (int i = 0; i < bp.size; i++) {
            int dy = bp.dy[i];
            if (dy < lo || dy >= hi || bp.blockId[i] == 0) continue;   // air: no ghost
            filtered.add(new PastePreview.Change(
                    bp.dx[i], dy, bp.dz[i], bp.blockId[i], bp.rotation[i]));
        }
        List<PastePreview.FluidChange> wet = new ArrayList<>();
        for (PastePreview.FluidChange f : bp.toClipboard().fluids()) {
            if (f.dy() >= lo && f.dy() < hi) {
                wet.add(f);
            }
        }
        pastePreview.show(bp.anchorX, bp.anchorY, bp.anchorZ, filtered, wet);
    }

    private static int clampLayer(int layer, DiffPainter p) {
        if (layer < p.minLayer()) return p.minLayer();
        if (layer > p.maxLayer()) return p.maxLayer();
        return layer;
    }

    /** Whether there is a client to draw for; warns once while waiting. */
    private boolean requireReady() {
        if (pastePreview.isAvailable()) {
            return true;
        }
        if (!warnedNoClient) {
            log.warn("no client yet - waiting for a session");
            warnedNoClient = true;
        }
        return false;
    }

    private Optional<Vec3> playerPosition() {
        Optional<Player> p = world.player();
        if (p.isEmpty()) {
            log.warn("no local player position yet");
        }
        return p.map(Player::position);
    }

    /**
     * The block the player stands on, or - over air or unloaded ground - plain stone, so the cube is
     * always something to see. Not id 1: that is the server's {@code Unknown}, the stand-in for a
     * block it does not know. Empty when the catalog has not arrived.
     */
    private OptionalInt sampleStandingBlockId(int x, int y, int z) {
        Block under = world.blockAt(x, y, z);
        if (under != null && under.type() != null && under.type().id() > 0 && !under.isAir()) {
            return OptionalInt.of(under.type().id());
        }
        return worldState.blockId("Rock_Stone");
    }
}
