package dev.whitedev.jpi.agent.file;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.ArrayList;
import java.util.List;

public final class FileCallSiteTransformer {
    private static final String RUNTIME = Type.getInternalName(FileInterceptorRuntime.class);
    private static final String PATH = "java/nio/file/Path";
    private static final String FILE = "java/io/File";
    private static final String PRIMARY_SUFFIX = "Ljava/lang/Object;Ljava/lang/String;Ljava/lang/String;"
            + "Ljava/lang/String;Ljava/lang/String;)";

    private FileCallSiteTransformer() {}

    public static Result transform(byte[] source) {
        ClassReader reader = new ClassReader(source);
        ClassNode type = new ClassNode();
        reader.accept(type, 0);
        int sites = 0;
        for (MethodNode method : type.methods) {
            if ((method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) continue;
            for (AbstractInsnNode instruction = method.instructions.getFirst(); instruction != null;) {
                AbstractInsnNode next = instruction.getNext();
                if (instruction instanceof MethodInsnNode) {
                    MethodInsnNode call = (MethodInsnNode) instruction;
                    Site site = site(call);
                    if (site != null) {
                        method.instructions.insertBefore(call, intercept(type.name, method, call, site));
                        sites++;
                    }
                }
                instruction = next;
            }
        }
        if (sites == 0) return new Result(source, 0);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        type.accept(writer);
        return new Result(writer.toByteArray(), sites);
    }

    private static InsnList intercept(String caller, MethodNode method, MethodInsnNode call, Site site) {
        Type[] arguments = Type.getArgumentTypes(call.desc);
        int[] locals = new int[arguments.length];
        InsnList code = new InsnList();
        for (int index = arguments.length - 1; index >= 0; index--) {
            locals[index] = method.maxLocals;
            method.maxLocals += arguments[index].getSize();
            code.add(new VarInsnNode(arguments[index].getOpcode(Opcodes.ISTORE), locals[index]));
        }
        int receiver = -1;
        boolean constructor = call.getOpcode() == Opcodes.INVOKESPECIAL && "<init>".equals(call.name);
        if (site.receiver && !constructor) {
            receiver = method.maxLocals++;
            code.add(new VarInsnNode(Opcodes.ASTORE, receiver));
            code.add(new VarInsnNode(Opcodes.ALOAD, receiver));
            addPrimary(code, Type.getObjectType(FILE), site.operation, caller, method, locals, arguments, site.pathArguments);
        } else if (call.getOpcode() != Opcodes.INVOKESTATIC && !constructor) {
            receiver = method.maxLocals++;
            code.add(new VarInsnNode(Opcodes.ASTORE, receiver));
            code.add(new VarInsnNode(Opcodes.ALOAD, receiver));
        }
        boolean emitted = site.receiver;
        for (int index = 0; index < arguments.length; index++) {
            Type argument = arguments[index];
            code.add(new VarInsnNode(argument.getOpcode(Opcodes.ILOAD), locals[index]));
            if (!site.pathArguments.contains(Integer.valueOf(index))) continue;
            if (!emitted) {
                addPrimary(code, argument, site.operation, caller, method, locals, arguments, site.pathArguments);
                emitted = true;
            } else {
                addSecondary(code, argument, site.operation, caller, method);
            }
        }
        return code;
    }

    private static void addPrimary(InsnList code, Type pathType, FileOperation operation, String caller,
                                   MethodNode method, int[] locals, Type[] arguments, List<Integer> paths) {
        int payload = payload(arguments, paths, operation);
        if (payload < 0) code.add(new InsnNode(Opcodes.ACONST_NULL));
        else code.add(new VarInsnNode(Opcodes.ALOAD, locals[payload]));
        code.add(new LdcInsnNode(operation.name()));
        code.add(new LdcInsnNode(caller.replace('/', '.')));
        code.add(new LdcInsnNode(method.name));
        code.add(new LdcInsnNode(method.desc));
        String descriptor = "(" + pathType.getDescriptor() + PRIMARY_SUFFIX + pathType.getDescriptor();
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME, converter(pathType), descriptor, false));
    }

    private static void addSecondary(InsnList code, Type pathType, FileOperation operation,
                                     String caller, MethodNode method) {
        code.add(new LdcInsnNode(operation.name()));
        code.add(new LdcInsnNode(caller.replace('/', '.')));
        code.add(new LdcInsnNode(method.name));
        code.add(new LdcInsnNode(method.desc));
        String descriptor = "(" + pathType.getDescriptor() + "Ljava/lang/String;Ljava/lang/String;"
                + "Ljava/lang/String;Ljava/lang/String;)" + pathType.getDescriptor();
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME, "remap" + capitalized(pathType), descriptor, false));
    }

    private static int payload(Type[] arguments, List<Integer> paths, FileOperation operation) {
        if (operation != FileOperation.WRITE && operation != FileOperation.TRUNCATE) return -1;
        for (int index = 0; index < arguments.length; index++) {
            if (paths.contains(Integer.valueOf(index))) continue;
            String descriptor = arguments[index].getDescriptor();
            if ("[B".equals(descriptor) || "Ljava/lang/CharSequence;".equals(descriptor)
                    || "Ljava/lang/String;".equals(descriptor) || "Ljava/nio/ByteBuffer;".equals(descriptor)) return index;
        }
        return -1;
    }

    private static String converter(Type type) {
        if (FILE.equals(type.getInternalName())) return "file";
        if (PATH.equals(type.getInternalName())) return "path";
        return "string";
    }

    private static String capitalized(Type type) {
        String value = converter(type);
        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }

    private static Site site(MethodInsnNode call) {
        Type[] arguments = Type.getArgumentTypes(call.desc);
        List<Integer> paths = new ArrayList<Integer>();
        if ("java/nio/file/Files".equals(call.owner)) {
            for (int index = 0; index < arguments.length; index++) {
                if (object(arguments[index], PATH)) paths.add(Integer.valueOf(index));
            }
            FileOperation operation = filesOperation(call.name);
            return operation == null || paths.isEmpty() ? null : new Site(operation, false, paths);
        }
        if ("java/nio/channels/FileChannel".equals(call.owner) && "open".equals(call.name)
                && arguments.length > 0 && object(arguments[0], PATH)) {
            paths.add(Integer.valueOf(0));
            return new Site(FileOperation.OPEN, false, paths);
        }
        if ("<init>".equals(call.name) && ("java/io/FileInputStream".equals(call.owner)
                || "java/io/FileOutputStream".equals(call.owner) || "java/io/RandomAccessFile".equals(call.owner))) {
            if (arguments.length == 0 || !isPath(arguments[0])) return null;
            paths.add(Integer.valueOf(0));
            FileOperation operation = "java/io/FileInputStream".equals(call.owner) ? FileOperation.READ
                    : "java/io/FileOutputStream".equals(call.owner) ? FileOperation.TRUNCATE : FileOperation.OPEN;
            return new Site(operation, false, paths);
        }
        if (FILE.equals(call.owner) && ("delete".equals(call.name) || "createNewFile".equals(call.name)
                || "mkdir".equals(call.name) || "mkdirs".equals(call.name) || "renameTo".equals(call.name))) {
            for (int index = 0; index < arguments.length; index++) if (isPath(arguments[index])) paths.add(Integer.valueOf(index));
            FileOperation operation = "delete".equals(call.name) ? FileOperation.DELETE
                    : "renameTo".equals(call.name) ? FileOperation.MOVE : FileOperation.CREATE;
            return new Site(operation, true, paths);
        }
        return null;
    }

    private static boolean isPath(Type type) {
        return object(type, PATH) || object(type, FILE) || object(type, "java/lang/String");
    }

    private static boolean object(Type type, String name) {
        return type.getSort() == Type.OBJECT && name.equals(type.getInternalName());
    }

    private static FileOperation filesOperation(String name) {
        if (name.startsWith("read") || "lines".equals(name) || "newBufferedReader".equals(name)) return FileOperation.READ;
        if (name.startsWith("write") || "newBufferedWriter".equals(name)) return FileOperation.WRITE;
        if (name.startsWith("create")) return FileOperation.CREATE;
        if (name.startsWith("delete")) return FileOperation.DELETE;
        if ("move".equals(name)) return FileOperation.MOVE;
        if ("copy".equals(name)) return FileOperation.COPY;
        if (name.startsWith("newInput") || name.startsWith("newOutput") || name.startsWith("newByteChannel")) return FileOperation.OPEN;
        return null;
    }

    public static final class Result {
        public final byte[] bytecode;
        public final int sites;

        Result(byte[] bytecode, int sites) {
            this.bytecode = bytecode;
            this.sites = sites;
        }
    }

    private static final class Site {
        final FileOperation operation;
        final boolean receiver;
        final List<Integer> pathArguments;

        Site(FileOperation operation, boolean receiver, List<Integer> pathArguments) {
            this.operation = operation;
            this.receiver = receiver;
            this.pathArguments = pathArguments;
        }
    }
}
