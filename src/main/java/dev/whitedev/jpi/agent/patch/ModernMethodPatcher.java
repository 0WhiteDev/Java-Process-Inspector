package dev.whitedev.jpi.agent.patch;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class ModernMethodPatcher {
    private static final Set<String> KEYWORDS = new HashSet<String>(Arrays.asList(
            "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class",
            "const", "continue", "default", "do", "double", "else", "enum", "extends", "final",
            "finally", "float", "for", "goto", "if", "implements", "import", "instanceof", "int",
            "interface", "long", "native", "new", "package", "private", "protected", "public",
            "return", "short", "static", "strictfp", "super", "switch", "synchronized", "this",
            "throw", "throws", "transient", "try", "void", "volatile", "while", "record", "sealed",
            "permits", "non-sealed", "var", "yield"));

    private ModernMethodPatcher() { }

    public static byte[] patch(byte[] original, Class<?> target, String methodName, String descriptor,
                        String body, RuntimeJavaCompiler.ClassPath classPath) throws Exception {
        ClassNode originalNode = read(original);
        MethodNode originalMethod = method(originalNode, methodName, descriptor);
        if (originalMethod == null) throw new IOException("Method not found: " + methodName + descriptor);
        if ((originalMethod.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0
                || methodName.startsWith("<")) {
            throw new IOException("The selected method does not have a replaceable body");
        }
        String source = source(originalNode, target, originalMethod, normalizeBody(body));
        byte[] donorBytes = RuntimeJavaCompiler.compile(target.getName(), source, classPath);
        ClassNode donorNode = read(donorBytes);
        MethodNode donorMethod = method(donorNode, methodName, descriptor);
        if (donorMethod == null) throw new IOException("Modern compilation did not produce the selected method");

        List<Handle> originalLambdas = lambdaHandles(originalNode.name, originalMethod);
        List<Handle> donorLambdas = lambdaHandles(donorNode.name, donorMethod);
        if (donorLambdas.size() > originalLambdas.size()) {
            throw new IOException("This edit introduces more lambda bodies than the original method. Standard HotSwap cannot add synthetic methods.");
        }

        Map<String, Handle> replacements = new LinkedHashMap<String, Handle>();
        for (int index = 0; index < donorLambdas.size(); index++) {
            Handle donorHandle = donorLambdas.get(index);
            Handle originalHandle = originalLambdas.get(index);
            if (!donorHandle.getDesc().equals(originalHandle.getDesc())) {
                throw new IOException("Lambda " + (index + 1)
                        + " changes its captured variables or parameter shape. Keep the original lambda signature.");
            }
            replacements.put(handleKey(donorHandle), originalHandle);
            MethodNode donorLambda = method(donorNode, donorHandle.getName(), donorHandle.getDesc());
            MethodNode originalLambda = method(originalNode, originalHandle.getName(), originalHandle.getDesc());
            if (donorLambda == null || originalLambda == null) {
                throw new IOException("Lambda implementation could not be mapped to the original class");
            }
            rewriteLambdaHandles(donorNode.name, donorLambda, replacements);
            replaceCode(originalLambda, donorLambda);
        }

        rewriteLambdaHandles(donorNode.name, donorMethod, replacements);
        replaceCode(originalMethod, donorMethod);
        ClassWriter writer = new ClassWriter(0);
        originalNode.accept(writer);
        return writer.toByteArray();
    }

    private static String source(ClassNode type, Class<?> target, MethodNode selected, String body)
            throws IOException {
        String binaryName = target.getName();
        int separator = binaryName.lastIndexOf('.');
        String packageName = separator < 0 ? "" : binaryName.substring(0, separator);
        String simpleName = separator < 0 ? binaryName : binaryName.substring(separator + 1);
        if (!validIdentifier(simpleName) || !validPackage(packageName)) {
            throw new IOException("The selected binary name cannot be represented as Java source: " + binaryName);
        }

        StringBuilder source = new StringBuilder();
        if (!packageName.isEmpty()) source.append("package ").append(packageName).append(';').append((char) 10);
        source.append("public class ").append(simpleName).append(" {").append((char) 10);

        Set<String> fieldNames = new LinkedHashSet<String>();
        for (org.objectweb.asm.tree.FieldNode field : type.fields) {
            appendField(source, fieldNames, field.name, Type.getType(field.desc), (field.access & Opcodes.ACC_STATIC) != 0);
        }
        appendInheritedFields(source, fieldNames, target.getSuperclass());

        Set<String> methodKeys = new LinkedHashSet<String>();
        String selectedJavaKey = javaMethodKey(selected.name, Type.getArgumentTypes(selected.desc));
        for (MethodNode method : type.methods) {
            if (method.name.startsWith("<") || method.name.startsWith("lambda$") || !validIdentifier(method.name)) continue;
            Type[] arguments = Type.getArgumentTypes(method.desc);
            String key = javaMethodKey(method.name, arguments);
            if (key.equals(selectedJavaKey) || !methodKeys.add(key)) continue;
            appendStub(source, method.name, Type.getReturnType(method.desc), arguments,
                    (method.access & Opcodes.ACC_STATIC) != 0);
        }
        appendInheritedMethods(source, methodKeys, target.getSuperclass(), selectedJavaKey);

        Type[] parameters = Type.getArgumentTypes(selected.desc);
        source.append("public ");
        if ((selected.access & Opcodes.ACC_STATIC) != 0) source.append("static ");
        source.append(typeName(Type.getReturnType(selected.desc))).append(' ')
                .append(selected.name).append('(');
        appendParameters(source, parameters);
        source.append(')');
        if (selected.exceptions != null && !selected.exceptions.isEmpty()) {
            source.append(" throws ");
            for (int index = 0; index < selected.exceptions.size(); index++) {
                if (index > 0) source.append(", ");
                source.append(selected.exceptions.get(index).replace('/', '.'));
            }
        }
        source.append(' ').append(body).append((char) 10).append('}').append((char) 10);
        return source.toString();
    }

    private static void appendInheritedFields(StringBuilder source, Set<String> names, Class<?> type) {
        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
            try {
                for (Field field : current.getDeclaredFields()) {
                    appendField(source, names, field.getName(), Type.getType(field.getType()), Modifier.isStatic(field.getModifiers()));
                }
            } catch (Throwable ignored) { }
        }
    }

    private static void appendField(StringBuilder source, Set<String> names, String name, Type type, boolean isStatic) {
        if (!validIdentifier(name) || !names.add(name)) return;
        source.append("public ");
        if (isStatic) source.append("static ");
        source.append(typeName(type)).append(' ').append(name).append(';').append((char) 10);
    }

    private static void appendInheritedMethods(StringBuilder source, Set<String> keys, Class<?> type,
                                               String selectedKey) {
        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
            try {
                for (Method method : current.getDeclaredMethods()) {
                    if (!validIdentifier(method.getName())) continue;
                    Type[] parameters = new Type[method.getParameterTypes().length];
                    for (int index = 0; index < parameters.length; index++) {
                        parameters[index] = Type.getType(method.getParameterTypes()[index]);
                    }
                    String key = javaMethodKey(method.getName(), parameters);
                    if (key.equals(selectedKey) || !keys.add(key)) continue;
                    appendStub(source, method.getName(), Type.getType(method.getReturnType()), parameters,
                            Modifier.isStatic(method.getModifiers()));
                }
            } catch (Throwable ignored) { }
        }
    }

    private static void appendStub(StringBuilder source, String name, Type returnType, Type[] parameters,
                                   boolean isStatic) {
        source.append("public ");
        if (isStatic) source.append("static ");
        source.append(typeName(returnType)).append(' ').append(name).append('(');
        appendParameters(source, parameters);
        source.append(") {");
        String value = defaultValue(returnType);
        if (value != null) source.append(" return ").append(value).append(';');
        source.append(" }").append((char) 10);
    }

    private static void appendParameters(StringBuilder source, Type[] parameters) {
        for (int index = 0; index < parameters.length; index++) {
            if (index > 0) source.append(", ");
            source.append(typeName(parameters[index])).append(" $").append(index + 1);
        }
    }

    private static String defaultValue(Type type) {
        switch (type.getSort()) {
            case Type.VOID: return null;
            case Type.BOOLEAN: return "false";
            case Type.CHAR: return "(char) 0";
            case Type.BYTE:
            case Type.SHORT:
            case Type.INT: return "0";
            case Type.LONG: return "0L";
            case Type.FLOAT: return "0F";
            case Type.DOUBLE: return "0D";
            default: return "null";
        }
    }

    private static String typeName(Type type) {
        return type.getClassName();
    }

    private static String javaMethodKey(String name, Type[] arguments) {
        StringBuilder key = new StringBuilder(name).append('(');
        for (Type argument : arguments) key.append(argument.getDescriptor());
        return key.append(')').toString();
    }

    private static String normalizeBody(String body) throws IOException {
        String trimmed = body.trim();
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) {
            throw new IOException("Method body must start with { and end with }");
        }
        StringBuilder output = new StringBuilder(trimmed.length());
        boolean string = false;
        boolean character = false;
        boolean escaped = false;
        for (int index = 0; index < trimmed.length();) {
            char value = trimmed.charAt(index);
            if (escaped) {
                output.append(value);
                escaped = false;
                index++;
                continue;
            }
            if ((string || character) && value == 92) {
                output.append(value);
                escaped = true;
                index++;
                continue;
            }
            if (!character && value == 34) {
                string = !string;
                output.append(value);
                index++;
                continue;
            }
            if (!string && value == 39) {
                character = !character;
                output.append(value);
                index++;
                continue;
            }
            if (!string && !character && trimmed.startsWith("$0", index)
                    && identifierBoundary(trimmed, index + 2)) {
                output.append("this");
                index += 2;
                continue;
            }
            output.append(value);
            index++;
        }
        return output.toString();
    }

    private static boolean identifierBoundary(String value, int index) {
        return index >= value.length() || !Character.isJavaIdentifierPart(value.charAt(index));
    }

    private static boolean validPackage(String packageName) {
        if (packageName.isEmpty()) return true;
        String[] parts = packageName.split("[.]");
        for (String part : parts) if (!validIdentifier(part)) return false;
        return true;
    }

    private static boolean validIdentifier(String value) {
        if (value == null || value.isEmpty() || KEYWORDS.contains(value)
                || !Character.isJavaIdentifierStart(value.charAt(0))) return false;
        for (int index = 1; index < value.length(); index++) {
            if (!Character.isJavaIdentifierPart(value.charAt(index))) return false;
        }
        return true;
    }

    private static ClassNode read(byte[] bytecode) {
        ClassNode node = new ClassNode();
        new ClassReader(bytecode).accept(node, 0);
        return node;
    }

    private static MethodNode method(ClassNode type, String name, String descriptor) {
        for (MethodNode method : type.methods) {
            if (name.equals(method.name) && descriptor.equals(method.desc)) return method;
        }
        return null;
    }

    private static List<Handle> lambdaHandles(String owner, MethodNode method) {
        Map<String, Handle> handles = new LinkedHashMap<String, Handle>();
        for (AbstractInsnNode instruction = method.instructions.getFirst();
             instruction != null; instruction = instruction.getNext()) {
            if (!(instruction instanceof InvokeDynamicInsnNode)) continue;
            InvokeDynamicInsnNode dynamic = (InvokeDynamicInsnNode) instruction;
            for (Object argument : dynamic.bsmArgs) {
                if (!(argument instanceof Handle)) continue;
                Handle handle = (Handle) argument;
                if (owner.equals(handle.getOwner()) && handle.getName().startsWith("lambda$")) {
                    handles.put(handleKey(handle), handle);
                }
            }
        }
        return new ArrayList<Handle>(handles.values());
    }

    private static void rewriteLambdaHandles(String donorOwner, MethodNode method,
                                             Map<String, Handle> replacements) {
        for (AbstractInsnNode instruction = method.instructions.getFirst();
             instruction != null; instruction = instruction.getNext()) {
            if (!(instruction instanceof InvokeDynamicInsnNode)) continue;
            InvokeDynamicInsnNode dynamic = (InvokeDynamicInsnNode) instruction;
            for (int index = 0; index < dynamic.bsmArgs.length; index++) {
                Object argument = dynamic.bsmArgs[index];
                if (!(argument instanceof Handle)) continue;
                Handle handle = (Handle) argument;
                if (!donorOwner.equals(handle.getOwner())) continue;
                Handle replacement = replacements.get(handleKey(handle));
                if (replacement != null) dynamic.bsmArgs[index] = replacement;
            }
        }
    }

    private static String handleKey(Handle handle) {
        return handle.getOwner() + "." + handle.getName() + handle.getDesc();
    }

    private static void replaceCode(MethodNode target, MethodNode source) {
        target.instructions = source.instructions;
        target.tryCatchBlocks = source.tryCatchBlocks;
        target.localVariables = source.localVariables;
        target.visibleLocalVariableAnnotations = source.visibleLocalVariableAnnotations;
        target.invisibleLocalVariableAnnotations = source.invisibleLocalVariableAnnotations;
        target.maxStack = source.maxStack;
        target.maxLocals = source.maxLocals;
    }
}
