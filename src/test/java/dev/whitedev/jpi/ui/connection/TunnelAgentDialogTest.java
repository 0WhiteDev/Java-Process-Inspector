package dev.whitedev.jpi.ui.connection;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TunnelAgentDialogTest {
    @Test void quotesSemicolonSeparatedAgentOptionsForContainerShells() {
        String argument = "-javaagent:jpi.jar=mode=listen;host=127.0.0.1;port=43123;token=1234567890abcdef";
        assertEquals('"' + argument + '"', TunnelAgentDialog.quoteAgentArgument(argument));
    }
}
