package dev.whitedev.jpi.nativeaccess;

public final class MemoryMatch {
    private final long address;
    private final String value;
    private final int byteSize;
    public MemoryMatch(long address, String value, int byteSize) { this.address = address; this.value = value; this.byteSize = byteSize; }
    public long address() { return address; }
    public String value() { return value; }
    public int byteSize() { return byteSize; }
}
