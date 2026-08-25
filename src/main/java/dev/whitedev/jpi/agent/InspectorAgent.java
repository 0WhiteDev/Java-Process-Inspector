package dev.whitedev.jpi.agent;

import java.lang.instrument.Instrumentation;

public final class InspectorAgent {
    private static volatile AgentServer activeServer;
    private static volatile ClassRegistry classRegistry;

    private InspectorAgent() {}

    public static void premain(String options, Instrumentation instrumentation) throws Exception {
        agentmain(options, instrumentation);
    }

    public static synchronized void agentmain(String options, Instrumentation instrumentation) throws Exception {
        if (classRegistry == null) {
            ClassRegistry registry = new ClassRegistry();
            instrumentation.addTransformer(registry, instrumentation.isRetransformClassesSupported());
            classRegistry = registry;
        }
        if (activeServer != null) activeServer.close();
        AgentServer server = new AgentServer(AgentOptions.parse(options), instrumentation, classRegistry);
        server.prepare();
        activeServer = server;
        Thread thread = new Thread(server, "jpi-agent-session");
        thread.setDaemon(true);
        thread.setContextClassLoader(InspectorAgent.class.getClassLoader());
        thread.start();
    }
}
