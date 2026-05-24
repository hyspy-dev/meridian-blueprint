package meridian.blueprint;

import com.google.gson.Gson;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Reads a {@code .prefab.json} file from disk into {@link PrefabFile}.
 *
 * <p>One method, one purpose — keeps the JSON parsing dependency out of every
 * other class. {@link Gson} is intentionally re-created each call: the cost
 * is trivial, and a single static instance would pin Gson's classes into
 * memory for the life of the module.
 */
final class PrefabLoader {

    private PrefabLoader() {}

    /** Loads + parses {@code path}; throws on any I/O or JSON failure. */
    static PrefabFile load(Path path) throws IOException {
        String json = Files.readString(path, StandardCharsets.UTF_8);
        PrefabFile pf = new Gson().fromJson(json, PrefabFile.class);
        if (pf == null) {
            throw new IOException("prefab file decoded to null: " + path);
        }
        if (pf.blocks == null) {
            // Empty array is fine — but missing the key is a malformed file.
            throw new IOException("prefab file has no 'blocks' array: " + path);
        }
        return pf;
    }
}
