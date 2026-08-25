package dev.whitedev.jpi.attach;

import java.io.PrintStream;
import java.util.LinkedHashMap;
import java.util.Map;

public final class TunnelAgentCommand {
    private TunnelAgentCommand() {}

    public static boolean matches(String[] arguments) {
        return arguments != null && arguments.length > 0 && "agent-server".equals(arguments[0]);
    }

    public static int run(String[] arguments, AttachService service, PrintStream output, PrintStream error) {
        try {
            Map<String, String> values = parse(arguments);
            String pid = required(values, "pid");
            int port = integer(required(values, "port"), "port");
            String token = required(values, "token");
            int timeout = integer(values.getOrDefault("timeout", "900"), "timeout");
            AttachService.PreparedTunnelAgent prepared = service.prepareTunnelAgent(port, token, timeout);
            service.loadTunnelAgent(pid, prepared);
            output.println("JPI listening agent loaded into PID " + pid + " on 127.0.0.1:" + port);
            output.println("Keep the SSH tunnel active, then connect the desktop client with the same token.");
            return 0;
        } catch (Exception exception) {
            error.println("Could not start JPI agent server: " + exception.getMessage());
            return 1;
        }
    }

    private static Map<String, String> parse(String[] arguments) {
        Map<String, String> values = new LinkedHashMap<>();
        for (int index = 1; index < arguments.length; index++) {
            String name = arguments[index];
            if (!name.startsWith("--") || index + 1 >= arguments.length) {
                throw new IllegalArgumentException("Usage: agent-server --pid <pid> --port <port> --token <token> [--timeout <seconds>]");
            }
            values.put(name.substring(2), arguments[++index]);
        }
        return values;
    }

    private static String required(Map<String, String> values, String name) {
        String value = values.get(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing --" + name);
        return value;
    }

    private static int integer(String value, String name) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("--" + name + " must be a number", exception);
        }
    }
}
