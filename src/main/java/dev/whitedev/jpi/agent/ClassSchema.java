package dev.whitedev.jpi.agent;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class ClassSchema {
    private static final int MAGIC = 0xCAFEBABE;
    final String name;
    final String superName;
    final int access;
    final List<String> interfaces;
    final List<String> fields;
    final List<String> methods;

    private ClassSchema(String name, String superName, int access, List<String> interfaces,
                        List<String> fields, List<String> methods) {
        this.name = name;
        this.superName = superName;
        this.access = access;
        this.interfaces = interfaces;
        this.fields = fields;
        this.methods = methods;
    }

    static ClassSchema read(byte[] bytecode) throws IOException {
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytecode))) {
            if (input.readInt() != MAGIC) throw new IOException("Invalid class-file magic");
            input.readUnsignedShort();
            input.readUnsignedShort();
            int constantCount = input.readUnsignedShort();
            Object[] constants = new Object[constantCount];
            for (int index = 1; index < constantCount; index++) {
                int tag = input.readUnsignedByte();
                switch (tag) {
                    case 1:
                        constants[index] = input.readUTF();
                        break;
                    case 3:
                    case 4:
                        skipFully(input, 4);
                        break;
                    case 5:
                    case 6:
                        skipFully(input, 8);
                        index++;
                        break;
                    case 7:
                        constants[index] = input.readUnsignedShort();
                        break;
                    case 8:
                    case 16:
                    case 19:
                    case 20:
                        skipFully(input, 2);
                        break;
                    case 9:
                    case 10:
                    case 11:
                    case 12:
                    case 17:
                    case 18:
                        skipFully(input, 4);
                        break;
                    case 15:
                        skipFully(input, 3);
                        break;
                    default:
                        throw new IOException("Unsupported constant-pool tag " + tag);
                }
            }
            int access = input.readUnsignedShort();
            String name = className(constants, input.readUnsignedShort());
            int superIndex = input.readUnsignedShort();
            String superName = superIndex == 0 ? "" : className(constants, superIndex);
            int interfaceCount = input.readUnsignedShort();
            List<String> interfaces = new ArrayList<>();
            for (int index = 0; index < interfaceCount; index++) interfaces.add(className(constants, input.readUnsignedShort()));
            List<String> fields = members(input, constants);
            List<String> methods = members(input, constants);
            Collections.sort(interfaces);
            Collections.sort(fields);
            Collections.sort(methods);
            return new ClassSchema(name, superName, access, interfaces, fields, methods);
        }
    }

    static void verifyCompatible(byte[] current, byte[] replacement) throws IOException {
        ClassSchema before = read(current);
        ClassSchema after = read(replacement);
        if (!before.name.equals(after.name)) throw new IOException("Compiled class name changed from " + before.name + " to " + after.name);
        if (!before.superName.equals(after.superName)) throw new IOException("Changing the superclass is not supported by standard HotSwap");
        if (before.access != after.access) throw new IOException("Changing class modifiers is not supported by standard HotSwap");
        if (!before.interfaces.equals(after.interfaces)) throw new IOException("Changing implemented interfaces is not supported by standard HotSwap");
        if (!before.fields.equals(after.fields)) throw new IOException("Adding, removing, or changing fields is not supported by standard HotSwap");
        if (!before.methods.equals(after.methods)) throw new IOException("Adding, removing, or changing method signatures is not supported by standard HotSwap");
    }

    private static List<String> members(DataInputStream input, Object[] constants) throws IOException {
        int count = input.readUnsignedShort();
        List<String> members = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            int access = input.readUnsignedShort();
            String name = utf8(constants, input.readUnsignedShort());
            String descriptor = utf8(constants, input.readUnsignedShort());
            members.add(Integer.toHexString(access) + " " + name + " " + descriptor);
            int attributes = input.readUnsignedShort();
            for (int attribute = 0; attribute < attributes; attribute++) {
                input.readUnsignedShort();
                long length = Integer.toUnsignedLong(input.readInt());
                skipFully(input, length);
            }
        }
        return members;
    }

    private static String className(Object[] constants, int index) throws IOException {
        Object value = constants[index];
        if (!(value instanceof Integer)) throw new IOException("Invalid class constant at " + index);
        return utf8(constants, (Integer) value).replace('/', '.');
    }

    private static String utf8(Object[] constants, int index) throws IOException {
        Object value = constants[index];
        if (!(value instanceof String)) throw new IOException("Invalid UTF-8 constant at " + index);
        return (String) value;
    }

    private static void skipFully(DataInputStream input, long length) throws IOException {
        while (length > 0) {
            long skipped = input.skip(length);
            if (skipped > 0) {
                length -= skipped;
            } else if (input.read() < 0) {
                throw new IOException("Unexpected end of class file");
            } else {
                length--;
            }
        }
    }
}
