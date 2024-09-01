package uk.whitedev.memory;

import uk.whitedev.utils.ProcessUtil;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.*;
import java.util.List;
import java.util.stream.Collectors;

public class MemoryEditorGUI {
    private final MemoryEditor editor = new MemoryEditor();
    private JFrame frame;
    private JTextArea outputArea;
    private JTextField pidField;
    private JTextField searchStrField;
    private JComboBox<String> dataTypeCombo;
    private JComboBox<String> scanTypeCombo;
    private MemoryLocation[] memoryLocations;

    public void runMemoryEditGui() {
        new Thread(() -> new MemoryEditorGUI().createAndShowGUI()).start();
    }

    private void createAndShowGUI() {
        Color backgroundColor = new Color(50, 50, 50);
        Color textColor = Color.WHITE;
        Color buttonColor = new Color(80, 80, 80);
        Color buttonTextColor = Color.WHITE;

        frame = new JFrame("Memory Editor [Java Process Inspector]");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setSize(600, 400);
        frame.setLayout(new BorderLayout());


        JPanel topPanel = new JPanel(new GridLayout(6, 2));
        topPanel.setBackground(backgroundColor);
        topPanel.add(createLabeledComponent("PID:", textColor));
        pidField = new JTextField(ProcessUtil.getProcessPid());
        pidField.setBackground(new Color(70, 70, 70));
        pidField.setForeground(textColor);
        topPanel.add(pidField);

        topPanel.add(createLabeledComponent("Search Value:", textColor));
        searchStrField = new JTextField();
        searchStrField.setBackground(new Color(70, 70, 70));
        searchStrField.setForeground(textColor);
        topPanel.add(searchStrField);

        topPanel.add(createLabeledComponent("Data Type:", textColor));
        String[] dataTypes = {"String", "Int", "Double", "Long", "Float", "Short"};
        dataTypeCombo = new JComboBox<>(dataTypes);
        dataTypeCombo.setBackground(new Color(70, 70, 70));
        dataTypeCombo.setForeground(textColor);
        topPanel.add(dataTypeCombo);

        topPanel.add(createLabeledComponent("Scan Type:", textColor));
        String[] scanTypes = {"1 - Windows", "2 - All"};
        scanTypeCombo = new JComboBox<>(scanTypes);
        scanTypeCombo.setBackground(new Color(70, 70, 70));
        scanTypeCombo.setForeground(textColor);
        topPanel.add(scanTypeCombo);

        JButton scanButton = new JButton("Scan Memory");
        scanButton.setBackground(buttonColor);
        scanButton.setForeground(buttonTextColor);
        topPanel.add(scanButton);

        JButton scanPidButton = new JButton("Scan PID");
        scanPidButton.setBackground(buttonColor);
        scanPidButton.setForeground(buttonTextColor);
        topPanel.add(scanPidButton);

        JButton filterButton = new JButton("Filter Results");
        filterButton.setBackground(buttonColor);
        filterButton.setForeground(buttonTextColor);
        topPanel.add(filterButton);

        JButton modifyButton = new JButton("Modify Memory");
        modifyButton.setBackground(buttonColor);
        modifyButton.setForeground(buttonTextColor);
        topPanel.add(modifyButton);

        JPanel bottomPanel = new JPanel(new BorderLayout());
        bottomPanel.setBackground(backgroundColor);

        outputArea = new JTextArea(10, 40);
        outputArea.setEditable(false);
        outputArea.setBackground(new Color(70, 70, 70));
        outputArea.setForeground(textColor);
        bottomPanel.add(new JScrollPane(outputArea));

        frame.add(topPanel, BorderLayout.NORTH);
        frame.add(bottomPanel, BorderLayout.CENTER);

        scanPidButton.addActionListener(new ScanPidButtonListener());
        scanButton.addActionListener(new ScanButtonListener());
        filterButton.addActionListener(new FilterButtonListener());
        modifyButton.addActionListener(new ModifyButtonListener());

        frame.setVisible(true);
    }

    private JPanel createLabeledComponent(String labelText, Color textColor) {
        JPanel panel = new JPanel(new BorderLayout());
        JLabel label = new JLabel(labelText);
        panel.setBackground(new Color(50, 50, 50));
        label.setForeground(textColor);
        panel.add(label, BorderLayout.WEST);
        return panel;
    }


