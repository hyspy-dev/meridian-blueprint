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

    /** Rebuilds the name→id index from the live catalog if its size changed. */
    private Map<String, Integer> ensureIndex() {
        var types = worldState.allBlockTypes();
        int n = types.size();
        if (n == indexedAt && !index.isEmpty()) {
            return index;
        }
        Map<String, Integer> next = new HashMap<>(n * 2);
        for (BlockView v : types) {
            // Two block types occasionally share a name across migrations;
            // last-write wins, matching how the server's own asset map merges.
            next.put(v.name(), v.id());
        }
        index = next;
        indexedAt = n;
        return next;
    }
}
