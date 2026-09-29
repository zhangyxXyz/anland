#!/usr/bin/env python3
"""Disposable GTK Wayland client for the initial Android task identity test.

Run inside the container with auto_attach disabled, then pass the created
window id to TaskIdentityInstrumentation. No installed launchers are changed.
"""
import os
import pathlib
import subprocess
import sys
import tempfile

os.environ["GDK_BACKEND"] = "wayland"
os.environ.setdefault("XDG_RUNTIME_DIR", "/run/anland")
os.environ.setdefault("WAYLAND_DISPLAY", "wayland-0")

app_id = "org.example.AnlandTaskIdentityProbe"
if "--window" in sys.argv:
    import gi
    gi.require_version("Gtk", "3.0")
    from gi.repository import Gio, GLib, Gtk

    GLib.set_prgname(app_id)

    app = Gtk.Application(application_id=app_id, flags=Gio.ApplicationFlags.NON_UNIQUE)

    def activate(application):
        window = Gtk.ApplicationWindow(application=application, title="Early window title")
        window.set_default_size(720, 480)
        window.add(Gtk.Label(label="Task identity fixture — no click or refocus needed"))
        window.show_all()

    app.connect("activate", activate)
    app.run([])
else:
    with tempfile.TemporaryDirectory(prefix="anland-task-identity-") as directory:
        root = pathlib.Path(directory)
        desktop = root / "applications" / (app_id + ".desktop")
        desktop.parent.mkdir()
        icon = root / "fixture.svg"
        icon.write_text('<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 128 128">'
                        '<rect width="128" height="128" rx="24" fill="#167b73"/>'
                        '<path d="M28 64L54 90L100 38" fill="none" stroke="white" stroke-width="12"/>'
                        '</svg>')
        desktop.write_text("[Desktop Entry]\nType=Application\nName=Task identity fixture\n"
                           f"Icon={icon}\nExec=true\n")
        # /proc/<pid>/environ exposes the environment at exec, not Python's
        # later heap-based putenv values. Match an actual desktop launch.
        subprocess.run([sys.executable, __file__, "--window"], check=True,
                       env={**os.environ, "XDG_DATA_HOME": str(root)})
