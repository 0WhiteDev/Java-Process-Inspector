package dev.whitedev.jpi.attach;

import java.io.IOException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ProcessInjectorTest {
    @Test void rejectsInvalidPidsBeforeLoadingNativeCode() {
        ProcessInjector injector = new ProcessInjector();
        for (String pid : new String[]{null, "", "-1", "0", "12;echo", "99999999999999999999", "2147483648", Long.toString(ProcessHandle.current().pid())}) {
            assertThrows(IOException.class, () -> injector.inject(pid, null));
        }
    }
}
