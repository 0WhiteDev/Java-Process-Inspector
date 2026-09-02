package dev.whitedev.jpi.debug;

import com.sun.jdi.BooleanType;
import com.sun.jdi.ByteType;
import com.sun.jdi.CharType;
import com.sun.jdi.DoubleType;
import com.sun.jdi.FloatType;
import com.sun.jdi.IntegerType;
import com.sun.jdi.LongType;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.ShortType;
import com.sun.jdi.Type;
import com.sun.jdi.Value;
import com.sun.jdi.VirtualMachine;
import com.sun.jdi.VoidType;

public final class ValueEditor {
    public Value parse(VirtualMachine vm, Type type, String input) {
        String value = input == null ? "" : input.trim();
        try {
            if (type instanceof VoidType) return vm.mirrorOfVoid();
            if (type instanceof BooleanType) return vm.mirrorOf(booleanValue(value));
            if (type instanceof ByteType) return vm.mirrorOf(Byte.parseByte(value));
            if (type instanceof ShortType) return vm.mirrorOf(Short.parseShort(value));
            if (type instanceof IntegerType) return vm.mirrorOf(Integer.parseInt(value));
            if (type instanceof LongType) return vm.mirrorOf(Long.parseLong(trimSuffix(value, 'l')));
            if (type instanceof FloatType) return vm.mirrorOf(Float.parseFloat(trimSuffix(value, 'f')));
            if (type instanceof DoubleType) return vm.mirrorOf(Double.parseDouble(trimSuffix(value, 'd')));
            if (type instanceof CharType) return vm.mirrorOf(charValue(value));
            if (type instanceof ReferenceType) {
                if ("null".equals(value)) return null;
                if ("java.lang.String".equals(type.name())) return vm.mirrorOf(stringValue(value));
                throw new IllegalArgumentException("Object references can only be set to null in the MVP editor");
            }
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("Value is not valid for " + type.name(), error);
        }
        throw new IllegalArgumentException("Editing is not supported for " + type.name());
    }

    private static boolean booleanValue(String value) {
        if ("true".equalsIgnoreCase(value)) return true;
        if ("false".equalsIgnoreCase(value)) return false;
        throw new IllegalArgumentException("Boolean value must be true or false");
    }

    private static char charValue(String value) {
        String decoded = stringValue(value);
        if (decoded.length() != 1) throw new IllegalArgumentException("Character value must contain one character");
        return decoded.charAt(0);
    }

    private static String stringValue(String value) {
        if (value.length() >= 2 && (value.startsWith("\"") && value.endsWith("\"")
                || value.startsWith("'") && value.endsWith("'"))) {
            value = value.substring(1, value.length() - 1);
        }
        return value.replace("\\n", "\n").replace("\\r", "\r")
                .replace("\\t", "\t").replace("\\\"", "\"").replace("\\'", "'")
                .replace("\\\\", "\\");
    }

    private static String trimSuffix(String value, char suffix) {
        return !value.isEmpty() && Character.toLowerCase(value.charAt(value.length() - 1)) == suffix
                ? value.substring(0, value.length() - 1) : value;
    }
}
