package meridian.blueprint;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;
import org.slf4j.Logger;

/**
 * Read-only view of the module's prefab directory — owns the snapshot the
 * settings live-list reads from.
 *
 * <p>The proxy polls {@link #rows()} on the UI EDT roughly twice per second
 * (see {@code SettingsSpec.liveList}). That contract demands a cheap read:
 * doing {@link Files#list} on every poll would block the UI on slow disks.
 * Instead a scheduled {@link #refresh} task rebuilds a {@code volatile
 * Snapshot} off the EDT, and the supplier just hands out the latest snapshot
 * reference.
 *
 * <p>Click-payload pairing follows the snapshot pattern documented in
 * {@code docs/settings.md} — {@link Snapshot} carries both the displayed rows
 * and the matching file paths, so a click at index {@code i} resolves
 * against the exact rows the user saw on screen.
 */
final class PrefabLibrary {

    /** Pair of (display rows, matching file paths). Indices line up. */
    record Snapshot(List<String> rows, List<Path> paths) {
        static final Snapshot EMPTY = new Snapshot(List.of(), List.of());
    }

    private static final String PREFAB_SUFFIX = ".prefab.json";
    private static final String EMPTY_HINT = "(no .prefab.json files yet)";

    private final Logger log;
    private final Path dir;
    private volatile Snapshot snapshot = Snapshot.EMPTY;

    PrefabLibrary(Logger log, Path dir) {
        this.log = log;
        this.dir = dir;
    }

    /** Volatile read — safe to call from any thread, including the EDT. */
    List<String> rows() {
        List<String> r = snapshot.rows();
        // The live-list widget treats an empty list as "no widget at all";
        // surfacing a single hint row makes the empty state visible and
        // tells the user where to drop files.
        return r.isEmpty() ? List.of(EMPTY_HINT) : r;
    }

    /** Resolves a row index from the most-recent snapshot to its file path. */
    Optional<Path> pathAt(int index) {
        Snapshot s = snapshot;
        if (index < 0 || index >= s.paths().size()) return Optional.empty();
        return Optional.of(s.paths().get(index));
    }

    /**
     * Rescans the directory and atomically publishes a new {@link Snapshot}.
     * Called from a scheduler tick, off the EDT — {@link Files#list} is
     * allowed to block here.
     */
    void refresh() {
        if (!Files.isDirectory(dir)) {
            snapshot = Snapshot.EMPTY;
            return;
        }
        try (Stream<Path> stream = Files.list(dir)) {
            List<Path> sorted = stream
                    .filter(p -> p.getFileName().toString().endsWith(PREFAB_SUFFIX))
                    .sorted(Comparator.comparingLong((Path p) -> p.toFile().lastModified()).reversed())
                    .toList();
            List<String> rows = new ArrayList<>(sorted.size());
            List<Path> paths = new ArrayList<>(sorted.size());
            for (Path p : sorted) {
                rows.add(formatRow(p));
                paths.add(p);
            }
            snapshot = new Snapshot(List.copyOf(rows), List.copyOf(paths));
        } catch (IOException e) {
            log.warn("scan failed for {}", dir, e);
            snapshot = Snapshot.EMPTY;
        }
    }

    /** Human-readable row: name + size in KB. Latest-modified comes first. */
    private static String formatRow(Path p) {
        String name = p.getFileName().toString();
        // Trim the .prefab.json suffix so long names stay readable.
        String base = name.endsWith(PREFAB_SUFFIX)
                ? name.substring(0, name.length() - PREFAB_SUFFIX.length())
                : name;
        long sizeKb = Math.max(1, p.toFile().length() / 1024);
        return String.format(Locale.ROOT, "%s (%d KB)", base, sizeKb);
    }
}
