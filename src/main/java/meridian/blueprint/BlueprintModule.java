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
import meridian.core.api.World;
import meridian.core.api.WorldState;
import org.slf4j.Logger;

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

        BlockNameResolver names = new BlockNameResolver(worldState);
        PreviewController preview = new PreviewController(
                log, world, debug, names, pastePreview, builderSelection);

        PrefabLibrary library = new PrefabLibrary(log, ctx.getDataDir());
        // The paste tool's E opens the game's prefab list; with a core that can open windows we
        // fill it with our prefabs instead of the server's.
        Pages pages = ctx.services().get(Pages.class).orElse(null);
        if (pages != null && builderSelection != null) {
            preview.setPrefabList(new PrefabListWindow(log, pages, builderSelection, library, preview, scheduler));
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
                .build());

        log.info("enabled — live diff overlay, {} listed prefab(s) at boot",
                library.rows().size());
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
