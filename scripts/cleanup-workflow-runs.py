"""Keep each workflow's six newest runs and all runs younger than 30 days."""

import datetime as dt
import json
import os
import subprocess


def eligible_runs(runs, now):
    cutoff = now - dt.timedelta(days=30)
    ordered = sorted(runs, key=lambda run: (run["created_at"], run["id"]), reverse=True)
    # Count all conclusions (including failure/cancelled), across branches/tags.
    return [
        run for run in ordered[6:]
        if run["status"] == "completed"
        and dt.datetime.fromisoformat(run["created_at"].replace("Z", "+00:00")) < cutoff
    ]


def pages(endpoint):
    result = subprocess.check_output(
        ["gh", "api", endpoint, "--paginate", "--slurp"], text=True
    )
    return json.loads(result)


def main():
    repo = os.environ["GH_REPO"]
    # A missing value is safe for local/manual execution; schedules pass false.
    dry_run = os.environ.get("DRY_RUN", "true").lower() != "false"
    now = dt.datetime.now(dt.timezone.utc)
    eligible = deleted = 0
    workflows = [
        workflow
        for page in pages(f"repos/{repo}/actions/workflows?per_page=100")
        for workflow in page["workflows"]
    ]
    for workflow in workflows:
        # Fetch every page before deleting, so pagination cannot skip shifted rows.
        runs = [
            run
            for page in pages(f"repos/{repo}/actions/workflows/{workflow['id']}/runs?per_page=100")
            for run in page["workflow_runs"]
        ]
        candidates = eligible_runs(runs, now)
        eligible += len(candidates)
        print(f"Workflow {workflow['id']}: {len(runs)} runs, {len(candidates)} eligible", flush=True)
        for run in candidates:
            print(
                f"{'Would delete' if dry_run else 'Delete'} {run['html_url']} "
                f"({run['created_at']}, {run.get('conclusion')})", flush=True
            )
            if not dry_run:
                subprocess.run(
                    ["gh", "api", "--method", "DELETE", f"repos/{repo}/actions/runs/{run['id']}"],
                    check=True,
                )
                deleted += 1
    summary = f"Dry run: {dry_run}; eligible runs: {eligible}; deleted runs: {deleted}."
    print(summary)
    if os.environ.get("GITHUB_STEP_SUMMARY"):
        with open(os.environ["GITHUB_STEP_SUMMARY"], "a", encoding="utf-8") as output:
            output.write(summary + "\n")


if __name__ == "__main__":
    main()
