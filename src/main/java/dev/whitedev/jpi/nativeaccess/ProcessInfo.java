package dev.whitedev.jpi.nativeaccess;

public record ProcessInfo(int pid, String name) {
    @Override public String toString() { return pid + "  -  " + name; }
}
