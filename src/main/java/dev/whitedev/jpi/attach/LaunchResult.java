package dev.whitedev.jpi.attach;

import java.io.File;

public final class LaunchResult {
    private final InspectorSession session;
    private final Process process;
    private final File logFile;

    LaunchResult(InspectorSession session, Process process, File logFile) {
        this.session = session;
        this.process = process;
        this.logFile = logFile;
    }

    public InspectorSession session() { return session; }
    public Process process() { return process; }
    public File logFile() { return logFile; }
}
