package twigblocktree;

import com.intellij.openapi.editor.colors.TextAttributesKey;
import com.intellij.openapi.fileTypes.PlainSyntaxHighlighter;
import com.intellij.openapi.fileTypes.SyntaxHighlighter;
import com.intellij.openapi.options.colors.AttributesDescriptor;
import com.intellij.openapi.options.colors.ColorDescriptor;
import com.intellij.openapi.options.colors.ColorSettingsPage;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.Icon;
import java.util.Map;

public final class TwigBlockTreeColorSettingsPage implements ColorSettingsPage {

    @Override
    public @Nullable Icon getIcon() {
        return null;
    }

    @Override
    public @NotNull SyntaxHighlighter getHighlighter() {
        return new PlainSyntaxHighlighter();
    }

    @Override
    public @NotNull String getDemoText() {
        return """
                <tag>{% if</tag> user %}
                  Hello {{ user.name }}!
                <tag>{% else</tag> %}
                  Please log in.
                <tag>{% endif</tag> %}
                """;
    }

    @Override
    public @Nullable Map<String, TextAttributesKey> getAdditionalHighlightingTagToDescriptorMap() {
        return Map.of("tag", TwigBlockTreeColors.MATCHED_TAG);
    }

    @Override
    public AttributesDescriptor @NotNull [] getAttributeDescriptors() {
        return new AttributesDescriptor[]{
                new AttributesDescriptor("Matching tags of the block under the caret", TwigBlockTreeColors.MATCHED_TAG),
        };
    }

    @Override
    public ColorDescriptor @NotNull [] getColorDescriptors() {
        ColorDescriptor[] result = new ColorDescriptor[TwigBlockTreeColors.LEVELS.length];
        for (int i = 0; i < result.length; i++) {
            result[i] = new ColorDescriptor(
                    "Gutter bar//Level " + (i + 1),
                    TwigBlockTreeColors.LEVELS[i],
                    ColorDescriptor.Kind.FOREGROUND);
        }
        return result;
    }

    @Override
    public @NotNull String getDisplayName() {
        return "Twig Block Tree";
    }
}
