package meridian.blueprint;

import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import meridian.api.module.Scheduler;
import meridian.core.api.BuilderSelection;
import meridian.core.api.Pages;
import meridian.core.api.PastePreview;
import org.slf4j.Logger;

/**
 * The paste tool's prefab list (its E key), filled with the module's own prefabs.
 *
 * <p>It is the game's own window - {@code Pages/PrefabListPage.ui}, the one the server opens for
 * an editor - driven the way the server drives it: a search field, one row per prefab, a click
 * spins it in the preview beside the list, a double click or Load puts it in the paste tool and
 * closes the window. The keys the rows send back are the server's too ({@code Preview},
 * {@code Browse}, {@code File}, {@code @SearchQuery}).
 */
final class PrefabListWindow implements Pages.Listener {

    private static final String PAGE = "Pages/PrefabListPage.ui";
    private static final String ROW = "Pages/BasicTextButton.ui";
    private static final String SUFFIX = ".prefab.json";

    private final Logger log;
    private final Pages pages;
    private final BuilderSelection tools;
    private final PrefabLibrary library;
    private final PreviewController preview;
    private final Scheduler scheduler;

    private volatile String query = "";
    /** The prefab clicked last - shown in the preview, loaded by the Load button. */
    private volatile String previewed;
    /** The rows as last shown: name to file. */
    private volatile Map<String, Path> shown = Map.of();

    PrefabListWindow(Logger log, Pages pages, BuilderSelection tools, PrefabLibrary library,
            PreviewController preview, Scheduler scheduler) {
        this.log = log;
        this.pages = pages;
        this.tools = tools;
        this.library = library;
        this.preview = preview;
        this.scheduler = scheduler;
    }

    /** Opens the window - from the network thread, so the folder is read here and nothing else. */
    void open() {
        library.refresh();
        previewed = null;
        Pages.Page page = pages.open("blueprint.prefabs", true, b -> {
            b.append(PAGE);
            searchField(b);
            persistent(b);
            b.set("#CurrentPath.Text", "Blueprint prefabs");
            fileList(b);
            loadButton(b);
        }, this);
        if (page == null) {
            log.warn("no client to show the prefab list to");
        }
    }

    @Override
    public void data(Pages.Page page, Map<String, String> data) {
        String search = data.get("@SearchQuery");
        if (search != null) {
            query = search;
            page.update(this::listing);
            return;
        }
        if ("~".equals(data.get("File"))) {
            query = "";
            page.update(b -> {
                searchField(b);
                listing(b);
            });
            return;
        }
        String name = data.get("Preview");
        if ("true".equals(data.get("Browse"))) {
            String target = name != null ? name : previewed;
            Path file = target == null ? null : shown.get(target);
            if (file != null) {
                tools.showPrefabPreview(null);
                page.close();
                scheduler.schedule(() -> preview.loadPrefabIntoPasteTool(file), Duration.ZERO);
            }
            return;
        }
        if (name != null && !name.equals(previewed)) {
            Path file = shown.get(name);
            if (file == null) {
                return;
            }
            previewed = name;
            page.update(b -> {
                persistent(b);
                fileList(b);
                loadButton(b);
                b.set("#PreviewMessage.Visible", false);
            });
            // A big prefab takes a while to read; the list has answered already.
            scheduler.schedule(() -> showPreview(page, name, file), Duration.ZERO);
        }
    }

    @Override
    public void dismissed(Pages.Page page) {
        tools.showPrefabPreview(null);
    }

    private void showPreview(Pages.Page page, String name, Path file) {
        if (!page.isOpen() || !name.equals(previewed)) {
            return;
        }
        Optional<Blueprint> bp = preview.readPrefab(file);
        if (!page.isOpen() || !name.equals(previewed)) {
            return;
        }
        if (bp.isEmpty()) {
            tools.showPrefabPreview(null);
            page.update(b -> b.set("#PreviewMessage.Text", "Could not read " + name)
                    .set("#PreviewMessage.Visible", true));
            return;
        }
        tools.showPrefabPreview(PreviewController.changesOf(bp.get()));
    }

    private void searchField(Pages.Builder b) {
        b.set("#SearchInput.Value", query);
        b.bind(Pages.Trigger.VALUE_CHANGED, "#SearchInput",
                Map.of("@SearchQuery", "#SearchInput.Value"), false);
    }

    private void persistent(Pages.Builder b) {
        b.bind(Pages.Trigger.ACTIVATING, "#HomeButton", Map.of("File", "~"), true);
    }

    private void listing(Pages.Builder b) {
        persistent(b);
        fileList(b);
        b.set("#PreviewMessage.Visible", false);
    }

    private void loadButton(Pages.Builder b) {
        b.set("#LoadButton.Visible", previewed != null);
        b.bind(Pages.Trigger.ACTIVATING, "#LoadButton", Map.of("Browse", "true"), true);
    }

    private void fileList(Pages.Builder b) {
        b.clear("#FileList");
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        Map<String, Path> rows = new LinkedHashMap<>();
        for (Path p : library.paths()) {
            String file = p.getFileName().toString();
            String name = file.endsWith(SUFFIX) ? file.substring(0, file.length() - SUFFIX.length()) : file;
            if (q.isEmpty() || name.toLowerCase(Locale.ROOT).contains(q)) {
                rows.put(name, p);
            }
        }
        shown = rows;
        if (rows.isEmpty()) {
            b.appendTo("#FileList", ROW);
            b.set("#FileList[0].Text", q.isEmpty()
                    ? "No prefabs yet - save a selection from the Blueprint panel"
                    : "Nothing matches");
            return;
        }
        int i = 0;
        for (String name : rows.keySet()) {
            String row = "#FileList[" + i + "]";
            b.appendTo("#FileList", ROW);
            b.set(row + ".Text", name);
            b.setRef(row + ".Style", ROW, name.equals(previewed) ? "ActiveLabelStyle" : "SelectedLabelStyle");
            Map<String, String> load = new LinkedHashMap<>();
            load.put("Browse", "true");
            load.put("Preview", name);
            b.bind(Pages.Trigger.ACTIVATING, row, Map.of("Preview", name), false);
            b.bind(Pages.Trigger.DOUBLE_CLICKING, row, load, true);
            i++;
        }
    }
}
