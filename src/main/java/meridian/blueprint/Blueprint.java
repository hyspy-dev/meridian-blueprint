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

    Blueprint(int anchorX, int anchorY, int anchorZ,
              int[] dx, int[] dy, int[] dz, int[] blockId, byte[] rotation) {
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
    }
}
