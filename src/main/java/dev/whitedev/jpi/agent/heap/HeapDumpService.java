package dev.whitedev.jpi.agent.heap;

import java.io.File;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

public final class HeapDumpService {
    private HeapDumpService() {}

    public static String dump(String requestedPath) throws Exception {
        if (requestedPath == null || requestedPath.trim().isEmpty()) throw new IOException("Heap dump path is required");
        File requested = new File(requestedPath.trim());
        if (!requested.isAbsolute()) throw new IOException("Heap dump path must be absolute");
        File target = requested.getCanonicalFile();
        if (!target.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".hprof")) {
            throw new IOException("Heap dump file must use the .hprof extension");
        }
        if (target.exists()) throw new IOException("Heap dump destination already exists: " + target);
        File parent = target.getParentFile();
        if (parent == null || !parent.isDirectory() || !parent.canWrite()) {
            throw new IOException("Heap dump directory is not writable: " + parent);
        }

        Class<?> diagnosticType = Class.forName("com.sun.management.HotSpotDiagnosticMXBean");
        Method platformBean = ManagementFactory.class.getMethod("getPlatformMXBean", Class.class);
        Object diagnostic = platformBean.invoke(null, diagnosticType);
        if (diagnostic == null) throw new IOException("This JVM does not expose HotSpot heap dump support");
        Method dumpHeap = diagnosticType.getMethod("dumpHeap", String.class, boolean.class);
        try {
            dumpHeap.invoke(diagnostic, target.getAbsolutePath(), Boolean.TRUE);
        } catch (InvocationTargetException error) {
            Throwable cause = error.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            throw error;
        }
        return target.getAbsolutePath() + "\t" + target.length();
    }
}
