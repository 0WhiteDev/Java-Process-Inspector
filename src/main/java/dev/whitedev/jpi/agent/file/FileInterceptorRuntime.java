package dev.whitedev.jpi.agent.file;

import dev.whitedev.jpi.agent.trace.TraceRuntime;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystemException;
import java.nio.file.Path;
import java.util.Base64;

public final class FileInterceptorRuntime {
    private static final FileRuleEngine RULES = new FileRuleEngine();
    private static final FileEventCollector EVENTS = new FileEventCollector();
    private static volatile boolean active;
    private static volatile boolean captureContent;
    private static volatile int previewLimit = 4096;

    private FileInterceptorRuntime() {}

    public static Path path(Path value, Object content, String operation, String callerClass,
                            String callerMethod, String callerDescriptor) throws IOException {
        return (Path) intercept(value, content, operation, callerClass, callerMethod, callerDescriptor, Path.class);
    }

    public static File file(File value, Object content, String operation, String callerClass,
                            String callerMethod, String callerDescriptor) throws IOException {
        return (File) intercept(value, content, operation, callerClass, callerMethod, callerDescriptor, File.class);
    }

    public static String string(String value, Object content, String operation, String callerClass,
                                String callerMethod, String callerDescriptor) throws IOException {
        return (String) intercept(value, content, operation, callerClass, callerMethod, callerDescriptor, String.class);
    }

    public static Path remapPath(Path value, String operation, String callerClass,
                                 String callerMethod, String callerDescriptor) throws IOException {
        return (Path) remap(value, FileOperation.valueOf(operation), Path.class,
                callerClass + "." + callerMethod + callerDescriptor);
    }

    public static File remapFile(File value, String operation, String callerClass,
                                 String callerMethod, String callerDescriptor) throws IOException {
        return (File) remap(value, FileOperation.valueOf(operation), File.class,
                callerClass + "." + callerMethod + callerDescriptor);
    }

    public static String remapString(String value, String operation, String callerClass,
                                     String callerMethod, String callerDescriptor) throws IOException {
        return (String) remap(value, FileOperation.valueOf(operation), String.class,
                callerClass + "." + callerMethod + callerDescriptor);
    }

    static void configure(int eventLimit, boolean content, int bytes) {
        EVENTS.configure(eventLimit);
        captureContent = content;
        previewLimit = bytes;
        active = true;
    }

    static void clear() {
        active = false;
        captureContent = false;
        RULES.clear();
        EVENTS.clear();
    }

    static void deactivate() {
        active = false;
        captureContent = false;
        EVENTS.clear();
    }

    static FileRuleEngine rules() {
        return RULES;
    }

    static String events() {
        return EVENTS.drain();
    }

    static void clearEvents() {
        EVENTS.clear();
    }

    private static Object intercept(Object value, Object content, String operationName, String callerClass,
                                    String callerMethod, String callerDescriptor, Class<?> outputType) throws IOException {
        if (!active || value == null) return value;
        FileOperation operation = FileOperation.valueOf(operationName);
        String path = String.valueOf(value);
        String normalized = FilePathResolver.normalized(value);
        String caller = callerClass + "." + callerMethod + callerDescriptor;
        FileRule rule = RULES.decision(operation, normalized, caller);
        FileDecision decision = rule.decision;
        Object result = value;
        String redirected = "";
        String error = "";
        if (decision == FileDecision.BLOCK) error = "Blocked by JPI File Monitor";
        else if (decision == FileDecision.REDIRECT) {
            Path destination = FilePathResolver.redirected(value, rule.redirectRoot);
            prepareDestination(destination, operation);
            redirected = destination.toString();
            result = converted(destination, outputType);
        }
        EVENTS.add(operation, path, normalized, callerClass, callerMethod, callerDescriptor,
                Thread.currentThread().getName(), decision, requestedBytes(content), redirected, error,
                stack(), preview(content), TraceRuntime.currentCallId());
        if (decision == FileDecision.BLOCK) {
            throw new FileSystemException(normalized, null, "Blocked by JPI File Monitor");
        }
        return result;
    }

    private static Object remap(Object value, FileOperation operation, Class<?> outputType, String caller) throws IOException {
        if (!active || value == null) return value;
        FileRule rule = RULES.decision(operation, FilePathResolver.normalized(value), caller);
        if (rule.decision != FileDecision.REDIRECT) return value;
        Path destination = FilePathResolver.redirected(value, rule.redirectRoot);
        prepareDestination(destination, operation);
        return converted(destination, outputType);
    }

    private static void prepareDestination(Path destination, FileOperation operation) throws IOException {
        if (operation != FileOperation.WRITE && operation != FileOperation.CREATE
                && operation != FileOperation.TRUNCATE && operation != FileOperation.OPEN
                && operation != FileOperation.MOVE && operation != FileOperation.COPY) return;
        Path parent = destination.getParent();
        if (parent != null) java.nio.file.Files.createDirectories(parent);
    }

    private static Object converted(Path value, Class<?> type) {
        if (type == Path.class) return value;
        if (type == File.class) return value.toFile();
        return value.toString();
    }

    private static long requestedBytes(Object content) {
        if (content instanceof byte[]) return ((byte[]) content).length;
        if (content instanceof CharSequence) return ((CharSequence) content).length();
        if (content instanceof ByteBuffer) return ((ByteBuffer) content).remaining();
        return -1L;
    }

    private static String preview(Object content) {
        if (!captureContent || content == null) return "";
        byte[] bytes;
        if (content instanceof byte[]) bytes = (byte[]) content;
        else if (content instanceof CharSequence) bytes = content.toString().getBytes(StandardCharsets.UTF_8);
        else if (content instanceof ByteBuffer) {
            ByteBuffer buffer = ((ByteBuffer) content).duplicate();
            bytes = new byte[Math.min(buffer.remaining(), previewLimit)];
            buffer.get(bytes);
        } else return "";
        int size = Math.min(bytes.length, previewLimit);
        byte[] bounded = new byte[size];
        System.arraycopy(bytes, 0, bounded, 0, size);
        return Base64.getEncoder().encodeToString(bounded);
    }

    private static String stack() {
        StringBuilder output = new StringBuilder();
        StackTraceElement[] trace = Thread.currentThread().getStackTrace();
        int captured = 0;
        for (StackTraceElement frame : trace) {
            String owner = frame.getClassName();
            if (owner.equals(Thread.class.getName()) || owner.equals(FileInterceptorRuntime.class.getName())) continue;
            if (captured++ >= 12) break;
            if (output.length() > 0) output.append('\n');
            output.append(frame);
        }
        return output.toString();
    }
}
