package meridian.blueprint;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import meridian.core.api.Block;
import meridian.core.api.BlockBox;
import meridian.core.api.BlockPos;
import meridian.core.api.BlockView;
import meridian.core.api.Containers;
import meridian.core.api.DebugRender;
import meridian.core.api.Goal;
import meridian.core.api.Movement;
import meridian.core.api.NavResult;
import meridian.core.api.Navigator;
import meridian.core.api.PlayerState;
import meridian.core.api.Vec3;
import meridian.core.api.Worker;
import meridian.core.api.World;
import meridian.core.api.WorldState;
import org.slf4j.Logger;

/**
 * Builds a blueprint where it stands, with the player's own character or a headless one: first the
 * blocks in the way - another block in a cell, or the right one turned the wrong way - broken, top
 * down; then the blueprint's blocks put in, bottom up, each where it has something to lean on, with
 * the rotation the blueprint gives it; then the scaffolding taken down and the drops picked up.
 *
 * <p>The blocks come from the player's inventory - the storage and the backpack into the hotbar as
 * needed - and, when it runs out, out of the nearest chests that hold them, or the chosen one.
 *
 * <p>Scaffolding, of a cheap block the player carries: a column under a block that has nothing to
 * lean on yet, and a pillar to stand on when a block is out of reach from anywhere the body gets
 * to. Both are taken down at the end. Getting at each block, breaking it, placing it and pillaring
 * up are core's {@link Worker}.
 */
final class Construction {

    /** Where blocks the player runs out of come from. */
    enum Chests { OFF, NEAREST, CHOSEN }

    /**
     * @param chests   where blocks the player runs out of come from
     * @param chest    the chosen chest, for {@link Chests#CHOSEN}
     * @param scaffold the item to build scaffolding of; {@code null} or empty: a cheap block carried
     */
    record Options(Chests chests, BlockPos chest, String scaffold) {
        static final Options DEFAULT = new Options(Chests.NEAREST, null, null);
    }

    /** How a build went. */
    record Result(String status, int placed, int broken, int scaffolds, int left, Map<String, Integer> lacking) {}

    /** Blocks scaffolding is made of, cheapest first, when none is chosen: those the player carries. */
    static final List<String> CHEAP = List.of("Soil_Dirt", "Rock_Stone_Cobble", "Rock_Stone", "Soil_Grass",
            "Rock_Sandstone", "Wood_Oak_Planks");
    /** The tallest scaffold column or pillar: a pillar's top is still a drop the body survives. */
    static final int SCAFFOLD_MOST = 6;
    /** How often a block is tried before it is left. */
    static final int TRIES = 3;
    /** Chests looked in for blocks within this far, at most this many. */
    static final double CHEST_RADIUS = 32;
    static final int CHESTS_MOST = 8;
    /** The id of the box that outlines the block being worked on. */
    static final String TARGET_BOX = "meridian-blueprint:target";

    /** One block of the blueprint, where it goes in the world. */
    private record Cell(BlockPos pos, int id, int rotation, String item) {}

    private enum State { CORRECT, MISSING, WRONG, ROTATED }

    private final Movement movement;
    private final World world;
    private final WorldState worldState;
    private final Worker worker;
    private final Navigator navigator;
    private final PlayerState inventory;
    private final Containers containers;
    private final DebugRender debug;
    private final Executor executor;
    private final Logger log;
    private final AtomicInteger job = new AtomicInteger();
    private volatile String status = "Not building.";
    private volatile CompletableFuture<Result> running = CompletableFuture.completedFuture(null);

    Construction(Movement movement, World world, WorldState worldState, Worker worker, Navigator navigator,
                 PlayerState inventory, Containers containers, DebugRender debug, Executor executor, Logger log) {
        this.movement = movement;
        this.world = world;
        this.worldState = worldState;
        this.worker = worker;
        this.navigator = navigator;
        this.inventory = inventory;
        this.containers = containers;
        this.debug = debug;
        this.executor = executor;
        this.log = log;
    }

