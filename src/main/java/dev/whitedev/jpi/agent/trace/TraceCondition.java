package dev.whitedev.jpi.agent.trace;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

abstract class TraceCondition {
    private static final Pattern ARGUMENT_NULL = Pattern.compile("\\$(\\d+)\\s*(==|!=)\\s*null");
    private static final Pattern ARGUMENT_CONTAINS = Pattern.compile("\\$(\\d+)\\.contains\\(\"(.*)\"\\)");
    private static final Pattern RESULT_COMPARE = Pattern.compile("returnValue\\s*(==|!=)\\s*(.+)");
    private static final Pattern EXCEPTION_NULL = Pattern.compile("exception\\s*(==|!=)\\s*null");
    private static final Pattern THREAD_CONTAINS = Pattern.compile("thread\\.name\\.contains\\(\"(.*)\"\\)");
    private static final Pattern DURATION = Pattern.compile("duration\\s*(>=|<=|==|>|<)\\s*([0-9]+(?:\\.[0-9]+)?)\\s*(ns|us|ms|s)?");

    abstract boolean matches(Context context);

    static List<TraceCondition> parse(String expression) {
        if (expression == null || expression.trim().isEmpty()) return Collections.emptyList();
        List<TraceCondition> conditions = new ArrayList<TraceCondition>();
        for (String value : split(expression)) conditions.add(parseSingle(value.trim()));
        return conditions;
    }

    static boolean matches(List<TraceCondition> conditions, Context context) {
        for (TraceCondition condition : conditions) {
            if (!condition.matches(context)) return false;
        }
        return true;
    }

    private static TraceCondition parseSingle(String expression) {
        Matcher matcher = ARGUMENT_NULL.matcher(expression);
        if (matcher.matches()) {
            final int index = Integer.parseInt(matcher.group(1));
            final boolean equal = "==".equals(matcher.group(2));
            return new TraceCondition() {
                @Override boolean matches(Context context) {
                    return (context.argument(index) == null) == equal;
                }
            };
        }

        matcher = ARGUMENT_CONTAINS.matcher(expression);
        if (matcher.matches()) {
            final int index = Integer.parseInt(matcher.group(1));
            final String expected = unescape(matcher.group(2));
            return new TraceCondition() {
                @Override boolean matches(Context context) {
                    Object value = context.argument(index);
                    return value instanceof String && ((String) value).contains(expected);
                }
            };
        }

        matcher = RESULT_COMPARE.matcher(expression);
        if (matcher.matches()) {
            final boolean equal = "==".equals(matcher.group(1));
            final String expected = matcher.group(2).trim();
            return new TraceCondition() {
                @Override boolean matches(Context context) {
                    return valueEquals(context.returnValue, expected) == equal;
                }
            };
        }

        matcher = EXCEPTION_NULL.matcher(expression);
        if (matcher.matches()) {
            final boolean equal = "==".equals(matcher.group(1));
            return new TraceCondition() {
                @Override boolean matches(Context context) {
                    return (context.exception == null) == equal;
                }
            };
        }

        matcher = THREAD_CONTAINS.matcher(expression);
        if (matcher.matches()) {
            final String expected = unescape(matcher.group(1));
            return new TraceCondition() {
                @Override boolean matches(Context context) {
                    return context.threadName.contains(expected);
                }
            };
        }

        matcher = DURATION.matcher(expression);
        if (matcher.matches()) {
            final String operator = matcher.group(1);
            final double expected = toNanos(Double.parseDouble(matcher.group(2)), matcher.group(3));
            return new TraceCondition() {
                @Override boolean matches(Context context) {
                    return compare(context.durationNanos, expected, operator);
                }
            };
        }

        throw new IllegalArgumentException("Unsupported trace condition: " + expression);
    }

    private static List<String> split(String expression) {
        List<String> values = new ArrayList<String>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        boolean escaped = false;
        for (int index = 0; index < expression.length(); index++) {
            char value = expression.charAt(index);
            if (escaped) {
                current.append(value);
                escaped = false;
            } else if (value == '\\' && quoted) {
                current.append(value);
                escaped = true;
            } else if (value == '"') {
                current.append(value);
                quoted = !quoted;
            } else if (!quoted && value == '&' && index + 1 < expression.length()
                    && expression.charAt(index + 1) == '&') {
                if (current.toString().trim().isEmpty()) throw new IllegalArgumentException("Empty trace condition");
                values.add(current.toString());
                current.setLength(0);
                index++;
            } else {
                current.append(value);
            }
        }
        if (quoted) throw new IllegalArgumentException("Unclosed quote in trace condition");
        if (current.toString().trim().isEmpty()) throw new IllegalArgumentException("Empty trace condition");
        values.add(current.toString());
        return values;
    }

    private static boolean valueEquals(Object value, String expected) {
        if ("null".equals(expected)) return value == null;
        if (value == null) return false;
        if (expected.length() >= 2 && expected.startsWith("\"") && expected.endsWith("\"")) {
            String literal = unescape(expected.substring(1, expected.length() - 1));
            return value instanceof String && ((String) value).equals(literal)
                    || value instanceof Character && String.valueOf(value).equals(literal);
        }
        if ("true".equalsIgnoreCase(expected) || "false".equalsIgnoreCase(expected)) {
            return value instanceof Boolean && ((Boolean) value).booleanValue() == Boolean.parseBoolean(expected);
        }
        try {
            return value instanceof Number
                    && Double.compare(((Number) value).doubleValue(), Double.parseDouble(expected)) == 0;
        } catch (NumberFormatException ignored) {
            return value instanceof Enum<?> && ((Enum<?>) value).name().equals(expected);
        }
    }

    private static double toNanos(double value, String unit) {
        if (unit == null || "ms".equals(unit)) return value * 1_000_000d;
        if ("s".equals(unit)) return value * 1_000_000_000d;
        if ("us".equals(unit)) return value * 1_000d;
        return value;
    }

    private static boolean compare(double actual, double expected, String operator) {
        if (">".equals(operator)) return actual > expected;
        if ("<".equals(operator)) return actual < expected;
        if (">=".equals(operator)) return actual >= expected;
        if ("<=".equals(operator)) return actual <= expected;
        return Double.compare(actual, expected) == 0;
    }

    private static String unescape(String value) {
        return value.replace("\\n", "\n").replace("\\\"", "\"").replace("\\\\", "\\");
    }

    static final class Context {
        final Object receiver;
        final Object[] arguments;
        final Object returnValue;
        final Throwable exception;
        final long durationNanos;
        final String threadName;

        Context(Object receiver, Object[] arguments, Object returnValue, Throwable exception,
                long durationNanos, String threadName) {
            this.receiver = receiver;
            this.arguments = arguments;
            this.returnValue = returnValue;
            this.exception = exception;
            this.durationNanos = durationNanos;
            this.threadName = threadName;
        }

        Object argument(int index) {
            if (index == 0) return receiver;
            int argumentIndex = index - 1;
            return argumentIndex >= 0 && argumentIndex < arguments.length ? arguments[argumentIndex] : null;
        }
    }
}