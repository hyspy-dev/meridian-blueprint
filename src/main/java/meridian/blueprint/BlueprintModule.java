package meridian.blueprint;

import java.time.Duration;
import meridian.api.module.ModuleContext;
import meridian.api.module.ProxyModule;
import meridian.api.module.Scheduler;
import meridian.api.settings.SettingBinding;
import meridian.api.settings.SettingsSpec;
import meridian.core.api.BuilderSelection;
import meridian.core.api.Chat;
import meridian.core.api.ClientAssets;
import meridian.core.api.DebugRender;
import meridian.core.api.Pages;
import meridian.core.api.PastePreview;
import meridian.core.api.Prefabs;
import meridian.core.api.World;
import meridian.core.api.WorldState;
import org.slf4j.Logger;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * meridian-blueprint — Litematica-style schematic preview for the Meridian
 * proxy. The preview pipeline has two layers:
 *
 * <ul>
 *   <li>Core's {@link PastePreview} draws a textured base — the game's own
 *       paste ghost, so the blueprint reads as a regular block render, each
 *       block facing the way the blueprint has it.</li>
 *   <li>Core's {@link DebugRender} cubes drawn on top of the base form the
 *       diff overlay — cyan for missing, orange for wrong, yellow for the
 *       right block facing the wrong way, nothing for correct. The painter
 *       rescans the world at 1 Hz and repaints only when a cell changes.</li>
 * </ul>
 *
 * <p>No protocol types: everything goes through core services, so one jar
 * serves every game version. The in-game builder tools ({@link BuilderSelection})
 * are 0.6+ only and simply absent before that.
 *
 * <p>Layer slicing — for big prefabs the panel exposes a window size and
 * Prev/Next/jump-to-layer controls. The painter and the paste-preview both
 * filter to the same window so the overlay stays aligned with the texture.
 */
public class BlueprintModule implements ProxyModule {

    /** How often the data directory is rescanned for new prefab files. */
    private static final Duration LIBRARY_REFRESH = Duration.ofSeconds(5);

    /** Latest text in the "Prefab name" field. Session-only — the field
     *  itself isn't persistent because a saved-and-cleared name shouldn't
     *  re-appear next launch. */
    private volatile String draftPrefabName = "";

    /** A file from the module's jar, or null - logged - when it is not there. */
    private static byte[] resource(String path, Logger log) {
        try (java.io.InputStream in = BlueprintModule.class.getResourceAsStream(path)) {
            if (in == null) {
                log.warn("missing resource {}", path);
                return null;
            }
            return in.readAllBytes();
        } catch (java.io.IOException e) {
            log.warn("cannot read resource {}", path, e);
            return null;
        }
    }

