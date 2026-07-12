package dev.whitedev.jpi.agent;

import java.net.InetAddress;
import java.util.HashMap;
import java.util.Map;

public final class AgentOptions {
    private final InetAddress host;
    private final int port;
    private final String token;

    private AgentOptions(InetAddress host, int port, String token) {
        this.host = host;
        this.port = port;
        this.token = token;
    }

    public static AgentOptions parse(String value) {
        try {
            Map<String, String> values = new HashMap<>();
            for (String entry : value.split(";")) {
                int separator = entry.indexOf('=');
                if (separator > 0) values.put(entry.substring(0, separator), entry.substring(separator + 1));
            }
            String host = required(values, "host");
            String token = required(values, "token");
            int port = Integer.parseInt(required(values, "port"));
            if (!InetAddress.getByName(host).isLoopbackAddress()) {
                throw new IllegalArgumentException("Agent only accepts a loopback host");
            }
            if (port < 1 || port > 65535 || token.length() < 16) {
                throw new IllegalArgumentException("Invalid agent port or token");
            }
            return new AgentOptions(InetAddress.getByName(host), port, token);
        } catch (Exception exception) {
            if (exception instanceof IllegalArgumentException) throw (IllegalArgumentException) exception;
            throw new IllegalArgumentException("Invalid agent options", exception);
        }
    }

    private static String required(Map<String, String> values, String key) {
        String value = values.get(key);
        if (value == null || value.isEmpty()) throw new IllegalArgumentException("Missing agent option: " + key);
        return value;
    }

    public InetAddress host() { return host; }
    public int port() { return port; }
    public String token() { return token; }
}
