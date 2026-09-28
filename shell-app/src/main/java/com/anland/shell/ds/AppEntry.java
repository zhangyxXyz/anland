package com.anland.shell.ds;

/** One Linux application parsed from a .desktop file inside a container. */
public final class AppEntry {

    public final String container;   /* owning container name */
    public final String id;          /* desktop file id (org.mozilla.firefox) */
    public final String name;        /* display name (locale-matched) */
    public final String exec;        /* raw Exec= value (field codes kept raw here) */
    public final String icon;        /* Icon= value: absolute path or icon name */
    public final String desktopPath; /* in-container .desktop path */
    public final String windowAppId; /* optional explicit activation target */
    public final boolean desktopSession;

    public AppEntry(String container, String id, String name, String exec,
                    String icon, String desktopPath) {
        this(container,id,name,exec,icon,desktopPath,"",false);
    }
    public AppEntry(String container, String id, String name, String exec,
                    String icon, String desktopPath, String windowAppId, boolean desktopSession) {
        this.container = container;
        this.id = id;
        this.name = name;
        this.exec = exec;
        this.icon = icon == null ? "" : icon;
        this.desktopPath = desktopPath;
        this.windowAppId = windowAppId == null ? "" : windowAppId;
        this.desktopSession = desktopSession;
    }

    /** Stable key for icon caching. */
    public String iconKey() {
        return container + "|" + icon;
    }

    @Override public String toString() {
        return name + " (" + id + ")";
    }
}
