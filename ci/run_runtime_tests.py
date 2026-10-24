"""Run real Fabric runtime tests in build/run; never target a user's saves.

Example (Windows with Git Bash):
  python ci/run_runtime_tests.py --java-home C:/path/to/jdk-25 \
      --gradle C:/path/to/gradle/bin/gradle --client

Requires a display/OpenGL for --client. Server tests always run first. This script
never fabricates test output and exits nonzero on missing runtime evidence.
"""
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import shutil
import signal
import subprocess
import time


ROOT = Path(__file__).resolve().parents[1]


def run_stage(gradle: Path, task: str, environment: dict[str, str], report_dir: Path,
              timeout: int) -> dict[str, object]:
    log = report_dir / f"{task}.log"
    bash = shutil.which("bash")
    if os.name == "nt" and bash is None:
        raise RuntimeError("Git Bash not found")
    command = ([str(gradle)] if os.name != "nt" else [str(bash), gradle.as_posix()]) + [
        "--no-daemon", "--no-configuration-cache", "--console=plain", task,
    ]
    started = time.time()
    with log.open("w", encoding="utf-8") as output:
        with subprocess.Popen(command, cwd=ROOT, env=environment, stdout=output,
                              stderr=subprocess.STDOUT, start_new_session=os.name != "nt") as process:
            try:
                returncode = process.wait(timeout=timeout)
            except subprocess.TimeoutExpired:
                if os.name == "nt":
                    subprocess.run(["taskkill", "/PID", str(process.pid), "/T", "/F"],
                                   stdout=output, stderr=subprocess.STDOUT, check=False)
                else:
                    os.killpg(process.pid, signal.SIGKILL)
                process.kill()
                process.wait()
                return {"task": task, "passed": False, "reason": "runtime timeout", "log": str(log)}
    text = log.read_text(encoding="utf-8", errors="replace")
    print(text[-12000:])
    stage: dict[str, object] = {"task": task, "passed": returncode == 0,
                                "exit_code": returncode, "log": str(log)}
    markers = (["COUNTERSTRIKE_RUNTIME_EVIDENCE real generated block checks=",
                "COUNTERSTRIKE_RUNTIME_EVIDENCE combat tick=",
                "COUNTERSTRIKE_RUNTIME_EVIDENCE custom combat tick=",
                "COUNTERSTRIKE_INVENTORY_EVIDENCE",
                "COUNTERSTRIKE_BOT_SAFETY shouldBeSaved=false"] if task == "runGameTest" else
               ["COUNTERSTRIKE_CLIENT_EVIDENCE snapshot restored after leave, stop, disconnect and restart"])
    stage["evidence"] = [line for line in text.splitlines() if "COUNTERSTRIKE_" in line]
    missing = [marker for marker in markers if marker not in text]
    if missing:
        stage.update(passed=False, missing_evidence=missing)
    if task == "runClientGameTest":
        screenshots = []
        missing_screenshots = []
        for name in ["harbor-participant-running", "harbor-real-render-overview",
                     "harbor-restored-after-restart"]:
            files = list((ROOT / "build/run/clientGameTest").rglob(f"*{name}*.png"))
            valid = [p for p in files if p.stat().st_mtime >= started - 2
                     and p.read_bytes()[:8] == b"\x89PNG\r\n\x1a\n"]
            if not valid:
                stage.update(passed=False)
                missing_screenshots.append(name)
            screenshots.extend(str(p) for p in valid)
        stage["missing_screenshots"] = missing_screenshots
        stage["screenshots"] = screenshots
    return stage


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java-home", type=Path, required=True)
    parser.add_argument("--gradle", type=Path, required=True,
                        help="Gradle shell launcher (not gradle.bat)")
    parser.add_argument("--client", action="store_true", help="Also run real renderer/snapshot smoke")
    parser.add_argument("--timeout", type=int, default=900, help="Seconds allowed per Gradle stage")
    args = parser.parse_args()
    java = args.java_home / "bin" / ("java.exe" if os.name == "nt" else "java")
    if not java.is_file() or not args.gradle.is_file():
        parser.error("Both JDK bin/java and Gradle launcher must exist")
    if os.name == "nt" and not shutil.which("bash"):
        parser.error("Git Bash is required on Windows")
    if args.timeout <= 0:
        parser.error("--timeout must be positive")
    environment = dict(os.environ)
    environment["JAVA_HOME"] = str(args.java_home.resolve())
    environment["PATH"] = str(args.java_home.resolve() / "bin") + os.pathsep + environment["PATH"]
    report_dir = ROOT / "build/reports/counterstrike-runtime"
    report_dir.mkdir(parents=True, exist_ok=True)
    stages = [run_stage(args.gradle.resolve(), "runGameTest", environment, report_dir, args.timeout)]
    if args.client and stages[-1]["passed"]:
        stages.append(run_stage(args.gradle.resolve(), "runClientGameTest", environment, report_dir, args.timeout))
    report = {"passed": all(stage["passed"] for stage in stages)
              and (not args.client or len(stages) == 2), "client_requested": args.client, "stages": stages}
    destination = report_dir / "results.json"
    destination.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(f"Runtime report: {destination}")
    return 0 if report["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
