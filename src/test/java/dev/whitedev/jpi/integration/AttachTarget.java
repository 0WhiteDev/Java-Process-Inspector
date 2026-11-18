package dev.whitedev.jpi.integration;

import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.function.IntUnaryOperator;

public final class AttachTarget {
    public static volatile String marker = "jpi-smoke-target";

    public static void setMarker(String value) {
        marker = value;
    }

    public static String runtimeValue() {
        return "before";
    }

    public static byte[] digest(byte[] input) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(input);
    }

    public static int lambdaValue(int input) {
        IntUnaryOperator operation = value -> value + 1;
        return operation.applyAsInt(input);
    }

    public static int branchValue(int input) {
        if (input < 0) return -1;
        return input % 2 == 0 ? input * 2 : input + 1;
    }

    public static byte[] fileRoundTrip(String path, byte[] value) throws Exception {
        Path target = Paths.get(path);
        Files.write(target, value);
        return Files.readAllBytes(target);
    }

    public static boolean fileDelete(String path) throws Exception {
        return Files.deleteIfExists(Paths.get(path));
    }

    public static void main(String[] args) throws Exception {
        System.out.println(ManagementFactory.getRuntimeMXBean().getName().split("@", 2)[0]);
        System.out.flush();
        while (true) Thread.sleep(1000);
    }
}
