#!/usr/bin/env python3
"""
Resolves Android compile/runtime dependencies directly from Gradle libs.versions.toml
and app/build.gradle.kts using Google Maven and Maven Central (.module + .pom),
extracts AARs, and generates a merged AndroidManifest.xml.
"""

import argparse
import json
import os
import re
import shutil
import sys
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET
import zipfile
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

REPOS = [
    "https://dl.google.com/dl/android/maven2",
    "https://repo1.maven.org/maven2",
    "https://maven-central.storage-download.googleapis.com/maven2",
]

ANDROID_NS = "http://schemas.android.com/apk/res/android"
TOOLS_NS = "http://schemas.android.com/tools"
ET.register_namespace("android", ANDROID_NS)
ET.register_namespace("tools", TOOLS_NS)


def parse_version_tuple(v: str):
    parts = []
    for token in re.split(r"[.\-+]", v):
        if token.isdigit():
            parts.append((0, int(token), ""))
        else:
            m = re.match(r"^(\d+)(.*)$", token)
            if m:
                parts.append((0, int(m.group(1)), m.group(2)))
            else:
                parts.append((1, 0, token))
    return tuple(parts)


def is_higher_version(new_v: str, old_v: str) -> bool:
    return parse_version_tuple(new_v) > parse_version_tuple(old_v)


def normalize_version_spec(spec: dict) -> str | None:
    if not spec:
        return None
    for key in ("strictly", "requires", "prefers"):
        val = spec.get(key)
        if val:
            # Handle simple ranges like [1.0.0,2.0.0) or [1.0.0]
            val = val.strip()
            if val.startswith("[") and val.endswith("]") and "," not in val:
                return val[1:-1].strip()
            if val.startswith("[") or val.startswith("("):
                inner = val[1:-1].split(",")[0].strip()
                if inner:
                    return inner
            return val
    return None


def parse_versions_toml(toml_path: Path):
    versions = {}
    libraries = {}
    section = None
    for raw_line in toml_path.read_text(encoding="utf-8").splitlines():
        line = raw_line.split("#", 1)[0].strip()
        if not line:
            continue
        if line.startswith("[") and line.endswith("]"):
            section = line[1:-1].strip()
            continue
        if "=" not in line:
            continue
        key, val = [x.strip() for x in line.split("=", 1)]
        if section == "versions":
            versions[key] = val.strip('"').strip("'")
        elif section == "libraries":
            group = re.search(r'group\s*=\s*"([^"]+)"', val)
            name = re.search(r'name\s*=\s*"([^"]+)"', val)
            ver = re.search(r'version\s*=\s*"([^"]+)"', val)
            ver_ref = re.search(r'version\.ref\s*=\s*"([^"]+)"', val)
            module = re.search(r'module\s*=\s*"([^"]+)"', val)
            if module:
                g, n = module.group(1).split(":", 1)
            elif group and name:
                g, n = group.group(1), name.group(1)
            else:
                continue
            v = None
            if ver:
                v = ver.group(1)
            elif ver_ref:
                v = versions.get(ver_ref.group(1))
            libraries[key] = (g, n, v)
    return versions, libraries


def strip_kotlin_comments(text: str) -> str:
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.DOTALL)
    lines = []
    for line in text.splitlines():
        # Strip // outside quotes (simple heuristic sufficient for build.gradle.kts)
        idx = line.find("//")
        if idx != -1 and "://" not in line[: idx + 3]:
            line = line[:idx]
        lines.append(line)
    return "\n".join(lines)


def parse_app_gradle(gradle_path: Path, libraries: dict):
    content = strip_kotlin_comments(gradle_path.read_text(encoding="utf-8"))
    deps = []
    # Match implementation(platform(libs.foo.bar)) or implementation(libs.foo.bar)
    for m in re.finditer(
        r"\bimplementation\s*\(\s*(platform\s*\(\s*)?libs\.([A-Za-z0-9_.]+)\s*\)?\s*\)",
        content,
    ):
        is_platform = bool(m.group(1))
        lib_key = m.group(2).replace(".", "-")
        if lib_key not in libraries:
            raise RuntimeError(f"Library key '{lib_key}' from build.gradle.kts not found in libs.versions.toml")
        g, a, v = libraries[lib_key]
        deps.append((g, a, v, is_platform))

    app_id_m = re.search(r'applicationId\s*=\s*"([^"]+)"', content)
    min_sdk_m = re.search(r"minSdk\s*=\s*(\d+)", content)
    target_sdk_m = re.search(r"targetSdk\s*=\s*(\d+)", content)
    ver_code_m = re.search(r"versionCode\s*=\s*(\d+)", content)
    ver_name_m = re.search(r'versionName\s*=\s*"([^"]+)"', content)

    meta = {
        "applicationId": app_id_m.group(1) if app_id_m else "com.example.app",
        "minSdk": min_sdk_m.group(1) if min_sdk_m else "24",
        "targetSdk": target_sdk_m.group(1) if target_sdk_m else "36",
        "versionCode": ver_code_m.group(1) if ver_code_m else "1",
        "versionName": ver_name_m.group(1) if ver_name_m else "1.0",
    }
    return deps, meta


