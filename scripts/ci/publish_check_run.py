#!/usr/bin/env python3
"""
Publishes a CI report as a GitHub check run so it is readable through the API / Checks tab even when raw
job logs are not downloadable. Used by .github/workflows/android-device-tests.yml.

usage: publish_check_run.py NAME CONCLUSION TITLE SUMMARY_FILE TEXT_FILE
  CONCLUSION: success | failure | neutral
Needs env GH_TOKEN (github.token with checks: write), GITHUB_SHA and GITHUB_REPOSITORY.
"""
import json
import os
import subprocess
import sys


def read(path, limit):
    try:
        with open(path, errors="replace") as fh:
            return fh.read()[:limit]
    except OSError:
        return "(missing: %s)" % path


def main():
    name, conclusion, title, summary_file, text_file = sys.argv[1:6]
    payload = {
        "name": name,
        "head_sha": os.environ["GITHUB_SHA"],
        "status": "completed",
        "conclusion": conclusion,
        "output": {
            "title": title[:250],
            "summary": read(summary_file, 60000),
            "text": read(text_file, 64000),
        },
    }
    with open("check_payload.json", "w") as fh:
        json.dump(payload, fh)
    result = subprocess.run(
        ["gh", "api", "-X", "POST", "repos/%s/check-runs" % os.environ["GITHUB_REPOSITORY"], "--input", "check_payload.json"],
        capture_output=True, text=True,
    )
    if result.returncode != 0:
        print("check-run publish failed:", result.stderr[:500])


if __name__ == "__main__":
    main()
