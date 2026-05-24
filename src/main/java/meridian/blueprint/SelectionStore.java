package meridian.blueprint;

import java.util.Optional;
import meridian.core.api.BlockPos;

/**
 * Two-corner selection that drives the capture flow.
 *
 * <p>The player sets each corner explicitly by aiming at a block and
 * pressing a button — see {@link PreviewController#setCornerOneFromCrosshair}.
 * Once both corners exist {@link #box()} returns the axis-aligned bounding
 * box, ready for {@link PreviewController#captureSelection} to scan.
 *
 * <p>Earlier iterations tried to grab the box by spoofing the in-game
 * selection tool — the client checks builder/creative mode itself before
 * letting the tool be used, so a spoof has no effect outside of it. The
 * crosshair-pick path doesn't need any spoofing: it reads from observed
 * {@code MouseInteraction} traffic via the core service.
 */
final class SelectionStore {

    record Box(int xMin, int yMin, int zMin, int xMax, int yMax, int zMax) {
        int sizeX() { return xMax - xMin + 1; }
        int sizeY() { return yMax - yMin + 1; }
        int sizeZ() { return zMax - zMin + 1; }
        int blockCount() { return sizeX() * sizeY() * sizeZ(); }
    }

    private volatile BlockPos p1;
    private volatile BlockPos p2;

    Optional<BlockPos> p1() { return Optional.ofNullable(p1); }
    Optional<BlockPos> p2() { return Optional.ofNullable(p2); }

    void setP1(BlockPos pos) { this.p1 = pos; }
    void setP2(BlockPos pos) { this.p2 = pos; }

    /** Returns the AABB of {@code p1}/{@code p2}; empty if either is unset. */
    Optional<Box> box() {
        BlockPos a = p1, b = p2;
        if (a == null || b == null) return Optional.empty();
        int x0 = Math.min(a.x(), b.x()), x1 = Math.max(a.x(), b.x());
        int y0 = Math.min(a.y(), b.y()), y1 = Math.max(a.y(), b.y());
        int z0 = Math.min(a.z(), b.z()), z1 = Math.max(a.z(), b.z());
        return Optional.of(new Box(x0, y0, z0, x1, y1, z1));
    }

    void clear() {
        p1 = null;
        p2 = null;
    }
}
