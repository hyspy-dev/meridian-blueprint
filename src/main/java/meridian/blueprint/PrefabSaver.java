package meridian.blueprint;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import meridian.core.api.Block;
import meridian.core.api.World;

/**
 * Captures a selection box into a {@code .prefab.json} on disk — the same
 * format the server uses, so files saved here drop straight into a server's
 * {@code prefabs/} directory and load via the in-game paste tool.
 *
 * <p>Air is skipped — sparse prefabs (a house with hollow rooms) end up
 * smaller and don't carry meaningless "Empty" markers. If we ever need
 * sealed volumes (where every voxel including air is named) we'd add a
 * toggle here; for now the captured prefab matches what the player would
 * see if they walked through the volume.
 *
 * <p>JSON layout matches {@code SelectionPrefabSerializer} v8:
 * <pre>{@code
 * {
 *   "version": 8,
 *   "blockIdVersion": 0,
 *   "anchorX": 0, "anchorY": 0, "anchorZ": 0,
 *   "blocks": [ { "x":0, "y":0, "z":0, "name":"Rock_Stone" }, ... ]
 * }
 * }</pre>
 */
final class PrefabSaver {

    /** Serializer format version — same constant the server uses. */
    private static final int VERSION = 8;
    /** Block-id-version we stamp the file with. Server uses a migration
     *  chain; we write 0 (== "no migration applied") and the receiving
     *  server applies whatever chain it has registered on load. */
    private static final int BLOCK_ID_VERSION = 0;

    private PrefabSaver() {}

    /** POJO layout fed straight to Gson. Mirrors {@link PrefabFile} but
     *  drops the loader-only fields we don't write back (support / filler /
     *  components — server treats absent as default). */
    private static final class Doc {
        int version = VERSION;
        int blockIdVersion = BLOCK_ID_VERSION;
        int anchorX, anchorY, anchorZ;
        List<DocBlock> blocks;
    }

    private static final class DocBlock {
        int x, y, z;
        String name;
        Integer rotation;       // omitted when 0

        DocBlock(int x, int y, int z, String name, int rotation) {
            this.x = x; this.y = y; this.z = z;
            this.name = name;
            this.rotation = rotation == 0 ? null : rotation;
        }
    }

    /**
     * Scans every voxel inside the box, builds a {@link Doc}, and writes
     * it as pretty-printed JSON to {@code dir/<name>.prefab.json}.
     *
     * @return number of blocks written (air skipped)
     * @throws IOException on disk failure or unsafe filename
     */
    static int save(World world, SelectionStore.Box box, Path dir, String name) throws IOException {
        String safe = sanitize(name);
        if (safe.isEmpty()) {
            throw new IOException("blueprint name is empty after sanitisation");
        }
        Path target = dir.resolve(safe + ".prefab.json");

        List<DocBlock> blocks = new ArrayList<>(Math.min(box.blockCount(), 4096));
        for (int y = box.yMin(); y <= box.yMax(); y++) {
            for (int z = box.zMin(); z <= box.zMax(); z++) {
                for (int x = box.xMin(); x <= box.xMax(); x++) {
                    Block b = world.blockAt(x, y, z);
                    if (b == null || b.type() == null || b.isAir()) continue;
                    String typeName = b.type().name();
                    if (typeName == null || typeName.isEmpty()) continue;
                    blocks.add(new DocBlock(
                            x - box.xMin(),
                            y - box.yMin(),
                            z - box.zMin(),
                            typeName,
                            0));    // rotation unknown from server — write 0
                }
            }
        }

        Doc doc = new Doc();
        doc.blocks = blocks;
        // anchor stays (0,0,0) — the file's coords are already local; the
        // server's "anchor" is where the prefab pins to the world on paste,
        // and 0,0,0 is the conventional default ("pin at the local origin").

        Files.createDirectories(target.getParent());
        Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
        String json = gson.toJson(doc);
        Files.writeString(target, json, StandardCharsets.UTF_8);
        return blocks.size();
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
