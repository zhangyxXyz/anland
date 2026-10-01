"""On-device Fcitx bridge regression, run as root inside the container.

Creates only disposable GTK fields. Exercises actual frontend delivery,
UTF-8 composition, focus-generation rejection and password redaction.
No clipboard or application documents are touched.
"""
import argparse
import json
import os
from pathlib import Path
import select
import socket
import struct
import subprocess
import sys
import time

MAGIC = 0x41494D31
REQUEST = struct.Struct('<IIQiiII')
REPLY = struct.Struct('<IiQIiiiiiiI')


def client():
    import gi
    gi.require_version('Gtk', '3.0')
    from gi.repository import Gtk, GLib
    window = Gtk.Window(title='Anland IME regression (temporary)')
    window.set_default_size(900, 500)
    box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=80)
    box.set_border_width(120)
    entries = [Gtk.Entry(), Gtk.Entry(), Gtk.Entry()]
    entries[2].set_visibility(False)
    for entry in entries:
        box.pack_start(entry, False, False, 0)
    window.add(box)
    window.show_all()
    entries[0].grab_focus()
    window.present()

    def command(_fd, _condition):
        line = sys.stdin.readline().strip()
        if line.startswith('focus '):
            entries[int(line.split()[1])].grab_focus()
            GLib.timeout_add(100, lambda: (print('focused', flush=True), False)[1])
        elif line == 'text':
            print(json.dumps([entry.get_text() for entry in entries]), flush=True)
        elif line == 'quit' or not line:
            Gtk.main_quit()
            return False
        return True
    GLib.io_add_watch(sys.stdin, GLib.IO_IN | GLib.IO_HUP, command)
    GLib.timeout_add(300, lambda: (print('ready', flush=True), False)[1])
    Gtk.main()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--client', action='store_true')
    parser.add_argument('--socket')
    parser.add_argument('--user', default='seiun')
    parser.add_argument('--display', default=':1')
    parser.add_argument('--authority', default='/run/user/1000/anland-desktop/Xauthority')
    parser.add_argument('--bus', required=False)
    args = parser.parse_args()
    if args.client:
        return client()
    if not args.socket or not args.bus:
        parser.error('--socket and --bus required')
    env = dict(os.environ, DISPLAY=args.display, XAUTHORITY=args.authority,
               GDK_BACKEND='x11', GTK_IM_MODULE='fcitx', DBUS_SESSION_BUS_ADDRESS=args.bus,
               PATH='/usr/bin:/bin', XDG_RUNTIME_DIR='/run/user/1000', DCONF_PROFILE='')
    child = subprocess.Popen(['sudo', '-u', args.user, '--preserve-env=DISPLAY,XAUTHORITY,GDK_BACKEND,GTK_IM_MODULE,DBUS_SESSION_BUS_ADDRESS,XDG_RUNTIME_DIR,DCONF_PROFILE',
                              sys.executable, str(Path(__file__).resolve()), '--client'],
                             stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                             text=True, bufsize=1, env=env)
    def response():
        if not select.select([child.stdout], [], [], 5)[0]:
            raise AssertionError('GTK probe timed out')
        line = child.stdout.readline().strip()
        assert line, 'GTK probe exited'
        return line

    def command(line):
        child.stdin.write(line + '\n')
        child.stdin.flush()
        return response()

    def request(op=0, token=0, text='', a=0, b=0, packet=None):
        encoded = text.encode()
        with socket.socket(socket.AF_UNIX, socket.SOCK_SEQPACKET) as conn:
            conn.settimeout(2)
            conn.connect(args.socket)
            conn.sendall(packet or REQUEST.pack(MAGIC, op, token, a, b, len(encoded), 0) + encoded)
            reply = conn.recv(REPLY.size + 4000)
        values = REPLY.unpack(reply[:REPLY.size])
        assert values[0] == MAGIC and values[-1] == len(reply) - REPLY.size
        return values, reply[REPLY.size:].decode()

    def settle():
        time.sleep(0.15)
        values, text = request()
        assert values[1] == 1, 'No focused Fcitx input context'
        return values, text

    try:
        assert response() == 'ready'
        state, _ = settle()
        token = state[2]
        assert state[9] > 0 and state[7] > 0, 'Cursor geometry missing'
        assert request(2, token, 'nihao', a=5)[0][1] == 1
        assert json.loads(command('text'))[0] == '', 'Preedit must not commit'
        expected = '你好😀𠀀，test'
        assert request(1, token, expected)[0][1] == 1
        time.sleep(0.2)
        assert json.loads(command('text'))[0] == expected, 'Unicode commit lost/duplicated'
        settle()
        assert request(3, token, a=4)[0][1] == 1
        time.sleep(0.15)
        assert json.loads(command('text'))[0] == expected[:-4], 'Delete-surrounding lost text'
        assert request(1, token, 'test')[0][1] == 1
        time.sleep(0.15)
        assert json.loads(command('text'))[0] == expected
        assert request(4, token, a=0, b=1)[0][1] == -1, 'Unsupported selection accepted'
        assert request(2, token, 'cancel', a=6)[0][1] == 1
        assert request(2, token, '')[0][1] == 1
        time.sleep(0.1)
        assert json.loads(command('text'))[0] == expected, 'Composition cancellation modified text'
        assert command('focus 1') == 'focused'
        state, _ = settle()
        assert state[2] != token
        assert request(1, token, 'WRONG')[0][1] == -1, 'Stale focus commit accepted'
        assert json.loads(command('text'))[1] == ''
        assert request(1, state[2], '第二个输入框')[0][1] == 1
        time.sleep(0.1)
        assert json.loads(command('text'))[1] == '第二个输入框'
        assert command('focus 2') == 'focused'
        state, text = settle()
        assert state[3] & 2 and not text, 'Password context leaked'
        assert request(1, state[2], 'temporary password')[0][1] == 1
        time.sleep(0.15)
        state, text = settle()
        assert state[3] & 2 and not text and not state[3] & 1, 'Nonempty password context leaked'
        assert request(packet=b'bad')[0][1] == -1
        assert request(packet=REQUEST.pack(MAGIC, 1, state[2], 0, 0, 1, 0) + b'\xff')[0][1] == -1
        print('PASS: real GTK Unicode/delete/preedit/cancel/focus/password/malformed requests')
    finally:
        if child.poll() is None:
            child.stdin.write('quit\n')
            child.stdin.flush()
            try:
                child.wait(timeout=3)
            except subprocess.TimeoutExpired:
                child.terminate()
                child.wait(timeout=3)


if __name__ == '__main__':
    main()
