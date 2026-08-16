package dev.whitedev.jpi.ui;

import dev.whitedev.jpi.attach.AttachService;
import dev.whitedev.jpi.attach.CommandLineTokenizer;
import dev.whitedev.jpi.attach.InspectorSession;
import dev.whitedev.jpi.attach.JvmDescriptor;
import dev.whitedev.jpi.attach.JvmDiscovery;
import dev.whitedev.jpi.deobfuscation.DeobfuscationWorkspace;
import dev.whitedev.jpi.nativeaccess.WindowsNativeAccess;
import dev.whitedev.jpi.nativeaccess.WindowsNetworkAccess;
import dev.whitedev.jpi.ui.analysis.BytecodeCfgPanel;
import dev.whitedev.jpi.ui.browser.ClassesPanel;
import dev.whitedev.jpi.ui.nativeview.DllPanel;
import dev.whitedev.jpi.ui.nativeview.MemoryPanel;
import dev.whitedev.jpi.ui.nativeview.NativeSymbolsPanel;
import dev.whitedev.jpi.ui.nativeview.NetworkPanel;
import dev.whitedev.jpi.ui.system.EnvironmentPanel;
import dev.whitedev.jpi.ui.system.OverviewPanel;
import dev.whitedev.jpi.ui.tracing.ApiHooksPanel;
import dev.whitedev.jpi.ui.tracing.LiveTracerPanel;
import dev.whitedev.jpi.ui.tracing.XrefsPanel;
import dev.whitedev.jpi.ui.workspace.DeobfuscationWorkspacePanel;
import dev.whitedev.jpi.ui.workspace.ExecutorPanel;
import dev.whitedev.jpi.ui.inspection.ConstantSearchPanel;
import dev.whitedev.jpi.ui.inspection.FieldsPanel;
import dev.whitedev.jpi.ui.inspection.HeapObjectPanel;
import com.formdev.flatlaf.extras.FlatSVGIcon;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.datatransfer.StringSelection;
import java.io.File;
import java.net.URI;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class MainFrame extends JFrame {
    private final JvmDiscovery discovery = new JvmDiscovery();
    private final AttachService attachService = new AttachService();
    private final JComboBox<JvmDescriptor> targets = new JComboBox<>();
    private final JButton attach = Ui.primaryButton("Attach");
    private final JButton launchEarly = Ui.secondaryButton("Launch with early agent...");
    private final JButton manualEarly = Ui.secondaryButton("Manual agent...");
    private final JButton disconnect = Ui.secondaryButton("Disconnect");
    private final JLabel status = new JLabel("Not attached");
    private final CardLayout cardLayout = new CardLayout();
    private final JPanel cards = new JPanel(cardLayout);
    private final Map<String, JButton> navigation = new LinkedHashMap<>();
    private final List<SessionAware> views;
    private InspectorSession session;

    public MainFrame() {
        super("Java Process Inspector");
        setIconImage(new FlatSVGIcon("assets/logo.svg", 64, 64).getImage());
        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        setMinimumSize(new Dimension(1024, 700));
        Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
        setSize(Math.min(1280, screen.width - 70), Math.min(820, screen.height - 90));
        setLocationRelativeTo(null);

        DeobfuscationWorkspace mappingWorkspace = new DeobfuscationWorkspace();
        OverviewPanel overview = new OverviewPanel();
        LiveTracerPanel tracer = new LiveTracerPanel(mappingWorkspace);
        XrefsPanel xrefs = new XrefsPanel(mappingWorkspace);
        BytecodeCfgPanel cfg = new BytecodeCfgPanel(mappingWorkspace);
        ApiHooksPanel apiHooks = new ApiHooksPanel(mappingWorkspace, xrefs, () -> selectView("Xrefs"));
        ClassesPanel classes = new ClassesPanel(mappingWorkspace, tracer, xrefs, cfg,
                () -> selectView("Live tracer"), () -> selectView("Xrefs"), () -> selectView("Bytecode CFG"));
        DeobfuscationWorkspacePanel deobfuscation = new DeobfuscationWorkspacePanel(mappingWorkspace);
        ExecutorPanel executor = new ExecutorPanel(mappingWorkspace);
        FieldsPanel fields = new FieldsPanel(mappingWorkspace);
        HeapObjectPanel heapObjects = new HeapObjectPanel(mappingWorkspace);
        EnvironmentPanel environment = new EnvironmentPanel();
        ConstantSearchPanel constantSearch = new ConstantSearchPanel(mappingWorkspace);
        WindowsNativeAccess windows = new WindowsNativeAccess();
        NetworkPanel network = new NetworkPanel(new WindowsNetworkAccess());
        MemoryPanel memory = new MemoryPanel(windows);
        DllPanel dll = new DllPanel(windows);
        NativeSymbolsPanel nativeSymbols = new NativeSymbolsPanel();
        views = Arrays.asList(overview, classes, tracer, apiHooks, xrefs, cfg, deobfuscation,
                constantSearch, executor, fields, environment, network, heapObjects, nativeSymbols, memory, dll);

        addCard("Overview", overview);
        addCard("Loaded classes", classes);
        addCard("Live tracer", tracer);
        addCard("API hooks", apiHooks);
        addCard("Xrefs", xrefs);
        addCard("Bytecode CFG", cfg);
        addCard("Deobfuscation", deobfuscation);
        addCard("Constant search", constantSearch);
        addCard("Code executor", executor);
        addCard("Static fields", fields);
        addCard("Heap objects", heapObjects);
        addCard("Native symbols", nativeSymbols);
        addCard("VM environment", environment);
        addCard("Network activity", network);
        addCard("Memory scanner", memory);
        addCard("DLL loader", dll);

        JPanel content = new JPanel(new BorderLayout(0, 12));
        content.setBorder(new EmptyBorder(14, 16, 16, 16));
        content.add(connectionBar(), BorderLayout.NORTH);
        content.add(cards, BorderLayout.CENTER);

        JPanel root = new JPanel(new BorderLayout());
        root.add(sidebar(), BorderLayout.WEST);
        root.add(content, BorderLayout.CENTER);
        setContentPane(root);

        attach.addActionListener(e -> connect());
        launchEarly.addActionListener(e -> launchWithEarlyAgent());
        manualEarly.addActionListener(e -> prepareManualEarlyAgent());
        disconnect.addActionListener(e -> disconnect());
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosed(WindowEvent event) { disconnect(); }
        });
        setSession(null);
        selectView("Overview");
        refreshTargets();
    }

    private void addCard(String name, JComponent component) {
        cards.add(component, name);
    }

    private JPanel sidebar() {
        JPanel sidebar = new JPanel();
        sidebar.setBackground(Ui.SIDEBAR);
        sidebar.setPreferredSize(new Dimension(184, 0));
        sidebar.setBorder(new EmptyBorder(16, 6, 16, 6));
        sidebar.setLayout(new BoxLayout(sidebar, BoxLayout.Y_AXIS));

        JPanel brand = new JPanel(new BorderLayout(12, 0));
        brand.setOpaque(false);
        brand.setAlignmentX(Component.LEFT_ALIGNMENT);
        brand.setMaximumSize(new Dimension(Integer.MAX_VALUE, 48));
        JLabel mark = new JLabel(new FlatSVGIcon("assets/logo.svg", 40, 40));
        mark.setPreferredSize(new Dimension(42, 42));
        JPanel brandText = new JPanel();
        brandText.setOpaque(false);
        brandText.setLayout(new BoxLayout(brandText, BoxLayout.Y_AXIS));
        JLabel name = new JLabel("JPI");
        name.setFont(name.getFont().deriveFont(Font.BOLD, 15f));
        JLabel inspector = new JLabel("Process Inspector");
        inspector.setForeground(Ui.MUTED);
        brandText.add(name);
        brandText.add(inspector);
        brand.add(mark, BorderLayout.WEST);
        brand.add(brandText, BorderLayout.CENTER);
        sidebar.add(brand);
        sidebar.add(Box.createVerticalStrut(12));

        JLabel workspace = new JLabel("WORKSPACE");
        workspace.setForeground(Ui.MUTED);
        workspace.setFont(workspace.getFont().deriveFont(Font.BOLD, 11f));
        workspace.setBorder(new EmptyBorder(0, 10, 8, 0));
        workspace.setAlignmentX(Component.LEFT_ALIGNMENT);
        sidebar.add(workspace);
        addNavigation(sidebar, "Overview");
        addNavigation(sidebar, "Loaded classes");
        addNavigation(sidebar, "Live tracer");
        addNavigation(sidebar, "API hooks");
        addNavigation(sidebar, "Xrefs");
        addNavigation(sidebar, "Bytecode CFG");
        addNavigation(sidebar, "Deobfuscation");
        addNavigation(sidebar, "Constant search");
        addNavigation(sidebar, "Code executor");
        addNavigation(sidebar, "Static fields");
        addNavigation(sidebar, "VM environment");
        addNavigation(sidebar, "Network activity");
        sidebar.add(Box.createVerticalStrut(10));
        JLabel advanced = new JLabel("ADVANCED");
        advanced.setForeground(Ui.MUTED);
        advanced.setFont(advanced.getFont().deriveFont(Font.BOLD, 11f));
        advanced.setBorder(new EmptyBorder(0, 10, 8, 0));
        advanced.setAlignmentX(Component.LEFT_ALIGNMENT);
        sidebar.add(advanced);
        addNavigation(sidebar, "Heap objects");
        addNavigation(sidebar, "Native symbols");
        addNavigation(sidebar, "Memory scanner");
        addNavigation(sidebar, "DLL loader");
        sidebar.add(Box.createVerticalGlue());
        JPanel footer = new JPanel();
        footer.setOpaque(false);
        footer.setLayout(new BoxLayout(footer, BoxLayout.Y_AXIS));
        footer.setBorder(new EmptyBorder(0, 10, 0, 0));
        footer.setAlignmentX(Component.LEFT_ALIGNMENT);
        footer.setMaximumSize(new Dimension(Integer.MAX_VALUE, 42));

        JLabel protocol = new JLabel("Protocol v1");
        protocol.setForeground(Ui.MUTED);
        protocol.setAlignmentX(Component.LEFT_ALIGNMENT);
        footer.add(protocol);

        JPanel authorRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        authorRow.setOpaque(false);
        authorRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel createdBy = new JLabel("Created by: ");
        createdBy.setForeground(Ui.MUTED);
        JLabel author = new JLabel("<html><u>0WhiteDev</u></html>");
        author.setForeground(Ui.TEXT);
        author.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        author.setToolTipText("github.com/0WhiteDev");
        author.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent event) { openAuthorProfile(); }
        });
        authorRow.add(createdBy);
        authorRow.add(author);
        footer.add(authorRow);
        sidebar.add(footer);
        return sidebar;
    }

    private void openAuthorProfile() {
        try {
            if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                throw new IllegalStateException("Opening links is not supported on this system");
            }
            Desktop.getDesktop().browse(URI.create("https:" + "/" + "/github.com/0WhiteDev"));
        } catch (Exception error) {
            Ui.error(this, error);
        }
    }

    private void addNavigation(JPanel sidebar, final String name) {
        JButton button = Ui.navigationButton(name);
        button.addActionListener(e -> selectView(name));
        navigation.put(name, button);
        sidebar.add(button);
        sidebar.add(Box.createVerticalStrut(1));
    }

    private void selectView(String name) {
        cardLayout.show(cards, name);
        for (Map.Entry<String, JButton> entry : navigation.entrySet()) {
            boolean selected = entry.getKey().equals(name);
            entry.getValue().setBackground(selected ? Ui.SURFACE_LIGHT : Ui.SIDEBAR);
            entry.getValue().setForeground(selected ? Color.WHITE : Ui.MUTED);
        }
    }

    private JPanel connectionBar() {
        JPanel bar = Ui.card(new BorderLayout(0, 10));
        bar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Ui.BORDER),
                new EmptyBorder(10, 14, 10, 14)));
        JPanel top = new JPanel(new BorderLayout(16, 0));
        top.setOpaque(false);
        JPanel label = new JPanel();
        label.setOpaque(false);
        label.setLayout(new BoxLayout(label, BoxLayout.Y_AXIS));
        JLabel heading = new JLabel("Target JVM");
        heading.setFont(heading.getFont().deriveFont(Font.BOLD, 14f));
        JLabel hint = new JLabel("Select a local Java process and start an inspection session");
        hint.setForeground(Ui.MUTED);
        label.add(heading);
        label.add(hint);

        targets.setPreferredSize(new Dimension(360, 36));
        targets.putClientProperty("JComponent.roundRect", true);
        JButton refresh = Ui.secondaryButton("Refresh");
        refresh.addActionListener(e -> refreshTargets());

        status.setOpaque(true);
        status.setHorizontalAlignment(SwingConstants.CENTER);
        status.setBorder(new EmptyBorder(7, 12, 7, 12));
        status.putClientProperty("FlatLaf.style", "arc: 999");
        top.add(label, BorderLayout.WEST);
        top.add(status, BorderLayout.EAST);

        JPanel selector = new JPanel(new BorderLayout(10, 0));
        selector.setOpaque(false);
        selector.add(targets, BorderLayout.CENTER);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        actions.setOpaque(false);
        actions.add(refresh);
        actions.add(manualEarly);
        actions.add(launchEarly);
        actions.add(attach);
        actions.add(disconnect);
        selector.add(actions, BorderLayout.EAST);
        bar.add(top, BorderLayout.NORTH);
        bar.add(selector, BorderLayout.SOUTH);
        return bar;
    }

    private void refreshTargets() {
        targets.removeAllItems();
        targets.addItem(new JvmDescriptor("", "Discovering JVMs..."));
        Async.run(discovery::discover, found -> {
            targets.removeAllItems();
            for (JvmDescriptor target : found) targets.addItem(target);
            if (found.isEmpty()) targets.addItem(new JvmDescriptor("", "No attachable JVM found"));
        }, error -> Ui.error(this, error));
    }

    private void connect() {
        final JvmDescriptor target = (JvmDescriptor) targets.getSelectedItem();
        if (target == null || target.id().isEmpty()) return;
        attach.setEnabled(false);
        targets.setEnabled(false);
        setStatus("Connecting...", Ui.WARNING, Ui.SURFACE_LIGHT);
        Async.run(() -> attachService.attach(target), connected -> {
            setSession(connected);
            setStatus("Attached  |  PID " + target.id(), Ui.SUCCESS, new Color(22, 62, 52));
        }, error -> {
            setSession(null);
            setStatus("Attach failed", Ui.WARNING, new Color(69, 48, 25));
            Ui.error(this, error);
        });
    }

    private void launchWithEarlyAgent() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Select the executable JAR to launch with JPI before main()");
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        final File targetJar = chooser.getSelectedFile();
        JTextField vmArguments = new JTextField();
        JTextField applicationArguments = new JTextField();
        vmArguments.putClientProperty("JTextField.placeholderText", "-Xmx2g -Dexample=value");
        applicationArguments.putClientProperty("JTextField.placeholderText", "--profile analysis");
        JPanel form = new JPanel(new GridLayout(4, 1, 0, 6));
        form.add(new JLabel("Optional JVM arguments"));
        form.add(vmArguments);
        form.add(new JLabel("Optional application arguments"));
        form.add(applicationArguments);
        if (JOptionPane.showConfirmDialog(this, form, "Launch with early JPI agent",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return;
        final List<String> vmArgs;
        final List<String> appArgs;
        try {
            vmArgs = CommandLineTokenizer.parse(vmArguments.getText());
            appArgs = CommandLineTokenizer.parse(applicationArguments.getText());
        } catch (IllegalArgumentException error) {
            Ui.error(this, error);
            return;
        }
        attach.setEnabled(false);
        launchEarly.setEnabled(false);
        targets.setEnabled(false);
        setStatus("Launching before main()...", Ui.WARNING, Ui.SURFACE_LIGHT);
        Async.run(() -> attachService.launch(targetJar, vmArgs, appArgs), result -> {
            setSession(result.session());
            setStatus("Attached early  |  PID " + result.session().target().id(),
                    Ui.SUCCESS, new Color(60, 60, 60));
            status.setToolTipText("Target output: " + result.logFile().getAbsolutePath());
        }, error -> {
            setSession(null);
            setStatus("Early launch failed", Ui.WARNING, new Color(69, 48, 25));
            Ui.error(this, error);
        });
    }

    private void prepareManualEarlyAgent() {
        final AttachService.PreparedAgentSession prepared;
        try {
            prepared = attachService.prepareEarlyAgent();
        } catch (Exception error) {
            Ui.error(this, error);
            return;
        }
        JTextArea argument = new JTextArea(prepared.jvmArgument(), 3, 68);
        argument.setEditable(false);
        argument.setLineWrap(true);
        argument.setWrapStyleWord(false);
        JTextField pid = new JTextField();
        pid.putClientProperty("JTextField.placeholderText", "Optional PID (enables native/network views)");
        JPanel form = new JPanel(new BorderLayout(0, 10));
        form.add(new JLabel("Add this argument to the target JVM startup options, then start the application:"), BorderLayout.NORTH);
        form.add(Ui.scroll(argument), BorderLayout.CENTER);
        form.add(pid, BorderLayout.SOUTH);
        Object[] options = {"Copy and wait", "Cancel"};
        int choice = JOptionPane.showOptionDialog(this, form, "Manual early-agent connection",
                JOptionPane.DEFAULT_OPTION, JOptionPane.PLAIN_MESSAGE, null, options, options[0]);
        if (choice != 0) {
            prepared.close();
            return;
        }
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(
                new StringSelection(prepared.jvmArgument()), null);
        attach.setEnabled(false);
        launchEarly.setEnabled(false);
        manualEarly.setEnabled(false);
        targets.setEnabled(false);
        setStatus("Waiting for early agent...", Ui.WARNING, Ui.SURFACE_LIGHT);
        String targetPid = pid.getText();
        Async.run(() -> prepared.await(targetPid, "External JVM"), connected -> {
            setSession(connected);
            setStatus("Attached  |  PID " + connected.target().id(), Ui.SUCCESS, new Color(60, 60, 60));
        }, error -> {
            prepared.close();
            setSession(null);
            setStatus("Early-agent timeout", Ui.WARNING, new Color(69, 48, 25));
            Ui.error(this, error);
        });
    }

    private void disconnect() {
        InspectorSession current = session;
        session = null;
        if (current != null) current.close();
        setSession(null);
    }

    private void setSession(InspectorSession value) {
        session = value;
        for (SessionAware view : views) view.setSession(value);
        boolean connected = value != null;
        attach.setEnabled(!connected);
        launchEarly.setEnabled(!connected);
        manualEarly.setEnabled(!connected);
        disconnect.setEnabled(connected);
        targets.setEnabled(!connected);
        if (!connected) setStatus("Not attached", Ui.MUTED, Ui.SURFACE_LIGHT);
    }

    private void setStatus(String text, Color foreground, Color background) {
        status.setText(text);
        status.setForeground(foreground);
        status.setBackground(background);
    }
}
