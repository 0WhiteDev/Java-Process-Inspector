package dev.whitedev.jpi.nativeaccess;

import com.sun.jna.Memory;
import com.sun.jna.Platform;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.IPHlpAPI;
import com.sun.jna.ptr.IntByReference;

import java.io.IOException;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class WindowsNetworkAccess {
    private static final int ERROR_INSUFFICIENT_BUFFER = 122;
    private static final int TCP_TABLE_OWNER_PID_ALL = 5;
    private static final int UDP_TABLE_OWNER_PID = 1;
    private static final int MAX_ROWS = 100_000;

    public boolean isSupported() { return Platform.isWindows(); }

    public List<NetworkConnection> connections(int pid) throws IOException {
        if (!isSupported()) throw new IOException("Network ownership inspection is available on Windows only");
        List<NetworkConnection> result = new ArrayList<>();
        readTcp4(pid, result); readTcp6(pid, result); readUdp4(pid, result); readUdp6(pid, result);
        result.sort(Comparator.comparing(NetworkConnection::protocol)
                .thenComparing(NetworkConnection::localEndpoint).thenComparing(NetworkConnection::remoteEndpoint));
        return result;
    }

    private void readTcp4(int pid, List<NetworkConnection> output) throws IOException {
        Memory table = tcpTable(IPHlpAPI.AF_INET); int count = boundedCount(table);
        for (int row = 0; row < count; row++) {
            long offset = 4L + row * 24L;
            if (unsigned(table.getInt(offset + 20)) != pid) continue;
            output.add(new NetworkConnection("TCP4", address(table, offset + 4, 4), port(table.getInt(offset + 8)),
                    address(table, offset + 12, 4), port(table.getInt(offset + 16)), tcpState(table.getInt(offset))));
        }
    }

    private void readTcp6(int pid, List<NetworkConnection> output) throws IOException {
        Memory table = tcpTable(IPHlpAPI.AF_INET6); int count = boundedCount(table);
        for (int row = 0; row < count; row++) {
            long offset = 4L + row * 56L;
            if (unsigned(table.getInt(offset + 52)) != pid) continue;
            output.add(new NetworkConnection("TCP6", address(table, offset, 16), port(table.getInt(offset + 20)),
                    address(table, offset + 24, 16), port(table.getInt(offset + 44)), tcpState(table.getInt(offset + 48))));
        }
    }

    private void readUdp4(int pid, List<NetworkConnection> output) throws IOException {
        Memory table = udpTable(IPHlpAPI.AF_INET); int count = boundedCount(table);
        for (int row = 0; row < count; row++) {
            long offset = 4L + row * 12L;
            if (unsigned(table.getInt(offset + 8)) != pid) continue;
            output.add(new NetworkConnection("UDP4", address(table, offset, 4), port(table.getInt(offset + 4)), "*", 0, "BOUND"));
        }
    }

    private void readUdp6(int pid, List<NetworkConnection> output) throws IOException {
        Memory table = udpTable(IPHlpAPI.AF_INET6); int count = boundedCount(table);
        for (int row = 0; row < count; row++) {
            long offset = 4L + row * 28L;
            if (unsigned(table.getInt(offset + 24)) != pid) continue;
            output.add(new NetworkConnection("UDP6", address(table, offset, 16), port(table.getInt(offset + 20)), "*", 0, "BOUND"));
        }
    }

    private Memory tcpTable(int family) throws IOException {
        return table((pointer, size) -> IPHlpAPI.INSTANCE.GetExtendedTcpTable(pointer, size, true, family, TCP_TABLE_OWNER_PID_ALL, 0));
    }

    private Memory udpTable(int family) throws IOException {
        return table((pointer, size) -> IPHlpAPI.INSTANCE.GetExtendedUdpTable(pointer, size, true, family, UDP_TABLE_OWNER_PID, 0));
    }

    private Memory table(TableReader reader) throws IOException {
        IntByReference size = new IntByReference(); int first = reader.read(null, size);
        if (first != ERROR_INSUFFICIENT_BUFFER && first != 0) throw new IOException("IP Helper API failed: " + first);
        Memory memory = new Memory(Math.max(4, size.getValue())); int result = reader.read(memory, size);
        if (result != 0) throw new IOException("IP Helper API failed: " + result);
        return memory;
    }

    private static int boundedCount(Pointer table) throws IOException {
        long count = unsigned(table.getInt(0));
        if (count > MAX_ROWS) throw new IOException("Network table is unexpectedly large: " + count);
        return (int) count;
    }

    static int port(int networkOrder) { return Short.toUnsignedInt(Short.reverseBytes((short) networkOrder)); }

    private static String address(Pointer table, long offset, int length) throws IOException {
        try { return InetAddress.getByAddress(table.getByteArray(offset, length)).getHostAddress(); }
        catch (Exception error) { throw new IOException("Invalid address returned by IP Helper API", error); }
    }

    private static String tcpState(int state) {
        return switch (state) {
            case 1 -> "CLOSED"; case 2 -> "LISTEN"; case 3 -> "SYN_SENT"; case 4 -> "SYN_RECEIVED";
            case 5 -> "ESTABLISHED"; case 6 -> "FIN_WAIT_1"; case 7 -> "FIN_WAIT_2"; case 8 -> "CLOSE_WAIT";
            case 9 -> "CLOSING"; case 10 -> "LAST_ACK"; case 11 -> "TIME_WAIT"; case 12 -> "DELETE_TCB";
            default -> "STATE_" + state;
        };
    }

    private static long unsigned(int value) { return Integer.toUnsignedLong(value); }
    @FunctionalInterface private interface TableReader { int read(Pointer pointer, IntByReference size); }
}
