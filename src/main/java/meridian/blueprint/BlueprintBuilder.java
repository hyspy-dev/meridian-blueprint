package meridian.blueprint;

/**
 * Construction-side helpers - they turn an input into a ready-to-render {@link Blueprint}. A prefab
 * file is core's ({@code Prefabs.read} and {@code resolve}, then {@link Blueprint#of}); what is left
 * here is the self-test shape.
 */
final class BlueprintBuilder {

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
}
