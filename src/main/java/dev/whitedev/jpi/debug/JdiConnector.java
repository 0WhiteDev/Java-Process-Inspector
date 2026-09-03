package dev.whitedev.jpi.debug;

import com.sun.jdi.Bootstrap;
import com.sun.jdi.VirtualMachine;
import com.sun.jdi.connect.AttachingConnector;
import com.sun.jdi.connect.Connector;
import com.sun.jdi.connect.LaunchingConnector;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

public final class JdiConnector {
    public VirtualMachine attach(String host, int port) throws Exception {
        if (port < 1 || port > 65535) throw new IllegalArgumentException("JDWP port must be between 1 and 65535");
        AttachingConnector connector = Bootstrap.virtualMachineManager().attachingConnectors().stream()
                .filter(value -> "com.sun.jdi.SocketAttach".equals(value.name())).findFirst()
                .orElseThrow(() -> new IOException("JDI socket attach connector is unavailable"));
        Map<String, Connector.Argument> arguments = connector.defaultArguments();
        arguments.get("hostname").setValue(host == null || host.isBlank() ? "127.0.0.1" : host.trim());
        arguments.get("port").setValue(Integer.toString(port));
        Connector.Argument timeout = arguments.get("timeout");
        if (timeout != null) timeout.setValue("15000");
        return connector.attach(arguments);
    }

    public VirtualMachine attach(long processId) throws Exception {
        if (processId < 1) throw new IllegalArgumentException("Process ID must be positive");
        AttachingConnector connector = Bootstrap.virtualMachineManager().attachingConnectors().stream()
                .filter(value -> "com.sun.jdi.ProcessAttach".equals(value.name())).findFirst()
                .orElseThrow(() -> new IOException("JDI process attach connector is unavailable in this JDK"));
        Map<String, Connector.Argument> arguments = connector.defaultArguments();
        arguments.get("pid").setValue(Long.toString(processId));
        Connector.Argument timeout = arguments.get("timeout");
        if (timeout != null) timeout.setValue("15000");
        try {
            return connector.attach(arguments);
        } catch (Exception error) {
            throw new IOException("Could not attach debugger to PID " + processId
                    + ". The running JVM must already have JDWP enabled with server=y.", error);
        }
    }

    public LaunchResult launch(File jar, List<String> vmArguments, List<String> applicationArguments) throws Exception {
        File target = jar.getCanonicalFile();
        if (!target.isFile() || !target.getName().toLowerCase().endsWith(".jar")) {
            throw new IOException("Select an existing executable JAR");
        }
        LaunchingConnector connector = Bootstrap.virtualMachineManager().defaultConnector();
        Map<String, Connector.Argument> arguments = connector.defaultArguments();
        arguments.get("main").setValue("-jar " + quote(target.getAbsolutePath()) + join(applicationArguments));
        Connector.Argument options = arguments.get("options");
        if (options != null) options.setValue(joinArguments(vmArguments));
        Connector.Argument suspend = arguments.get("suspend");
        if (suspend != null) suspend.setValue("true");
        Connector.Argument home = arguments.get("home");
        if (home != null) home.setValue(System.getProperty("java.home"));
        VirtualMachine vm = connector.launch(arguments);
        drain(vm.process().getInputStream(), "jpi-debug-target-output");
        drain(vm.process().getErrorStream(), "jpi-debug-target-errors");
        return new LaunchResult(vm, vm.process(), target);
    }

    private static String join(List<String> values) {
        String joined = joinArguments(values);
        return joined.isEmpty() ? "" : " " + joined;
    }

    private static String joinArguments(List<String> values) {
        if (values == null || values.isEmpty()) return "";
        StringBuilder output = new StringBuilder();
        for (String value : values) {
            if (output.length() > 0) output.append(' ');
            output.append(quote(value));
        }
        return output.toString();
    }

    private static String quote(String value) {
        if (value == null) return "";
        return value.matches("[A-Za-z0-9_./:=+-]+") ? value
                : '"' + value.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }

    private static void drain(InputStream input, String name) {
        Thread thread = new Thread(() -> {
            try (input) {
                input.transferTo(java.io.OutputStream.nullOutputStream());
            } catch (IOException ignored) {
            }
        }, name);
        thread.setDaemon(true);
        thread.start();
    }

    public record LaunchResult(VirtualMachine virtualMachine, Process process, File target) {
    }
}
