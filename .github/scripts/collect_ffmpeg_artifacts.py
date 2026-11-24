#!/usr/bin/env python3
"""
Collects FFmpeg native library binaries (arm64-v8a, armeabi-v7a, x86_64, x86)
for APK packaging:
1. If non-expired artifacts already exist from a successful build-ffmpeg.yml run,
   downloads and extracts them.
2. Otherwise, if prebuilt libffmpeg.so binaries for all required ABIs already exist
   in --local-jni-dir (default: app/src/main/jniLibs), uses them directly without
   requiring workflow dispatch permissions or a 20+ minute NDK rebuild.
3. Otherwise, dispatches build-ffmpeg.yml, waits for completion, and downloads them.
"""

import argparse
import datetime
import json
import os
import shutil
import time
import urllib.error
import urllib.parse
import urllib.request
import zipfile
from pathlib import Path

REQUIRED_ABIS = ("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
WORKFLOW_FILE = "build-ffmpeg.yml"


class StripAuthOnCrossHostRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        new_req = super().redirect_request(req, fp, code, msg, headers, newurl)
        if new_req is not None:
            old_host = urllib.parse.urlsplit(req.full_url).netloc
            new_host = urllib.parse.urlsplit(newurl).netloc
            if old_host != new_host:
                new_req.remove_header("Authorization")
                new_req.remove_header("Accept")
        return new_req


_OPENER = urllib.request.build_opener(StripAuthOnCrossHostRedirect())


def gh_request(
    url: str,
    token: str,
    method: str = "GET",
    body: dict | None = None,
    accept: str = "application/vnd.github+json",
):
    headers = {
        "Authorization": f"Bearer {token}",
        "Accept": accept,
        "X-GitHub-Api-Version": "2022-11-28",
        "User-Agent": "VoiceRecorder-APK-CI/1.0",
    }
    data = None
    if body is not None:
        data = json.dumps(body).encode("utf-8")
        headers["Content-Type"] = "application/json"

    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    with _OPENER.open(req, timeout=60) as resp:
        raw = resp.read()
        return resp.status, raw


def gh_json(url: str, token: str, method: str = "GET", body: dict | None = None):
    status, raw = gh_request(url, token, method=method, body=body)
    if not raw:
        return status, {}
    return status, json.loads(raw.decode("utf-8"))


def download_artifact_zip(api_base: str, artifact: dict, token: str, dest_zip: Path):
    url = artifact.get("archive_download_url") or f"{api_base}/actions/artifacts/{artifact['id']}/zip"
    req = urllib.request.Request(
        url,
        headers={
            "Authorization": f"Bearer {token}",
            "Accept": "application/vnd.github+json",
            "X-GitHub-Api-Version": "2022-11-28",
            "User-Agent": "VoiceRecorder-APK-CI/1.0",
        },
    )
    dest_zip.parent.mkdir(parents=True, exist_ok=True)
    with _OPENER.open(req, timeout=120) as resp:
        dest_zip.write_bytes(resp.read())
    print(f"Downloaded {artifact['name']} -> {dest_zip} ({dest_zip.stat().st_size} bytes)")


def find_complete_artifacts_in_run(api_base: str, run_id: int, token: str):
    _, data = gh_json(f"{api_base}/actions/runs/{run_id}/artifacts?per_page=100", token)
    by_name = {
        a["name"]: a
        for a in data.get("artifacts", [])
        if not a.get("expired", False) and a.get("size_in_bytes", 0) > 0
    }
    per_abi = [by_name.get(f"libffmpeg-{abi}") for abi in REQUIRED_ABIS]
    if all(per_abi):
        return per_abi
    if "libffmpeg-bundle-all" in by_name:
        return [by_name["libffmpeg-bundle-all"]]
    return None


def find_existing_artifacts(api_base: str, token: str):
    _, runs_data = gh_json(
        f"{api_base}/actions/workflows/{WORKFLOW_FILE}/runs?status=completed&per_page=30",
        token,
    )
    for run in runs_data.get("workflow_runs", []):
        if run.get("conclusion") != "success":
            continue
        found = find_complete_artifacts_in_run(api_base, run["id"], token)
        if found:
            print(f"Found existing non-expired FFmpeg artifacts in run #{run['run_number']} (id={run['id']}).")
            return found
    return None


def copy_local_jni_libs(local_jni_dir: Path, lib_out_dir: Path) -> bool:
    if not local_jni_dir.is_dir():
        return False
    sources: dict[str, Path] = {}
    for abi in REQUIRED_ABIS:
        candidate = local_jni_dir / abi / "libffmpeg.so"
        if not candidate.is_file() or candidate.stat().st_size <= 0:
            return False
        sources[abi] = candidate

    if lib_out_dir.exists():
        shutil.rmtree(lib_out_dir)
    lib_out_dir.mkdir(parents=True, exist_ok=True)
    for abi, src_so in sources.items():
        target_abi_dir = lib_out_dir / abi
        target_abi_dir.mkdir(parents=True, exist_ok=True)
        shutil.copy2(src_so, target_abi_dir / "libffmpeg.so")
        print(f"Using prebuilt repository binary {src_so} -> {target_abi_dir / 'libffmpeg.so'}")
    return True


def find_active_run(api_base: str, token: str):
    for status in ("in_progress", "queued", "waiting"):
        _, data = gh_json(
            f"{api_base}/actions/workflows/{WORKFLOW_FILE}/runs?status={status}&per_page=10",
            token,
        )
        runs = data.get("workflow_runs", [])
        if runs:
            return runs[0]
    return None


def trigger_and_wait_ffmpeg(api_base: str, ref: str, token: str) -> int:
    active = find_active_run(api_base, token)
    if active:
        run_id = active["id"]
        print(f"FFmpeg build workflow is already active (run id={run_id}, status={active['status']}). Waiting...")
    else:
        dispatch_time = datetime.datetime.now(datetime.timezone.utc) - datetime.timedelta(seconds=5)
        print(f"No valid FFmpeg artifacts or local binaries found. Dispatching {WORKFLOW_FILE} on ref '{ref}'...")
        gh_json(
            f"{api_base}/actions/workflows/{WORKFLOW_FILE}/dispatches",
            token,
            method="POST",
            body={
                "ref": ref,
                "inputs": {
                    "target_abi": "all",
                    "verify_symbols": "enable",
                },
            },
        )
        run_id = None
        for _ in range(24):
            time.sleep(5)
            _, data = gh_json(
                f"{api_base}/actions/workflows/{WORKFLOW_FILE}/runs?event=workflow_dispatch&per_page=10",
                token,
            )
            for r in data.get("workflow_runs", []):
                created = datetime.datetime.fromisoformat(r["created_at"].replace("Z", "+00:00"))
                if created >= dispatch_time:
                    run_id = r["id"]
                    print(f"Started {WORKFLOW_FILE} run #{r['run_number']} (id={run_id}): {r['html_url']}")
                    break
            if run_id is not None:
                break
        if run_id is None:
            raise RuntimeError(f"Timed out waiting for dispatched {WORKFLOW_FILE} run to appear.")

    deadline = time.time() + 45 * 60
    while time.time() < deadline:
        _, run = gh_json(f"{api_base}/actions/runs/{run_id}", token)
        status = run.get("status")
        conclusion = run.get("conclusion")
        print(f"FFmpeg workflow run id={run_id}: status={status}, conclusion={conclusion}")
        if status == "completed":
            if conclusion != "success":
                raise RuntimeError(
                    f"{WORKFLOW_FILE} run id={run_id} failed with conclusion={conclusion}: {run.get('html_url')}"
                )
            return run_id
        time.sleep(15)

    raise RuntimeError(f"Timed out waiting for {WORKFLOW_FILE} run id={run_id} to complete.")


def extract_to_lib_dir(zip_files: list[Path], extract_dir: Path, lib_out_dir: Path):
    if extract_dir.exists():
        shutil.rmtree(extract_dir)
    if lib_out_dir.exists():
        shutil.rmtree(lib_out_dir)
    extract_dir.mkdir(parents=True, exist_ok=True)
    lib_out_dir.mkdir(parents=True, exist_ok=True)

    for zpath in zip_files:
        sub = extract_dir / zpath.stem
        sub.mkdir(parents=True, exist_ok=True)
        with zipfile.ZipFile(zpath, "r") as zf:
            zf.extractall(sub)
        print(f"Extracted {zpath.name} -> {sub}")

    for abi in REQUIRED_ABIS:
        matches = [
            p for p in extract_dir.rglob("libffmpeg.so")
            if p.parent.name == abi and p.stat().st_size > 0
        ]
        if not matches:
            raise RuntimeError(
                f"Missing libffmpeg.so for architecture '{abi}' inside extracted FFmpeg artifacts."
            )
        src_so = matches[0]
        target_abi_dir = lib_out_dir / abi
        target_abi_dir.mkdir(parents=True, exist_ok=True)
        shutil.copy2(src_so, target_abi_dir / "libffmpeg.so")
        print(f"Staged {abi}/libffmpeg.so -> {target_abi_dir / 'libffmpeg.so'}")


def download_and_stage_artifacts(api_base: str, artifacts: list[dict], token: str, zips_dir: Path, extract_dir: Path, lib_out_dir: Path):
    if zips_dir.exists():
        shutil.rmtree(zips_dir)
    zips_dir.mkdir(parents=True, exist_ok=True)

    downloaded_zips = []
    for art in artifacts:
        dest = zips_dir / f"{art['name']}.zip"
        download_artifact_zip(api_base, art, token, dest)
        downloaded_zips.append(dest)

    extract_to_lib_dir(downloaded_zips, extract_dir, lib_out_dir)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--repo", required=True, help="owner/repo")
    parser.add_argument("--ref", required=True, help="Git ref for workflow dispatch fallback")
    parser.add_argument("--zips-dir", default="build/ffmpeg_zips")
    parser.add_argument("--extract-dir", default="build/ffmpeg_extracted")
    parser.add_argument("--lib-out-dir", default="build/apk_stage/lib")
    parser.add_argument("--local-jni-dir", default="app/src/main/jniLibs")
    args = parser.parse_args()

    token = os.environ.get("GITHUB_TOKEN") or os.environ.get("GH_TOKEN")
    api_base = f"https://api.github.com/repos/{args.repo}"
    zips_dir = Path(args.zips_dir)
    extract_dir = Path(args.extract_dir)
    lib_out_dir = Path(args.lib_out_dir)
    local_jni_dir = Path(args.local_jni_dir)

    artifacts = None
    if token:
        try:
            artifacts = find_existing_artifacts(api_base, token)
        except Exception as exc:
            print(f"Warning: Could not query existing FFmpeg workflow artifacts ({exc}).")

    if artifacts and token:
        try:
            download_and_stage_artifacts(api_base, artifacts, token, zips_dir, extract_dir, lib_out_dir)
            print("All 4 ABI FFmpeg native libraries staged from workflow artifacts.")
            return
        except Exception as exc:
            print(f"Warning: Failed to download/extract FFmpeg artifacts ({exc}). Checking local jniLibs...")

    if copy_local_jni_libs(local_jni_dir, lib_out_dir):
        print("All 4 ABI FFmpeg native libraries staged from repository jniLibs.")
        return

    if not token:
        raise RuntimeError("GITHUB_TOKEN or GH_TOKEN environment variable is required to dispatch FFmpeg build.")

    run_id = trigger_and_wait_ffmpeg(api_base, args.ref, token)
    artifacts = find_complete_artifacts_in_run(api_base, run_id, token)
    if not artifacts:
        raise RuntimeError(
            f"{WORKFLOW_FILE} run id={run_id} completed, but required FFmpeg artifacts were not found."
        )
    download_and_stage_artifacts(api_base, artifacts, token, zips_dir, extract_dir, lib_out_dir)
    print("All 4 ABI FFmpeg native libraries staged successfully.")


if __name__ == "__main__":
    main()
