package meridian.blueprint;

import java.util.List;

/**
 * POJO mirror of a {@code .prefab.json} document — the server's own format
 * ({@code SelectionPrefabSerializer} v8). Despite the extension this is plain
 * UTF-8 JSON: the Hytale server's {@code BsonUtil.writeDocument} writes
 * extended-JSON, not binary BSON.
 *
 * <p>Only the fields the preview pipeline needs are decoded; entities and
 * fluids are accepted-and-ignored so a real prefab still parses, even though
 * the diff/overlay layer doesn't act on them yet. {@code blockIdVersion} is
 * also kept around but not currently used — once we hit a server that
 * actually emits a non-current id version we'll wire migrations through it.
 */
final class PrefabFile {

    /** One block cell. Coordinates are relative to the prefab's anchor. */
    static final class Block {
        int x;
        int y;
        int z;
        /** Block type id by name (e.g. {@code "Rock_Stone"} or {@code "Empty"}). */
        String name;
        /** Block rotation (0..7); absent in JSON ⇒ defaults to 0. */
        int rotation;
        /** Hytale's filler bitfield for neighbour-cull hints. Not visualised. */
        int filler;
        /** Hytale's support indicator. Not visualised. */
        int support;
    }

    int version;
    int blockIdVersion;
    int anchorX;
    int anchorY;
    int anchorZ;
    List<Block> blocks;
}
