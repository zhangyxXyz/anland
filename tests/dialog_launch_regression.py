"""Device test for a disposable unsaved document's native save dialog.

Specify coordinates from a fresh screenshot. The open tap must open a native
dialog and cancel must dismiss it without discarding the document. Requires
PresentationProbe.java compiled to DEX and pushed to --probe-dex on the device.
Verifies Android task identity, direct launch and surviving parent attachment.
No service restarts, document creation or device-setting changes are performed.
"""
import argparse
from pathlib import Path
import re
import shlex
import subprocess
import time


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--adb', required=True)
    p.add_argument('--serial', required=True)
    p.add_argument('--window', type=int, required=True)
    p.add_argument('--open-tap', type=int, nargs=2, required=True)
    p.add_argument('--cancel-tap', type=int, nargs=2, required=True)
    p.add_argument('--probe-dex', required=True)
    p.add_argument('--rounds', type=int, default=3)
    p.add_argument('--output', type=Path, required=True)
    args = p.parse_args()
    adb = [args.adb, '-s', args.serial]

    def run(*cmd):
        return subprocess.check_output(adb + list(cmd), timeout=20).decode('utf-8', 'replace')

    def states():
        text = run('shell', 'su', '-c', shlex.quote(
            'CLASSPATH=' + shlex.quote(args.probe_dex) + ' app_process /system/bin PresentationProbe'))
        return {int(m[0]): tuple(map(int, m[1:])) for m in re.findall(
            r'id=(\d+) attached=(\d+) parent=(\d+) width=(\d+) height=(\d+) dialog=(\d+)', text)}

    def task_for(dump, uri):
        for block in re.split(r'\* Hist ', dump)[1:]:
            if re.search(r'Intent \{ dat=' + re.escape(uri) + r'(?:\s|\})', block):
                m = re.search(r'ActivityRecord\{[^\n}]* t(\d+)', block)
                if m:
                    return int(m[1])
        raise AssertionError('No Activity for ' + uri)

    assert states().get(args.window, (0,))[0] == 1, 'Parent must be attached'
    parent_uri = f'anland://win/{args.window}'
    parent_task = task_for(run('shell', 'dumpsys', 'activity', 'activities'), parent_uri)
    marker = 'dialog-launch-regression-' + str(time.time_ns())
    run('shell', 'log', '-t', 'anland-test', marker)
    results = []
    for round_no in range(args.rounds):
        assert not any(v[1] == args.window and v[4] for v in states().values()), 'Dialog already open'
        run('shell', 'input', 'tap', *map(str, args.open_tap))
        deadline = time.monotonic() + 5
        while True:
            current = states()
            children = [k for k, v in current.items() if v[1] == args.window and v[4] and v[0]]
            if children:
                break
            assert time.monotonic() < deadline, 'Dialog failed to attach'
            time.sleep(.1)
        assert len(children) == 1, f'Duplicate dialogs: {children}'
        child = children[0]
        assert current[args.window][0] == 1, 'Dialog detached its visible parent'
        dump = run('shell', 'dumpsys', 'activity', 'activities')
        assert task_for(dump, f'anland://dialog/{child}') == parent_task
        assert task_for(dump, parent_uri) == parent_task
        run('shell', 'input', 'tap', *map(str, args.cancel_tap))
        deadline = time.monotonic() + 5
        while child in states():
            assert time.monotonic() < deadline, 'Cancel failed to close the dialog'
            time.sleep(.1)
        assert states()[args.window][0] == 1, 'Cancel lost parent attachment'
        results.append(f'PASS round {round_no + 1}: dialog {child} shared task {parent_task}; cancel retained parent')
        print(results[-1])

    log = run('shell', 'logcat', '-d', '-v', 'brief', '-s', 'ActivityTaskManager:I',
              'anland-daemon:I', 'anland-awlwin:I', 'anland-test:I', '*:S')
    assert marker in log, 'Log marker lost; cannot validate transition history'
    delta = log.split(marker, 1)[1]
    args.output.write_text('\n'.join(results) + '\n' + delta, encoding='utf-8')
    starts = [line for line in delta.splitlines() if re.search(r'START u\d+ ', line)]
    assert not any('.awl.AwlWindowActivity' in line for line in starts), 'Intermediate document Activity launched'
    assert sum('.awl.AwlDialogActivity' in line for line in starts) == args.rounds, starts
    assert f'SURFACE {args.window} ' not in delta, 'Parent surface was unnecessarily reattached'
    print('PASS every dialog launched directly, without an intermediate document task or parent reattach')


if __name__ == '__main__':
    main()
