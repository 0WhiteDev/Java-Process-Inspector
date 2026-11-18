package dev.whitedev.jpi.integration;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

public final class TransformationFixture {
    public static void main(String[] args) throws Exception {
        String target = TransformationTarget.class.getName();
        byte[] bytecode;
        try (InputStream input = TransformationTarget.class.getResourceAsStream("TransformationTarget.class")) {
            bytecode = input.readAllBytes();
        }
        Method duplicate = new DuplicateLoader().define(target, bytecode).getMethod("update", int.class);
        TransformationTarget.update(0);
        duplicate.invoke(null, 0);
        System.out.println(ProcessHandle.current().pid());
        BufferedReader input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        String command;
        while ((command = input.readLine()) != null) {
            String[] values = command.split(" ", 2);
            if ("read".equals(values[0])) {
                System.out.println(TransformationTarget.readFile(Path.of(values[1])));
                continue;
            }
            int value = Integer.parseInt(values[1]);
            Object result = "duplicate".equals(values[0]) ? duplicate.invoke(null, value) : TransformationTarget.update(value);
            System.out.println(result);
        }
    }

    private static final class DuplicateLoader extends ClassLoader {
        Class<?> define(String name, byte[] bytecode) {
            return defineClass(name, bytecode, 0, bytecode.length);
        }
    }
}
