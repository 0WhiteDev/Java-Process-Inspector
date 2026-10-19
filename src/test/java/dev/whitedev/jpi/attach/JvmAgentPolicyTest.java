package dev.whitedev.jpi.attach;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class JvmAgentPolicyTest {
    @Test void observesIndependentFlagsAndLastVisibleValues() {
        JvmAgentPolicy policy = JvmAgentPolicy.fromCommandArguments(List.of("-XX:+DisableAttachMechanism",
                "-XX:-DisableAttachMechanism", "-XX:+EnableDynamicAgentLoading", "-XX:-EnableDynamicAgentLoading", "Main"));
        assertEquals(AttachRestriction.EXPLICITLY_ENABLED, policy.attachRestriction());
        assertEquals(JvmAgentPolicy.DynamicLoading.DISABLED, policy.dynamicLoading());
        assertEquals("-XX:-EnableDynamicAgentLoading", assertThrows(AgentLoadingRestrictedException.class,
                () -> policy.rejectObservedRestrictions("123")).vmOption());
    }

    @Test void moduleApplicationArgumentsAndOptionValuesAreNotJvmPolicy() {
        for (List<String> arguments : List.of(
                List.of("--module=example/Main", "-XX:+DisableAttachMechanism", "-XX:-EnableDynamicAgentLoading"),
                List.of("-jar", "app.jar", "-XX:+DisableAttachMechanism"),
                List.of("-cp", "-XX:+DisableAttachMechanism", "Main", "-XX:-EnableDynamicAgentLoading"))) {
            JvmAgentPolicy policy = JvmAgentPolicy.fromCommandArguments(arguments);
            assertEquals(AttachRestriction.UNKNOWN, policy.attachRestriction());
            assertEquals(JvmAgentPolicy.DynamicLoading.UNKNOWN, policy.dynamicLoading());
        }
    }

    @Test void opaqueFilesInvalidateEarlierObservations() {
        for (String opaque : List.of("@options", "-XX:Flags=flags.txt", "-XX:VMOptionsFile=options.txt")) {
            JvmAgentPolicy policy = JvmAgentPolicy.fromCommandArguments(List.of("-XX:-DisableAttachMechanism",
                    "-XX:+EnableDynamicAgentLoading", opaque));
            assertEquals(AttachRestriction.UNKNOWN, policy.attachRestriction());
            assertEquals(JvmAgentPolicy.DynamicLoading.UNKNOWN, policy.dynamicLoading());
        }
    }

    @Test void attachRestrictionTakesPriorityAndRefusalHasNoRetryCause() {
        JvmAgentPolicy policy = JvmAgentPolicy.fromCommandArguments(List.of("-XX:+DisableAttachMechanism", "-XX:-EnableDynamicAgentLoading", "Main"));
        AgentLoadingRestrictedException error = assertThrows(AgentLoadingRestrictedException.class, () -> policy.rejectObservedRestrictions("123"));
        assertEquals("-XX:+DisableAttachMechanism", error.vmOption());
        assertEquals(0, error.getSuppressed().length);
        assertTrue(error.getMessage().contains("preloaded"));
    }

    @Test void diagnosticSummaryConservativelyDescribesPolicy() {
        assertTrue(JvmAgentPolicy.fromCommandArguments(List.of("-XX:+DisableAttachMechanism", "Main"))
                .diagnosticSummary().contains("pre-provisioned"));
        assertTrue(JvmAgentPolicy.fromCommandArguments(List.of("-XX:-EnableDynamicAgentLoading", "Main"))
                .diagnosticSummary().contains("startup agent"));
        assertTrue(JvmAgentPolicy.fromCommandArguments(List.of("@hidden-arguments"))
                .diagnosticSummary().contains("not fully observable"));
        assertFalse(JvmAgentPolicy.fromCommandArguments(List.of("-XX:-DisableAttachMechanism",
                        "-XX:+EnableDynamicAgentLoading", "Main"))
                .diagnosticSummary().contains("not guaranteed"));
    }

    @Test void unavailableProcessMetadataRemainsUnknown() {
        for (String pid : new String[]{null, "invalid", "-1", "9999999999999999999999"}) {
            JvmAgentPolicy policy = JvmAgentPolicy.inspect(pid);
            assertEquals(AttachRestriction.UNKNOWN, policy.attachRestriction());
            assertEquals(JvmAgentPolicy.DynamicLoading.UNKNOWN, policy.dynamicLoading());
        }
    }
}
