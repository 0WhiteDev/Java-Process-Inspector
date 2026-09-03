package dev.whitedev.jpi.debug;

import com.sun.jdi.AbsentInformationException;
import com.sun.jdi.ArrayReference;
import com.sun.jdi.ArrayType;
import com.sun.jdi.ClassType;
import com.sun.jdi.Field;
import com.sun.jdi.IncompatibleThreadStateException;
import com.sun.jdi.LocalVariable;
import com.sun.jdi.Location;
import com.sun.jdi.ObjectReference;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.StackFrame;
import com.sun.jdi.ThreadReference;
import com.sun.jdi.Value;
import com.sun.jdi.VirtualMachine;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

public final class StackFrameManager {
    private static final int MAX_FRAMES = 256;
    private static final int MAX_ITEMS = 100;
    private static final int MAX_DEPTH = 3;
    private final VirtualMachine vm;
    private final ThreadManager threads;
    private final ValueFormatter formatter = new ValueFormatter();
    private final ValueEditor editor = new ValueEditor();

    StackFrameManager(VirtualMachine vm, ThreadManager threads) {
        this.vm = vm;
        this.threads = threads;
    }

    public List<FrameView> frames(long threadId) throws IncompatibleThreadStateException {
        ThreadReference thread = threads.find(threadId);
        List<StackFrame> frames = thread.frames(0, Math.min(thread.frameCount(), MAX_FRAMES));
        List<FrameView> values = new ArrayList<>();
        for (int index = 0; index < frames.size(); index++) {
            Location location = frames.get(index).location();
            values.add(new FrameView(threadId, index, location.declaringType().name(),
                    location.method().name(), location.method().signature(), location.lineNumber(),
                    location.codeIndex(), source(location)));
        }
        return List.copyOf(values);
    }

    public List<VariableView> variables(long threadId, int frameIndex) throws Exception {
        StackFrame frame = frame(threadId, frameIndex);
        List<VariableView> values = new ArrayList<>();
        ObjectReference receiver = frame.thisObject();
        if (receiver != null) values.add(variable("this", receiver.referenceType().name(), receiver,
                Kind.THIS, null, null, receiver, null, null, -1));
        try {
            List<LocalVariable> locals = frame.visibleVariables();
            Map<LocalVariable, Value> current = frame.getValues(locals);
            for (LocalVariable local : locals) {
                values.add(variable(local.name(), local.typeName(), current.get(local),
                        local.isArgument() ? Kind.ARGUMENT : Kind.LOCAL,
                        frame, local, null, null, null, -1));
            }
        } catch (AbsentInformationException error) {
            List<Value> arguments = frame.getArgumentValues();
            for (int index = 0; index < arguments.size(); index++) {
                Value value = arguments.get(index);
                values.add(variable("$" + (index + 1), typeName(value), value,
                        Kind.ARGUMENT_READ_ONLY, null, null, null, null, null, -1));
            }
        }
        ReferenceType owner = frame.location().declaringType();
        List<Field> fields = new ArrayList<>();
        for (Field field : owner.allFields()) if (field.isStatic()) fields.add(field);
        fields.sort(Comparator.comparing(Field::name));
        for (int index = 0; index < Math.min(MAX_ITEMS, fields.size()); index++) {
            Field field = fields.get(index);
            values.add(variable(field.name(), field.typeName(), owner.getValue(field), Kind.STATIC_FIELD,
                    null, null, null, owner, field, -1));
        }
        return List.copyOf(values);
    }

    public String locationDetails(long threadId, int frameIndex) throws IncompatibleThreadStateException {
        StackFrame frame = frame(threadId, frameIndex);
        Location location = frame.location();
        StringBuilder output = new StringBuilder();
        output.append(location.declaringType().name()).append('.').append(location.method().name())
                .append(location.method().signature()).append('\n')
                .append("Source: ").append(source(location).isEmpty() ? "not available" : source(location)).append('\n')
                .append("Line: ").append(location.lineNumber() < 0 ? "not available" : location.lineNumber()).append('\n')
                .append("Bytecode index: ").append(location.codeIndex()).append('\n');
        try {
            byte[] bytecode = location.method().bytecodes();
            int center = Math.toIntExact(Math.min(location.codeIndex(), Integer.MAX_VALUE));
            int from = Math.max(0, center - 16);
            int to = Math.min(bytecode.length, center + 32);
            output.append("\nBytecode ").append(from).append("..").append(Math.max(from, to - 1)).append('\n');
            for (int index = from; index < to; index++) {
                if ((index - from) % 16 == 0) output.append(String.format("%04d  ", index));
                output.append(String.format("%02X ", bytecode[index] & 0xff));
                if ((index - from) % 16 == 15 || index == to - 1) output.append('\n');
            }
        } catch (RuntimeException ignored) {
            output.append("\nBytecode is not exposed by this target JVM.\n");
        }
        return output.toString();
    }

