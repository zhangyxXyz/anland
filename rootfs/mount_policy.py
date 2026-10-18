"""Persistent, per-service mount propagation policy shared by image builders."""
import hashlib
from pathlib import Path
import sys

# Keep this allowlist explicit. In particular, Docker and containerd must retain
# their upstream propagation settings. Versioned PHP units need separate review.
SERVICES = (
    'systemd-resolved', 'systemd-networkd', 'systemd-logind',
    'polkit', 'php8.4-fpm', 'mariadb',
)
CONTENT = b'[Service]\nMountFlags=slave\n'
FILES = {
    f'etc/systemd/system/{service}.service.d/90-anland-mountflags-slave.conf': CONTENT
    for service in SERVICES
}


def hashes():
    return {path: hashlib.sha256(data).hexdigest() for path, data in FILES.items()}


def verify(root, manifest):
    if manifest.get('service_mount_policy') != hashes():
        raise ValueError('Image manifest has a different service mount policy')
    for name, expected in FILES.items():
        path = Path(root) / name
        if path.is_symlink() or not path.is_file() or path.read_bytes() != expected:
            raise ValueError(f'Missing or modified service mount policy: {name}')


def install(root):
    root = Path(root).resolve()
    # Install even when a unit is not yet present: its policy then also applies
    # when that exact service is installed later. This does not enable services.
    for name, data in FILES.items():
        path = root / name
        if not path.resolve().is_relative_to(root) or path.is_symlink():
            raise ValueError(f'Unexpected policy path: {path}')
        if path.exists() and (not path.is_file() or path.read_bytes() != data):
            raise ValueError(f'Refusing to overwrite a different policy: {path}')
    for name, data in FILES.items():
        path = root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)
        path.chmod(0o644)


if __name__ == '__main__':
    install(sys.argv[1])
