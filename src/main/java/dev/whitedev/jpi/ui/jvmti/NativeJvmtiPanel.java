package dev.whitedev.jpi.ui.jvmti;

import dev.whitedev.jpi.attach.ImprovedAttachService;
import dev.whitedev.jpi.attach.InspectorSession;
import dev.whitedev.jpi.attach.jvmti.NativeAgentArtifact;
import dev.whitedev.jpi.protocol.Operation;
import dev.whitedev.jpi.ui.Async;
import dev.whitedev.jpi.ui.SessionAware;
import dev.whitedev.jpi.ui.Ui;
import dev.whitedev.jpi.ui.layout.WrappingFlowLayout;

import javax.swing.*;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.nio.file.Path;
import java.util.List;

public final class NativeJvmtiPanel extends JPanel implements SessionAware {
    private final ImprovedAttachService attachService;
    private final Runnable openClasses;
    private final JTextField path = new JTextField();
    private final JTextField digest = new JTextField();
    private final JTextField heapClass = new JTextField("java.lang.String");
    private final JSpinner heapLimit = new JSpinner(new SpinnerNumberModel(100000, 1, 10000000, 10000));
    private final JTextArea output = Ui.outputArea();
    private final RawMemoryPanel rawMemory = new RawMemoryPanel();
    private final NativeClassCapturePanel classCapture = new NativeClassCapturePanel();
    private final JButton bundled = Ui.secondaryButton("Use bundled library");
    private final JButton external = Ui.secondaryButton("Choose external library...");
    private final JButton load = Ui.primaryButton("Load through JVM / Bind");
    private final JButton inject = Ui.secondaryButton("Load with native injection");
    private final JButton bind = Ui.secondaryButton("Bind startup agent");
    private final JButton refresh = Ui.secondaryButton("Refresh capabilities");
    private final JButton classes = Ui.secondaryButton("Browse with JVMTI");
    private final JButton heap = Ui.secondaryButton("Inspect class heap");
    private InspectorSession session;
    private boolean busy;
    private boolean nativeReady;
    private long generation;

