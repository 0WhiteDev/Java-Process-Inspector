package dev.whitedev.jpi.decompile;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MethodBodyCompatibilityTest {
    @Test void rejectsLambdasAndMethodReferences() {
        assertTrue(MethodBodyCompatibility.unsupportedReason("{ call(value -> use(value)); }").startsWith("Lambda"));
        assertTrue(MethodBodyCompatibility.unsupportedReason("{ call(this::use); }").startsWith("Method references"));
    }

    @Test void ignoresOperatorsInsideStringsAndComments() {
        assertNull(MethodBodyCompatibility.unsupportedReason("{ String value = \"-> ::\"; return value; }"));
    }
}