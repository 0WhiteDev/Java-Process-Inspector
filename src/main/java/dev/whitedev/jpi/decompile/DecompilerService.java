package dev.whitedev.jpi.decompile;

import java.net.URISyntaxException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import dev.whitedev.jpi.plugin.api.decompile.DecompilationRequest;

import java.io.*;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class DecompilerService {
    private final Map<DecompilerEngine, List<File>> extracted = new EnumMap<>(DecompilerEngine.class);

    public String decompile(String className, byte[] bytecode) throws IOException, InterruptedException {
        return decompile(DecompilerEngine.CFR, className, bytecode);
    }

    public String decompile(DecompilerEngine engine, String className, byte[] bytecode)
            throws IOException, InterruptedException {
        return decompile(engine, className, null, null, bytecode);
    }

    public String decompile(DecompilerOption option, String className, byte[] bytecode) throws Exception {
        if (option == null || !option.plugin()) {
            return decompile(option == null ? DecompilerEngine.CFR : option.engine(), className, bytecode);
        }
        return option.provider().decompile(new DecompilationRequest(className, bytecode, "", ""));
    }

    public String decompileMethod(String className, String methodName, byte[] bytecode)
            throws IOException, InterruptedException {
        return decompileMethod(DecompilerEngine.CFR, className, methodName, null, bytecode);
    }

    public String decompileMethod(DecompilerEngine engine, String className, String methodName,
                                  String descriptor, byte[] bytecode)
            throws IOException, InterruptedException {
        return decompile(engine, className, methodName, descriptor, bytecode);
    }

    public String decompileMethod(DecompilerOption option, String className, String methodName,
                                  String descriptor, byte[] bytecode) throws Exception {
        if (option == null || !option.plugin()) {
            return decompileMethod(option == null ? DecompilerEngine.CFR : option.engine(),
                    className, methodName, descriptor, bytecode);
        }
        return option.provider().decompile(new DecompilationRequest(className, bytecode, methodName, descriptor));
    }

    private String decompile(DecompilerEngine engine, String className, String methodName,
                             String descriptor, byte[] bytecode)
            throws IOException, InterruptedException {
        File classFile = File.createTempFile("jpi-", "-" + simpleName(className) + ".class");
        try {
            Files.write(classFile.toPath(), bytecode);
            return switch (engine) {
                case CFR -> cfr(classFile, methodName);
                case VINEFLOWER -> vineflower(classFile);
                case PROCYON -> procyon(classFile);
            };
        } finally {
            if (!classFile.delete()) classFile.deleteOnExit();
        }
    }

    private String cfr(File classFile, String methodName) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add(javaExecutable());
        command.add("-Xmx256m");
        command.add("-jar");
        command.add(resources(DecompilerEngine.CFR).getFirst().getAbsolutePath());
        command.add(classFile.getAbsolutePath());
        command.add("--silent");
        command.add("true");
        if (methodName != null) {
            command.add("--methodname");
            command.add(methodName);
        }
        return run(command, "CFR");
    }

    private String vineflower(File classFile) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add(javaExecutable());
        command.add("-Xmx256m");
        command.add("-jar");
        command.add(resources(DecompilerEngine.VINEFLOWER).getFirst().getAbsolutePath());
        command.add("-s");
        command.add(classFile.getAbsolutePath());
        String output = run(command, "Vineflower");
        if (output.startsWith("==== ")) {
            int firstLine = output.indexOf(10);
            if (firstLine >= 0) output = output.substring(firstLine + 1);
        }
        return output;
    }

    private String procyon(File classFile) throws IOException, InterruptedException {
        try {
            String classPath = new File(DecompilerService.class.getProtectionDomain().getCodeSource().getLocation().toURI()).getAbsolutePath();
            return run(List.of(javaExecutable(), "-Xmx256m", "-cp", classPath, ProcyonWorker.class.getName(), classFile.getAbsolutePath()), "Procyon");
        } catch (URISyntaxException error) {
            throw new IOException("Could not resolve the local decompiler classpath", error);
        }
    }

    String procyonInProcess(File classFile) throws IOException {
        List<File> files = resources(DecompilerEngine.PROCYON);
        URL[] urls = files.stream().map(File::toURI).map(uri -> {
            try {
                return uri.toURL();
            } catch (Exception error) {
                throw new IllegalArgumentException(error);
            }
        }).toArray(URL[]::new);
        try (URLClassLoader loader = new URLClassLoader(urls, ClassLoader.getPlatformClassLoader())) {
            Class<?> settingsType = loader.loadClass("com.strobel.decompiler.DecompilerSettings");
            Class<?> outputType = loader.loadClass("com.strobel.decompiler.ITextOutput");
            Class<?> plainOutputType = loader.loadClass("com.strobel.decompiler.PlainTextOutput");
            Class<?> decompilerType = loader.loadClass("com.strobel.decompiler.Decompiler");
            Object settings = settingsType.getMethod("javaDefaults").invoke(null);
            StringWriter writer = new StringWriter();
            Object output = plainOutputType.getConstructor(Writer.class).newInstance(writer);
            decompilerType.getMethod("decompile", String.class, outputType, settingsType)
                    .invoke(null, classFile.getAbsolutePath(), output, settings);
            return writer.toString();
        } catch (InvocationTargetException error) {
            Throwable cause = error.getCause() == null ? error : error.getCause();
            throw new IOException("Procyon failed: " + cause.getMessage(), cause);
        } catch (ReflectiveOperationException | IllegalArgumentException error) {
            throw new IOException("Embedded Procyon could not be started", error);
        }
    }

    private synchronized List<File> resources(DecompilerEngine engine) throws IOException {
        List<File> cached = extracted.get(engine);
        if (cached != null && cached.stream().allMatch(File::isFile)) return cached;
        List<File> files = new ArrayList<>();
        for (String resource : engine.resources()) {
            InputStream input = DecompilerService.class.getResourceAsStream(resource);
            if (input == null) throw new FileNotFoundException("Embedded resource is missing: " + resource);
            File file = File.createTempFile("jpi-" + engine.name().toLowerCase(Locale.ROOT) + "-", ".jar");
            try {
                Files.copy(input, file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            } finally {
                input.close();
            }
            file.deleteOnExit();
            files.add(file);
        }
        List<File> immutable = List.copyOf(files);
        extracted.put(engine, immutable);
        return immutable;
    }

    private static String run(List<String> command, String name) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        FutureTask<String> reader = new FutureTask<>(() -> read(process.getInputStream()));
        Thread.ofVirtual().name("jpi-decompiler-output").start(reader);
        try {
            if (!process.waitFor(30, TimeUnit.SECONDS)) throw new IOException(name + " exceeded its 30 second timeout");
            String output = reader.get(2, TimeUnit.SECONDS);
            if (process.exitValue() != 0) throw new IOException(name + " exited with code " + process.exitValue() + System.lineSeparator() + output);
            return output;
        } catch (ExecutionException error) {
            throw new IOException(name + " output could not be read", error.getCause());
        } catch (TimeoutException error) {
            throw new IOException(name + " output did not close", error);
        } finally {
            if (process.isAlive()) process.destroyForcibly();
            reader.cancel(true);
            process.getInputStream().close();
        }
    }

    private static String javaExecutable() {
        File executable = new File(new File(System.getProperty("java.home"), "bin"),
                System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java");
        return executable.getAbsolutePath();
    }

    private static String simpleName(String name) {
        int index = name.lastIndexOf('.');
        return index < 0 ? name : name.substring(index + 1);
    }

    private static String read(InputStream input) throws IOException {
        Reader reader = new InputStreamReader(input, StandardCharsets.UTF_8);
        StringBuilder out = new StringBuilder();
        char[] buffer = new char[8192];
        int count;
        while ((count = reader.read(buffer)) != -1) {
            if (out.length() + count > 8 * 1024 * 1024) throw new IOException("Decompiler output exceeds 8 MiB");
            out.append(buffer, 0, count);
        }
        return out.toString();
    }
}