    public List<VariableView> children(VariableView variable) {
        Value value = variable.value;
        List<VariableView> values = new ArrayList<>();
        if (value instanceof ArrayReference array) {
            int count = Math.min(MAX_ITEMS, array.length());
            for (int index = 0; index < count; index++) {
                Value item = array.getValue(index);
                values.add(childVariable(variable, "[" + index + "]", typeName(item), item, Kind.ARRAY_ITEM,
                        null, null, null, null, index, array));
            }
        } else if (value instanceof ObjectReference object) {
            List<Field> fields = new ArrayList<>();
            for (Field field : object.referenceType().allFields()) if (!field.isStatic()) fields.add(field);
            fields.sort(Comparator.comparing(Field::name));
            Map<Field, Value> current = object.getValues(fields.subList(0, Math.min(MAX_ITEMS, fields.size())));
            for (Map.Entry<Field, Value> entry : current.entrySet()) {
                Field field = entry.getKey();
                values.add(childVariable(variable, field.name(), field.typeName(), entry.getValue(),
                        Kind.INSTANCE_FIELD, null, object, null, field, -1, null));
            }
        }
        return List.copyOf(values);
    }

    public Change setValue(VariableView variable, String input) throws Exception {
        if (!variable.editable()) throw new IllegalArgumentException("This debugger value is read-only");
        String before = formatter.format(variable.value);
        Value replacement;
        if (variable.local != null) {
            replacement = editor.parse(vm, variable.local.type(), input);
            variable.frame.setValue(variable.local, replacement);
        } else if (variable.field != null && variable.object != null) {
            replacement = editor.parse(vm, variable.field.type(), input);
            variable.object.setValue(variable.field, replacement);
        } else if (variable.field != null && variable.owner instanceof ClassType owner) {
            replacement = editor.parse(vm, variable.field.type(), input);
            owner.setValue(variable.field, replacement);
        } else if (variable.array != null) {
            replacement = editor.parse(vm, ((ArrayType) variable.array.referenceType()).componentType(), input);
            variable.array.setValue(variable.index, replacement);
        } else {
            throw new IllegalArgumentException("This debugger value cannot be changed");
        }
        return new Change(variable.name, before, formatter.format(replacement));
    }

    StackFrame frame(long threadId, int frameIndex) throws IncompatibleThreadStateException {
        return threads.find(threadId).frame(frameIndex);
    }

    private VariableView variable(String name, String type, Value value, Kind kind, StackFrame frame,
                                  LocalVariable local, ObjectReference object, ReferenceType owner,
                                  Field field, int index) {
        return variable(name, type, value, kind, frame, local, object, owner, field, index, null);
    }

    private VariableView variable(String name, String type, Value value, Kind kind, StackFrame frame,
                                  LocalVariable local, ObjectReference object, ReferenceType owner,
                                  Field field, int index, ArrayReference array) {
        return new VariableView(name, type, formatter.format(value), expandable(value, 0), kind,
                value, frame, local, object, owner, field, array, index, 0);
    }

    private VariableView childVariable(VariableView parent, String name, String type, Value value, Kind kind,
                                       LocalVariable local, ObjectReference object, ReferenceType owner,
                                       Field field, int index, ArrayReference array) {
        int depth = parent.depth + 1;
        return new VariableView(name, type, formatter.format(value), expandable(value, depth), kind,
                value, null, local, object, owner, field, array, index, depth);
    }

    private static boolean expandable(Value value, int depth) {
        return depth < MAX_DEPTH && value instanceof ObjectReference
                && !(value instanceof com.sun.jdi.StringReference);
    }

    private static String typeName(Value value) {
        return value == null ? "java.lang.Object" : value.type().name();
    }

    private static String source(Location location) {
        try {
            return location.sourcePath();
        } catch (AbsentInformationException error) {
            return "";
        }
    }

    public record FrameView(long threadId, int index, String className, String methodName,
                            String descriptor, int sourceLine, long codeIndex, String sourcePath) {
        @Override public String toString() {
            return "#" + index + " " + className + "." + methodName + descriptor
                    + (sourceLine < 0 ? " @ BCI " + codeIndex : "  line " + sourceLine);
        }
    }

    public static final class VariableView {
        private final String name;
        private final String type;
        private final String displayValue;
        private final boolean expandable;
        private final Kind kind;
        private final Value value;
        private final StackFrame frame;
        private final LocalVariable local;
        private final ObjectReference object;
        private final ReferenceType owner;
        private final Field field;
        private final ArrayReference array;
        private final int index;
        private final int depth;

        VariableView(String name, String type, String displayValue, boolean expandable, Kind kind,
                     Value value, StackFrame frame, LocalVariable local, ObjectReference object,
                     ReferenceType owner, Field field, ArrayReference array, int index, int depth) {
            this.name = name;
            this.type = type;
            this.displayValue = displayValue;
            this.expandable = expandable;
            this.kind = kind;
            this.value = value;
            this.frame = frame;
            this.local = local;
            this.object = object;
            this.owner = owner;
            this.field = field;
            this.array = array;
            this.index = index;
            this.depth = depth;
        }

        public String name() { return name; }
        public String type() { return type; }
        public String displayValue() { return displayValue; }
        public boolean expandable() { return expandable; }
        public Kind kind() { return kind; }
        public boolean editable() {
            return local != null || field != null && (object != null || owner instanceof ClassType) || array != null;
        }

        @Override public String toString() {
            return name + " : " + type + " = " + displayValue;
        }
    }

    public enum Kind { THIS, ARGUMENT, ARGUMENT_READ_ONLY, LOCAL, INSTANCE_FIELD, STATIC_FIELD, ARRAY_ITEM }
    public record Change(String name, String before, String after) { }
}
