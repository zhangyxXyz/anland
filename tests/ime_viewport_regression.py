"""Check real IME animation viewport sizes on a disposable focused text client.

Re-enter the existing Android document to request its keyboard, then tap the
IME's hide button. Does not type, modify preferences or close the Linux window.
Use coordinates and --height from a fresh screenshot, with system animations on.
"""
import argparse
from pathlib import Path
import re
import subprocess
import time


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--adb', required=True)
    p.add_argument('--serial', required=True)
    p.add_argument('--window', type=int, required=True)
    p.add_argument('--height', type=int, required=True)
    p.add_argument('--hide-tap', type=int, nargs=2, required=True)
    p.add_argument('--rounds', type=int, default=3)
    p.add_argument('--output', type=Path, required=True)
    args = p.parse_args()
    adb = [args.adb, '-s', args.serial]

    def run(*cmd):
        return subprocess.check_output(adb + list(cmd), timeout=20).decode('utf-8', 'replace')

    marker = 'ime-viewport-regression-' + str(time.time_ns())
    run('shell', 'log', '-t', 'anland-test', marker)
    for n in range(args.rounds):
        run('shell', 'log', '-t', 'anland-test', f'{marker}-show-{n}')
        run('shell', 'am', 'start', '-W', '-n',
            'com.anlandnext/com.anlandnext.awl.AwlWindowActivity',
            '-d', f'anland://win/{args.window}', '--el', 'id', str(args.window), '-f', '0x10080000')
        time.sleep(2)
        run('shell', 'log', '-t', 'anland-test', f'{marker}-hide-{n}')
        run('shell', 'input', 'tap', *map(str, args.hide_tap))
        time.sleep(2)
    log = run('shell', 'logcat', '-d', '-v', 'threadtime', '-s',
              'anland-awlwin:I', 'anland-test:I', '*:S')
    assert marker in log, 'Log marker lost'
    log = log.split(marker, 1)[1]
    args.output.write_text(log, encoding='utf-8')
    pattern = rf'win {args.window} surface \d+x(\d+)'
    for n in range(args.rounds):
        show = log.split(f'{marker}-show-{n}', 1)[1].split(f'{marker}-hide-{n}', 1)[0]
        hide = log.split(f'{marker}-hide-{n}', 1)[1].split(f'{marker}-show-', 1)[0]
        opening = [args.height] + list(map(int, re.findall(pattern, show)))
        closing = list(map(int, re.findall(pattern, hide)))
        assert len(set(opening)) >= 4, f'No intermediate opening animation frames: {opening}'
        assert all(a >= b for a, b in zip(opening, opening[1:])), f'Opening jumped back: {opening}'
        assert opening[-1] < args.height, 'Keyboard did not reduce viewport'
        closing.insert(0, opening[-1])
        assert len(set(closing)) >= 4, f'No intermediate closing animation frames: {closing}'
        assert all(a <= b for a, b in zip(closing, closing[1:])), f'Closing jumped back: {closing}'
        assert closing[-1] == args.height, f'Viewport not restored: {closing}'
        print(f'PASS round {n+1}: opening {opening}; closing {closing}')


if __name__ == '__main__':
    main()
