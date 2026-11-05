package meridian.blueprint;

import java.util.Map;
import meridian.core.api.Chat;
import meridian.core.api.ClientAssets;
import meridian.core.api.Pages;
import org.slf4j.Logger;

/**
 * The blueprint tool's F: the settings panel's layer controls, in game. Moving and turning the
 * projection is the paste tool's job now, so those stay in the settings panel only.
 *
 * <p>The window is a document of ours ({@code ui/MeridianBlueprintPanel.ui}), handed to the client
 * while it loads - the only way to give it a document without a visible asset rebuild. A client
 * that connected before the module was on has not got it, and opening a page on a document it
 * cannot find would cost the player the connection, so then we only say to reconnect.
 */
final class BlueprintPanelWindow implements Pages.Listener {

    /** Where the client keeps it - next to the game's own pages, so {@code ../Common.ui} resolves. */
    static final String ASSET = "UI/Custom/Pages/MeridianBlueprintPanel.ui";
    private static final String DOC = "Pages/MeridianBlueprintPanel.ui";
    private static final String ROOT = "#BlueprintPage ";

    private static final String[] BUTTONS = {
        "PrevLayer", "NextLayer", "WindowLess", "WindowMore", "WindowAll", "DoneButton",
    };

    private final Logger log;
    private final Pages pages;
    private final ClientAssets assets;
    private final Chat chat;
    private final PreviewController preview;

    BlueprintPanelWindow(Logger log, Pages pages, ClientAssets assets, Chat chat, PreviewController preview) {
        this.log = log;
        this.pages = pages;
        this.assets = assets;
        this.chat = chat;
        this.preview = preview;
    }

    void open() {
        if (assets != null && !assets.deliveredAtConnect(ASSET)) {
            log.info("blueprint panel not on this client yet - it arrives with the next connect");
            if (chat != null) {
                chat.send("Blueprint panel: reconnect once to load it.", "#E8A93B");
            }
            return;
        }
        pages.open("blueprint.panel", true, b -> {
            b.append(DOC);
            for (String id : BUTTONS) {
                b.bind(Pages.Trigger.ACTIVATING, ROOT + "#" + id, Map.of("Action", id), true);
            }
            status(b);
        }, this);
    }

    @Override
    public void data(Pages.Page page, Map<String, String> data) {
        String action = data.get("Action");
        if (action == null) {
            return;
        }
        switch (action) {
            case "PrevLayer" -> preview.stepLayer(-1);
            case "NextLayer" -> preview.stepLayer(+1);
            case "WindowLess" -> preview.setWindow(Math.max(0, preview.windowSize() - 1));
            case "WindowMore" -> preview.setWindow(Math.min(256, preview.windowSize() + 1));
            case "WindowAll" -> preview.setWindow(0);
            case "DoneButton" -> {
                page.close();
                return;
            }
            default -> {
                return;
            }
        }
        page.update(this::status);
    }

    /** The labels: where the layers stand and how many show. */
    private void status(Pages.Builder b) {
        int win = preview.windowSize();
        b.set(ROOT + "#LayerStatus.Text", preview.layerLine());
        b.set(ROOT + "#WindowValue.Text", win <= 0 ? "all" : Integer.toString(win));
    }
}
