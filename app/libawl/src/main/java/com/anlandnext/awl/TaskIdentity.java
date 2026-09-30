package com.anlandnext.awl;

/** Use resolved desktop metadata; unknown clients keep their own title. */
final class TaskIdentity {
    static String label(String desktopName, String title) {
        return desktopName != null && !desktopName.isEmpty() ? desktopName : title;
    }
    static String label(String desktopName, String title, String container, boolean showContainer) {
        String base = label(desktopName, title);
        if (!showContainer || container == null || container.trim().isEmpty() || base == null || base.isEmpty()) return base;
        return base + " · " + container;
    }
}
