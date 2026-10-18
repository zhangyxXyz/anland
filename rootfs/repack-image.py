"""Apply the build's mount policy to an existing image without upgrading packages.

No guest files are extracted on the host. Every unchanged tar entry, including
Docker files, links, ownership and extended attributes, is checked after writing.
"""
import argparse
import copy
import hashlib
import importlib.util
import io
import json
import lzma
from pathlib import Path, PurePosixPath
import sys
import tarfile

SOURCE = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SOURCE / 'rootfs'))
sys.path.insert(0, str(SOURCE / 'scripts/ci'))
from mount_policy import FILES, hashes
from release import sha256
from rootfs_target import archive_name, resolve
from versions import read_versions

spec = importlib.util.spec_from_file_location('package_image', SOURCE / 'rootfs/package-image.py')
image = importlib.util.module_from_spec(spec)
spec.loader.exec_module(image)


class DigestReader:
    def __init__(self, stream):
        self.stream = stream
        self.digest = hashlib.sha256()

    def read(self, size=-1):
        data = self.stream.read(size)
        self.digest.update(data)
        return data


def name_of(member):
    name = member.name
    while name.startswith('./'):
        name = name[2:]
    name = name.rstrip('/')
    if name.startswith('/') or '..' in PurePosixPath(name).parts:
        raise ValueError(f'Unsafe archive member: {member.name}')
    return name


def fingerprint(member, digest):
    fields = ('mode', 'uid', 'gid', 'size', 'mtime', 'type', 'linkname',
              'uname', 'gname', 'devmajor', 'devminor', 'pax_headers')
    values = {key: getattr(member, key) for key in fields}
    # PAX may encode long paths or numeric fields that GNU tar encoded in its
    # header. Compare their decoded values above, retaining actual xattrs here.
    values['pax_headers'] = {key: value for key, value in member.pax_headers.items()
                             if key not in ('path', 'linkpath', 'size', 'uid', 'gid',
                                            'uname', 'gname', 'mtime', 'hdrcharset')}
    values['type'] = values['type'].decode('ascii')
    values['sha256'] = digest
    return hashlib.sha256(json.dumps(values, sort_keys=True).encode()).hexdigest()


def add_entry(tar, member, stream):
    reader = DigestReader(stream) if member.isfile() else None
    tar.addfile(member, reader)
    return fingerprint(member, reader.digest.hexdigest() if reader else None)


