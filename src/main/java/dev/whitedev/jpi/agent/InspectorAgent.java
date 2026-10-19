package dev.whitedev.jpi.agent;

import java.lang.instrument.Instrumentation;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import dev.whitedev.jpi.agent.jvmti.NativeJvmtiBridge;

public final class InspectorAgent {
    private static volatile AgentServer activeServer;
    private static volatile ClassRegistry classRegistry;
    private static boolean nativeBootstrap;
    private static Thread nativeShutdownHook;

    private InspectorAgent() {}

    public static void premain(String options, Instrumentation instrumentation) throws Exception {
        agentmain(options, instrumentation);
    }

    public static synchronized void agentmain(String options, Instrumentation instrumentation) throws Exception {
        if (nativeBootstrap) throw new IOException("A native JPI communication deployment already owns this agent lifecycle");
        if (classRegistry == null) {
            ClassRegistry registry = new ClassRegistry();
            instrumentation.addTransformer(registry, instrumentation.isRetransformClassesSupported());
            classRegistry = registry;
        }
        start(new AgentServer(AgentOptions.parse(options), instrumentation, classRegistry));
    }

    public static synchronized void nativeAgentMain(String options) throws Exception {
        if (activeServer != null) throw new IOException("A JPI server already owns this agent lifecycle");
        Map<String, String> values = new HashMap<String, String>();
        for (String entry : options.split(";")) {
            int separator = entry.indexOf('=');
            if (separator > 0 && values.put(entry.substring(0, separator), entry.substring(separator + 1)) != null)
                throw new IOException("Duplicate native communication option");
        }
        if (!"true".equals(values.get("jpi.bootstrap"))) throw new IOException("Native communication was not provisioned");
        AgentOptions parsed = AgentOptions.parse(options);
        NativeJvmtiBridge.connect(values.get("jpi.library"), values.get("jpi.sha256"));
        ClassRegistry registry = new ClassRegistry();
        AgentServer server = new AgentServer(parsed, RuntimeAccess.jvmti(), registry);
        Thread shutdown = new Thread(new Runnable() {
            @Override public void run() { shutdownNativeAgent(); }
        }, "jpi-native-shutdown");
        boolean hookAdded = false;
        try {
            server.prepare();
            if (nativeShutdownHook == null) {
                Runtime.getRuntime().addShutdownHook(shutdown);
                nativeShutdownHook = shutdown;
                hookAdded = true;
            }
            nativeBootstrap = true;
            classRegistry = registry;
            start(server);
        } catch (Throwable error) {
            server.close();
            activeServer = null;
            nativeBootstrap = false;
            if (hookAdded) {
                Runtime.getRuntime().removeShutdownHook(shutdown);
                nativeShutdownHook = null;
            }
            throw error;
        }
    }

    public static synchronized boolean nativeCommunicationAvailable() {
        return nativeBootstrap && activeServer == null;
    }

    private static synchronized void shutdownNativeAgent() {
        if (activeServer != null) { activeServer.close(); activeServer = null; }
    }

    static synchronized void serverStopped(AgentServer server) {
        if (activeServer == server) activeServer = null;
    }

    private static void start(AgentServer server) throws Exception {
        if (activeServer != null) { activeServer.close(); activeServer = null; }
        try { server.prepare(); }
        catch (Throwable error) { server.close(); throw error; }
        activeServer = server;
        Thread thread = new Thread(server, "jpi-agent-session");
        thread.setDaemon(true);
        thread.setContextClassLoader(InspectorAgent.class.getClassLoader());
        try { thread.start(); }
        catch (Throwable error) { server.close(); activeServer = null; throw error; }
    }
}
