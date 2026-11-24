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
                <comment>{# Greeting #}</comment>
                <match>{% if user %}</match>
                  Hello <print>{{ user.name }}</print>!
                <match>{% else %}</match>
                <excluded>  Please <stmt>{% include 'login.html.twig' %}</stmt></excluded>
                <match>{% endif %}</match>
                """;
    }

    @Override
    public @Nullable Map<String, TextAttributesKey> getAdditionalHighlightingTagToDescriptorMap() {
        return Map.of(
                "match", TwigBlockTreeColors.MATCHED_TAG,
                "excluded", TwigBlockTreeColors.EXCLUDED_TEXT,
                "stmt", TwigBlockTreeColors.STATEMENT_BACKGROUND,
                "print", TwigBlockTreeColors.PRINT_BACKGROUND,
                "comment", TwigBlockTreeColors.COMMENT_BACKGROUND);
    }

    @Override
    public AttributesDescriptor @NotNull [] getAttributeDescriptors() {
        return new AttributesDescriptor[]{
                new AttributesDescriptor("Tags//Matching tags of the block under the caret", TwigBlockTreeColors.MATCHED_TAG),
                new AttributesDescriptor("Text in branches the caret is not in", TwigBlockTreeColors.EXCLUDED_TEXT),
                new AttributesDescriptor("Backgrounds//{% %} statements", TwigBlockTreeColors.STATEMENT_BACKGROUND),
                new AttributesDescriptor("Backgrounds//{{ }} prints", TwigBlockTreeColors.PRINT_BACKGROUND),
                new AttributesDescriptor("Backgrounds//{# #} comments", TwigBlockTreeColors.COMMENT_BACKGROUND),
        };
    }

    @Override
    public ColorDescriptor @NotNull [] getColorDescriptors() {
        return new ColorDescriptor[]{
                new ColorDescriptor("Curves//Block without the caret", TwigBlockTreeColors.DEFAULT, ColorDescriptor.Kind.FOREGROUND),
                new ColorDescriptor("Curves//Branch with the caret", TwigBlockTreeColors.ACTIVE, ColorDescriptor.Kind.FOREGROUND),
                new ColorDescriptor("Curves//Other branches (else, elseif)", TwigBlockTreeColors.OPPOSITE, ColorDescriptor.Kind.FOREGROUND),
        };
    }

    @Override
    public @NotNull String getDisplayName() {
        return "Twig Block Tree";
    }
}
