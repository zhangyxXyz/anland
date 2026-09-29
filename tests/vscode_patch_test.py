"""Guard installed-file patching: integrity, backups, idempotence and rollback."""
import base64
import hashlib
import importlib.util
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

source = Path(__file__).resolve().parents[1] / "patches/vscode/apply-edit-context-fix.py"
spec = importlib.util.spec_from_file_location("vscode_patch", source)
fix = importlib.util.module_from_spec(spec)
spec.loader.exec_module(fix)

ORIGINAL = ("_emitTypeEvent(e,t){if(!this._editContext)return;"
            "let i=this._previousEditContextSelection.endExclusive,n=0;}"
            "this._editContext.updateText(0,this._previousEditContextText.length,newText)")


class PatchTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.bundle = self.root / "out/vs/workbench/main.js"
        self.bundle.parent.mkdir(parents=True)
        self.bundle.write_text(ORIGINAL, encoding="utf-8")
        self.product = self.root / "product.json"
        self.metadata = {"checksums": {"vs/workbench/main.js": self.checksum(), "unrelated": "keep"}, "nameShort": "Code"}
        self.product.write_text(json.dumps(self.metadata), encoding="utf-8")
        self.original_manifest = self.product.read_bytes()

    def checksum(self):
        return base64.b64encode(hashlib.sha256(self.bundle.read_bytes()).digest()).decode().rstrip("=")

    def run_fix(self, apply=True):
        argv = [str(source), str(self.bundle), "--product", str(self.product)]
        if apply:
            argv.append("--apply")
        with patch.object(sys, "argv", argv):
            fix.main()

    def test_apply_preserves_originals_and_other_metadata(self):
        self.run_fix()
        self.assertEqual(self.bundle.read_text().count(fix.SYNC), 1)
        manifest = json.loads(self.product.read_text())
        self.assertEqual(manifest["checksums"]["vs/workbench/main.js"], self.checksum())
        self.assertEqual(manifest["checksums"]["unrelated"], "keep")
        self.assertEqual(manifest["nameShort"], "Code")
        self.assertEqual(next(self.bundle.parent.glob("*.bak")).read_text(), ORIGINAL)
        self.assertEqual(next(self.root.glob("*.bak")).read_bytes(), self.original_manifest)
        once = self.bundle.read_bytes(), self.product.read_bytes()
        self.run_fix()
        self.assertEqual(once, (self.bundle.read_bytes(), self.product.read_bytes()))

    def test_check_does_not_write(self):
        self.run_fix(False)
        self.assertEqual(self.bundle.read_text(), ORIGINAL)
        self.assertEqual(self.product.read_bytes(), self.original_manifest)
        self.assertFalse(list(self.root.rglob("*.bak")))

    def test_integrity_mismatch_rejected_without_writes(self):
        self.bundle.write_text(ORIGINAL + "altered", encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "integrity manifest"):
            self.run_fix()
        self.assertFalse(list(self.root.rglob("*.bak")))
        self.assertEqual(self.product.read_bytes(), self.original_manifest)

    def test_unknown_or_ambiguous_source_rejected(self):
        for content in (ORIGINAL.replace("_emitTypeEvent", "_changed"), ORIGINAL * 2):
            with self.assertRaisesRegex(ValueError, "Unreviewed"):
                fix.patched_bundle(content)

    def test_manifest_publish_failure_restores_both_files(self):
        atomic = fix.atomic_write
        failed = False
        def fail_once(path, content):
            nonlocal failed
            if path == self.product and not failed:
                failed = True
                raise OSError("simulated publication failure")
            atomic(path, content)
        with patch.object(fix, "atomic_write", fail_once), self.assertRaises(OSError):
            self.run_fix()
        self.assertEqual(self.bundle.read_text(), ORIGINAL)
        self.assertEqual(self.product.read_bytes(), self.original_manifest)


if __name__ == "__main__":
    unittest.main()
