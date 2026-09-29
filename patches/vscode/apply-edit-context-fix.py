#!/usr/bin/env python3
"""Apply the adjacent source fix to a reviewed VS Code compiled bundle.

Explicit path only; never discover installations or patch on app startup.
Package upgrades replace this file: check the new source before reapplying.
"""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import tempfile

SYNC = "this._previousEditContextText=this._editContext.text;"
METHOD = re.compile(r"(_emitTypeEvent\([A-Za-z_$][\w$]*,[A-Za-z_$][\w$]*\)"
                    r"\{if\(!this\._editContext\)return;)")
UPDATE = "this._editContext.updateText(0,this._previousEditContextText.length,"


def patched_bundle(text):
    matches = list(METHOD.finditer(text))
    if len(matches) != 1 or text.count(UPDATE) != 1:
        raise ValueError("Unreviewed VS Code bundle: expected source anchors changed")
    end = matches[0].end()
    if text.startswith(SYNC, end):
        return text
    # Guard the surrounding method as well as its name, and never double-patch.
    if SYNC in text or not re.match(
            r"(?:let|const) [A-Za-z_$][\w$]*=this\._previousEditContextSelection\.endExclusive,",
            text[end:]):
        raise ValueError("Unreviewed VS Code textupdate implementation")
    return text[:end] + SYNC + text[end:]


def backup_file(path, original):
    digest = hashlib.sha256(original).hexdigest()
    backup = path.with_name(path.name + ".anland-edit-context-" + digest[:16] + ".bak")
    if backup.exists():
        if backup.read_bytes() != original:
            raise ValueError("Existing backup does not match original")
    else:
        shutil.copy2(path, backup)
    print("Backup=" + str(backup))


def atomic_write(path, content):
    stat = path.stat()
    fd, temporary = tempfile.mkstemp(prefix=path.name + ".", dir=path.parent)
    try:
        with os.fdopen(fd, "wb") as out:
            out.write(content)
            out.flush()
            os.fsync(out.fileno())
        shutil.copystat(path, temporary)
        if hasattr(os, "chown"):
            os.chown(temporary, stat.st_uid, stat.st_gid)
        os.replace(temporary, path)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("bundle", type=Path)
    parser.add_argument("--product", type=Path, required=True, help="matching product.json integrity manifest")
    parser.add_argument("--apply", action="store_true", help="write after validation; default is check only")
    args = parser.parse_args()
    path = args.bundle.resolve(strict=True)
    product = args.product.resolve(strict=True)
    key = path.relative_to(product.parent / "out").as_posix()
    original = path.read_bytes()
    manifest = product.read_bytes()
    metadata = json.loads(manifest)
    expected = metadata.get("checksums", {}).get(key)
    actual = base64.b64encode(hashlib.sha256(original).digest()).decode().rstrip("=")
    if expected != actual:
        raise ValueError("Bundle does not match its integrity manifest; refusing to patch")
    patched = patched_bundle(original.decode("utf-8")).encode("utf-8")
    digest = hashlib.sha256(original).hexdigest()
    if patched == original:
        print("Already fixed; integrity verified; SHA256=" + digest)
        return
    print("Reviewed anchors and integrity verified; original SHA256=" + digest)
    if not args.apply:
        return
    metadata["checksums"][key] = base64.b64encode(hashlib.sha256(patched).digest()).decode().rstrip("=")
    # Keep the integrity checker enabled with the new local build's exact hash.
    # Both originals are retained; restore the bundle if publishing fails.
    backup_file(path, original)
    backup_file(product, manifest)
    try:
        atomic_write(path, patched)
        atomic_write(product, (json.dumps(metadata, ensure_ascii=False, indent=2) + "\n").encode("utf-8"))
    except BaseException:
        atomic_write(path, original)
        atomic_write(product, manifest)
        raise
    print("Fixed SHA256=" + hashlib.sha256(patched).hexdigest())


if __name__ == "__main__":
    main()
