package dev.whitedev.jpi.integration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class TransformationTarget {
    public static int counter;

    public static int update(int value) {
        counter = value;
        if (value < 0) {
            return -1;
        }
        return value + 1;
    }

    public static int readFile(Path file) throws IOException {
        return Files.readAllBytes(file).length;
    }
}
