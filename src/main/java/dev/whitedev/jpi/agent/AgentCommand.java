package dev.whitedev.jpi.agent;

interface AgentCommand {
    byte[] execute(String payload) throws Exception;
}
