package dev.whitedev.jpi.agent;

import java.net.InetAddress;
import java.util.HashMap;
import java.util.Map;

public final class AgentOptions {
    public enum Mode { CONNECT, LISTEN }

    private final Mode mode;
    private final InetAddress host;
    private final int port;
    private final String token;
    private final int acceptTimeoutMillis;

    private AgentOptions(Mode mode, InetAddress host, int port, String token, int acceptTimeoutMillis) {
        this.mode = mode;
        this.host = host;
        this.port = port;
        this.token = token;
        this.acceptTimeoutMillis = acceptTimeoutMillis;
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
            Mode mode = mode(values.get("mode"));
            int acceptTimeoutSeconds = integer(values.get("acceptTimeoutSeconds"), 900);
            InetAddress address = InetAddress.getByName(host);
            if (!address.isLoopbackAddress()) {
                throw new IllegalArgumentException("Agent only accepts a loopback host");
            }
            if (port < 1 || port > 65535 || token.length() < 16 || token.length() > 512
                    || acceptTimeoutSeconds < 10 || acceptTimeoutSeconds > 3600) {
                throw new IllegalArgumentException("Invalid agent port or token");
            }
            return new AgentOptions(mode, address, port, token, acceptTimeoutSeconds * 1000);
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

    private static Mode mode(String value) {
        if (value == null || value.isEmpty() || "connect".equalsIgnoreCase(value)) return Mode.CONNECT;
        if ("listen".equalsIgnoreCase(value)) return Mode.LISTEN;
        throw new IllegalArgumentException("Invalid agent mode: " + value);
    }

    private static int integer(String value, int fallback) {
        return value == null || value.isEmpty() ? fallback : Integer.parseInt(value);
    }

    public Mode mode() { return mode; }
    public InetAddress host() { return host; }
    public int port() { return port; }
    public String token() { return token; }
    public int acceptTimeoutMillis() { return acceptTimeoutMillis; }
}
