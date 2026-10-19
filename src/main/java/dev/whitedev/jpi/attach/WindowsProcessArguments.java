package dev.whitedev.jpi.attach;

import com.sun.jna.platform.win32.COM.COMUtils;
import com.sun.jna.platform.win32.COM.WbemcliUtil;
import com.sun.jna.platform.win32.Ole32;
import com.sun.jna.platform.win32.Shell32Util;
import com.sun.jna.platform.win32.WinError;
import com.sun.jna.platform.win32.WinNT.HRESULT;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeoutException;

final class WindowsProcessArguments {
    private enum Property { COMMANDLINE }

    static List<String> read(long pid) {
        HRESULT initialized = Ole32.INSTANCE.CoInitializeEx(null, Ole32.COINIT_MULTITHREADED);
        boolean balanceInitialization = COMUtils.SUCCEEDED(initialized);
        if (!balanceInitialization && initialized.intValue() != WinError.RPC_E_CHANGED_MODE) return null;
        try {
            var query = new WbemcliUtil.WmiQuery<>("Win32_Process WHERE ProcessId = " + pid, Property.class);
            var result = query.execute(1500);
            if (result.getResultCount() != 1) return null;
            Object value = result.getValue(Property.COMMANDLINE, 0);
            if (!(value instanceof String command) || command.isBlank()) return null;
            String[] argv = Shell32Util.CommandLineToArgv(command);
            return argv.length > 1 ? List.copyOf(Arrays.asList(argv).subList(1, argv.length)) : List.of();
        } catch (RuntimeException | LinkageError | TimeoutException unavailable) { return null; }
        finally { if (balanceInitialization) Ole32.INSTANCE.CoUninitialize(); }
    }
}