    private class ScanPidButtonListener implements ActionListener {
        @Override
        public void actionPerformed(ActionEvent e) {
            outputArea.setText("");
            try {
                int scanType = scanTypeCombo.getSelectedIndex() + 1;
                if (scanType == 1) {
                    WindowInfo[] win = editor.getOpenWindows();
                    for (WindowInfo windowInfo : win) {
                        outputArea.append("PID: " + windowInfo.getPid() + ", Name: " + windowInfo.getTitle() + "\n");
                    }
                } else if (scanType == 2) {
                    Map<Integer, String> processes = editor.listProcesses();
                    for (Map.Entry<Integer, String> entry : processes.entrySet()) {
                        outputArea.append("PID: " + entry.getKey() + ", Name: " + entry.getValue() + "\n");
                    }
                }
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(frame, "Error scanning processes or windows", "Error", JOptionPane.ERROR_MESSAGE);
                for (StackTraceElement stackTraceElement : ex.getStackTrace()) {
                    outputArea.append(stackTraceElement.toString() + '\n');
                }
            }
        }
    }

    private class ScanButtonListener implements ActionListener {
        @Override
        public void actionPerformed(ActionEvent e) {
            outputArea.setText("");
            try {
                long pid = Long.parseLong(pidField.getText());
                int dataType = dataTypeCombo.getSelectedIndex();
                memoryLocations = editor.scanMemory(pid, searchStrField.getText(), dataType);
                if (memoryLocations.length > 0) {
                    for (MemoryLocation memoryLocation : memoryLocations) {
                        outputArea.append("Address: " + memoryLocation.getAddress() + ", Value: " + memoryLocation.getValue() + "\n");
                    }
                } else {
                    outputArea.append("Not found any value!");
                }
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(frame, "Error scanning memory", "Error", JOptionPane.ERROR_MESSAGE);
                for (StackTraceElement stackTraceElement : ex.getStackTrace()) {
                    outputArea.append(stackTraceElement.toString() + '\n');
                }
            }
        }
    }

    private class FilterButtonListener implements ActionListener {
        @Override
        public void actionPerformed(ActionEvent e) {
            outputArea.setText("");
            try {
                if (memoryLocations != null && memoryLocations.length > 0) {
                    String newFilterValue = JOptionPane.showInputDialog(frame, "Provide new value:");
                    if (newFilterValue != null && !newFilterValue.isEmpty()) {
                        long pid = Long.parseLong(pidField.getText());
                        int dataType = dataTypeCombo.getSelectedIndex();
                        MemoryLocation[] newMemoryLocations = editor.scanMemory(pid, newFilterValue, dataType);
                        memoryLocations = filterMemLoc(memoryLocations, newMemoryLocations);
                        for (MemoryLocation memoryLocation : memoryLocations) {
                            outputArea.append("Address: " + memoryLocation.getAddress() + ", Value: " + memoryLocation.getValue() + "\n");
                        }
                    }
                } else {
                    JOptionPane.showMessageDialog(frame, "Can't filter empty list!", "Error", JOptionPane.ERROR_MESSAGE);
                }
            } catch (NumberFormatException ex) {
                JOptionPane.showMessageDialog(frame, "Invalid PID or data type", "Error", JOptionPane.ERROR_MESSAGE);
                for (StackTraceElement stackTraceElement : ex.getStackTrace()) {
                    outputArea.append(stackTraceElement.toString() + '\n');
                }
            }
        }
    }

    private class ModifyButtonListener implements ActionListener {
        @Override
        public void actionPerformed(ActionEvent e) {
            try {
                long pid = Long.parseLong(pidField.getText());
                int dataType = dataTypeCombo.getSelectedIndex();
                String newValue = JOptionPane.showInputDialog(frame, "Provide new value:");
                if (newValue != null && !newValue.isEmpty()) {
                    MemoryLocation[] memoryLocations = editor.scanMemory(pid, searchStrField.getText(), dataType);
                    editor.modifyMemory(pid, memoryLocations, newValue, dataType);
                    JOptionPane.showMessageDialog(frame, "Memory modified successfully", "Success", JOptionPane.INFORMATION_MESSAGE);
                }
            } catch (NumberFormatException ex) {
                JOptionPane.showMessageDialog(frame, "Invalid PID or data type", "Error", JOptionPane.ERROR_MESSAGE);
                for (StackTraceElement stackTraceElement : ex.getStackTrace()) {
                    outputArea.append(stackTraceElement.toString() + '\n');
                }
            }
        }
    }

    private MemoryLocation[] filterMemLoc(MemoryLocation[] oldMemLoc, MemoryLocation[] newMemLoc) {
        Set<Long> oldAddresses = Arrays.stream(oldMemLoc)
                .map(MemoryLocation::getAddress)
                .collect(Collectors.toSet());
        List<MemoryLocation> memLocList = Arrays.stream(newMemLoc)
                .filter(loc -> oldAddresses.contains(loc.getAddress()))
                .collect(Collectors.toList());
        return memLocList.toArray(new MemoryLocation[0]);
    }
}
