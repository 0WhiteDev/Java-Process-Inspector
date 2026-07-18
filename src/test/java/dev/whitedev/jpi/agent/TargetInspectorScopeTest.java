package dev.whitedev.jpi.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TargetInspectorScopeTest {
    @Test void defaultScopeAcceptsOnlyClassesWithoutNamedPackages() {
        assertTrue(TargetInspector.within("Main", "<default>"));
        assertTrue(TargetInspector.within("Main$Worker", "<default>"));
        assertFalse(TargetInspector.within("app.Main", "<default>"));
        assertFalse(TargetInspector.within("java.lang.String", "<default>"));
    }

    @Test void namedScopeKeepsItsExistingPrefixRules() {
        assertTrue(TargetInspector.within("app.feature.Main", "app.feature"));
        assertTrue(TargetInspector.within("app.feature.Main$Worker", "app.feature"));
        assertFalse(TargetInspector.within("app.other.Main", "app.feature"));
    }
}