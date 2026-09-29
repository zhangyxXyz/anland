"""Resolve the reviewed RootFS target; Debian 13 is the shared default."""
import json
from pathlib import Path

SOURCES = Path(__file__).resolve().parents[2] / 'rootfs/sources.json'


def resolve(name=None):
    lock = json.loads(SOURCES.read_text(encoding='utf-8'))
    name = name or lock['default_target']
    if name not in lock['targets']:
        raise ValueError(f'Unsupported RootFS target: {name}')
    return name, lock['targets'][name]


def archive_name(versions, suffix, target=None):
    _, config = resolve(target)
    return f"anland-rootfs-{config['slug']}-arm64-{versions['ROOTFS_VERSION']}{suffix}.tar.xz"
