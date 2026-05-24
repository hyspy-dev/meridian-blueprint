package meridian.blueprint;

import java.time.Duration;
import meridian.api.module.ModuleContext;
import meridian.api.module.ProxyModule;
import meridian.api.module.Scheduler;
import meridian.api.packet.Direction;
import meridian.api.packet.HandlerPosition;
import meridian.api.settings.SettingsSpec;
import meridian.core.api.DebugRender;
import meridian.core.api.World;
import meridian.core.api.WorldState;
import org.slf4j.Logger;

/**
 * meridian-blueprint — Litematica-style schematic preview for the Meridian
 * proxy. Combines two client-native rendering pipes:
 *
 * <ul>
 *   <li>{@code ShowTriggerVolumePastePrefabPreview} draws the textured base
 *       — the same packet the server's paste tool uses, so the blueprint
 *       reads as a normal block render.</li>
 *   <li>{@link DebugRender#box} draws the diff overlay — depth-tested,
 *       semi-transparent debug cubes on top of the base. Repainted every
 *       500 ms so colours match the live world (cyan = missing, orange =
 *       wrong type, hidden when correct).</li>
 * </ul>
 *
 * <p>The settings panel exposes a live list of {@code .prefab.json} files
 * found in the module's data directory; clicking a row pastes that prefab
 * under the player's feet and starts the diff loop. Files are rescanned in
 * the background every 5 seconds, so dropping a new file makes it appear in
 * the list without restarting the proxy.
 *
 * <p>A "Preview sanity cube" button stays as a self-test path that doesn't
 * need disk state — useful when the block catalog is still loading or no
 * prefab is on hand.
 */
public class BlueprintModule implements ProxyModule {

    /** How often the data directory is rescanned for new prefab files. */
    private static final Duration LIBRARY_REFRESH = Duration.ofSeconds(5);

    @Override
    public void onEnable(ModuleContext ctx) {
        Logger log = ctx.getLogger();
        World world = ctx.services().require(World.class);
        WorldState worldState = ctx.services().require(WorldState.class);
        DebugRender debug = ctx.services().require(DebugRender.class);
        Scheduler scheduler = ctx.scheduler();

        BlockNameResolver names = new BlockNameResolver(worldState);
        PreviewController preview = new PreviewController(log, world, debug, names);

        // Default-channel observer captures the ProxySession so the preview
        // controller has a pipe back to the client. MONITOR — observe-only.
        ctx.registerHandler(Direction.BOTH, HandlerPosition.MONITOR,
                (direction, session) -> new SessionObserver(preview));

        // The proxy owns and auto-creates this directory on first reference.
        // We never touch the working directory — that can be anywhere.
        PrefabLibrary library = new PrefabLibrary(log, ctx.getDataDir());
        log.info("prefabs dir = {}", ctx.getDataDir().toAbsolutePath());
        // First scan synchronously so the live list isn't empty if the user
        // opens the panel within the first 5 seconds.
        library.refresh();
        scheduler.scheduleAtFixedRate(library::refresh, LIBRARY_REFRESH, LIBRARY_REFRESH);

        ctx.registerSettings(SettingsSpec.builder()
                .liveList("Prefabs (click to preview)",
                        library::rows,
                        idx -> onPrefabClicked(ctx, preview, library, idx))
                .button("Preview sanity cube",
                        () -> preview.startSanityPreview(scheduler))
                .button("Hide preview", preview::hide)
                .build());

        log.info("enabled — live diff overlay, {} listed prefab(s) at boot",
                library.rows().size());
    }

    /**
     * Live-list click handler. Fires on the EDT, so the actual disk read +
     * resolve + packet build is bounced to {@code offloadExecutor()}. The
     * snapshot pattern in {@link PrefabLibrary} guarantees the index points
     * at the same row the user saw.
     */
    private static void onPrefabClicked(ModuleContext ctx, PreviewController preview,
                                        PrefabLibrary library, int idx) {
        library.pathAt(idx).ifPresent(path ->
                ctx.offloadExecutor().execute(() ->
                        preview.loadAndStart(ctx.scheduler(), path)));
    }
}
