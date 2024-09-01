package uk.whitedev.utils;

import java.lang.management.ManagementFactory;

public class ProcessUtil {
    public static String getProcessPid(){
        String processName = ManagementFactory.getRuntimeMXBean().getName();
        return processName.split("@")[0];
    }
}
