package meridian.blueprint;

import java.util.ArrayList;
import java.util.List;
import meridian.core.api.FluidCell;

/**
 * Gathers the fluid cells of a box while its blocks are scanned - for a copy and for a save
 * alike, so both keep the same water and lava.
 */
final class FluidCollector {

    /** One fluid cell, relative to the box, with its name for the prefab file. */
    record Cell(int x, int y, int z, int id, String name, int level) {}

    private final List<Cell> cells = new ArrayList<>();

    /** Takes the fluid at a cell, if there is one ({@code fluid} as {@code World.fluidCellAt} gave it). */
    void add(FluidCell fluid, int x, int y, int z) {
        if (fluid != null && fluid.id() > 0 && fluid.name() != null) {
            cells.add(new Cell(x, y, z, fluid.id(), fluid.name(), fluid.level()));
        }
    }

    List<Cell> cells() {
        return cells;
    }

    /** The cells as a blueprint's fluid layer. */
    Blueprint.Fluids build() {
        int n = cells.size();
        if (n == 0) {
            return Blueprint.Fluids.NONE;
        }
        int[] dx = new int[n], dy = new int[n], dz = new int[n], ids = new int[n];
        byte[] level = new byte[n];
        for (int i = 0; i < n; i++) {
            Cell c = cells.get(i);
            dx[i] = c.x();
            dy[i] = c.y();
            dz[i] = c.z();
            ids[i] = c.id();
            level[i] = (byte) c.level();
        }
        return new Blueprint.Fluids(dx, dy, dz, ids, level);
    }
}
