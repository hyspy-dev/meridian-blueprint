package meridian.blueprint;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ScheduledFuture;
import java.util.function.Supplier;
import meridian.api.module.Scheduler;
import meridian.core.api.Block;
import meridian.core.api.BlockView;
import meridian.core.api.Hud;
import meridian.core.api.PlayerState;
import meridian.core.api.World;
import meridian.core.api.WorldState;
import org.slf4j.Logger;

/**
 * The panel at the side of the player's screen while a blueprint is put down (owner, 22.11: "a
 * window AFTER the blueprint is pasted, hanging on the player's screen at the side"; then: "icons
 * instead of names, the look of the popup when an item is picked up"). On top the prefab's name and
 * how much of it stands; under it a strip a block kind: the item's icon - drawn by the game from its
 * id - and {@code carried / still to place}, red while what is carried is less. The most still to
 * place first. Counted anew every {@link #EVERY}; only what changed is sent.
 *
 * <p>A cell is still to place where the world does not have the blueprint's block, turned as drawn
 * - as the overlay paints it. Air in the blueprint is no block to place.
 */
final class MaterialsHud {

    static final String HUD_ID = "meridian.blueprint.materials";
    static final int Z_ORDER = 60;
    static final Duration EVERY = Duration.ofSeconds(2);
    /** Strips of blocks; the rest are counted under them. */
    static final int ROWS = 10;
    static final int WIDTH = 150;
    static final int ROW = 36;
    static final int GAP = 4;
    static final int HEAD = 34;

    private final Logger log;
    private final Hud hud;
    private final World world;
    private final WorldState worldState;
    private final PlayerState inventory;

    private volatile Blueprint shown;
    private volatile Supplier<String> title = () -> "Blueprint";
    private volatile ScheduledFuture<?> ticking;
    /** What each element was last sent: selector -> value. */
    private final Map<String, Object> sent = new HashMap<>();

    MaterialsHud(Logger log, Hud hud, World world, WorldState worldState, PlayerState inventory) {
        this.log = log;
        this.hud = hud;
        this.world = world;
        this.worldState = worldState;
        this.inventory = inventory;
    }

    /** The panel for {@code bp}, drawn now and kept current until {@link #stop}. */
    synchronized void start(Blueprint bp, Supplier<String> title, Scheduler scheduler) {
        stop();
        if (hud == null || bp == null || scheduler == null) {
            return;
        }
        shown = bp;
        this.title = title == null ? () -> "Blueprint" : title;
        sent.clear();
        try {
            Hud.Batch b = hud.batch();
            b.appendInline(markup());
            refresh(b);
            b.show(HUD_ID, Z_ORDER);
        } catch (RuntimeException e) {
            log.warn("the materials panel cannot be drawn", e);
            shown = null;
            return;
        }
        ticking = scheduler.scheduleAtFixedRate(this::tick, EVERY, EVERY);
    }

    /** Takes the panel off the screen. */
    synchronized void stop() {
        ScheduledFuture<?> t = ticking;
        ticking = null;
        if (t != null) {
            t.cancel(false);
        }
        if (shown != null && hud != null) {
            try {
                hud.hide(HUD_ID);
            } catch (RuntimeException e) {
                log.debug("materials panel hide", e);
            }
        }
        shown = null;
    }

    private synchronized void tick() {
        if (shown == null) {
            return;
        }
        try {
            Hud.Batch b = hud.batch();
            if (refresh(b)) {
                b.patch(HUD_ID, Z_ORDER);
            }
        } catch (RuntimeException e) {
            log.debug("materials panel tick", e);
        }
    }

