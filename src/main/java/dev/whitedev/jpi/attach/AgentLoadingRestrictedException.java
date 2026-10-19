package dev.whitedev.jpi.attach;

import java.io.IOException;

public final class AgentLoadingRestrictedException extends IOException {
    private final String vmOption;

    public AgentLoadingRestrictedException(String pid, String vmOption) {
        super("Native agent loading refused for PID " + pid + ": observed " + vmOption
                + ". Connect to an owner-provisioned preloaded endpoint, or restart with -agentpath and JPI communication options."
                + " An unprepared restricted JVM has no supported JPI late-loading path; no injection or Attach fallback is attempted.");
        this.vmOption = vmOption;
    }

    public String vmOption() { return vmOption; }
}
