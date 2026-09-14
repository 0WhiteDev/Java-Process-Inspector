package dev.whitedev.jpi.profiler;

public enum ProfilerCategory {
    CPU("CPU", "samples"),
    ALLOCATIONS("Allocations", "bytes"),
    LOCKS("Locks", "nanoseconds"),
    EXCEPTIONS("Exceptions", "events"),
    GC("GC", "nanoseconds"),
    THREADS("Threads", "events"),
    IO("I/O", "bytes");

    private final String label;
    private final String unit;

    ProfilerCategory(String label, String unit) {
        this.label = label;
        this.unit = unit;
    }

    public String unit() {
        return unit;
    }

    @Override public String toString() {
        return label;
    }
}
