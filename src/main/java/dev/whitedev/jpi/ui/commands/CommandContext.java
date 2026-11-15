package dev.whitedev.jpi.ui.commands;

import dev.whitedev.jpi.attach.InspectorSession;
import dev.whitedev.jpi.ui.context.AnalysisTarget;

public record CommandContext(InspectorSession session, AnalysisTarget target, String view, Object debuggerIdentity) {}
