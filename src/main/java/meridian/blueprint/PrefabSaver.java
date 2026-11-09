package meridian.blueprint;

import java.io.IOException;
import java.nio.file.Path;
import meridian.core.api.BlockPos;
import meridian.core.api.Prefabs;

/**
 * Captures a selection box into a {@code .prefab.json} on disk — the server's own format, so files
 * saved here drop straight into a server's {@code prefabs/} directory and load via the in-game paste
 * tool. The format and the capture are core's ({@link Prefabs}); what is here is where the file goes.
 *
 * <p>Air is skipped — sparse prefabs (a house with hollow rooms) end up smaller and don't carry
 * meaningless "Empty" markers. The cells a door or a bed only fills are kept, with their offset
 * back to the block's own cell, as the server writes them.
 */
final class PrefabSaver {

    private PrefabSaver() {}

    /**
     * Captures every voxel inside the box and writes it to {@code dir/<name>.prefab.json}.
     *
     * @return number of blocks written (air skipped)
     * @throws IOException on disk failure or unsafe filename
     */
    static int save(Prefabs prefabs, SelectionStore.Box box, Path dir, String name) throws IOException {
        Path target = targetOf(dir, name);
        if (target == null) {
            throw new IOException("blueprint name is empty after sanitisation");
        }
        // The box's lowest corner is the anchor (0, 0, 0): the file's cells are already local, and
        // the anchor is where the prefab pins to the world on paste.
        Prefabs.Prefab prefab = prefabs.capture(new BlockPos(box.xMin(), box.yMin(), box.zMin()),
                new BlockPos(box.xMax(), box.yMax(), box.zMax()));
        prefabs.write(target, prefab);
        return prefab.blocks().size();
    }

    /** The file a prefab saved under {@code name} goes to - to ask before overwriting it. */
    static Path targetOf(Path dir, String name) {
        String safe = sanitize(name);
        return safe.isEmpty() ? null : dir.resolve(safe + ".prefab.json");
    }

    /** Strips characters that would either break the path or clash with the
     *  {@code .prefab.json} suffix the library scans for. */
    private static String sanitize(String raw) {
        if (raw == null) return "";
        String t = raw.trim();
        // Replace anything outside [A-Za-z0-9_-.] with underscore.
        StringBuilder sb = new StringBuilder(t.length());
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '_' || c == '-' || c == '.') {
                sb.append(c);
            } else if (c == ' ') {
                sb.append('_');
            }
            // Other characters are dropped silently.
        }
        // Don't let the user save into a `.prefab.json` already (we add the
        // suffix ourselves — double suffix breaks the library scan).
        String result = sb.toString();
        if (result.endsWith(".prefab.json")) {
            result = result.substring(0, result.length() - ".prefab.json".length());
        } else if (result.endsWith(".json")) {
            result = result.substring(0, result.length() - ".json".length());
        }
        return result;
    }
}
