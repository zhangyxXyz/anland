package com.anlandnext.awl;

/** Use resolved desktop metadata; unknown clients keep their own title. */
final class TaskIdentity {
    static String label(String desktopName, String title) {
        return desktopName != null && !desktopName.isEmpty() ? desktopName : title;
    }
}
