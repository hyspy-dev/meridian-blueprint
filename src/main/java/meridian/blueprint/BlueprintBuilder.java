package meridian.blueprint;

import java.util.HashMap;
import java.util.Map;

/**
 * Construction-side helpers — they turn an input (a hard-coded shape, a
 * parsed {@link PrefabFile}) into a ready-to-render {@link Blueprint}.
 *
 * <p>Kept separate from {@link PreviewController} so it can be tested
 * standalone and so the controller stays a thin lifecycle wrapper.
 */
final class BlueprintBuilder {

    /** Result of {@link #fromPrefab} — the blueprint plus diagnostic counters. */
    record Result(Blueprint blueprint, int skippedEmpty, Map<String, Integer> unknownNames) {}

    private BlueprintBuilder() {}

    /**
     * Builds a 3×3×3 sanity cube of {@code blockId} starting one block above
     * the anchor (so the player isn't buried inside it).
     */
    static Blueprint sanityCube(int anchorX, int anchorY, int anchorZ, int blockId) {
        int n = 27;
        int[] dx = new int[n], dy = new int[n], dz = new int[n], ids = new int[n];
        byte[] rot = new byte[n];
        int i = 0;
        for (int y = 0; y < 3; y++) {
            for (int z = -1; z <= 1; z++) {
                for (int x = -1; x <= 1; x++) {
                    dx[i] = x;
                    dy[i] = y + 1;
                    dz[i] = z;
                    ids[i] = blockId;
                    rot[i] = 0;
                    i++;
                }
            }
        }
        return new Blueprint(anchorX, anchorY, anchorZ, dx, dy, dz, ids, rot);
    }

    /**
     * Converts a parsed {@link PrefabFile} into a {@link Blueprint} anchored
     * at {@code (anchorX, anchorY, anchorZ)}. The file's own anchor fields
     * are intentionally ignored — preview UX is "paste at my feet" regardless
     * of authoring offsets.
     *
     * <ul>
     *   <li>{@code "Empty"} cells (Hytale's "no block" marker inside the
     *       prefab format) are dropped silently.</li>
     *   <li>Names the live block catalog doesn't recognise are aggregated
     *       into {@link Result#unknownNames} — the caller logs one line per
     *       unique name instead of one line per dropped block.</li>
     * </ul>
     */
    static Result fromPrefab(PrefabFile prefab, BlockNameResolver names,
                             int anchorX, int anchorY, int anchorZ) {
        int max = prefab.blocks.size();
        // Worst-case allocation — trim later. Net memory is small even on
        // a 10k-cell prefab so this isn't worth a two-pass count.
        int[] dx = new int[max], dy = new int[max], dz = new int[max], ids = new int[max];
        byte[] rot = new byte[max];
        Map<String, Integer> unknown = new HashMap<>();
        int skippedEmpty = 0;
        int i = 0;
        for (PrefabFile.Block b : prefab.blocks) {
            int id = names.resolve(b.name);
            if (id == 0) {                          // "Empty" marker.
                skippedEmpty++;
                continue;
            }
            if (id < 0) {                           // Not in catalog.
                unknown.merge(b.name, 1, Integer::sum);
                continue;
            }
            dx[i] = b.x;
            dy[i] = b.y;
            dz[i] = b.z;
            ids[i] = id;
            rot[i] = (byte) b.rotation;
            i++;
        }
        // Trim — i is the real count.
        int[] dxT = trim(dx, i), dyT = trim(dy, i), dzT = trim(dz, i), idsT = trim(ids, i);
        byte[] rotT = new byte[i];
        System.arraycopy(rot, 0, rotT, 0, i);
        Blueprint bp = new Blueprint(anchorX, anchorY, anchorZ, dxT, dyT, dzT, idsT, rotT);
        return new Result(bp, skippedEmpty, unknown);
    }

    private static int[] trim(int[] src, int len) {
        if (len == src.length) return src;
        int[] out = new int[len];
        System.arraycopy(src, 0, out, 0, len);
        return out;
    }
}