def fetch_bytes(url: str, cache_file: Path | None = None) -> bytes | None:
    if cache_file and cache_file.exists() and cache_file.stat().st_size > 0:
        return cache_file.read_bytes()
    req = urllib.request.Request(url, headers={"User-Agent": "AndroidCliBuild/1.0"})
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            data = resp.read()
            if cache_file:
                cache_file.parent.mkdir(parents=True, exist_ok=True)
                tmp = cache_file.with_suffix(cache_file.suffix + ".tmp")
                tmp.write_bytes(data)
                tmp.replace(cache_file)
            return data
    except urllib.error.HTTPError as e:
        if e.code == 404:
            return None
        raise


def fetch_from_repos(rel_path: str, cache_dir: Path) -> tuple[bytes, str] | None:
    cache_file = cache_dir / "meta" / rel_path
    if cache_file.exists() and cache_file.stat().st_size > 0:
        repo_marker = cache_file.with_suffix(cache_file.suffix + ".repo")
        repo = repo_marker.read_text(encoding="utf-8").strip() if repo_marker.exists() else REPOS[0]
        return cache_file.read_bytes(), repo
    for repo in REPOS:
        url = f"{repo}/{rel_path}"
        data = fetch_bytes(url, cache_file)
        if data is not None:
            repo_marker = cache_file.with_suffix(cache_file.suffix + ".repo")
            repo_marker.write_text(repo, encoding="utf-8")
            return data, repo
    return None


def pick_module_variant(variants: list, want_platform: bool):
    best = None
    best_score = -1
    for v in variants:
        attrs = v.get("attributes", {})
        cat = attrs.get("org.gradle.category")
        usage = attrs.get("org.gradle.usage")
        kmp = attrs.get("org.jetbrains.kotlin.platform.type")
        build_type = attrs.get("com.android.build.api.attributes.BuildTypeAttr")
        jvm_env = attrs.get("org.gradle.jvm.environment")

        if want_platform:
            if cat not in ("platform", "enforced-platform"):
                continue
            score = 10 if usage == "java-runtime" else (5 if usage == "java-api" else 1)
            if score > best_score:
                best = v
                best_score = score
            continue

        if cat in ("documentation", "platform", "enforced-platform"):
            continue
        if usage not in ("java-runtime", "java-api", None):
            continue
        if kmp not in ("androidJvm", "jvm", "common", None):
            continue
        if build_type not in ("release", None):
            continue
        if not v.get("files") and not v.get("available-at"):
            continue

        score = 0
        if usage == "java-runtime":
            score += 100
        elif usage == "java-api":
            score += 50
        if kmp == "androidJvm":
            score += 40
        elif kmp == "jvm":
            score += 20
        elif kmp == "common":
            score += 10
        if jvm_env == "android":
            score += 15
        elif jvm_env == "standard-jvm":
            score += 5
        if v.get("files"):
            score += 2
        if score > best_score:
            best = v
            best_score = score
    return best


