package dev.whitedev.jpi.fixture;

import com.sun.management.HotSpotDiagnosticMXBean;

import java.lang.management.ManagementFactory;
import java.lang.ref.WeakReference;

public final class HprofReferenceFixture {
    public static final SessionManager INSTANCE = new SessionManager();
    public static final WeakReference<UserSession> WEAK = new WeakReference<>(INSTANCE.currentSession);

    public static void main(String[] arguments) throws Exception {
        ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class).dumpHeap(arguments[0], true);
    }

    public static final class SessionManager {
        public final UserSession currentSession = new UserSession();
        public final Object[] aliases = {currentSession};
    }

    public static final class UserSession {
        public final byte[] payload = new byte[1024 * 1024];
    }
}
