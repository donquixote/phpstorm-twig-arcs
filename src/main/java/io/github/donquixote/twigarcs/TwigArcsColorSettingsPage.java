package io.github.donquixote.twigarcs;

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

public final class TwigArcsColorSettingsPage implements ColorSettingsPage {

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
                {% if <cond>user</cond> %}
                  Logged in
                {% else %}
                <excluded>  Logged out</excluded>
                {% endif %}
                """;
    }

    @Override
    public @Nullable Map<String, TextAttributesKey> getAdditionalHighlightingTagToDescriptorMap() {
        return Map.of(
                "match", TwigArcsColors.MATCHED_TAG,
                "excluded", TwigArcsColors.EXCLUDED_TEXT,
                "stmt", TwigArcsColors.STATEMENT_BACKGROUND,
                "print", TwigArcsColors.PRINT_BACKGROUND,
                "comment", TwigArcsColors.COMMENT_BACKGROUND,
                "cond", TwigArcsColors.MATCHED_CONDITION);
    }

    @Override
    public AttributesDescriptor @NotNull [] getAttributeDescriptors() {
        return new AttributesDescriptor[]{
                new AttributesDescriptor("Tags//Matching tags of the block under the caret", TwigArcsColors.MATCHED_TAG),
                new AttributesDescriptor("Tags//Condition that decided another block", TwigArcsColors.MATCHED_CONDITION),
                new AttributesDescriptor("Text in branches the caret is not in", TwigArcsColors.EXCLUDED_TEXT),
                new AttributesDescriptor("Backgrounds//{% %} statements", TwigArcsColors.STATEMENT_BACKGROUND),
                new AttributesDescriptor("Backgrounds//{{ }} prints", TwigArcsColors.PRINT_BACKGROUND),
                new AttributesDescriptor("Backgrounds//{# #} comments", TwigArcsColors.COMMENT_BACKGROUND),
        };
    }

    @Override
    public ColorDescriptor @NotNull [] getColorDescriptors() {
        return new ColorDescriptor[]{
                new ColorDescriptor("Curves//Block without the caret", TwigArcsColors.DEFAULT, ColorDescriptor.Kind.FOREGROUND),
                new ColorDescriptor("Curves//Branch with the caret", TwigArcsColors.ACTIVE, ColorDescriptor.Kind.FOREGROUND),
                new ColorDescriptor("Curves//Excluded branches", TwigArcsColors.OPPOSITE, ColorDescriptor.Kind.FOREGROUND),
                new ColorDescriptor("Curves//Branch taken if reached (same condition elsewhere)", TwigArcsColors.INFERRED, ColorDescriptor.Kind.FOREGROUND),
        };
    }

    @Override
    public @NotNull String getDisplayName() {
        return "Twig Arcs";
    }
}
