"""One version source for Gradle, module packaging and release orchestration."""
import argparse
import os
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[2]
KEYS = {
    'RELEASE_VERSION', 'SHELL_VERSION_NAME', 'SHELL_VERSION_CODE',
    'WAYLAND_VERSION_NAME', 'WAYLAND_VERSION_CODE',
    'MODULE_VERSION_NAME', 'MODULE_VERSION_CODE', 'ROOTFS_VERSION',
}
VERSION = re.compile(r'[0-9]+\.[0-9]+\.[0-9]+(?:-[0-9A-Za-z]+(?:[.-][0-9A-Za-z]+)*)?')


def read_versions(path=ROOT / 'version.properties'):
    result = {}
    for line in Path(path).read_text(encoding='utf-8').splitlines():
        line = line.strip()
        if not line or line.startswith('#'):
            continue
        key, sep, value = line.partition('=')
        if not sep or key not in KEYS or key in result:
            raise ValueError(f'Invalid or duplicate version key: {key}')
        if key.endswith('_CODE'):
            if not re.fullmatch(r'[1-9][0-9]*', value) or int(value) > 2100000000:
                raise ValueError(f'Invalid Android version code: {key}')
        elif not VERSION.fullmatch(value):
            raise ValueError(f'Invalid version: {key}')
        result[key] = value
    if result.keys() != KEYS:
        raise ValueError(f'Missing version keys: {KEYS - result.keys()}')
    return result


def version_suffix():
    suffix = os.environ.get('ANLAND_VERSION_SUFFIX', '')
    if suffix and not re.fullmatch(r'-dev\.[0-9]+\+[0-9a-f]{7,40}', suffix):
        raise ValueError('Invalid ANLAND_VERSION_SUFFIX')
    return suffix


def render_module(template, destination):
    versions = read_versions()
    content = Path(template).read_text(encoding='utf-8')
    replacements = {
        'version': versions['MODULE_VERSION_NAME'] + version_suffix(),
        'versionCode': versions['MODULE_VERSION_CODE'],
    }
    for key, value in replacements.items():
        content, count = re.subn(rf'^{key}=.*$', f'{key}={value}', content, flags=re.M)
        if count != 1:
            raise ValueError(f'Module template must contain one {key}')
    Path(destination).write_text(content, encoding='utf-8', newline='\n')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--module', nargs=2, metavar=('TEMPLATE', 'OUTPUT'))
    args = parser.parse_args()
    if args.module:
        render_module(*args.module)
    else:
        for key, value in read_versions().items():
            print(f'{key}={value}')
