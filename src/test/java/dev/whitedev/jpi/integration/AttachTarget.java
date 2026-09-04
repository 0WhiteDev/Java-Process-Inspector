package dev.whitedev.jpi.integration;

import java.lang.management.ManagementFactory;

public final class AttachTarget {
    public static volatile String marker = "jpi-smoke-target";
    public static void setMarker(String value) { marker = value; }
    public static String runtimeValue() { return "before"; }
    public static byte[] digest(byte[] input) throws Exception {
        return java.security.MessageDigest.getInstance("SHA-256").digest(input);
    }
    public static int lambdaValue(int input) {
        java.util.function.IntUnaryOperator operation = value -> value + 1;
        return operation.applyAsInt(input);
    }
    public static int branchValue(int input) {
        if (input < 0) return -1;
        return input % 2 == 0 ? input * 2 : input + 1;
    }
    public static byte[] fileRoundTrip(String path, byte[] value) throws Exception {
        java.nio.file.Path target = java.nio.file.Paths.get(path);
        java.nio.file.Files.write(target, value);
        return java.nio.file.Files.readAllBytes(target);
    }
    public static boolean fileDelete(String path) throws Exception {
        return java.nio.file.Files.deleteIfExists(java.nio.file.Paths.get(path));
    }
    public static void main(String[] args) throws Exception {
        System.out.println(ManagementFactory.getRuntimeMXBean().getName().split("@", 2)[0]);
        System.out.flush();
        while (true) Thread.sleep(1000);
    }
}
