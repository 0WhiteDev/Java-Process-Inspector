package uk.whitedev.classes;

import uk.whitedev.utils.ClassUtil;
import uk.whitedev.utils.ColorUtils;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.StyledDocument;
import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;

public class ClassCheckerGui {
    private JFrame frame;
    private JList<String> classList;
    private DefaultListModel<String> listModel;
    private JTextField searchField;
    private final ClassUtil classUtil = new ClassUtil();
    private final Color backgroundColor = new Color(50, 50, 50);
    private final Color textColor = Color.WHITE;
    private final Color buttonColor = new Color(80, 80, 80);
    private final Color buttonTextColor = Color.WHITE;

    public void runClassEditGui() {
        new Thread(this::createAndShowGUI).start();
    }

    private void createAndShowGUI() {
        frame = new JFrame("Class Checker [Java Process Inspector]");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setSize(600, 400);
        frame.setLayout(new BorderLayout());

        listModel = new DefaultListModel<>();
        classList = new JList<>(listModel);
        classList.setBackground(backgroundColor);
        classList.setForeground(textColor);
        JScrollPane listScrollPane = new JScrollPane(classList);

        JPanel buttonPanel = new JPanel();
        buttonPanel.setLayout(new GridLayout(3, 1, 10, 10));
        buttonPanel.setBackground(backgroundColor);

        JButton decompileButton = new JButton("Decompile Class");
        JButton reloadButton = new JButton("Reload Class List");
        JButton dumpButton = new JButton("Dump Classes");

        styleButton(decompileButton, buttonColor, buttonTextColor);
        styleButton(reloadButton, buttonColor, buttonTextColor);
        styleButton(dumpButton, buttonColor, buttonTextColor);

        decompileButton.addActionListener(e -> decompileClass());
        reloadButton.addActionListener(e -> reloadClassList());
        dumpButton.addActionListener(e -> dumpClasses());

        buttonPanel.add(decompileButton);
        buttonPanel.add(reloadButton);
        buttonPanel.add(dumpButton);

        JPanel searchPanel = new JPanel(new BorderLayout());
        searchPanel.setBackground(backgroundColor);

        searchField = new JTextField();
        searchField.setBackground(backgroundColor);
        searchField.setForeground(textColor);
        JLabel searchLabel = new JLabel("Search:");
        searchLabel.setForeground(textColor);

        searchPanel.add(searchLabel, BorderLayout.WEST);
        searchPanel.add(searchField, BorderLayout.CENTER);

        searchField.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                if (!searchField.getText().isEmpty()) {
                    filterClassList();
                } else {
                    reloadClassList();
                }
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                if (!searchField.getText().isEmpty()) {
                    filterClassList();
                } else {
                    reloadClassList();
                }
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                filterClassList();
            }
        });

        frame.add(searchPanel, BorderLayout.NORTH);
        frame.add(listScrollPane, BorderLayout.CENTER);
        frame.add(buttonPanel, BorderLayout.EAST);

        reloadClassList();

        frame.setVisible(true);
    }

    private void filterClassList() {
        String searchText = searchField.getText().toLowerCase();
        List<String> filteredClasses = new ArrayList<>();
        for (int i = 0; i < listModel.getSize(); i++) {
            String className = listModel.getElementAt(i).toLowerCase();
            if (className.contains(searchText)) {
                filteredClasses.add(listModel.getElementAt(i));
            }
        }
        listModel.clear();
        for (String className : filteredClasses) {
            listModel.addElement(className);
        }
    }

    private void styleButton(JButton button, Color backgroundColor, Color textColor) {
        button.setBackground(backgroundColor);
        button.setForeground(textColor);
        button.setFocusPainted(false);
    }

    private void reloadClassList() {
        List<String> loadedClasses = classUtil.getLoadedClasses();
        listModel.clear();
        if (!loadedClasses.isEmpty()) {
            for (String className : loadedClasses) {
                if (!listModel.contains(className)) {
                    listModel.addElement(className);
                }
            }
        } else {
            JOptionPane.showMessageDialog(frame, "Cannot access loaded classes!", "Classes Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void decompileClass() {
        String selectedClass = classList.getSelectedValue();
        if (selectedClass != null) {
            JFrame decompileFrame = new JFrame("Decompiled Code - " + selectedClass);
            decompileFrame.setSize(500, 400);
            decompileFrame.setLayout(new BorderLayout());

            JTextPane codeArea = new JTextPane();
            StyledDocument doc = codeArea.getStyledDocument();
            ColorUtils.addStylesToDocument(doc);

            try {
                String code = classUtil.decompileClass(classUtil.findClassURL(selectedClass));
                ColorUtils.applySyntaxHighlighting(code, doc);
            } catch (IOException | InterruptedException | URISyntaxException e) {
                codeArea.setText("Can't decompile this class!");
            }

            codeArea.setEditable(false);
            codeArea.setBackground(backgroundColor);
            codeArea.setForeground(textColor);

            decompileFrame.add(new JScrollPane(codeArea), BorderLayout.CENTER);

            JButton closeButton = new JButton("Close");
            closeButton.addActionListener(e -> decompileFrame.dispose());
            decompileFrame.add(closeButton, BorderLayout.SOUTH);
            closeButton.setBackground(buttonColor);
            closeButton.setForeground(buttonTextColor);

            decompileFrame.setVisible(true);
        } else {
            JOptionPane.showMessageDialog(frame, "Please select a class from the list.", "No Class Selected", JOptionPane.WARNING_MESSAGE);
        }
    }

    private void dumpClasses() {
        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setDialogTitle("Select Directory to Save Classes");
        fileChooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);

        int result = fileChooser.showSaveDialog(frame);
        if (result == JFileChooser.APPROVE_OPTION) {
            File selectedDirectory = fileChooser.getSelectedFile();
            List<String> loadedClasses = classUtil.getLoadedClasses();
            if (!loadedClasses.isEmpty()) {
                for (String className : loadedClasses) {
                    try {
                        classUtil.dumpClass(className, selectedDirectory);
                    } catch (IOException e) {
                        System.out.println("[JPI Class Dumper] Ignoring unreachable class: " + className);
                    }
                }
                JOptionPane.showMessageDialog(frame, "Classes dumped successfully!", "Success", JOptionPane.INFORMATION_MESSAGE);
            }else{
                JOptionPane.showMessageDialog(frame, "Cannot access loaded classes!", "Classes Error", JOptionPane.ERROR_MESSAGE);
            }

        }
    }
}
