package dev.whitedev.jpi.attach;

import dev.whitedev.jpi.attach.jvmti.NativeAgentArtifact;
import java.io.IOException;
import java.util.Locale;
import java.nio.charset.StandardCharsets;

public final class ProcessInjector {
    private static boolean loaded;

    public static boolean isSupported() {
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        String os = System.getProperty("os.name", "");
        return (os.equals("Linux") || os.startsWith("Windows"))
                && (arch.equals("amd64") || arch.equals("x86_64"));
    }

    public void inject(String pid, NativeAgentArtifact agent) throws IOException {
        inject(pid, agent, null);
    }

    public void injectCommunicationAgent(String pid, NativeAgentArtifact agent, String options) throws IOException {
        if (options == null || !options.startsWith("jpi.bootstrap=true;") || options.indexOf('\0') >= 0
                || options.getBytes(StandardCharsets.UTF_8).length > 16384)
            throw new IOException("Valid bounded native communication options are required");
        inject(pid, agent, options);
    }

    private void inject(String pid, NativeAgentArtifact agent, String options) throws IOException {
        if (pid == null || !pid.matches("[1-9][0-9]*")) throw new IOException("A positive numeric PID is required");
        long value;
        try { value = Long.parseLong(pid); }
        catch (NumberFormatException error) { throw new IOException("PID is out of range", error); }
        if (value > Integer.MAX_VALUE || value == ProcessHandle.current().pid()) throw new IOException("Invalid target PID or self attachment");
        if (!isSupported()) throw new IOException("Native injection supports Windows and Linux x86_64 only; use Attach API on this platform");
        if (options == null) JvmAgentPolicy.inspect(pid).rejectObservedRestrictions(pid);
        if (agent == null) throw new IOException("A verified native agent artifact is required");
        NativeAgentArtifact verified = NativeAgentArtifact.verify(agent.library(), agent.sha256());
        load();
        synchronized (ProcessInjector.class) {
            byte[] path = verified.library().toString().getBytes(StandardCharsets.UTF_8);
            if (options == null) inject0((int) value, path);
            else injectCommunication0((int) value, path, options.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static synchronized void load() throws IOException {
        if (loaded) return;
        NativeAgentArtifact backend = NativeAgentArtifact.extractBundled("process_injector");
        try { System.load(backend.library().toString()); loaded = true; }
        catch (LinkageError error) { throw new IOException("Could not load native process injector", error); }
    }

    private static native void inject0(int pid, byte[] path) throws IOException;
    private static native void injectCommunication0(int pid, byte[] path, byte[] options) throws IOException;
}
