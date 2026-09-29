"""Inject local components after upstream installation and before image export."""
import json
from pathlib import Path
import shutil
import sys

SOURCE = Path(__file__).resolve().parents[1]


def replace_once(text, old, new):
    if text.count(old) != 1:
        raise ValueError(f'Upstream builder changed: expected one {old!r}')
    return text.replace(old, new)


def integrate(builder, inputs):
    builder, inputs = Path(builder), Path(inputs)
    lock = json.loads((SOURCE / 'rootfs/sources.json').read_text())
    shutil.copytree(inputs, builder / 'anland-overrides', dirs_exist_ok=True)
    shutil.copyfile(SOURCE / 'rootfs/install-overrides.sh', builder / 'anland-overrides/install.sh')
    shutil.copyfile(SOURCE / 'rootfs/verify-installed.py', builder / 'anland-overrides/verify-installed.py')
    dockerfile = builder / 'Debian-13.Dockerfile'
    text = dockerfile.read_text(encoding='utf-8')
    text = replace_once(text, 'FROM debian:trixie AS customizer\n',
                        'FROM debian:trixie AS customizer\nCOPY anland-overrides/session.deb /tmp/anland-session.deb\n')
    text = replace_once(text, '/usr/local/sbin/install-anland-desktop "$DESKTOP" --1 &&',
                        'apt-get install -y /tmp/anland-session.deb && rm /tmp/anland-session.deb &&')
    text = replace_once(text, 'FROM scratch AS export\n',
                        'COPY anland-overrides/ /tmp/anland-overrides/\n'
                        'RUN bash /tmp/anland-overrides/install.sh && rm -rf /tmp/anland-overrides '
                        '/usr/local/sbin/assets && apt-get clean && rm -rf /var/lib/apt/lists/*\n\n'
                        'FROM scratch AS export\n')
    dockerfile.write_text(text, encoding='utf-8', newline='\n')
    script = builder / 'build_rootfs-native.sh'
    text = script.read_text(encoding='utf-8')
    start = text.index('if [ -n "$ANLAND_PACKAGE_FAMILY" ]; then\n')
    end = text.index('\nfi\n', start) + len('\nfi\n')
    text = text[:start] + ('# Session package is checksum-locked and supplied in the Docker context.\n'
                           f'ANLAND_PACKAGE_REVISION="{lock["session_package"]["sha256"]}"\n') + text[end:]
    script.write_text(text, encoding='utf-8', newline='\n')


if __name__ == '__main__':
    integrate(*sys.argv[1:])