    public NativeJvmtiPanel(ImprovedAttachService attachService, Runnable openClasses) {
        super(new BorderLayout(0, 12));
        this.attachService = attachService;
        this.openClasses = openClasses;
        setOpaque(false);
        JPanel top = new JPanel(new BorderLayout(0, 8));
        top.setOpaque(false);
        top.add(Ui.sectionHeader("Native JVMTI", "Optional native backend loaded through supported JVM agent interfaces.", null), BorderLayout.NORTH);
        JPanel fields = new JPanel(new GridLayout(0, 1, 0, 4));
        fields.add(new JLabel("Absolute native library path in the target filesystem"));
        fields.add(path);
        fields.add(new JLabel("Expected SHA-256 from a trusted build or release"));
        fields.add(digest);
        fields.add(new JLabel("Class identifier for native heap inspection"));
        fields.add(heapClass);
        fields.add(new JLabel("Maximum instances to aggregate"));
        fields.add(heapLimit);
        top.add(fields, BorderLayout.CENTER);
        JPanel actions = new JPanel(new WrappingFlowLayout(FlowLayout.LEFT, 8, 3));
        JButton copy = Ui.secondaryButton("Copy startup JVM argument");
        for (JButton button : List.of(bundled, external, load, inject, bind, refresh, classes, heap, copy)) actions.add(button);
        top.add(actions, BorderLayout.SOUTH);
        bundled.addActionListener(event -> selectBundled());
        external.addActionListener(event -> selectExternal());
        load.addActionListener(event -> load(false));
        inject.addActionListener(event -> load(true));
        bind.addActionListener(event -> bind());
        refresh.addActionListener(event -> refresh());
        classes.addActionListener(event -> openClasses.run());
        heap.addActionListener(event -> inspectHeap());
        copy.addActionListener(event -> {
            String value = path.getText().trim();
            if (value.isEmpty() || value.contains("=") || value.contains("\n") || value.contains("\r")) {
                Ui.error(this, new IllegalArgumentException("Select an absolute library path without equals signs or line breaks"));
                return;
            }
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection("-agentpath:" + value), null);
            output.setText("Copied one JVM argument. Add it to the target's VM options before -jar or its main class.\n"
                    + "Quote the entire argument when passing paths with spaces through a shell.\n"
                    + "Keep the existing JPI -javaagent option for the inspector UI and protocol.\n");
        });
        add(top, BorderLayout.NORTH);
        JTabbedPane details = new JTabbedPane();
        details.addTab("Capabilities", Ui.scroll(output));
        details.addTab("Raw memory", rawMemory);
        details.addTab("Class interception", classCapture);
        add(details, BorderLayout.CENTER);
        output.setText("Instrumentation remains available until the native backend is bound.\n\n"
                + "1. Select the bundled library or a trusted external build.\n"
                + "2. For local targets, load it through the JVM after confirming native code execution.\n"
                + "3. If dynamic loading is unavailable, start the target with -agentpath, then bind that same library and hash.\n"
                + "4. Inspect capabilities, browse classes, patch through JVMTI, or aggregate shallow heap usage for one class.\n\n"
                + "Tunnel sessions only support binding a library already loaded in the remote JVM.\n"
                + "A DLL or shared-library load by itself does not initialize JVMTI; JPI uses Agent_OnAttach or Agent_OnLoad.\n"
                + "Native libraries remain loaded until the target exits. Retransformation can invoke other agents.\n");
        controls();
    }

    @Override public void setSession(InspectorSession value) {
        session = value;
        rawMemory.setSession(value);
        classCapture.setSession(value);
        generation++;
        busy = false;
        nativeReady = false;
        controls();
        if (value != null) output.setText("Target attached. Refresh capabilities to check a backend loaded during early launch, or load and bind it here.\nInstrumentation remains the default.\n");
        else output.setText("Not attached. Native libraries already loaded in a target remain resident until that JVM exits.\n");
    }

    private void selectBundled() {
        if (busy) return;
        if (session != null && session.isTunneled()) {
            Ui.error(this, new IllegalArgumentException("For tunneled targets enter the remote library path and trusted SHA-256. Local extraction is not uploaded."));
            return;
        }
        busy = true;
        controls();
        long epoch = generation;
        Async.run(NativeAgentArtifact::extractBundled, artifact -> {
            if (epoch != generation) return;
            path.setText(artifact.library().toString());
            digest.setText(artifact.sha256());
            busy = false;
            controls();
        }, error -> failed(epoch, error));
    }

    private void selectExternal() {
        if (busy) return;
        JFileChooser chooser = new JFileChooser();
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        path.setText(chooser.getSelectedFile().getAbsolutePath());
        digest.setText("");
        output.setText("Enter the SHA-256 from a trusted source before loading this library.\nA matching hash verifies bytes, not the trustworthiness or signature of the publisher.\n");
    }

    private boolean confirm() {
        return JOptionPane.showConfirmDialog(this,
                "This executes native code inside the target JVM and can crash it.\nThe library cannot be unloaded through JPI; changing it requires a target restart.\nOnly proceed with a library and process you trust and are authorized to inspect.",
                "Load native JVMTI backend", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE) == JOptionPane.OK_OPTION;
    }

    private void load(boolean nativeInjection) {
        InspectorSession current = session;
        if (current == null || current.isTunneled() || busy || !confirm()) return;
        String library = path.getText().trim();
        String sha = digest.getText().trim();
        long epoch = generation;
        busy = true;
        controls();
        Async.run(() -> {
            NativeAgentArtifact artifact = NativeAgentArtifact.verify(Path.of(library), sha);
            return nativeInjection ? attachService.enableNativeBackendWithInjection(current, artifact)
                    : attachService.enableNativeBackend(current, artifact);
        }, result -> completed(epoch, result), error -> failed(epoch, error));
    }

    private void bind() {
        InspectorSession current = session;
        if (current == null || busy || !confirm()) return;
        String payload = path.getText().trim() + "\n" + digest.getText().trim();
        long epoch = generation;
        busy = true;
        controls();
        Async.run(() -> current.requestText(Operation.JVMTI_BIND, payload), result -> completed(epoch, result), error -> failed(epoch, error));
    }

    private void refresh() {
        InspectorSession current = session;
        if (current == null || busy) return;
        long epoch = generation;
        busy = true;
        controls();
        Async.run(() -> current.requestText(Operation.JVMTI_STATUS, ""), result -> completed(epoch, result), error -> failed(epoch, error));
    }

    private void inspectHeap() {
        InspectorSession current = session;
        if (current == null || busy || !nativeReady) return;
        String identifier = heapClass.getText().trim();
        if (identifier.isEmpty()) {
            Ui.error(this, new IllegalArgumentException("Enter a class identifier"));
            return;
        }
        long epoch = generation;
        busy = true;
        controls();
        String payload = identifier + "\n" + ((Number) heapLimit.getValue()).longValue();
        Async.run(() -> current.requestText(Operation.JVMTI_HEAP_STATS, payload), result -> completed(epoch, result), error -> failed(epoch, error));
    }

    private void completed(long epoch, String result) {
        if (epoch != generation) return;
        busy = false;
        output.setText(result);
        output.setCaretPosition(0);
        nativeReady = result.startsWith("backend=JVMTI\n");
        controls();
    }

    private void failed(long epoch, Throwable error) {
        if (epoch != generation) return;
        busy = false;
        controls();
        output.setText("Native backend operation failed: " + error.getMessage() + "\nInstrumentation remains available.\n");
        Ui.error(this, error);
    }

    private void controls() {
        path.setEditable(!busy);
        digest.setEditable(!busy);
        heapClass.setEditable(!busy);
        heapLimit.setEnabled(!busy);
        bundled.setEnabled(!busy && (session == null || !session.isTunneled()));
        external.setEnabled(!busy);
        load.setEnabled(session != null && !session.isTunneled() && !busy);
        inject.setEnabled(session != null && !session.isTunneled() && !busy && dev.whitedev.jpi.attach.ProcessInjector.isSupported());
        bind.setEnabled(session != null && !busy);
        refresh.setEnabled(session != null && !busy);
        classes.setEnabled(session != null && !busy && nativeReady);
        heap.setEnabled(session != null && !busy && nativeReady);
    }
}
