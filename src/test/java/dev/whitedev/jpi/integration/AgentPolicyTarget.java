package dev.whitedev.jpi.integration;

import com.sun.management.HotSpotDiagnosticMXBean;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.management.ManagementFactory;

public final class AgentPolicyTarget {
    public static void main(String[] arguments) throws Exception {
        System.out.println("READY\t" + ProcessHandle.current().pid());
        System.out.flush();
        BufferedReader input = new BufferedReader(new InputStreamReader(System.in));
        String command;
        while ((command = input.readLine()) != null) {
            String response;
            if (command.equals("exit")) return;
            if (command.equals("policy")) {
                HotSpotDiagnosticMXBean bean = ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class);
                response = bean.getVMOption("DisableAttachMechanism").getValue() + ","
                        + bean.getVMOption("EnableDynamicAgentLoading").getValue();
            } else if (command.equals("native")) {
                try {
                    Class<?> bridge = Class.forName("dev.whitedev.jpi.agent.jvmti.NativeJvmtiBridge");
                    String status = (String) bridge.getMethod("status").invoke(null);
                    response = status.startsWith("backend=JVMTI\n") ? "bound" : "not-bound";
                } catch (ClassNotFoundException absent) { response = "no-jpi-classes"; }
            } else response = "pong";
            System.out.println("ANSWER\t" + response);
            System.out.flush();
        }
    }
}
