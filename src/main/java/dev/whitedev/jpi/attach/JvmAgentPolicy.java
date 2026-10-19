package dev.whitedev.jpi.attach;

import java.util.List;

public record JvmAgentPolicy(AttachRestriction attachRestriction, DynamicLoading dynamicLoading) {
    public enum DynamicLoading { DISABLED, EXPLICITLY_ENABLED, UNKNOWN }

    public static JvmAgentPolicy inspect(String pid) {
        List<String> arguments = AttachRestriction.observedArguments(pid);
        return arguments == null ? new JvmAgentPolicy(AttachRestriction.UNKNOWN, DynamicLoading.UNKNOWN)
                : fromCommandArguments(arguments);
    }

    static JvmAgentPolicy fromCommandArguments(List<String> arguments) {
        List<String> flags = AttachRestriction.vmArguments(arguments);
        DynamicLoading dynamic = DynamicLoading.UNKNOWN;
        if (flags != null) for (String value : flags) {
            if (value.equals("-XX:-EnableDynamicAgentLoading")) dynamic = DynamicLoading.DISABLED;
            if (value.equals("-XX:+EnableDynamicAgentLoading")) dynamic = DynamicLoading.EXPLICITLY_ENABLED;
        }
        return new JvmAgentPolicy(AttachRestriction.fromCommandArguments(arguments), dynamic);
    }

    public String diagnosticSummary() {
        if (attachRestriction == AttachRestriction.DISABLED)
            return "Attach API disabled (-XX:+DisableAttachMechanism); use a pre-provisioned agent endpoint.";
        if (dynamicLoading == DynamicLoading.DISABLED)
            return "Dynamic agent loading disabled (-XX:-EnableDynamicAgentLoading); use a startup agent.";
        if (attachRestriction == AttachRestriction.UNKNOWN || dynamicLoading == DynamicLoading.UNKNOWN)
            return "JVM loading policy is not fully observable; successful loading is not guaranteed.";
        return "No explicit JVM agent-loading restriction observed; permissions and runtime checks still apply.";
    }

    public void rejectObservedRestrictions(String pid) throws AgentLoadingRestrictedException {
        if (attachRestriction == AttachRestriction.DISABLED)
            throw new AgentLoadingRestrictedException(pid, "-XX:+DisableAttachMechanism");
        if (dynamicLoading == DynamicLoading.DISABLED)
            throw new AgentLoadingRestrictedException(pid, "-XX:-EnableDynamicAgentLoading");
    }

}
