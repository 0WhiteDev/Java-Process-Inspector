package dev.whitedev.jpi.fixture;

import dev.whitedev.jpi.heap.HprofAnalysis;

import java.nio.file.Path;

public final class HprofPackagedProbe {
    public static void main(String[] arguments) throws Exception {
        try (HprofAnalysis snapshot = HprofAnalysis.open(Path.of(arguments[0]), 256)) {
            var objects = snapshot.search("HprofReferenceFixture$UserSession", 10);
            if (objects.size() != 1) throw new IllegalStateException("Fixture session missing from packaged analyzer");
            var report = snapshot.inspect(objects.getFirst().id(), true, true);
            if (report.paths().isEmpty() || report.object().retainedSize() < 1024 * 1024) {
                throw new IllegalStateException("Packaged heap analysis did not resolve roots and retained size");
            }
        }
    }
}
