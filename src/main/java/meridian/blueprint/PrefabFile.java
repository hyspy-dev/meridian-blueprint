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
        /**
         * Which way the block faces: the server's index 0..63 ({@code roll*16 + pitch*4 + yaw},
         * each a quarter turn); absent in JSON ⇒ 0, unturned.
         */
        int rotation;
        /**
         * Non-zero on a cell a bigger block (a door, a bed) only reaches into: the packed offset
         * back to its own cell ({@code Block.filler()} in core). Such cells are not placed.
         */
        int filler;
        /** Hytale's support indicator. Not visualised. */
        int support;
    }

    /** One fluid cell - the server keeps fluids in a list of their own, beside the blocks. */
    static final class Fluid {
        int x;
        int y;
        int z;
        /** The fluid by name ({@code "Water_Source"}, {@code "Water"}). */
        String name;
        /** The server's fill level of the cell. */
        int level;
    }

    int version;
    int blockIdVersion;
    int anchorX;
    int anchorY;
    int anchorZ;
    List<Block> blocks;
    /** Absent in a prefab without water or lava. */
    List<Fluid> fluids;
}
