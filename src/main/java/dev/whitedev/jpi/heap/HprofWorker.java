package dev.whitedev.jpi.heap;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.EOFException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.nio.file.Path;

public final class HprofWorker {
    private HprofWorker() {}

    public static void main(String[] arguments) throws Exception {
        var input = new ObjectInputStream(new BufferedInputStream(System.in));
        var output = new ObjectOutputStream(new BufferedOutputStream(System.out));
        output.flush();
        System.setOut(System.err);
        HprofSnapshot snapshot = null;
        while (true) {
            Request request;
            try {
                request = (Request) input.readObject();
            } catch (EOFException error) {
                return;
            }
            Object response;
            try {
                if ("OPEN".equals(request.action())) {
                    snapshot = HprofSnapshot.open(Path.of(request.text()));
                    response = snapshot.file().toString();
                } else {
                    if (snapshot == null) throw new IllegalStateException("Open a snapshot first");
                    response = switch (request.action()) {
                        case "SEARCH" -> snapshot.search(request.text(), request.limit());
                        case "BIGGEST" -> snapshot.biggest(request.limit());
                        case "INSPECT" -> snapshot.inspect(request.id(), request.retained(), request.excludeWeak());
                        default -> throw new IllegalArgumentException("Unknown heap analysis operation");
                    };
                }
            } catch (Exception error) {
                response = new Failure(error.getClass().getSimpleName() + ": " + error.getMessage());
            }
            output.writeObject(response);
            output.reset();
            output.flush();
        }
    }

    public record Request(String action, String text, int limit, long id, boolean retained,
                          boolean excludeWeak) implements Serializable {}
    public record Failure(String message) implements Serializable {}
}
