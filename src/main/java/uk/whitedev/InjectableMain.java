package uk.whitedev;

import uk.whitedev.classes.ClassCheckerGui;
import uk.whitedev.injector.InjectorGui;
import uk.whitedev.memory.MemoryEditorGUI;
import uk.whitedev.utils.ColorUtils;
import uk.whitedev.utils.ProcessUtil;

import javax.swing.*;
import java.awt.*;
import java.io.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.BadLocationException;
import javax.swing.text.StyledDocument;
import javax.tools.*;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;

public class InjectableMain extends JFrame {
    private final JTextPane codeArea;
    private final JTextArea outputArea;
    private boolean isApplyingHighlighting = false;

    public InjectableMain() {
        setTitle("Dynamic Java Code Executor [Java Process Inspector]");
        setSize(800, 400);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLayout(new BorderLayout());

        JPanel centerPanel = new JPanel();
        centerPanel.setLayout(new GridLayout(1, 2));
        centerPanel.setBackground(Color.DARK_GRAY);

        codeArea = new JTextPane();
        StyledDocument doc = codeArea.getStyledDocument();
        ColorUtils.addStylesToDocument(doc);
        codeArea.setText(
                "import java.io.PrintStream;\n" +
                        "public class DynamicClass {\n" +
                        "    public static void execute(PrintStream out) {\n" +
                        "       //Your code here (don't change classname and main method (execute))\n" +
                        "    }\n" +
                        "}");
        ColorUtils.applySyntaxHighlighting(codeArea.getText(), doc);
        codeArea.setBackground(new Color(50, 50, 50));
        codeArea.setForeground(Color.WHITE);
        codeArea.setCaretColor(Color.WHITE);
        centerPanel.add(new JScrollPane(codeArea));

        outputArea = new JTextArea("####### JAVA CODE OUTPUT #######");
        outputArea.setEditable(false);
        outputArea.setBackground(new Color(50, 50, 50));
        outputArea.setForeground(Color.WHITE);
        centerPanel.add(new JScrollPane(outputArea));

        doc.addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                handleTextChanged(e, EditFunc.INSERT);
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                handleTextChanged(e, EditFunc.REMOVE);
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                handleTextChanged(e, EditFunc.INSERT);
            }
        });

        add(centerPanel, BorderLayout.CENTER);

        JPanel buttonPanel = getjPanel();
        add(buttonPanel, BorderLayout.SOUTH);

        getContentPane().setBackground(Color.DARK_GRAY);
    }

    private JPanel getjPanel() {
        JPanel buttonPanel = new JPanel(new BorderLayout());
        buttonPanel.setBackground(Color.DARK_GRAY);

        JPanel buttonSubPanel = new JPanel();
        buttonSubPanel.setLayout(new FlowLayout(FlowLayout.CENTER, 20, 10));
        buttonSubPanel.setBackground(Color.DARK_GRAY);

        JButton runButton = new JButton("Run Code");
        runButton.addActionListener(e -> executeCode(codeArea.getText()));
        runButton.setBackground(Color.GRAY);
        runButton.setForeground(Color.WHITE);

        JButton memoryButton = new JButton("Open Memory Editor");
        memoryButton.addActionListener(e -> new MemoryEditorGUI().runMemoryEditGui());
        memoryButton.setBackground(Color.GRAY);
        memoryButton.setForeground(Color.WHITE);

        JButton injectButton = new JButton("Inject DLL");
        injectButton.addActionListener(e -> new InjectorGui().runInjectorGui());
        injectButton.setBackground(Color.GRAY);
        injectButton.setForeground(Color.WHITE);

        JButton classesButton = new JButton("Check Loaded Classes");
        classesButton.addActionListener(e -> new ClassCheckerGui().runClassEditGui());
        classesButton.setBackground(Color.GRAY);
        classesButton.setForeground(Color.WHITE);

        JButton pidButton = new JButton("Show Pid");
        pidButton.addActionListener(e -> {
            JOptionPane.showMessageDialog(JOptionPane.getRootFrame(), "Pid: " + ProcessUtil.getProcessPid(), "Process pid", JOptionPane.INFORMATION_MESSAGE);
        });
        pidButton.setBackground(Color.GRAY);
        pidButton.setForeground(Color.WHITE);

        buttonSubPanel.add(runButton);
        buttonSubPanel.add(memoryButton);
        buttonSubPanel.add(injectButton);
        buttonSubPanel.add(classesButton);
        buttonSubPanel.add(pidButton);

        buttonPanel.add(buttonSubPanel, BorderLayout.CENTER);

        JLabel authorLabel = new JLabel("Author: 0WhiteDev (https://github.com/0WhiteDev)");
        authorLabel.setForeground(Color.WHITE);
        buttonPanel.add(authorLabel, BorderLayout.SOUTH);

        return buttonPanel;
    }

    private void executeCode(String code) {
        File tempDir;
        try {
            tempDir = Files.createTempDirectory("dynamic").toFile();
            tempDir.deleteOnExit();
        } catch (IOException e) {
            outputArea.setText("Error creating temporary directory: " + e.getMessage());
            return;
        }

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        StringWriter compilationOutput = new StringWriter();
        PrintWriter compilationWriter = new PrintWriter(compilationOutput);

        SimpleJavaFileObject file = new JavaSourceFromString("DynamicClass", code);
        StandardJavaFileManager fileManager = compiler.getStandardFileManager(null, null, null);
        Iterable<? extends JavaFileObject> compilationUnits = Collections.singletonList(file);
        Iterable<String> options = Arrays.asList("-d", tempDir.getAbsolutePath());
        JavaCompiler.CompilationTask task = compiler.getTask(compilationWriter, fileManager, null, options, null, compilationUnits);

        boolean success = task.call();
        if (success) {
            try {
                InjectClassLoader classLoader = new InjectClassLoader(tempDir.toURI().toURL());
                Class<?> dynamicClass = classLoader.loadClass("DynamicClass");
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                PrintStream ps = new PrintStream(baos);
                System.setOut(ps);

                dynamicClass.getMethod("execute", PrintStream.class).invoke(null, ps);

                ps.flush();
                outputArea.setText(baos.toString());
                System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.out)));

            } catch (Exception e) {
                outputArea.setText("Error during execution: " + e + "\n" + getStackTrace(e));
            }
        } else {
            outputArea.setText("Compilation failed:\n" + compilationOutput);
        }
    }

    private String getStackTrace(Throwable t) {
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }

    private void handleTextChanged(DocumentEvent e, EditFunc editFunc) {
        if (isApplyingHighlighting) return;
        int caretPosition = codeArea.getCaretPosition();
        SwingUtilities.invokeLater(() -> {
            StyledDocument doc = (StyledDocument) e.getDocument();
            try {
                String currentText = doc.getText(0, doc.getLength());
                isApplyingHighlighting = true;
                ColorUtils.applySyntaxHighlighting(currentText, doc);
            } catch (BadLocationException ex) {
                ex.printStackTrace();
            } finally {
                isApplyingHighlighting = false;
                SwingUtilities.invokeLater(() -> {
                    codeArea.setCaretPosition(Math.min(caretPosition + (editFunc == EditFunc.INSERT ? 1 : -1), doc.getLength()));
                });
            }
        });
    }

    private enum EditFunc{
        INSERT, REMOVE
    }

    public static void main(String[] args) {
        new Thread(() -> new InjectableMain().setVisible(true)).start();
    }
}