    /**
     * The panel's markup: the heading, then the strips one under another, each a dark box with the
     * icon at its left and the numbers beside it - white, and red over it while short. ASCII only.
     */
    private static String markup() {
        int height = HEAD + ROWS * (ROW + GAP) + 18;
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.ROOT, "Group #BpMatRoot { Anchor: (Top: 200, Right: 12, Width: %d, Height: %d); ",
                WIDTH, height));
        sb.append(String.format(Locale.ROOT, "Label #BpMatTitle { Anchor: (Left: 0, Top: 0, Width: %d, Height: 16); "
                + "Text: \"-\"; Style: (FontSize: 12, TextColor: #FFD27F, RenderBold: true); } ", WIDTH));
        sb.append(String.format(Locale.ROOT, "Label #BpMatBuilt { Anchor: (Left: 0, Top: 16, Width: %d, Height: 14); "
                + "Text: \"-\"; Style: (FontSize: 11, TextColor: #FFFFFF, RenderBold: false); } ", WIDTH));
        for (int i = 0; i < ROWS; i++) {
            int top = HEAD + i * (ROW + GAP);
            sb.append(String.format(Locale.ROOT, "Group #BpRow%d { Anchor: (Left: 0, Top: %d, Width: %d, Height: %d); ",
                    i, top, WIDTH, ROW));
            sb.append(String.format(Locale.ROOT, "Panel #BpBack%d { Anchor: (Left: 0, Top: 0, Width: %d, Height: %d); "
                    + "Background: #000000A0; } ", i, WIDTH, ROW));
            sb.append(String.format(Locale.ROOT, "ItemSlot #BpIco%d { Anchor: (Left: 2, Top: 2, Width: 32, Height: 32); "
                    + "ShowQualityBackground: false; ShowQuantity: false; } ", i));
            sb.append(String.format(Locale.ROOT, "Label #BpNum%d { Anchor: (Left: 42, Top: 9, Width: %d, Height: 18); "
                    + "Text: \"-\"; Style: (FontSize: 14, TextColor: #FFFFFF, RenderBold: true); } ", i, WIDTH - 46));
            sb.append(String.format(Locale.ROOT, "Label #BpLow%d { Anchor: (Left: 42, Top: 9, Width: %d, Height: 18); "
                    + "Text: \"-\"; Style: (FontSize: 14, TextColor: #FF6B6B, RenderBold: true); } ", i, WIDTH - 46));
            sb.append("} ");
        }
        sb.append(String.format(Locale.ROOT, "Label #BpMatMore { Anchor: (Left: 0, Top: %d, Width: %d, Height: 14); "
                        + "Text: \"-\"; Style: (FontSize: 11, TextColor: #FFFFFF, RenderBold: false); } ",
                HEAD + ROWS * (ROW + GAP), WIDTH));
        return sb.append("}").toString();
    }

    /** The panel as it is now, what changed set in {@code b}; whether anything did. */
    private boolean refresh(Hud.Batch b) {
        Blueprint bp = shown;
        Map<Integer, BlockView> types = new HashMap<>();
        for (BlockView t : worldState.allBlockTypes()) {
            types.put(t.id(), t);
        }
        Map<String, int[]> byItem = new HashMap<>();      // item -> {all, still to place}
        int all = 0;
        int left = 0;
        for (int i = 0; i < bp.size; i++) {
            int id = bp.blockId[i];
            if (id == 0) {
                continue;
            }
            BlockView t = types.get(id);
            if (t == null) {
                continue;
            }
            String item = types.getOrDefault(t.family(), t).name();
            int[] n = byItem.computeIfAbsent(item, k -> new int[2]);
            n[0]++;
            all++;
            Block actual = world.blockAt(bp.anchorX + bp.dx[i], bp.anchorY + bp.dy[i], bp.anchorZ + bp.dz[i]);
            boolean there = actual != null && !actual.isAir() && actual.type() != null && actual.type().id() > 0
                    && actual.matches(id, bp.rotation[i] & 0xFF);
            if (!there) {
                n[1]++;
                left++;
            }
        }
        Map<String, Integer> have = Materials.carried(inventory);
        List<Map.Entry<String, int[]>> rows = new ArrayList<>(byItem.entrySet());
        rows.sort((a, c) -> c.getValue()[1] != a.getValue()[1] ? Integer.compare(c.getValue()[1], a.getValue()[1])
                : c.getValue()[0] != a.getValue()[0] ? Integer.compare(c.getValue()[0], a.getValue()[0])
                : a.getKey().compareTo(c.getKey()));

        boolean any = put(b, "#BpMatTitle.Text", ascii(title.get()));
        any |= put(b, "#BpMatBuilt.Text", String.format(Locale.ROOT, "Built %d of %d", all - left, all));
        for (int r = 0; r < ROWS; r++) {
            boolean used = r < rows.size();
            any |= put(b, "#BpRow" + r + ".Visible", used);
            if (!used) {
                continue;
            }
            Map.Entry<String, int[]> e = rows.get(r);
            int carried = have.getOrDefault(e.getKey(), 0);
            int still = e.getValue()[1];
            boolean short_ = carried < still;
            String numbers = carried + " / " + still;
            any |= put(b, "#BpIco" + r + ".ItemId", e.getKey());
            any |= put(b, "#BpNum" + r + ".Text", numbers);
            any |= put(b, "#BpLow" + r + ".Text", numbers);
            any |= put(b, "#BpNum" + r + ".Visible", !short_);
            any |= put(b, "#BpLow" + r + ".Visible", short_);
        }
        any |= put(b, "#BpMatMore.Text", rows.size() > ROWS ? "+" + (rows.size() - ROWS) + " kinds" : " ");
        return any;
    }

    /** {@code value} set in {@code b} unless it is what was last sent there; whether it was set. */
    private boolean put(Hud.Batch b, String selector, Object value) {
        if (Objects.equals(sent.get(selector), value)) {
            return false;
        }
        if (value instanceof Boolean v) {
            b.set(selector, v);
        } else {
            b.set(selector, (String) value);
        }
        sent.put(selector, value);
        return true;
    }

    /** Text the panel shows: printable ASCII only - a name may hold anything. */
    private static String ascii(String s) {
        StringBuilder b = new StringBuilder(s == null ? 0 : s.length());
        if (s != null) {
            for (int i = 0; i < s.length(); i++) {
                char ch = s.charAt(i);
                b.append(ch >= 32 && ch < 127 && ch != '"' ? ch : '?');
            }
        }
        return b.length() == 0 ? " " : b.toString();
    }
}
