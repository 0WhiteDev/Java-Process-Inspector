package dev.whitedev.jpi.debug;

import com.sun.jdi.ArrayReference;
import com.sun.jdi.ObjectReference;
import com.sun.jdi.PrimitiveValue;
import com.sun.jdi.StringReference;
import com.sun.jdi.Value;

public final class ValueFormatter {
    private static final int MAX_STRING = 4096;

    public String format(Value value) {
        if (value == null) return "null";
        if (value instanceof StringReference string) {
            String text = string.value();
            if (text.length() > MAX_STRING) text = text.substring(0, MAX_STRING) + "...";
            return '"' + escaped(text) + '"';
        }
        if (value instanceof PrimitiveValue) return value.toString();
        if (value instanceof ArrayReference array) {
            return array.referenceType().name() + "[" + array.length() + "] @" + array.uniqueID();
        }
        if (value instanceof ObjectReference object) {
            return object.referenceType().name() + " @" + object.uniqueID();
        }
        return value.toString();
    }

    private static String escaped(String value) {
        return value.replace("\\", "\\\\").replace("\r", "\\r")
                .replace("\n", "\\n").replace("\t", "\\t").replace("\"", "\\\"");
    }
}
