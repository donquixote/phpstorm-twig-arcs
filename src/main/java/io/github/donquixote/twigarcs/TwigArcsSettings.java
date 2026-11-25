package io.github.donquixote.twigarcs;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import org.jetbrains.annotations.NotNull;

/** Application-wide settings (Settings | Editor | Twig Arcs). Colors live in the color scheme instead. */
@Service(Service.Level.APP)
@State(name = "TwigArcsSettings", storages = @Storage("twigarcs.xml"))
public final class TwigArcsSettings implements PersistentStateComponent<TwigArcsSettings.Data> {

    public static final class Data {
        /**
         * Widen the gutter's left free area to fit nested arcs. Uses an internal platform API
         * ({@code EditorGutterComponentEx.reserveLeftFreePaintersAreaWidth}); without it the area keeps its default width.
         */
        public boolean widenGutter = true;
    }

    private Data data = new Data();

    public static TwigArcsSettings getInstance() {
        return ApplicationManager.getApplication().getService(TwigArcsSettings.class);
    }

    public boolean isWidenGutter() {
        return data.widenGutter;
    }

    public void setWidenGutter(boolean value) {
        data.widenGutter = value;
    }

    @Override
    public @NotNull Data getState() {
        return data;
    }

    @Override
    public void loadState(@NotNull Data state) {
        data = state;
    }
}
