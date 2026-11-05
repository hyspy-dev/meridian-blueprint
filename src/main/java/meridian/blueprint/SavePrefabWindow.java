package meridian.blueprint;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import meridian.api.module.Scheduler;
import meridian.core.api.Chat;
import meridian.core.api.ClientAssets;
import meridian.core.api.Pages;
import org.slf4j.Logger;

/**
 * Use on the selection tool: a "save prefab" window - a name and Save - writing the selection into
 * the module's prefab folder, where the paste tool's list finds it; and "Copy to buffer", which
 * puts the selection straight into the paste tool without saving anything.
 *
 * <p>The window is a document of ours modelled on the game's, given to the client while it loads.
 * A client that connected before has only the game's own page, used then without the copy button.
 *
 * <p>{@code Pages/PrefabSavePage.ui} is the server's page for {@code /prefab save}; its asset-pack,
 * entity and support options mean nothing here and are hidden. Its overwrite question is kept.
 */
final class SavePrefabWindow implements Pages.Listener {

    private static final String DOC = "Pages/PrefabSavePage.ui";
    /** Ours: the same window with "Copy to buffer" - given to the client while it loads. */
    static final String OWN_ASSET = "UI/Custom/Pages/MeridianBlueprintSave.ui";
    private static final String OWN_DOC = "Pages/MeridianBlueprintSave.ui";
    private static final String MAIN = "#MainPage ";
    private static final String CONFIRM = "#OverwriteConfirmPage ";
    private static final String[] HIDDEN = {
        "#TargetPack", "#Entities", "#Empty", "#FromClipboard", "#UsePlayerAnchor",
        "#RemoveSupport", "#SetupSupport", "#Compressed",
    };

    private final Logger log;
    private final Pages pages;
    private final Chat chat;
    private final PreviewController preview;
    private final PrefabLibrary library;
    private final Path dir;
    private final Scheduler scheduler;
    private final ClientAssets assets;

    /** The name waiting on the overwrite question. */
    private volatile String pending;

    SavePrefabWindow(Logger log, Pages pages, Chat chat, PreviewController preview, PrefabLibrary library,
            Path dir, Scheduler scheduler, ClientAssets assets) {
        this.assets = assets;
        this.log = log;
        this.pages = pages;
        this.chat = chat;
        this.preview = preview;
        this.library = library;
        this.dir = dir;
        this.scheduler = scheduler;
    }

    void open() {
        if (!preview.hasSelection()) {
            say("Select an area with the selection tool first.");
            return;
        }
        pending = null;
        if (assets != null && assets.deliveredAtConnect(OWN_ASSET)) {
            pages.open("blueprint.save", true, b -> {
                b.append(OWN_DOC);
                b.set(MAIN + "#SizeText.Text", "Selection: " + preview.selectionStatus());
                bindCommon(b);
                b.bind(Pages.Trigger.ACTIVATING, MAIN + "#CopyButton", Map.of("Action", "Copy"), true);
            }, this);
            return;
        }
        // A client that connected before the module was on has only the game's own page.
        pages.open("blueprint.save", true, b -> {
            b.append(DOC);
            // As the server sets it up - the pack browser and its pages stay out of sight.
            b.set("#PackBrowserPage.Visible", false);
            b.set("#CreatePackPage.Visible", false);
            for (String id : HIDDEN) {
                b.set(MAIN + id + ".Visible", false);
            }
            b.set(MAIN + "#NameRequiredText.Visible", false);
            b.set("#OverwriteConfirmPage.Visible", false);
            bindCommon(b);
        }, this);
    }

    /** Save, Cancel and the overwrite question - the same ids in both documents. */
    private void bindCommon(Pages.Builder b) {
        b.bind(Pages.Trigger.ACTIVATING, MAIN + "#SaveButton",
                Map.of("Action", "Save", "@Name", MAIN + "#NameInput.Value"), true);
        b.bind(Pages.Trigger.ACTIVATING, MAIN + "#CancelButton", Map.of("Action", "Cancel"), true);
        b.bind(Pages.Trigger.ACTIVATING, CONFIRM + "#ConfirmOverwriteButton",
                Map.of("Action", "ConfirmOverwrite"), true);
        b.bind(Pages.Trigger.ACTIVATING, CONFIRM + "#CancelOverwriteButton",
                Map.of("Action", "CancelOverwrite"), true);
    }

    @Override
    public void data(Pages.Page page, Map<String, String> data) {
        String action = data.get("Action");
        if (action == null) {
            return;
        }
        switch (action) {
            case "Cancel" -> page.close();
            case "Copy" -> {
                page.close();
                preview.copySelection();
            }
            case "Save" -> {
                String name = data.get("@Name");
                name = name == null ? "" : name.trim();
                Path target = name.isEmpty() ? null : PrefabSaver.targetOf(dir, name);
                if (target == null) {
                    page.update(b -> b.set(MAIN + "#NameRequiredText.Visible", true));
                    return;
                }
                if (Files.exists(target)) {
                    pending = name;
                    String shown = name;
                    page.update(b -> b.set("#MainPage.Visible", false)
                            .set("#OverwriteConfirmPage.Visible", true)
                            .set(CONFIRM + "#MessageText.Text",
                                    "A prefab named \"" + shown + "\" already exists. Overwrite it?"));
                    return;
                }
                save(page, name);
            }
            case "ConfirmOverwrite" -> {
                String name = pending;
                if (name != null) {
                    save(page, name);
                }
            }
            case "CancelOverwrite" -> {
                pending = null;
                page.update(b -> b.set("#OverwriteConfirmPage.Visible", false)
                        .set("#MainPage.Visible", true));
            }
            default -> { }
        }
    }

    /** Closes the window and writes the file off the network thread - a big selection takes a while. */
    private void save(Pages.Page page, String name) {
        pending = null;
        page.close();
        scheduler.schedule(() -> {
            Object result = preview.saveSelection(dir, name);
            if (result instanceof Integer blocks) {
                library.refresh();
                say("Saved \"" + name + "\" (" + blocks + " blocks).");
            } else {
                say(String.valueOf(result));
            }
        }, Duration.ZERO);
    }

    private void say(String text) {
        log.info("save prefab: {}", text);
        if (chat != null) {
            chat.send("Blueprint: " + text, "#E8A93B");
        }
    }
}