    String status() {
        return status;
    }

    /** Ends the build under way; completes once it has stopped. */
    CompletableFuture<Void> cancel() {
        job.incrementAndGet();
        worker.cancel();
        return running.handle((r, e) -> null);
    }

    /** Builds the blueprint where it stands. */
    CompletableFuture<Result> build(Blueprint bp, Options options) {
        if (bp == null || bp.size == 0) {
            status = "Nothing to build: place a blueprint first.";
            return CompletableFuture.completedFuture(new Result(status, 0, 0, 0, 0, Map.of()));
        }
        int id = job.incrementAndGet();
        worker.cancel();
        CompletableFuture<Result> before = running;
        CompletableFuture<Result> done = new CompletableFuture<>();
        running = done;
        executor.execute(() -> {
            try {
                try {
                    before.get(1, TimeUnit.MINUTES);
                } catch (Exception ignored) {
                    // ended badly or slowly: this one goes on
                }
                Result r = run(id, bp, options == null ? Options.DEFAULT : options);
                status = r.status();
                log.info("blueprint: {}", status);
                done.complete(r);
            } catch (Throwable t) {
                log.warn("blueprint: building failed", t);
                status = "Failed: " + t;
                done.completeExceptionally(t);
            } finally {
                unmark();
            }
        });
        return done;
    }

    private boolean live(int id) {
        return job.get() == id;
    }

    private Result run(int id, Blueprint bp, Options o) throws Exception {
        if (!worker.available()) {
            return new Result("Cannot move the player here.", 0, 0, 0, 0, Map.of());
        }
        Movement.Wheel wheel = movement.take().orElse(null);
        if (wheel == null) {
            return new Result("Cannot move the player here.", 0, 0, 0, 0, Map.of());
        }
        wheel.released().thenRun(() -> job.compareAndSet(id, id + 1));
        try {
            return construct(id, bp, o);
        } finally {
            wheel.release();
        }
    }