def parse_pom_deps(pom_bytes: bytes):
    root = ET.fromstring(pom_bytes)
    ns = ""
    if root.tag.startswith("{"):
        ns = root.tag.split("}", 1)[0] + "}"
    packaging = root.findtext(f"{ns}packaging") or "jar"
    props = {}
    proj_ver = root.findtext(f"{ns}version")
    if not proj_ver:
        parent = root.find(f"{ns}parent")
        if parent is not None:
            proj_ver = parent.findtext(f"{ns}version")
    if proj_ver:
        props["project.version"] = proj_ver
    props_el = root.find(f"{ns}properties")
    if props_el is not None:
        for child in props_el:
            tag = child.tag.replace(ns, "")
            if child.text:
                props[tag] = child.text.strip()

    def subst(s: str | None) -> str | None:
        if not s:
            return s
        for k, v in props.items():
            s = s.replace("${" + k + "}", v)
        return s

    mgmt_constraints = []
    mgmt_el = root.find(f"{ns}dependencyManagement/{ns}dependencies")
    if mgmt_el is not None:
        for d in mgmt_el.findall(f"{ns}dependency"):
            g = subst(d.findtext(f"{ns}groupId"))
            a = subst(d.findtext(f"{ns}artifactId"))
            v = subst(d.findtext(f"{ns}version"))
            if g and a and v:
                mgmt_constraints.append((g.strip(), a.strip(), v.strip()))

    deps = []
    deps_el = root.find(f"{ns}dependencies")
    if deps_el is not None:
        for d in deps_el.findall(f"{ns}dependency"):
            scope = (d.findtext(f"{ns}scope") or "compile").strip()
            opt = (d.findtext(f"{ns}optional") or "false").strip().lower()
            if scope in ("test", "provided", "system") or opt == "true":
                continue
            g = subst(d.findtext(f"{ns}groupId"))
            a = subst(d.findtext(f"{ns}artifactId"))
            v = subst(d.findtext(f"{ns}version"))
            if g and a:
                deps.append((g.strip(), a.strip(), v.strip() if v else None))
    return packaging.strip(), deps, mgmt_constraints


def resolve_all(root_deps: list, cache_dir: Path, kotlin_version: str | None = None):
    constraints: dict[tuple[str, str], str] = {}
    if kotlin_version:
        constraints[("org.jetbrains.kotlin", "kotlin-stdlib")] = kotlin_version
        constraints[("org.jetbrains.kotlin", "kotlin-stdlib-common")] = kotlin_version
    resolved_ver: dict[tuple[str, str], str] = {}
    artifacts: dict[tuple[str, str], dict] = {}
    visited_gv: set[tuple[str, str, str, bool]] = set()
    pending_unversioned: set[tuple[str, str, bool]] = set()
    # Process platform BOMs before regular dependencies
    queue: list[tuple[str, str, str | None, bool]] = sorted(root_deps, key=lambda x: 0 if x[3] else 1)

    def apply_constraint(g: str, a: str, v: str):
        key = (g, a)
        if key not in constraints or is_higher_version(v, constraints[key]):
            constraints[key] = v
        if key in resolved_ver and is_higher_version(v, resolved_ver[key]):
            resolved_ver[key] = v
            queue.append((g, a, v, False))
        for pg, pa, p_plat in list(pending_unversioned):
            if (pg, pa) == key:
                pending_unversioned.discard((pg, pa, p_plat))
                queue.append((pg, pa, v, p_plat))

    while queue:
        # Always prioritize platform items in queue
        plat_idx = next((i for i, item in enumerate(queue) if item[3]), 0)
        g, a, req_v, is_platform = queue.pop(plat_idx)
        key = (g, a)
        v = req_v or constraints.get(key)
        if not v:
            pending_unversioned.add((g, a, is_platform))
            continue
        if key in constraints and is_higher_version(constraints[key], v):
            v = constraints[key]
        if key in resolved_ver:
            if is_higher_version(v, resolved_ver[key]):
                resolved_ver[key] = v
            else:
                v = resolved_ver[key]
        else:
            resolved_ver[key] = v

        if (g, a, v, is_platform) in visited_gv:
            continue
        visited_gv.add((g, a, v, is_platform))

        g_path = g.replace(".", "/")
        mod_rel = f"{g_path}/{a}/{v}/{a}-{v}.module"
        mod_res = fetch_from_repos(mod_rel, cache_dir)
        if mod_res is not None:
            mod_bytes, repo = mod_res
            mod = json.loads(mod_bytes.decode("utf-8"))
            variant = pick_module_variant(mod.get("variants", []), is_platform)
            if variant is None:
                continue

            for c in variant.get("dependencyConstraints", []):
                cg, ca = c["group"], c["module"]
                cv = normalize_version_spec(c.get("version", {}))
                if cv:
                    apply_constraint(cg, ca, cv)

            if "available-at" in variant:
                avail = variant["available-at"]
                queue.append((avail["group"], avail["module"], avail["version"], is_platform))
                continue

            files = []
            for f in variant.get("files", []):
                fname = f["name"]
                furl = f.get("url", fname)
                if fname.endswith((".jar", ".aar")):
                    files.append((f"{repo}/{g_path}/{a}/{v}/{furl}", fname))
            artifacts[key] = {"version": v, "files": files, "is_platform": is_platform}

            for d in variant.get("dependencies", []):
                dg, da = d["group"], d["module"]
                dv = normalize_version_spec(d.get("version", {}))
                d_cat = d.get("attributes", {}).get("org.gradle.category")
                d_plat = d_cat in ("platform", "enforced-platform")
                if dv:
                    apply_constraint(dg, da, dv)
                queue.append((dg, da, dv or constraints.get((dg, da)), d_plat))
        else:
            pom_rel = f"{g_path}/{a}/{v}/{a}-{v}.pom"
            pom_res = fetch_from_repos(pom_rel, cache_dir)
            if pom_res is None:
                raise RuntimeError(f"Could not resolve {g}:{a}:{v} from Google Maven or Maven Central")
            pom_bytes, repo = pom_res
            packaging, pom_deps, mgmt_constraints = parse_pom_deps(pom_bytes)
            for cg, ca, cv in mgmt_constraints:
                apply_constraint(cg, ca, cv)
            files = []
            if packaging in ("jar", "aar", "bundle"):
                ext = "aar" if packaging == "aar" else "jar"
                fname = f"{a}-{v}.{ext}"
                files.append((f"{repo}/{g_path}/{a}/{v}/{fname}", fname))
            artifacts[key] = {"version": v, "files": files, "is_platform": is_platform}
            for dg, da, dv in pom_deps:
                if dv:
                    apply_constraint(dg, da, dv)
                queue.append((dg, da, dv or constraints.get((dg, da)), False))

    # Filter artifacts to final winning versions and non-empty files
    final_files = []
    for key, info in sorted(artifacts.items()):
        if info["is_platform"]:
            continue
        if info["version"] != resolved_ver.get(key):
            continue
        for url, fname in info["files"]:
            final_files.append((key[0], key[1], info["version"], url, fname))
    return final_files


