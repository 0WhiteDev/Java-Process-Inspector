package dev.whitedev.jpi.attach;

import java.io.File;
import java.io.IOException;
import dev.whitedev.jpi.attach.jvmti.NativeAgentArtifact;
import dev.whitedev.jpi.protocol.Operation;

public final class ImprovedAttachService extends AttachService {
    public ImprovedAttachService() { super(); }
    public ImprovedAttachService(File agentJar) { super(agentJar); }

    public InspectorSession attachNative(JvmDescriptor target) throws Exception {
        if (target == null) throw new IllegalArgumentException("A local target is required");
        NativeAgentArtifact artifact = NativeAgentArtifact.extractBundled();
        SessionChannel channel = new SessionChannel();
        InspectorSession session = null;
        try {
            new ProcessInjector().injectCommunicationAgent(target.id(), artifact,
                    nativeCommunicationOptions(artifact, channel.options()));
            session = channel.accept(target);
            String info = session.requestText(Operation.SESSION_INFO, "");
            if (!info.startsWith("pid=" + target.id() + "\n")
                    || !info.contains("\ncommunication=native-bootstrap\n")
                    || !info.contains("\nruntime=JVMTI\n"))
                throw new IOException("Native callback does not match the requested JVM runtime");
            return session;
        } catch (Exception error) {
            if (session != null) session.close();
            throw error;
        } finally { channel.close(); }
    }

    public void injectNativeCommunicationAgent(String pid, NativeAgentArtifact artifact, PreparedTunnelAgent prepared) throws Exception {
        NativeAgentArtifact verified = NativeAgentArtifact.verify(artifact.library(), artifact.sha256());
        String options = nativeCommunicationOptions(verified, prepared);
        new ProcessInjector().injectCommunicationAgent(pid, verified, options);
    }

    public String nativePreloadedAgentArgument(NativeAgentArtifact artifact, PreparedTunnelAgent prepared) throws Exception {
        NativeAgentArtifact verified = NativeAgentArtifact.verify(artifact.library(), artifact.sha256());
        return "-agentpath:" + verified.library().toFile().getCanonicalPath() + "=" + nativeCommunicationOptions(verified, prepared);
    }

    public void loadNativeCommunicationAgent(String pid, NativeAgentArtifact artifact, PreparedTunnelAgent prepared) throws Exception {
        NativeAgentArtifact verified = NativeAgentArtifact.verify(artifact.library(), artifact.sha256());
        loadNativeAgent(pid, verified, nativeCommunicationOptions(verified, prepared));
    }

    private String nativeCommunicationOptions(NativeAgentArtifact verified, PreparedTunnelAgent prepared) throws Exception {
        if (prepared == null) throw new IllegalArgumentException("Prepared listener is required");
        dev.whitedev.jpi.agent.AgentOptions options = dev.whitedev.jpi.agent.AgentOptions.parse(prepared.options());
        if (options.mode() != dev.whitedev.jpi.agent.AgentOptions.Mode.LISTEN)
            throw new IllegalArgumentException("A preloaded listener configuration is required");
        return nativeCommunicationOptions(verified, prepared.options());
    }

    public InspectorSession connectNativeAgent(int port, String token, JvmDescriptor target) throws IOException {
        InspectorSession session = connectPreloadedAgent(port, token, target);
        try {
            String info = session.requestText(Operation.SESSION_INFO, "");
            if (!info.contains("\ncommunication=native-bootstrap\n") || !info.contains("\nruntime=JVMTI\n"))
                throw new IOException("Endpoint is not a native JVMTI communication deployment");
            if (!session.requestText(Operation.JVMTI_STATUS, "").startsWith("backend=JVMTI\n"))
                throw new IOException("Native endpoint has no bound JVMTI runtime");
            return session;
        } catch (IOException error) { session.close(); throw error; }
    }

    public String enableNativeBackendWithInjection(InspectorSession session, NativeAgentArtifact artifact) throws IOException {
        if (session == null || session.isTunneled()) throw new IOException("Injection requires a local inspection session");
        NativeAgentArtifact verified = NativeAgentArtifact.verify(artifact.library(), artifact.sha256());
        if (session.requestText(Operation.JVMTI_STATUS, "").startsWith("backend=JVMTI\n")) {
            return session.requestText(Operation.JVMTI_BIND, verified.bindingOptions());
        }
        String info = session.requestText(Operation.SESSION_INFO, "");
        if (info.contains("\nattach_disabled=true\n"))
            throw new AgentLoadingRestrictedException(session.target().id(), "-XX:+DisableAttachMechanism");
        if (info.contains("\ndynamic_agent_loading=false\n"))
            throw new AgentLoadingRestrictedException(session.target().id(), "-XX:-EnableDynamicAgentLoading");
        if (!info.startsWith("pid=" + session.target().id() + "\n")) throw new IOException("Inspection session belongs to a different JVM");
        boolean runtimeAllowsLoading = info.contains("\nattach_disabled=false\n") && info.contains("\ndynamic_agent_loading=true\n");
        loadNativeAgentWithInjection(session.target().id(), verified, runtimeAllowsLoading);
        return session.requestText(Operation.JVMTI_BIND, verified.bindingOptions());
    }

    public void loadNativeAgentWithInjection(String pid, NativeAgentArtifact artifact) throws IOException {
        loadNativeAgentWithInjection(pid, artifact, false);
    }

    private void loadNativeAgentWithInjection(String pid, NativeAgentArtifact artifact, boolean runtimeAllowsLoading) throws IOException {
        JvmAgentPolicy policy = JvmAgentPolicy.inspect(pid);
        policy.rejectObservedRestrictions(pid);
        if (!runtimeAllowsLoading) { loadNativeAgent(pid, artifact); return; }
        if (!ProcessInjector.isSupported()) { loadNativeAgent(pid, artifact); return; }
        try { new ProcessInjector().inject(pid, artifact); }
        catch (AgentLoadingRestrictedException restricted) { throw restricted; }
        catch (IndeterminateInjectionException uncertain) { throw uncertain; }
        catch (IOException injectionError) {
            try { loadNativeAgent(pid, artifact); }
            catch (IOException attachError) {
                IOException combined = new IOException("Native injection and Attach API loading both failed. Injection: "
                        + injectionError.getMessage() + " Attach API: " + attachError.getMessage(), attachError);
                combined.addSuppressed(injectionError);
                throw combined;
            }
        }
    }
}
