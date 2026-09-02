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
        String[] path = expression.split("\\.");
        Value current = root(frame, path[0]);
        for (int index = 1; index < path.length; index++) current = member(current, path[index]);
        return current;
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
