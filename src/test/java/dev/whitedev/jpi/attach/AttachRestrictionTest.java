package dev.whitedev.jpi.attach;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class AttachRestrictionTest {
    @Test void recognizesVmFlagsAndTheirOrderWithoutReadingApplicationArguments() {
        assertEquals(AttachRestriction.DISABLED, AttachRestriction.fromCommandArguments(List.of("-cp", "classes", "-XX:+DisableAttachMechanism", "Main")));
        assertEquals(AttachRestriction.EXPLICITLY_ENABLED, AttachRestriction.fromCommandArguments(List.of("-XX:+DisableAttachMechanism", "-XX:-DisableAttachMechanism", "Main")));
        assertEquals(AttachRestriction.UNKNOWN, AttachRestriction.fromCommandArguments(List.of("-jar", "app.jar", "-XX:+DisableAttachMechanism")));
        assertEquals(AttachRestriction.UNKNOWN, AttachRestriction.fromCommandArguments(List.of("Main", "-XX:+DisableAttachMechanism")));
        assertEquals(AttachRestriction.UNKNOWN, AttachRestriction.fromCommandArguments(List.of("@hidden-options")));
    }
    @Test void missingOrInvalidProcessesRemainUnknown() {
        for (String pid : new String[]{null, "bad", "0", "-1", "99999999999999999999"}) assertEquals(AttachRestriction.UNKNOWN, AttachRestriction.inspect(pid));
    }
}
