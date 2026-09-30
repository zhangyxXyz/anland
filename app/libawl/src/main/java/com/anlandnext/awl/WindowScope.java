package com.anlandnext.awl;

/** View scope is independent of presentation labels and global window settings. */
public final class WindowScope {
    public static final String ALL = "all";
    public static final String UNKNOWN = "unknown";
    public static String container(String name) { return "container:" + name; }
    public static boolean matches(String scope, String name) {
        if (ALL.equals(scope)) return true;
        if (UNKNOWN.equals(scope)) return name == null || name.trim().isEmpty();
        return name != null && scope != null && scope.equals(container(name));
    }
    private WindowScope() { }
}
