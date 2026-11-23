package twigblocktree;

import com.intellij.lang.ASTNode;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.colors.ColorKey;
import com.intellij.openapi.editor.markup.HighlighterLayer;
import com.intellij.openapi.editor.markup.HighlighterTargetArea;
import com.intellij.openapi.editor.markup.LineMarkerRenderer;
import com.intellij.openapi.editor.markup.MarkupModel;
import com.intellij.openapi.editor.markup.RangeHighlighter;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.util.concurrency.AppExecutorUtil;
import com.intellij.util.ui.JBUI;
import com.jetbrains.twig.TwigLanguage;
import com.jetbrains.twig.TwigTokenTypes;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.awt.Color;
import java.awt.Graphics;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;

/**
 * Finds the Twig blocks around the caret and paints them: a gutter bar per nesting level,
 * plus highlighting of the sibling tags when the caret is on a tag.
 *
 * <p>Twig's PSI (see the Symfony plugin for usages): a block like {@code {% if %}...{% endif %}}
 * is a statement element (IF_STATEMENT, FOR_STATEMENT, BLOCK_STATEMENT, ...) whose children
 * are tag elements (IF_TAG, ELSE_TAG, the end tag, ...) and the content in between.
 * A tag is recognised generically by its first token being "{%", so this works for
 * every block type the Twig plugin parses, without listing them.
 */
final class TwigBlockTreeHighlighter {

    private static final Key<List<RangeHighlighter>> HIGHLIGHTERS_KEY = Key.create("twigblocktree.highlighters");

    private static final int LAYER = HighlighterLayer.SELECTION - 1;

    /**
     * Horizontal position of the gutter bar, relative to the line marker area.
     * The HTML tag tree draws at x-1..x+1, so this puts the Twig bar right next to it.
     */
    private static final int BAR_X_OFFSET = 2;
    private static final int BAR_WIDTH = 2;

    /** A Twig block: its full range, and the ranges of its own tags ("{% if", "{% else", "{% endif"). */
    private record Block(TextRange range, List<TextRange> tags) {
    }

    /** {@code enclosing} is innermost first. {@code matched} is the block whose tag the caret is on. */
    private record Result(List<Block> enclosing, @Nullable Block matched) {
    }

    private static final Result EMPTY = new Result(List.of(), null);

    private TwigBlockTreeHighlighter() {
    }

    static void schedule(@NotNull Editor editor, @NotNull Disposable parent) {
        Project project = editor.getProject();
        if (project == null || editor.isDisposed()) {
            return;
        }
        Document document = editor.getDocument();
        int offset = editor.getCaretModel().getOffset();

        ReadAction.nonBlocking(() -> compute(project, document, offset))
                .withDocumentsCommitted(project)
                .expireWith(parent)
                .coalesceBy(editor, HIGHLIGHTERS_KEY)
                .finishOnUiThread(ModalityState.stateForComponent(editor.getComponent()), result -> apply(editor, result))
                .submit(AppExecutorUtil.getAppExecutorService());
    }

    // ---------------------------------------------------------------- compute (read action, background)

    private static Result compute(Project project, Document document, int caretOffset) {
        PsiFile file = PsiDocumentManager.getInstance(project).getPsiFile(document);
        if (file == null) {
            return EMPTY;
        }
        PsiFile twig = file.getViewProvider().getPsi(TwigLanguage.INSTANCE);
        if (twig == null) {
            return EMPTY;
        }
        int offset = Math.min(caretOffset, document.getTextLength());

        PsiElement leaf = twig.findElementAt(offset);
        PsiElement before = offset > 0 ? twig.findElementAt(offset - 1) : null;

        // Tag under the caret; also accept the caret sitting directly after "%}".
        PsiElement caretTag = findEnclosingTag(leaf);
        if (caretTag == null) {
            caretTag = findEnclosingTag(before);
        }

        PsiElement start = caretTag != null ? caretTag : (leaf != null ? leaf : before);
        List<Block> enclosing = new ArrayList<>();
        for (PsiElement e = start; e != null && !(e instanceof PsiFile); e = e.getParent()) {
            Block block = toBlock(e);
            if (block != null) {
                enclosing.add(block);
            }
        }

        Block matched = caretTag != null ? toBlock(caretTag.getParent()) : null;
        return new Result(enclosing, matched);
    }

    /** A tag is a composite element starting with the "{%" token. */
    private static boolean isTag(@NotNull PsiElement e) {
        ASTNode node = e.getNode();
        if (node == null) {
            return false;
        }
        ASTNode first = node.getFirstChildNode();
        return first != null && first.getElementType() == TwigTokenTypes.STATEMENT_BLOCK_START;
    }

    private static @Nullable PsiElement findEnclosingTag(@Nullable PsiElement leaf) {
        for (PsiElement e = leaf; e != null && !(e instanceof PsiFile); e = e.getParent()) {
            if (isTag(e)) {
                return e;
            }
        }
        return null;
    }

