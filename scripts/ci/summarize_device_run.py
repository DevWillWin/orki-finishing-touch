#!/usr/bin/env python3
"""Turns build-reports/device + the JUnit XML of connectedDebugAndroidTest into summary.md / details.md."""
import glob
import os
import re
import sys
import xml.etree.ElementTree as ET

OUT = "build-reports/device"


def read(path, limit=10**7):
    try:
        with open(path, errors="replace") as fh:
            return fh.read()[:limit]
    except OSError:
        return ""


def main():
    total = failed = skipped = 0
    per_class, problems, device_lines = [], [], []
    system_out = []
    for f in sorted(glob.glob("app/build/outputs/androidTest-results/**/*.xml", recursive=True)):
        try:
            root = ET.parse(f).getroot()
        except ET.ParseError:
            continue
        for suite in ([root] if root.tag == "testsuite" else root.iter("testsuite")):
            t, fl, er, sk = (int(suite.get(k, 0)) for k in ("tests", "failures", "errors", "skipped"))
            total += t; failed += fl + er; skipped += sk
            per_class.append("%s: tests=%d failures=%d skipped=%d time=%ss" % (suite.get("name"), t, fl + er, sk, suite.get("time")))
            for tc in suite.iter("testcase"):
                status = "PASS"
                for kind in ("failure", "error"):
                    for e in tc.findall(kind):
                        status = "FAIL"
                        problems.append("%s.%s: %s\n%s" % (tc.get("classname"), tc.get("name"), (e.get("message") or "")[:700], (e.text or "")[:1500]))
                if tc.find("skipped") is not None:
                    status = "SKIP"
                device_lines.append("%-5s %s.%s (%ss)" % (status, tc.get("classname", "").split(".")[-1], tc.get("name"), tc.get("time")))
            for so in suite.iter("system-out"):
                system_out.append(so.text or "")

    logcat = read(os.path.join(OUT, "logcat_full.txt"))
    tagged = [l for l in logcat.splitlines() if re.search(r" (OrkiDevice|BodoTTS|PerchanceImageEngine|OrkiBuild)\s*:", l)]
    deaths = [l for l in logcat.splitlines() if re.search(r"am_kill|am_proc_died|am_anr|ANR in|lmkd|lowmemorykiller|FATAL EXCEPTION|Fatal signal|has died|am_low_memory", l)]

    gradle_exit = read(os.path.join(OUT, "gradle_exit.txt")).strip()
    device = read(os.path.join(OUT, "device.txt"))
    verdict = "PASS" if (failed == 0 and total > 0 and gradle_exit == "gradle_exit=0") else "FAIL"
    summary = "device tests: **%s** (tests=%d failed=%d skipped=%d, %s)\n\n```\n%s\n```\n\n" % (verdict, total, failed, skipped, gradle_exit, device.strip())
    summary += "\n".join("- " + c for c in per_class) + "\n\n```\n" + "\n".join(device_lines) + "\n```\n"
    details = "## Failing tests\n```\n%s\n```\n" % ("\n\n".join(problems[:12]) or "none")
    details += "\n## Process deaths / kills / ANRs in logcat\n```\n%s\n```\n" % ("\n".join(deaths[:40]) or "none")
    details += "\n## App logs (OrkiDevice / BodoTTS / PerchanceImageEngine)\n```\n%s\n```\n" % ("\n".join(l[-260:] for l in tagged[-220:]) or "none")
    with open(os.path.join(OUT, "summary.md"), "w") as fh:
        fh.write(summary)
    with open(os.path.join(OUT, "details.md"), "w") as fh:
        fh.write(details)
    with open(os.path.join(OUT, "verdict.txt"), "w") as fh:
        fh.write(verdict + " tests=%d failed=%d" % (total, failed))
    print(summary)


if __name__ == "__main__":
    main()
