package dev.whitedev.jpi.debug;

import com.sun.jdi.Method;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.VirtualMachine;

import java.util.ArrayList;
import java.util.List;

public final class MethodLocationResolver {
    private final VirtualMachine vm;

    MethodLocationResolver(VirtualMachine vm) {
        this.vm = vm;
    }

    public long codeIndex(String className, String methodName, String descriptor, int instructionOrdinal) {
        if (instructionOrdinal < 0) throw new IllegalArgumentException("Instruction ordinal cannot be negative");
        Method method = method(className, methodName, descriptor);
        List<Integer> offsets = offsets(method.bytecodes());
        if (instructionOrdinal >= offsets.size()) {
            throw new IllegalArgumentException("CFG instruction is outside the loaded method bytecode");
        }
        return offsets.get(instructionOrdinal).longValue();
    }

    private Method method(String className, String methodName, String descriptor) {
        for (ReferenceType type : vm.classesByName(className)) {
            for (Method method : type.methodsByName(methodName)) {
                if (descriptor == null || descriptor.isBlank() || descriptor.equals(method.signature())) return method;
            }
        }
        throw new IllegalArgumentException("Method is not loaded in the debugger: " + className + "." + methodName + descriptor);
    }

    static List<Integer> offsets(byte[] code) {
        List<Integer> values = new ArrayList<>();
        int index = 0;
        while (index < code.length) {
            values.add(Integer.valueOf(index));
            int opcode = code[index] & 0xff;
            int length = length(code, index, opcode);
            if (length < 1 || index + length > code.length) {
                throw new IllegalArgumentException("Invalid JVM bytecode at BCI " + index);
            }
            index += length;
        }
        return List.copyOf(values);
    }

    private static int length(byte[] code, int index, int opcode) {
        if (opcode == 170) {
            int aligned = aligned(index + 1);
            int low = integer(code, aligned + 4);
            int high = integer(code, aligned + 8);
            long entries = (long) high - low + 1L;
            if (entries < 0L || entries > 1_000_000L) return -1;
            return Math.toIntExact(aligned - index + 12L + entries * 4L);
        }
        if (opcode == 171) {
            int aligned = aligned(index + 1);
            int pairs = integer(code, aligned + 4);
            if (pairs < 0 || pairs > 1_000_000) return -1;
            return Math.toIntExact(aligned - index + 8L + (long) pairs * 8L);
        }
        if (opcode == 196) {
            if (index + 1 >= code.length) return -1;
            return (code[index + 1] & 0xff) == 132 ? 6 : 4;
        }
        if (opcode == 16 || opcode == 18 || opcode == 188 || opcode >= 21 && opcode <= 25
                || opcode >= 54 && opcode <= 58 || opcode == 169) return 2;
        if (opcode == 17 || opcode == 19 || opcode == 20 || opcode == 132
                || opcode >= 153 && opcode <= 168 || opcode == 198 || opcode == 199
                || opcode >= 178 && opcode <= 184 || opcode == 187 || opcode == 189
                || opcode == 192 || opcode == 193) return 3;
        if (opcode == 197) return 4;
        if (opcode == 185 || opcode == 186 || opcode == 200 || opcode == 201) return 5;
        return 1;
    }

    private static int aligned(int value) {
        return (value + 3) & ~3;
    }

    private static int integer(byte[] value, int offset) {
        if (offset < 0 || offset + 4 > value.length) throw new IllegalArgumentException("Truncated switch instruction");
        return (value[offset] & 0xff) << 24 | (value[offset + 1] & 0xff) << 16
                | (value[offset + 2] & 0xff) << 8 | value[offset + 3] & 0xff;
    }
}
