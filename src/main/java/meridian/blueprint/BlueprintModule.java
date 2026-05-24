package meridian.blueprint;

import java.time.Duration;
import meridian.api.module.ModuleContext;
import meridian.api.module.ProxyModule;
import meridian.api.module.Scheduler;
import meridian.api.packet.Direction;
import meridian.api.packet.HandlerPosition;
import meridian.api.settings.SettingBinding;
import meridian.api.settings.SettingsSpec;
import meridian.core.api.DebugRender;
import meridian.core.api.World;
import meridian.core.api.WorldState;
import org.slf4j.Logger;

/**
 * meridian-blueprint — Litematica-style schematic preview for the Meridian
 * proxy. The preview pipeline has two layers:
 *
 * <ul>
 *   <li>{@code ShowTriggerVolumePastePrefabPreview} draws a textured base —
 *       the same packet the server's paste tool uses, so the blueprint
 *       reads as a regular block render.</li>
 *   <li>{@code DisplayDebug} cubes drawn on top of the base form the diff
 *       overlay — depth-tested, opacity 0.3, cyan for missing / orange for
 *       wrong / nothing for correct. The painter rescans the world at 1 Hz
 *       and pushes a single repaint only when a cell actually changes.</li>
 * </ul>
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

    @Override
    public void onEnable(ModuleContext ctx) {
        Logger log = ctx.getLogger();
        World world = ctx.services().require(World.class);
        WorldState worldState = ctx.services().require(WorldState.class);
        DebugRender debug = ctx.services().require(DebugRender.class);
        Scheduler scheduler = ctx.scheduler();

        BlockNameResolver names = new BlockNameResolver(worldState);
        PreviewController preview = new PreviewController(log, world, debug, names);

        ctx.registerHandler(Direction.BOTH, HandlerPosition.MONITOR,
                (direction, session) -> new SessionObserver(preview));

        PrefabLibrary library = new PrefabLibrary(log, ctx.getDataDir());
        log.info("prefabs dir = {}", ctx.getDataDir().toAbsolutePath());
        library.refresh();
        scheduler.scheduleAtFixedRate(library::refresh, LIBRARY_REFRESH, LIBRARY_REFRESH);

        // The layer input is a string field because the proxy's SettingBinding
        // only supports .string today (see docs/settings.md). Prev / Next
        // buttons push the new value through the binding so the widget stays
        // in sync; clicks in the field itself parse the text and apply.
        SettingBinding<String> layerBinding = new SettingBinding<>();

        ctx.registerSettings(SettingsSpec.builder()
                .liveList("Prefabs (click to preview)",
                        library::rows,
                        idx -> onPrefabClicked(ctx, preview, library, idx))
                .liveText("Status", preview::statusLine)
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
                // ───── Capture selection (crosshair-driven) ─────
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
