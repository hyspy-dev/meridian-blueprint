package meridian.blueprint;

/**
 * Which way a block faces, and what happens to that when a whole blueprint is turned or
 * mirrored.
 *
 * <p>The server keeps a block's orientation as one number, 0..63: {@code roll * 16 + pitch * 4 +
 * yaw}, each a quarter turn. Under it is a rotation matrix {@code Ry(yaw) * Rx(pitch) *
 * Rz(roll)} whose entries are all 0 or ±1, so every step here is exact integer arithmetic. The
 * maths is the server's own ({@code RotationTuple}, {@code BlockRotationUtil}), carried over line
 * for line, because a blueprint turned here has to match one the server would paste.
 *
 * <p>The 64 numbers describe only 24 orientations - several numbers can mean the same facing -
 * so two blocks are compared by {@link #canonical}, never by the raw number.
 */
final class BlockRotations {

    /** Axis numbering shared with the preview's rotate and mirror controls. */
    static final int X = 0, Y = 1, Z = 2;

    private BlockRotations() {}

    /**
     * A block's orientation after the blueprint it sits in is turned a quarter turn around
     * {@code axis}, the same way the preview turns positions ({@code Axis.rotate} on the server).
     */
    static int rotate(int index, int axis) {
        int[][] turn = switch (axis) {
            case X -> new int[][] {{1, 0, 0}, {0, 0, -1}, {0, 1, 0}};
            case Y -> new int[][] {{0, 0, 1}, {0, 1, 0}, {-1, 0, 0}};
            case Z -> new int[][] {{0, -1, 0}, {1, 0, 0}, {0, 0, 1}};
            default -> throw new IllegalArgumentException("axis " + axis);
        };
        return toIndex(multiply(turn, toMatrix(index)));
    }

    /**
     * A block's orientation after the blueprint is mirrored across the plane perpendicular to
     * {@code axis}. The reflection is folded back into a proper rotation the way the server does
     * for its default, symmetric blocks; the few block types it marks otherwise come out turned a
     * quarter from what the server would paste, since that mark does not reach the client.
     */
    static int mirror(int index, int axis) {
        if (axis < X || axis > Z) {
            throw new IllegalArgumentException("axis " + axis);
        }
        int[][] m = toMatrix(index);
        for (int i = 0; i < 3; i++) {
            m[axis][i] = -m[axis][i];
        }
        int[][] symmetric = {{-1, 0, 0}, {0, 1, 0}, {0, 0, 1}};
        return toIndex(multiply(m, symmetric));
    }

    /** The one number for this orientation that every number meaning the same facing maps to. */
    static int canonical(int index) {
        return toIndex(toMatrix(index));
    }

    /** Whether two orientation numbers describe the same facing. */
    static boolean same(int a, int b) {
        return a == b || canonical(a) == canonical(b);
    }

    // ------------------------------------------------------------------

    private static int[][] toMatrix(int index) {
        int i = index & 63;
        int yaw = i & 3, pitch = (i >> 2) & 3, roll = i >> 4;
        int cy = cos(yaw), sy = sin(yaw);
        int cp = cos(pitch), sp = sin(pitch);
        int cr = cos(roll), sr = sin(roll);
        return new int[][] {
            { cy * cr + sy * sp * sr, -cy * sr + sy * sp * cr,  sy * cp},
            {                cp * sr,                 cp * cr,      -sp},
            {-sy * cr + cy * sp * sr,  sy * sr + cy * sp * cr,  cy * cp}
        };
    }

    private static int toIndex(int[][] m) {
        int sp = -m[1][2];
        int pitch = quarter(sp, sp == 0 ? 1 : 0);
        int yaw;
        int roll;
        if (sp != 1 && sp != -1) {
            yaw = quarter(m[0][2], m[2][2]);
            roll = quarter(m[1][0], m[1][1]);
        } else {
            yaw = quarter(-m[2][0], m[0][0]);   // gimbal lock: roll folds into yaw
            roll = 0;
        }
        return roll * 16 + pitch * 4 + yaw;
    }

    /** The quarter turn with this sine and cosine. */
    private static int quarter(int sin, int cos) {
        if (sin == 0 && cos == 1) return 0;
        if (sin == 1 && cos == 0) return 1;
        if (sin == 0 && cos == -1) return 2;
        if (sin == -1 && cos == 0) return 3;
        throw new IllegalArgumentException("not a quarter turn: sin=" + sin + " cos=" + cos);
    }

    private static int cos(int q) {
        return q == 0 ? 1 : q == 2 ? -1 : 0;
    }

    private static int sin(int q) {
        return q == 1 ? 1 : q == 3 ? -1 : 0;
    }

    private static int[][] multiply(int[][] a, int[][] b) {
        int[][] r = new int[3][3];
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                r[i][j] = a[i][0] * b[0][j] + a[i][1] * b[1][j] + a[i][2] * b[2][j];
            }
        }
        return r;
    }
}
