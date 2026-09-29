"""Inject local components after upstream installation and before image export."""
import os
from pathlib import Path
import shutil
import sys

SOURCE = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SOURCE / 'scripts/ci'))
from rootfs_target import resolve


def replace_once(text, old, new):
    if text.count(old) != 1:
        raise ValueError(f'Upstream builder changed: expected one {old!r}')
    return text.replace(old, new)


def integrate(builder, inputs, target=None):
    builder, inputs = Path(builder), Path(inputs)
    target, config = resolve(target or os.environ.get('ROOTFS_TARGET'))
    package = config['session_package']
    context = builder / 'anland-overrides'
    shutil.copytree(inputs, context, dirs_exist_ok=True)
    shutil.copyfile(SOURCE / 'rootfs/install-overrides.sh', context / 'install.sh')
    shutil.copyfile(SOURCE / 'rootfs/verify-installed.py', context / 'verify-installed.py')
    filename = package['filename']
    install = {
        'apt': f'apt-get install -y /tmp/{filename}',
        'dnf': f'dnf install -y --setopt=install_weak_deps=False /tmp/{filename}',
        'pacman': ('cp /etc/pacman.conf /tmp/anland-pacman.conf && '
                   "sed -i '/^\\[options\\]/a LocalFileSigLevel = Optional' /tmp/anland-pacman.conf && "
                   f'pacman --config /tmp/anland-pacman.conf -U --noconfirm /tmp/{filename} && '
                   'rm /tmp/anland-pacman.conf'),
    }[config['package_manager']]
    dockerfile = builder / config['dockerfile']
    text = dockerfile.read_text(encoding='utf-8')
    base = f"FROM {config['base_image']} AS customizer\n"
    text = replace_once(text, base, base + f'COPY anland-overrides/{filename} /tmp/{filename}\n')
    text = replace_once(text, '/usr/local/sbin/install-anland-desktop "$DESKTOP" --1 &&',
                        f'{install} && rm /tmp/{filename} &&')
    if config['package_manager'] == 'pacman':
        # Keep the runtime's repository databases for the component build stage.
        # Refreshing only the build stage could link against a newer Arch ABI.
        text = text.replace(' /var/lib/pacman/sync/*', '')
    text = replace_once(text, 'FROM scratch AS export\n',
                        'FROM customizer AS component-builder\n'
                        'COPY anland-overrides/ /src/\n'
                        'RUN bash /src/build-components.sh\n\n'
                        'FROM customizer AS integrated\n'
                        'COPY --from=component-builder /out/ /tmp/anland-overrides/\n'
                        'COPY anland-overrides/install.sh anland-overrides/verify-installed.py /tmp/anland-overrides/\n'
                        'RUN bash /tmp/anland-overrides/install.sh && rm -rf /tmp/anland-overrides /usr/local/sbin/assets\n\n'
                        'FROM scratch AS export\n')
    text = replace_once(text, 'COPY --from=customizer / /', 'COPY --from=integrated / /')
    dockerfile.write_text(text, encoding='utf-8', newline='\n')
    script = builder / 'build_rootfs-native.sh'
    text = script.read_text(encoding='utf-8')
    start = text.index('if [ -n "$ANLAND_PACKAGE_FAMILY" ]; then\n')
    end = text.index('\nfi\n', start) + len('\nfi\n')
    text = text[:start] + ('# Session package is checksum-locked and supplied in the Docker context.\n'
                           f'ANLAND_PACKAGE_REVISION="{package["sha256"]}"\n') + text[end:]
    script.write_text(text, encoding='utf-8', newline='\n')


if __name__ == '__main__':
    integrate(*sys.argv[1:])
