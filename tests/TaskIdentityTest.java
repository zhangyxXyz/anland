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
        System.out.println("Task identity container label tests passed");
    }
}
