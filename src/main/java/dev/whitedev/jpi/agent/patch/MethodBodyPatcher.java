package dev.whitedev.jpi.agent.patch;

import javassist.ClassPool;
import javassist.CtBehavior;
import javassist.CtClass;
import javassist.LoaderClassPath;
import javassist.Modifier;

import java.io.ByteArrayInputStream;
import java.io.IOException;

public final class MethodBodyPatcher {
    private MethodBodyPatcher() { }

    public static String methods(byte[] bytecode, ClassLoader loader) throws Exception {
        CtClass type = open(bytecode, loader);
        try {
            StringBuilder output = new StringBuilder();
            for (CtBehavior behavior : type.getDeclaredBehaviors()) {
                String name = behavior.getMethodInfo2().getName();
                int modifiers = behavior.getModifiers();
                boolean patchable = !Modifier.isAbstract(modifiers) && !Modifier.isNative(modifiers)
                        && !name.startsWith("<");
                output.append(name).append('\t')
                        .append(behavior.getMethodInfo2().getDescriptor()).append('\t')
                        .append(Modifier.toString(modifiers)).append('\t')
                        .append(patchable).append('\n');
            }
            return output.toString();
        } finally {
            type.detach();
        }
    }

    public static byte[] patch(byte[] bytecode, ClassLoader loader, String name, String descriptor,
                        String body) throws Exception {
        CtClass type = open(bytecode, loader);
        try {
            CtBehavior selected = null;
            for (CtBehavior behavior : type.getDeclaredBehaviors()) {
                if (name.equals(behavior.getMethodInfo2().getName())
                        && descriptor.equals(behavior.getMethodInfo2().getDescriptor())) {
                    selected = behavior;
                    break;
                }
            }
            if (selected == null) throw new IOException("Method not found: " + name + descriptor);
            int modifiers = selected.getModifiers();
            if (Modifier.isAbstract(modifiers) || Modifier.isNative(modifiers)
                    || name.startsWith("<")) {
                throw new IOException("The selected method does not have a replaceable body");
            }
            try {
                selected.setBody(body);
            } catch (javassist.CannotCompileException error) {
                String detail = error.getMessage() == null ? "unknown parser error" : error.getMessage();
                if (body.contains("->") || body.contains("::")) {
                    throw new IOException("The method-only compiler does not support lambda expressions or method references. Replace them with ordinary statements, use full source, or apply class bytecode.", error);
                }
                throw new IOException("Method body compilation failed: " + detail, error);
            }
            return type.toBytecode();
        } finally {
            type.detach();
        }
    }

    private static CtClass open(byte[] bytecode, ClassLoader loader) throws Exception {
        ClassPool pool = new ClassPool(true);
        if (loader != null) pool.insertClassPath(new LoaderClassPath(loader));
        return pool.makeClass(new ByteArrayInputStream(bytecode), false);
    }
}
