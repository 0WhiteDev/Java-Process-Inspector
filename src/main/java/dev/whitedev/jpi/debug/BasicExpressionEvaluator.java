package dev.whitedev.jpi.debug;

import com.sun.jdi.AbsentInformationException;
import com.sun.jdi.ArrayReference;
import com.sun.jdi.Field;
import com.sun.jdi.LocalVariable;
import com.sun.jdi.ObjectReference;
import com.sun.jdi.StackFrame;
import com.sun.jdi.Value;
import com.sun.jdi.VirtualMachine;

public final class BasicExpressionEvaluator {
    private final VirtualMachine vm;
    private final ThreadManager threads;
    private final ValueFormatter formatter = new ValueFormatter();

    BasicExpressionEvaluator(VirtualMachine vm, ThreadManager threads) {
        this.vm = vm;
        this.threads = threads;
    }

    public String evaluate(long threadId, int frameIndex, String expression) throws Exception {
        String text = expression == null ? "" : expression.trim();
        if (text.isEmpty()) throw new IllegalArgumentException("Enter an expression");
        Value value = literal(text);
        if (value == null && !"null".equals(text)) value = resolve(threads.find(threadId).frame(frameIndex), text);
        return formatter.format(value);
    }

    private Value resolve(StackFrame frame, String expression) throws Exception {
        int position = boundary(expression, 0);
        Value current = root(frame, expression.substring(0, position));
        while (position < expression.length()) {
            char marker = expression.charAt(position);
            if (marker == '.') {
                int start = ++position;
                position = boundary(expression, start);
                if (start == position) throw new IllegalArgumentException("Missing field name");
                current = member(current, expression.substring(start, position));
            } else if (marker == '[') {
                int close = expression.indexOf(']', position + 1);
                if (close < 0) throw new IllegalArgumentException("Missing closing array bracket");
                current = arrayItem(current, expression.substring(position + 1, close));
                position = close + 1;
            } else {
                throw new IllegalArgumentException("Unexpected expression character at " + position);
            }
        }
        return current;
    }

    private static int boundary(String value, int start) {
        int position = start;
        while (position < value.length() && value.charAt(position) != '.' && value.charAt(position) != '[') position++;
        return position;
    }

    private static Value arrayItem(Value value, String indexText) {
        if (!(value instanceof ArrayReference array)) throw new IllegalArgumentException("Value is not an array");
        final int index;
        try {
            index = Integer.parseInt(indexText.trim());
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("Array index must be an integer", error);
        }
        if (index < 0 || index >= array.length()) throw new IllegalArgumentException("Array index is out of bounds");
        return array.getValue(index);
    }

    private Value root(StackFrame frame, String name) throws Exception {
        if ("this".equals(name)) return frame.thisObject();
        try {
            LocalVariable variable = frame.visibleVariableByName(name);
            if (variable != null) return frame.getValue(variable);
        } catch (AbsentInformationException ignored) {
        }
        ObjectReference receiver = frame.thisObject();
        if (receiver != null) {
            Field field = receiver.referenceType().fieldByName(name);
            if (field != null) return receiver.getValue(field);
        }
        Field field = frame.location().declaringType().fieldByName(name);
        if (field != null && field.isStatic()) return frame.location().declaringType().getValue(field);
        throw new IllegalArgumentException("Unknown variable or field: " + name);
    }

    private static Value member(Value value, String name) {
        if (value instanceof ArrayReference array && "length".equals(name)) {
            return array.virtualMachine().mirrorOf(array.length());
        }
        if (!(value instanceof ObjectReference object)) {
            throw new IllegalArgumentException("Cannot read " + name + " from a non-object value");
        }
        Field field = object.referenceType().fieldByName(name);
        if (field == null) throw new IllegalArgumentException("Unknown field: " + name);
        return object.getValue(field);
    }

    private Value literal(String text) {
        if ("null".equals(text)) return null;
        if ("true".equals(text) || "false".equals(text)) return vm.mirrorOf(Boolean.parseBoolean(text));
        if (text.length() >= 2 && text.startsWith("\"") && text.endsWith("\"")) {
            return vm.mirrorOf(text.substring(1, text.length() - 1));
        }
        try {
            if (text.endsWith("L") || text.endsWith("l")) return vm.mirrorOf(Long.parseLong(text.substring(0, text.length() - 1)));
            if (text.contains(".") || text.endsWith("D") || text.endsWith("d")) {
                String value = text.endsWith("D") || text.endsWith("d") ? text.substring(0, text.length() - 1) : text;
                return vm.mirrorOf(Double.parseDouble(value));
            }
            return vm.mirrorOf(Integer.parseInt(text));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
