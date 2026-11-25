package io.github.donquixote.twigarcs;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.event.CaretEvent;
import com.intellij.openapi.editor.event.CaretListener;
import com.intellij.openapi.editor.event.DocumentEvent;
import com.intellij.openapi.editor.event.DocumentListener;
import com.intellij.openapi.editor.event.EditorFactoryEvent;
import com.intellij.openapi.editor.event.EditorFactoryListener;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.vfs.VirtualFile;
import com.jetbrains.twig.TwigFileType;
import org.jetbrains.annotations.NotNull;

/**
 * Attaches caret and document listeners to every editor that shows a Twig file.
 */
public final class TwigArcsEditorListener implements EditorFactoryListener {

    private static final Key<Disposable> DISPOSABLE_KEY = Key.create("io.github.donquixote.twigarcs.disposable");

    /** The disposable of a Twig editor's listeners, or null for other editors. */
    static @org.jetbrains.annotations.Nullable Disposable disposable(@NotNull Editor editor) {
        return editor.getUserData(DISPOSABLE_KEY);
    }

    @Override
    public void editorCreated(@NotNull EditorFactoryEvent event) {
        Editor editor = event.getEditor();
        if (editor.getProject() == null || editor.isOneLineMode()) {
            return;
        }
        VirtualFile file = FileDocumentManager.getInstance().getFile(editor.getDocument());
        if (file == null || file.getFileType() != TwigFileType.INSTANCE) {
            return;
        }

        Disposable disposable = Disposer.newDisposable("TwigArcs editor listener");
        editor.putUserData(DISPOSABLE_KEY, disposable);

        editor.getCaretModel().addCaretListener(new CaretListener() {
            @Override
            public void caretPositionChanged(@NotNull CaretEvent e) {
                TwigArcsHighlighter.schedule(editor, disposable);
            }
        }, disposable);

        editor.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void documentChanged(@NotNull DocumentEvent e) {
                TwigArcsHighlighter.schedule(editor, disposable);
            }
        }, disposable);

        TwigArcsHighlighter.schedule(editor, disposable);
    }

    @Override
    public void editorReleased(@NotNull EditorFactoryEvent event) {
        Editor editor = event.getEditor();
        Disposable disposable = editor.getUserData(DISPOSABLE_KEY);
        if (disposable != null) {
            editor.putUserData(DISPOSABLE_KEY, null);
            Disposer.dispose(disposable);
        }
    }
}
