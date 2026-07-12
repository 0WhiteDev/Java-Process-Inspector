package dev.whitedev.jpi.ui;

import dev.whitedev.jpi.attach.InspectorSession;

interface SessionAware {
    void setSession(InspectorSession session);
}
