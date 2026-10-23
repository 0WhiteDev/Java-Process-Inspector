package dev.whitedev.jpi.decompile;

import java.io.File;
import java.nio.charset.StandardCharsets;

public final class ProcyonWorker {
    private ProcyonWorker() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("A local class file is required");
        String result = new DecompilerService().procyonInProcess(new File(args[0]));
        System.out.write(result.getBytes(StandardCharsets.UTF_8));
        System.out.flush();
    }
}
