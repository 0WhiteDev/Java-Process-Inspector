package dev.whitedev.jpi.agent.patch;

import org.eclipse.jdt.internal.compiler.tool.EclipseCompiler;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.FileObject;
import javax.tools.ForwardingJavaFileManager;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class RuntimeJavaCompiler {
    public interface ClassPath {
        byte[] find(String binaryName);
        Collection<String> list(String packageName, boolean recurse);
    }

    private static final ClassPath EMPTY_CLASS_PATH = new ClassPath() {
        public byte[] find(String binaryName) { return null; }
        public Collection<String> list(String packageName, boolean recurse) { return Collections.emptyList(); }
    };

    private RuntimeJavaCompiler() { }

    public static byte[] compile(String binaryName, String source) throws IOException {
        return compile(binaryName, source, EMPTY_CLASS_PATH);
    }

    public static byte[] compile(String binaryName, String source, ClassPath classPath) throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) compiler = new EclipseCompiler();
        return compile(compiler, binaryName, source, classPath);
    }

    public static byte[] compile(JavaCompiler compiler, String binaryName, String source, ClassPath classPath)
            throws IOException {
        if (compiler == null) throw new IOException("No Java compiler is available");
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<JavaFileObject>();
        File temporarySourceRoot = null;
        try (StandardJavaFileManager standard = compiler.getStandardFileManager(
                diagnostics, Locale.ROOT, StandardCharsets.UTF_8);
             MemoryFileManager manager = new MemoryFileManager(standard, classPath, source)) {
            Iterable<? extends JavaFileObject> units;
            if (compiler instanceof EclipseCompiler) {
                temporarySourceRoot = Files.createTempDirectory("jpi-ecj-").toFile();
                File sourceFile = new File(temporarySourceRoot,
                        binaryName.replace('.', File.separatorChar) + JavaFileObject.Kind.SOURCE.extension);
                File parent = sourceFile.getParentFile();
                if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                    throw new IOException("Could not create the embedded compiler source directory");
                }
                Files.write(sourceFile.toPath(), source.getBytes(StandardCharsets.UTF_8));
                units = standard.getJavaFileObjects(sourceFile);
            } else {
                units = Collections.singletonList(new SourceFile(binaryName, source));
            }
            List<String> options = Arrays.asList(
                    "-proc:none", "-g", "-classpath", System.getProperty("java.class.path", ""));
            boolean success = Boolean.TRUE.equals(
                    compiler.getTask(null, manager, diagnostics, options, null, units).call());
            if (!success) throw new IOException(formatDiagnostics(diagnostics));
            Map<String, byte[]> outputs = manager.outputs();
            byte[] primary = outputs.get(binaryName);
            if (primary == null) throw new IOException("Compilation did not produce " + binaryName + ". Outputs: " + outputs.keySet());
            if (outputs.size() != 1) {
                List<String> additional = new ArrayList<String>(outputs.keySet());
                additional.remove(binaryName);
                throw new IOException("Runtime redefinition cannot introduce additional classes: " + join(additional));
            }
            return primary;
        } finally {
            deleteTree(temporarySourceRoot);
        }
    }

    private static void deleteTree(File file) {
        if (file == null || !file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) deleteTree(child);
        }
        if (!file.delete()) file.deleteOnExit();
    }
    private static String join(List<String> values) {
        StringBuilder output = new StringBuilder();
        for (String value : values) {
            if (output.length() > 0) output.append(", ");
            output.append(value);
        }
        return output.toString();
    }

    private static String formatDiagnostics(DiagnosticCollector<JavaFileObject> diagnostics) {
        StringBuilder output = new StringBuilder("Compilation failed");
        for (Diagnostic<? extends JavaFileObject> diagnostic : diagnostics.getDiagnostics()) {
            output.append((char) 10).append("line ").append(diagnostic.getLineNumber())
                    .append(": ").append(diagnostic.getMessage(Locale.ROOT));
        }
        return output.toString();
    }

    private static final class SourceFile extends SimpleJavaFileObject {
        private final String source;

        SourceFile(String binaryName, String source) {
            super(URI.create("string:/" + binaryName.replace('.', '/') + Kind.SOURCE.extension), Kind.SOURCE);
            this.source = source;
        }

        @Override public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return source;
        }
    }

    private static final class ClassInputFile extends SimpleJavaFileObject {
        private final String binaryName;
        private final byte[] bytecode;

        ClassInputFile(String binaryName, byte[] bytecode) {
            super(URI.create("memory:/" + binaryName.replace('.', '/') + Kind.CLASS.extension), Kind.CLASS);
            this.binaryName = binaryName;
            this.bytecode = bytecode;
        }

        @Override public InputStream openInputStream() {
            return new ByteArrayInputStream(bytecode);
        }
    }

    private static final class ClassOutputFile extends SimpleJavaFileObject {
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();

        ClassOutputFile(String binaryName, Kind kind) {
            super(URI.create("memory:/" + binaryName.replace('.', '/') + kind.extension), kind);
        }

        @Override public OutputStream openOutputStream() {
            return output;
        }

        byte[] bytes() {
            return output.toByteArray();
        }
    }

    private static final class MemoryFileManager extends ForwardingJavaFileManager<StandardJavaFileManager> {
        private final Map<String, ClassOutputFile> classes = new LinkedHashMap<String, ClassOutputFile>();
        private final ClassPath classPath;
        private final Set<String> sourceIdentifiers;

        MemoryFileManager(StandardJavaFileManager delegate, ClassPath classPath, String source) {
            super(delegate);
            this.classPath = classPath == null ? EMPTY_CLASS_PATH : classPath;
            this.sourceIdentifiers = identifiers(source);
        }

        @Override public JavaFileObject getJavaFileForOutput(JavaFileManager.Location location, String className,
                                                             JavaFileObject.Kind kind, FileObject sibling) {
            String binaryName = className.replace('/', '.');
            ClassOutputFile file = new ClassOutputFile(binaryName, kind);
            classes.put(binaryName, file);
            return file;
        }

        @Override public JavaFileObject getJavaFileForInput(JavaFileManager.Location location, String className,
                                                            JavaFileObject.Kind kind) throws IOException {
            try {
                JavaFileObject standard = super.getJavaFileForInput(location, className, kind);
                if (standard != null) return standard;
            } catch (IOException ignored) { }
            if (kind == JavaFileObject.Kind.CLASS && location == StandardLocation.CLASS_PATH) {
                byte[] bytecode = classPath.find(className);
                if (bytecode != null) return new ClassInputFile(className, bytecode);
            }
            return null;
        }

        @Override public Iterable<JavaFileObject> list(JavaFileManager.Location location, String packageName,
                                                       Set<JavaFileObject.Kind> kinds, boolean recurse)
                throws IOException {
            Iterable<JavaFileObject> standard = super.list(location, packageName, kinds, recurse);
            if (location != StandardLocation.CLASS_PATH || !kinds.contains(JavaFileObject.Kind.CLASS)) return standard;
            List<JavaFileObject> output = new ArrayList<JavaFileObject>();
            Set<String> names = new LinkedHashSet<String>();
            for (JavaFileObject file : standard) {
                output.add(file);
                String name = inferBinaryName(location, file);
                if (name != null) names.add(name);
            }
            for (String name : classPath.list(packageName, recurse)) {
                if (!referenced(name) || !names.add(name)) continue;
                byte[] bytecode = classPath.find(name);
                if (bytecode != null) output.add(new ClassInputFile(name, bytecode));
            }
            return output;
        }

        private boolean referenced(String binaryName) {
            int separator = binaryName.lastIndexOf('.');
            String simpleName = separator < 0 ? binaryName : binaryName.substring(separator + 1);
            if (sourceIdentifiers.contains(simpleName)) return true;
            int nested = simpleName.indexOf('$');
            return nested > 0 && sourceIdentifiers.contains(simpleName.substring(0, nested));
        }

        private static Set<String> identifiers(String source) {
            Set<String> values = new HashSet<String>();
            for (int index = 0; index < source.length();) {
                char value = source.charAt(index);
                if (!Character.isJavaIdentifierStart(value)) {
                    index++;
                    continue;
                }
                int end = index + 1;
                while (end < source.length() && Character.isJavaIdentifierPart(source.charAt(end))) end++;
                values.add(source.substring(index, end));
                index = end;
            }
            return values;
        }

        @Override public String inferBinaryName(JavaFileManager.Location location, JavaFileObject file) {
            if (file instanceof ClassInputFile) return ((ClassInputFile) file).binaryName;
            return super.inferBinaryName(location, file);
        }

        Map<String, byte[]> outputs() {
            Map<String, byte[]> output = new LinkedHashMap<String, byte[]>();
            for (Map.Entry<String, ClassOutputFile> entry : classes.entrySet()) {
                output.put(entry.getKey(), entry.getValue().bytes());
            }
            return output;
        }
    }
}