    private Result construct(int id, Blueprint bp, Options o) throws Exception {
        Map<Integer, BlockView> types = new HashMap<>();
        for (BlockView t : worldState.allBlockTypes()) {
            types.put(t.id(), t);
        }
        List<Cell> cells = new ArrayList<>(bp.size);
        for (int i = 0; i < bp.size; i++) {
            BlockView t = types.get(bp.blockId[i]);
            if (t == null) {
                continue;
            }
            BlockView fam = types.getOrDefault(t.family(), t);
            cells.add(new Cell(new BlockPos(bp.anchorX + bp.dx[i], bp.anchorY + bp.dy[i], bp.anchorZ + bp.dz[i]),
                    bp.blockId[i], bp.rotation[i] & 0xFF, fam.name()));
        }
        if (cells.isEmpty()) {
            return new Result("Nothing to build: no block of the blueprint is known here.", 0, 0, 0, 0, Map.of());
        }
        BlockBox box = bounds(cells);
        Map<BlockPos, Cell> byPos = new HashMap<>();
        for (Cell c : cells) {
            byPos.put(c.pos(), c);
        }
        int placed = 0;
        List<BlockPos> broken = new ArrayList<>();
        Set<BlockPos> scaffolds = new LinkedHashSet<>();
        Set<BlockPos> skipped = new HashSet<>();
        Map<BlockPos, Integer> tries = new HashMap<>();
        Map<String, Integer> lacking = new LinkedHashMap<>();
        Worker.Access inBox = Worker.Access.inside(box);

        // 1. What is in the way: another block, or the right one turned wrong - broken top down.
        List<Cell> wrong = new ArrayList<>();
        for (Cell c : cells) {
            State s = state(c);
            if (s == State.WRONG || s == State.ROTATED) {
                wrong.add(c);
            }
        }
        wrong.sort(Comparator.comparingInt((Cell c) -> -c.pos().y()));
        for (Cell c : wrong) {
            if (!live(id)) {
                break;
            }
            status = progress(placed, broken.size(), "clearing " + text(c.pos()));
            mark(c.pos());
            Worker.Dug d = worker.dig(c.pos(), inBox, Worker.Tools.BEST).get(10, TimeUnit.MINUTES);
            broken.addAll(d.broken());
            if (d.outcome() == Worker.Outcome.INTERRUPTED || d.outcome() == Worker.Outcome.NOT_AVAILABLE) {
                job.incrementAndGet();
            } else if (d.outcome() != Worker.Outcome.BROKEN && d.outcome() != Worker.Outcome.NOTHING) {
                skipped.add(c.pos());
            }
        }

        // 2. The blueprint's blocks, bottom up, each where it has something to lean on.
        while (live(id)) {
            List<Cell> pending = new ArrayList<>();
            Cell inTheWay = null;
            for (Cell c : cells) {
                if (skipped.contains(c.pos())) {
                    continue;
                }
                State s = state(c);
                if (s == State.MISSING) {
                    pending.add(c);
                } else if ((s == State.WRONG || s == State.ROTATED) && inTheWay == null) {
                    inTheWay = c;
                }
            }
            if (inTheWay != null) {
                // Something came into a cell since (it fell, it was placed): broken again, or left.
                mark(inTheWay.pos());
                Worker.Dug d = worker.dig(inTheWay.pos(), inBox, Worker.Tools.BEST).get(10, TimeUnit.MINUTES);
                broken.addAll(d.broken());
                if (d.outcome() != Worker.Outcome.BROKEN && tries.merge(inTheWay.pos(), 1, Integer::sum) >= TRIES) {
                    skipped.add(inTheWay.pos());
                }
                continue;
            }
            if (pending.isEmpty()) {
                break;
            }
            Cell next = choose(pending);
            if (next == null) {
                // Nothing left with something to lean on: a column of scaffolding under the lowest.
                Cell lowest = pending.stream().min(Comparator.comparingInt((Cell c) -> c.pos().y())
                        .thenComparingDouble(c -> distance(c.pos()))).orElseThrow();
                status = progress(placed, broken.size(), "a column under " + text(lowest.pos()));
                if (!support(id, lowest.pos(), byPos, scaffolds, o, cells)) {
                    skipped.add(lowest.pos());
                }
                continue;
            }
            if (!carried(next.item()) && !fetch(id, next.item(), needed(next.item(), pending), o)) {
                lacking.merge(next.item(), 1, Integer::sum);
                skipped.add(next.pos());
                continue;
            }
            status = progress(placed, broken.size(), "placing " + next.item() + " at " + text(next.pos()));
            mark(next.pos());
            Worker.Outcome out = worker.place(next.pos(), next.item(), next.rotation()).get(5, TimeUnit.MINUTES);
            switch (out) {
                case PLACED -> placed++;
                case OCCUPIED -> { }
                case INTERRUPTED, NOT_AVAILABLE -> job.incrementAndGet();
                case NO_ITEM -> {
                    if (!fetch(id, next.item(), needed(next.item(), pending), o)) {
                        lacking.merge(next.item(), 1, Integer::sum);
                        skipped.add(next.pos());
                    }
                }
                case NO_PLACE, UNREACHABLE -> {
                    // Out of reach from anywhere the body gets to: a pillar to stand on beside it.
                    if (tries.merge(next.pos(), 1, Integer::sum) >= TRIES || !pillar(id, next.pos(), byPos, scaffolds, o)) {
                        skipped.add(next.pos());
                        log.info("blueprint: left {} ({})", text(next.pos()), out);
                    }
                }
                default -> {
                    if (tries.merge(next.pos(), 1, Integer::sum) >= TRIES) {
                        skipped.add(next.pos());
                        log.info("blueprint: left {} ({})", text(next.pos()), out);
                    }
                }
            }
        }

        // 3. The scaffolding down, top first; then what was broken picked up.
        List<BlockPos> down = new ArrayList<>(scaffolds);
        down.sort(Comparator.comparingInt((BlockPos p) -> -p.y()));
        for (BlockPos p : down) {
            if (!live(id)) {
                break;
            }
            status = progress(placed, broken.size(), "taking down the scaffolding");
            mark(p);
            Worker.Dug d = worker.dig(p, Worker.Access.inside(BlockBox.of(p.x(), p.y(), p.z(), p.x(), p.y(), p.z())),
                    Worker.Tools.BEST).get(10, TimeUnit.MINUTES);
            broken.addAll(d.broken());
        }
        unmark();
        if (live(id) && !broken.isEmpty()) {
            status = progress(placed, broken.size(), "picking up the drops");
            worker.pickUp(box.grow(4)).get(10, TimeUnit.MINUTES);
        }
        int left = 0;
        for (Cell c : cells) {
            if (state(c) != State.CORRECT) {
                left++;
            }
        }
        String head = !live(id) ? "Stopped" : left == 0 ? "Built" : "Done";
        StringBuilder s = new StringBuilder(String.format(Locale.ROOT, "%s: %d placed, %d broken, %d scaffold%s",
                head, placed, broken.size(), scaffolds.size(), left > 0 ? ", " + left + " still to build" : ""));
        if (!lacking.isEmpty()) {
            s.append(", short of ").append(lacking.keySet());
        }
        return new Result(s.append('.').toString(), placed, broken.size(), scaffolds.size(), left, lacking);
    }

