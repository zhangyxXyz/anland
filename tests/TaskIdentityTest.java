package com.anlandnext.awl;

public class TaskIdentityTest {
    private static void equal(String expected, String actual) {
        if (!java.util.Objects.equals(expected, actual)) throw new AssertionError(actual);
    }
    public static void main(String[] args) {
        equal("Chrome", TaskIdentity.label("Chrome", "Page title", "HostDebian", false));
        equal("Chrome · HostDebian", TaskIdentity.label("Chrome", "Page title", "HostDebian", true));
        equal("Page title · Ubuntu", TaskIdentity.label(null, "Page title", "Ubuntu", true));
        equal("Chrome", TaskIdentity.label("Chrome", "Page title", null, true));
        equal("Chrome", TaskIdentity.label("Chrome", "Page title", "  ", true));
        equal(null, TaskIdentity.label(null, null, "HostDebian", true));
        // Derive both states from the original identity: repeated toggles cannot stack suffixes.
        for (int i = 0; i < 3; i++) {
            equal("Chrome · HostDebian", TaskIdentity.label("Chrome", "Page title", "HostDebian", true));
            equal("Chrome", TaskIdentity.label("Chrome", "Page title", "HostDebian", false));
        }
        if (!WindowScope.matches(WindowScope.container("HostDebian"), "HostDebian")) throw new AssertionError();
        if (WindowScope.matches(WindowScope.container("HostDebian"), "HostUbuntu")) throw new AssertionError();
        if (WindowScope.matches(WindowScope.container("HostDebian"), null)) throw new AssertionError();
        if (!WindowScope.matches(WindowScope.UNKNOWN, null)) throw new AssertionError();
        if (WindowScope.matches(WindowScope.UNKNOWN, "HostDebian")) throw new AssertionError();
        if (!WindowScope.matches(WindowScope.ALL, "HostUbuntu")) throw new AssertionError();
        if (!WindowScope.matches(WindowScope.ALL, null)) throw new AssertionError();
        // Container names cannot collide with the All/Other selector tokens.
        if (WindowScope.matches(WindowScope.container("all"), "unknown")) throw new AssertionError();
        System.out.println("Task identity container label tests passed");
    }
}
