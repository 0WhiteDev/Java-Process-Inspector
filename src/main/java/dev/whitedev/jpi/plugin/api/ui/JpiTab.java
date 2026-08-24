package dev.whitedev.jpi.plugin.api.ui;

import dev.whitedev.jpi.plugin.api.JpiContext;

import javax.swing.JComponent;

public interface JpiTab {
    String id();
    String title();

    default TabGroup group() {
        return TabGroup.ADVANCED;
    }

    JComponent createComponent(JpiContext context) throws Exception;
}
