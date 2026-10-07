package dev.whitedev.jpi.ui.analysis;

import dev.whitedev.jpi.deobfuscation.DeobfuscationWorkspace;
import org.junit.jupiter.api.Test;

import javax.swing.*;
import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class InvestigationPanelTest {
    @Test void exposesTheAssistantAndManualAnalysisWithoutEnablingDetachedActions() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            InvestigationPanel panel = new InvestigationPanel(new DeobfuscationWorkspace(),
                    ignored -> { }, ignored -> { }, ignored -> { });
            panel.setSession(null);
            List<Component> components = components(panel);
            assertTrue(components.stream().anyMatch(component -> component instanceof JTabbedPane tabs
                    && tabs.getTabCount() == 2 && "Assistant".equals(tabs.getTitleAt(0))
                    && "Entry points and CFG".equals(tabs.getTitleAt(1))));
            for (String label : List.of("Investigate", "Cancel analysis", "Export report...", "Prepare selected probe")) {
                JButton button = components.stream().filter(component -> component instanceof JButton candidate
                        && label.equals(candidate.getText())).map(component -> (JButton) component).findFirst().orElseThrow();
                assertFalse(button.isEnabled(), label);
            }
            assertTrue(components.stream().anyMatch(component -> component instanceof JProgressBar progress
                    && progress.getMaximum() == 7 && progress.getValue() == 0));
        });
    }

    private static List<Component> components(Container parent) {
        List<Component> result = new ArrayList<>();
        for (Component component : parent.getComponents()) {
            result.add(component);
            if (component instanceof Container container) result.addAll(components(container));
        }
        return result;
    }
}
