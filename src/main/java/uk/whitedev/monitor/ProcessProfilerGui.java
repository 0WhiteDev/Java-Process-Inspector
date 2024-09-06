package uk.whitedev.monitor;

import uk.whitedev.utils.FieldUtil;
import uk.whitedev.utils.MonitorUtil;

import javax.swing.*;
import java.awt.*;

public class ProcessProfilerGui {
    private JTextArea performanceArea;
    private JTextArea resourcesArea;
    private JTextArea processInfoArea;
    private JTextArea fieldsArea;

    private final Color backgroundColor = new Color(50, 50, 50);
    private final Color textColor = Color.WHITE;
    private final Color buttonColor = new Color(80, 80, 80);
    private final Color buttonTextColor = Color.WHITE;

    private final MonitorUtil monitorUtil = new MonitorUtil();
    private final FieldUtil fieldUtil = new FieldUtil();

    private JScrollPane performanceScrollPane;
    private JScrollPane resourcesScrollPane;
    private JScrollPane processInfoScrollPane;
    private JScrollPane fieldsScrollPane;

    public void runProfilerGui() {
        new Thread(this::createAndShowGUI).start();
    }

    private void createAndShowGUI() {
        JFrame frame = new JFrame("Process Profiler [Java Process Inspector]");
        frame.setSize(800, 600);
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setLayout(new BorderLayout());

        performanceArea = new JTextArea(monitorUtil.getPerformanceData());
        resourcesArea = new JTextArea(monitorUtil.getResourcesData());
        processInfoArea = new JTextArea(monitorUtil.getDetailedInfo());
        fieldsArea = new JTextArea(fieldUtil.getAllFields());

        performanceArea.setEditable(false);
        resourcesArea.setEditable(false);
        processInfoArea.setEditable(false);
        fieldsArea.setEditable(false);

        performanceArea.setBackground(backgroundColor);
        performanceArea.setForeground(textColor);
        resourcesArea.setBackground(backgroundColor);
        resourcesArea.setForeground(textColor);
        processInfoArea.setBackground(backgroundColor);
        processInfoArea.setForeground(textColor);
        fieldsArea.setBackground(backgroundColor);
        fieldsArea.setForeground(textColor);

        performanceScrollPane = new JScrollPane(performanceArea);
        resourcesScrollPane = new JScrollPane(resourcesArea);
        processInfoScrollPane = new JScrollPane(processInfoArea);
        fieldsScrollPane = new JScrollPane(fieldsArea);

        JPanel panel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.BOTH;
        gbc.weightx = 1.0;
        gbc.weighty = 1.0;

        JSplitPane splitPane1 = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,
                performanceScrollPane,
                processInfoScrollPane);
        splitPane1.setDividerLocation(400);

        JSplitPane splitPane2 = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,
                resourcesScrollPane,
                fieldsScrollPane);
        splitPane2.setDividerLocation(400);

        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.gridwidth = 1;
        gbc.gridheight = 1;
        gbc.weighty = 0.5;
        panel.add(splitPane1, gbc);

        gbc.gridx = 0;
        gbc.gridy = 1;
        gbc.weightx = 1.0;
        gbc.weighty = 0.5;
        panel.add(splitPane2, gbc);

        frame.add(panel, BorderLayout.CENTER);

        JButton refreshFieldsButton = new JButton("Refresh Fields");
        refreshFieldsButton.addActionListener(e ->fieldsArea.setText(fieldUtil.getAllFields()));
        refreshFieldsButton.setBackground(buttonColor);
        refreshFieldsButton.setForeground(buttonTextColor);
        refreshFieldsButton.setFocusPainted(false);
        frame.add(refreshFieldsButton, BorderLayout.SOUTH);

        initRefreshTimer();

        frame.setVisible(true);
    }


    private void initRefreshTimer() {
        Timer refreshTimer = new Timer(1000, e -> updateTextAreas());
        refreshTimer.start();
    }

    private void updateTextAreas() {
        SwingUtilities.invokeLater(() -> {
            Point performanceScrollPos = performanceScrollPane.getViewport().getViewPosition();
            Point resourcesScrollPos = resourcesScrollPane.getViewport().getViewPosition();
            Point processInfoScrollPos = processInfoScrollPane.getViewport().getViewPosition();
            Point fieldsScrollPos = fieldsScrollPane.getViewport().getViewPosition();

            performanceArea.setText(monitorUtil.getPerformanceData());
            resourcesArea.setText(monitorUtil.getResourcesData());
            processInfoArea.setText(monitorUtil.getDetailedInfo());

            SwingUtilities.invokeLater(() -> {
                performanceScrollPane.getViewport().setViewPosition(performanceScrollPos);
                resourcesScrollPane.getViewport().setViewPosition(resourcesScrollPos);
                processInfoScrollPane.getViewport().setViewPosition(processInfoScrollPos);
                fieldsScrollPane.getViewport().setViewPosition(fieldsScrollPos);
            });
        });
    }
}