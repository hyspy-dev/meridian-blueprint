package meridian.blueprint;

/**
 * Immutable blueprint payload — what the player wants placed and where.
 *
 * <p>Column-of-arrays layout: instead of {@code List<Record>} we keep four
 * parallel {@code int[]}s plus a {@code byte[]}. On an 8 000-cell prefab
 * that's ~160 KB of dense memory — one allocation, no per-cell object
 * headers, cache-friendly for the tick scan which streams through every
 * cell once per second. Iteration is a tight integer loop the JIT inlines
 * well; the previous {@code List<Expected>.get(i)} had a virtual call and
 * a record object dereference at every index.
 *
 * <p>Coordinates are <em>offsets from the anchor</em>, exactly how the
 * server-side prefab format stores them. The painter adds {@code anchor.*}
 * once per tick. We could pre-bake absolute coordinates but that doubles
 * the memory and the anchor never moves during a preview anyway.
 */
final class Blueprint {

    final int anchorX, anchorY, anchorZ;
    final int size;
    final int[] dx;
    final int[] dy;
    final int[] dz;
    final int[] blockId;
    final byte[] rotation;
    /** Water, lava and the like - a layer of their own, beside the blocks; may share their cells. */
    final Fluids fluids;

    /**
     * The fluid cells, laid out like the blocks: offsets from the same anchor, the fluid's id on
     * this connection, and its level as the server keeps it. A source and its flow are separate
     * fluids ({@code Water_Source}, {@code Water}).
     */
    record Fluids(int[] dx, int[] dy, int[] dz, int[] fluidId, byte[] level) {
        static final Fluids NONE = new Fluids(new int[0], new int[0], new int[0], new int[0], new byte[0]);

        Fluids {
            if (dx.length != dy.length || dy.length != dz.length
                    || dz.length != fluidId.length || fluidId.length != level.length) {
                throw new IllegalArgumentException("fluid arrays must have equal length");
            }
        }

        int size() {
            return dx.length;
        }
    }

    Blueprint(int anchorX, int anchorY, int anchorZ,
              int[] dx, int[] dy, int[] dz, int[] blockId, byte[] rotation) {
        this(anchorX, anchorY, anchorZ, dx, dy, dz, blockId, rotation, Fluids.NONE);
    }

    Blueprint(int anchorX, int anchorY, int anchorZ,
              int[] dx, int[] dy, int[] dz, int[] blockId, byte[] rotation, Fluids fluids) {
        if (dx.length != dy.length || dy.length != dz.length
                || dz.length != blockId.length || blockId.length != rotation.length) {
            throw new IllegalArgumentException("blueprint arrays must have equal length");
        }
        this.anchorX = anchorX;
        this.anchorY = anchorY;
        this.anchorZ = anchorZ;
        this.size = dx.length;
        this.dx = dx;
        this.dy = dy;
        this.dz = dz;
        this.blockId = blockId;
        this.rotation = rotation;
        this.fluids = fluids == null ? Fluids.NONE : fluids;
    }
}
