package meridian.blueprint;

import java.util.ArrayList;
import java.util.List;
import meridian.core.api.Clipboard;
import meridian.core.api.PastePreview;

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

    /** A clipboard - cells relative to its anchor - as a blueprint whose anchor is at {@code (x, y, z)}. */
    static Blueprint of(int x, int y, int z, Clipboard clipboard) {
        List<PastePreview.Change> blocks = clipboard.blocks();
        int n = blocks.size();
        int[] dx = new int[n], dy = new int[n], dz = new int[n], ids = new int[n];
        byte[] rot = new byte[n];
        for (int i = 0; i < n; i++) {
            PastePreview.Change c = blocks.get(i);
            dx[i] = c.dx();
            dy[i] = c.dy();
            dz[i] = c.dz();
            ids[i] = c.blockId();
            rot[i] = (byte) c.rotation();
        }
        List<PastePreview.FluidChange> wet = clipboard.fluids();
        int m = wet.size();
        int[] fx = new int[m], fy = new int[m], fz = new int[m], fids = new int[m];
        byte[] level = new byte[m];
        for (int i = 0; i < m; i++) {
            PastePreview.FluidChange f = wet.get(i);
            fx[i] = f.dx();
            fy[i] = f.dy();
            fz[i] = f.dz();
            fids[i] = f.fluidId();
            level[i] = (byte) f.level();
        }
        return new Blueprint(x, y, z, dx, dy, dz, ids, rot, m == 0 ? Fluids.NONE : new Fluids(fx, fy, fz, fids, level));
    }

    /** The cells as a clipboard, relative to the anchor - what the paste tool and the preview take. */
    Clipboard toClipboard() {
        List<PastePreview.Change> blocks = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            blocks.add(new PastePreview.Change(dx[i], dy[i], dz[i], blockId[i], rotation[i] & 0xFF));
        }
        List<PastePreview.FluidChange> wet = new ArrayList<>(fluids.size());
        for (int i = 0; i < fluids.size(); i++) {
            wet.add(new PastePreview.FluidChange(fluids.dx()[i], fluids.dy()[i], fluids.dz()[i],
                    fluids.fluidId()[i], fluids.level()[i]));
        }
        return new Clipboard(blocks, wet);
    }
}
