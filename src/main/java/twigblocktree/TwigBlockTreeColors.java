package twigblocktree;

import com.intellij.openapi.editor.colors.CodeInsightColors;
import com.intellij.openapi.editor.colors.ColorKey;
import com.intellij.openapi.editor.colors.TextAttributesKey;
import com.intellij.ui.JBColor;

import java.awt.Color;

/**
 * Color keys, editable in Settings | Editor | Color Scheme | Twig Block Tree.
 * Background defaults for the Default and Darcula schemes are in {@code colorSchemes/*.xml}.
 */
public final class TwigBlockTreeColors {

    /** Curves of blocks that do not contain the caret. */
    public static final ColorKey DEFAULT = ColorKey.createColorKey("TWIG_BLOCK_TREE_DEFAULT_BLOCK",
            new JBColor(new Color(0x7B8FE6), new Color(0x6878C8)));

    /** Curve of the branch holding the caret, in every block that contains the caret. */
    public static final ColorKey ACTIVE = ColorKey.createColorKey("TWIG_BLOCK_TREE_ACTIVE_BRANCH",
            new JBColor(new Color(0x2DA44E), new Color(0x4EBE6A)));

    /** Curves of the other branches ({% else %}, {% elseif %}) of a block that contains the caret. */
    public static final ColorKey OPPOSITE = ColorKey.createColorKey("TWIG_BLOCK_TREE_EXCLUDED_BRANCH",
            new JBColor(new Color(0xE5484D), new Color(0xF06A6A)));

    /** Text in the branches of a block that the caret is not in. Defaults to the "unused code" style (greyed out). */
    public static final TextAttributesKey EXCLUDED_TEXT =
            TextAttributesKey.createTextAttributesKey("TWIG_BLOCK_TREE_EXCLUDED_TEXT", CodeInsightColors.NOT_USED_ELEMENT_ATTRIBUTES);

    /** All tags of the block when the caret is on one of them ({% if %} / {% else %} / {% endif %}). */
    public static final TextAttributesKey MATCHED_TAG =
            TextAttributesKey.createTextAttributesKey("TWIG_BLOCK_TREE_MATCHED_TAG", CodeInsightColors.MATCHED_BRACE_ATTRIBUTES);

    /** Every {% ... %}. Pale background by default. */
    public static final TextAttributesKey STATEMENT_BACKGROUND =
            TextAttributesKey.createTextAttributesKey("TWIG_BLOCK_TREE_STATEMENT_BACKGROUND");

    /** Every {{ ... }}. No background by default. */
    public static final TextAttributesKey PRINT_BACKGROUND =
            TextAttributesKey.createTextAttributesKey("TWIG_BLOCK_TREE_PRINT_BACKGROUND");

    /** Every {# ... #}. No background by default. */
    public static final TextAttributesKey COMMENT_BACKGROUND =
            TextAttributesKey.createTextAttributesKey("TWIG_BLOCK_TREE_COMMENT_BACKGROUND");

    private TwigBlockTreeColors() {
    }
}
