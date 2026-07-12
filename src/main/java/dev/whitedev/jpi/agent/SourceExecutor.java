package dev.whitedev.jpi.agent;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class SourceExecutor {
    private static final Pattern CLASS_NAME = Pattern.compile("public\\s+(?:final\\s+)?class\\s+([A-Za-z_$][A-Za-z0-9_$]*)");
    private SourceExecutor() {}

    static String execute(String source) throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) throw new IllegalStateException("Target runtime does not include the Java compiler (run it with a JDK)");
        Matcher matcher = CLASS_NAME.matcher(source);
        if (!matcher.find()) throw new IllegalArgumentException("Source must contain a public class");
        String className = matcher.group(1);
        File directory = Files.createTempDirectory("jpi-exec-").toFile();
        File sourceFile = new File(directory, className + ".java");
        Files.write(sourceFile.toPath(), source.getBytes(StandardCharsets.UTF_8));
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager manager = compiler.getStandardFileManager(diagnostics, Locale.ROOT, StandardCharsets.UTF_8)) {
            Iterable<? extends JavaFileObject> units = manager.getJavaFileObjects(sourceFile);
            boolean success = compiler.getTask(null, manager, diagnostics,
                    Arrays.asList("-d", directory.getAbsolutePath(), "-classpath", System.getProperty("java.class.path", "")),
                    null, units).call();
            if (!success) return diagnostics(diagnostics);
        }
        ClassLoader parent = Thread.currentThread().getContextClassLoader();
        try (URLClassLoader loader = new URLClassLoader(new java.net.URL[]{directory.toURI().toURL()}, parent)) {
            Class<?> executable = Class.forName(className, true, loader);
            Method method = executable.getMethod("execute", PrintStream.class);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (PrintStream output = new PrintStream(bytes, true, "UTF-8")) {
                method.invoke(null, output);
            } catch (InvocationTargetException error) {
                throw unwrap(error);
            }
            return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            delete(directory);
        }
    }

    private static String diagnostics(DiagnosticCollector<JavaFileObject> diagnostics) {
        StringBuilder out = new StringBuilder("Compilation failed:\n");
        for (Diagnostic<? extends JavaFileObject> item : diagnostics.getDiagnostics()) {
            out.append("line ").append(item.getLineNumber()).append(": ").append(item.getMessage(Locale.ROOT)).append('\n');
        }
        return out.toString();
    }

    private static Exception unwrap(InvocationTargetException error) {
        Throwable cause = error.getCause();
        return cause instanceof Exception ? (Exception) cause : new RuntimeException(cause);
    }

    private static void delete(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) delete(child);
        if (!file.delete()) file.deleteOnExit();
    }
}