    /** How a cell stands against the world, the way the overlay paints it. */
    private State state(Cell c) {
        BlockPos p = c.pos();
        Block actual = world.blockAt(p.x(), p.y(), p.z());
        int actualId = actual != null && actual.type() != null ? actual.type().id() : 0;
        if (actual == null || actual.isAir() || actualId <= 0) {
            return State.MISSING;
        }
        if (!actual.matches(c.id(), actual.rotation())) {
            return State.WRONG;
        }
        return actual.matches(c.id(), c.rotation()) ? State.CORRECT : State.ROTATED;
    }

    /** Whether a block in the cell has something to lean on: one beside it, below or above. */
    private boolean supported(BlockPos p) {
        int[][] around = {{0, -1, 0}, {1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}, {0, 1, 0}};
        for (int[] d : around) {
            int x = p.x() + d[0];
            int y = p.y() + d[1];
            int z = p.z() + d[2];
            if (world.known(x, y, z) && !world.blockAt(x, y, z).isAir()
                    && movement.cell(new BlockPos(x, y, z)).solid()) {
                return true;
            }
        }
        return false;
    }

    /**
     * The next block to place: of those with something to lean on, the lowest layer first, and in it
     * the nearest to the body.
     */
    private Cell choose(List<Cell> pending) {
        Cell best = null;
        double bestCost = Double.MAX_VALUE;
        for (Cell c : pending) {
            if (!supported(c.pos())) {
                continue;
            }
            double cost = c.pos().y() * 1000.0 + distance(c.pos());
            if (cost < bestCost) {
                bestCost = cost;
                best = c;
            }
        }
        return best;
    }

