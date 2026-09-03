package dev.whitedev.jpi.debug;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MethodLocationResolverTest {
    @Test void resolvesFixedWideAndLookupSwitchInstructionOffsets() {
        byte[] code = new byte[]{
                3,
                16, 10,
                (byte) 196, 21, 1, 0,
                (byte) 171,
                0, 0, 0, 12,
                0, 0, 0, 1,
                0, 0, 0, 7,
                0, 0, 0, 8,
                (byte) 177
        };
        assertEquals(List.of(0, 1, 3, 7, 24), MethodLocationResolver.offsets(code));
    }

    @Test void resolvesTableSwitchPaddingAndEntries() {
        byte[] code = new byte[]{
                0,
                (byte) 170,
                0, 0,
                0, 0, 0, 20,
                0, 0, 0, 2,
                0, 0, 0, 3,
                0, 0, 0, 8,
                0, 0, 0, 12,
                (byte) 177
        };
        assertEquals(List.of(0, 1, 24), MethodLocationResolver.offsets(code));
    }
}
