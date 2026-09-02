package dev.whitedev.jpi.debug;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BreakpointSpecTest {
    @Test void formatsMethodLineAndBytecodeLocations() {
        assertEquals("sample.Target.run()V", spec(BreakpointSpec.Type.METHOD, null, null).location());
        assertEquals("sample.Target:42", spec(BreakpointSpec.Type.LINE, 42, null).location());
        assertEquals("sample.Target.run()V @ BCI 19", spec(BreakpointSpec.Type.BYTECODE, null, 19L).location());
    }

    @Test void validatesRequiredLocations() {
        assertThrows(IllegalArgumentException.class, () -> new BreakpointSpec("sample.Target", "run", "()V",
                0, null, BreakpointSpec.Type.LINE, BreakpointSpec.SuspendPolicy.THREAD, true));
        assertThrows(IllegalArgumentException.class, () -> new BreakpointSpec("sample.Target", "run", "()V",
                null, -1L, BreakpointSpec.Type.BYTECODE, BreakpointSpec.SuspendPolicy.THREAD, true));
        assertThrows(IllegalArgumentException.class, () -> new BreakpointSpec("sample.Target", "", "()V",
                null, null, BreakpointSpec.Type.METHOD, BreakpointSpec.SuspendPolicy.THREAD, true));
    }

    private static BreakpointSpec spec(BreakpointSpec.Type type, Integer line, Long bci) {
        return new BreakpointSpec("sample.Target", "run", "()V", line, bci, type,
                BreakpointSpec.SuspendPolicy.THREAD, true);
    }
}