def download_and_extract(final_files: list, out_dir: Path, cache_dir: Path):
    art_dir = cache_dir / "artifacts"
    aar_root = out_dir / "aar"
    if aar_root.exists():
        shutil.rmtree(aar_root)
    aar_root.mkdir(parents=True, exist_ok=True)

    def dl(item):
        g, a, v, url, fname = item
        dest = art_dir / g / a / v / fname
        if not dest.exists() or dest.stat().st_size == 0:
            data = fetch_bytes(url, dest)
            if data is None:
                raise RuntimeError(f"Failed to download {url}")
        return g, a, v, dest

    with ThreadPoolExecutor(max_workers=8) as pool:
        downloaded = list(pool.map(dl, final_files))

    jars = []
    res_dirs = []
    extra_packages = []
    aar_manifests = []
    jni_dirs = []

    for g, a, v, path in downloaded:
        if path.suffix == ".jar":
            jars.append(str(path.resolve()))
        elif path.suffix == ".aar":
            extract_dir = aar_root / f"{g}_{a}_{v}"
            extract_dir.mkdir(parents=True, exist_ok=True)
            with zipfile.ZipFile(path, "r") as zf:
                zf.extractall(extract_dir)
            classes_jar = extract_dir / "classes.jar"
            if classes_jar.exists():
                jars.append(str(classes_jar.resolve()))
            libs_dir = extract_dir / "libs"
            if libs_dir.exists():
                for jar_p in sorted(libs_dir.glob("*.jar")):
                    jars.append(str(jar_p.resolve()))
            res_p = extract_dir / "res"
            if res_p.exists() and any(res_p.iterdir()):
                res_dirs.append(str(res_p.resolve()))
            jni_p = extract_dir / "jni"
            if jni_p.exists() and any(jni_p.iterdir()):
                jni_dirs.append(str(jni_p.resolve()))
            mf = extract_dir / "AndroidManifest.xml"
            if mf.exists():
                aar_manifests.append(mf)
                try:
                    pkg = ET.parse(mf).getroot().attrib.get("package")
                    if pkg:
                        extra_packages.append(pkg)
                except ET.ParseError:
                    pass

    return jars, res_dirs, sorted(set(extra_packages)), aar_manifests, jni_dirs