    @Override
    public void onEnable(ModuleContext ctx) {
        Logger log = ctx.getLogger();
        World world = ctx.services().require(World.class);
        WorldState worldState = ctx.services().require(WorldState.class);
        DebugRender debug = ctx.services().require(DebugRender.class);
        PastePreview pastePreview = ctx.services().require(PastePreview.class);
        // The native selection tool is a 0.6+ affair; core provides it only where the protocol
        // can force the tool on. Absent on 0.5.9, and the panel then offers only the crosshair.
        BuilderSelection builderSelection = ctx.services().get(BuilderSelection.class).orElse(null);
        Scheduler scheduler = ctx.scheduler();

        Prefabs prefabs = ctx.services().require(Prefabs.class);
        PreviewController preview = new PreviewController(
                log, world, worldState, prefabs, debug, pastePreview, builderSelection);

        PrefabLibrary library = new PrefabLibrary(log, ctx.getDataDir());
        // The paste tool's E opens the game's prefab list; core opens it over our prefabs instead
        // of the server's.
        preview.setLibrary(library);
        Pages pages = ctx.services().get(Pages.class).orElse(null);
        if (pages != null && builderSelection != null) {
            // The blueprint tool's F opens a document of ours; it rides along with the client's load.
            ClientAssets assets = ctx.services().get(ClientAssets.class).orElse(null);
            byte[] panel = resource("/ui/MeridianBlueprintPanel.ui", log);
            if (assets != null && panel != null) {
                assets.provideAtConnect(BlueprintPanelWindow.ASSET, panel);
            }
            byte[] saveDoc = resource("/ui/MeridianBlueprintSave.ui", log);
            if (assets != null && saveDoc != null) {
                assets.provideAtConnect(SavePrefabWindow.OWN_ASSET, saveDoc);
            }
            Chat chat = ctx.services().get(Chat.class).orElse(null);
            if (chat != null) {
                preview.setChat(text -> chat.send("Blueprint: " + text, "#E8A93B"));
            }
            preview.setWindows(
                    assets != null && panel != null ? new BlueprintPanelWindow(log, pages, assets, chat, preview) : null,
                    new SavePrefabWindow(log, pages, chat, preview, library, ctx.getDataDir(), scheduler,
                            saveDoc != null ? assets : null));
        }
        log.info("prefabs dir = {}", ctx.getDataDir().toAbsolutePath());
        SettingsSpec building = enableBuilding(ctx, world, worldState, debug, preview);
        library.refresh();
        scheduler.scheduleAtFixedRate(library::refresh, LIBRARY_REFRESH, LIBRARY_REFRESH);

        // The layer input is a string field because the proxy's SettingBinding
        // only supports .string today (see docs/settings.md). Prev / Next
        // buttons push the new value through the binding so the widget stays
        // in sync; clicks in the field itself parse the text and apply.
        SettingBinding<String> layerBinding = new SettingBinding<>();

        ctx.registerSettings(SettingsSpec.builder()
                // The one control: put our tools in the toolbar (F on each opens its popup); off
                // clears the tools but leaves any projections up.
                .bool("blueprinting",
                        preview.hasBuilderTools()
                                ? "Start blueprinting (put our tools in the toolbar)"
                                : "Blueprinting (needs a 0.6+ server)",
                        false, on -> preview.setBlueprinting(on, scheduler))
                .liveText("Status", preview::statusLine)
                .liveList("Prefabs (click to preview)",
                        library::rows,
                        idx -> onPrefabClicked(ctx, preview, library, idx))
                .liveText("Layers", preview::layerLine)
                .int_("layerWindow", "Layers shown (0 = all)", 0, 256, 0, preview::setWindow)
                    .persistent()
                .button("◀ Prev layer", () -> {
                    int newLayer = preview.shiftLayer(-1);
                    layerBinding.set(Integer.toString(newLayer));
                })
                .string("layer", "Jump to layer", "0",
                        v -> setLayerFromText(preview, v), layerBinding)
                .button("Next layer ▶", () -> {
                    int newLayer = preview.shiftLayer(+1);
                    layerBinding.set(Integer.toString(newLayer));
                })
                .button("Preview sanity cube",
                        () -> preview.startSanityPreview(scheduler))
                .button("Hide preview", preview::hide)
                .section("Move preview", SettingsSpec.builder()
                        .int_("moveStep", "Step (blocks)", 1, 256, 1, preview::setMoveStep)
                            .persistent()
                        .button("◀ West (X-)",  () -> preview.shiftPreview(-preview.moveStep(), 0, 0))
                        .button("East (X+) ▶",  () -> preview.shiftPreview(+preview.moveStep(), 0, 0))
                        .button("▲ North (Z-)", () -> preview.shiftPreview(0, 0, -preview.moveStep()))
                        .button("▼ South (Z+)", () -> preview.shiftPreview(0, 0, +preview.moveStep()))
                        .button("↑ Up (Y+)",    () -> preview.shiftPreview(0, +preview.moveStep(), 0))
                        .button("↓ Down (Y-)",  () -> preview.shiftPreview(0, -preview.moveStep(), 0))
                        .build())
                .section("Rotate / mirror preview", SettingsSpec.builder()
                        .button("↻ Rotate X (pitch)", () -> preview.rotatePreview(0))
                        .button("↻ Rotate Y (yaw)",   () -> preview.rotatePreview(1))
                        .button("↻ Rotate Z (roll)",  () -> preview.rotatePreview(2))
                        .button("⇆ Mirror X",         () -> preview.mirrorPreview(0))
                        .button("⇕ Mirror Y",         () -> preview.mirrorPreview(1))
                        .button("⇆ Mirror Z",         () -> preview.mirrorPreview(2))
                        .build())
                // ───── Capture selection: the selection tool's box, or two crosshair corners ─────
                .liveText("Selection", preview::selectionStatus)
                .int_("pickRange", "Crosshair reach (blocks)", 1, 256, 7, preview::setPickRange)
                    .persistent()
                .bool("aimDebug", "Live aim-debug overlay (green cube on looked-at block)",
                        false, v -> preview.setAimDebug(v, scheduler))
                .button("Set corner 1 (looked-at block)",
                        preview::setCornerOneFromCrosshair)
                .button("Set corner 2 (looked-at block)",
                        preview::setCornerTwoFromCrosshair)
                .string("prefabName", "Prefab name", "", v -> draftPrefabName = v)
                .button("Save selection as prefab",
                        () -> preview.saveSelectionAs(ctx.getDataDir(), draftPrefabName))
                .button("Preview selection (no save)",
                        () -> preview.captureSelection(scheduler))
                .button("Clear selection", preview::clearSelection)
                .section("Build", building == null ? SettingsSpec.builder()
                        .liveText("Building", () -> "Needs core's Worker: this proxy cannot move the player.")
                        .build() : building)
                .build());

        log.info("enabled — live diff overlay, {} listed prefab(s) at boot",
                library.rows().size());
    }

