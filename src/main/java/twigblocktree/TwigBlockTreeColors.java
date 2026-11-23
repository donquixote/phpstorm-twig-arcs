package twigblocktree;

import com.intellij.openapi.editor.colors.CodeInsightColors;
import com.intellij.openapi.editor.colors.ColorKey;
import com.intellij.openapi.editor.colors.TextAttributesKey;
import com.intellij.ui.JBColor;

import java.awt.Color;

/**
 * Color keys, editable in Settings | Editor | Color Scheme | Twig Block Tree.
 */
public final class TwigBlockTreeColors {

    /** Gutter bar colors by nesting depth (outermost block = level 1). Deeper levels cycle through these. */
    public static final ColorKey[] LEVELS;

    /** Highlighting of the sibling tags ({% if %} / {% else %} / {% endif %}) when the caret is on one of them. */
    public static final TextAttributesKey MATCHED_TAG =
            TextAttributesKey.createTextAttributesKey("TWIG_BLOCK_TREE_MATCHED_TAG", CodeInsightColors.MATCHED_BRACE_ATTRIBUTES);

    // Deliberately different from the HTML tag tree defaults, so the two can be told apart.
    private static final int[] LIGHT = {0xE07B00, 0x00897B, 0xC2185B, 0x3F51B5, 0x7CB342, 0x8D6E63};
    private static final int[] DARK = {0xF0A040, 0x4DB6AC, 0xF06292, 0x7986CB, 0xAED581, 0xBCAAA4};

    static {
        LEVELS = new ColorKey[LIGHT.length];
        for (int i = 0; i < LIGHT.length; i++) {
            LEVELS[i] = ColorKey.createColorKey(
                    "TWIG_BLOCK_TREE_LEVEL_" + (i + 1),
                    new JBColor(new Color(LIGHT[i]), new Color(DARK[i])));
        }
    }

    private TwigBlockTreeColors() {
    }

    static ColorKey levelKey(int depth) {
        return LEVELS[depth % LEVELS.length];
    }
}
