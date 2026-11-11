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
import meridian.core.api.FluidCell;
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
 * Builds a blueprint where it stands, with the player's own character or a headless one:
 *
 * <ol>
 *   <li>What is in the way broken, top down: another block where the blueprint has one, the right
 *       block turned the wrong way, a block where the blueprint has air. A fluid where the blueprint
 *       has air is shut out with scaffolding - a block put in a fluid's cell takes its place.</li>
 *   <li>The blueprint's blocks put in, bottom up, each where it has something to lean on, with the
 *       rotation the blueprint gives it.</li>
 *   <li>Its water poured in, a bucket at a time, filled at the nearest water outside it. Lava has no
 *       bucket in the game: a blueprint's lava is left, and said so.</li>
 *   <li>The scaffolding taken down, the drops picked up.</li>
 * </ol>
 *
 * <p>The blocks come from the player's inventory - the storage and the backpack into the hotbar as
 * needed - and, when it runs out, out of the nearest chests that hold them, or the chosen one.
 *
 * <p>Scaffolding, of a cheap block the player carries: a column under a block that has nothing to
 * lean on yet; and a pillar to stand on when a block is out of reach from anywhere the body gets to
 * - every block it reaches placed from its top, then the pillar taken down under the feet. Getting
 * at each block, breaking, placing, pouring and pillaring are core's {@link Worker}.
 *
 * <p>{@link #buildAlong} builds the blueprint again and again further along an axis, each copy
 * against the one before: a bridge, a road, a wall.
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
    record Result(String status, int placed, int broken, int scaffolds, int left, Map<String, Integer> lacking,
                  int poured, int copies) {}

    /** Blocks scaffolding is made of, cheapest first, when none is chosen: those the player carries. */
    static final List<String> CHEAP = List.of("Soil_Dirt", "Rock_Stone_Cobble", "Rock_Stone", "Soil_Grass",
            "Rock_Sandstone", "Wood_Oak_Planks");
    /** The tallest scaffold column under a block: its blocks go in from below. */
    static final int COLUMN_MOST = 6;
    /** The tallest pillar to stand on: it is taken down under the feet. */
    static final int PILLAR_MOST = 24;
    /** From a pillar's top, blocks within this of the eyes are placed before it comes down. */
    static final double FROM_PILLAR = 4.0;
    /** How often a block is tried before it is left. */
    static final int TRIES = 3;
    /** Chests looked in for blocks within this far, at most this many. */
    static final double CHEST_RADIUS = 32;
    static final int CHESTS_MOST = 8;
    /** Water to fill a bucket at is looked for within this far of the body. */
    static final int WATER_RADIUS = 24;
    /** The empty bucket, and what it is called once filled with water. */
    static final String BUCKET = "Container_Bucket";
    static final String WATER_BUCKET = "*Container_Bucket_State_Filled_Water";
    /** The id of the box that outlines the block being worked on. */
    static final String TARGET_BOX = "meridian-blueprint:target";

    /** One block of the blueprint, where it goes in the world; {@code id} 0: air, {@code item} null. */
    private record Cell(BlockPos pos, int id, int rotation, String item) {
        boolean air() {
            return id == 0;
        }
    }

    private enum State { CORRECT, MISSING, WRONG, ROTATED, WET }

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
        return buildAlong(bp, options, 0, 0, 1);
    }

    /**
     * Builds the blueprint, then again {@code (stepX, stepZ)} further along, and again: {@code copies}
     * times, or with {@code copies} under 1 until stopped or a copy is left unfinished (the blocks ran
     * out, or it could not be built). Each copy leans on the one before.
     */
    CompletableFuture<Result> buildAlong(Blueprint bp, Options options, int stepX, int stepZ, int copies) {
        if (bp == null || bp.size + bp.fluids.size() == 0) {
            status = "Nothing to build: place a blueprint first.";
            return CompletableFuture.completedFuture(new Result(status, 0, 0, 0, 0, Map.of(), 0, 0));
        }
        int id = job.incrementAndGet();
        worker.cancel();
        CompletableFuture<Result> before = running;
        CompletableFuture<Result> done = new CompletableFuture<>();
        running = done;
        Options o = options == null ? Options.DEFAULT : options;
        executor.execute(() -> {
            try {
                try {
                    before.get(1, TimeUnit.MINUTES);
                } catch (Exception ignored) {
                    // ended badly or slowly: this one goes on
                }
                Result r = run(id, bp, o, stepX, stepZ, copies);
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

    private Result run(int id, Blueprint bp, Options o, int stepX, int stepZ, int copies) throws Exception {
        if (!worker.available()) {
            return new Result("Cannot move the player here.", 0, 0, 0, 0, Map.of(), 0, 0);
        }
        Movement.Wheel wheel = movement.take().orElse(null);
        if (wheel == null) {
            return new Result("Cannot move the player here.", 0, 0, 0, 0, Map.of(), 0, 0);
        }
        wheel.released().thenRun(() -> job.compareAndSet(id, id + 1));
        try {
            Result total = null;
            for (int k = 0; copies < 1 || k < copies; k++) {
                Blueprint copy = k == 0 ? bp : shifted(bp, stepX * k, stepZ * k);
                Result r = construct(id, copy, o, k, copies);
                total = total == null ? r : sum(total, r);
                if (!live(id) || r.left() > 0 || (stepX == 0 && stepZ == 0)) {
                    break;
                }
            }
            if (total != null && total.copies() > 1) {
                // A line of copies: the whole of it said, the last copy's state at the end.
                String head = !live(id) ? "Stopped" : total.left() == 0 ? "Built" : "Done";
                String s = String.format(Locale.ROOT, "%s %d copies: %d placed, %d broken, %d scaffold%s%s%s.", head,
                        total.copies(), total.placed(), total.broken(), total.scaffolds(),
                        total.poured() > 0 ? ", " + total.poured() + " poured" : "",
                        total.left() > 0 ? ", " + total.left() + " still to build in the last" : "",
                        total.lacking().isEmpty() ? "" : ", short of " + total.lacking().keySet());
                total = new Result(s, total.placed(), total.broken(), total.scaffolds(), total.left(), total.lacking(),
                        total.poured(), total.copies());
            }
            return total;
        } finally {
            wheel.release();
        }
    }

    private static Blueprint shifted(Blueprint bp, int dx, int dz) {
        return new Blueprint(bp.anchorX + dx, bp.anchorY, bp.anchorZ + dz, bp.dx, bp.dy, bp.dz, bp.blockId, bp.rotation,
                bp.fluids);
    }

    private static Result sum(Result a, Result b) {
        Map<String, Integer> lacking = new LinkedHashMap<>(a.lacking());
        b.lacking().forEach((k, v) -> lacking.merge(k, v, Integer::sum));
        return new Result(b.status(), a.placed() + b.placed(), a.broken() + b.broken(), a.scaffolds() + b.scaffolds(),
                b.left(), lacking, a.poured() + b.poured(), a.copies() + b.copies());
    }

    private Result construct(int id, Blueprint bp, Options o, int copy, int copies) throws Exception {
        Map<Integer, BlockView> types = new HashMap<>();
        for (BlockView t : worldState.allBlockTypes()) {
            types.put(t.id(), t);
        }
        List<Cell> cells = new ArrayList<>(bp.size);
        for (int i = 0; i < bp.size; i++) {
            BlockPos pos = new BlockPos(bp.anchorX + bp.dx[i], bp.anchorY + bp.dy[i], bp.anchorZ + bp.dz[i]);
            if (bp.blockId[i] == 0) {
                cells.add(new Cell(pos, 0, 0, null));
                continue;
            }
            BlockView t = types.get(bp.blockId[i]);
            if (t == null) {
                continue;
            }
            BlockView fam = types.getOrDefault(t.family(), t);
            cells.add(new Cell(pos, bp.blockId[i], bp.rotation[i] & 0xFF, fam.name()));
        }
        if (cells.isEmpty() && bp.fluids.size() == 0) {
            return new Result("Nothing to build: no block of the blueprint is known here.", 0, 0, 0, 0, Map.of(), 0, 0);
        }
        String what = copies == 1 ? "" : " (copy " + (copy + 1) + (copies > 1 ? " of " + copies : "") + ")";
        BlockBox box = bounds(cells, bp);
        Map<BlockPos, Cell> byPos = new HashMap<>();
        for (Cell c : cells) {
            byPos.put(c.pos(), c);
        }
        Progress pr = new Progress(what);
        Set<BlockPos> scaffolds = new LinkedHashSet<>();
        Set<BlockPos> skipped = new HashSet<>();
        Map<BlockPos, Integer> tries = new HashMap<>();
        Map<String, Integer> lacking = new LinkedHashMap<>();
        Worker.Access inBox = Worker.Access.inside(box);

        // 1. What is in the way, top down: broken; a fluid where air goes shut out with scaffolding.
        List<Cell> wrong = new ArrayList<>();
        for (Cell c : cells) {
            State s = state(c);
            if (s == State.WRONG || s == State.ROTATED || s == State.WET) {
                wrong.add(c);
            }
        }
        wrong.sort(Comparator.comparingInt((Cell c) -> -c.pos().y()));
        for (Cell c : wrong) {
            if (!live(id)) {
                break;
            }
            if (!clear(id, c, inBox, pr, scaffolds, o, cells)) {
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
                } else if (s != State.CORRECT && inTheWay == null) {
                    inTheWay = c;
                }
            }
            if (inTheWay != null) {
                // Something came into a cell since (it fell, it flowed, it was placed).
                if (!clear(id, inTheWay, inBox, pr, scaffolds, o, cells)
                        || tries.merge(inTheWay.pos(), 1, Integer::sum) >= TRIES) {
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
                status = pr.line("a column under " + text(lowest.pos()));
                if (!support(id, lowest.pos(), byPos, scaffolds, o, cells)) {
                    skipped.add(lowest.pos());
                }
                continue;
            }
            Worker.Outcome out = placeCell(id, next, pending, o, pr, lacking, skipped);
            if (out == null) {
                continue;
            }
            switch (out) {
                case PLACED, OCCUPIED -> { }
                case INTERRUPTED, NOT_AVAILABLE -> job.incrementAndGet();
                case NO_PLACE, UNREACHABLE -> {
                    // Out of reach from anywhere the body gets to: a pillar to stand on beside it.
                    if (tries.merge(next.pos(), 1, Integer::sum) >= TRIES
                            || !fromPillar(id, next.pos(), byPos, o, pr, lacking, skipped, cells)) {
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

        // 3. The water poured in.
        if (live(id)) {
            pour(id, bp, pr, lacking);
        }

        // 4. The scaffolding down, top first; then what was broken picked up.
        List<BlockPos> down = new ArrayList<>(scaffolds);
        down.sort(Comparator.comparingInt((BlockPos p) -> -p.y()));
        for (BlockPos p : down) {
            if (!live(id)) {
                break;
            }
            if (world.blockAt(p.x(), p.y(), p.z()).isAir()) {
                continue;
            }
            status = pr.line("taking down the scaffolding");
            mark(p);
            Worker.Dug d = worker.dig(p, Worker.Access.inside(BlockBox.of(p.x(), p.y(), p.z(), p.x(), p.y(), p.z())),
                    Worker.Tools.BEST).get(10, TimeUnit.MINUTES);
            pr.broken.addAll(d.broken());
        }
        unmark();
        if (live(id) && !pr.broken.isEmpty()) {
            status = pr.line("picking up the drops");
            worker.pickUp(box.grow(4)).get(10, TimeUnit.MINUTES);
        }
        int left = 0;
        for (Cell c : cells) {
            if (state(c) != State.CORRECT) {
                left++;
            }
        }
        left += waterLeft(bp);
        String head = !live(id) ? "Stopped" : left == 0 ? "Built" : "Done";
        StringBuilder s = new StringBuilder(String.format(Locale.ROOT, "%s%s: %d placed, %d broken, %d scaffold%s%s",
                head, what, pr.placed, pr.broken.size(), scaffolds.size(), pr.poured > 0 ? ", " + pr.poured + " poured" : "",
                left > 0 ? ", " + left + " still to build" : ""));
        if (!lacking.isEmpty()) {
            s.append(", short of ").append(lacking.keySet());
        }
        return new Result(s.append('.').toString(), pr.placed, pr.broken.size(), scaffolds.size(), left, lacking,
                pr.poured, 1);
    }

    /** What a build has done so far, for its status line. */
    private static final class Progress {
        final String what;
        final List<BlockPos> broken = new ArrayList<>();
        int placed;
        int poured;

        Progress(String what) {
            this.what = what;
        }

        String line(String doing) {
            return "Building" + what + ": " + placed + " placed, " + broken.size() + " broken - " + doing + ".";
        }
    }

    /**
     * A cell made ready: a block in the way broken; a fluid where air goes shut out with scaffolding
     * (the block takes the fluid's place, and comes down at the end). {@code false} when it could not be.
     */
    private boolean clear(int id, Cell c, Worker.Access inBox, Progress pr, Set<BlockPos> scaffolds, Options o,
                          List<Cell> cells) throws Exception {
        State s = state(c);
        mark(c.pos());
        if (s == State.WET) {
            String item = scaffoldItem(o);
            if (item == null) {
                return false;
            }
            status = pr.line("shutting out the fluid at " + text(c.pos()));
            Worker.Outcome out = worker.place(c.pos(), item, -1).get(5, TimeUnit.MINUTES);
            if (out == Worker.Outcome.PLACED) {
                scaffolds.add(c.pos());
                return true;
            }
            return false;
        }
        status = pr.line("clearing " + text(c.pos()));
        Worker.Dug d = worker.dig(c.pos(), inBox, Worker.Tools.BEST).get(10, TimeUnit.MINUTES);
        pr.broken.addAll(d.broken());
        if (d.outcome() == Worker.Outcome.INTERRUPTED || d.outcome() == Worker.Outcome.NOT_AVAILABLE) {
            job.incrementAndGet();
            return false;
        }
        return d.outcome() == Worker.Outcome.BROKEN || d.outcome() == Worker.Outcome.NOTHING;
    }

    /**
     * One block placed, its item fetched first when the player has none; {@code null} when it was left
     * for want of the item.
     */
    private Worker.Outcome placeCell(int id, Cell c, List<Cell> pending, Options o, Progress pr,
                                     Map<String, Integer> lacking, Set<BlockPos> skipped) throws Exception {
        if (!carried(c.item()) && !fetch(id, c.item(), needed(c.item(), pending), o)) {
            lacking.merge(c.item(), 1, Integer::sum);
            skipped.add(c.pos());
            return null;
        }
        status = pr.line("placing " + c.item() + " at " + text(c.pos()));
        mark(c.pos());
        Worker.Outcome out = worker.place(c.pos(), c.item(), c.rotation()).get(5, TimeUnit.MINUTES);
        if (out == Worker.Outcome.PLACED) {
            pr.placed++;
        } else if (out == Worker.Outcome.NO_ITEM) {
            if (!fetch(id, c.item(), needed(c.item(), pending), o)) {
                lacking.merge(c.item(), 1, Integer::sum);
                skipped.add(c.pos());
                return null;
            }
        }
        return out;
    }

    /** How a cell stands against the world, the way the overlay paints it. */
    private State state(Cell c) {
        BlockPos p = c.pos();
        Block actual = world.blockAt(p.x(), p.y(), p.z());
        int actualId = actual != null && actual.type() != null ? actual.type().id() : 0;
        boolean air = actual == null || actual.isAir() || actualId <= 0;
        if (c.air()) {
            if (!air) {
                return State.WRONG;
            }
            return world.fluidCellAt(p.x(), p.y(), p.z()) == null ? State.CORRECT : State.WET;
        }
        if (air) {
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

    private double fromEyes(BlockPos p) {
        Vec3 me = movement.position();
        if (me == null) {
            return Double.MAX_VALUE;
        }
        double eye = movement.capabilities().eyeHeight();
        double dx = p.x() + 0.5 - me.x();
        double dy = p.y() + 0.5 - (me.y() + eye);
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
        while (ground > cell.y() - 1 - COLUMN_MOST - 1 && world.known(cell.x(), ground, cell.z())
                && world.blockAt(cell.x(), ground, cell.z()).isAir()) {
            ground--;
        }
        if (cell.y() - 1 - ground > COLUMN_MOST || world.blockAt(cell.x(), ground, cell.z()).isAir()) {
            return false;
        }
        String item = scaffoldItem(o);
        if (item == null) {
            return false;
        }
        for (int y = ground + 1; y < cell.y(); y++) {
            if (!live(id)) {
                return false;
            }
            BlockPos p = new BlockPos(cell.x(), y, cell.z());
            Cell own = byPos.get(p);
            if (own != null && !own.air() && state(own) == State.MISSING) {
                continue;                     // one of the blueprint's: it goes in on its own turn
            }
            mark(p);
            Worker.Outcome out = worker.place(p, item, -1).get(5, TimeUnit.MINUTES);
            if (out == Worker.Outcome.PLACED) {
                scaffolds.add(p);
            } else if (out != Worker.Outcome.OCCUPIED) {
                log.info("blueprint: the column under {} stopped at {} ({})", text(cell), text(p), out);
                return false;
            }
        }
        return true;
    }

    /**
     * A pillar of scaffolding to stand on beside a cell no place reaches - on a column of ground next
     * to it, not one of the blueprint's, as high as puts the feet a block under the cell; from its
     * top, the cell and every other block within reach with something to lean on placed; then the
     * pillar taken down under the feet. {@code false} when there is no such column, it would be too
     * tall, or the cell is still not placed.
     */
    private boolean fromPillar(int id, BlockPos cell, Map<BlockPos, Cell> byPos, Options o, Progress pr,
                               Map<String, Integer> lacking, Set<BlockPos> skipped, List<Cell> cells) throws Exception {
        String item = scaffoldItem(o);
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
                for (int y = cell.y() - 1; y >= cell.y() - 1 - PILLAR_MOST; y--) {
                    BlockPos at = new BlockPos(cell.x() + dx, y, cell.z() + dz);
                    if (movement.standAt(at).isEmpty()) {
                        continue;
                    }
                    int h = (cell.y() - 1) - y;
                    boolean clear = h <= PILLAR_MOST;
                    for (int k = 0; clear && k <= h + 2; k++) {
                        BlockPos c = new BlockPos(at.x(), y + k, at.z());
                        clear = !byPos.containsKey(c) && world.blockAt(c.x(), c.y(), c.z()).isAir();
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
        status = pr.line("a pillar of " + height + " at " + text(base) + " to reach " + text(cell));
        NavResult r = navigator.goTo(Goal.at(base)).get(3, TimeUnit.MINUTES);
        if (!live(id) || r.status() != NavResult.Status.ARRIVED) {
            return false;
        }
        Worker.Outcome up = worker.pillarUp(item, height).get(2, TimeUnit.MINUTES);
        boolean placed = false;
        if (up == Worker.Outcome.PLACED) {
            // Every block within reach from up here, nearest the cell first.
            List<Cell> near = new ArrayList<>();
            for (Cell c : cells) {
                if (!c.air() && !skipped.contains(c.pos()) && state(c) == State.MISSING && fromEyes(c.pos()) <= FROM_PILLAR) {
                    near.add(c);
                }
            }
            near.sort(Comparator.comparingInt((Cell c) -> c.pos().y()).thenComparingDouble(c -> fromEyes(c.pos())));
            boolean more = true;
            while (more && live(id)) {
                more = false;
                for (Cell c : near) {
                    if (state(c) != State.MISSING || !supported(c.pos())) {
                        continue;
                    }
                    Worker.Outcome out = placeCell(id, c, near, o, pr, lacking, skipped);
                    if (out == Worker.Outcome.PLACED) {
                        more = true;
                        placed |= c.pos().equals(cell);
                    }
                }
            }
        }
        // Down again, the pillar taken down under the feet.
        status = pr.line("coming down the pillar");
        Vec3 at = movement.position();
        int blocks = at == null ? height : Math.max(0, (int) Math.floor(at.y() + 1e-3) - base.y());
        if (blocks > 0) {
            worker.pillarDown(blocks).get(3, TimeUnit.MINUTES);
        }
        return placed || !world.blockAt(cell.x(), cell.y(), cell.z()).isAir();
    }

    // ------------------------------------------------------------------
    // Water
    // ------------------------------------------------------------------

    /**
     * The blueprint's water sources poured in, a bucket at a time, the bucket filled at the nearest
     * water source outside the blueprint. Its other fluids - lava - cannot be carried: said so.
     */
    private void pour(int id, Blueprint bp, Progress pr, Map<String, Integer> lacking) throws Exception {
        int water = world.fluidId("Water_Source");
        Set<BlockPos> wet = new HashSet<>();
        List<BlockPos> sources = new ArrayList<>();
        for (int i = 0; i < bp.fluids.size(); i++) {
            BlockPos p = new BlockPos(bp.anchorX + bp.fluids.dx()[i], bp.anchorY + bp.fluids.dy()[i],
                    bp.anchorZ + bp.fluids.dz()[i]);
            wet.add(p);
            int fid = bp.fluids.fluidId()[i];
            if (fid == water) {
                sources.add(p);
            } else if (fid == world.fluidId("Lava_Source")) {
                FluidCell f = world.fluidCellAt(p.x(), p.y(), p.z());
                if (f == null || f.id() != fid) {
                    lacking.merge("lava (no bucket carries it)", 1, Integer::sum);
                }
            }
        }
        sources.sort(Comparator.comparingInt(BlockPos::y));
        for (BlockPos p : sources) {
            if (!live(id)) {
                return;
            }
            FluidCell f = world.fluidCellAt(p.x(), p.y(), p.z());
            if (f != null && f.id() == water) {
                continue;
            }
            if (!world.blockAt(p.x(), p.y(), p.z()).isAir()) {
                continue;
            }
            if (!carried(WATER_BUCKET)) {
                if (!carried(BUCKET)) {
                    lacking.merge(BUCKET, 1, Integer::sum);
                    return;
                }
                if (!fillBucket(id, wet, pr)) {
                    lacking.merge("water to fill a bucket at", 1, Integer::sum);
                    return;
                }
            }
            status = pr.line("pouring water at " + text(p));
            mark(p);
            Worker.Outcome out = worker.pour(p, WATER_BUCKET).get(5, TimeUnit.MINUTES);
            if (out == Worker.Outcome.PLACED) {
                pr.poured++;
            } else {
                log.info("blueprint: water at {} not poured ({})", text(p), out);
            }
        }
    }

    /** A bucket filled at the nearest water source outside the blueprint. */
    private boolean fillBucket(int id, Set<BlockPos> wet, Progress pr) throws Exception {
        Vec3 me = movement.position();
        int water = world.fluidId("Water_Source");
        if (me == null || water < 0) {
            return false;
        }
        int cx = (int) Math.floor(me.x());
        int cy = (int) Math.floor(me.y());
        int cz = (int) Math.floor(me.z());
        List<BlockPos> found = new ArrayList<>();
        for (int dy = -8; dy <= 8; dy++) {
            for (int dx = -WATER_RADIUS; dx <= WATER_RADIUS; dx++) {
                for (int dz = -WATER_RADIUS; dz <= WATER_RADIUS; dz++) {
                    int x = cx + dx;
                    int y = cy + dy;
                    int z = cz + dz;
                    FluidCell f = world.fluidCellAt(x, y, z);
                    if (f != null && f.id() == water && !wet.contains(new BlockPos(x, y, z))) {
                        found.add(new BlockPos(x, y, z));
                    }
                }
            }
        }
        found.sort(Comparator.comparingDouble(this::distance));
        for (BlockPos p : found.subList(0, Math.min(4, found.size()))) {
            if (!live(id)) {
                return false;
            }
            status = pr.line("filling a bucket at " + text(p));
            if (worker.scoop(p, BUCKET).get(3, TimeUnit.MINUTES) == Worker.Outcome.FILLED) {
                return true;
            }
        }
        return false;
    }

    /** The blueprint's water sources still dry. */
    private int waterLeft(Blueprint bp) {
        int water = world.fluidId("Water_Source");
        int n = 0;
        for (int i = 0; i < bp.fluids.size(); i++) {
            if (bp.fluids.fluidId()[i] != water) {
                continue;
            }
            FluidCell f = world.fluidCellAt(bp.anchorX + bp.fluids.dx()[i], bp.anchorY + bp.fluids.dy()[i],
                    bp.anchorZ + bp.fluids.dz()[i]);
            if (f == null || f.id() != water) {
                n++;
            }
        }
        return n;
    }

    // ------------------------------------------------------------------

    /** The block scaffolding is made of: the chosen one, or the cheapest carried. */
    private String scaffoldItem(Options o) {
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
            if (item.equals(c.item())) {
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
     * it - those core saw holding it first, then the rest in turn.
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

    private static BlockBox bounds(List<Cell> cells, Blueprint bp) {
        int x0 = Integer.MAX_VALUE, y0 = Integer.MAX_VALUE, z0 = Integer.MAX_VALUE;
        int x1 = Integer.MIN_VALUE, y1 = Integer.MIN_VALUE, z1 = Integer.MIN_VALUE;
        List<BlockPos> all = new ArrayList<>();
        for (Cell c : cells) {
            all.add(c.pos());
        }
        for (int i = 0; i < bp.fluids.size(); i++) {
            all.add(new BlockPos(bp.anchorX + bp.fluids.dx()[i], bp.anchorY + bp.fluids.dy()[i],
                    bp.anchorZ + bp.fluids.dz()[i]));
        }
        for (BlockPos p : all) {
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

    private static String text(BlockPos p) {
        return p.x() + " " + p.y() + " " + p.z();
    }
}