def repack(base, directory):
    base, directory = Path(base), Path(directory)
    versions = read_versions()
    print('Verifying base image runtime and provenance...', flush=True)
    manifest = image.verify_archive(base, require_mount_policy=False)
    target, config = resolve(manifest['target'])
    if manifest['target_config'] != config:
        raise ValueError('Base image target differs from current target configuration')
    if manifest['rootfs_version'] == versions['ROOTFS_VERSION']:
        raise ValueError('Bump ROOTFS_VERSION before repacking an image')
    directory.mkdir(parents=True, exist_ok=True)
    output = directory / archive_name(versions, '', target)
    partial = output.with_name(output.name + '.partial')
    if output.exists() or partial.exists():
        raise FileExistsError(output)
    base_hash = sha256(base)
    updated = copy.deepcopy(manifest)
    updated['rootfs_version'] = versions['ROOTFS_VERSION']
    updated['service_mount_policy'] = hashes()
    updated['configuration_repack'] = {
        'base_archive': base.name,
        'base_sha256': base_hash,
        'base_rootfs_version': manifest['rootfs_version'],
        'method': 'mount-policy-only; original runtime and package files preserved',
        'tools': {name: sha256(SOURCE / 'rootfs' / name)
                  for name in ('mount_policy.py', 'repack-image.py')},
    }
    replacements = {**FILES, image.METADATA: (json.dumps(updated, indent=2) + '\n').encode()}
    parents = {parent.as_posix() for name in FILES
               for parent in PurePosixPath(name).parents if parent.as_posix() != '.'}
    expected, added, changed = {}, [], []
    seen = set()
    print('Writing configuration-only image (all other files retained)...', flush=True)
    with partial.open('xb') as raw, lzma.LZMAFile(raw, 'w', preset=3) as compressed, \
            tarfile.open(fileobj=compressed, mode='w|') as dest:
        with tarfile.open(base, 'r|xz') as src:
            for member in src:
                name = name_of(member)
                if member.sparse is not None:
                    raise ValueError(f'Sparse tar entry needs a native tar repack: {name}')
                if name in seen:
                    raise ValueError(f'Duplicate archive path: {name}')
                seen.add(name)
                if name in parents and not member.isdir():
                    raise ValueError(f'Policy parent is not a directory: {name}')
                stream = src.extractfile(member) if member.isfile() else None
                if name in replacements:
                    if not member.isfile():
                        raise ValueError(f'Cannot replace nonregular file: {name}')
                    old = stream.read()
                    data = replacements[name]
                    if name in FILES and old != data:
                        raise ValueError(f'Conflicting existing mount policy: {name}')
                    member = copy.deepcopy(member)
                    member.size = len(data)
                    if 'size' in member.pax_headers:
                        member.pax_headers['size'] = str(len(data))
                    stream = io.BytesIO(data)
                    if old != data:
                        changed.append(name)
                expected[name] = add_entry(dest, member, stream)
        if image.METADATA not in seen:
            raise ValueError('Base image manifest missing')
        for name in sorted(parents - seen):
            member = tarfile.TarInfo(name)
            member.type = tarfile.DIRTYPE
            member.mode = 0o755
            member.uname = member.gname = 'root'
            expected[name] = add_entry(dest, member, None)
            added.append(name)
        for name, data in FILES.items():
            if name in seen:
                continue
            member = tarfile.TarInfo(name)
            member.mode = 0o644
            member.uname = member.gname = 'root'
            member.size = len(data)
            expected[name] = add_entry(dest, member, io.BytesIO(data))
            added.append(name)
    print('Checking every exported entry against the base plus allowed changes...', flush=True)
    checked = set()
    with tarfile.open(partial, 'r|xz') as tar:
        for member in tar:
            name = name_of(member)
            if name in checked or name not in expected:
                raise ValueError(f'Unexpected exported member: {name}')
            checked.add(name)
            digest = None
            if member.isfile():
                reader = DigestReader(tar.extractfile(member))
                while reader.read(1024 * 1024):
                    pass
                digest = reader.digest.hexdigest()
            if fingerprint(member, digest) != expected[name]:
                raise ValueError(f'Exported entry changed unexpectedly: {name}')
    if checked != expected.keys():
        raise ValueError('Exported entries missing')
    print('Verifying runtime hashes and all six mount policy files...', flush=True)
    if image.verify_archive(partial) != updated:
        raise ValueError('Exported provenance differs')
    output_hash = sha256(partial)
    partial.rename(output)
    (directory / 'ROOTFS-ARCHIVE-SHA256SUMS').write_text(
        f'{output_hash}  {output.name}\n', encoding='utf-8', newline='\n')
    (directory / 'rootfs-components.json').write_text(
        json.dumps(updated, indent=2) + '\n', encoding='utf-8', newline='\n')
    report = {
        'base_sha256': base_hash, 'output_sha256': output_hash,
        'verified_entries': len(checked), 'added_entries': sorted(added),
        'modified_entries': sorted(changed),
        'unchanged_base_entries': len(seen) - len(changed),
        'docker_and_other_existing_files': 'content and tar metadata unchanged',
        'boot_test': 'not performed on this archive; same six settings previously tested on device',
    }
    (directory / 'repack-verification.json').write_text(
        json.dumps(report, indent=2) + '\n', encoding='utf-8', newline='\n')
    print(json.dumps(report, indent=2), flush=True)
    print(f'Created {output}', flush=True)
    return output


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('base_archive')
    parser.add_argument('output_directory')
    args = parser.parse_args()
    repack(args.base_archive, args.output_directory)
