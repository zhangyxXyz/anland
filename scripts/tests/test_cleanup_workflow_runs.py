import datetime as dt
import importlib.util
from pathlib import Path
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location(
    "cleanup", Path(__file__).resolve().parents[1] / "cleanup-workflow-runs.py"
)
cleanup = importlib.util.module_from_spec(spec)
spec.loader.exec_module(cleanup)
NOW = dt.datetime(2026, 9, 15, tzinfo=dt.timezone.utc)


def run(identifier, days, status="completed", conclusion="success"):
    return {"id": identifier, "created_at": (NOW - dt.timedelta(days=days)).isoformat(),
            "status": status, "conclusion": conclusion, "html_url": f"https://example.com/{identifier}"}


class CleanupTests(unittest.TestCase):
    def test_keeps_six_newest_regardless_of_conclusion(self):
        runs = [run(i, 40 + i, conclusion="failure" if i < 6 else "success") for i in range(10)]
        self.assertEqual([r["id"] for r in cleanup.eligible_runs(list(reversed(runs)), NOW)], [6, 7, 8, 9])
        self.assertEqual(cleanup.eligible_runs(runs[:5], NOW), [])

    def test_age_boundary_and_active_runs(self):
        runs = [run(i, i) for i in range(6)] + [
            run(6, 29), run(7, 30), run(8, 31, "in_progress"),
            run(9, 32, conclusion="failure"), run(10, 33, conclusion="cancelled")]
        self.assertEqual([r["id"] for r in cleanup.eligible_runs(runs, NOW)], [9, 10])

    def test_dry_run_never_deletes_and_keeps_workflows_separate(self):
        workflows = [{"workflows": [{"id": 1}, {"id": 2}]}]
        first = [{"workflow_runs": [run(i, 1000 + i) for i in range(4)]},
                 {"workflow_runs": [run(i, 1000 + i) for i in range(4, 8)]}]
        second = [{"workflow_runs": [run(20, 1000)]}]
        with patch.dict(cleanup.os.environ, {"GH_REPO": "owner/repo", "DRY_RUN": "true", "GITHUB_STEP_SUMMARY": ""}), \
                patch.object(cleanup, "pages", side_effect=[workflows, first, second]), \
                patch.object(cleanup.subprocess, "run") as delete:
            cleanup.main()
            delete.assert_not_called()

    def test_live_mode_deletes_only_eligible_runs(self):
        with patch.dict(cleanup.os.environ, {"GH_REPO": "owner/repo", "DRY_RUN": "false", "GITHUB_STEP_SUMMARY": ""}), \
                patch.object(cleanup, "pages", side_effect=[
                    [{"workflows": [{"id": 1}]}],
                    [{"workflow_runs": [run(i, 1000 + i) for i in range(7)]}],
                ]), patch.object(cleanup.subprocess, "run") as delete:
            cleanup.main()
            delete.assert_called_once_with(
                ["gh", "api", "--method", "DELETE", "repos/owner/repo/actions/runs/6"], check=True)


if __name__ == "__main__":
    unittest.main()
