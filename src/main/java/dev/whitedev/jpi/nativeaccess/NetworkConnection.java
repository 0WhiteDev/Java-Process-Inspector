package dev.whitedev.jpi.nativeaccess;

public record NetworkConnection(String protocol, String localAddress, int localPort,
                                String remoteAddress, int remotePort, String state) {
    public String localEndpoint() { return endpoint(localAddress, localPort); }
    public String remoteEndpoint() { return endpoint(remoteAddress, remotePort); }

    private static String endpoint(String address, int port) {
        if (address == null || address.isBlank()) return "*";
        String host = address.contains(":") ? "[" + address + "]" : address;
        return port == 0 ? host : host + ":" + port;
    }
}
