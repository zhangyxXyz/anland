"""Prove an SSH session reaches this running container, without exporting secrets.

Host keys can be copied in rootfs images. A fresh, short-lived proof under this
container's /run distinguishes those clones even when their host keys match.
Only public host keys leave the container; the user's private key stays in Android.
"""
import base64
import json
import os
import pathlib
import pwd
import re
import secrets
import shutil
import subprocess
import sys


def checked_path(path):
    if not re.fullmatch(r"/run/anland-ssh-launch-[a-zA-Z0-9_-]+/proof", path):
        raise ValueError("Invalid proof path")
    proof = pathlib.Path(path)
    parent = proof.parent
    if parent.is_symlink() or parent.resolve() != parent:
        raise ValueError("Invalid proof directory")
    return proof


def cleanup(path):
    proof = checked_path(path)
    parent = proof.parent
    if parent.exists():
        if parent.stat().st_uid != 0:
            raise ValueError("Invalid proof owner")
        proof.unlink(missing_ok=True)
        parent.rmdir()


def prepare(username, path):
    proof = checked_path(path)
    user = pwd.getpwnam(username)
    config = subprocess.check_output(["/usr/sbin/sshd", "-T"], text=True, timeout=10)
    keys = []
    for line in config.splitlines():
        if not line.startswith("hostkey "):
            continue
        private_path = line.split(" ", 1)[1]
        public_path = pathlib.Path(private_path + ".pub")
        if public_path.is_file():
            public = public_path.read_text()
        else:
            # Custom host-key paths need not have a .pub companion. ssh-keygen
            # derives only the public key; private host material is never output.
            public = subprocess.check_output(
                ["ssh-keygen", "-y", "-P", "", "-f", private_path], text=True, timeout=10)
        fields = public.split()
        if len(fields) < 2 or not fields[0].startswith(("ssh-", "ecdsa-", "sk-")):
            raise ValueError("Invalid public host key")
        base64.b64decode(fields[1], validate=True)
        keys.append(fields[1])
    if not keys:
        raise ValueError("No public host keys")
    directory = proof.parent
    # Android chooses this unique path before invoking us, so it can also clean
    # up if the helper writes the proof but its result is lost or times out.
    directory.mkdir(mode=0o711)
    try:
        os.chmod(directory, 0o711)
        value = secrets.token_hex(32)
        proof.write_text(value + "\n")
        os.chown(proof, user.pw_uid, user.pw_gid)
        os.chmod(proof, 0o400)
        return dict(uid=user.pw_uid, host_keys=keys, path=str(proof), proof=value)
    except BaseException:
        shutil.rmtree(directory)
        raise


if sys.argv[1] == "prepare":
    print(json.dumps(prepare(sys.argv[2], sys.argv[3])))
elif sys.argv[1] == "cleanup":
    cleanup(sys.argv[2])
else:
    raise ValueError("Invalid operation")