    private double distance(BlockPos p) {
        Vec3 me = movement.position();
        if (me == null) {
            return 0;
        }
        double dx = p.x() + 0.5 - me.x();
        double dy = p.y() + 0.5 - me.y();
        double dz = p.z() + 0.5 - me.z();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /**
     * A column of scaffolding under the cell, from the first block below it up to just under it, so
     * the cell has something to lean on. {@code false} when it is too tall, or a block of it would
     * not go in.
     */
    private boolean support(int id, BlockPos cell, Map<BlockPos, Cell> byPos, Set<BlockPos> scaffolds, Options o,
                            List<Cell> cells) throws Exception {
        int ground = cell.y() - 1;
        while (ground > cell.y() - 1 - SCAFFOLD_MOST - 1 && world.known(cell.x(), ground, cell.z())
                && world.blockAt(cell.x(), ground, cell.z()).isAir()) {
            ground--;
        }
        if (cell.y() - 1 - ground > SCAFFOLD_MOST || world.blockAt(cell.x(), ground, cell.z()).isAir()) {
            return false;
        }
        String item = scaffoldItem(o, cells);
        if (item == null) {
            return false;
        }
        for (int y = ground + 1; y < cell.y(); y++) {
            if (!live(id)) {
                return false;
            }
            BlockPos p = new BlockPos(cell.x(), y, cell.z());
            mark(p);
            Worker.Outcome out = worker.place(p, item, -1).get(5, TimeUnit.MINUTES);
            if (out == Worker.Outcome.PLACED) {
                if (!byPos.containsKey(p)) {
                    scaffolds.add(p);
                }
            } else if (out != Worker.Outcome.OCCUPIED) {
                log.info("blueprint: the column under {} stopped at {} ({})", text(cell), text(p), out);
                return false;
            }
        }
        return true;
    }

    /**
     * A pillar of scaffolding to stand on beside a cell no place reaches: on a column of ground next to
     * it, not one of the blueprint's, as high as puts the feet a block under the cell. {@code false}
     * when there is none, or it would be too tall.
     */
    private boolean pillar(int id, BlockPos cell, Map<BlockPos, Cell> byPos, Set<BlockPos> scaffolds, Options o)
            throws Exception {
        String item = scaffoldItem(o, null);
        if (item == null) {
            return false;
        }
        BlockPos base = null;
        int height = 0;
        double best = Double.MAX_VALUE;
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                if (dx == 0 && dz == 0 || Math.abs(dx) + Math.abs(dz) > 2) {
                    continue;
                }
                // The ground of the column, under the cell's height.
                for (int y = cell.y() - 1; y >= cell.y() - 1 - SCAFFOLD_MOST; y--) {
                    BlockPos at = new BlockPos(cell.x() + dx, y, cell.z() + dz);
                    if (movement.standAt(at).isEmpty()) {
                        continue;
                    }
                    int h = (cell.y() - 1) - y;
                    boolean clear = h <= SCAFFOLD_MOST;
                    for (int k = 0; clear && k <= h + 1; k++) {
                        BlockPos c = new BlockPos(at.x(), y + k, at.z());
                        clear = !byPos.containsKey(c) && (k < h || world.blockAt(c.x(), c.y(), c.z()).isAir());
                    }
                    double d = distance(at) + h;
                    if (clear && d < best) {
                        best = d;
                        base = at;
                        height = h;
                    }
                    break;
                }
            }
        }
        if (base == null || height <= 0) {
            return false;
        }
        status = "Building: a pillar of " + height + " at " + text(base) + " to reach " + text(cell) + ".";
        NavResult r = navigator.goTo(Goal.at(base)).get(3, TimeUnit.MINUTES);
        if (!live(id) || r.status() != NavResult.Status.ARRIVED) {
            return false;
        }
        Worker.Outcome out = worker.pillarUp(item, height).get(2, TimeUnit.MINUTES);
        for (int k = 0; k < height; k++) {
            BlockPos p = new BlockPos(base.x(), base.y() + k, base.z());
            if (!world.blockAt(p.x(), p.y(), p.z()).isAir()) {
                scaffolds.add(p);
            }
        }
        return out == Worker.Outcome.PLACED;
    }

    /** The block scaffolding is made of: the chosen one, or the cheapest carried the blueprint does not need. */
    private String scaffoldItem(Options o, List<Cell> cells) {
        if (o.scaffold() != null && !o.scaffold().isBlank()) {
            return carried(o.scaffold()) ? o.scaffold() : null;
        }
        for (String item : CHEAP) {
            if (carried(item)) {
                return item;
            }
        }
        return null;
    }

    /** How many of the item the blocks still to place take. */
    private static int needed(String item, List<Cell> pending) {
        int n = 0;
        for (Cell c : pending) {
            if (c.item().equals(item)) {
                n++;
            }
        }
        return n;
    }

    /** Whether the player carries any of the item: the hotbar, the storage or the backpack. */
    private boolean carried(String item) {
        if (inventory == null) {
            return true;
        }
        for (Map.Entry<String, PlayerState.Section> e : inventory.inventories().entrySet()) {
            if (!List.of("Hotbar", "Storage", "Backpack").contains(e.getKey())) {
                continue;
            }
            for (Containers.Item it : e.getValue().items().values()) {
                if (it != null && item.equals(it.id()) && it.quantity() > 0) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Takes up to {@code count} of the item out of a chest: the chosen one, or the nearest that hold
     * it - those core saw opened first, then the rest in turn.
     */
    private boolean fetch(int id, String item, int count, Options o) throws Exception {
        if (o.chests() == Chests.OFF || count <= 0) {
            return carried(item);
        }
        List<BlockPos> chests = new ArrayList<>();
        if (o.chests() == Chests.CHOSEN) {
            if (o.chest() != null) {
                chests.add(o.chest());
            }
        } else {
            Vec3 me = movement.position();
            if (me != null) {
                for (Block b : world.find(me, CHEST_RADIUS, t -> t.name() != null && t.name().contains("Chest"),
                        CHESTS_MOST)) {
                    chests.add(new BlockPos(b.x(), b.y(), b.z()));
                }
            }
            // Those seen holding it first; those seen without it last.
            chests.sort(Comparator.comparingInt(c -> seenHolding(c, item)));
        }
        for (BlockPos chest : chests) {
            if (!live(id)) {
                return false;
            }
            if (seenHolding(chest, item) == 2) {
                continue;
            }
            status = "Building: taking " + item + " out of the chest at " + text(chest) + ".";
            Map<String, Integer> got = worker.take(chest, Map.of(item, count)).get(3, TimeUnit.MINUTES);
            if (got.getOrDefault(item, 0) > 0) {
                return true;
            }
        }
        return carried(item);
    }

    /** 0: core saw the chest holding the item; 1: not seen; 2: seen, without it. */
    private int seenHolding(BlockPos chest, String item) {
        Containers.Contents c = containers == null ? null : containers.at(chest);
        if (c == null) {
            return 1;
        }
        for (Containers.Item it : c.items().values()) {
            if (it != null && item.equals(it.id())) {
                return 0;
            }
        }
        return 2;
    }

    private static BlockBox bounds(List<Cell> cells) {
        int x0 = Integer.MAX_VALUE, y0 = Integer.MAX_VALUE, z0 = Integer.MAX_VALUE;
        int x1 = Integer.MIN_VALUE, y1 = Integer.MIN_VALUE, z1 = Integer.MIN_VALUE;
        for (Cell c : cells) {
            BlockPos p = c.pos();
            x0 = Math.min(x0, p.x());
            y0 = Math.min(y0, p.y());
            z0 = Math.min(z0, p.z());
            x1 = Math.max(x1, p.x());
            y1 = Math.max(y1, p.y());
            z1 = Math.max(z1, p.z());
        }
        return BlockBox.of(x0, y0, z0, x1, y1, z1);
    }

    /** Outlines the block worked on now, through walls. */
    private void mark(BlockPos p) {
        if (debug != null) {
            debug.worldBox(TARGET_BOX, p.x() + 0.5, p.y() + 0.5, p.z() + 0.5, 1.02, 1.02, 1.02, 0.2f, 0.8f, 1f, 0.35f);
        }
    }

    private void unmark() {
        if (debug != null) {
            debug.clearWorldBox(TARGET_BOX);
        }
    }

    private static String progress(int placed, int broken, String doing) {
        return "Building: " + placed + " placed, " + broken + " broken - " + doing + ".";
    }

    private static String text(BlockPos p) {
        return p.x() + " " + p.y() + " " + p.z();
    }
}
