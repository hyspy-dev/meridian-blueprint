package meridian.blueprint;

import java.util.HashMap;
import java.util.Map;
import meridian.core.api.BlockView;
import meridian.core.api.WorldState;

/**
 * Maps the human-readable block-type names that live in {@code .prefab.json}
 * (e.g. {@code "Rock_Stone"}, {@code "Soil_Dirt"}, {@code "Empty"}) onto the
 * server's integer block ids that the protocol works with.
 *
 * <p>The catalog is owned by {@link WorldState#allBlockTypes} — populated
 * lazily by core from observed {@code UpdateBlockTypes} packets. Until that
 * packet has arrived the collection is empty; we rebuild the local index on
 * every {@link #resolve} call so a prefab loaded mid-handshake still finds
 * its block ids once the catalog catches up.
 *
 * <p>{@code "Empty"} is special — Hytale uses it as the "no block here" marker
 * inside a prefab (instead of omitting the entry). We resolve it to id 0
 * unconditionally; the diff loop treats id 0 as air and renders nothing.
 */
final class BlockNameResolver {

    private static final String EMPTY_NAME = "Empty";

    private final WorldState worldState;

    /** Cached snapshot of the catalog; rebuilt on each call when it grew. */
    private volatile Map<String, Integer> index = Map.of();
    /**
     * Block id → the lowest id of its family of states, for blocks that have states. A lamp that
     * is on and the same lamp off map to one family; a block with no states is absent.
     */
    private volatile Map<Integer, Integer> family = Map.of();
    /** Block id → whether which way it faces is part of what it is. */
    private volatile Map<Integer, Boolean> rotationMatters = Map.of();
    private volatile int indexedAt = -1;

    BlockNameResolver(WorldState worldState) {
        this.worldState = worldState;
    }

    /**
     * Returns the integer block id for {@code name}, or {@code -1} if the
     * server's catalog doesn't (yet) carry that block type.
     *
     * <p>{@code "Empty"} always resolves to 0 (air) — see class docs.
     */
    int resolve(String name) {
        if (name == null || name.isEmpty()) return -1;
        if (EMPTY_NAME.equals(name)) return 0;
        Map<String, Integer> snap = ensureIndex();
        Integer id = snap.get(name);
        return id == null ? -1 : id;
    }

    /**
     * Whether two block ids are the same block - the same id, or two states of one block: an open
     * door where the blueprint has it shut, a lamp that is lit. A state is the player's to change
     * at will, so it is not a wrong block.
     */
    boolean sameBlock(int a, int b) {
        if (a == b) {
            return true;
        }
        Integer fa = family.get(a);
        return fa != null && fa.equals(family.get(b));
    }

    /** Whether the way a block of this id faces is part of what it is. */
    boolean rotationMatters(int id) {
        return rotationMatters.getOrDefault(id, false);
    }

    /**
     * Catches up with the catalog if it has grown. {@link #sameBlock} and {@link #rotationMatters}
     * answer from what was last caught up, since they are asked for every cell of a scan - the
     * scan calls this once before it starts.
     */
    void refresh() {
        ensureIndex();
    }

    /** Rebuilds the name→id index from the live catalog if its size changed. */
    private Map<String, Integer> ensureIndex() {
        var types = worldState.allBlockTypes();
        int n = types.size();
        if (n == indexedAt && !index.isEmpty()) {
            return index;
        }
        Map<String, Integer> next = new HashMap<>(n * 2);
        Map<Integer, Boolean> turns = new HashMap<>(n * 2);
        // Families are joined both ways - every member names the others, but a catalog caught
        // half-way through arriving may not yet hold every member, so each link is kept.
        Map<Integer, Integer> parent = new HashMap<>();
        for (BlockView v : types) {
            // Two block types occasionally share a name across migrations;
            // last-write wins, matching how the server's own asset map merges.
            next.put(v.name(), v.id());
            turns.put(v.id(), v.rotationMatters());
            for (Integer stateId : v.states().values()) {
                if (stateId != null && stateId > 0) {
                    union(parent, v.id(), stateId);
                }
            }
        }
        Map<Integer, Integer> families = new HashMap<>(parent.size() * 2);
        for (Integer id : parent.keySet()) {
            families.put(id, root(parent, id));
        }
        family = families;
        rotationMatters = turns;
        index = next;
        indexedAt = n;
        return next;
    }

    private static int root(Map<Integer, Integer> parent, int id) {
        int r = id;
        while (parent.getOrDefault(r, r) != r) {
            r = parent.get(r);
        }
        return r;
    }

    /** Joins two families, keeping the lower id as the root so the answer is stable. */
    private static void union(Map<Integer, Integer> parent, int a, int b) {
        parent.putIfAbsent(a, a);
        parent.putIfAbsent(b, b);
        int ra = root(parent, a), rb = root(parent, b);
        if (ra != rb) {
            parent.put(Math.max(ra, rb), Math.min(ra, rb));
        }
    }
}
