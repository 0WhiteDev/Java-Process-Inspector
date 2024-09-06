package uk.whitedev.utils;

import java.io.*;
import java.lang.reflect.Field;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.util.*;
import java.util.function.Supplier;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

public class ClassUtil {

    public List<String> getLoadedClasses() {
        List<Supplier<List<String>>> loaders = Arrays.asList(
                this::getLoadedClassesFromJMap,
                this::getLoadedClassesFromLoader,
                this::getLoadedClassesFromUrlLoader
        );
        return loaders.stream()
                .map(Supplier::get)
                .filter(classNames -> !classNames.isEmpty())
                .findFirst()
                .orElse(Collections.emptyList());
    }

    private List<String> getLoadedClassesFromJMap() {
        List<String> classNames = new ArrayList<>();
        try {
            Process process = new ProcessBuilder("jmap", "-histo:live", ProcessUtil.getProcessPid()).start();
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line;

            while ((line = reader.readLine()) != null) {
                String[] parts = line.trim().split("\\s+");
                if (parts.length >= 4) {
                    String className = parts[3];
                    int dollarIndex = className.indexOf('$');
                    if (dollarIndex != -1) className = className.substring(0, dollarIndex);
                    if (className.startsWith("[")) className = className.substring(2);
                    classNames.add(className);
                }
            }
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                System.err.println("Error: jmap command failed with exit code " + exitCode);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return classNames;
    }

    private List<String> getLoadedClassesFromLoader() {
        ClassLoader classLoader = ClassLoader.getSystemClassLoader();
        List<String> classNames = new ArrayList<>();
        try {
            Field classesField;
            classesField = ClassLoader.class.getDeclaredField("classes");
            classesField.setAccessible(true);
            Vector<Class<?>> classes;
            classes = (Vector<Class<?>>) classesField.get(classLoader);
            for (Class<?> clazz : classes) {
                classNames.add(clazz.getName());
            }
        } catch (IllegalAccessException | NoSuchFieldException e) {
            e.printStackTrace();
        }
        return classNames;
    }

    private List<String> getLoadedClassesFromUrlLoader() {
        ClassLoader classLoader = ClassLoader.getSystemClassLoader();
        List<String> classNames = new ArrayList<>();
        try {
            if (classLoader instanceof URLClassLoader) {
                URLClassLoader urlClassLoader = (URLClassLoader) classLoader;
                URL[] urls = urlClassLoader.getURLs();
                for (URL url : urls) {
                    if (url.getFile().endsWith(".jar")) {
                        try (JarFile jarFile = new JarFile(url.getFile())) {
                            Enumeration<JarEntry> entries = jarFile.entries();
                            while (entries.hasMoreElements()) {
                                JarEntry entry = entries.nextElement();
                                classNames.add(entry.getName());
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return classNames;
    }

    private String getClassFilePathFromURL(URL classURL) throws IOException, URISyntaxException {
        if (classURL == null) {
            throw new IllegalArgumentException("URL cannot be null");
        }

        String protocol = classURL.getProtocol();
        if ("file".equals(protocol)) {
            URI uri = classURL.toURI();
            if (!uri.isAbsolute()) {
                throw new IllegalArgumentException("URI is not absolute: " + uri);
            }
            return new File(uri).getAbsolutePath();
        } else if ("jar".equals(protocol)) {
            String path = classURL.getPath();
            int bangIndex = path.indexOf('!');
            if (bangIndex == -1) {
                throw new IllegalArgumentException("Invalid jar URL: " + path);
            }

            String jarPath = path.substring(5, bangIndex);
            String classPath = path.substring(bangIndex + 2);

            File tempFile = File.createTempFile("decompiled", ".class");
            try (JarFile jarFile = new JarFile(new File(jarPath))) {
                JarEntry entry = jarFile.getJarEntry(classPath);
                if (entry == null) {
                    throw new FileNotFoundException("Class entry not found in JAR: " + classPath);
                }
                try (InputStream is = jarFile.getInputStream(entry);
                     OutputStream os = Files.newOutputStream(tempFile.toPath())) {
                    byte[] buffer = new byte[1024];
                    int bytesRead;
                    while ((bytesRead = is.read(buffer)) != -1) {
                        os.write(buffer, 0, bytesRead);
                    }
                }
                return tempFile.getAbsolutePath();
            }
        } else {
            throw new UnsupportedOperationException("Protocol not supported: " + protocol);
        }
    }


    public String decompileClass(URL classFilePath) throws IOException, InterruptedException, URISyntaxException {
        File classFile = new File(getClassFilePathFromURL(classFilePath));
        ProcessBuilder processBuilder = new ProcessBuilder(
                "java", "-jar", loadCFR(), classFile.getAbsolutePath()
        );
        Process process = processBuilder.start();
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
        }
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new RuntimeException("CFR failed with exit code " + exitCode);
        }
        return output.toString();
    }

    public URL findClassURL(String className) throws IOException, URISyntaxException {
        String classFileName = className.replace('.', '/') + ".class";

        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        if (classLoader == null) {
            classLoader = ClassLoader.getSystemClassLoader();
        }

        URL classURL = classLoader.getResource(classFileName);
        if (classURL != null) {
            return classURL;
        }

        if (classURL.getProtocol().equals("jar")) {
            try (JarFile ignored = new JarFile(new File(classURL.toURI().getPath()))) {
                URL entryURL = classLoader.getResource(classFileName);
                if (entryURL != null) {
                    return entryURL;
                }
            }
        }

        return null;
    }

    private String loadCFR() {
        try (InputStream is = ClassUtil.class.getResourceAsStream("/assets/cfr-0.152.jar")) {
            if (is == null) {
                throw new IOException("CFR not found in JAR: /assets/cfr-0.152.jar");
            }
            File tempFile = File.createTempFile("cfr0152", ".jar");
            tempFile.deleteOnExit();
            Files.copy(is, tempFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return tempFile.getAbsolutePath();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public void dumpClass(String className, File baseDir) throws IOException {
        String classPath = className.replace('.', '/') + ".class";
        File classFile = new File(baseDir, classPath);
        File parentDir = classFile.getParentFile();
        if (!parentDir.exists() && !parentDir.mkdirs()) {
            throw new IOException("Failed to create directory: " + parentDir.getAbsolutePath());
        }
        InputStream classStream = getClassAsStream(className);
        if (classStream != null) {
            try (FileOutputStream outputStream = new FileOutputStream(classFile)) {
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = classStream.read(buffer)) != -1) {
                    outputStream.write(buffer, 0, bytesRead);
                }
            } finally {
                classStream.close();
            }
        } else {
            throw new IOException("Class stream is null for: " + className);
        }
    }

    private InputStream getClassAsStream(String className) {
        String classPath = className.replace('.', '/') + ".class";
        ClassLoader classLoader = getClass().getClassLoader();
        URL classUrl = classLoader.getResource(classPath);
        if (classUrl != null) {
            try {
                return classUrl.openStream();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        return null;
    }
}