    /**
     * A block is an element whose first child is a tag and which has at least one more tag child
     * (the end tag, or else/elseif). Single-tag statements like {% set x = 1 %} are not blocks.
     */
    private static @Nullable Block toBlock(@Nullable PsiElement e) {
        if (e == null || e instanceof PsiFile || isTag(e)) {
            return null;
        }
        PsiElement first = e.getFirstChild();
        if (first == null || !isTag(first)) {
            return null;
        }
        List<TextRange> tags = new ArrayList<>();
        for (PsiElement child = first; child != null; child = child.getNextSibling()) {
            if (isTag(child)) {
                tags.add(tagHighlightRange(child));
            }
        }
        if (tags.size() < 2) {
            return null;
        }
        return new Block(e.getTextRange(), tags);
    }

    /** "{% if" rather than the whole "{% if some.long.condition %}". */
    private static TextRange tagHighlightRange(@NotNull PsiElement tag) {
        TextRange tagRange = tag.getTextRange();
        for (ASTNode child = tag.getNode().getFirstChildNode(); child != null; child = child.getTreeNext()) {
            if (child.getElementType() == TwigTokenTypes.TAG_NAME) {
                return new TextRange(tagRange.getStartOffset(), child.getTextRange().getEndOffset());
            }
        }
        return tagRange;
    }

    // ---------------------------------------------------------------- apply (UI thread)

    private static void apply(@NotNull Editor editor, @NotNull Result result) {
        clear(editor);
        if (editor.isDisposed()) {
            return;
        }
        Document doc = editor.getDocument();
        int length = doc.getTextLength();
        MarkupModel markup = editor.getMarkupModel();
        List<RangeHighlighter> added = new ArrayList<>();

        // Gutter bars: each line gets the color of the innermost block that contains it,
        // so for each block only the lines outside the next inner block are painted.
        List<Block> blocks = result.enclosing();
        int count = blocks.size();
        for (int i = 0; i < count; i++) {
            TextRange range = clamp(blocks.get(i).range(), length);
            int depth = count - 1 - i; // 0 = outermost, so a block keeps its color while you move inside it
            ColorKey color = TwigBlockTreeColors.levelKey(depth);
            int startLine = doc.getLineNumber(range.getStartOffset());
            int endLine = doc.getLineNumber(range.getEndOffset());
            if (i == 0) {
                addBar(doc, markup, added, startLine, endLine, color);
            } else {
                TextRange inner = clamp(blocks.get(i - 1).range(), length);
                int innerStart = doc.getLineNumber(inner.getStartOffset());
                int innerEnd = doc.getLineNumber(inner.getEndOffset());
                addBar(doc, markup, added, startLine, innerStart - 1, color);
                addBar(doc, markup, added, innerEnd + 1, endLine, color);
            }
        }

        // Matching tags of the block whose tag the caret is on.
        Block matched = result.matched();
        if (matched != null) {
            for (TextRange tag : matched.tags()) {
                TextRange r = clamp(tag, length);
                if (!r.isEmpty()) {
                    added.add(markup.addRangeHighlighter(TwigBlockTreeColors.MATCHED_TAG,
                            r.getStartOffset(), r.getEndOffset(), LAYER, HighlighterTargetArea.EXACT_RANGE));
                }
            }
        }

        editor.putUserData(HIGHLIGHTERS_KEY, added);
    }

    private static void addBar(Document doc, MarkupModel markup, List<RangeHighlighter> added,
                               int fromLine, int toLine, ColorKey color) {
        if (fromLine > toLine) {
            return;
        }
        RangeHighlighter h = markup.addRangeHighlighter(null,
                doc.getLineStartOffset(fromLine), doc.getLineEndOffset(toLine),
                LAYER, HighlighterTargetArea.LINES_IN_RANGE);
        h.setLineMarkerRenderer(new BarRenderer(color));
        added.add(h);
    }

    private static void clear(@NotNull Editor editor) {
        List<RangeHighlighter> old = editor.getUserData(HIGHLIGHTERS_KEY);
        if (old != null) {
            for (RangeHighlighter h : old) {
                if (h.isValid()) {
                    h.dispose();
                }
            }
            editor.putUserData(HIGHLIGHTERS_KEY, null);
        }
    }

    private static TextRange clamp(TextRange r, int length) {
        int start = Math.min(r.getStartOffset(), length);
        int end = Math.min(r.getEndOffset(), length);
        return new TextRange(start, Math.max(start, end));
    }

    /** Paints a thin vertical bar. The color is read at paint time, so color scheme changes apply at once. */
    private record BarRenderer(ColorKey key) implements LineMarkerRenderer {
        @Override
        public void paint(@NotNull Editor editor, @NotNull Graphics g, @NotNull Rectangle r) {
            Color color = editor.getColorsScheme().getColor(key);
            if (color == null) {
                return;
            }
            g.setColor(color);
            g.fillRect(r.x + JBUI.scale(BAR_X_OFFSET), r.y, JBUI.scale(BAR_WIDTH), r.height);
        }
    }
}
