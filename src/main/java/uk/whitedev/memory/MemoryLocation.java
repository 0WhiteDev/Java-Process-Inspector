package uk.whitedev.memory;

public class MemoryLocation {
    private final long address;
    private final String value;

    public MemoryLocation(long address, String value) {
        this.address = address;
        this.value = value;
    }

    public long getAddress() {
        return address;
    }

    public String getValue() {
        return value;
    }
}