def merge_manifests(app_manifest: Path, aar_manifests: list[Path], meta: dict, out_manifest: Path):
    app_id = meta["applicationId"]
    raw_app = app_manifest.read_text(encoding="utf-8").replace("${applicationId}", app_id)
    app_root = ET.fromstring(raw_app)
    app_root.attrib["package"] = app_id

    uses_sdk = app_root.find("uses-sdk")
    if uses_sdk is None:
        uses_sdk = ET.Element("uses-sdk")
        app_root.insert(0, uses_sdk)
    uses_sdk.attrib[f"{{{ANDROID_NS}}}minSdkVersion"] = meta["minSdk"]
    uses_sdk.attrib[f"{{{ANDROID_NS}}}targetSdkVersion"] = meta["targetSdk"]

    existing_perms = {
        el.attrib.get(f"{{{ANDROID_NS}}}name")
        for el in app_root.findall("uses-permission")
    }
    app_el = app_root.find("application")
    if app_el is None:
        app_el = ET.SubElement(app_root, "application")

    existing_components = {}
    for tag in ("provider", "service", "receiver", "activity"):
        for el in app_el.findall(tag):
            name = el.attrib.get(f"{{{ANDROID_NS}}}name")
            if name:
                existing_components[(tag, name)] = el

    for mf in aar_manifests:
        raw = mf.read_text(encoding="utf-8").replace("${applicationId}", app_id)
        root = ET.fromstring(raw)
        for perm in root.findall("uses-permission"):
            pname = perm.attrib.get(f"{{{ANDROID_NS}}}name")
            if pname and pname not in existing_perms:
                existing_perms.add(pname)
                app_root.append(perm)

        lib_app = root.find("application")
        if lib_app is None:
            continue
        acf = lib_app.attrib.get(f"{{{ANDROID_NS}}}appComponentFactory")
        if acf and f"{{{ANDROID_NS}}}appComponentFactory" not in app_el.attrib:
            app_el.attrib[f"{{{ANDROID_NS}}}appComponentFactory"] = acf

        for tag in ("provider", "service", "receiver", "activity"):
            for comp in lib_app.findall(tag):
                cname = comp.attrib.get(f"{{{ANDROID_NS}}}name")
                if not cname:
                    continue
                if (tag, cname) not in existing_components:
                    existing_components[(tag, cname)] = comp
                    app_el.append(comp)
                else:
                    # Merge child <meta-data> elements (e.g. androidx.startup.InitializationProvider)
                    target_comp = existing_components[(tag, cname)]
                    existing_md = {
                        md.attrib.get(f"{{{ANDROID_NS}}}name")
                        for md in target_comp.findall("meta-data")
                    }
                    for md in comp.findall("meta-data"):
                        md_name = md.attrib.get(f"{{{ANDROID_NS}}}name")
                        if md_name and md_name not in existing_md:
                            existing_md.add(md_name)
                            target_comp.append(md)

    out_manifest.parent.mkdir(parents=True, exist_ok=True)
    ET.ElementTree(app_root).write(out_manifest, encoding="utf-8", xml_declaration=True)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--project-dir", default=".")
    parser.add_argument("--out-dir", default="build/deps")
    parser.add_argument("--cache-dir", default=".cli-cache/maven")
    args = parser.parse_args()

    proj = Path(args.project_dir)
    out_dir = Path(args.out_dir)
    cache_dir = Path(args.cache_dir)
    out_dir.mkdir(parents=True, exist_ok=True)
    cache_dir.mkdir(parents=True, exist_ok=True)

    versions, libraries = parse_versions_toml(proj / "gradle/libs.versions.toml")
    root_deps, meta = parse_app_gradle(proj / "app/build.gradle.kts", libraries)
    meta["kotlinVersion"] = versions.get("kotlin", "2.4.21")

    print(f"Resolving {len(root_deps)} root dependencies (Kotlin {meta['kotlinVersion']})...")
    final_files = resolve_all(root_deps, cache_dir, kotlin_version=meta["kotlinVersion"])
    print(f"Resolved {len(final_files)} Maven artifacts.")

    jars, res_dirs, extra_packages, aar_manifests, jni_dirs = download_and_extract(
        final_files, out_dir, cache_dir
    )
    merged_manifest = out_dir / "AndroidManifest.xml"
    merge_manifests(proj / "app/src/main/AndroidManifest.xml", aar_manifests, meta, merged_manifest)

    (out_dir / "classpath.txt").write_text("\n".join(jars) + "\n", encoding="utf-8")
    (out_dir / "res_dirs.txt").write_text("\n".join(res_dirs) + "\n", encoding="utf-8")
    (out_dir / "extra_packages.txt").write_text(":".join(extra_packages), encoding="utf-8")
    (out_dir / "jni_dirs.txt").write_text("\n".join(jni_dirs) + "\n", encoding="utf-8")
    (out_dir / "meta.json").write_text(json.dumps(meta, indent=2), encoding="utf-8")

    print(f"Wrote {len(jars)} classpath JARs, {len(res_dirs)} AAR res dirs, {len(extra_packages)} extra packages.")


if __name__ == "__main__":
    main()
