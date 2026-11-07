package meridian.blueprint;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledFuture;
import meridian.api.module.Scheduler;
import meridian.core.api.Block;
import meridian.core.api.BlockPos;
import meridian.core.api.DebugRender;
import java.util.List;
import meridian.core.api.BuilderSelection;
import meridian.core.api.PastePreview;
import meridian.core.api.Player;
import meridian.core.api.Vec3;
import meridian.core.api.World;
import org.slf4j.Logger;

/**
 * Lifecycle for an active blueprint preview. Owns the scheduler handle for the diff tick, and
 * emits the textured paste-preview at start / on layer change / hide through the core
 * {@link PastePreview} service - core owns the wire, this owns what is drawn.
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
    private final BlockNameResolver names;
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
     * to its anchor and unturned. Null until something is chosen.
     */
    private volatile Blueprint clipboard;
    /** The quarter turns given to the paste tool's clipboard since it was loaded, in order: {axis, steps}. */
    private final List<int[]> clipboardTurns = new CopyOnWriteArrayList<>();
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

    /** The paste tool's prefab list (its E key), when core can open windows. */
    private volatile PrefabListWindow prefabList;

    void setPrefabList(PrefabListWindow window) {
        prefabList = window;
    }

    PreviewController(Logger log, World world, DebugRender debug, BlockNameResolver names,
                      PastePreview pastePreview, BuilderSelection builderSelection) {
        this.log = log;
        this.world = world;
        this.debug = debug;
        this.names = names;
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
            // its usual keys by itself; we only hear the turns and where it was pasted.
            builderSelection.onPaste(new BuilderSelection.PasteListener() {
                @Override public void turned(BuilderSelection.Axis axis, int degrees) {
                    clipboardTurns.add(new int[] {axis.ordinal(), Math.floorMod(degrees / 90, 4)});
                    log.info("paste tool turned {} by {}", axis, degrees);
                    // The client spun its preview and waits for the turned clipboard, as the
                    // server would send it.
                    confirmTurned();
                }
                @Override public void flipped(BuilderSelection.Axis axis) {
                    clipboardTurns.add(new int[] {axis.ordinal(), FLIP});
                    log.info("paste tool flipped across {}", axis);
                    confirmTurned();
                }
                @Override public void copyAsked() {
                    copySelection();
                }
                @Override public void listAsked() {
                    PrefabListWindow window = prefabList;
                    if (window != null) {
                        window.open();
                    } else {
                        log.info("paste tool asked for the prefab list - no window on this core");
                    }
                }
                @Override public void reset() {
                    clipboardTurns.clear();
                    log.info("paste tool turns reset");
                    builderSelection.setClipboard(clipboardChanges(), clipboardFluidChanges());
                }
                @Override public void pasted(int x, int y, int z) {
                    placeClipboard(x, y, z);
                }
            });
            builderSelection.defineTools(TOOL_SPECS);   // teach the client our items
            toolsWired = true;
        }
        builderSelection.setClipboard(on ? clipboardChanges() : null, on ? clipboardFluidChanges() : null);
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
        int blockId = sampleStandingBlockId(ox, oy - 1, oz);

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
        PrefabFile prefab;
        try {
            prefab = PrefabLoader.load(prefabPath);
        } catch (IOException e) {
            log.warn("failed to read prefab {}", prefabPath, e);
            return Optional.empty();
        }
        BlueprintBuilder.Result result = BlueprintBuilder.fromPrefab(prefab, names, world::fluidId, 0, 0, 0);
        if (!result.unknownNames().isEmpty()) {
            log.warn("skipped {} unique unknown block types: {}",
                    result.unknownNames().size(), result.unknownNames());
        }
        return result.blueprint().size == 0 ? Optional.empty() : Optional.of(result.blueprint());
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
        BlueprintBuilder.Result result = BlueprintBuilder.fromPrefab(prefab, names, world::fluidId, ox, oy, oz);
        if (!result.unknownNames().isEmpty()) {
            log.warn("skipped {} unique unknown block types: {}",
                    result.unknownNames().size(), result.unknownNames());
        }
        if (result.blueprint().size == 0) {
            log.warn("prefab {} has no usable blocks after resolution", prefabPath);
            return;
        }
        String label = prefabPath.getFileName() + " (" + result.blueprint().size + " blocks, "
                + result.skippedEmpty() + " Empty skipped)";
        if (loadIntoPasteTool(result.blueprint(), label)) {
            return;
        }
        startBlueprint(scheduler, result.blueprint(), label);
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
            int blocks = PrefabSaver.save(world, b, prefabsDir, name.trim());
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

        int[] dx = new int[volume], dy = new int[volume], dz = new int[volume], ids = new int[volume];
        byte[] rot = new byte[volume];
        FluidCollector wet = new FluidCollector();
        int i = 0;
        for (int y = b.yMin(); y <= b.yMax(); y++) {
            for (int z = b.zMin(); z <= b.zMax(); z++) {
                for (int x = b.xMin(); x <= b.xMax(); x++) {
                    wet.add(world.fluidCellAt(x, y, z), x - b.xMin(), y - b.yMin(), z - b.zMin());
                    Block blk = world.blockAt(x, y, z);
                    int id = blk != null && blk.type() != null ? blk.type().id() : 0;
                    if (id <= 0) continue;             // skip air
                    // A door's or a bed's other cells: placing it on its own cell brings them back.
                    if (blk.isFiller()) continue;
                    dx[i] = x - b.xMin();
                    dy[i] = y - b.yMin();
                    dz[i] = z - b.zMin();
                    ids[i] = id;
                    rot[i] = (byte) blk.rotation();
                    i++;
                }
            }
        }
        if (i == 0) {
            log.warn("selection contains no solid blocks");
            return;
        }
        Blueprint bp = new Blueprint(b.xMin(), b.yMin(), b.zMin(),
                trim(dx, i), trim(dy, i), trim(dz, i), trim(ids, i), trim(rot, i), wet.build());
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
     * it, as the server mirrors a symmetric block (see {@link BlockRotations#mirror}).
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
        byte[] nr = new byte[n];
        for (int i = 0; i < n; i++) {
            int x = bp.dx[i], y = bp.dy[i], z = bp.dz[i];
            int r = bp.rotation[i] & 0xFF;
            nr[i] = (byte) (mirror ? BlockRotations.mirror(r, axis) : BlockRotations.rotate(r, axis));
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

        Blueprint.Fluids f = bp.fluids;
        int m = f.size();
        int[] fx = new int[m], fy = new int[m], fz = new int[m];
        for (int i = 0; i < m; i++) {
            int x = f.dx()[i], y = f.dy()[i], z = f.dz()[i];
            if (mirror) {
                fx[i] = (axis == 0) ? -x : x;
                fy[i] = (axis == 1) ? -y : y;
                fz[i] = (axis == 2) ? -z : z;
            } else {
                switch (axis) {
                    case 0 -> { fx[i] = x;  fy[i] = -z; fz[i] =  y; }
                    case 1 -> { fx[i] = z;  fy[i] =  y; fz[i] = -x; }
                    case 2 -> { fx[i] = -y; fy[i] =  x; fz[i] =  z; }
                }
            }
        }

        int minX = 0, minY = 0, minZ = 0;
        if (n + m > 0) {
            minX = minY = minZ = Integer.MAX_VALUE;
            for (int i = 0; i < n; i++) {
                minX = Math.min(minX, nx[i]); minY = Math.min(minY, ny[i]); minZ = Math.min(minZ, nz[i]);
            }
            for (int i = 0; i < m; i++) {
                minX = Math.min(minX, fx[i]); minY = Math.min(minY, fy[i]); minZ = Math.min(minZ, fz[i]);
            }
            for (int i = 0; i < n; i++) {
                nx[i] -= minX;
                ny[i] -= minY;
                nz[i] -= minZ;
            }
            for (int i = 0; i < m; i++) {
                fx[i] -= minX;
                fy[i] -= minY;
                fz[i] -= minZ;
            }
        }

        swapInBlueprint(new Blueprint(
                bp.anchorX + minX, bp.anchorY + minY, bp.anchorZ + minZ,
                nx, ny, nz, bp.blockId, nr,
                new Blueprint.Fluids(fx, fy, fz, f.fluidId(), f.level())));
    }

    /** Replaces the active blueprint with {@code newBp}, rebuilds the
     *  painter, preserves the layer window, and re-issues the textured
     *  preview. Shared tail for shift / rotate / mirror. */
    private void swapInBlueprint(Blueprint newBp) {
        DiffPainter oldPainter = painter;
        if (oldPainter == null) return;

        DiffPainter p = new DiffPainter(log, world, debug, names, newBp);
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
        DiffPainter p = new DiffPainter(log, world, debug, names, bp);
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
        clipboardTurns.clear();
        if (builderSelection != null && blueprinting) {
            builderSelection.setClipboard(clipboardChanges(), clipboardFluidChanges());
        }
    }

    /** The paste tool's clipboard as blocks relative to its anchor, or null when nothing is chosen. */
    private List<PastePreview.Change> clipboardChanges() {
        Blueprint bp = clipboard;
        return bp == null ? null : changesOf(bp);
    }

    /** The paste tool clipboard's fluids, relative to its anchor; empty when nothing is chosen. */
    private List<PastePreview.FluidChange> clipboardFluidChanges() {
        Blueprint bp = clipboard;
        return bp == null ? List.of() : fluidChangesOf(bp);
    }

    /** The clipboard turned the way the paste tool turned it, handed back as the server would. */
    private void confirmTurned() {
        Blueprint base = clipboard;
        if (base != null && builderSelection != null) {
            Blueprint turned = PreviewController.turned(base, clipboardTurns, 0, 0, 0);
            builderSelection.confirmTurn(changesOf(turned), fluidChangesOf(turned));
        }
    }

    /** A blueprint's fluid cells as clipboard fluids, relative to its anchor. */
    static List<PastePreview.FluidChange> fluidChangesOf(Blueprint bp) {
        Blueprint.Fluids f = bp.fluids;
        List<PastePreview.FluidChange> out = new ArrayList<>(f.size());
        for (int i = 0; i < f.size(); i++) {
            out.add(new PastePreview.FluidChange(f.dx()[i], f.dy()[i], f.dz()[i], f.fluidId()[i],
                    f.level()[i]));
        }
        return out;
    }

    /** A blueprint's cells as clipboard blocks, relative to its anchor. */
    static List<PastePreview.Change> changesOf(Blueprint bp) {
        List<PastePreview.Change> out = new ArrayList<>(bp.size);
        for (int i = 0; i < bp.size; i++) {
            out.add(new PastePreview.Change(bp.dx[i], bp.dy[i], bp.dz[i], bp.blockId[i],
                    bp.rotation[i] & 0xFF));
        }
        return out;
    }

    /**
     * Puts the projection down where the paste tool pasted: the clipboard turned the way the
     * player turned it, about its anchor, with the anchor at the pasted block - what the server
     * would have built there. Nothing reaches the world; the projection is what moves.
     */
    private void placeClipboard(int x, int y, int z) {
        Blueprint base = clipboard;
        Scheduler scheduler = pasteScheduler;
        if (base == null || scheduler == null) {
            log.warn("paste ignored - no blueprint chosen yet");
            return;
        }
        Blueprint placed = turned(base, clipboardTurns, x, y, z);
        log.info("paste tool pasted at {},{},{} after {} turn(s)", x, y, z, clipboardTurns.size());
        // Off the network thread: the first scan of a big blueprint reads every one of its blocks.
        scheduler.schedule(() -> startBlueprint(scheduler, placed, "pasted at " + x + "," + y + "," + z),
                Duration.ZERO);
    }

    /** The steps of a {@code clipboardTurns} entry that make it a mirror instead of a turn. */
    static final int FLIP = -1;

    /**
     * {@code base} turned by each {@code {axis, quarterSteps}} in order, about its anchor, and
     * anchored at {@code (x, y, z)}: positions by the server's {@code Axis.rotate}, each block's
     * facing by {@link BlockRotations#rotate}. An entry of {@link #FLIP} steps mirrors across that
     * axis through the anchor instead, as the server's {@code Axis.flip}, facings by
     * {@link BlockRotations#mirror}.
     */
    static Blueprint turned(Blueprint base, List<int[]> turns, int x, int y, int z) {
        int n = base.size;
        int[] dx = base.dx.clone(), dy = base.dy.clone(), dz = base.dz.clone();
        byte[] rot = base.rotation.clone();
        for (int[] turn : turns) {
            if (turn[1] == FLIP) {
                for (int i = 0; i < n; i++) {
                    switch (turn[0]) {
                        case BlockRotations.X -> dx[i] = -dx[i];
                        case BlockRotations.Y -> dy[i] = -dy[i];
                        case BlockRotations.Z -> dz[i] = -dz[i];
                        default -> throw new IllegalArgumentException("axis " + turn[0]);
                    }
                    rot[i] = (byte) BlockRotations.mirror(rot[i] & 0xFF, turn[0]);
                }
                continue;
            }
            for (int step = 0; step < turn[1]; step++) {
                for (int i = 0; i < n; i++) {
                    int cx = dx[i], cy = dy[i], cz = dz[i];
                    switch (turn[0]) {
                        case BlockRotations.X -> { dy[i] = -cz; dz[i] = cy; }
                        case BlockRotations.Y -> { dx[i] = cz; dz[i] = -cx; }
                        case BlockRotations.Z -> { dx[i] = -cy; dy[i] = cx; }
                        default -> throw new IllegalArgumentException("axis " + turn[0]);
                    }
                    rot[i] = (byte) BlockRotations.rotate(rot[i] & 0xFF, turn[0]);
                }
            }
        }
        Blueprint.Fluids f = base.fluids;
        int m = f.size();
        int[] fx = f.dx().clone(), fy = f.dy().clone(), fz = f.dz().clone();
        for (int[] turn : turns) {
            for (int i = 0; i < m; i++) {
                if (turn[1] == FLIP) {
                    switch (turn[0]) {
                        case BlockRotations.X -> fx[i] = -fx[i];
                        case BlockRotations.Y -> fy[i] = -fy[i];
                        case BlockRotations.Z -> fz[i] = -fz[i];
                        default -> throw new IllegalArgumentException("axis " + turn[0]);
                    }
                    continue;
                }
                for (int step = 0; step < turn[1]; step++) {
                    int cx = fx[i], cy = fy[i], cz = fz[i];
                    switch (turn[0]) {
                        case BlockRotations.X -> { fy[i] = -cz; fz[i] = cy; }
                        case BlockRotations.Y -> { fx[i] = cz; fz[i] = -cx; }
                        case BlockRotations.Z -> { fx[i] = -cy; fy[i] = cx; }
                        default -> throw new IllegalArgumentException("axis " + turn[0]);
                    }
                }
            }
        }
        return new Blueprint(x, y, z, dx, dy, dz, base.blockId, rot,
                new Blueprint.Fluids(fx, fy, fz, f.fluidId(), f.level()));
    }

    private void sendPastePreview(Blueprint bp, int lo, int hi) {
        List<PastePreview.Change> filtered = new ArrayList<>(bp.size);
        for (int i = 0; i < bp.size; i++) {
            int dy = bp.dy[i];
            if (dy < lo || dy >= hi) continue;
            filtered.add(new PastePreview.Change(
                    bp.dx[i], dy, bp.dz[i], bp.blockId[i], bp.rotation[i]));
        }
        List<PastePreview.FluidChange> wet = new ArrayList<>();
        for (PastePreview.FluidChange f : fluidChangesOf(bp)) {
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
