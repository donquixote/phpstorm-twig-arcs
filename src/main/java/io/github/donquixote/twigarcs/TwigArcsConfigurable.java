package io.github.donquixote.twigarcs;

import com.intellij.openapi.options.Configurable;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;

import javax.swing.JComponent;
import javax.swing.JPanel;

/** Settings | Editor | Twig Arcs. */
public final class TwigArcsConfigurable implements Configurable {

    private JBCheckBox widenGutter;

    @Override
    public @Nls String getDisplayName() {
        return "Twig Arcs";
    }

    @Override
    public @NotNull JComponent createComponent() {
        widenGutter = new JBCheckBox("Widen the gutter to fit nested arcs");
        JBLabel widenComment = comment("The gutter area left of the icons grows by about 3px per nesting level, so arcs "
                + "of deeply nested blocks at the start of a line keep their round shape. Uses an internal PhpStorm API; "
                + "when off, the area keeps its default width.");
        JBLabel colors = comment("Colors: Editor | Color Scheme | Twig Arcs.");
        return FormBuilder.createFormBuilder()
                .addComponent(widenGutter)
                .addComponent(widenComment, 0)
                .addVerticalGap(JBUI.scale(12))
                .addComponent(colors)
                .addComponentFillVertically(new JPanel(), 0)
                .getPanel();
    }

    private static JBLabel comment(String text) {
        JBLabel label = new JBLabel("<html>" + text + "</html>");
        label.setComponentStyle(UIUtil.ComponentStyle.SMALL);
        label.setFontColor(UIUtil.FontColor.BRIGHTER);
        label.setBorder(JBUI.Borders.emptyLeft(24));
        return label;
    }

    @Override
    public boolean isModified() {
        return widenGutter.isSelected() != TwigArcsSettings.getInstance().isWidenGutter();
    }

    @Override
    public void apply() {
        TwigArcsSettings.getInstance().setWidenGutter(widenGutter.isSelected());
        TwigArcsHighlighter.refreshAll();
    }

    @Override
    public void reset() {
        widenGutter.setSelected(TwigArcsSettings.getInstance().isWidenGutter());
    }

    @Override
    public void disposeUIResources() {
        widenGutter = null;
    }
}
