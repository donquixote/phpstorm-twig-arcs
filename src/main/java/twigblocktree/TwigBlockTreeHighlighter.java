package twigblocktree;

import com.intellij.lang.ASTNode;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.LogicalPosition;
import com.intellij.openapi.editor.colors.ColorKey;
import com.intellij.openapi.editor.colors.TextAttributesKey;
import com.intellij.openapi.editor.ex.EditorEx;
import com.intellij.openapi.editor.ex.EditorGutterComponentEx;
import com.intellij.openapi.editor.ex.util.EditorUtil;
import com.intellij.openapi.editor.markup.CustomHighlighterRenderer;
import com.intellij.openapi.editor.markup.HighlighterLayer;
import com.intellij.openapi.editor.markup.HighlighterTargetArea;
import com.intellij.openapi.editor.markup.LineMarkerRendererEx;
import com.intellij.openapi.editor.markup.MarkupModel;
import com.intellij.openapi.editor.markup.RangeHighlighter;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.tree.IElementType;
import com.intellij.util.concurrency.AppExecutorUtil;
import com.intellij.util.ui.JBUI;
import com.jetbrains.twig.TwigLanguage;
import com.jetbrains.twig.TwigTokenTypes;
import com.jetbrains.twig.elements.TwigElementTypes;
import com.jetbrains.twig.elements.TwigTag;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.geom.Arc2D;
import java.awt.geom.Path2D;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Shows the Twig block structure as dotted curves left of the tags, one curve per branch:
 * <pre>
 *  ╭ {% if a %}
 *  │   ...
 *  ╰╮{% else %}     (an inverted "3" for if/else)
 *  ╭╯  ...
 *  ╰ {% endif %}
 * </pre>
 * A branch curve is a half circle if it fits between the tag and the left limit, otherwise a vertical line with a
 * quarter circle at each end. The available space is the indentation plus the gutter up to its free area left of the
 * icons; nested curves stay a step right of the curve of the branch they are in.
 *
 * <p>Blocks without the caret: blue. Blocks with the caret: the caret's branch green (thicker dots), the other
 * branches red, their content greyed out. Matching tags are highlighted when the caret is on a tag.
 * {@code {% %}}, {@code {{ }}} and {@code {# #}} get configurable backgrounds.
 *
 * <p>Painting: the curves are computed in editor content coordinates. A {@link CustomHighlighterRenderer} paints them
 * in the text area; a {@link LineMarkerRendererEx} at {@code Position.CUSTOM} paints the same paths shifted by the gutter
 * width, so negative x lands in the gutter (whose right edge touches the text area's x = 0).
 *
 * <p>Twig PSI shape (from the Twig plugin's {@code TwigBlockStatements}):
 * <pre>
 * IF_STATEMENT
 *   IF_TAG, content,
 *   ELSEIF_STATEMENT (ELSEIF_TAG, content),
 *   ELSE_STATEMENT (ELSE_TAG, content),
 *   ENDIF_TAG
 * </pre>
 * {@code for ... else} is built the same way. Other statements: opening tag, content, end tag.
 * Content statements like {@code {% include %}} can be direct children of a block too.
 */
final class TwigBlockTreeHighlighter {

    private static final Key<State> STATE_KEY = Key.create("twigblocktree.state");

    private static final int LAYER = HighlighterLayer.SELECTION - 1;
    /** Greying of excluded branches must beat syntax colors. */
    private static final int EXCLUDED_TEXT_LAYER = HighlighterLayer.ADDITIONAL_SYNTAX;
    /**
     * Backgrounds of Twig constructs. Must be at least SYNTAX: highlighters below that layer rank under the syntax
     * highlighter's own attributes (IterationState), so a background from the Twig/template color settings would win.
     */
    private static final int BACKGROUND_LAYER = HighlighterLayer.SYNTAX;

    /* Geometry in px (unscaled). */
    /** Space between the gutter's free area start and the outermost curves. */
    private static final int PAD_LEFT = 1;
    /** Minimum distance between a curve and the curves nested in it. */
    private static final int NEST_STEP = 3;
    /** Space between the curve/connector end and the tag. */
    private static final int TAG_GAP = 2;
    private static final float DOT = 1.5f;
    private static final float DOT_ACTIVE = 2f;
    private static final float DOT_GAP = 3f;

    /**
     * A Twig block. {@code tags}: full ranges of the opening tag, else/elseif tags and end tag; {@code tagLines}:
     * their lines. {@code parent}: index of the enclosing block, or -1.
     */
    private record Block(TextRange range, int parent, int depth, List<TextRange> tags, int[] tagLines) {
        int branchCount() {
            return tags.size() - 1;
        }

        boolean isSingleLine() {
            return tagLines[0] == tagLines[tagLines.length - 1];
        }

        /** Branch holding {@code offset}: the one started by the last tag at or before it. */
        int branchAt(int offset) {
            int branch = 0;
            for (int i = 0; i < tags.size(); i++) {
                if (tags.get(i).getStartOffset() <= offset) {
                    branch = i;
                }
            }
            return Math.min(branch, branchCount() - 1);
        }

        /** Content of a branch: from the end of the tag that starts it to the start of the next tag. */
        TextRange branchContent(int branch) {
            int start = tags.get(branch).getEndOffset();
            int end = tags.get(branch + 1).getStartOffset();
            return new TextRange(start, Math.max(start, end));
        }
    }

    /**
     * {@code blocks} in pre-order: a parent always comes before its children.
     * {@code statements}, {@code prints}, {@code comments}: ranges of all {% %}, {{ }} and {# #} in the file.
     */
    private record Model(long stamp, List<Block> blocks, Map<TextRange, Block> byRange, int maxDepth,
                         List<TextRange> statements, List<TextRange> prints, List<TextRange> comments) {
    }

    /** x positions (content coordinates) where connectors end, per block and tag. Depends on the font. */
    private record Layout(Model model, int spaceWidth, int textStart, float[][] tagX) {
    }

    /** {@code activeBranches}: for each block enclosing the caret, the branch holding it. */
    private record Result(Model model, Map<Block, Integer> activeBranches, @Nullable Block matched) {
    }

    private enum Style {
        DEFAULT(TwigBlockTreeColors.DEFAULT, DOT),
        ACTIVE(TwigBlockTreeColors.ACTIVE, DOT_ACTIVE),
        EXCLUDED(TwigBlockTreeColors.OPPOSITE, DOT);

        final ColorKey key;
        final float dot;

        Style(ColorKey key, float dot) {
            this.key = key;
            this.dot = dot;
        }
    }

    /** One branch curve, connectors included, in content coordinates. */
    private record Curve(Path2D path, Style style, float top, float bottom) {
    }

    /** Per-editor state. */
    private static final class State {
        /** Last model built (background cache, reused while the document is unchanged). */
        volatile @Nullable Model cache;
        /** Model being painted (UI thread only). */
        @Nullable Model applied;
        @Nullable Layout layout;
        volatile Map<Block, Integer> activeBranches = Map.of();
        /** x of the gutter's left free painters area, in gutter coordinates; reported by {@link AreaProbe}. */
        int leftAreaX = -1;

        @Nullable RangeHighlighter textPainter;
        @Nullable RangeHighlighter gutterPainter;
        @Nullable RangeHighlighter areaProbe;
        final List<RangeHighlighter> backgrounds = new ArrayList<>();
        /** Highlighters that depend on the caret: matched tags, greyed branches. */
        final List<RangeHighlighter> caretHighlighters = new ArrayList<>();
        @Nullable Disposable widthReservation;
        int reservedWidth = -1;
    }

    private TwigBlockTreeHighlighter() {
    }

    static void schedule(@NotNull Editor editor, @NotNull Disposable parent) {
        Project project = editor.getProject();
        if (project == null || editor.isDisposed()) {
            return;
        }
        State state = editor.getUserData(STATE_KEY);
        if (state == null) {
            state = new State();
            editor.putUserData(STATE_KEY, state);
        }
        State s = state;
        Document document = editor.getDocument();
        int offset = editor.getCaretModel().getOffset();

        ReadAction.nonBlocking(() -> compute(project, document, offset, s))
                .withDocumentsCommitted(project)
                .expireWith(parent)
                .coalesceBy(editor, STATE_KEY)
                .finishOnUiThread(ModalityState.stateForComponent(editor.getComponent()), result -> apply(editor, s, result, parent))
                .submit(AppExecutorUtil.getAppExecutorService());
    }

    // ---------------------------------------------------------------- compute (read action, background)

    private static @Nullable Result compute(Project project, Document document, int caretOffset, State state) {
        PsiFile file = PsiDocumentManager.getInstance(project).getPsiFile(document);
        if (file == null) {
            return null;
        }
        PsiFile twig = file.getViewProvider().getPsi(TwigLanguage.INSTANCE);
        if (twig == null) {
            return null;
        }

        long stamp = document.getModificationStamp();
        Model model = state.cache;
        if (model == null || model.stamp() != stamp) {
            model = buildModel(twig, document, stamp);
            state.cache = model;
        }

        int offset = Math.min(caretOffset, document.getTextLength());
        PsiElement leaf = twig.findElementAt(offset);
        PsiElement before = offset > 0 ? twig.findElementAt(offset - 1) : null;

        // Tag under the caret; also accept the caret sitting directly after "%}".
        PsiElement caretTag = findEnclosingTag(leaf);
        if (caretTag == null) {
            caretTag = findEnclosingTag(before);
        }

        // Every block whose range contains the caret, including caret positions in the HTML between its tags.
        Map<Block, Integer> active = new HashMap<>();
        for (Block block : model.blocks()) {
            TextRange r = block.range();
            if (r.getStartOffset() <= offset && offset <= r.getEndOffset()) {
                active.put(block, block.branchAt(offset));
            }
        }

        Block matched = null;
        if (caretTag != null) {
            PsiElement owner = caretTag.getParent();
            if (owner != null && isBranchStatement(owner)) {
                owner = owner.getParent(); // {% else %} -> ELSE_STATEMENT -> IF_STATEMENT
            }
            Block block = owner == null ? null : model.byRange().get(owner.getTextRange());
            if (block != null && block.tags().contains(caretTag.getTextRange())) {
                matched = block; // only when the caret is on one of the block's own tags, not e.g. an {% include %}
            }
        }
        return new Result(model, active, matched);
    }

    private static Model buildModel(PsiFile twig, Document document, long stamp) {
        List<Block> blocks = new ArrayList<>();
        List<TextRange> statements = new ArrayList<>();
        List<TextRange> prints = new ArrayList<>();
        List<TextRange> comments = new ArrayList<>();
        walk(twig, -1, 0, document, blocks, statements, prints, comments);
        Map<TextRange, Block> byRange = new HashMap<>();
        int maxDepth = 0;
        for (Block b : blocks) {
            byRange.put(b.range(), b);
            maxDepth = Math.max(maxDepth, b.depth());
        }
        return new Model(stamp, blocks, byRange, maxDepth, statements, prints, comments);
    }

    private static void walk(PsiElement parent, int parentIndex, int depth, Document doc, List<Block> blocks,
                             List<TextRange> statements, List<TextRange> prints, List<TextRange> comments) {
        for (PsiElement c = parent.getFirstChild(); c != null; c = c.getNextSibling()) {
            if (c.getFirstChild() == null) {
                continue; // leaf
            }
            IElementType type = c.getNode().getElementType();
            if (type == TwigElementTypes.PRINT_BLOCK) {
                prints.add(c.getTextRange());
            } else if (type == TwigElementTypes.COMMENT_BLOCK) {
                comments.add(c.getTextRange());
            } else if (isTag(c)) {
                statements.add(c.getTextRange());
            }
            Block block = toBlock(c, parentIndex, depth, doc);
            if (block != null) {
                blocks.add(block);
                walk(c, blocks.size() - 1, depth + 1, doc, blocks, statements, prints, comments);
            } else {
                walk(c, parentIndex, depth, doc, blocks, statements, prints, comments);
            }
        }
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

    /** ELSE_STATEMENT / ELSEIF_STATEMENT: an else/elseif tag plus the content of that branch. */
    private static boolean isBranchStatement(@NotNull PsiElement e) {
        IElementType type = e.getNode() == null ? null : e.getNode().getElementType();
        return type == TwigElementTypes.ELSE_STATEMENT || type == TwigElementTypes.ELSEIF_STATEMENT;
    }

    /** Structural end tag, e.g. {% endif %}. Content tags like {% include %} are not. */
    private static boolean isEndTag(@NotNull PsiElement e) {
        return isTag(e)
                && e.getNode().getElementType() instanceof TwigTag tag
                && tag.isStructural()
                && tag.getTagName().startsWith("end");
    }

    /**
     * A block is an element whose first child is a tag (the opener) and which has at least one more block tag:
     * an else/elseif (first child of an ELSE_STATEMENT / ELSEIF_STATEMENT child) or a structural end tag.
     * Single-tag statements like {% set x = 1 %} are not blocks.
     */
    private static @Nullable Block toBlock(@NotNull PsiElement e, int parentIndex, int depth, Document doc) {
        if (e instanceof PsiFile || isTag(e) || isBranchStatement(e)) {
            return null;
        }
        PsiElement opener = e.getFirstChild();
        if (opener == null || !isTag(opener)) {
            return null;
        }
        List<TextRange> tags = new ArrayList<>();
        tags.add(opener.getTextRange());
        for (PsiElement child = opener.getNextSibling(); child != null; child = child.getNextSibling()) {
            if (isBranchStatement(child) && child.getFirstChild() != null && isTag(child.getFirstChild())) {
                tags.add(child.getFirstChild().getTextRange());
            } else if (isEndTag(child)) {
                tags.add(child.getTextRange());
            }
        }
        if (tags.size() < 2) {
            return null;
        }
        int length = doc.getTextLength();
        int[] tagLines = new int[tags.size()];
        for (int i = 0; i < tagLines.length; i++) {
            tagLines[i] = doc.getLineNumber(Math.min(tags.get(i).getStartOffset(), length));
        }
        return new Block(e.getTextRange(), parentIndex, depth, tags, tagLines);
    }

    // ---------------------------------------------------------------- apply (UI thread)

    private static void apply(@NotNull Editor editor, @NotNull State state, @Nullable Result result,
                              @NotNull Disposable parent) {
        if (editor.isDisposed()) {
            return;
        }
        disposeAll(state.caretHighlighters);
        if (result == null) {
            disposeAll(state.backgrounds);
            state.applied = null;
            state.activeBranches = Map.of();
            reserveWidth(editor, state, parent, 0);
            repaint(editor);
            return;
        }
        int length = editor.getDocument().getTextLength();
        MarkupModel markup = editor.getMarkupModel();
        ensurePainters(editor, state, markup);

        if (state.applied != result.model()) {
            Model model = result.model();
            disposeAll(state.backgrounds);
            addAll(markup, state.backgrounds, model.statements(), TwigBlockTreeColors.STATEMENT_BACKGROUND, BACKGROUND_LAYER, length);
            addAll(markup, state.backgrounds, model.prints(), TwigBlockTreeColors.PRINT_BACKGROUND, BACKGROUND_LAYER, length);
            addAll(markup, state.backgrounds, model.comments(), TwigBlockTreeColors.COMMENT_BACKGROUND, BACKGROUND_LAYER, length);
            state.applied = model;
            reserveWidth(editor, state, parent, model.blocks().isEmpty() ? 0 : PAD_LEFT + model.maxDepth() * NEST_STEP + 2);
        }
        state.activeBranches = result.activeBranches();

        Block matched = result.matched();
        if (matched != null) {
            addAll(markup, state.caretHighlighters, matched.tags(), TwigBlockTreeColors.MATCHED_TAG, LAYER, length);
        }
        for (Map.Entry<Block, Integer> e : result.activeBranches().entrySet()) {
            Block block = e.getKey();
            List<TextRange> excluded = new ArrayList<>();
            for (int branch = 0; branch < block.branchCount(); branch++) {
                if (branch != e.getValue()) {
                    excluded.add(block.branchContent(branch));
                }
            }
            addAll(markup, state.caretHighlighters, excluded, TwigBlockTreeColors.EXCLUDED_TEXT, EXCLUDED_TEXT_LAYER, length);
        }

        repaint(editor);
    }

    private static void addAll(MarkupModel markup, List<RangeHighlighter> out, List<TextRange> ranges,
                               TextAttributesKey key, int layer, int length) {
        for (TextRange range : ranges) {
            TextRange r = clamp(range, length);
            if (!r.isEmpty()) {
                out.add(markup.addRangeHighlighter(key, r.getStartOffset(), r.getEndOffset(), layer, HighlighterTargetArea.EXACT_RANGE));
            }
        }
    }

    /** Whole-document highlighters that paint the curves (text area, gutter) and locate the gutter's free area. */
    private static void ensurePainters(Editor editor, State state, MarkupModel markup) {
        if (state.textPainter == null || !state.textPainter.isValid()) {
            RangeHighlighter h = wholeDocument(editor, markup, HighlighterTargetArea.EXACT_RANGE);
            h.setCustomRenderer(new TextPainter(state));
            state.textPainter = h;
        }
        if (state.gutterPainter == null || !state.gutterPainter.isValid()) {
            RangeHighlighter h = wholeDocument(editor, markup, HighlighterTargetArea.LINES_IN_RANGE);
            h.setLineMarkerRenderer(new GutterPainter(state));
            state.gutterPainter = h;
        }
        if (state.areaProbe == null || !state.areaProbe.isValid()) {
            RangeHighlighter h = wholeDocument(editor, markup, HighlighterTargetArea.LINES_IN_RANGE);
            h.setLineMarkerRenderer(new AreaProbe(state));
            state.areaProbe = h;
        }
    }

    private static RangeHighlighter wholeDocument(Editor editor, MarkupModel markup, HighlighterTargetArea area) {
        RangeHighlighter h = markup.addRangeHighlighter(null, 0, editor.getDocument().getTextLength(), LAYER, area);
        h.setGreedyToLeft(true);
        h.setGreedyToRight(true);
        return h;
    }

    /** Widens the gutter's left free painters area, so nested curves have room before the icon column. */
    private static void reserveWidth(Editor editor, State state, Disposable parent, int width) {
        if (width == state.reservedWidth || !(editor instanceof EditorEx ex)) {
            return;
        }
        if (state.widthReservation != null) {
            Disposer.dispose(state.widthReservation);
            state.widthReservation = null;
        }
        state.reservedWidth = width;
        if (width > 0) {
            Disposable reservation = Disposer.newDisposable(parent, "TwigBlockTree gutter width");
            ex.getGutterComponentEx().reserveLeftFreePaintersAreaWidth(reservation, width);
            state.widthReservation = reservation;
        }
    }

    private static void repaint(Editor editor) {
        editor.getContentComponent().repaint();
        if (editor instanceof EditorEx ex) {
            ex.getGutterComponentEx().repaint();
        }
    }

    private static void disposeAll(List<RangeHighlighter> highlighters) {
        for (RangeHighlighter h : highlighters) {
            if (h.isValid()) {
                h.dispose();
            }
        }
        highlighters.clear();
    }

    private static TextRange clamp(TextRange r, int length) {
        int start = Math.min(r.getStartOffset(), length);
        int end = Math.min(r.getEndOffset(), length);
        return new TextRange(start, Math.max(start, end));
    }

    // ---------------------------------------------------------------- geometry (UI thread, at paint time)

    private static @Nullable Layout layout(Editor editor, State state) {
        Model model = state.applied;
        if (model == null) {
            return null;
        }
        int space = EditorUtil.getPlainSpaceWidth(editor);
        Layout layout = state.layout;
        if (layout != null && layout.model() == model && layout.spaceWidth() == space) {
            return layout;
        }
        Document doc = editor.getDocument();
        int textStart = editor.logicalPositionToXY(new LogicalPosition(0, 0)).x;
        float gap = JBUI.scale((float) TAG_GAP);
        List<Block> blocks = model.blocks();
        float[][] tagX = new float[blocks.size()][];
        for (int i = 0; i < blocks.size(); i++) {
            int[] lines = blocks.get(i).tagLines();
            tagX[i] = new float[lines.length];
            for (int k = 0; k < lines.length; k++) {
                int column = lines[k] < doc.getLineCount() ? Math.max(0, indentColumn(editor, doc, lines[k])) : 0;
                tagX[i][k] = textStart + column * space - gap;
            }
        }
        layout = new Layout(model, space, textStart, tagX);
        state.layout = layout;
        return layout;
    }

    /** Logical column of the first non-whitespace character on the line, or -1 for a blank line. */
    private static int indentColumn(Editor editor, Document doc, int line) {
        CharSequence text = doc.getCharsSequence();
        int end = doc.getLineEndOffset(line);
        for (int i = doc.getLineStartOffset(line); i < end; i++) {
            if (!Character.isWhitespace(text.charAt(i))) {
                return editor.offsetToLogicalPosition(i).column;
            }
        }
        return -1;
    }

    /** All branch curves, in content coordinates (negative x = in the gutter). */
    private static List<Curve> curves(Editor editor, State state) {
        Layout layout = layout(editor, state);
        if (layout == null || !(editor instanceof EditorEx ex)) {
            return List.of();
        }
        EditorGutterComponentEx gutter = ex.getGutterComponentEx();
        int gutterWidth = gutter.getWidth();
        int areaX = state.leftAreaX >= 0 ? state.leftAreaX : gutter.getIconAreaOffset() - JBUI.scale(Math.max(0, state.reservedWidth));
        float rootLimit = areaX - gutterWidth + JBUI.scale((float) PAD_LEFT);
        float nestStep = JBUI.scale((float) NEST_STEP);

        Document doc = editor.getDocument();
        int lineCount = doc.getLineCount();
        int lineHeight = editor.getLineHeight();
        Map<Block, Integer> active = state.activeBranches;
        List<Block> blocks = layout.model().blocks();
        float[][] lefts = new float[blocks.size()][];
        List<Curve> curves = new ArrayList<>();

        for (int i = 0; i < blocks.size(); i++) {
            Block b = blocks.get(i);
            int[] lines = b.tagLines();
            if (lines[lines.length - 1] >= lineCount) {
                lefts[i] = new float[]{rootLimit};
                continue;
            }
            float limit = rootLimit;
            if (b.parent() >= 0 && lefts[b.parent()] != null) {
                Block p = blocks.get(b.parent());
                float[] pl = lefts[b.parent()];
                limit = pl[Math.min(p.branchAt(b.range().getStartOffset()), pl.length - 1)] + nestStep;
            }
            float[] tx = layout.tagX()[i];
            float[] ys = new float[lines.length];
            for (int k = 0; k < lines.length; k++) {
                ys[k] = editor.logicalPositionToXY(new LogicalPosition(lines[k], 0)).y + lineHeight / 2f;
            }
            Integer activeBranch = active.get(b);

            if (b.isSingleLine()) {
                float q = lineHeight / 4f;
                float x = min(tx);
                Path2D path = new Path2D.Float();
                path.moveTo(x, ys[0] - q);
                lefts[i] = new float[]{appendCurve(path, x, ys[0] - q, ys[0] + q, limit)};
                curves.add(new Curve(path, style(activeBranch, activeBranch != null ? activeBranch : 0), ys[0] - q, ys[0] + q));
                continue;
            }

            lefts[i] = new float[b.branchCount()];
            for (int k = 0; k < b.branchCount(); k++) {
                float x = Math.min(tx[k], tx[k + 1]);
                Path2D path = new Path2D.Float();
                path.moveTo(tx[k], ys[k]);
                path.lineTo(x, ys[k]);
                lefts[i][k] = appendCurve(path, x, ys[k], ys[k + 1], limit);
                path.lineTo(tx[k + 1], ys[k + 1]);
                curves.add(new Curve(path, style(activeBranch, k), ys[k], ys[k + 1]));
            }
        }
        return curves;
    }

    /**
     * Appends a curve from (x, y0) down to (x, y1), bulging left: a half circle if it stays right of {@code limit},
     * otherwise a vertical line at the limit with a quarter circle at each end. Returns the leftmost x.
     */
    private static float appendCurve(Path2D path, float x, float y0, float y1, float limit) {
        float r = (y1 - y0) / 2;
        if (x - r >= limit) {
            path.append(new Arc2D.Float(x - r, y0, 2 * r, 2 * r, 90, 180, Arc2D.OPEN), true);
            return x - r;
        }
        float radius = Math.max(0, Math.min(r, x - limit));
        if (radius < 0.5f) {
            path.lineTo(x, y1);
            return x;
        }
        path.append(new Arc2D.Float(x - radius, y0, 2 * radius, 2 * radius, 90, 90, Arc2D.OPEN), true);
        path.lineTo(x - radius, y1 - radius);
        path.append(new Arc2D.Float(x - radius, y1 - 2 * radius, 2 * radius, 2 * radius, 180, 90, Arc2D.OPEN), true);
        return x - radius;
    }

    private static float min(float[] values) {
        float m = Float.MAX_VALUE;
        for (float v : values) {
            m = Math.min(m, v);
        }
        return m;
    }

    private static Style style(@Nullable Integer activeBranch, int branch) {
        return activeBranch == null ? Style.DEFAULT : activeBranch == branch ? Style.ACTIVE : Style.EXCLUDED;
    }

    // ---------------------------------------------------------------- painting

    /** Draws the curves in round dots; {@code dx} shifts content coordinates into the target component. */
    private static void paintCurves(Editor editor, State state, Graphics g, int dx) {
        Rectangle clip = g.getClipBounds();
        List<Curve> curves = curves(editor, state);
        if (curves.isEmpty()) {
            return;
        }
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            g2.translate(dx, 0);
            Map<Style, BasicStroke> strokes = new HashMap<>();
            for (Curve curve : curves) {
                if (clip != null && (curve.bottom() < clip.y - 4 || curve.top() > clip.y + clip.height + 4)) {
                    continue;
                }
                Color color = editor.getColorsScheme().getColor(curve.style().key);
                if (color == null) {
                    continue;
                }
                g2.setColor(color);
                g2.setStroke(strokes.computeIfAbsent(curve.style(), TwigBlockTreeHighlighter::dotStroke));
                g2.draw(curve.path());
            }
        } finally {
            g2.dispose();
        }
    }

    /** Round dots: (almost) zero-length dashes with round caps, one dot diameter wide. */
    private static BasicStroke dotStroke(Style style) {
        float dot = JBUI.scale(style.dot);
        float period = dot + JBUI.scale(DOT_GAP);
        return new BasicStroke(dot, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 10f,
                new float[]{0.01f, period - 0.01f}, 0f);
    }

    /** Paints the curves in the text area. */
    private record TextPainter(State state) implements CustomHighlighterRenderer {
        @Override
        public void paint(@NotNull Editor editor, @NotNull RangeHighlighter highlighter, @NotNull Graphics g) {
            paintCurves(editor, state, g, 0);
        }
    }

    /** Paints the parts of the curves that lie left of the text area, in the gutter. */
    private record GutterPainter(State state) implements LineMarkerRendererEx {
        @Override
        public @NotNull Position getPosition() {
            return Position.CUSTOM;
        }

        @Override
        public void paint(@NotNull Editor editor, @NotNull Graphics g, @NotNull Rectangle r) {
            paintCurves(editor, state, g, r.x + r.width);
        }
    }

    /**
     * Paints nothing; makes the gutter show its left free painters area and reports where that area starts,
     * which is the left limit for the curves.
     */
    private record AreaProbe(State state) implements LineMarkerRendererEx {
        @Override
        public @NotNull Position getPosition() {
            return Position.LEFT;
        }

        @Override
        public void paint(@NotNull Editor editor, @NotNull Graphics g, @NotNull Rectangle r) {
            if (state.leftAreaX != r.x) {
                state.leftAreaX = r.x;
                ApplicationManager.getApplication().invokeLater(() -> {
                    if (!editor.isDisposed()) {
                        repaint(editor);
                    }
                });
            }
        }
    }
}
