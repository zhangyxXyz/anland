package com.anlandnext.awl;

/** A transient Wayland toplevel, rendered by the same GPU/input bridge as
 * ordinary windows, but sized by its geometry and retained in its parent's
 * Android task. No app-specific widget or dialog recreation is involved. */
public final class AwlDialogActivity extends AwlWindowActivity { }
