package dev.whitedev.jpi.ui.tracing;

import dev.whitedev.jpi.deobfuscation.DeobfuscationWorkspace;
import dev.whitedev.jpi.ui.context.AnalysisTarget;
import org.junit.jupiter.api.Test;

import javax.swing.*;
import java.awt.Component;
import java.awt.Container;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

class XrefsPanelTest {
    @Test void runtimeViewExcludesStaticRowsAndRetainsFailedAndReflectiveEdges() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            XrefsPanel panel = new XrefsPanel(new DeobfuscationWorkspace());
            panel.selectUsages(AnalysisTarget.method("c:1", "app.Target", "run", "()V"), true);
            panel.renderXrefs(row("STATIC", "staticCaller") + row("DYNAMIC", "liveCaller")
                    + row("FAILED", "failedCaller") + row("REFLECTIVE", "reflectiveCaller"));
            JTable table = incoming(panel);
            assertEquals(3, table.getRowCount());
            assertEquals("liveCaller", table.getValueAt(0, 2));
            assertEquals("failedCaller", table.getValueAt(1, 2));
            assertEquals("reflectiveCaller", table.getValueAt(2, 2));
        });
    }

    @Test void staticViewExcludesObservedRowsAndEmptyRuntimeViewDoesNotInventUsages() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            XrefsPanel panel = new XrefsPanel(new DeobfuscationWorkspace());
            AnalysisTarget target = AnalysisTarget.type("c:1", "app.Target");
            panel.selectUsages(target, false);
            panel.renderXrefs(row("STATIC", "staticCaller") + row("DYNAMIC", "liveCaller"));
            assertEquals(1, incoming(panel).getRowCount());
            assertEquals("staticCaller", incoming(panel).getValueAt(0, 2));
            panel.selectUsages(target, true);
            panel.renderXrefs("");
            assertEquals(0, incoming(panel).getRowCount());
        });
    }

    private static JTable incoming(XrefsPanel panel) {
        JTabbedPane tabs = findTabs(panel);
        assertNotNull(tabs);
        return (JTable) ((JScrollPane) tabs.getComponentAt(0)).getViewport().getView();
    }

    private static JTabbedPane findTabs(Container parent) {
        for (Component component : parent.getComponents()) {
            if (component instanceof JTabbedPane tabs) return tabs;
            if (component instanceof Container container) {
                JTabbedPane nested = findTabs(container);
                if (nested != null) return nested;
            }
        }
        return null;
    }

    private static String row(String layer, String method) {
        return String.join("\t", "R", layer, "CALLED_BY", "1", encoded("c:2"), encoded("app.Caller"),
                encoded(method), encoded("()V"), encoded("invoke")) + "\n";
    }

    private static String encoded(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}
