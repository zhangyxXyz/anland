"""Device regression: document re-entry must preserve Wayland keyboard focus.

Use only a disposable client started with WAYLAND_DEBUG=1. Example (Windows):
  python tests/window_focus_regression.py --adb <adb.exe> --serial <tablet> \
    --window <test-window-id> --client-log /proc/<container-pid>/root/tmp/<probe>/wayland.log
The test re-enters the SAME Android document (onNewIntent -> pause/resume, no
window-focus callback), then checks real Wayland events, not UI log assertions.
It does not type, close windows, restart services or change device settings.
"""
import argparse
import re
import shlex
import subprocess
import time


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--adb', required=True)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--window', type=int, required=True)
    parser.add_argument('--client-log', required=True)
    parser.add_argument('--rounds', type=int, default=5)
    args = parser.parse_args()
    adb = [args.adb, '-s', args.serial]

    def run(*command):
        return subprocess.check_output(adb + list(command), timeout=15).decode(errors='replace')

    def trace():
        return run('exec-out', 'su', '-c', 'cat ' + shlex.quote(args.client_log))

    def enter():
        run('shell', 'am', 'start', '-W', '-n',
            'com.anlandnext/com.anlandnext.awl.AwlWindowActivity',
            '-d', f'anland://win/{args.window}', '--el', 'id', str(args.window),
            '-f', '0x10080000')

    def focus(text, interface):
        return re.findall(r'(?<!\w)' + interface + r'[#@]\d+\.(enter|leave)\(', text)

    # Bring the test document forward before exercising same-document re-entry.
    enter()
    time.sleep(0.5)
    for iteration in range(args.rounds):
        previous = trace()
        enter()
        deadline = time.monotonic() + 3
        while True:
            current = trace()
            delta = current[len(previous):]
            keyboard = focus(delta, 'wl_keyboard')
            text_input = focus(delta, 'zwp_text_input_v3')
            # A surviving attachment may legitimately have no leave at all.
            # Once a leave is emitted, a matching enter must follow it.
            all_keyboard = focus(current, 'wl_keyboard')
            all_text = focus(current, 'zwp_text_input_v3')
            restored = (all_keyboard and all_keyboard[-1] == 'enter'
                        and all_text and all_text[-1] == 'enter')
            if restored:
                time.sleep(0.15)  # also catch a delayed ONEWAY pause
                stable = trace()
                if (focus(stable, 'wl_keyboard')[-1] == 'enter'
                        and focus(stable, 'zwp_text_input_v3')[-1] == 'enter'):
                    break
            if time.monotonic() >= deadline:
                raise AssertionError(f'round {iteration+1}: lost focus after re-entry; '
                                     f'keyboard={keyboard}, text-input={text_input}')
            time.sleep(0.1)
        print(f'PASS round {iteration+1}: keyboard/text-input focus retained')


if __name__ == '__main__':
    main()
