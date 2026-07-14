package dev.whitedev.jpi.agent;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TraceConditionTest {
    @Test void evaluatesSupportedRuntimeFilters() {
        TraceCondition.Context context = new TraceCondition.Context(
                new Object(), new Object[]{"access-token", Integer.valueOf(5)}, Boolean.FALSE,
                null, 12_000_000L, "Worker-2");

        List<TraceCondition> conditions = TraceCondition.parse(
                "$1 != null && $1.contains(\"token\") && returnValue == false"
                        + " && exception == null && thread.name.contains(\"Worker\") && duration > 10ms");

        assertTrue(TraceCondition.matches(conditions, context));
        assertFalse(TraceCondition.matches(TraceCondition.parse("$2 == null"), context));
        assertThrows(IllegalArgumentException.class, () -> TraceCondition.parse("unknown.value == true"));
    }
}