    /** Where blocks the player runs out of come from, as the panel offers it. */
    enum ChestChoice {
        OFF("None: what is carried", Construction.Chests.OFF),
        NEAREST("The nearest chests that hold them", Construction.Chests.NEAREST),
        CHOSEN("The chosen chest", Construction.Chests.CHOSEN);

        private final String label;
        final Construction.Chests mode;

        ChestChoice(String label, Construction.Chests mode) {
            this.label = label;
            this.mode = mode;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private volatile Construction.Chests chests = Construction.Chests.NEAREST;
    private volatile meridian.core.api.BlockPos chest;
    private volatile String scaffold = "";
    private volatile String buildNote = "";

    private Construction.Options buildOptions() {
        return new Construction.Options(chests, chest, scaffold);
    }

    /**
     * The Build section and the {@code blueprint} command - where core moves the player and breaks
     * and places blocks ({@code Worker}); nothing where it does not.
     */
    private SettingsSpec enableBuilding(ModuleContext ctx, World world, WorldState worldState, DebugRender debug,
                                        PreviewController preview) {
        meridian.core.api.Movement movement = ctx.services().get(meridian.core.api.Movement.class).orElse(null);
        meridian.core.api.Worker worker = ctx.services().get(meridian.core.api.Worker.class).orElse(null);
        meridian.core.api.Navigator navigator = ctx.services().get(meridian.core.api.Navigator.class).orElse(null);
        if (movement == null || worker == null || navigator == null) {
            return null;
        }
        meridian.core.api.Containers containers = ctx.services().get(meridian.core.api.Containers.class).orElse(null);
        Construction construction = new Construction(movement, world, worldState, worker, navigator,
                ctx.services().get(meridian.core.api.PlayerState.class).orElse(null), containers, debug,
                ctx.offloadExecutor(), ctx.getLogger());
        try {
            ctx.registerCommand("blueprint", BUILD_USAGE, (args, json) -> buildCommand(construction, preview, worldState, args));
        } catch (NoSuchMethodError olderProxy) {
            // no command channel there
        }
        return SettingsSpec.builder()
                .enum_("buildChests", "Blocks the player runs out of", ChestChoice.class, ChestChoice.NEAREST,
                        v -> chests = v.mode)
                .button("Choose the chest I opened last", () -> {
                    meridian.core.api.BlockPos last = containers == null ? null : containers.lastOpened().orElse(null);
                    buildNote = last == null ? "No chest opened yet - open the one to take blocks from in the game." : "";
                    if (last != null) {
                        chest = last;
                    }
                })
                .liveText("Chest", () -> chest == null ? "None chosen." : chest.x() + " " + chest.y() + " " + chest.z())
                .string("buildScaffold", "Scaffolding of (an item; empty: a cheap block carried)", "",
                        v -> scaffold = v == null ? "" : v.trim())
                .button("Build", () -> {
                    buildNote = "";
                    construction.build(preview.active(), buildOptions());
                })
                .button("Stop building", () -> {
                    buildNote = "";
                    construction.cancel();
                })
                .liveText("Building", () -> buildNote.isEmpty() ? construction.status() : buildNote)
                .build();
    }

    private static final String BUILD_USAGE = "build | build-box <x y z> <w h d> <block> [rotation] | stop"
            + " | set chests off|nearest|chest <x y z> | set scaffold <item>";

    /** {@code blueprint <conn> build | build-box ... | stop | set ...}. */
    private CompletableFuture<?> buildCommand(Construction construction, PreviewController preview,
                                              WorldState worldState, List<String> args) {
        String what = args.isEmpty() ? "" : args.get(0).toLowerCase(Locale.ROOT);
        switch (what) {
            case "stop" -> {
                return construction.cancel().thenApply(v -> Map.of("stopped", true));
            }
            case "set" -> {
                String key = args.size() > 1 ? args.get(1).toLowerCase(Locale.ROOT) : "";
                String value = args.size() > 2 ? args.get(2) : "";
                if (key.equals("scaffold")) {
                    scaffold = value;
                } else if (key.equals("chests")) {
                    switch (value.toLowerCase(Locale.ROOT)) {
                        case "off" -> chests = Construction.Chests.OFF;
                        case "chest" -> {
                            chests = Construction.Chests.CHOSEN;
                            chest = new meridian.core.api.BlockPos(Integer.parseInt(args.get(3)),
                                    Integer.parseInt(args.get(4)), Integer.parseInt(args.get(5)));
                        }
                        default -> chests = Construction.Chests.NEAREST;
                    }
                } else {
                    throw new IllegalArgumentException(BUILD_USAGE);
                }
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("chests", chests.name());
                out.put("chest", chest);
                out.put("scaffold", scaffold);
                return CompletableFuture.completedFuture(out);
            }
            case "build" -> {
                return construction.build(preview.active(), buildOptions()).thenApply(BlueprintModule::result);
            }
            case "build-box" -> {
                // A box of one block, for checking the building without a prefab.
                if (args.size() < 8) {
                    throw new IllegalArgumentException(BUILD_USAGE);
                }
                int x = Integer.parseInt(args.get(1));
                int y = Integer.parseInt(args.get(2));
                int z = Integer.parseInt(args.get(3));
                int w = Integer.parseInt(args.get(4));
                int h = Integer.parseInt(args.get(5));
                int d = Integer.parseInt(args.get(6));
                int id = worldState.blockId(args.get(7)).orElseThrow(() -> new IllegalArgumentException("no block " + args.get(7)));
                int rot = args.size() > 8 ? Integer.parseInt(args.get(8)) : 0;
                int n = w * h * d;
                int[] dx = new int[n], dy = new int[n], dz = new int[n], ids = new int[n];
                byte[] rots = new byte[n];
                int i = 0;
                for (int k = 0; k < h; k++) {
                    for (int a = 0; a < w; a++) {
                        for (int b = 0; b < d; b++) {
                            dx[i] = a;
                            dy[i] = k;
                            dz[i] = b;
                            ids[i] = id;
                            rots[i] = (byte) rot;
                            i++;
                        }
                    }
                }
                return construction.build(new Blueprint(x, y, z, dx, dy, dz, ids, rots), buildOptions())
                        .thenApply(BlueprintModule::result);
            }
            default -> throw new IllegalArgumentException(BUILD_USAGE);
        }
    }

    private static Map<String, Object> result(Construction.Result r) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", r.status());
        out.put("placed", r.placed());
        out.put("broken", r.broken());
        out.put("scaffolds", r.scaffolds());
        out.put("left", r.left());
        out.put("lacking", r.lacking());
        return out;
    }

    private static void onPrefabClicked(ModuleContext ctx, PreviewController preview,
                                        PrefabLibrary library, int idx) {
        library.pathAt(idx).ifPresent(path ->
                ctx.offloadExecutor().execute(() ->
                        preview.loadAndStart(ctx.scheduler(), path)));
    }

    /** Parse-and-apply with a forgiving rule — blank or garbage input is ignored. */
    private static void setLayerFromText(PreviewController preview, String v) {
        if (v == null || v.isBlank()) return;
        try {
            preview.setLayer(Integer.parseInt(v.trim()));
        } catch (NumberFormatException ignored) {
            // Field accepts arbitrary text; the proxy's text widget saves on
            // focus loss, so transient typing isn't worth a log line.
        }
    }
}
