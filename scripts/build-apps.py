"""Build signed APKs locally or in CI using the same keys and Gradle tasks."""
import argparse
import os
from pathlib import Path
import shutil
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'scripts/ci'))
from signing import config_path, load_config, verify_key


def single_file(pattern):
    files = list(ROOT.glob(pattern))
    if len(files) != 1:
        raise ValueError(f'Expected one build output matching {pattern}')
    return files[0]


def build(component, output, keystore_dir, bash):
    path = config_path(component, keystore_dir).resolve()
    config, store = load_config(path)
    verify_key(component, config, store)
    project = 'shell-app' if component == 'shell' else 'app'
    env = dict(os.environ, ANLAND_SIGNING_CONFIG_FILE=str(path))
    tasks = ['assembleRelease', 'assembleReleaseAndroidTest', 'lintRelease']
    if component == 'wayland':
        tasks.append(':libawl:assembleRelease')
    subprocess.run([bash, 'gradlew', *tasks, '--console=plain'], cwd=ROOT / project, env=env, check=True)
    output.mkdir(parents=True, exist_ok=True)
    files = {
        f'anland-{component}.apk': f'{project}/build/outputs/apk/release/*.apk',
        f'anland-{component}-tests.apk': f'{project}/build/outputs/apk/androidTest/release/*.apk',
    }
    if component == 'wayland':
        files['anland-awllib.aar'] = 'app/libawl/build/outputs/aar/libawl-release.aar'
    for name, pattern in files.items():
        shutil.copyfile(single_file(pattern), output / name)
    subprocess.run([sys.executable, str(ROOT / 'scripts/ci/verify-android.py'), component,
                    '--directory', str(output), '--apps-only'], env=env, check=True)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--component', choices=('shell', 'wayland', 'all'), default='all')
    parser.add_argument('--keystore-dir')
    parser.add_argument('--output', type=Path, default=ROOT / 'outputs/apps')
    parser.add_argument('--bash', default='D:/MyProfile/Git/bin/bash.exe' if os.name == 'nt' else 'bash')
    args = parser.parse_args()
    components = ('shell', 'wayland') if args.component == 'all' else (args.component,)
    # Preflight every requested key before spending time building anything.
    for component in components:
        config, store = load_config(config_path(component, args.keystore_dir))
        verify_key(component, config, store)
    for component in components:
        build(component, args.output.resolve(), args.keystore_dir, args.bash)
    print('Signed APKs and test APKs:', args.output.resolve())
