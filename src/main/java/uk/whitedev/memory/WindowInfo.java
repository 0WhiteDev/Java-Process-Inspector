package uk.whitedev.memory;

public class WindowInfo {
    private final String title;
    private final long pid;

    public WindowInfo(String title, long pid) {
        this.title = title;
        this.pid = pid;
    }

    public String getTitle() {
        return title;
    }

    public long getPid() {
        return pid;
    }

    @Override
    public String toString() {
        return "WindowInfo{title='" + title + "', pid=" + pid + "}";
    }
}
