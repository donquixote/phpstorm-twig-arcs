package io.github.donquixote.twigarcs;

import com.intellij.lang.ASTNode;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
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
import com.intellij.ui.scale.JBUIScale;
import com.jetbrains.twig.TwigLanguage;
import com.jetbrains.twig.TwigTokenTypes;
import com.jetbrains.twig.elements.TwigElementTypes;
import com.jetbrains.twig.elements.TwigTag;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.PathIterator;
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
final class TwigArcsHighlighter {

    private static final Key<State> STATE_KEY = Key.create("io.github.donquixote.twigarcs.state");

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
    /** Dot size and space between dots (edge to edge): blocks without the caret and other branches. */
    private static final float DOT = 1.5f;
    private static final float DOT_GAP = 2.5f;
    /** Dot size and space between dots: the caret's branch and "taken if reached" branches. */
    private static final float DOT_ACTIVE = 2.5f;
    private static final float DOT_GAP_ACTIVE = 1.5f;

    /**
     * A Twig block. {@code tags}: full ranges of the opening tag, else/elseif tags and end tag; {@code tagLines}:
     * their lines. {@code parent}: index of the enclosing block, or -1. {@code loop}: index of the nearest enclosing
     * {% for %} block, or -1. {@code conds}: per tag, the condition of an if/elseif tag, else null.
     */
    private record Block(TextRange range, int parent, int depth, int loop, Kind kind, List<TextRange> tags, int[] tagLines,
                         List<Cond> conds) {
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

    private enum Kind { IF, FOR, OTHER }

    /** Condition of an if/elseif tag: {@code base} (normalized text) negated or not; {@code range}: its text. */
    private record Cond(String base, boolean negated, TextRange range) {
    }

    /** A {% set name ... %} tag. */
    private record SetTag(int offset, String name) {
    }

    /**
     * {@code blocks} in pre-order: a parent always comes before its children.
     * {@code statements}, {@code prints}, {@code comments}: ranges of all {% %}, {{ }} and {# #} in the file.
     */
    private record Model(long stamp, List<Block> blocks, Map<TextRange, Block> byRange, int maxDepth,
                         List<TextRange> statements, List<TextRange> prints, List<TextRange> comments, List<SetTag> sets) {
    }

    /**
     * State of one branch. ACTIVE: the caret's branch, or certainly taken. INFERRED: taken if reached, decided by a
     * block with the same condition.
     */
    private enum Branch { DEFAULT, ACTIVE, EXCLUDED, INFERRED }

    /** x positions (content coordinates) where connectors end, per block and tag. Depends on the font. */
    private record Layout(Model model, int spaceWidth, int textStart, float[][] tagX) {
    }

    /**
     * {@code states}: branch states of every block that is not entirely DEFAULT. {@code conditions}: condition texts
     * to highlight (the deciding one and the decided one).
     */
    private record Result(Model model, Map<Block, Branch[]> states, @Nullable Block matched, List<TextRange> conditions) {
    }

    private enum Style {
        DEFAULT(TwigArcsColors.DEFAULT, DOT, DOT_GAP, false),
        ACTIVE(TwigArcsColors.ACTIVE, DOT_ACTIVE, DOT_GAP_ACTIVE, false),
        EXCLUDED(TwigArcsColors.OPPOSITE, DOT, DOT_GAP, false),
        /** Taken if reached: every other dot in the DEFAULT color and size (taken, but maybe never reached). */
        INFERRED(TwigArcsColors.INFERRED, DOT_ACTIVE, DOT_GAP_ACTIVE, true);

        final ColorKey key;
        final float dot;
        final float gap;
        final boolean alternating;

        Style(ColorKey key, float dot, float gap, boolean alternating) {
            this.key = key;
            this.dot = dot;
            this.gap = gap;
            this.alternating = alternating;
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
        volatile Map<Block, Branch[]> states = Map.of();
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

    private TwigArcsHighlighter() {
    }

    /** After a settings change: re-apply everything in all Twig editors. */
    static void refreshAll() {
        for (Editor editor : EditorFactory.getInstance().getAllEditors()) {
            State state = editor.getUserData(STATE_KEY);
            Disposable parent = TwigArcsEditorListener.disposable(editor);
            if (state != null && parent != null) {
                state.applied = null;
                schedule(editor, parent);
            }
        }
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

        List<TextRange> conditions = new ArrayList<>();
        Map<Block, Branch[]> states = branchStates(model, offset, conditions);

        Block matched = null;
        if (caretTag != null) {
            PsiElement owner = caretTag.getParent();
            while (owner != null && isBranchStatement(owner)) {
                owner = owner.getParent(); // {% else %} -> ELSE_STATEMENT (-> ELSEIF_STATEMENT ...) -> IF_STATEMENT
            }
            Block block = owner == null ? null : model.byRange().get(owner.getTextRange());
            if (block != null && block.tags().contains(caretTag.getTextRange())) {
                matched = block; // only when the caret is on one of the block's own tags, not e.g. an {% include %}
            }
        }
        return new Result(model, states, matched, conditions);
    }

    /**
     * Branch states: blocks around the caret get ACTIVE/EXCLUDED. Their if/elseif conditions become facts (earlier
     * conditions false, the caret's one true), which decide other if blocks with the same condition, branch by branch:
     * known true = INFERRED (taken if reached) and the rest EXCLUDED, known false = EXCLUDED, unknown = stop.
     * EXCLUDED stays valid under an undecided parent; INFERRED only means "taken if the code gets there".
     * Facts only apply within the same {% for %} loop and not across a {% set %} of a variable in the condition.
     * Blocks inside an EXCLUDED branch are unreachable: all EXCLUDED. Finally, INFERRED becomes ACTIVE (certainly
     * taken) where the block always runs together with the caret's line, see {@link #certainlyReached}.
     */
    private static Map<Block, Branch[]> branchStates(Model model, int offset, List<TextRange> conditions) {
        List<Block> blocks = model.blocks();
        Map<Block, Branch[]> states = new HashMap<>();
        record Fact(Cond cond, boolean value, Block source) {
        }
        List<Fact> facts = new ArrayList<>();

        for (Block b : blocks) {
            TextRange r = b.range();
            if (r.getStartOffset() > offset || offset > r.getEndOffset()) {
                continue;
            }
            int active = b.branchAt(offset);
            Branch[] st = new Branch[b.branchCount()];
            for (int i = 0; i < st.length; i++) {
                st[i] = i == active ? Branch.ACTIVE : Branch.EXCLUDED;
            }
            states.put(b, st);
            if (b.kind() == Kind.IF) {
                for (int i = 0; i <= active; i++) {
                    Cond c = b.conds().get(i);
                    if (c != null) {
                        facts.add(new Fact(c, (i == active) != c.negated(), b));
                    }
                }
            }
        }

        if (!facts.isEmpty()) {
            for (Block b : blocks) {
                if (b.kind() != Kind.IF || states.containsKey(b)) {
                    continue;
                }
                Branch[] st = new Branch[b.branchCount()];
                java.util.Arrays.fill(st, Branch.DEFAULT);
                boolean decided = false;
                for (int i = 0; i < st.length; i++) {
                    Cond c = b.conds().get(i);
                    Boolean value = null;
                    if (c == null) {
                        value = true; // else: taken when all earlier branches are ruled out
                    } else {
                        for (Fact f : facts) {
                            if (f.cond().base().equals(c.base())
                                    && f.source().loop() == b.loop()
                                    && !setBetween(model, c.base(), f.source().range().getStartOffset(), b.range().getStartOffset())) {
                                value = f.value() != c.negated();
                                conditions.add(f.cond().range());
                                conditions.add(c.range());
                                break;
                            }
                        }
                    }
                    if (value == null) {
                        break;
                    }
                    decided = true;
                    if (value) {
                        st[i] = Branch.INFERRED;
                        for (int j = i + 1; j < st.length; j++) {
                            st[j] = Branch.EXCLUDED;
                        }
                        break;
                    }
                    st[i] = Branch.EXCLUDED;
                }
                if (decided) {
                    states.put(b, st);
                }
            }
        }

        // Unreachable: inside an EXCLUDED branch of the parent (pre-order: parents are final before children).
        for (Block b : blocks) {
            if (b.parent() < 0) {
                continue;
            }
            Block p = blocks.get(b.parent());
            Branch[] ps = states.get(p);
            if (ps != null && ps[p.branchAt(b.range().getStartOffset())] == Branch.EXCLUDED) {
                Branch[] st = new Branch[b.branchCount()];
                java.util.Arrays.fill(st, Branch.EXCLUDED);
                states.put(b, st);
            }
        }

        // Certainly taken (pre-order: parents are upgraded before their children are checked).
        for (Block b : blocks) {
            Branch[] st = states.get(b);
            if (st == null) {
                continue;
            }
            for (int i = 0; i < st.length; i++) {
                if (st[i] == Branch.INFERRED && certainlyReached(blocks, states, b, offset)) {
                    st[i] = Branch.ACTIVE;
                }
            }
        }
        return states;
    }

    /**
     * Whether the code always reaches {@code b} when it reaches the caret's line: going up, every enclosing block
     * is an if whose branch around it is ACTIVE (certainly taken), up to the top level or to the first block that
     * contains the caret, whose branch around it must be the caret's branch. An undecided if, or a for / block /
     * macro without the caret (may run zero times, be overridden, never be called) makes it uncertain.
     */
    private static boolean certainlyReached(List<Block> blocks, Map<Block, Branch[]> states, Block b, int offset) {
        for (Block c = b; c.parent() >= 0; ) {
            Block p = blocks.get(c.parent());
            Branch[] ps = states.get(p);
            boolean taken = ps != null && ps[p.branchAt(c.range().getStartOffset())] == Branch.ACTIVE;
            TextRange r = p.range();
            if (r.getStartOffset() <= offset && offset <= r.getEndOffset()) {
                return taken;
            }
            if (p.kind() != Kind.IF || !taken) {
                return false;
            }
            c = p;
        }
        return true;
    }

    /** Whether a {% set %} of a variable used in {@code condition} lies between the two offsets. */
    private static boolean setBetween(Model model, String condition, int a, int b) {
        int from = Math.min(a, b);
        int to = Math.max(a, b);
        for (SetTag set : model.sets()) {
            if (set.offset() > from && set.offset() < to
                    && java.util.regex.Pattern.compile("\\b" + java.util.regex.Pattern.quote(set.name()) + "\\b").matcher(condition).find()) {
                return true;
            }
        }
        return false;
    }

    private static Model buildModel(PsiFile twig, Document document, long stamp) {
        List<Block> blocks = new ArrayList<>();
        List<TextRange> statements = new ArrayList<>();
        List<TextRange> prints = new ArrayList<>();
        List<TextRange> comments = new ArrayList<>();
        List<SetTag> sets = new ArrayList<>();
        walk(twig, -1, -1, 0, document, blocks, statements, prints, comments, sets);
        Map<TextRange, Block> byRange = new HashMap<>();
        int maxDepth = 0;
        for (Block b : blocks) {
            byRange.put(b.range(), b);
            maxDepth = Math.max(maxDepth, b.depth());
        }
        return new Model(stamp, blocks, byRange, maxDepth, statements, prints, comments, sets);
    }

    private static final java.util.regex.Pattern SET_NAME = java.util.regex.Pattern.compile("^\\{%-?\\s*set\\s+(\\w+)");

    private static void walk(PsiElement parent, int parentIndex, int loopIndex, int depth, Document doc, List<Block> blocks,
                             List<TextRange> statements, List<TextRange> prints, List<TextRange> comments, List<SetTag> sets) {
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
                if (type == TwigElementTypes.SET_TAG) {
                    java.util.regex.Matcher m = SET_NAME.matcher(c.getText());
                    if (m.find()) {
                        sets.add(new SetTag(c.getTextRange().getStartOffset(), m.group(1)));
                    }
                }
            }
            Block block = toBlock(c, parentIndex, loopIndex, depth, doc);
            if (block != null) {
                blocks.add(block);
                int index = blocks.size() - 1;
                walk(c, index, block.kind() == Kind.FOR ? index : loopIndex, depth + 1, doc, blocks, statements, prints, comments, sets);
            } else {
                walk(c, parentIndex, loopIndex, depth, doc, blocks, statements, prints, comments, sets);
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
    private static @Nullable Block toBlock(@NotNull PsiElement e, int parentIndex, int loopIndex, int depth, Document doc) {
        if (e instanceof PsiFile || isTag(e) || isBranchStatement(e)) {
            return null;
        }
        PsiElement opener = e.getFirstChild();
        if (opener == null || !isTag(opener)) {
            return null;
        }
        List<TextRange> tags = new ArrayList<>();
        List<Cond> conds = new ArrayList<>();
        tags.add(opener.getTextRange());
        conds.add(condition(opener));
        collectBlockTags(opener.getNextSibling(), tags, conds);
        if (tags.size() < 2) {
            return null;
        }
        int length = doc.getTextLength();
        int[] tagLines = new int[tags.size()];
        for (int i = 0; i < tagLines.length; i++) {
            tagLines[i] = doc.getLineNumber(Math.min(tags.get(i).getStartOffset(), length));
        }
        IElementType type = e.getNode().getElementType();
        Kind kind = type == TwigElementTypes.IF_STATEMENT ? Kind.IF : type == TwigElementTypes.FOR_STATEMENT ? Kind.FOR : Kind.OTHER;
        return new Block(e.getTextRange(), parentIndex, depth, loopIndex, kind, tags, tagLines, conds);
    }

    /**
     * Adds the else/elseif tags and the end tag found among {@code first} and its next siblings, in document order.
     * Branch statements can be nested: the Twig parser ends an ELSEIF_STATEMENT only at the end tag, so in
     * if/elseif/elseif/else the following elseif and else statements are children of the first ELSEIF_STATEMENT.
     */
    private static void collectBlockTags(@Nullable PsiElement first, List<TextRange> tags, List<Cond> conds) {
        for (PsiElement child = first; child != null; child = child.getNextSibling()) {
            if (isBranchStatement(child) && child.getFirstChild() != null && isTag(child.getFirstChild())) {
                PsiElement tag = child.getFirstChild();
                tags.add(tag.getTextRange());
                conds.add(condition(tag));
                collectBlockTags(tag.getNextSibling(), tags, conds);
            } else if (isEndTag(child)) {
                tags.add(child.getTextRange());
                conds.add(null);
            }
        }
    }

    /** Condition of an if/elseif tag: the text between the tag name and "%}", normalized; null for other tags. */
    private static @Nullable Cond condition(@NotNull PsiElement tag) {
        IElementType type = tag.getNode().getElementType();
        if (type != TwigElementTypes.IF_TAG && type != TwigElementTypes.ELSEIF_TAG) {
            return null;
        }
        int start = -1;
        int end = -1;
        boolean afterName = false;
        for (ASTNode n = tag.getNode().getFirstChildNode(); n != null; n = n.getTreeNext()) {
            IElementType t = n.getElementType();
            if (t == TwigTokenTypes.TAG_NAME) {
                afterName = true;
            } else if (t == TwigTokenTypes.STATEMENT_BLOCK_END) {
                break;
            } else if (afterName && !n.getText().isBlank()) {
                if (start < 0) {
                    start = n.getStartOffset();
                }
                end = n.getStartOffset() + n.getTextLength();
            }
        }
        if (start < 0) {
            return null;
        }
        TextRange range = new TextRange(start, end);
        String text = range.substring(tag.getContainingFile().getText()).trim().replaceAll("\\s+", " ");
        boolean negated = false;
        text = stripParens(text);
        while (text.startsWith("not ")) {
            String rest = text.substring(4).trim();
            String operand = rest;
            while (operand.startsWith("not ")) {
                operand = operand.substring(4).trim();
            }
            if (!isSingleOperand(operand)) {
                break; // "not a and b" is "(not a) and b": keep it as an opaque condition
            }
            negated = !negated;
            text = stripParens(rest);
        }
        return new Cond(text, negated, range);
    }

    /** Removes parentheses that wrap the whole text: "(a and b)" -> "a and b". */
    private static String stripParens(String s) {
        while (s.startsWith("(") && s.endsWith(")") && closingParen(s) == s.length() - 1) {
            s = s.substring(1, s.length() - 1).trim();
        }
        return s;
    }

    /** Index of the parenthesis that closes the one at index 0, or -1. */
    private static int closingParen(String s) {
        int depth = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')' && --depth == 0) {
                return i;
            }
        }
        return -1;
    }

    /** No space outside brackets and quotes, e.g. "user.roles[0]" or "is_granted('ROLE_ADMIN')". */
    private static boolean isSingleOperand(String s) {
        int depth = 0;
        char quote = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                }
            } else if (c == '\'' || c == '"') {
                quote = c;
            } else if (c == '(' || c == '[' || c == '{') {
                depth++;
            } else if (c == ')' || c == ']' || c == '}') {
                depth--;
            } else if (c == ' ' && depth == 0) {
                return false;
            }
        }
        return true;
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
            state.states = Map.of();
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
            addAll(markup, state.backgrounds, model.statements(), TwigArcsColors.STATEMENT_BACKGROUND, BACKGROUND_LAYER, length);
            addAll(markup, state.backgrounds, model.prints(), TwigArcsColors.PRINT_BACKGROUND, BACKGROUND_LAYER, length);
            addAll(markup, state.backgrounds, model.comments(), TwigArcsColors.COMMENT_BACKGROUND, BACKGROUND_LAYER, length);
            state.applied = model;
            reserveWidth(editor, state, parent, model.blocks().isEmpty() ? 0 : PAD_LEFT + model.maxDepth() * NEST_STEP + 2);
        }
        state.states = result.states();

        Block matched = result.matched();
        if (matched != null) {
            addAll(markup, state.caretHighlighters, matched.tags(), TwigArcsColors.MATCHED_TAG, LAYER, length);
        }
        addAll(markup, state.caretHighlighters, result.conditions(), TwigArcsColors.MATCHED_CONDITION, LAYER, length);

        // Grey out excluded branches (not again inside a branch that is already greyed).
        List<Block> blocks = result.model().blocks();
        List<TextRange> excluded = new ArrayList<>();
        for (Map.Entry<Block, Branch[]> e : result.states().entrySet()) {
            Block block = e.getKey();
            if (block.parent() >= 0) {
                Block p = blocks.get(block.parent());
                Branch[] ps = result.states().get(p);
                if (ps != null && ps[p.branchAt(block.range().getStartOffset())] == Branch.EXCLUDED) {
                    continue;
                }
            }
            Branch[] st = e.getValue();
            for (int branch = 0; branch < st.length; branch++) {
                if (st[branch] == Branch.EXCLUDED) {
                    excluded.add(block.branchContent(branch));
                }
            }
        }
        addAll(markup, state.caretHighlighters, excluded, TwigArcsColors.EXCLUDED_TEXT, EXCLUDED_TEXT_LAYER, length);

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
    /** Set when the internal gutter API is missing or changed in this IDE version: fall back to the default width. */
    private static volatile boolean reserveUnavailable;

    /**
     * Widens the gutter's left free area (setting "Widen the gutter", on by default). This is an internal platform API,
     * so any linkage error turns it off for the session instead of breaking the plugin.
     */
    private static void reserveWidth(Editor editor, State state, Disposable parent, int width) {
        if (reserveUnavailable || !TwigArcsSettings.getInstance().isWidenGutter()) {
            width = 0;
        }
        if (width == state.reservedWidth || !(editor instanceof EditorEx ex)) {
            return;
        }
        if (state.widthReservation != null) {
            Disposer.dispose(state.widthReservation);
            state.widthReservation = null;
        }
        state.reservedWidth = width;
        if (width > 0) {
            Disposable reservation = Disposer.newDisposable(parent, "TwigArcs gutter width");
            try {
                ex.getGutterComponentEx().reserveLeftFreePaintersAreaWidth(reservation, width);
            } catch (LinkageError e) {
                reserveUnavailable = true;
                state.reservedWidth = 0;
                Disposer.dispose(reservation);
                return;
            }
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
        float gap = JBUIScale.scale((float) TAG_GAP);
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
        int areaX = state.leftAreaX >= 0 ? state.leftAreaX : gutter.getIconAreaOffset() - JBUIScale.scale(Math.max(0, state.reservedWidth));
        float rootLimit = areaX - gutterWidth + JBUIScale.scale((float) PAD_LEFT);
        float nestStep = JBUIScale.scale((float) NEST_STEP);

        Document doc = editor.getDocument();
        int lineCount = doc.getLineCount();
        int lineHeight = editor.getLineHeight();
        Map<Block, Branch[]> states = state.states;
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
            Branch[] st = states.get(b);

            if (b.isSingleLine()) {
                float q = lineHeight / 4f;
                float x = min(tx);
                Path2D path = new Path2D.Float();
                path.moveTo(x, ys[0] - q);
                lefts[i] = new float[]{appendCurve(path, x, ys[0] - q, ys[0] + q, limit)};
                curves.add(new Curve(path, singleLineStyle(st), ys[0] - q, ys[0] + q));
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
                curves.add(new Curve(path, st == null ? Style.DEFAULT : style(st[k]), ys[k], ys[k + 1]));
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

    private static Style style(Branch branch) {
        return switch (branch) {
            case DEFAULT -> Style.DEFAULT;
            case ACTIVE -> Style.ACTIVE;
            case EXCLUDED -> Style.EXCLUDED;
            case INFERRED -> Style.INFERRED;
        };
    }

    /** A single-line block has one curve for all its branches. */
    private static Style singleLineStyle(Branch @Nullable [] st) {
        if (st == null) {
            return Style.DEFAULT;
        }
        boolean allExcluded = true;
        for (Branch b : st) {
            if (b == Branch.ACTIVE) {
                return Style.ACTIVE;
            }
            if (b == Branch.INFERRED) {
                return Style.INFERRED;
            }
            allExcluded &= b == Branch.EXCLUDED;
        }
        return allExcluded ? Style.EXCLUDED : Style.DEFAULT;
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
            for (Curve curve : curves) {
                if (clip != null && (curve.bottom() < clip.y - 4 || curve.top() > clip.y + clip.height + 4)) {
                    continue;
                }
                Style style = curve.style();
                Color color = editor.getColorsScheme().getColor(style.key);
                if (color == null) {
                    continue;
                }
                float dot = JBUIScale.scale(style.dot);
                float period = dot + JBUIScale.scale(style.gap);
                Color altColor = style.alternating ? editor.getColorsScheme().getColor(Style.DEFAULT.key) : null;
                paintDots(g2, curve.path(), period, dot, color, JBUIScale.scale(Style.DEFAULT.dot), altColor);
            }
        } finally {
            g2.dispose();
        }
    }

    /**
     * Places round dots along the path, {@code period} apart, starting at its first point. The same path gives the
     * same dots in the text area and in the gutter, so the two halves line up. With {@code altColor}, every other
     * dot is drawn in that color and {@code altDot} size instead.
     */
    private static void paintDots(Graphics2D g, Path2D path, float period, float dot, Color color,
                                  float altDot, @Nullable Color altColor) {
        g.setColor(color);
        int index = 0;
        float[] c = new float[6];
        float px = 0;
        float py = 0;
        float next = 0; // distance along the path to the next dot
        for (PathIterator it = path.getPathIterator(null, 0.2); !it.isDone(); it.next()) {
            int type = it.currentSegment(c);
            if (type == PathIterator.SEG_MOVETO) {
                px = c[0];
                py = c[1];
                continue;
            }
            if (type != PathIterator.SEG_LINETO) {
                continue;
            }
            float sx = c[0] - px;
            float sy = c[1] - py;
            float length = (float) Math.hypot(sx, sy);
            while (next <= length) {
                float t = length == 0 ? 0 : next / length;
                float x = px + sx * t;
                float y = py + sy * t;
                float d = dot;
                if (altColor != null) {
                    boolean alt = index++ % 2 == 1;
                    g.setColor(alt ? altColor : color);
                    d = alt ? altDot : dot;
                }
                g.fill(new Ellipse2D.Float(x - d / 2, y - d / 2, d, d));
                next += period;
            }
            next -= length;
            px = c[0];
            py = c[1];
        }
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
