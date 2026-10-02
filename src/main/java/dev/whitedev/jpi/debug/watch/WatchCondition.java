package dev.whitedev.jpi.debug.watch;

import com.sun.jdi.BooleanValue;
import com.sun.jdi.CharValue;
import com.sun.jdi.ObjectReference;
import com.sun.jdi.PrimitiveValue;
import com.sun.jdi.StringReference;
import com.sun.jdi.Value;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public record WatchCondition(String operator, Object operand) {
    private static final Pattern SYNTAX = Pattern.compile("^(==|!=|<=|>=|<|>)\\s*(.+)$");

    public static WatchCondition parse(String text) {
        Matcher matcher = SYNTAX.matcher(text == null ? "" : text.trim());
        if (!matcher.matches()) throw new IllegalArgumentException("Use a comparison such as < 5, == false, != null, or == \"ready\"");
        String literal = matcher.group(2).trim();
        Object operand;
        if ("null".equals(literal)) operand = null;
        else if ("true".equals(literal) || "false".equals(literal)) operand = Boolean.valueOf(literal);
        else if (literal.startsWith("\"") && literal.endsWith("\"") && literal.length() >= 2) {
            operand = literal.substring(1, literal.length() - 1);
            if (((String) operand).contains("\"") || ((String) operand).contains("\\")) {
                throw new IllegalArgumentException("String comparisons currently accept plain text without escapes");
            }
        } else {
            try { operand = new BigDecimal(literal); }
            catch (NumberFormatException error) { throw new IllegalArgumentException("Comparison requires a numeric, boolean, null, or quoted string literal"); }
        }
        String operator = matcher.group(1);
        if (!(operand instanceof BigDecimal) && !"==".equals(operator) && !"!=".equals(operator)) {
            throw new IllegalArgumentException("Ordering comparisons require numbers");
        }
        return new WatchCondition(operator, operand);
    }

    public boolean matches(Value value) { return matchesScalar(scalar(value)); }

    public boolean matchesScalar(Object value) {
        boolean equal;
        if (operand instanceof BigDecimal number) {
            if (value instanceof SpecialNumber special) {
                if (Double.isNaN(special.value())) return "!=".equals(operator);
                int comparison = special.value() < 0 ? -1 : 1;
                return switch (operator) {
                    case "!=" -> true;
                    case "<", "<=" -> comparison < 0;
                    case ">", ">=" -> comparison > 0;
                    default -> false;
                };
            }
            if (!(value instanceof BigDecimal actual)) throw new IllegalArgumentException("Watched value is not numeric");
            int comparison = actual.compareTo(number);
            return switch (operator) {
                case "==" -> comparison == 0;
                case "!=" -> comparison != 0;
                case "<" -> comparison < 0;
                case "<=" -> comparison <= 0;
                case ">" -> comparison > 0;
                case ">=" -> comparison >= 0;
                default -> false;
            };
        }
        if (value != null && operand instanceof Boolean && !(value instanceof Boolean)) {
            throw new IllegalArgumentException("Watched value is not boolean");
        }
        if (value != null && operand instanceof String && !(value instanceof String)) {
            throw new IllegalArgumentException("Watched value is not a string");
        }
        equal = Objects.equals(value, operand);
        return "==".equals(operator) ? equal : !equal;
    }

    public static boolean changed(Value before, Value after) {
        Object left = scalar(before), right = scalar(after);
        if (left instanceof BigDecimal a && right instanceof BigDecimal b) return a.compareTo(b) != 0;
        return !Objects.equals(left, right);
    }

    private static Object scalar(Value value) {
        if (value == null) return null;
        if (value instanceof BooleanValue bool) return bool.value();
        if (value instanceof CharValue character) return BigDecimal.valueOf(character.value());
        if (value instanceof PrimitiveValue primitive) {
            try { return new BigDecimal(primitive.toString()); }
            catch (NumberFormatException error) { return new SpecialNumber(primitive.doubleValue()); }
        }
        if (value instanceof StringReference string) return string.value();
        if (value instanceof ObjectReference object) return new Identity(object.uniqueID());
        throw new IllegalArgumentException("Unsupported value type");
    }

    private record Identity(long id) {}
    private record SpecialNumber(double value) {}
}
